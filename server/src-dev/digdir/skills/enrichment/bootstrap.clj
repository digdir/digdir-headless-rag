(ns digdir.skills.enrichment.bootstrap
  "Phase B.5 — one-off enrichment driver.

   Lets the operator (or, in Phase C, the self-improve agent) say
   'enrich these chunk-ids with hypothetical questions' and have the
   full pipeline run: ensure the collection exists, fetch each chunk's
   content + doc context from Typesense, call
   `:builtin/enrichment-propose-questions`, then
   `:builtin/enrichment-apply-questions`.

   This namespace lives in `src-dev/` and is not registered as a skill
   itself — it's the harness that wires the skills together for a
   batch run. Phase C will reimplement this same control flow as a
   proper agent tool / sub-graph; for now a plain function keeps the
   smoke-test path short."
  (:require [digdir.api.context :as api-ctx]
            [digdir.pipeline.collections :as pipeline-coll]
            [digdir.rag.skills.core :as skills]
            [digdir.rag.typesense :as ts-utils]
            [digdir.skills.enrichment.collections :as enrich-coll]
            ;; Require skill nss so they self-register if the consumer
            ;; hasn't already pulled them in.
            [digdir.skills.enrichment.propose-questions]
            [digdir.skills.enrichment.apply-questions]
            [taoensso.timbre :as timbre]
            [typesense.client :as ts]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- fetch-chunk
  "Fetch one chunk's full document from Typesense by `chunk_id`.

   The chunks schema doesn't set its own `id` field, so Typesense
   auto-assigns one — we can't use `retrieve-document` directly. A
   `filter_by chunk_id:=<id>` search returns exactly the matching row."
  [settings chunks-collection chunk-id]
  (try
    (let [resp (ts/search settings chunks-collection
                          {:q "*"
                           :filter_by (str "chunk_id:=" chunk-id)
                           :per_page 1})
          hit (-> resp :hits first :document)]
      (when (nil? hit)
        (throw (ex-info (str "Chunk " chunk-id " not found in " chunks-collection)
                        {:chunk-id chunk-id
                         :collection chunks-collection})))
      hit)
    (catch clojure.lang.ExceptionInfo e
      (throw (ex-info (str "Failed to fetch chunk " chunk-id " from " chunks-collection)
                      {:chunk-id chunk-id
                       :collection chunks-collection
                       :cause (ex-data e)}
                      e)))))

(defn- fetch-doc-title
  "Best-effort doc-title lookup from the base docs collection. The agent
   prompt uses it as context; returning nil/blank just leaves the
   placeholder empty in the rendered prompt, which is fine.

   Searches by `doc_num` rather than the auto-assigned Typesense `id`
   so this works for any docs schema (website/episerver/folder)."
  [settings docs-collection doc-num]
  (when (and doc-num (seq (str doc-num)))
    (try
      (let [resp (ts/search settings docs-collection
                            {:q "*"
                             :filter_by (str "doc_num:=" doc-num)
                             :per_page 1})
            doc (-> resp :hits first :document)]
        (or (:title doc) (:name doc)))
      (catch Throwable t
        (timbre/warn t (str "bootstrap: fetch-doc-title failed for "
                            docs-collection " doc_num=" doc-num))
        nil))))

(defn- doc-url
  "If the chunk row carries a :url field (website pipelines do), surface
   it; otherwise nil. Used for the prompt's doc-url placeholder."
  [chunk-doc]
  (:url chunk-doc))

(defn- propose-one
  "Invoke the propose-questions skill for a single chunk. Returns a
   proposal map ready for the apply-questions skill, or throws."
  [tenant settings chunk-doc doc-title question-count]
  (let [chunk-id (:chunk_id chunk-doc)
        result (skills/execute-skill
                :builtin/enrichment-propose-questions
                {:inputs {:chunk-id chunk-id
                          :chunk-content (:content_markdown chunk-doc)
                          :doc-title doc-title
                          :doc-url (doc-url chunk-doc)}
                 :parameters {:question-count question-count}
                 :services {:typesense settings}
                 :skill-params {:tenant tenant}})]
    (if (skills/result-success? result)
      (let [out (skills/get-result-outputs result)]
        {:chunk-id chunk-id
         :doc-num (:doc_num chunk-doc)
         :questions (:questions out)
         :provenance (:provenance out)})
      (throw (ex-info "propose-questions failed"
                      {:chunk-id chunk-id
                       :error (:error result)
                       :result result})))))

;; =============================================================================
;; Public driver
;; =============================================================================

(defn enrich-chunks!
  "Run the propose+apply pipeline for `chunk-ids` against the
   hypothetical-questions enrichment collection for `dataset-ref`.

   Args:
     dataset-ref     — {:tenant ... :dataset-config-key ...}
     chunk-ids       — collection of chunk-id strings
     opts            — {:question-count 4
                        :dry-run? false}

   Returns a summary map: collection name, per-chunk question lists,
   apply skill result. Idempotent: re-running over the same chunk-ids
   replaces their rows in the enrichment collection."
  ([dataset-ref chunk-ids]
   (enrich-chunks! dataset-ref chunk-ids {}))
  ([dataset-ref chunk-ids {:keys [question-count dry-run?]
                           :or {question-count 4 dry-run? false}}]
   (let [{:keys [config dataset-config]} (api-ctx/resolve-dataset-context-by-ref! dataset-ref)
         pipeline-config (or config dataset-config)
         base-names (pipeline-coll/pipeline-collection-names pipeline-config)
         enrich-coll-name (enrich-coll/enrichment-collection-name
                           pipeline-config :hypothetical-questions)
         tenant (:tenant dataset-ref)
         settings (ts-utils/make-ts-settings {:tenant tenant})]
     (when-not settings
       (throw (ex-info "No Typesense settings for tenant" {:tenant tenant})))
     ;; 1) Ensure the enrichment collection exists.
     (enrich-coll/ensure-collection! pipeline-config :hypothetical-questions)
     ;; Stub services map. The skills resolve their own Typesense
     ;; settings internally — we just need the key present to pass
     ;; the execution-context validator.
     ;; 2) Fetch each chunk + propose questions.
     (let [proposals
           (vec
            (for [chunk-id chunk-ids]
              (let [chunk-doc (fetch-chunk settings (:chunks-collection base-names) chunk-id)
                    doc-title (fetch-doc-title settings
                                               (:docs-collection base-names)
                                               (:doc_num chunk-doc))]
                (propose-one tenant settings chunk-doc doc-title question-count))))
           ;; 3) Apply (batched delete-by-filter + upsert).
           apply-result (skills/execute-skill
                         :builtin/enrichment-apply-questions
                         {:inputs {:proposals proposals
                                   :collection-name enrich-coll-name}
                          :parameters {:dry-run? dry-run?}
                          :services {:typesense settings}
                          :skill-params {:tenant tenant}})]
       (if (skills/result-success? apply-result)
         {:dataset-ref dataset-ref
          :collection-name enrich-coll-name
          :docs-collection (:docs-collection base-names)
          :chunks-collection (:chunks-collection base-names)
          :proposals proposals
          :apply (skills/get-result-outputs apply-result)
          :dry-run? dry-run?}
         (throw (ex-info "apply-questions failed"
                         {:error (:error apply-result)})))))))

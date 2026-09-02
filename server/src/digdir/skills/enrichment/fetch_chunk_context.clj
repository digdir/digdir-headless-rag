(ns digdir.skills.enrichment.fetch-chunk-context
  "`:builtin/enrichment-fetch-chunk-context` — given a chunk-id, fetch
   the chunk's content + minimal document context from Typesense.

   Exists so the inner sub-graph of the self-improve experiment can
   start with `:chunk-id` only and let the structure do the lookup,
   rather than forcing the outer graph to pre-fetch and thread four
   inputs per chunk through the foreach.

   Lookups mirror the helpers in `digdir.demo.self-improve-agent` that
   the ReAct version uses (`fetch-chunk-content` + `fetch-doc-title`).
   Reads `:content_markdown` from the chunks collection and `:title` /
   `:url` from the docs collection.

   Lives in `src-dev/` alongside its siblings — offline tooling, not
   part of the runtime retrieval path."
  (:require [digdir.rag.skills.core :as skills]
            [digdir.rag.typesense :as ts-utils]
            [taoensso.timbre :as timbre]
            [typesense.client :as ts]))

;; =============================================================================
;; Metadata
;; =============================================================================

(def fetch-chunk-context-metadata
  {:skill-id :builtin/enrichment-fetch-chunk-context
   :name "Fetch chunk context for enrichment"
   :description "Look up a chunk's content and its document's title/URL from Typesense, given the chunk-id. Used by the self-improve graph as the first step of each per-chunk sub-graph iteration."
   :category :retrieval
   ;; Only :chunk-id and :chunks-collection are strictly required. The
   ;; skill body falls back to skill-params for :tenant and tolerates
   ;; missing :docs-collection (doc-title degrades to nil). Listing
   ;; optional inputs in :inputs would make the runner's validator
   ;; reject callers that elide them.
   :inputs [:chunk-id :chunks-collection]
   :outputs [:chunk-id :chunk-content :doc-num :doc-title :doc-url]
   :parameters {}
   :required-services #{:typesense}
   :version "1.0.0"
   :tags #{:typesense :enrichment :self-improve :graph-only}})

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- fetch-chunk
  "Fetch one chunk by chunk_id. Returns the document map or nil.
   Warn-logs on Typesense error so misconfig doesn't masquerade as
   'chunk not found' (which is what the caller does on nil)."
  [settings chunks-collection chunk-id]
  (try
    (let [resp (ts/search settings chunks-collection
                          {:q "*"
                           :filter_by (str "chunk_id:=" chunk-id)
                           :per_page 1})]
      (-> resp :hits first :document))
    (catch Throwable t
      (timbre/warn t (str "fetch-chunk-context: fetch-chunk failed for "
                          chunks-collection " chunk_id=" chunk-id))
      nil)))

(defn- fetch-doc-title
  "Fetch a document's title (and any other fields we care about) by
   doc_num. Returns the document map or nil. Wrapped because the docs
   collection may not be configured/reachable in all environments;
   missing doc title is acceptable degradation, missing chunk content
   is not. Warn-logs on Typesense error so a real failure is visible."
  [settings docs-collection doc-num]
  (when (and doc-num (seq (str doc-num)) docs-collection)
    (try
      (let [resp (ts/search settings docs-collection
                            {:q "*"
                             :filter_by (str "doc_num:=" doc-num)
                             :per_page 1})]
        (-> resp :hits first :document))
      (catch Throwable t
        (timbre/warn t (str "fetch-chunk-context: fetch-doc-title failed for "
                            docs-collection " doc_num=" doc-num))
        nil))))

;; =============================================================================
;; Skill body
;; =============================================================================

(defn execute-fetch-chunk-context
  "Fetch chunk content + doc title/URL for one chunk-id. Throws if the
   chunk-id can't be found — that's a hard error, the calling graph
   should not silently proceed with empty content. Missing doc title
   degrades gracefully (the propose-questions skill accepts nil)."
  [{:keys [inputs skill-params]}]
  (let [{:keys [chunk-id tenant chunks-collection docs-collection]} inputs
        effective-tenant (or tenant (:tenant skill-params))]
    (cond
      (or (nil? chunk-id) (and (string? chunk-id) (empty? chunk-id)))
      (throw (ex-info "Missing :chunk-id input"
                      {:skill-id :builtin/enrichment-fetch-chunk-context}))

      (or (nil? chunks-collection) (and (string? chunks-collection) (empty? chunks-collection)))
      (throw (ex-info "Missing :chunks-collection input"
                      {:skill-id :builtin/enrichment-fetch-chunk-context}))

      :else
      (let [settings (ts-utils/make-ts-settings
                      (when effective-tenant {:tenant effective-tenant}))
            _ (when (nil? settings)
                (throw (ex-info "No Typesense settings — tenant missing or unconfigured"
                                {:tenant effective-tenant})))
            chunk (fetch-chunk settings chunks-collection chunk-id)]
        (when-not chunk
          (throw (ex-info "Chunk not found"
                          {:chunk-id chunk-id
                           :chunks-collection chunks-collection})))
        (let [doc (fetch-doc-title settings docs-collection (:doc_num chunk))]
          (skills/success-result
           {:chunk-id chunk-id
            :chunk-content (:content_markdown chunk)
            :doc-num (:doc_num chunk)
            :doc-title (:title doc)
            :doc-url (:url chunk)}
           {:tenant effective-tenant
            :found-doc? (some? doc)}))))))

;; =============================================================================
;; Registration
;; =============================================================================

(def fetch-chunk-context-skill
  {:metadata fetch-chunk-context-metadata
   :execute execute-fetch-chunk-context})

(defn register!
  "Register the fetch-chunk-context skill. Idempotent."
  []
  (skills/register-skill! fetch-chunk-context-skill))

(register!)

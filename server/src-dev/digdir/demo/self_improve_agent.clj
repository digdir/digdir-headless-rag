(ns digdir.demo.self-improve-agent
  "Phase C — `:docs/self-improve-agent`.

   An offline meta-agent that uses the Phase A eval-suite skill and
   the Phase B propose/apply skills to systematically grow the
   hypothetical-questions enrichment collection. Its goal is to
   maximize the eval pass-rate delta per chunk it enriches.

   The agent runs over `builtin/agent-rag` (so it inherits the same
   search/read tools the user-facing agent has) and adds four ReAct
   tools wired to the enrichment skill set:

     analyze_corpus              — corpus stats + sample chunks
     propose_questions_for_chunk — wraps :builtin/enrichment-propose-questions
     apply_enrichments           — wraps :builtin/enrichment-apply-questions
     run_eval_delta              — wraps :builtin/enrichment-eval-suite

   This lives in `src-dev/` because the enrichment skills it depends
   on do too. Production builds without src-dev on the classpath just
   skip seeding it (see digdir.config.db's try/require pattern).

   Phase D will add propose_verified_phrases_for_chunk,
   propose_facts_for_chunk, propose_kg_node_for_doc tools as the
   matching skills land."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [digdir.rag.skills.core :as skills]
            [digdir.rag.typesense :as ts-utils]
            [digdir.skills.builtin.agent.tools :as agent-tools]
            [digdir.skills.enrichment.collections :as enrich-coll]
            ;; Force skill registration on namespace load.
            [digdir.skills.enrichment.apply-questions]
            [digdir.skills.enrichment.eval-delta]
            [digdir.skills.enrichment.propose-questions]
            [typesense.client :as ts]
            [taoensso.timbre :as timbre]))

;; =============================================================================
;; Shared resolution helpers
;; =============================================================================

(defn- resolve-tenant
  "Standard ambient-ctx → tenant resolution, matching the pattern used
   in `digdir.demo.altinn-authoring`. Tools fall through several keys
   because ambient-ctx shape varies a bit depending on whether the
   tool runs via a dataset-bound binding."
  [ambient-ctx]
  (or (get-in ambient-ctx [:opts :tenant])
      (:tenant ambient-ctx)
      (get-in ambient-ctx [:skill-params :tenant])
      "digdir"))

(defn- resolve-dataset-config-key
  [ambient-ctx]
  (or (get-in ambient-ctx [:opts :dataset-config-key])
      (get-in ambient-ctx [:dataset-ref :dataset-config-key])
      "public-docs"))

(defn- resolve-tenant-config-key
  "Tenant-config-key identifies a tenant-scope config-tree node (not a
   dataset). Defaults to \"default\" -- the standard tenant config node
   used by `bb agent-budget-benchmark digdir dataset public-docs
   --tenant-config-key default`. Earlier this fell back to
   `dataset-config-key`, which caused diagnostics' config lookup to
   search for a node named \"public-docs\" under tenant root \"runtime\"
   and throw \"runtime config node not found\"."
  [ambient-ctx]
  (or (get-in ambient-ctx [:opts :tenant-config-key])
      "default"))

(defn- resolve-runtime-config-key
  "Runtime-config-key falls back to tenant-config-key (which defaults to
   \"default\"). Same shape diagnostics' agent-budget-benchmark accepts."
  [ambient-ctx]
  (or (get-in ambient-ctx [:opts :runtime-config-key])
      (resolve-tenant-config-key ambient-ctx)))

(defn- json-out
  "Render the tool result as a compact JSON object the LLM can parse.
   Errors come back as `{:error ...}` so the agent's tool-result
   reader can branch on it."
  [m]
  (json/write-str m :escape-slash false))

(defn- derive-enrichment-collection-name
  "Fallback: derive the enrichment collection name from the
   docs-collection name when pipeline-config isn't reachable from
   ambient-ctx. Phase B.1's naming invariant is
   `{prefix}documents_{hash}` for docs and
   `{prefix}enrichment_hypothetical_questions_{hash}` for enrichment,
   so we just swap the `documents_` segment.

   Returns nil (not the original) when the input doesn't match the
   invariant — better to surface a missing-collection error than to
   silently target a wrong-looking name."
  [docs-collection]
  (when (and docs-collection (seq docs-collection))
    (let [replaced (str/replace docs-collection
                                #"documents_(?=[a-f0-9]+$)"
                                "enrichment_hypothetical_questions_")]
      (when (not= replaced docs-collection)
        replaced))))

(defn- resolve-enrichment-collection-name
  "Prefer pipeline-config when available (most authoritative — same
   path Phase B uses everywhere). Fall back to deriving from
   docs-collection if the agent's ambient-ctx didn't thread
   pipeline-config through."
  [ambient-ctx]
  (let [pipeline-config (or (get-in ambient-ctx [:opts :pipeline-config])
                            (:pipeline-config ambient-ctx))
        docs-coll (or (:docs-collection ambient-ctx)
                      (get-in ambient-ctx [:opts :docs-collection]))]
    (or (when pipeline-config
          (enrich-coll/enrichment-collection-name
           pipeline-config :hypothetical-questions))
        (derive-enrichment-collection-name docs-coll))))

(defn- safe-execute
  "Wraps a tool body so any thrown exception comes back as a JSON
   error string instead of bubbling through the agent loop. The ReAct
   loop only sees a string from a tool; throwing here would surface as
   a generic 'tool call failed' to the LLM and lose the actual cause."
  [tool-name f]
  (try
    (f)
    (catch Throwable t
      (timbre/warn t (str "self-improve-agent " tool-name " failed"))
      (json-out {:tool tool-name
                 :error (str (.getMessage t))
                 :error-type (str (type t))}))))

;; =============================================================================
;; Tool 1 — analyze_corpus
;; =============================================================================

(def analyze-corpus-tool-spec
  {:type "function"
   :function
   {:name "analyze_corpus"
    :description (str "Report corpus-level stats (total chunks/docs) and "
                      "return a sample of chunk_ids with title + content_length. "
                      "Use this at the start of a session to orient. Does NOT "
                      "read chunk contents — use the search_documents / "
                      "read_chunks tools for that.")
    :parameters
    {:type "object"
     :properties
     {:sample_size {:type "integer"
                    :description "How many sample chunks to return (default 10, max 50)."}}}}})

(defn- typesense-collection-stats
  "Fetch document count + a sampled set of rows from a Typesense
   collection. Done as a plain `*`-search with a small per_page rather
   than a full export so we don't move megabytes for an analysis turn."
  [settings collection-name sample-size]
  (let [resp (ts/search settings collection-name
                        {:q "*"
                         :per_page (min (max 1 sample-size) 50)
                         :page 1})]
    {:found (:found resp)
     :hits (mapv :document (:hits resp))}))

(defn execute-analyze-corpus-tool
  [args _!workspace ambient-ctx]
  (safe-execute
   "analyze_corpus"
   (fn []
     (let [sample-size (or (:sample_size args) (get args "sample_size") 10)
           tenant (resolve-tenant ambient-ctx)
           docs-coll (or (:docs-collection ambient-ctx)
                         (get-in ambient-ctx [:opts :docs-collection]))
           chunks-coll (or (:chunks-collection ambient-ctx)
                           (get-in ambient-ctx [:opts :chunks-collection]))
           settings (ts-utils/make-ts-settings {:tenant tenant})
           docs-stats (typesense-collection-stats settings docs-coll 1)
           chunk-stats (typesense-collection-stats settings chunks-coll sample-size)
           ;; Best-effort: count existing enrichment rows so the agent
           ;; knows how much work has already happened. Uses the same
           ;; resolver as apply_enrichments, which falls back to
           ;; deriving the name from the docs-collection when
           ;; pipeline-config isn't reachable from ambient-ctx.
           enrich-coll-name (try (resolve-enrichment-collection-name ambient-ctx)
                                 (catch Throwable _ nil))
           enrich-found (when enrich-coll-name
                          (try (:found (typesense-collection-stats settings enrich-coll-name 1))
                               (catch Throwable _ 0)))]
       (json-out
        {:tool "analyze_corpus"
         :docs-collection docs-coll
         :chunks-collection chunks-coll
         :enrichment-collection enrich-coll-name
         :total-docs (:found docs-stats)
         :total-chunks (:found chunk-stats)
         :existing-enrichment-rows enrich-found
         :sample-chunks
         (mapv (fn [c]
                 {:chunk_id (:chunk_id c)
                  :doc_num (:doc_num c)
                  :chunk_index (:chunk_index c)
                  :content_length (:content_length c)
                  :title (:title c)})
               (:hits chunk-stats))})))))

;; =============================================================================
;; Tool 2 — propose_questions_for_chunk
;; =============================================================================

(def propose-questions-tool-spec
  {:type "function"
   :function
   {:name "propose_questions_for_chunk"
    :description (str "Generate K (default 4) hypothetical search "
                      "questions for one chunk. Returns the proposed "
                      "questions plus provenance (model, prompt-hash). "
                      "Does NOT write to Typesense — use "
                      "apply_enrichments to commit chosen proposals.")
    :parameters
    {:type "object"
     :required ["chunk_id"]
     :properties
     {:chunk_id {:type "string"
                 :description "ID of the chunk to enrich. Get this from analyze_corpus or search_documents."}
      :question_count {:type "integer"
                       :description "Number of questions to generate (default 4, max 8)."}}}}})

(defn- fetch-chunk-content
  "Helper: pull the chunk's content from Typesense by chunk_id."
  [settings chunks-collection chunk-id]
  (let [resp (ts/search settings chunks-collection
                        {:q "*"
                         :filter_by (str "chunk_id:=" chunk-id)
                         :per_page 1})]
    (-> resp :hits first :document)))

(defn- fetch-doc-title
  [settings docs-collection doc-num]
  (when (and doc-num (seq (str doc-num)))
    (try
      (let [resp (ts/search settings docs-collection
                            {:q "*"
                             :filter_by (str "doc_num:=" doc-num)
                             :per_page 1})]
        (-> resp :hits first :document :title))
      (catch Throwable _ nil))))

(defn execute-propose-questions-tool
  [args _!workspace ambient-ctx]
  (safe-execute
   "propose_questions_for_chunk"
   (fn []
     (let [chunk-id (or (:chunk_id args) (get args "chunk_id"))
           q-count (or (:question_count args) (get args "question_count") 4)
           tenant (resolve-tenant ambient-ctx)
           docs-coll (or (:docs-collection ambient-ctx)
                         (get-in ambient-ctx [:opts :docs-collection]))
           chunks-coll (or (:chunks-collection ambient-ctx)
                           (get-in ambient-ctx [:opts :chunks-collection]))
           settings (ts-utils/make-ts-settings {:tenant tenant})
           chunk-doc (fetch-chunk-content settings chunks-coll chunk-id)]
       (if-not chunk-doc
         (json-out {:tool "propose_questions_for_chunk"
                    :error (str "chunk_id " chunk-id " not found in " chunks-coll)})
         (let [doc-title (fetch-doc-title settings docs-coll (:doc_num chunk-doc))
               result (skills/execute-skill
                       :builtin/enrichment-propose-questions
                       {:inputs {:chunk-id chunk-id
                                 :chunk-content (:content_markdown chunk-doc)
                                 :doc-title doc-title
                                 :doc-url (:url chunk-doc)}
                        :parameters {:question-count (min q-count 8)}
                        :services {:typesense settings}
                        :skill-params {:tenant tenant}})]
           (if (skills/result-success? result)
             (let [out (skills/get-result-outputs result)]
               (json-out
                {:tool "propose_questions_for_chunk"
                 :chunk_id chunk-id
                 :doc_num (:doc_num chunk-doc)
                 :doc_title doc-title
                 :questions (:questions out)
                 :provenance (:provenance out)
                 :next "Call apply_enrichments with these proposals to commit them."}))
             (json-out
              {:tool "propose_questions_for_chunk"
               :chunk_id chunk-id
               :error (get-in result [:error :error-message] "unknown")}))))))))

;; =============================================================================
;; Tool 3 — apply_enrichments
;; =============================================================================

(def apply-enrichments-tool-spec
  {:type "function"
   :function
   {:name "apply_enrichments"
    :description (str "Write a batch of proposed enrichments to the "
                      "parallel Typesense collection. Idempotent on "
                      "chunk_id: re-applying replaces prior rows. Pass "
                      "the proposals returned by propose_questions_for_chunk.")
    :parameters
    {:type "object"
     :required ["proposals"]
     :properties
     {:proposals
      {:type "array"
       :description "Each item: {chunk_id, doc_num, questions: [string], provenance: {model, prompt_hash, generated_at_ms}}"
       :items
       {:type "object"
        :required ["chunk_id" "questions"]
        :properties
        {:chunk_id {:type "string"}
         :doc_num {:type "string"}
         :questions {:type "array" :items {:type "string"}}
         :provenance {:type "object"}}}}
      :dry_run
      {:type "boolean"
       :description "If true, report what would be written without touching Typesense."}}}}})

(defn- json-arg->keyword-map
  "The LLM passes nested JSON; arg keys are strings. Normalize a
   proposal map to the kebab-keyword shape the apply-questions skill
   expects (`:chunk-id :doc-num :questions :provenance` with
   `:provenance` having :model / :prompt-hash / :generated-at-ms)."
  [p]
  (let [chunk-id (or (get p :chunk_id) (get p "chunk_id"))
        doc-num (or (get p :doc_num) (get p "doc_num"))
        questions (or (get p :questions) (get p "questions") [])
        prov (or (get p :provenance) (get p "provenance") {})
        prov* {:model (or (get prov :model) (get prov "model"))
               :prompt-hash (or (get prov :prompt-hash)
                                (get prov "prompt_hash")
                                (get prov "prompt-hash"))
               :generated-at-ms (or (get prov :generated-at-ms)
                                    (get prov "generated_at_ms")
                                    (get prov "generated-at-ms"))}]
    {:chunk-id chunk-id
     :doc-num doc-num
     :questions (vec questions)
     :provenance (into {} (remove (comp nil? val)) prov*)}))

(defn execute-apply-enrichments-tool
  [args _!workspace ambient-ctx]
  (safe-execute
   "apply_enrichments"
   (fn []
     (let [raw-proposals (or (:proposals args) (get args "proposals") [])
           proposals (mapv json-arg->keyword-map raw-proposals)
           dry-run? (or (:dry_run args) (get args "dry_run") false)
           tenant (resolve-tenant ambient-ctx)
           coll-name (resolve-enrichment-collection-name ambient-ctx)
           settings (ts-utils/make-ts-settings {:tenant tenant})]
       (cond
         (empty? proposals)
         (json-out {:tool "apply_enrichments"
                    :error "No proposals supplied"})

         (nil? coll-name)
         (json-out {:tool "apply_enrichments"
                    :error "Could not resolve enrichment collection — pipeline-config missing from ambient-ctx"})

         :else
         (let [;; Ensure collection exists before applying. Idempotent.
               pipeline-config (or (get-in ambient-ctx [:opts :pipeline-config])
                                   (:pipeline-config ambient-ctx))
               _ (when pipeline-config
                   (try (enrich-coll/ensure-collection!
                         pipeline-config :hypothetical-questions)
                        (catch Throwable t
                          (timbre/warn t "ensure-collection! failed; will rely on existing collection"))))
               result (skills/execute-skill
                       :builtin/enrichment-apply-questions
                       {:inputs {:proposals proposals
                                 :collection-name coll-name}
                        :parameters {:dry-run? dry-run?}
                        :services {:typesense settings}
                        :skill-params {:tenant tenant}})]
           (if (skills/result-success? result)
             (let [out (skills/get-result-outputs result)]
               (json-out
                {:tool "apply_enrichments"
                 :collection-name coll-name
                 :applied-count (:applied-count out)
                 :chunk_ids (:chunk-ids out)
                 :dry_run dry-run?}))
             (json-out
              {:tool "apply_enrichments"
               :error (get-in result [:error :error-message] "unknown")}))))))))

;; =============================================================================
;; Tool 4 — run_eval_delta
;; =============================================================================

(def run-eval-delta-tool-spec
  {:type "function"
   :function
   {:name "run_eval_delta"
    :description (str "Run an agent-budget-benchmark eval suite against "
                      "the current corpus state. EXPENSIVE — takes minutes "
                      "of wall time and several dollars of LLM spend per "
                      "call. Use SPARINGLY (1-2 times per session). Returns "
                      "the summary {cases, current-pass, relaxed-pass, "
                      "gate-pass, error-count}. The summary IS the reward "
                      "signal: compare across runs to see if your enrichments "
                      "moved pass rate.")
    :parameters
    {:type "object"
     :properties
     {:suite_file
      {:type "string"
       :description "Path to the eval suite EDN, relative to server/. Defaults to test/fixtures/agent/altinn3_lansert_stability.edn (the fastest fixture)."}
      :enrichment_search_targets
      {:type "boolean"
       :description "When true, the eval runs with :enrichment-search-targets pointing at the hypothetical-questions collection. When false, runs the baseline (no enrichment)."}
      :graph_variant
      {:type "string"
       :description "Graph variant: bundled (default) | imperative | faithful."}}}}})

(defn execute-run-eval-delta-tool
  [args _!workspace ambient-ctx]
  (safe-execute
   "run_eval_delta"
   (fn []
     (let [suite-file (or (:suite_file args) (get args "suite_file")
                          "test/fixtures/agent/altinn3_lansert_stability.edn")
           use-enrichment? (boolean (or (:enrichment_search_targets args)
                                        (get args "enrichment_search_targets")))
           graph-variant (some-> (or (:graph_variant args) (get args "graph_variant"))
                                  keyword)
           tenant (resolve-tenant ambient-ctx)
           dataset-config-key (resolve-dataset-config-key ambient-ctx)
           tenant-config-key (resolve-tenant-config-key ambient-ctx)
           runtime-config-key (resolve-runtime-config-key ambient-ctx)
           ;; Build cli-opts as the eval-suite skill expects.
           coll-name (when use-enrichment?
                       (resolve-enrichment-collection-name ambient-ctx))
           extra-retrieval-params (when (and use-enrichment? coll-name)
                                    (pr-str
                                     {:enrichment-search-targets
                                      {:hypothetical-questions coll-name}}))
           inputs (cond-> {:suite-file suite-file
                           :tenant tenant
                           :dataset-config-key dataset-config-key
                           :tenant-config-key tenant-config-key
                           :runtime-config-key runtime-config-key
                           :graph-variant (or graph-variant :bundled)
                           :agent-id "builtin/agent-rag-agent"
                           :fail-on-gate? false
                           :progress? false}
                    extra-retrieval-params
                    (assoc :retrieval-params extra-retrieval-params))
           result (skills/execute-skill
                   :builtin/enrichment-eval-suite
                   {:inputs inputs
                    :parameters {}
                    :services {:typesense (ts-utils/make-ts-settings {:tenant tenant})}
                    :skill-params {:tenant tenant
                                   :dataset-config-key dataset-config-key
                                   :tenant-config-key tenant-config-key
                                   :runtime-config-key runtime-config-key}})]
       (if (skills/result-success? result)
         (let [out (skills/get-result-outputs result)]
           (json-out
            {:tool "run_eval_delta"
             :suite suite-file
             :enrichment-active? use-enrichment?
             :summary (:summary out)
             :gate-failed? (boolean (:gate-failed? out))
             :note "Compare current/relaxed-pass and error-count vs. previous runs to gauge improvement."}))
         (json-out
          {:tool "run_eval_delta"
           :error (get-in result [:error :error-message] "unknown")}))))))

;; =============================================================================
;; Agent definition
;; =============================================================================

;; =============================================================================
;; Registration
;; =============================================================================
;; The ReAct self-improve-agent was removed in Phase 0 — graph variants
;; under :builtin/docs-agent replaced it. The ReAct tools below remain
;; registered in case any future ReAct-shaped agent wants to call them.

(defn register!
  "Register the four ReAct tools with the agent loop. Idempotent."
  []
  (agent-tools/register-tool! "analyze_corpus"
                              analyze-corpus-tool-spec
                              execute-analyze-corpus-tool)
  (agent-tools/register-tool! "propose_questions_for_chunk"
                              propose-questions-tool-spec
                              execute-propose-questions-tool)
  (agent-tools/register-tool! "apply_enrichments"
                              apply-enrichments-tool-spec
                              execute-apply-enrichments-tool)
  (agent-tools/register-tool! "run_eval_delta"
                              run-eval-delta-tool-spec
                              execute-run-eval-delta-tool))

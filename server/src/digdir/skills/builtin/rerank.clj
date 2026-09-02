(ns digdir.skills.builtin.rerank
  "Rerank skill - wraps ColBERT reranking function.

   This skill reranks retrieved chunks by semantic relevance
   using the ColBERT reranker API."
  (:require [digdir.rag.core :as rag]
            [digdir.rag.skills.core :as skills]
            [clojure.string :as str]))

;; =============================================================================
;; Tool Definition (for agent invocation)
;; =============================================================================

(def rerank-tool-definition
  "Tool definition for agent-based skill invocation."
  {:type "function"
   :function
   {:name "rerank"
    :description "Rerank retrieved chunks by semantic relevance to the query"
    :parameters
    {:type "object"
     :properties
     {:chunk_ids
      {:type "array"
       :items {:type "string"}
       :description "IDs of chunks to rerank (from previous retrieval)"}
      :query
      {:type "string"
       :description "Query to rank relevance against"}
      :top_k
      {:type "integer"
       :description "Maximum number of chunks to return"
       :default 10}}
     :required ["chunk_ids" "query"]}}})

;; =============================================================================
;; Skill Metadata
;; =============================================================================

(def rerank-metadata
  {:skill-id :builtin/rerank
   :name "Chunk Reranking"
   :description "Rerank retrieved chunks by semantic relevance using ColBERT"
   :category :reranking
   :inputs [:chunks :query :docs-collection]
   :outputs [:chunks :context-docs]
   :parameters {:top-k :number
                :max-chunk-length :number
                :windowing :boolean
                :max-total-length :number
                :context-top-k :number
                :context-min-chunks :number
                :context-relative-score-threshold :number
                :context-max-chunk-length :number
                :max-context-length :number
                :enabled :boolean}
   :version "1.0.0"
   :tags #{:colbert :reranking :production}
   :tool-definition rerank-tool-definition})

;; =============================================================================
;; Skill Implementation
;; =============================================================================


(defn build-context-from-chunks
  "Build context docs from already-ranked chunks without reranking.
   Preserve full chunk content; rely on read selection and chunk count limits
   rather than snippet truncation."
  [chunks docs-collection {:keys [context-top-k max-context-length]}]
  (let [context-top-k (or context-top-k 10)
        max-context-length (or max-context-length 8000)
        seen (atom #{})
        docs-length (atom 0)
        loaded-docs (atom [])
        used-chunks (atom [])]
    (doseq [chunk chunks
            :while (and (< @docs-length max-context-length)
                        (< (count @loaded-docs) context-top-k))]
      (let [chunk-id (:chunk_id chunk)
            doc-md (:content_markdown chunk)]
        (when (and chunk-id doc-md (not (contains? @seen chunk-id)))
          (swap! seen conj chunk-id)
          (let [title (get-in chunk [(keyword docs-collection) :title])
                metadata (:metadata chunk)
                metadata-str (when metadata (rag/format-metadata-headers metadata))
                source-desc (str "\n```\nTitle: " title
                                 (when metadata-str (str "\n" metadata-str))
                                 "\n```\n\n")
                clean-md (str/replace (or doc-md "") #"\{(\d+)\}" "")
                full-content (str source-desc clean-md)]
            (when (< @docs-length max-context-length)
              (swap! docs-length + (count full-content))
              (swap! loaded-docs conj {:page_content full-content
                                       :metadata {:source chunk-id}})
              (swap! used-chunks conj chunk)))))
    {:chunks (vec @used-chunks)
     :context-docs (vec @loaded-docs)
     :context-length @docs-length})))

(defn execute-rerank
  "Execute the rerank skill.

   Inputs:
     :chunks - Vector of chunk maps from retrieval
     :query - User query for reranking relevance
     :docs-collection - Collection name (for title lookup)

   Parameters:
     :top-k - Max chunks to rerank (default 40)
     :max-chunk-length - Max length per chunk for reranking (default 2000)
     :windowing - Lever A: hand the reranker the best-matching passage window
                  of each chunk instead of head+tail truncation (default true)
     :max-total-length - Max total length for reranking (default 10000)
     :context-top-k - Max chunks to return (default 10)
     :context-min-chunks - Minimum chunks to keep when adaptive score threshold is enabled (default = context-top-k)
     :context-relative-score-threshold - Keep chunks with score >= threshold * top-score (optional, e.g. 0.85)
     :context-max-chunk-length - Max length per chunk in context (default 1000)
     :max-context-length - Max total context length (default 8000)
     :enabled - Whether to rerank (default true)

   Returns:
     :chunks - Vector of reranked chunk maps
     :context-docs - Vector of doc content for context assembly"
  [{:keys [inputs parameters skill-params] :as _ctx}]
  (let [{:keys [chunks query docs-collection]} inputs
        {:keys [top-k max-chunk-length max-total-length windowing
                context-top-k context-min-chunks context-relative-score-threshold
                context-max-chunk-length max-context-length enabled]
         ;; Lever A, promoted on the retrieval path 2026-06-03 and matched here
         ;; (#455): rerank passage windowing ON, window budget 2000. Both halves
         ;; default together because the window budget IS :max-chunk-length —
         ;; windowing at 1000 is a third regime, neither the validated arm nor
         ;; the historical baseline. Deliberately the same `:or` construct as
         ;; digdir.skills.builtin.retrieval so the two call paths into
         ;; digdir.rag.rerank/rerank-chunks resolve the decision identically;
         ;; digdir.skills.builtin.rerank-regime-parity-test pins that. To
         ;; reproduce the historical pre-Lever-A baseline, pass BOTH
         ;; {:windowing false :max-chunk-length 1000}.
         :or {windowing true
              max-chunk-length 2000}} parameters
        enabled? (not= false enabled)
        context-max-chunk-length (or context-max-chunk-length max-chunk-length 1000)
        max-context-length (or max-context-length 8000)

        ;; Build params map expected by rerank-chunks. `:tenant` propagated
        ;; from skill-params so the downstream cfg/get calls in
        ;; digdir.rag.rerank can locate ColBERT credentials. Without it,
        ;; normalize-platform-opts throws on a nil tenant.
        rerank-params {:tenant (:tenant skill-params)
                       :translated_user_query query
                       :docsCollectionName docs-collection
                       :rerankTopkChunks (or top-k 40)
                       :rerankMaxChunkLength max-chunk-length
                       :rerankWindowing windowing
                       :rerankMaxLength (or max-total-length 10000)
                       :contextTopkChunks (or context-top-k 10)
                       :contextMinChunks context-min-chunks
                       :contextRelativeScoreThreshold context-relative-score-threshold
                       :contextMaxChunkLength context-max-chunk-length
                       :maxContextLength max-context-length}]

    (if (not enabled?)
      (let [{ranked-chunks :chunks
             :keys [context-docs context-length]}
            (build-context-from-chunks chunks docs-collection
                                      {:context-top-k (or context-top-k 10)
                                       :context-max-chunk-length context-max-chunk-length
                                       :max-context-length max-context-length
                                       :max-chunk-length max-chunk-length})]
        (skills/success-result
          {:chunks (vec ranked-chunks)
           :context-docs (vec context-docs)}
          {:input-chunk-count (count chunks)
           :output-chunk-count (count ranked-chunks)
           :rerank-disabled true
           :context-length context-length}))

      ;; Execute reranking
      (let [{:keys [used-chunks used-docs]} (rag/rerank-chunks chunks rerank-params)]
        (skills/success-result
          {:chunks (vec used-chunks)
           :context-docs (vec used-docs)}
          {:input-chunk-count (count chunks)
           :output-chunk-count (count used-chunks)})))))

;; =============================================================================
;; Skill Registration
;; =============================================================================

(def rerank-skill
  {:metadata rerank-metadata
   :execute execute-rerank})

(defn register!
  "Register the rerank skill."
  []
  (skills/register-skill! rerank-skill))

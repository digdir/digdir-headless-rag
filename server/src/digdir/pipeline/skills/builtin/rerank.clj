(ns digdir.pipeline.skills.builtin.rerank
  "Rerank skill - wraps ColBERT reranking function.

   This skill reranks retrieved chunks by semantic relevance
   using the ColBERT reranker API."
  (:require [digdir.rag.core :as rag]
            [digdir.rag.skills.core :as skills]
            [digdir.pipeline.skills.context :as ctx]))

;; =============================================================================
;; Skill Metadata
;; =============================================================================

(def rerank-metadata
  {:skill-id :builtin/rerank
   :name "Chunk Reranking"
   :description "Rerank retrieved chunks by semantic relevance using ColBERT"
   :category :reranking
   :inputs [:chunks :query :docs-collection]
   :outputs [:reranked-chunks :context-docs]
   :parameters {:top-k :number
                :max-chunk-length :number
                :max-total-length :number
                :context-top-k :number
                :max-context-length :number}
   :required-services #{:colbert}
   :version "1.0.0"
   :tags #{:colbert :reranking :production}})

;; =============================================================================
;; Skill Implementation
;; =============================================================================

(defn execute-rerank
  "Execute the rerank skill.

   Inputs:
     :chunks - Vector of chunk maps from retrieval
     :query - User query for reranking relevance
     :docs-collection - Collection name (for title lookup)

   Parameters:
     :top-k - Max chunks to rerank (default 40)
     :max-chunk-length - Max length per chunk for reranking (default 1000)
     :max-total-length - Max total length for reranking (default 10000)
     :context-top-k - Max chunks to return (default 10)
     :max-context-length - Max total context length (default 8000)

   Returns:
     :reranked-chunks - Vector of reranked chunk maps
     :context-docs - Vector of doc content for context assembly"
  [{:keys [inputs parameters] :as ctx}]
  (let [{:keys [chunks query docs-collection]} inputs
        {:keys [top-k max-chunk-length max-total-length
                context-top-k max-context-length]} parameters

        ;; Build params map expected by rerank-chunks
        rerank-params {:translated_user_query query
                       :docsCollectionName docs-collection
                       :rerankTopkChunks (or top-k 40)
                       :rerankMaxChunkLength (or max-chunk-length 1000)
                       :rerankMaxLength (or max-total-length 10000)
                       :contextTopkChunks (or context-top-k 10)
                       :contextMaxChunkLength (or max-chunk-length 1000)
                       :maxContextLength (or max-context-length 8000)}

        ;; Execute reranking
        {:keys [used-chunks used-docs]} (rag/rerank-chunks chunks rerank-params)]

    (skills/success-result
      {:reranked-chunks (vec used-chunks)
       :context-docs (vec used-docs)}
      {:input-chunk-count (count chunks)
       :output-chunk-count (count used-chunks)})))

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

(ns digdir.pipeline.skills.builtin.retrieval
  "Retrieval skill - wraps TypeSense search functions.

   This skill performs multi-strategy document retrieval:
   1. Search phrase similarity matching
   2. Metadata-based search
   3. Content-based search

   Results are merged and deduplicated."
  (:require [digdir.rag.core :as rag]
            [digdir.rag.skills.core :as skills]
            [digdir.pipeline.skills.context :as ctx]))

;; =============================================================================
;; Skill Metadata
;; =============================================================================

(def retrieval-metadata
  {:skill-id :builtin/retrieval
   :name "Document Retrieval"
   :description "Multi-strategy document retrieval from TypeSense collections"
   :category :retrieval
   :inputs [:queries :docs-collection :chunks-collection :phrases-collection]
   :outputs [:chunks :search-attribution]
   :parameters {:limit :number
                :filter-by :map}
   :required-services #{:typesense}
   :version "1.0.0"
   :tags #{:typesense :search :production}})

;; =============================================================================
;; Skill Implementation
;; =============================================================================

(defn execute-retrieval
  "Execute the retrieval skill.

   Inputs:
     :queries - Vector of search query strings, or a single query string
     :docs-collection - TypeSense documents collection name
     :chunks-collection - TypeSense chunks collection name
     :phrases-collection - TypeSense phrases collection name

   Parameters:
     :limit - Max results per search (default 20)
     :filter-by - Optional filter map for TypeSense

   Returns:
     :chunks - Vector of chunk maps with search attribution
     :search-attribution - Map of search-type to hit counts"
  [{:keys [inputs parameters services pipeline-config] :as ctx}]
  (let [{:keys [queries docs-collection chunks-collection phrases-collection]} inputs
        ;; Normalize queries to always be a vector
        queries (cond
                  (nil? queries) []
                  (string? queries) [queries]
                  (sequential? queries) (vec queries)
                  :else [queries])
        {:keys [limit filter-by]} parameters
        opts {:tenant (:tenant pipeline-config)
              :environment (:environment pipeline-config)}

        ;; Execute all three search strategies
        phrase-hits (rag/lookup-search-phrases-similar
                      phrases-collection
                      docs-collection
                      queries
                      nil ; prompt not needed for search
                      filter-by
                      opts)

        metadata-hits (rag/search-chunks-by-metadata
                        chunks-collection
                        docs-collection
                        queries
                        filter-by
                        opts)

        content-hits (rag/search-chunks-by-content
                       chunks-collection
                       docs-collection
                       queries
                       filter-by
                       opts)

        ;; Merge all results
        merged-hits (rag/merge-chunk-search-results
                      (map #(assoc % :search-type :phrase) phrase-hits)
                      metadata-hits
                      content-hits)

        ;; Retrieve full chunk documents
        chunks (when (seq merged-hits)
                 (rag/retrieve-chunks-by-id
                   docs-collection
                   chunks-collection
                   merged-hits
                   opts))

        search-attribution {:phrase (count phrase-hits)
                           :metadata (count metadata-hits)
                           :content (count content-hits)
                           :merged (count merged-hits)}]

    (skills/success-result
      {:chunks (vec chunks)
       :search-attribution search-attribution}
      {:search-strategies-used 3
       :total-hits-before-merge (+ (count phrase-hits)
                                   (count metadata-hits)
                                   (count content-hits))})))

;; =============================================================================
;; Skill Registration
;; =============================================================================

(def retrieval-skill
  {:metadata retrieval-metadata
   :execute execute-retrieval})

(defn register!
  "Register the retrieval skill."
  []
  (skills/register-skill! retrieval-skill))

;; =============================================================================
;; Tool Definition (for agent invocation)
;; =============================================================================

(def retrieval-tool-definition
  "Tool definition for agent-based skill invocation."
  {:type "function"
   :function
   {:name "retrieval"
    :description "Search documents using multi-strategy retrieval (phrase matching, metadata search, content search)"
    :parameters
    {:type "object"
     :properties
     {:queries
      {:type "array"
       :items {:type "string"}
       :description "Search queries to execute"}
      :filter
      {:type "object"
       :description "Optional filters to apply to search results"}}
     :required ["queries"]}}})

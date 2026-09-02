(ns digdir.pipeline.skills.builtin.multi-retrieval
  "Multi-Retrieval skill - execute multiple queries and merge results.

   This skill executes multiple search queries in parallel and
   intelligently merges the results with deduplication."
  (:require [digdir.rag.core :as rag]
            [digdir.rag.skills.core :as skills]
            [digdir.pipeline.skills.context :as ctx]))

;; =============================================================================
;; Skill Metadata
;; =============================================================================

(def multi-retrieval-metadata
  {:skill-id :builtin/multi-retrieval
   :name "Multi-Query Retrieval"
   :description "Execute multiple search queries and merge results"
   :category :retrieval
   :inputs [:queries :docs-collection :chunks-collection :phrases-collection]
   :outputs [:chunks :search-attribution :query-results]
   :parameters {:limit-per-query :number
                :total-limit :number
                :filter-by :map
                :merge-strategy :keyword}
   :required-services #{:typesense}
   :version "1.0.0"
   :tags #{:typesense :search :multi-query}})

;; =============================================================================
;; Skill Implementation
;; =============================================================================

(defn execute-single-query
  "Execute a single query and return results with attribution."
  [query docs-collection chunks-collection phrases-collection filter-by opts]
  (let [phrase-hits (rag/lookup-search-phrases-similar
                      phrases-collection
                      docs-collection
                      [query]
                      nil
                      filter-by
                      opts)
        metadata-hits (rag/search-chunks-by-metadata
                        chunks-collection
                        docs-collection
                        [query]
                        filter-by
                        opts)
        content-hits (rag/search-chunks-by-content
                       chunks-collection
                       docs-collection
                       [query]
                       filter-by
                       opts)]
    {:query query
     :phrase-hits phrase-hits
     :metadata-hits metadata-hits
     :content-hits content-hits
     :total-hits (+ (count phrase-hits)
                    (count metadata-hits)
                    (count content-hits))}))

(defn merge-query-results
  "Merge results from multiple queries with deduplication."
  [query-results merge-strategy]
  (let [all-phrase-hits (mapcat :phrase-hits query-results)
        all-metadata-hits (mapcat :metadata-hits query-results)
        all-content-hits (mapcat :content-hits query-results)]
    (case merge-strategy
      :union
      ;; Simple merge - dedupe by chunk_id, keep highest rank
      (rag/merge-chunk-search-results
        (map #(assoc % :search-type :phrase) all-phrase-hits)
        all-metadata-hits
        all-content-hits)

      :ranked
      ;; Merge with query-level boosting (earlier queries ranked higher)
      (let [boosted-results
            (mapcat (fn [idx qr]
                      (let [boost (/ 1.0 (inc idx))]
                        (map #(update % :rank * boost)
                             (concat (:phrase-hits qr)
                                     (:metadata-hits qr)
                                     (:content-hits qr)))))
                    (range)
                    query-results)]
        (rag/merge-chunk-search-results boosted-results))

      ;; Default: simple union
      (rag/merge-chunk-search-results
        (map #(assoc % :search-type :phrase) all-phrase-hits)
        all-metadata-hits
        all-content-hits))))

(defn execute-multi-retrieval
  "Execute the multi-retrieval skill.

   Inputs:
     :queries - Vector of search query strings
     :docs-collection - TypeSense documents collection name
     :chunks-collection - TypeSense chunks collection name
     :phrases-collection - TypeSense phrases collection name

   Parameters:
     :limit-per-query - Max results per query (default 20)
     :total-limit - Max total results (default 100)
     :filter-by - Optional filter map for TypeSense
     :merge-strategy - How to merge results (:union, :ranked)

   Returns:
     :chunks - Vector of chunk maps with search attribution
     :search-attribution - Map of search-type to hit counts
     :query-results - Individual query results for debugging"
  [{:keys [inputs parameters services pipeline-config] :as ctx}]
  (let [{:keys [queries docs-collection chunks-collection phrases-collection]} inputs
        {:keys [limit-per-query total-limit filter-by merge-strategy]} parameters
        opts {:tenant (:tenant pipeline-config)
              :environment (:environment pipeline-config)}

        ;; Execute each query
        query-results (mapv #(execute-single-query
                               %
                               docs-collection
                               chunks-collection
                               phrases-collection
                               filter-by
                               opts)
                           queries)

        ;; Merge results
        merged-hits (merge-query-results query-results (or merge-strategy :union))

        ;; Apply total limit
        limited-hits (if total-limit
                       (take total-limit merged-hits)
                       merged-hits)

        ;; Retrieve full chunks
        chunks (when (seq limited-hits)
                 (rag/retrieve-chunks-by-id
                   docs-collection
                   chunks-collection
                   limited-hits
                   opts))

        ;; Build attribution
        search-attribution {:queries-executed (count queries)
                           :total-phrase-hits (reduce + (map #(count (:phrase-hits %)) query-results))
                           :total-metadata-hits (reduce + (map #(count (:metadata-hits %)) query-results))
                           :total-content-hits (reduce + (map #(count (:content-hits %)) query-results))
                           :merged-count (count merged-hits)
                           :final-count (count limited-hits)}]

    (skills/success-result
      {:chunks (vec chunks)
       :search-attribution search-attribution
       :query-results (mapv #(select-keys % [:query :total-hits]) query-results)}
      {:queries-executed (count queries)})))

;; =============================================================================
;; Skill Registration
;; =============================================================================

(def multi-retrieval-skill
  {:metadata multi-retrieval-metadata
   :execute execute-multi-retrieval})

(defn register!
  "Register the multi-retrieval skill."
  []
  (skills/register-skill! multi-retrieval-skill))

(def multi-retrieval-tool-definition
  {:type "function"
   :function
   {:name "multi_retrieve"
    :description "Search with multiple queries and merge results"
    :parameters
    {:type "object"
     :properties
     {:queries {:type "array"
                :items {:type "string"}
                :description "Multiple search queries to execute"}
      :merge_strategy {:type "string"
                       :enum ["union" "ranked"]
                       :description "How to merge results"}}
     :required ["queries"]}}})

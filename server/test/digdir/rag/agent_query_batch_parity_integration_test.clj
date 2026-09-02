(ns digdir.rag.agent-query-batch-parity-integration-test
  "Integration tests to mirror planner-style multi-batch retrieval behavior seen in agent traces."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.rag.core :as rag]
            [digdir.rag.live-context :as live-ctx]
            [digdir.skills.builtin.retrieval :as retrieval]))

(def target-dataset-ref live-ctx/default-dataset-ref)
(def target-query "Hvor mange årsverk hadde Digdir i 2022?")
(def golden-chunk-id "6a80d6499075")

;; Mirrors batch style seen in agent traces (5 queries per call).
(def batch-1
  ["årsverk Digdir 2022"
   "antall ansatte Digitaliseringsdirektoratet 2022"
   "bemanning Digdir 2022"
   "ressursbruk Digitaliseringsdirektoratet 2022"
   "årsverk og ansatte Digdir 2022"])

(def batch-2
  ["årsverk Digdir i 2022"
   "bemanning i Digitaliseringsdirektoratet 2022"
   "ressursbruk årsverk Digdir 2022"
   "ansatte og årsverk Digdir 2022"
   "bemanningsoversikt Digitaliseringsdirektoratet 2022"])

(def ^:private !cfg (atom nil))

(defn- services-reachable?
  []
  (and (config-core/get-master-key)
       (try
         (some? (config-db/get-conn))
         (catch Exception _ false))))

(defn- resolve-config!
  []
  (let [{:keys [dataset-config
                docs-collection
                chunks-collection
                phrases-collection
                ts-opts]} (live-ctx/resolve-live-dataset-context! target-dataset-ref)]
    {:pipeline-config dataset-config
     :docs-collection docs-collection
     :chunks-collection chunks-collection
     :phrases-collection phrases-collection
     :ts-opts ts-opts}))

(defn- setup-fixture [f]
  (if (services-reachable?)
    (if-let [cfg (try
                   (resolve-config!)
                   (catch Exception _
                     nil))]
      (if (live-ctx/typesense-reachable? (:ts-opts cfg))
        (do
              (reset! !cfg cfg)
              (f))
        (println "Skipping agent query batch parity tests: Typesense not reachable"))
      (println "Skipping agent query batch parity tests: live dataset config unavailable"))
    (println "Skipping agent query batch parity tests: CONFIG_MASTER_KEY/config DB unavailable")))

(use-fixtures :once setup-fixture)

(defn- run-batch-retrieval
  [{:keys [docs-collection chunks-collection phrases-collection ts-opts]} queries]
  (let [res (retrieval/execute-retrieval
             {:inputs {:queries queries
                       :docs-collection docs-collection
                       :chunks-collection chunks-collection
                       :phrases-collection phrases-collection}
              :parameters {:retrieve-top-k 100
                           :query-aware-boost true}
              :skill-params {:tenant (:tenant ts-opts)
                             :dataset-config-key (:dataset-config-key ts-opts)}})]
    (vec (get-in res [:outputs :chunks]))))

(defn- merge-unique-chunks
  [& chunk-vecs]
  (->> (apply concat chunk-vecs)
       (reduce (fn [acc chunk]
                 (if (contains? acc (:chunk_id chunk))
                   acc
                   (assoc acc (:chunk_id chunk) chunk)))
               {})
       vals
       vec))

(defn- rank-position
  [chunks chunk-id]
  (some (fn [[idx chunk]]
          (when (= chunk-id (:chunk_id chunk))
            (inc idx)))
        (map-indexed vector chunks)))

(defn- context-position
  [used-docs chunk-id]
  (some (fn [[idx doc]]
          (when (= chunk-id (get-in doc [:metadata :source]))
            (inc idx)))
        (map-indexed vector used-docs)))

(deftest agent-query-batch-parity-retrieval-and-rerank
  (testing "Two planner-style query batches preserve golden chunk into workspace and rerank pool"
    (let [{:keys [docs-collection pipeline-config] :as cfg} @!cfg
          chunks-1 (run-batch-retrieval cfg batch-1)
          chunks-2 (run-batch-retrieval cfg batch-2)
          workspace (merge-unique-chunks chunks-1 chunks-2)
          golden-in-b1 (some #(= golden-chunk-id (:chunk_id %)) chunks-1)
          golden-in-b2 (some #(= golden-chunk-id (:chunk_id %)) chunks-2)
          golden-in-workspace (some #(= golden-chunk-id (:chunk_id %)) workspace)
          rerank-res (rag/rerank-chunks
                      workspace
                      {:translated_user_query target-query
                       :docsCollectionName docs-collection
                       :rerankTopkChunks 30
                       ;; Agent query parity test exercises full-RAG flow.
                       :rerankMaxChunkLength (or (:rerank-rag-max-chunk-length pipeline-config) 1000)
                       :rerankMaxLength (or (:rerank-rag-max-total-length pipeline-config) 10000)
                       :contextTopkChunks 30
                       :contextMinChunks 8
                       :contextRelativeScoreThreshold 0.85
                       :contextMaxChunkLength (or (:context-max-chunk-length pipeline-config) 1000)
                       :maxContextLength (or (:context-max-total-length pipeline-config) 8000)
                       :promptRagGenerate nil})
          reranked (or (:reranked-chunks rerank-res)
                       (:used-chunks rerank-res)
                       [])
          used-docs (:used-docs rerank-res)
          rerank-pos (rank-position reranked golden-chunk-id)
          context-pos (context-position used-docs golden-chunk-id)]
      (println "\nAgent batch parity:")
      (println "  batch-1 chunks:" (count chunks-1) "golden?" (boolean golden-in-b1))
      (println "  batch-2 chunks:" (count chunks-2) "golden?" (boolean golden-in-b2))
      (println "  workspace unique chunks:" (count workspace) "golden?" (boolean golden-in-workspace))
      (println "  rerank position:" rerank-pos "context position:" context-pos)
      (is golden-in-workspace "Golden chunk should be present in unioned workspace after two query batches")
      (is (some? rerank-pos) "Golden chunk should be present in rerank output for parity scenario")
      (when (some? rerank-pos)
        (is (<= rerank-pos 30) "Golden chunk should be inside rerankTopkChunks window")))))

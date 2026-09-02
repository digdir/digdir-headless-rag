(ns digdir.rag.rerank-isolation-integration-test
  "Independent integration tests for reranker quality.

   These tests isolate reranking from retrieval/query-planning by feeding fixed
   candidate chunk-id sets captured from diagnostics runs.

   Run with: bb rerank-isolation-eval"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.rag.core :as rag]
            [digdir.rag.live-context :as live-ctx]))

(def target-dataset-ref live-ctx/default-dataset-ref)

(def ^:private !test-config (atom nil))
(def ^:private !fixture (atom nil))

(defn- rerank-isolation-enabled?
  []
  (= "true" (some-> (System/getenv "RUN_RERANK_ISOLATION_INTEGRATION") str/lower-case)))

(defn- services-reachable?
  []
  (and (config-core/get-master-key)
       (try
         (let [conn (config-db/get-conn)]
           (some? conn))
         (catch Exception _ false))))

(defn- load-fixture!
  []
  (let [resource (io/resource "fixtures/rerank/isolation_candidates.edn")]
    (when-not resource
      (throw (ex-info "Missing fixture: fixtures/rerank/isolation_candidates.edn" {})))
    (edn/read-string (slurp resource))))

(defn- resolve-test-config!
  []
  (let [{:keys [dataset-config
                docs-collection
                chunks-collection
                colbert-url
                ts-opts]} (live-ctx/resolve-live-dataset-context! target-dataset-ref)]
    {:pipeline-config dataset-config
     :docs-collection docs-collection
     :chunks-collection chunks-collection
     :colbert-url colbert-url
     :ts-opts ts-opts}))

(defn- setup-fixture [f]
  (if (rerank-isolation-enabled?)
    (if (services-reachable?)
      (let [cfg (resolve-test-config!)]
        (if (and (seq (:colbert-url cfg))
                 (live-ctx/typesense-reachable? (:ts-opts cfg)))
          (do
            (reset! !test-config cfg)
            (let [fixture (load-fixture!)]
              (reset! !fixture fixture)
              (when-let [capture (:capture fixture)]
                (println "Using isolation fixture capture metadata:" capture)))
            (f))
          (println "Skipping rerank-isolation integration tests: Typesense/ColBERT not reachable")))
      (println "Skipping rerank-isolation integration tests: CONFIG_MASTER_KEY/config DB unavailable"))
    (println "Skipping rerank-isolation integration tests: set RUN_RERANK_ISOLATION_INTEGRATION=true to enable live reranker quality gates")))

(use-fixtures :once setup-fixture)

(defn- candidate-input
  [chunk-ids]
  (mapv (fn [idx cid]
          {:chunk_id cid
           :rank 0
           :index idx
           :hit-count 1
           :search-types #{:fixture}})
        (range)
        chunk-ids))

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

(defn- run-rerank
  [chunk-ids top-k context-top-k query]
  (binding [*out* (java.io.StringWriter.)
            *err* (java.io.StringWriter.)]
    (let [{:keys [docs-collection chunks-collection pipeline-config ts-opts]} @!test-config
          candidates (candidate-input chunk-ids)
          retrieved (rag/retrieve-chunks-by-id
                     docs-collection
                     chunks-collection
                     candidates
                     (assoc ts-opts :retrieve-top-k top-k))
          params {:translated_user_query query
                  :docsCollectionName docs-collection
                  :rerankTopkChunks top-k
                  ;; Rerank-isolation test exercises retrieval-only mode.
                  :rerankMaxChunkLength (or (:rerank-retrieval-max-chunk-length pipeline-config) 1000)
                  :rerankMaxLength (or (:rerank-retrieval-max-total-length pipeline-config) 10000)
                  :contextTopkChunks context-top-k
                  :contextMaxChunkLength (or (:context-max-chunk-length pipeline-config) 1000)
                  :maxContextLength (or (:context-max-total-length pipeline-config) 8000)
                  :promptRagGenerate nil}
          rerank-res (rag/rerank-chunks retrieved params)
          reranked (or (:reranked-chunks rerank-res) (:used-chunks rerank-res) [])]
      {:input-count (count chunk-ids)
       :retrieved-count (count retrieved)
       :candidate-ids (vec chunk-ids)
       :reranked reranked
       :used-docs (:used-docs rerank-res)})))

(defn- candidate-position
  [candidate-ids chunk-id]
  (some (fn [[idx cid]]
          (when (= chunk-id cid)
            (inc idx)))
        (map-indexed vector candidate-ids)))

(defn- position-delta
  [before after]
  (when (and before after)
    (- after before)))

(deftest rerank-isolation-cutoff-behavior
  (testing "Top-k cutoff behavior is independent from retrieval/query planning"
    (let [{:keys [query golden-chunk-id candidate-sets]} @!fixture
          filtered-top40 (:filtered-top40 candidate-sets)
          unfiltered-top40 (:unfiltered-top40 candidate-sets)
          filtered-run (run-rerank filtered-top40 40 30 query)
          unfiltered-run (run-rerank unfiltered-top40 40 30 query)
          filtered-before (candidate-position (:candidate-ids filtered-run) golden-chunk-id)
          unfiltered-before (candidate-position (:candidate-ids unfiltered-run) golden-chunk-id)
          filtered-rank (rank-position (:reranked filtered-run) golden-chunk-id)
          unfiltered-rank (rank-position (:reranked unfiltered-run) golden-chunk-id)
          filtered-context (context-position (:used-docs filtered-run) golden-chunk-id)
          unfiltered-context (context-position (:used-docs unfiltered-run) golden-chunk-id)
          filtered-delta (position-delta filtered-before filtered-rank)
          unfiltered-delta (position-delta unfiltered-before unfiltered-rank)]
      (println "\nRerank isolation cutoff:")
      (println "  filtered top40   -> before:" filtered-before
               ", after:" filtered-rank
               ", delta:" filtered-delta
               ", context-pos:" filtered-context)
      (println "  unfiltered top40 -> before:" unfiltered-before
               ", after:" unfiltered-rank
               ", delta:" unfiltered-delta
               ", context-pos:" unfiltered-context)
      (is (some? filtered-rank) "Golden chunk should be rerankable when present in candidate pool")
      (is (nil? unfiltered-rank) "Golden chunk should be absent when not in candidate pool")
      (is (some? filtered-context) "Golden chunk should enter context in filtered top40 case")
      (is (nil? unfiltered-context) "Golden chunk should not enter context in unfiltered top40 case"))))

(deftest rerank-isolation-quality-gate-top100
  (testing "Quality gate: golden chunk should rank highly in top-100 candidate pools"
    (let [{:keys [query golden-chunk-id candidate-sets]} @!fixture
          filtered-top100 (:filtered-top100 candidate-sets)
          unfiltered-top100 (:unfiltered-top100 candidate-sets)
          filtered-run (run-rerank filtered-top100 100 30 query)
          unfiltered-run (run-rerank unfiltered-top100 100 30 query)
          filtered-before (candidate-position (:candidate-ids filtered-run) golden-chunk-id)
          unfiltered-before (candidate-position (:candidate-ids unfiltered-run) golden-chunk-id)
          filtered-rank (rank-position (:reranked filtered-run) golden-chunk-id)
          unfiltered-rank (rank-position (:reranked unfiltered-run) golden-chunk-id)
          filtered-context (context-position (:used-docs filtered-run) golden-chunk-id)
          unfiltered-context (context-position (:used-docs unfiltered-run) golden-chunk-id)
          filtered-delta (position-delta filtered-before filtered-rank)
          unfiltered-delta (position-delta unfiltered-before unfiltered-rank)
          max-acceptable-rank (or (some-> (System/getenv "RERANK_MAX_ACCEPTABLE_RANK")
                                          Integer/parseInt)
                                  10)]
      (println "\nRerank isolation top100 quality gate:")
      (println "  max acceptable rank:" max-acceptable-rank)
      (println "  filtered top100   -> before:" filtered-before
               ", after:" filtered-rank
               ", delta:" filtered-delta
               ", context-pos:" filtered-context)
      (println "  unfiltered top100 -> before:" unfiltered-before
               ", after:" unfiltered-rank
               ", delta:" unfiltered-delta
               ", context-pos:" unfiltered-context)
      (is (some? filtered-rank) "Golden chunk missing in filtered top100 rerank output")
      (is (some? unfiltered-rank) "Golden chunk missing in unfiltered top100 rerank output")
      (is (<= filtered-rank max-acceptable-rank)
          (str "Filtered top100 rerank quality regression: golden chunk before/after "
               filtered-before "->" filtered-rank " (delta " filtered-delta
               ") exceeds acceptable rank " max-acceptable-rank))
      (is (<= unfiltered-rank max-acceptable-rank)
          (str "Unfiltered top100 rerank quality regression: golden chunk before/after "
               unfiltered-before "->" unfiltered-rank " (delta " unfiltered-delta
               ") exceeds acceptable rank " max-acceptable-rank)))))

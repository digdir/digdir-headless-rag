(ns digdir.rag.rerank-language-parity-integration-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.rag.live-context :as live-ctx]))

(def target-dataset-ref live-ctx/default-dataset-ref)
(def fixture-path "test/fixtures/rerank/language_pairs_arsverk_candidates.edn")

(defn- rerank-language-parity-enabled?
  []
  (= "true" (some-> (System/getenv "RUN_RERANK_LANGUAGE_PARITY_INTEGRATION") str/lower-case)))

(defn- rerank-language-benchmark-fn
  []
  (try
    (requiring-resolve 'digdir.tools.diagnostics/rerank-language-benchmark)
    (catch Throwable _ nil)))

(defn- services-reachable?
  []
  (and (config-core/get-master-key)
       (try
         (some? (config-db/get-conn))
         (catch Exception _ false))))

(defn- typesense-reachable?
  []
  (live-ctx/typesense-reachable? {:tenant (:tenant target-dataset-ref)
                                  :dataset-config-key (:dataset-config-key target-dataset-ref)}))

(defn- parse-last-edn-line
  [s]
  (let [lines (reverse (str/split-lines (or s "")))]
    (or
     (some (fn [line]
             (let [line (str/trim line)]
               (when (seq line)
                 (try
                   (edn/read-string {:readers {'sorted/map identity}} line)
                   (catch Throwable _ nil)))))
           lines)
     (throw (ex-info "No EDN payload found in diagnostics output" {:output s})))))

(defn- setup-fixture [f]
  (if (rerank-language-parity-enabled?)
    (if (services-reachable?)
      (if (typesense-reachable?)
        (f)
        (println "Skipping rerank language parity tests: Typesense not reachable"))
      (println "Skipping rerank language parity tests: CONFIG_MASTER_KEY/config DB unavailable"))
    (println "Skipping rerank language parity tests: set RUN_RERANK_LANGUAGE_PARITY_INTEGRATION=true to enable live parity checks")))

(use-fixtures :once setup-fixture)

(deftest rerank-language-benchmark-smoke-and-parity
  (testing "Bilingual rerank benchmark emits NO/EN metrics from captured candidates"
    (if-let [benchmark-fn (rerank-language-benchmark-fn)]
      (let [out (with-out-str
                  (benchmark-fn
                   {:dataset-ref target-dataset-ref
                    :cli-opts ["--fixture" fixture-path
                               "--top-k" "30"
                               "--context-top-k" "30"
                               "--context-min-chunks" "8"
                               "--fail-on-gate" "false"]}))
            payload (parse-last-edn-line out)
            by-lang (:summary-by-language payload)
            no-metrics (get by-lang :no)
            en-metrics (get by-lang :en)
            strict? (= "true" (some-> (System/getenv "RERANK_LANGUAGE_PARITY_ENFORCE") str/lower-case))
            max-gap 20
            no-p50 (:p50-rank no-metrics)
            en-p50 (:p50-rank en-metrics)
            rank-gap (when (and (number? no-p50) (number? en-p50))
                       (Math/abs (- (double no-p50) (double en-p50))))]
        (is (map? by-lang) "Expected language summary in benchmark payload")
        (is (pos? (or (:cases no-metrics) 0)) "Expected Norwegian cases in summary")
        (is (pos? (or (:cases en-metrics) 0)) "Expected English cases in summary")
        (is (number? (:mrr no-metrics)) "Expected numeric NO MRR")
        (is (number? (:mrr en-metrics)) "Expected numeric EN MRR")
        (when strict?
          (is (number? rank-gap)
              "Strict parity mode requires numeric NO/EN p50 ranks")
          (is (<= rank-gap max-gap)
              (str "NO/EN p50 rank gap exceeds threshold: " rank-gap " > " max-gap))))
      (println "Skipping rerank language parity tests: digdir.tools.diagnostics/rerank-language-benchmark unavailable"))))

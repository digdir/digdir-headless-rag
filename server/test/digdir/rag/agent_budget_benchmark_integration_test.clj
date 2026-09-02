(ns digdir.rag.agent-budget-benchmark-integration-test
  "Opt-in smoke test for the live agent budget benchmark against public-sector-knowledge/dev/kudos."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.rag.live-context :as live-ctx]))

(def target-dataset-ref live-ctx/default-dataset-ref)
(def target-agent-id live-ctx/default-agent-id)
(def fixture-path "test/fixtures/agent/budget_hard_query_suite.edn")

(defn- live-agent-budget-enabled?
  []
  (= "true" (some-> (System/getenv "RUN_AGENT_BUDGET_BENCHMARK_INTEGRATION")
                    str/lower-case)))

(defn- agent-budget-benchmark-fn
  []
  (try
    (requiring-resolve 'digdir.tools.diagnostics/agent-budget-benchmark)
    (catch Throwable _ nil)))

(defn- services-reachable?
  []
  (and (live-agent-budget-enabled?)
       (config-core/get-master-key)
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
  (if (services-reachable?)
    (if (typesense-reachable?)
      (f)
      (println "Skipping agent budget benchmark integration test: Typesense not reachable"))
    (println "Skipping agent budget benchmark integration test: set RUN_AGENT_BUDGET_BENCHMARK_INTEGRATION=true and ensure CONFIG_MASTER_KEY/config DB are available.")))

(use-fixtures :once setup-fixture)

(deftest agent-budget-benchmark-smoke
  (when (live-agent-budget-enabled?)
    (testing "Live benchmark emits paired current-vs-relaxed agent budget results"
      (if-let [benchmark-fn (agent-budget-benchmark-fn)]
        (let [out (with-out-str
                    (benchmark-fn
                     {:dataset-ref target-dataset-ref
                      :agent-id target-agent-id
                      :cli-opts ["--suite" fixture-path
                                 "--fail-on-gate" "false"]}))
              payload (parse-last-edn-line out)
              summary (:summary payload)
              results (:results payload)]
          (is (= 3 (count results)) "Expected all hard-query cases in the live suite")
          (is (map? summary) "Expected summary metrics in benchmark payload")
          (is (every? #(contains? % :current) results))
          (is (every? #(contains? % :relaxed) results))
          (is (every? #(contains? % :improved) results))
          (is (<= (:current-pass summary) (:relaxed-pass summary))
              "Relaxed budget should not underperform current budget in the live summary")
          (is (zero? (:regressed summary))
              "Live hard-query suite should not regress under the relaxed budget profile"))
        (println "Skipping agent budget benchmark integration test: digdir.tools.diagnostics/agent-budget-benchmark unavailable")))))

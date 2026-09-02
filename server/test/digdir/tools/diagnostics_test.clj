(ns digdir.tools.diagnostics-test
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.tools.diagnostics :as diagnostics]
            [digdir.api.routes :as routes]
            [digdir.rag.typesense :as ts-utils]
            [typesense.client :as ts-client]
            [digdir.skills.api :as skills-api]))

(defn- parse-output-edn
  [s]
  (edn/read-string {:readers {'sorted/map identity}} s))

(def ^:private test-dataset-ref
  {:tenant "ka"
   :dataset-config-key "dev"})

(def ^:private test-agent-id
  "builtin/agent-rag-agent")

(deftest chunk-find-accepts-dataset-root-config-params
  (testing "Chunk diagnostics map tenant + config-root + config-key onto a canonical dataset ref"
    (let [captured-opts (atom nil)
          payload (parse-output-edn
                   (with-out-str
                     (with-redefs [diagnostics/resolve-diagnostics-context! (fn [opts & _]
                                                                              (reset! captured-opts opts)
                                                                              {:conn nil
                                                                               :dataset-ref (:dataset-ref opts)
                                                                               :collection-names {:chunks-collection "chunks"
                                                                                                  :docs-collection "docs"}
                                                                               :opts {:tenant "ka"
                                                                                      :dataset-config-key "assistant"
                                                                                      :tenant-config-key "assistant"
                                                                                      :runtime-config-key "assistant"}})
                                   ts-client/multi-search (fn [_settings _query _opts]
                                                            {:results [{:found 0
                                                                        :hits []}]})]
                       (diagnostics/chunk-find {:tenant "ka"
                                                :config-root "dataset"
                                                :config-key "assistant"
                                                :needle "forskrift"}))))]
      (is (= {:tenant "ka"
              :dataset-config-key "assistant"}
             (:dataset-ref @captured-opts)))
      (is (= {:tenant "ka"
              :dataset-config-key "assistant"}
             (:dataset-ref payload)))
      (is (= {:needle "forskrift"
              :limit 50
              :exact false}
             (:effective-params payload)))
      (is (= 0 (:total-hits payload)))
      (is (= [] (:hits payload))))))

(deftest chunk-find-rejects-non-dataset-config-roots
  (testing "Chunk diagnostics fail fast when invoked with a non-dataset config root"
    (let [resolved? (atom false)
          payload (parse-output-edn
                   (with-out-str
                     (with-redefs [diagnostics/resolve-diagnostics-context! (fn [_opts & _]
                                                                              (reset! resolved? true)
                                                                              (throw (ex-info "should not resolve" {})))]
                       (diagnostics/chunk-find {:tenant "ka"
                                                :config-root :runtime
                                                :config-key "default"
                                                :needle "forskrift"}))))]
      (is (false? @resolved?))
      (is (= "Chunk diagnostics require config-root :dataset"
             (get-in payload [:error :message]))))))

(deftest retrieve-debug-accepts-dataset-root-config-params
  (testing "Retrieval diagnostics map tenant + config-root + config-key onto a canonical dataset ref (matching chunk-find's layout)"
    (let [captured-opts (atom nil)
          payload (parse-output-edn
                   (with-out-str
                     (with-redefs [diagnostics/resolve-diagnostics-context!
                                   (fn [opts & _]
                                     (reset! captured-opts opts)
                                     ;; Force the run path to exit fast with no hits.
                                     (throw (ex-info "stub" {})))]
                       (diagnostics/retrieve-debug {:tenant "ka"
                                                    :config-root "dataset"
                                                    :config-key "assistant"
                                                    :user-query "what is forskrift"}))))]
      (is (= {:tenant "ka" :dataset-config-key "assistant"}
             (:dataset-ref @captured-opts)))
      (is (= {:tenant "ka" :dataset-config-key "assistant"}
             (:dataset-ref payload))
          ":dataset-ref should still appear in the error payload even when resolve fails"))))

(deftest retrieve-debug-rejects-non-dataset-config-roots
  (testing "Retrieval diagnostics fail fast when invoked with a non-dataset config root"
    (let [resolved? (atom false)
          payload (parse-output-edn
                   (with-out-str
                     (with-redefs [diagnostics/resolve-diagnostics-context!
                                   (fn [_opts & _]
                                     (reset! resolved? true)
                                     (throw (ex-info "should not resolve" {})))]
                       (diagnostics/retrieve-debug {:tenant "ka"
                                                    :config-root :runtime
                                                    :config-key "default"
                                                    :user-query "q"}))))]
      (is (false? @resolved?))
      (is (= "Retrieval diagnostics require config-root :dataset"
             (get-in payload [:error :message]))))))

(deftest build-agent-skill-params-merges-agent-budget-overrides
  (testing "Agent benchmark params preserve existing RAG params and inject agent-specific limits"
    (with-redefs [routes/build-rag-skill-params (fn [_config params]
                                                  {:base true
                                                   :seen params})]
      (let [params (#'diagnostics/build-agent-skill-params
                    {:id "pipeline"}
                    {:temperature 0.0
                     :max-search-passes 5
                     :max-read-operations 7
                     :max-read-content-length 16000})
            agent-params (:builtin/agent params)]
        (is (= true (:base params)))
        (is (= 5 (:max-search-passes agent-params)))
        (is (= 7 (:max-read-operations agent-params)))
        (is (= 16000 (:max-read-content-length agent-params)))
        (is (= 0.0 (:temperature agent-params)))))))

(deftest run-agent-budget-profile-evaluates-answer-and-golden-presence
  (testing "Profile runs pass when answer matches, the golden chunk is present, and the agent is not insufficient"
    (let [captured (atom nil)]
      (with-redefs [routes/build-rag-skill-params (fn [_config _params]
                                                    {:builtin/retrieval {:retrieve-top-k 100}})
                    skills-api/run-skill-graph (fn [graph-id inputs opts]
                                                 (reset! captured {:graph-id graph-id
                                                                   :inputs inputs
                                                                   :opts opts})
                                                 {:outputs {:response "outer response"}
                                                  :step-results
                                                  {:agent {:outputs {:response "Digdir hadde 326 utførte årsverk i 2022 [1]."
                                                                     :chunks [{:chunk_id "6a80d6499075"}]
                                                                     :search-history [{:queries ["årsverk digdir 2022"]
                                                                                       :result-count 2
                                                                                       :new-count 2
                                                                                       :chunk-summaries [{:chunk-id "6a80d6499075"
                                                                                                          :doc-num "doc-1"
                                                                                                          :chunk-index 4
                                                                                                          :title "Digdir arsrapport 2022"
                                                                                                          :content-length 1800}
                                                                                                         {:chunk-id "other"
                                                                                                          :doc-num "doc-2"
                                                                                                          :chunk-index 1
                                                                                                          :title "Andre tal"
                                                                                                          :content-length 900}]}]
                                                                     :read-history [{:mode :chunk-ids
                                                                                     :chunk-ids ["6a80d6499075"]
                                                                                     :returned-count 1
                                                                                     :returned-chunk-ids ["6a80d6499075"]
                                                                                     :content-length 1800}]
                                                                     :sufficiency-decisions [{:status :enough
                                                                                              :action :stop}]
                                                                     :budget-state {:search-passes-used 2
                                                                                    :search-passes-remaining 2
                                                                                    :read-operations-used 1
                                                                                    :read-operations-remaining 5
                                                                                    :read-content-length-used 1800
                                                                                    :read-content-length-remaining 10200}
                                                                     :insufficient-context false}
                                                           :metadata {:search-passes 2
                                                                      :read-operations 1
                                                                      :read-content-length 1800}}}})]
        (let [result (#'diagnostics/run-agent-budget-profile
                      {:pipeline-config {:id "cfg"}
                       :collection-names {:docs-collection "docs"
                                          :chunks-collection "chunks"
                                          :phrases-collection "phrases"}
                       :dataset-ref test-dataset-ref
                       :agent-id test-agent-id
                       :skill-graph-id "builtin/agent-rag"
                       :opts {:tenant "ka"
                              :dataset-config-key "dev"
                              :tenant-config-key "dev"
                              :runtime-config-key "dev"}}
                      {:query "Hvor mange årsverk hadde Digdir i 2022?"
                       :golden-chunk-ids ["6a80d6499075"]
                       :expected-answer-pattern "\\b326\\b"}
                      :relaxed
                      {:max-search-passes 5
                       :max-read-operations 7})]
          (is (= :builtin/agent-rag (:graph-id @captured)))
          (is (= "Hvor mange årsverk hadde Digdir i 2022?"
                 (get-in @captured [:inputs :user-query])))
          (is (= 5 (get-in @captured [:opts :skill-params :builtin/agent :max-search-passes])))
          (is (= 7 (get-in @captured [:opts :skill-params :builtin/agent :max-read-operations])))
          (is (true? (:answer-pass result)))
          (is (true? (:golden-present result)))
          (is (true? (:pass result)))
          (is (= :enough (:latest-status result)))
          (is (= 2 (:search-passes result)))
          (is (= 1 (:read-operations result)))
          (is (= 1800 (:read-content-length result)))
          (is (= [{:pass 1
                   :queries ["årsverk digdir 2022"]
                   :result-count 2
                   :new-count 2
                   :top-chunks [{:chunk-id "6a80d6499075"
                                 :doc-num "doc-1"
                                 :chunk-index 4
                                 :title "Digdir arsrapport 2022"
                                 :content-length 1800}
                                {:chunk-id "other"
                                 :doc-num "doc-2"
                                 :chunk-index 1
                                 :title "Andre tal"
                                 :content-length 900}]}]
                 (get-in result [:inspection :searches])))
          (is (= [{:chunk-id "6a80d6499075"
                   :first-seen-pass 1
                   :doc-num "doc-1"
                   :chunk-index 4
                   :title "Digdir arsrapport 2022"
                   :content-length 1800}
                  {:chunk-id "other"
                   :first-seen-pass 1
                   :doc-num "doc-2"
                   :chunk-index 1
                   :title "Andre tal"
                   :content-length 900}]
                 (get-in result [:inspection :top-retrieved-chunks])))
          (is (= [{:operation 1
                   :mode :chunk-ids
                   :returned-count 1
                   :content-length 1800
                   :chunk-ids ["6a80d6499075"]
                   :returned-chunks [{:chunk-id "6a80d6499075"
                                      :doc-num "doc-1"
                                      :chunk-index 4
                                      :title "Digdir arsrapport 2022"
                                      :content-length 1800}]}]
                 (get-in result [:inspection :reads])))
          (is (= {:operation 1
                  :mode :chunk-ids
                  :returned-count 1
                  :content-length 1800
                  :chunk-ids ["6a80d6499075"]
                  :returned-chunks [{:chunk-id "6a80d6499075"
                                     :doc-num "doc-1"
                                     :chunk-index 4
                                     :title "Digdir arsrapport 2022"
                                     :content-length 1800}]}
                 (get-in result [:inspection :largest-read]))))))))

(deftest run-agent-budget-profile-fails-when-answer-or-evidence-is-missing
  (testing "Profile runs fail when the answer pattern misses or the golden chunk never reaches final outputs"
    (with-redefs [routes/build-rag-skill-params (fn [_config _params] {})
                  skills-api/run-skill-graph (fn [_graph-id _inputs _opts]
                                               {:step-results
                                                {:agent {:outputs {:response "Digdir hadde mange ansatte."
                                                                   :chunks [{:chunk_id "other"}]
                                                                   :sufficiency-decisions [{:status :insufficient
                                                                                            :action :re-search}]
                                                                   :budget-state {}
                                                                   :insufficient-context true}}}})]
      (let [result (#'diagnostics/run-agent-budget-profile
                    {:pipeline-config {:id "cfg"}
                     :collection-names {:docs-collection "docs"
                                        :chunks-collection "chunks"
                                        :phrases-collection "phrases"}
                     :dataset-ref test-dataset-ref
                     :agent-id test-agent-id
                     :skill-graph-id "builtin/agent-rag"
                     :opts {:tenant "ka"
                            :dataset-config-key "dev"
                            :tenant-config-key "dev"
                            :runtime-config-key "dev"}}
                    {:query "Hvor mange årsverk hadde Digdir i 2022?"
                     :golden-chunk-ids ["6a80d6499075"]
                     :expected-answer-pattern "\\b326\\b"}
                    :current
                    {:max-search-passes 4})]
        (is (false? (:answer-pass result)))
        (is (false? (:golden-present result)))
        (is (false? (:pass result)))
        (is (= :insufficient (:latest-status result)))))))

(deftest run-agent-budget-profile-surfaces-search-backend-errors
  (testing "Profile runs expose search backend failures as explicit benchmark errors"
    (with-redefs [routes/build-rag-skill-params (fn [_config _params] {})
                  skills-api/run-skill-graph (fn [_graph-id _inputs _opts]
                                               {:step-results
                                                {:agent {:outputs {:response "Search backend error: Retrieval backend failure: Connection refused"
                                                                   :chunks []
                                                                   :search-errors [{:error-type :retrieval-backend-failure
                                                                                    :error-message "Retrieval backend failure: Connection refused"}]
                                                                   :search-history []
                                                                   :read-history []
                                                                   :budget-state {:search-passes-used 1}
                                                                   :insufficient-context false}}}})]
      (let [result (#'diagnostics/run-agent-budget-profile
                    {:pipeline-config {:id "cfg"}
                     :collection-names {:docs-collection "docs"
                                        :chunks-collection "chunks"
                                        :phrases-collection "phrases"}
                     :dataset-ref test-dataset-ref
                     :agent-id test-agent-id
                     :skill-graph-id "builtin/agent-rag"
                     :opts {:tenant "ka"
                            :dataset-config-key "dev"
                            :tenant-config-key "dev"
                            :runtime-config-key "dev"}}
                    {:query "Hvor mange årsverk hadde Digdir i 2022?"
                     :golden-chunk-ids ["6a80d6499075"]
                     :expected-answer-pattern "\\b326\\b"}
                    :current
                    {:max-search-passes 4})]
        (is (false? (:pass result)))
        (is (= 1 (count (:search-errors result))))
        (is (= :search-backend-failure (get-in result [:error :type])))
        (is (re-find #"Connection refused" (get-in result [:error :message])))))))

(deftest read-agent-budget-suite-loads-hard-live-fixture
  (testing "The hard live suite normalizes budget metadata and answer patterns"
    (let [path (-> (io/resource "fixtures/agent/budget_hard_query_suite.edn")
                   io/file
                   str)
          cases (#'diagnostics/read-agent-budget-suite path)]
      (is (= [] cases)))))

(deftest read-agent-budget-suite-loads-medium-baseline-fixture
  (testing "The medium baseline suite keeps the previously stable regression cases"
    (let [path (-> (io/resource "fixtures/agent/baseline_token_budget_medium_difficulty.edn")
                   io/file
                   str)
          cases (#'diagnostics/read-agent-budget-suite path)
          by-id (into {} (map (juxt :id identity)) cases)]
      (is (= 4 (count cases)))
      (is (= :read-chars (get-in by-id ["digdir-arsverk-utforte-2022-arsrapport" :budget-dimension])))
      (is (= :canonical-pivot (get-in by-id ["digdir-arsverk-canonical-pivot" :budget-dimension])))
      (is (= :mixed (get-in by-id ["digdir-arsverk-bilingual-scope" :budget-dimension])))
      (is (= :mixed (get-in by-id ["digdir-utforte-lonnsutgifter-2020-2023-anchored-extraction" :budget-dimension])))
      (is (= ["15a66d5ec5c5"]
             (get-in by-id ["digdir-arsverk-canonical-pivot" :golden-chunk-ids]))))))

(deftest read-agent-budget-suite-loads-exploratory-fixture
  (testing "The exploratory suite captures unstable but informative live cases"
    (let [path (-> (io/resource "fixtures/agent/token_budget_exploratory_candidates.edn")
                   io/file
                   str)
          cases (#'diagnostics/read-agent-budget-suite path)
          by-id (into {} (map (juxt :id identity)) cases)]
      (is (= 7 (count cases)))
      (is (= :mixed (get-in by-id ["digdir-arsverk-metrics-2021-2023-path-instability" :budget-dimension])))
      (is (= ["b53d465f69b6" "15a66d5ec5c5" "6aefa3a05c7d"]
             (get-in by-id ["digdir-lonnsutgifter-per-arsverk-2021-2023-grounding" :golden-chunk-ids])))
      (is (= :read-chars (get-in by-id ["digdir-utforte-arsverk-2018-2023-path-instability" :budget-dimension])))
      (is (= ["15a66d5ec5c5" "6aefa3a05c7d"]
             (get-in by-id ["digdir-tilsette-lonnsutgifter-2020-2023-source-selection" :golden-chunk-ids])))
      (is (re-find #"2018"
                   (get-in by-id ["digdir-utforte-lonnsutgifter-2018-2023-inference-drift" :expected-answer-pattern]))))))

(deftest agent-budget-benchmark-includes-profile-inspection
  (testing "Live budget benchmark payload carries compact retrieval and read summaries per profile"
    (with-redefs [diagnostics/read-agent-budget-suite (fn [_]
                                                        [{:id "case-1"
                                                          :query "Hvor mange arsverk hadde Digdir i 2022?"
                                                          :budget-dimension :read-chars
                                                          :require-improvement? true
                                                          :current-budget {:max-read-content-length 12000}
                                                          :relaxed-budget {:max-read-content-length 16000}}])
                  diagnostics/resolve-diagnostics-context! (fn [_opts & _]
                                                             {:conn nil
                                                              :dataset-ref test-dataset-ref
                                                              :agent-id test-agent-id
                                                              :skill-graph-id "builtin/agent-rag"
                                                              :pipeline-config {:id "cfg"}
                                                              :collection-names {:docs-collection "docs"
                                                                                 :chunks-collection "chunks"
                                                                                 :phrases-collection "phrases"}
                                                              :opts {:tenant "ka"
                                                                     :dataset-config-key "dev"
                                                                     :tenant-config-key "dev"
                                                                     :runtime-config-key "dev"}})
                  diagnostics/typesense-preflight-error (fn [_opts] nil)
                  diagnostics/run-agent-budget-profile (fn [_ctx case-def profile budget]
                                                         {:profile profile
                                                          :query (:query case-def)
                                                          :pass (= profile :relaxed)
                                                          :inspection {:searches [{:pass 1
                                                                                   :queries ["arsverk digdir 2022"]
                                                                                   :result-count 2}]
                                                                       :top-retrieved-chunks [{:chunk-id "6a80d6499075"
                                                                                               :title "Digdir arsrapport 2022"}]
                                                                       :reads [{:operation 1
                                                                                :mode :chunk-ids
                                                                                :content-length (if (= profile :current) 10400 12600)
                                                                                :chunk-ids ["6a80d6499075"]}]
                                                                       :largest-read {:operation 1
                                                                                      :mode :chunk-ids
                                                                                      :content-length (if (= profile :current) 10400 12600)
                                                                                      :chunk-ids ["6a80d6499075"]}}
                                                          :read-content-length (:max-read-content-length budget)})]
      (let [payload (parse-output-edn
                     (with-out-str
                        (diagnostics/agent-budget-benchmark
                        {:dataset-ref test-dataset-ref
                         :agent-id test-agent-id
                         :cli-opts ["--suite" "fixture.edn"
                                    "--fail-on-gate" "false"]})))
            row (first (:results payload))]
        (is (= :read-chars (:budget-dimension row)))
        (is (true? (:require-improvement? row)))
        (is (= [{:pass 1
                 :queries ["arsverk digdir 2022"]
                 :result-count 2}]
               (get-in row [:current :inspection :searches])))
        (is (= [{:chunk-id "6a80d6499075"
                 :title "Digdir arsrapport 2022"}]
               (get-in row [:relaxed :inspection :top-retrieved-chunks])))
        (is (= {:operation 1
                :mode :chunk-ids
                :content-length 12600
                :chunk-ids ["6a80d6499075"]}
               (get-in row [:relaxed :inspection :largest-read])))
        (is (true? (get-in row [:relaxed :pass])))
        (is (true? (:improved row)))))))

(deftest agent-budget-benchmark-emits-progress-lines-when-enabled
  (testing "Live budget benchmark can print per-case progress without breaking the final EDN payload"
    (with-redefs [diagnostics/read-agent-budget-suite (fn [_]
                                                        [{:id "case-1"
                                                          :query "Hvor mange arsverk hadde Digdir i 2022?"
                                                          :budget-dimension :search-pass
                                                          :require-improvement? true
                                                          :current-budget {:max-search-passes 4}
                                                          :relaxed-budget {:max-search-passes 5}}])
                  diagnostics/resolve-diagnostics-context! (fn [_opts & _]
                                                             {:conn nil
                                                              :dataset-ref test-dataset-ref
                                                              :agent-id test-agent-id
                                                              :skill-graph-id "builtin/agent-rag"
                                                              :pipeline-config {:id "cfg"}
                                                              :collection-names {:docs-collection "docs"
                                                                                 :chunks-collection "chunks"
                                                                                 :phrases-collection "phrases"}
                                                              :opts {:tenant "ka"
                                                                     :dataset-config-key "dev"
                                                                     :tenant-config-key "dev"
                                                                     :runtime-config-key "dev"}})
                  diagnostics/typesense-preflight-error (fn [_opts] nil)
                  diagnostics/run-agent-budget-profile (fn [_ctx case-def profile _budget]
                                                         {:profile profile
                                                          :query (:query case-def)
                                                          :pass (= profile :relaxed)
                                                          :search-passes (if (= profile :relaxed) 2 1)
                                                          :read-operations 1
                                                          :read-content-length (if (= profile :relaxed) 14000 12000)})]
      (let [out-writer (java.io.StringWriter.)
            err-writer (java.io.StringWriter.)]
        (binding [*out* out-writer
                  *err* err-writer]
          (diagnostics/agent-budget-benchmark
           {:dataset-ref test-dataset-ref
            :agent-id test-agent-id
            :cli-opts ["--suite" "fixture.edn"
                       "--fail-on-gate" "false"
                       "--progress" "true"]}))
        (let [progress-lines (str/split-lines (str err-writer))
              payload (parse-output-edn (str out-writer))]
          (is (some #(re-find #"^PROGRESS case=case-1 dimension=search-pass require-improvement=true status=starting" %) progress-lines))
          (is (some #(re-find #"^PROGRESS case=case-1 profile=current status=done" %) progress-lines))
          (is (some #(re-find #"^PROGRESS case=case-1 profile=relaxed status=done" %) progress-lines))
          (is (some #(re-find #"^PROGRESS case=case-1 status=completed require-improvement=true improved=true" %) progress-lines))
          (is (= 1 (get-in payload [:summary :improved]))))))))

(deftest agent-budget-benchmark-preserves-nested-step-errors
  (testing "Case errors keep the wrapped step-id and nested step error payload"
    (with-redefs [diagnostics/read-agent-budget-suite (fn [_]
                                                        [{:id "case-1"
                                                          :query "Hva var det offisielle tallet?"
                                                          :budget-dimension :canonical-pivot
                                                          :current-budget {:max-search-passes 4}
                                                          :relaxed-budget {:max-search-passes 5}}])
                  diagnostics/resolve-diagnostics-context! (fn [_opts & _]
                                                             {:conn nil
                                                              :dataset-ref test-dataset-ref
                                                              :agent-id test-agent-id
                                                              :skill-graph-id "builtin/agent-rag"
                                                              :pipeline-config {:id "cfg"}
                                                              :collection-names {:docs-collection "docs"
                                                                                 :chunks-collection "chunks"
                                                                                 :phrases-collection "phrases"}
                                                              :opts {:tenant "ka"
                                                                     :dataset-config-key "dev"
                                                                     :tenant-config-key "dev"
                                                                     :runtime-config-key "dev"}})
                  diagnostics/typesense-preflight-error (fn [_opts] nil)
                  diagnostics/run-agent-budget-profile (fn [_ctx _case-def profile _budget]
                                                         (if (= profile :current)
                                                           (throw (ex-info "Step execution failed"
                                                                           {:step-id :agent
                                                                            :error {:error-type :generation-failure
                                                                                    :error-message "Model returned malformed citations"}}))
                                                           {:profile profile
                                                            :pass false
                                                            :search-passes 1
                                                            :read-operations 1
                                                            :read-content-length 1200}))]
      (let [payload (parse-output-edn
                     (with-out-str
                        (diagnostics/agent-budget-benchmark
                        {:dataset-ref test-dataset-ref
                         :agent-id test-agent-id
                         :cli-opts ["--suite" "fixture.edn"
                                    "--fail-on-gate" "false"]})))
            row (first (:results payload))]
        (is (= 1 (get-in payload [:summary :error-count])))
        (is (false? (get-in payload [:summary :gate-pass])))
        (is (= "Step execution failed" (get-in row [:error :message])))
        (is (= :agent (get-in row [:error :step-id])))
        (is (= :generation-failure (get-in row [:error :step-error :error-type])))
        (is (re-find #"malformed citations"
                     (get-in row [:error :step-error :error-message])))))))

(deftest agent-budget-summary-counts-improvements-regressions-and-stable-runs
  (testing "Summary rolls up paired profile outcomes into the benchmark gate metrics"
    (is (= {:cases 4
            :error-count 0
            :current-pass 1
            :relaxed-pass 2
            :improved 1
            :regressed 0
            :required-improvement-cases 0
            :missing-required-improvement 0
            :stable-pass 1
            :stable-fail 2
            :gate-pass true}
           (diagnostics/agent-budget-summary
            [{:current {:pass false} :relaxed {:pass true} :improved true}
             {:current {:pass true} :relaxed {:pass true} :stable-pass true}
             {:current {:pass false} :relaxed {:pass false} :stable-fail true}
             {:current {:pass false} :relaxed {:pass false} :stable-fail true}])))))

(deftest agent-budget-summary-fails-when-required-improvement-is-missed
  (testing "A selected gate case must show current->relaxed improvement, not just non-regression"
    (is (= {:cases 2
            :error-count 0
            :current-pass 1
            :relaxed-pass 1
            :improved 0
            :regressed 0
            :required-improvement-cases 1
            :missing-required-improvement 1
            :stable-pass 1
            :stable-fail 1
            :gate-pass false}
           (diagnostics/agent-budget-summary
            [{:require-improvement? true
              :current {:pass true}
              :relaxed {:pass true}
              :stable-pass true}
             {:current {:pass false}
              :relaxed {:pass false}
              :stable-fail true}])))))

(deftest agent-budget-summary-counts-errors-against-the-gate
  (testing "Errored cases stay in the case count and fail the gate"
    (is (= {:cases 2
            :error-count 1
            :current-pass 0
            :relaxed-pass 0
            :improved 0
            :regressed 0
            :required-improvement-cases 0
            :missing-required-improvement 0
            :stable-pass 0
            :stable-fail 1
            :gate-pass false}
           (diagnostics/agent-budget-summary
            [{:id "ok"
              :current {:pass false}
              :relaxed {:pass false}
              :stable-fail true}
             {:id "boom"
              :error {:message "Step execution failed"
                      :type "class clojure.lang.ExceptionInfo"}}])))))

(deftest typesense-preflight-error-detects-unreachable-backend
  (testing "Preflight returns a typed infra-unavailable error when Typesense health fails"
    (with-redefs [ts-utils/make-ts-settings (fn [_opts]
                                              {:uri "http://typesense-test:8108"
                                              :key "secret"})
                  ts-client/health (fn [_settings]
                                     (throw (ex-info "Connection refused" {})))]
      (let [err (#'diagnostics/typesense-preflight-error {:tenant "ka"
                                                          :dataset-config-key "dev"
                                                          :tenant-config-key "dev"})]
        (is (= :infra-unavailable (:error-type err)))
        (is (= :typesense (:service err)))
        (is (re-find #"Connection refused" (:message err)))
        (is (= "class clojure.lang.ExceptionInfo"
               (get-in err [:data :exception-type])))))))

(deftest compact-throwable-stops-on-self-referential-cause
  (testing "Compact throwable truncates pathological cause chains instead of recursing forever"
    (let [err (proxy [Exception] ["boom"]
                (getCause [] this))
          compact (#'diagnostics/compact-throwable err)]
      (is (= "boom" (:message compact)))
      (is (true? (:cause-truncated? compact)))
      (is (nil? (:cause compact))))))

(deftest agent-budget-benchmark-emits-infra-payload-on-preflight-failure
  (testing "Live budget benchmark aborts early with an infra payload instead of case results"
    (with-redefs [diagnostics/read-agent-budget-suite (fn [_] [{:id "case-1" :query "q"}])
                  diagnostics/resolve-diagnostics-context! (fn [_opts & _]
                                                             {:conn nil
                                                              :dataset-ref test-dataset-ref
                                                              :agent-id test-agent-id
                                                              :skill-graph-id "builtin/agent-rag"
                                                              :pipeline-config {:id "cfg"}
                                                              :collection-names {:docs-collection "docs"
                                                                                 :chunks-collection "chunks"
                                                                                 :phrases-collection "phrases"}
                                                              :opts {:tenant "ka"
                                                                     :dataset-config-key "dev"
                                                                     :tenant-config-key "dev"
                                                                     :runtime-config-key "dev"}})
                  diagnostics/typesense-preflight-error (fn [_opts]
                                                          {:error-type :infra-unavailable
                                                           :service :typesense
                                                           :message "Typesense preflight failed: Connection refused"})]
      (let [payload (parse-output-edn
                     (with-out-str
                        (diagnostics/agent-budget-benchmark
                        {:dataset-ref test-dataset-ref
                         :agent-id test-agent-id
                         :cli-opts ["--suite" "fixture.edn"
                                    "--fail-on-gate" "false"]})))]
        (is (= :infra-unavailable (get-in payload [:error :error-type])))
        (is (= :typesense (get-in payload [:error :service])))
        (is (nil? (:results payload)))))))

(deftest normalize-benchmark-case-extracts-source-slice
  (testing "Source slice is normalized to a lowercase keyword like language"
    (let [raw {:id "c-1"
               :query "q"
               :golden-chunk-ids ["abc"]
               :language "NO"
               :source-slice "Altinn-Docs"}
          normalized (#'diagnostics/normalize-benchmark-case 0 raw)]
      (is (= :no (:language normalized)))
      (is (= :altinn-docs (:source-slice normalized)))))
  (testing "Source slice accepts the :source alias and falls back to nil when absent"
    (is (= :digdir-docs
           (-> (#'diagnostics/normalize-benchmark-case 0 {:id "c" :query "q" :source :digdir-docs})
               :source-slice)))
    (is (nil? (:source-slice (#'diagnostics/normalize-benchmark-case 0 {:id "c" :query "q"}))))))

(deftest slice-summary-aggregates-by-arbitrary-key
  (testing "slice-summary groups per-case metrics by any slice key"
    (let [results [{:error nil
                    :language :no
                    :source-slice :altinn-docs
                    :golden [{:rerank-position 1 :context-position 1}]
                    :primary-metrics {:rank 1 :reciprocal-rank 1.0}}
                   {:error nil
                    :language :no
                    :source-slice :digdir-docs
                    :golden [{:rerank-position 3 :context-position nil}]
                    :primary-metrics {:rank 3 :reciprocal-rank (/ 1.0 3)}}
                   {:error nil
                    :language :en
                    :source-slice :altinn-docs
                    :golden [{:rerank-position nil :context-position nil}]
                    :primary-metrics {:rank nil :reciprocal-rank 0.0}}
                   {:error {:message "boom"}
                    :language :no
                    :source-slice :altinn-docs
                    :golden [{:rerank-position 1}]}]
          by-source (#'diagnostics/slice-summary :source-slice results)
          by-language (#'diagnostics/slice-summary :language results)]
      (is (= 2 (get-in by-source [:altinn-docs :cases])) "Error rows are excluded")
      (is (= 1 (get-in by-source [:altinn-docs :golden-present-in-retrank])))
      (is (= 1 (get-in by-source [:digdir-docs :cases])))
      (is (= 2 (get-in by-language [:no :cases])))
      (is (= 1 (get-in by-language [:en :cases]))))))

(deftest rerank-benchmark-emits-infra-payload-on-preflight-failure
  (testing "Rerank benchmark aborts early with an infra payload instead of benchmark rows"
    (with-redefs [diagnostics/read-benchmark-suite (fn [_] [{:id "case-1" :query "q"}])
                  diagnostics/resolve-diagnostics-context! (fn [_opts & _]
                                                             {:conn nil
                                                              :dataset-ref test-dataset-ref
                                                              :pipeline-config {:id "cfg"}
                                                              :collection-names {:docs-collection "docs"
                                                                                 :chunks-collection "chunks"
                                                                                 :phrases-collection "phrases"}
                                                              :opts {:tenant "ka"
                                                                     :dataset-config-key "dev"
                                                                     :tenant-config-key "dev"
                                                                     :runtime-config-key "dev"}})
                  diagnostics/typesense-preflight-error (fn [_opts]
                                                          {:error-type :infra-unavailable
                                                           :service :typesense
                                                           :message "Typesense preflight failed: Connection refused"})]
      (let [payload (parse-output-edn
                     (with-out-str
                       (diagnostics/rerank-benchmark
                        {:dataset-ref test-dataset-ref
                         :cli-opts ["--suite" "fixture.edn"
                                    "--fail-on-gate" "false"]})))]
        (is (= :infra-unavailable (get-in payload [:error :error-type])))
        (is (= :typesense (get-in payload [:error :service])))
        (is (nil? (:results payload)))))))

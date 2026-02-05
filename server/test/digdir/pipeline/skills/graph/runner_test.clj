(ns digdir.pipeline.skills.graph.runner-test
  "Integration tests for the skill graph runner.

   These tests verify:
   - Topological sorting of graph steps
   - Input resolution from graph inputs and step outputs
   - Step execution with mocked skills
   - Error handling and conditional execution"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [digdir.pipeline.skills.graph.runner :as runner]
            [digdir.pipeline.skills.graph.schema :as schema]
            [digdir.rag.skills.core :as skills]))

;; =============================================================================
;; Mock Skills for Testing
;; =============================================================================

(def mock-skill-executions
  "Atom to track skill executions during tests"
  (atom []))

(defn mock-query-planner-execute
  "Mock query planner that returns search phrases"
  [ctx]
  (swap! mock-skill-executions conj {:skill :test/query-planner :ctx ctx})
  (skills/success-result
    {:search-phrases ["phrase 1" "phrase 2" "phrase 3"]}
    {:duration-ms 50}))

(defn mock-retrieval-execute
  "Mock retrieval that returns chunks"
  [ctx]
  (swap! mock-skill-executions conj {:skill :test/retrieval :ctx ctx})
  (skills/success-result
    {:chunks [{:chunk-id "1" :content "test content 1"}
              {:chunk-id "2" :content "test content 2"}]
     :search-attribution {:phrase 10 :metadata 5 :content 3}}
    {:duration-ms 100}))

(defn mock-synthesis-execute
  "Mock synthesis that returns a response"
  [ctx]
  (swap! mock-skill-executions conj {:skill :test/synthesis :ctx ctx})
  (let [chunks (get-in ctx [:inputs :chunks] [])]
    (skills/success-result
      {:response (str "Answer based on " (count chunks) " chunks")
       :citations [{:chunk-id "1"}]}
      {:duration-ms 200})))

(defn mock-failing-skill-execute
  "Mock skill that always fails"
  [ctx]
  (swap! mock-skill-executions conj {:skill :test/failing :ctx ctx})
  (skills/error-result
    :test-error
    "Intentional test failure"
    {:skill-id :test/failing}))

(defn register-mock-skills!
  "Register mock skills for testing"
  []
  (skills/register-skill!
    {:metadata {:skill-id :test/query-planner
                :name "Test Query Planner"
                :description "Mock query planner for testing"
                :category :query-transformation
                :inputs [:query]
                :outputs [:search-phrases]
                :parameters {}
                :required-services #{}}
     :execute mock-query-planner-execute})

  (skills/register-skill!
    {:metadata {:skill-id :test/retrieval
                :name "Test Retrieval"
                :description "Mock retrieval for testing"
                :category :retrieval
                :inputs [:queries]
                :outputs [:chunks :search-attribution]
                :parameters {}
                :required-services #{}}
     :execute mock-retrieval-execute})

  (skills/register-skill!
    {:metadata {:skill-id :test/synthesis
                :name "Test Synthesis"
                :description "Mock synthesis for testing"
                :category :generation
                :inputs [:query :chunks]
                :outputs [:response :citations]
                :parameters {}
                :required-services #{}}
     :execute mock-synthesis-execute})

  (skills/register-skill!
    {:metadata {:skill-id :test/failing
                :name "Test Failing Skill"
                :description "Mock skill that always fails"
                :category :validation
                :inputs [:data]
                :outputs [:result]
                :parameters {}
                :required-services #{}}
     :execute mock-failing-skill-execute}))

(defn reset-test-state!
  "Reset all test state"
  []
  (reset! mock-skill-executions [])
  (skills/clear-registry!)
  (register-mock-skills!))

(defn with-mock-skills [f]
  "Test fixture that sets up mock skills"
  (reset-test-state!)
  (f)
  (skills/clear-registry!))

(use-fixtures :each with-mock-skills)

;; =============================================================================
;; Topological Sort Tests
;; =============================================================================

(deftest test-topological-sort-linear
  (testing "Linear graph sorts in order"
    (let [graph {:inputs [:query]
                 :outputs [:response]
                 :steps [{:id :step-a :skill :test/query-planner
                          :inputs {:query :$query}}
                         {:id :step-b :skill :test/retrieval
                          :inputs {:queries :step-a}}
                         {:id :step-c :skill :test/synthesis
                          :inputs {:query :$query :chunks [:step-b :chunks]}}]}
          order (runner/topological-sort graph)]
      (is (= [:step-a :step-b :step-c] order)))))

(deftest test-topological-sort-parallel-start
  (testing "Graph with parallel starting nodes"
    (let [graph {:inputs [:query :context]
                 :outputs [:response]
                 :steps [{:id :step-a :skill :test/query-planner
                          :inputs {:query :$query}}
                         {:id :step-b :skill :test/retrieval
                          :inputs {:queries :$context}}
                         {:id :step-c :skill :test/synthesis
                          :inputs {:query :$query
                                   :chunks [:step-a :search-phrases]
                                   :extra [:step-b :chunks]}}]}
          order (runner/topological-sort graph)]
      ;; Both a and b should come before c
      (is (< (.indexOf order :step-a) (.indexOf order :step-c)))
      (is (< (.indexOf order :step-b) (.indexOf order :step-c))))))

;; =============================================================================
;; Input Resolution Tests
;; =============================================================================

(deftest test-resolve-graph-input
  (testing "Resolves :$var to graph input value"
    (let [graph-inputs {:query "test query" :extra "extra value"}
          step-outputs {}
          resolved (runner/resolve-input-ref :$query graph-inputs step-outputs)]
      (is (= "test query" resolved)))))

(deftest test-resolve-step-output
  (testing "Resolves step-id to entire step outputs"
    (let [graph-inputs {}
          step-outputs {:step-a {:outputs {:phrases ["a" "b"]}}}
          resolved (runner/resolve-input-ref :step-a graph-inputs step-outputs)]
      (is (= {:phrases ["a" "b"]} resolved)))))

(deftest test-resolve-step-output-key
  (testing "Resolves [step-id :key] to specific output"
    (let [graph-inputs {}
          step-outputs {:step-a {:outputs {:phrases ["a" "b"] :count 2}}}
          resolved (runner/resolve-input-ref [:step-a :phrases] graph-inputs step-outputs)]
      (is (= ["a" "b"] resolved)))))

(deftest test-resolve-step-inputs
  (testing "Resolves all inputs for a step"
    (let [step {:id :step-c
                :skill :test/synthesis
                :inputs {:query :$query
                         :chunks [:step-b :chunks]
                         :static-value 42}}
          graph-inputs {:query "user question"}
          step-outputs {:step-b {:outputs {:chunks [{:id 1}]}}}
          resolved (runner/resolve-step-inputs step graph-inputs step-outputs)]
      (is (= "user question" (:query resolved)))
      (is (= [{:id 1}] (:chunks resolved)))
      (is (= 42 (:static-value resolved))))))

;; =============================================================================
;; Graph Execution Tests
;; =============================================================================

(deftest test-run-simple-graph
  (testing "Executes a simple linear graph"
    (let [graph {:inputs [:query]
                 :outputs [:response]
                 :steps [{:id :plan :skill :test/query-planner
                          :inputs {:query :$query}}
                         {:id :retrieve :skill :test/retrieval
                          :inputs {:queries [:plan :search-phrases]}}
                         {:id :synthesize :skill :test/synthesis
                          :inputs {:query :$query
                                   :chunks [:retrieve :chunks]}}]}
          inputs {:query "What is machine learning?"}
          opts {:tenant "test" :environment "dev"}
          result (runner/run-graph graph inputs opts)]

      ;; Check outputs were collected
      (is (some? (:outputs result)))
      (is (string? (get-in result [:outputs :response])))

      ;; Verify execution order
      (is (= 3 (count @mock-skill-executions)))
      (is (= :test/query-planner (:skill (first @mock-skill-executions))))
      (is (= :test/retrieval (:skill (second @mock-skill-executions))))
      (is (= :test/synthesis (:skill (nth @mock-skill-executions 2)))))))

(deftest test-run-graph-with-step-outputs
  (testing "Step outputs propagate correctly"
    (let [graph {:inputs [:query]
                 :outputs [:response :search-phrases]
                 :steps [{:id :plan :skill :test/query-planner
                          :inputs {:query :$query}}
                         {:id :synthesize :skill :test/synthesis
                          :inputs {:query :$query
                                   :chunks []}}]}
          inputs {:query "Test"}
          opts {:tenant "test" :environment "dev"}
          result (runner/run-graph graph inputs opts)]

      ;; Check that search-phrases from first step are in outputs
      (is (= ["phrase 1" "phrase 2" "phrase 3"]
             (get-in result [:outputs :search-phrases]))))))

;; =============================================================================
;; Conditional Execution Tests
;; =============================================================================

(deftest test-conditional-step-skipped
  (testing "Step with false condition is skipped"
    (let [graph {:inputs [:query :should-expand]
                 :outputs [:response]
                 :steps [{:id :plan :skill :test/query-planner
                          :inputs {:query :$query}
                          :condition :$should-expand}
                         {:id :synthesize :skill :test/synthesis
                          :inputs {:query :$query :chunks []}}]}
          inputs {:query "Test" :should-expand false}
          opts {:tenant "test" :environment "dev"}
          result (runner/run-graph graph inputs opts)]

      ;; Only synthesis should have executed
      (is (= 1 (count @mock-skill-executions)))
      (is (= :test/synthesis (:skill (first @mock-skill-executions))))

      ;; Skipped step should be marked
      (is (true? (get-in result [:step-results :plan :metadata :skipped]))))))

(deftest test-conditional-step-executed
  (testing "Step with true condition executes"
    (let [graph {:inputs [:query :should-expand]
                 :outputs [:response]
                 :steps [{:id :plan :skill :test/query-planner
                          :inputs {:query :$query}
                          :condition :$should-expand}
                         {:id :synthesize :skill :test/synthesis
                          :inputs {:query :$query :chunks []}}]}
          inputs {:query "Test" :should-expand true}
          opts {:tenant "test" :environment "dev"}
          result (runner/run-graph graph inputs opts)]

      ;; Both steps should have executed
      (is (= 2 (count @mock-skill-executions))))))

;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest test-error-on-fail-default
  (testing "Step failure throws by default"
    (let [graph {:inputs [:data]
                 :outputs [:result]
                 :steps [{:id :fail-step :skill :test/failing
                          :inputs {:data :$data}}]}
          inputs {:data "test"}
          opts {:tenant "test" :environment "dev"}]

      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Step execution failed"
                            (runner/run-graph graph inputs opts))))))

(deftest test-error-on-skip
  (testing "Step failure with on-error :skip continues"
    (let [graph {:inputs [:data :query]
                 :outputs [:response]
                 :steps [{:id :fail-step :skill :test/failing
                          :inputs {:data :$data}
                          :on-error :skip}
                         {:id :synthesize :skill :test/synthesis
                          :inputs {:query :$query :chunks []}}]}
          inputs {:data "test" :query "question"}
          opts {:tenant "test" :environment "dev"}
          result (runner/run-graph graph inputs opts)]

      ;; Both steps executed
      (is (= 2 (count @mock-skill-executions)))
      ;; Synthesis still produced output
      (is (some? (get-in result [:outputs :response]))))))

(deftest test-error-on-default
  (testing "Step failure with on-error :default uses empty outputs"
    (let [graph {:inputs [:data :query]
                 :outputs [:response :result]
                 :steps [{:id :fail-step :skill :test/failing
                          :inputs {:data :$data}
                          :on-error :default}
                         {:id :synthesize :skill :test/synthesis
                          :inputs {:query :$query :chunks []}}]}
          inputs {:data "test" :query "question"}
          opts {:tenant "test" :environment "dev"}
          result (runner/run-graph graph inputs opts)]

      ;; Defaulted step marked as such
      (is (true? (get-in result [:step-results :fail-step :metadata :defaulted]))))))

;; =============================================================================
;; Helper Function Tests
;; =============================================================================

(deftest test-get-graph-output
  (testing "Get specific output from result"
    (let [result {:outputs {:response "test answer"
                           :chunks [{:id 1}]}}]
      (is (= "test answer" (runner/get-graph-output result :response)))
      (is (= [{:id 1}] (runner/get-graph-output result :chunks)))
      (is (nil? (runner/get-graph-output result :nonexistent))))))

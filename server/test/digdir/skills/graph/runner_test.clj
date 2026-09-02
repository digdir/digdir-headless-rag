(ns digdir.skills.graph.runner-test
  "Integration tests for the skill graph runner.

   These tests verify:
   - Topological sorting of graph steps
   - Input resolution from graph inputs and step outputs
   - Step execution with mocked skills
   - Error handling and conditional execution"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [digdir.skills.graph.runner :as runner]
            [digdir.skills.graph.schema :as schema]
            [digdir.skills.context :as ctx]
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
    {:queries ["phrase 1" "phrase 2" "phrase 3"]}
    {:duration-ms 50}))

(defn mock-retrieval-execute
  "Mock retrieval that returns chunks"
  [ctx]
  (swap! mock-skill-executions conj {:skill :test/retrieval :ctx ctx})
  (skills/success-result
    {:chunks [{:chunk_id "1" :content_markdown "test content 1"}
              {:chunk_id "2" :content_markdown "test content 2"}]
     :search-attribution {:phrase 10 :metadata 5 :content 3 :merged 2}}
    {:duration-ms 100}))

(defn mock-synthesis-execute
  "Mock synthesis that returns a response"
  [ctx]
  (swap! mock-skill-executions conj {:skill :test/synthesis :ctx ctx})
  (let [chunks (get-in ctx [:inputs :chunks] [])]
    (skills/success-result
      {:response (str "Answer based on " (count chunks) " chunks")
       :citations [{:chunk_id "1"}]}
      {:duration-ms 200})))

(defn mock-summarization-execute
  "Mock summarization that returns a compact summary"
  [ctx]
  (swap! mock-skill-executions conj {:skill :test/summarization :ctx ctx})
  (skills/success-result
    {:summary (str "Summary for " (count (str (:content (get-in ctx [:inputs])))) " chars")
     :key-points ["one" "two"]}
    {:duration-ms 75}))

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
                :outputs [:queries]
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
    {:metadata {:skill-id :test/summarization
                :name "Test Summarization"
                :description "Mock summarization for testing"
                :category :generation
                :inputs [:content]
                :outputs [:summary :key-points]
                :parameters {}
                :required-services #{}}
     :execute mock-summarization-execute})

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

(defn with-mock-skills
  "Test fixture that sets up mock skills"
  [f]
  (reset-test-state!)
  (with-redefs [ctx/resolve-all-services (fn [_]
                                           {:typesense {}
                                            :azure-openai {}
                                            :colbert {}})]
    (f))
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
                                   :chunks [:step-a :queries]
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

(deftest test-resolve-step-output-nested-path
  (testing "Resolves [step-id :k1 :k2 ...] into nested output structures"
    (let [graph-inputs {}
          step-outputs {:eval {:outputs {:summary {:gate-pass true
                                                   :current-pass 1
                                                   :relaxed-pass 1}
                                         :results [{:id "c1"}]}}}]
      (is (= true
             (runner/resolve-input-ref [:eval :summary :gate-pass]
                                       graph-inputs step-outputs))
          "Two-level nested path")
      (is (= "c1"
             (runner/resolve-input-ref [:eval :results 0 :id]
                                       graph-inputs step-outputs))
          "Path with mixed key types (vector index)")
      (is (nil? (runner/resolve-input-ref [:eval :summary :missing :deeper]
                                          graph-inputs step-outputs))
          "Missing path segment returns nil rather than throwing"))))

(deftest test-resolve-graph-input-nested-path
  (testing "Resolves [:$var :k1 :k2 ...] into nested graph-input maps"
    (let [graph-inputs {:user {:profile {:name "Alice" :role :admin}}}
          step-outputs {}]
      (is (= "Alice"
             (runner/resolve-input-ref [:$user :profile :name]
                                       graph-inputs step-outputs))))))

(deftest test-resolve-step-inputs
  (testing "Resolves all inputs for a step"
    (let [step {:id :step-c
                :skill :test/synthesis
                :inputs {:query :$query
                         :chunks [:step-b :chunks]
                         :static-value 42}}
          graph-inputs {:query "user question"}
          step-outputs {:step-b {:outputs {:chunks [{:chunk_id "1"}]}}}
          resolved (runner/resolve-step-inputs step graph-inputs step-outputs)]
      (is (= "user question" (:query resolved)))
      (is (= [{:chunk_id "1"}] (:chunks resolved)))
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
                          :inputs {:queries [:plan :queries]}}
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
    (let [graph {:inputs [:query :empty-chunks]
                 :outputs [:response :queries]
                 :steps [{:id :plan :skill :test/query-planner
                          :inputs {:query :$query}}
                         {:id :synthesize :skill :test/synthesis
                          :inputs {:query :$query
                                   :chunks :$empty-chunks}}]}
          inputs {:query "Test"
                  :empty-chunks []}
          opts {:tenant "test" :environment "dev"}
          result (runner/run-graph graph inputs opts)]

      ;; Check that planned queries from first step are in outputs
      (is (= ["phrase 1" "phrase 2" "phrase 3"]
             (get-in result [:outputs :queries]))))))

(deftest test-run-graph-includes-stage-timings
  (testing "Graph execution metadata includes stage timings with skill IDs"
    (let [graph {:inputs [:content]
                 :outputs [:summary]
                 :steps [{:id :summarize :skill :test/summarization
                          :inputs {:content :$content}}]}
          inputs {:content "Summarize this text"}
          opts {:tenant "test" :environment "dev"}
          result (runner/run-graph graph inputs opts)]

      (is (= "Summary for 19 chars"
             (get-in result [:outputs :summary])))
      (is (= :test/summarization
             (get-in result [:execution-metadata :step-timings :summarize :skill-id])))
      (is (= :summarization
             (get-in result [:execution-metadata :step-timings :summarize :stage])))
      (is (= :summarize
             (get-in result [:execution-metadata :stage-timings 0 :step-id])))
      (is (= :test/summarization
             (get-in result [:execution-metadata :stage-timings 0 :skill-id]))))))

(deftest test-run-graph-emits-progress-events
  (testing "run-graph calls optional progress callback with ordered lifecycle events"
    (let [graph {:inputs [:query]
                 :outputs [:response]
                 :steps [{:id :plan :skill :test/query-planner
                         :inputs {:query :$query}}
                         {:id :retrieve :skill :test/retrieval
                          :inputs {:queries [:plan :queries]}}]}
          events (atom [])
          inputs {:query "What is machine learning?"}
          opts {:tenant "test"
                :environment "dev"
                :progress-fn #(swap! events conj %)}]
      (runner/run-graph graph inputs opts)
      (is (= [:step/started :step/completed :step/started :step/completed :graph/completed]
             (mapv :event @events)))
      (is (= [:plan :plan :retrieve :retrieve nil]
             (mapv :step-id @events)))
      (is (every? number? (map :duration-ms (filter #(contains? % :duration-ms) @events)))))))

;; =============================================================================
;; Conditional Execution Tests
;; =============================================================================

(deftest test-conditional-step-skipped
  (testing "Step with false condition is skipped"
    (let [graph {:inputs [:query :should-expand :empty-chunks]
                 :outputs [:response]
                 :steps [{:id :plan :skill :test/query-planner
                          :inputs {:query :$query}
                          :condition :$should-expand}
                         {:id :synthesize :skill :test/synthesis
                          :inputs {:query :$query :chunks :$empty-chunks}}]}
          inputs {:query "Test" :should-expand false :empty-chunks []}
          opts {:tenant "test" :environment "dev"}
          result (runner/run-graph graph inputs opts)]

      ;; Only synthesis should have executed
      (is (= 1 (count @mock-skill-executions)))
      (is (= :test/synthesis (:skill (first @mock-skill-executions))))

      ;; Skipped step should be marked
      (is (true? (get-in result [:step-results :plan :metadata :skipped]))))))

(deftest test-conditional-step-executed
  (testing "Step with true condition executes"
    (let [graph {:inputs [:query :should-expand :empty-chunks]
                 :outputs [:response]
                 :steps [{:id :plan :skill :test/query-planner
                          :inputs {:query :$query}
                          :condition :$should-expand}
                         {:id :synthesize :skill :test/synthesis
                          :inputs {:query :$query :chunks :$empty-chunks}}]}
          inputs {:query "Test" :should-expand true :empty-chunks []}
          opts {:tenant "test" :environment "dev"}
          _result (runner/run-graph graph inputs opts)]

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
    (let [graph {:inputs [:data :query :empty-chunks]
                 :outputs [:response]
                 :steps [{:id :fail-step :skill :test/failing
                          :inputs {:data :$data}
                          :on-error :skip}
                         {:id :synthesize :skill :test/synthesis
                          :inputs {:query :$query :chunks :$empty-chunks}}]}
          inputs {:data "test" :query "question" :empty-chunks []}
          opts {:tenant "test" :environment "dev"}
          result (runner/run-graph graph inputs opts)]

      ;; Both steps executed
      (is (= 2 (count @mock-skill-executions)))
      ;; Synthesis still produced output
      (is (some? (get-in result [:outputs :response]))))))

(deftest test-error-on-default
  (testing "Step failure with on-error :default uses empty outputs"
    (let [graph {:inputs [:data :query :empty-chunks]
                 :outputs [:response :result]
                 :steps [{:id :fail-step :skill :test/failing
                          :inputs {:data :$data}
                          :on-error :default}
                         {:id :synthesize :skill :test/synthesis
                          :inputs {:query :$query :chunks :$empty-chunks}}]}
          inputs {:data "test" :query "question" :empty-chunks []}
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
                           :chunks [{:chunk_id "1"}]}}]
      (is (= "test answer" (runner/get-graph-output result :response)))
      (is (= [{:chunk_id "1"}] (runner/get-graph-output result :chunks)))
      (is (nil? (runner/get-graph-output result :nonexistent))))))

;; =============================================================================
;; :foreach step tests
;; =============================================================================

(defn- mock-echo-execute
  "Mock skill that echoes its inputs back as outputs. Useful for testing
   iteration scope resolution."
  [ctx]
  (skills/success-result
   {:echoed (:inputs ctx)}
   {:duration-ms 1}))

(defn- mock-stringify-execute
  "Mock skill that turns its :input into a string, prefixed with the iteration
   index if provided. Used to confirm :$item / :$idx resolve correctly."
  [ctx]
  (let [{:keys [input idx]} (:inputs ctx)]
    (skills/success-result
     {:value (str (when idx (str idx ":")) (pr-str input))}
     {:duration-ms 1})))

(defn- mock-fail-on-execute
  "Mock skill that fails iff its :input equals the configured :fail-when value."
  [ctx]
  (let [{:keys [input fail-when]} (:inputs ctx)]
    (if (= input fail-when)
      (skills/error-result :test/intentional "fail-when matched" {:input input})
      (skills/success-result {:value input} {:duration-ms 1}))))

(defn- register-foreach-test-skills! []
  (skills/register-skill!
   {:metadata {:skill-id :test/echo
               :name "Echo"
               :description "Echoes inputs to outputs"
               :category :augmentation
               :inputs [:input]
               :outputs [:echoed]
               :parameters {}
               :required-services #{}}
    :execute mock-echo-execute})
  (skills/register-skill!
   {:metadata {:skill-id :test/stringify
               :name "Stringify"
               :description "Prints inputs with idx"
               :category :augmentation
               :inputs [:input :idx]
               :outputs [:value]
               :parameters {}
               :required-services #{}}
    :execute mock-stringify-execute})
  (skills/register-skill!
   {:metadata {:skill-id :test/fail-on
               :name "Fail-on"
               :description "Fails when :input equals :fail-when"
               :category :augmentation
               :inputs [:input :fail-when]
               :outputs [:value]
               :parameters {}
               :required-services #{}}
    :execute mock-fail-on-execute}))

(defn- collect-source
  "A regular step that returns a fixed vector under :items so foreach has
   something to iterate over."
  [items]
  (let [skill-id (keyword "test" (str "source-" (gensym)))]
    (skills/register-skill!
     {:metadata {:skill-id skill-id
                 :name "Source"
                 :description "Emits a fixed collection"
                 :category :augmentation
                 :inputs []
                 :outputs [:items]
                 :parameters {}
                 :required-services #{}}
      :execute (fn [_ctx] (skills/success-result {:items items} {}))})
    skill-id))

(deftest test-foreach-collects-vector
  (testing "Foreach runs the inner skill once per element and collects outputs as a vector"
    (register-foreach-test-skills!)
    (let [src-skill (collect-source ["a" "b" "c"])
          graph {:id :test/foreach-basic
                 :inputs []
                 :outputs [:echoed-items]
                 :steps [{:id :src
                          :skill src-skill
                          :inputs {}}
                         {:id :loop
                          :foreach {:over [:src :items]}
                          :do {:skill :test/echo
                               :inputs {:input :$item}}
                          :collect-as :echoed-items}]}
          result (runner/run-graph graph {} {})]
      (is (= 3 (count (get-in result [:outputs :echoed-items]))))
      (is (= ["a" "b" "c"]
             (mapv #(get-in % [:echoed :input]) (get-in result [:outputs :echoed-items])))))))

(deftest test-foreach-resolves-index
  (testing "Foreach exposes :$idx when :as-index is set"
    (register-foreach-test-skills!)
    (let [src-skill (collect-source ["x" "y"])
          graph {:id :test/foreach-idx
                 :inputs []
                 :outputs [:lines]
                 :steps [{:id :src
                          :skill src-skill
                          :inputs {}}
                         {:id :loop
                          :foreach {:over [:src :items]
                                    :as :item
                                    :as-index :idx}
                          :do {:skill :test/stringify
                               :inputs {:input :$item
                                        :idx :$idx}}
                          :collect-as :lines}]}
          result (runner/run-graph graph {} {})]
      (is (= ["0:\"x\"" "1:\"y\""]
             (mapv :value (get-in result [:outputs :lines])))))))

(deftest test-foreach-on-error-fail
  (testing ":on-error :fail aborts the whole foreach on the first failure"
    (register-foreach-test-skills!)
    (let [src-skill (collect-source [1 2 3])
          graph {:id :test/foreach-fail
                 :inputs []
                 :outputs [:values]
                 :steps [{:id :src :skill src-skill :inputs {}}
                         {:id :loop
                          :foreach {:over [:src :items]}
                          :do {:skill :test/fail-on
                               :inputs {:input :$item
                                        :fail-when 2}}
                          :collect-as :values
                          :on-error :fail}]}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (runner/run-graph graph {} {}))))))

(deftest test-foreach-on-error-skip
  (testing ":on-error :skip drops failed iterations from the collected vector"
    (register-foreach-test-skills!)
    (let [src-skill (collect-source [1 2 3 2 4])
          graph {:id :test/foreach-skip
                 :inputs []
                 :outputs [:values]
                 :steps [{:id :src :skill src-skill :inputs {}}
                         {:id :loop
                          :foreach {:over [:src :items]}
                          :do {:skill :test/fail-on
                               :inputs {:input :$item
                                        :fail-when 2}}
                          :collect-as :values
                          :on-error :skip}]}
          result (runner/run-graph graph {} {})]
      (is (= [1 3 4] (mapv :value (get-in result [:outputs :values]))))
      (is (= 5 (-> result :step-results :loop :metadata :iteration-count)))
      (is (= 3 (-> result :step-results :loop :metadata :collected-count))))))

(deftest test-foreach-on-error-default
  (testing ":on-error :default inserts an empty outputs map in the failed slot"
    (register-foreach-test-skills!)
    (let [src-skill (collect-source [1 2 3])
          graph {:id :test/foreach-default
                 :inputs []
                 :outputs [:values]
                 :steps [{:id :src :skill src-skill :inputs {}}
                         {:id :loop
                          :foreach {:over [:src :items]}
                          :do {:skill :test/fail-on
                               :inputs {:input :$item
                                        :fail-when 2}}
                          :collect-as :values
                          :on-error :default}]}
          result (runner/run-graph graph {} {})
          values (get-in result [:outputs :values])]
      (is (= 3 (count values)))
      (is (= 1 (:value (nth values 0))))
      (is (= {} (nth values 1)))
      (is (= 3 (:value (nth values 2)))))))

(deftest test-foreach-empty-collection
  (testing "Foreach over an empty :over collection produces an empty vector"
    (register-foreach-test-skills!)
    (let [src-skill (collect-source [])
          graph {:id :test/foreach-empty
                 :inputs []
                 :outputs [:values]
                 :steps [{:id :src :skill src-skill :inputs {}}
                         {:id :loop
                          :foreach {:over [:src :items]}
                          :do {:skill :test/echo
                               :inputs {:input :$item}}
                          :collect-as :values}]}
          result (runner/run-graph graph {} {})]
      (is (= [] (get-in result [:outputs :values]))))))

(deftest test-foreach-input-refs-include-over-and-inner
  (testing "extract-input-refs picks up both :foreach/:over and inner :do/:inputs refs (minus iteration scope)"
    (let [step {:id :loop
                :foreach {:over [:src :items] :as :item :as-index :idx}
                :do {:skill :test/stringify
                     :inputs {:input :$item
                              :idx :$idx
                              :context :$user-query
                              :prior [:src :items]}}
                :collect-as :lines}
          refs (schema/extract-input-refs step)]
      (is (contains? refs :src))
      (is (contains? refs :$user-query))
      (is (not (contains? refs :$item)))
      (is (not (contains? refs :$idx))))))

;; =============================================================================
;; :sub-graph step tests (Phase 2.2)
;; =============================================================================

(defn- register-child-graph!
  "Register a tiny child graph: one :test/echo step that echoes its :input
   into an :echoed output. Returns the graph id."
  [graph-id]
  (let [child {:id graph-id
               :name "Child echo graph"
               :description "echoes the :input graph input as :echoed output"
               :inputs [:input]
               :outputs [:echoed]
               :steps [{:id :inner-echo
                        :skill :test/echo
                        :inputs {:input :$input}}]}]
    (require 'digdir.skills.templates.core)
    (let [reg! (resolve 'digdir.skills.templates.core/register-skill-graph!)]
      (reg! {:id graph-id
             :name "child"
             :description "child"
             :graph child}))
    graph-id))

(defn- unregister-graph!
  [graph-id]
  (require 'digdir.skills.templates.core)
  (let [unreg! (resolve 'digdir.skills.templates.core/unregister-skill-graph!)]
    (unreg! graph-id)))

(deftest test-sub-graph-runs-child-and-returns-outputs
  (testing "A sub-graph step runs the referenced child graph and exposes its outputs"
    (register-foreach-test-skills!)
    (let [child-id (register-child-graph! :test/child-echo)]
      (try
        (let [parent {:id :test/parent
                      :inputs [:topic]
                      :outputs [:wrapped]
                      :steps [{:id :run-child
                               :sub-graph {:graph-id child-id
                                           :inputs {:input :$topic}}}]}
              result (runner/run-graph parent {:topic "hello"} {})
              outputs (get result :outputs)]
          (is (contains? outputs :echoed))
          (is (= "hello" (get-in outputs [:echoed :input])))
          (is (= child-id
                 (get-in result [:step-results :run-child :metadata :child-graph-id]))))
        (finally (unregister-graph! child-id))))))

(deftest test-foreach-with-sub-graph-do
  (testing "Foreach :do can be a sub-graph invocation (not just a single skill)"
    (register-foreach-test-skills!)
    (let [child-id (register-child-graph! :test/child-echo-foreach)]
      (try
        (let [parent {:id :test/parent-foreach-subgraph
                      :inputs [:items]
                      :outputs [:results]
                      :steps [{:id :per-item
                               :foreach {:over :$items :as :item}
                               :do {:sub-graph {:graph-id child-id
                                                :inputs {:input :$item}}}
                               :collect-as :results}]}
              result (runner/run-graph parent {:items ["a" "b" "c"]} {})
              outputs (:outputs result)
              collected (:results outputs)]
          (is (= 3 (count collected))
              "One outcome per input element")
          (is (= ["a" "b" "c"]
                 (mapv #(get-in % [:echoed :input]) collected))
              "Each iteration ran the child graph and produced echoed output"))
        (finally (unregister-graph! child-id))))))

(deftest test-sub-graph-missing-id-fails
  (testing "A sub-graph step referencing an unregistered graph-id fails with a clear error"
    (register-foreach-test-skills!)
    (let [parent {:id :test/parent
                  :inputs []
                  :outputs []
                  :steps [{:id :run-child
                           :sub-graph {:graph-id :test/does-not-exist
                                       :inputs {}}}]}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (runner/run-graph parent {} {}))))))

(deftest test-sub-graph-input-refs-extracted
  (testing "extract-input-refs picks up refs from :sub-graph/:inputs and not from the child"
    (let [step {:id :run-child
                :sub-graph {:graph-id :test/some-child
                            :inputs {:input :$topic
                                     :prior [:setup :value]}}}
          refs (schema/extract-input-refs step)]
      (is (contains? refs :$topic))
      (is (contains? refs :setup))
      (is (not (contains? refs :test/some-child))))))

(deftest test-sub-graph-input-resolution
  (testing "Inputs declared on the sub-graph step are resolved against the parent and passed verbatim to the child"
    (register-foreach-test-skills!)
    (let [child-id (register-child-graph! :test/child-echo-2)]
      (try
        (let [src-skill (collect-source ["alpha"])
              parent {:id :test/parent
                      :inputs []
                      :outputs [:echoed]
                      :steps [{:id :src :skill src-skill :inputs {}}
                              {:id :run-child
                               :sub-graph {:graph-id child-id
                                           :inputs {:input [:src :items]}}}]}
              result (runner/run-graph parent {} {})]
          ;; child graph took the :items vector verbatim
          (is (= ["alpha"] (get-in result [:outputs :echoed :input]))))
        (finally (unregister-graph! child-id))))))

;; =============================================================================
;; :loop step tests (Phase 2.3)
;; =============================================================================

(defn- mock-counter-execute
  "Mock skill that reads :n from :inputs and emits {:n (inc n) :done? (>= (inc n) limit)}.
   Limit is read from :limit. Useful for testing until-output break semantics."
  [ctx]
  (let [{:keys [n limit]} (:inputs ctx)
        n+ (inc (or n 0))]
    (skills/success-result
     {:n n+ :done? (>= n+ (or limit 3))}
     {:duration-ms 1})))

(defn- register-counter-skill! []
  (skills/register-skill!
   {:metadata {:skill-id :test/counter
               :name "Counter"
               :description "Increments a counter; flags :done? when limit reached"
               :category :augmentation
               :inputs [:n :limit]
               :outputs [:n :done?]
               :parameters {}
               :required-services #{}}
    :execute mock-counter-execute}))

(deftest test-loop-stops-on-until-output
  (testing "Loop step breaks AFTER the iteration whose outputs has truthy :until-output key"
    (register-foreach-test-skills!)
    (register-counter-skill!)
    (let [graph {:id :test/loop-until
                 :inputs []
                 :outputs [:iters]
                 :steps [{:id :counter
                          :loop {:max-iterations 10
                                 :until-output :done?
                                 :iteration-as :iter}
                          :do {:skill :test/counter
                               :inputs {:n [:$iter :n]
                                        :limit 4}}
                          :collect-as :iters}]}
          result (runner/run-graph graph {} {})
          iters (get-in result [:outputs :iters])
          loop-meta (get-in result [:step-results :counter :metadata])]
      ;; 4 iterations: 0→1, 1→2, 2→3, 3→4 (done? becomes true at iteration #3)
      (is (= 4 (count iters)))
      (is (= [1 2 3 4] (mapv :n iters)))
      (is (false? (:exhausted? loop-meta)))
      (is (= :until-output-truthy (:break-reason loop-meta))))))

(deftest test-loop-max-iterations-cap
  (testing "Loop stops at :max-iterations when :until-output is never truthy; metadata flags :exhausted?"
    (register-foreach-test-skills!)
    (register-counter-skill!)
    (let [graph {:id :test/loop-max
                 :inputs []
                 :outputs [:iters]
                 :steps [{:id :counter
                          :loop {:max-iterations 3
                                 :until-output :done?
                                 :iteration-as :iter}
                          :do {:skill :test/counter
                               :inputs {:n [:$iter :n]
                                        :limit 100}}
                          :collect-as :iters}]}
          result (runner/run-graph graph {} {})
          iters (get-in result [:outputs :iters])
          loop-meta (get-in result [:step-results :counter :metadata])]
      (is (= 3 (count iters)))
      (is (true? (:exhausted? loop-meta)))
      (is (= :max-iterations-reached (:break-reason loop-meta))))))

(deftest test-loop-exposes-iter-and-i
  (testing ":$iter (previous iteration's outputs) and :$i (current index) are resolvable inside :do/:inputs"
    (register-foreach-test-skills!)
    (register-counter-skill!)
    (let [graph {:id :test/loop-scope
                 :inputs []
                 :outputs [:rows]
                 :steps [{:id :rows
                          :loop {:max-iterations 3
                                 :iteration-as :iter
                                 :iteration-index-as :i}
                          :do {:skill :test/stringify
                               :inputs {:input [:$iter :n]
                                        :idx :$i}}
                          :collect-as :rows}]}
          result (runner/run-graph graph {} {})
          rows (get-in result [:outputs :rows])]
      ;; First iteration: prev-outputs is nil, so [:$iter :n] resolves to nil
      ;; Second/third: prev is also still nil since :test/stringify doesn't write :n
      (is (= 3 (count rows)))
      (is (= "0:nil" (:value (nth rows 0))))
      (is (= "1:nil" (:value (nth rows 1))))
      (is (= "2:nil" (:value (nth rows 2)))))))

(deftest test-loop-input-refs-extracted
  (testing "extract-input-refs picks up refs from :do/:inputs while filtering iteration-scope vars"
    (let [step {:id :rows
                :loop {:max-iterations 5
                       :until-output :done?
                       :iteration-as :iter
                       :iteration-index-as :i}
                :do {:skill :test/stringify
                     :inputs {:input [:$iter :n]
                              :idx :$i
                              :ext :$user-query
                              :prior [:setup :seed]}}
                :collect-as :rows}
          refs (schema/extract-input-refs step)]
      (is (contains? refs :$user-query))
      (is (contains? refs :setup))
      (is (not (contains? refs :$iter)))
      (is (not (contains? refs :$i))))))

;; =============================================================================
;; :select step tests (Phase 2.4a)
;; =============================================================================

(deftest test-select-runs-matching-branch
  (testing "Select runs the branch matching the :on dispatch value"
    (register-foreach-test-skills!)
    (let [src-skill (collect-source [:bingo])
          graph {:id :test/select-match
                 :inputs []
                 :outputs [:value]
                 :steps [{:id :src :skill src-skill :inputs {}}
                         {:id :route
                          :select {:on [:src :items]}
                          :branches {[:bingo] {:do {:skill :test/echo
                                                    :inputs {:input "matched"}}}
                                     :default {:do {:skill :test/echo
                                                    :inputs {:input "fallback"}}}}}]}
          result (runner/run-graph graph {} {})]
      (is (= "matched" (get-in result [:outputs :echoed :input])))
      (is (= [:bingo] (get-in result [:step-results :route :metadata :selected-branch]))))))

(deftest test-select-falls-back-to-default
  (testing "Select runs the :default branch when no exact match"
    (register-foreach-test-skills!)
    (let [graph {:id :test/select-default
                 :inputs []
                 :outputs [:value]
                 :steps [{:id :route
                          :select {:on "unknown"}
                          :branches {"a" {:do {:skill :test/echo
                                               :inputs {:input "a"}}}
                                     "b" {:do {:skill :test/echo
                                               :inputs {:input "b"}}}
                                     :default {:do {:skill :test/echo
                                                    :inputs {:input "default"}}}}}]}
          result (runner/run-graph graph {} {})]
      (is (= "default" (get-in result [:outputs :echoed :input])))
      (is (= :default (get-in result [:step-results :route :metadata :selected-branch]))))))

(deftest test-select-no-match-no-default-errors
  (testing "Select with no matching branch and no :default returns an error result"
    (register-foreach-test-skills!)
    (let [graph {:id :test/select-no-default
                 :inputs []
                 :outputs [:value]
                 :steps [{:id :route
                          :select {:on "unknown"}
                          :branches {"a" {:do {:skill :test/echo
                                               :inputs {:input "a"}}}}}]}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (runner/run-graph graph {} {}))))))

(deftest test-select-input-refs-extracted
  (testing "extract-input-refs picks up :select/:on plus every branch's :do inputs"
    (let [step {:id :route
                :select {:on [:gate :status]}
                :branches {:sufficient {:do {:skill :test/synth
                                             :inputs {:query :$user-query
                                                      :context [:retrieve :docs]}}}
                           :default {:do {:skill :test/continue
                                          :inputs {:hint [:gate :hint]}}}}}
          refs (schema/extract-input-refs step)]
      (is (contains? refs :gate))
      (is (contains? refs :$user-query))
      (is (contains? refs :retrieve)))))

;; =============================================================================
;; :dispatch-by-name step tests (Phase 2.4b)
;; =============================================================================

(defn- mock-tool-a-execute [ctx]
  (skills/success-result {:tool-result (str "A:" (:payload (:inputs ctx)))} {}))

(defn- mock-tool-b-execute [ctx]
  (skills/success-result {:tool-result (str "B:" (:payload (:inputs ctx)))} {}))

(defn- register-dispatch-test-skills! []
  (skills/register-skill!
   {:metadata {:skill-id :test/tool-a
               :name "Tool A"
               :description "First test tool"
               :category :augmentation
               :inputs [:payload]
               :outputs [:tool-result]
               :parameters {}
               :required-services #{}}
    :execute mock-tool-a-execute})
  (skills/register-skill!
   {:metadata {:skill-id :test/tool-b
               :name "Tool B"
               :description "Second test tool"
               :category :augmentation
               :inputs [:payload]
               :outputs [:tool-result]
               :parameters {}
               :required-services #{}}
    :execute mock-tool-b-execute}))

(deftest test-dispatch-by-name-runs-matched-skill
  (testing "Dispatch-by-name resolves :name through :registry and runs the matched skill"
    (register-foreach-test-skills!)
    (register-dispatch-test-skills!)
    (let [graph {:id :test/dispatch-runs
                 :inputs [:tool-name :payload :registry]
                 :outputs [:tool-result]
                 :steps [{:id :dispatch
                          :dispatch-by-name {:name :$tool-name
                                             :registry :$registry
                                             :inputs {:payload :$payload}}}]}
          result (runner/run-graph graph
                                   {:tool-name "alpha"
                                    :payload "hello"
                                    :registry {"alpha" :test/tool-a
                                               "beta"  :test/tool-b}}
                                   {})]
      (is (= "A:hello" (get-in result [:outputs :tool-result])))
      (is (= :test/tool-a (get-in result [:step-results :dispatch :metadata :resolved-skill-id])))
      (is (= "alpha" (get-in result [:step-results :dispatch :metadata :resolved-name]))))))

(deftest test-dispatch-by-name-unknown-name-errors
  (testing "Dispatch-by-name with a name not in the registry returns an error"
    (register-foreach-test-skills!)
    (register-dispatch-test-skills!)
    (let [graph {:id :test/dispatch-unknown
                 :inputs [:tool-name :registry]
                 :outputs []
                 :steps [{:id :dispatch
                          :dispatch-by-name {:name :$tool-name
                                             :registry :$registry}}]}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (runner/run-graph graph
                                     {:tool-name "ghost"
                                      :registry {"alpha" :test/tool-a}}
                                     {}))))))

(deftest test-dispatch-by-name-input-refs-extracted
  (testing "extract-input-refs picks up :name + :registry plus every value in :inputs"
    (let [step {:id :dispatch
                :dispatch-by-name {:name [:llm :tool-name]
                                   :registry :$tool-registry
                                   :inputs {:workspace :$workspace
                                            :iter [:react :iteration]
                                            :args [:llm :args]}}}
          refs (schema/extract-input-refs step)]
      (is (contains? refs :llm))
      (is (contains? refs :$tool-registry))
      (is (contains? refs :$workspace))
      (is (contains? refs :react)))))

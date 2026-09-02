(ns digdir.skills.events-test
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.skills.events :as events]))

(deftest emit-progress-invokes-callback
  (testing "emit-progress! forwards payload to the callback"
    (let [seen (atom nil)
          payload {:event :step/started :step-id :plan}]
      (events/emit-progress! #(reset! seen %) payload)
      (is (= payload @seen)))))

(deftest emit-progress-swallows-callback-errors
  (testing "emit-progress! does not throw when the callback fails"
    (is (nil? (events/emit-progress! (fn [_] (throw (ex-info "boom" {})))
                                     {:event :graph/completed})))))

(deftest constructors-shape-canonical-payloads
  (testing "shared constructors return the expected event shapes"
    (is (= {:event :step/completed
            :step-id :retrieve
            :skill-id :builtin/retrieval
            :duration-ms 42}
           (events/step-completed :retrieve :builtin/retrieval 42)))
    (is (= {:event :agent/tool-result
            :iteration 1
            :tool "search_documents"
            :ok? true
            :result-summary "Found 12 chunks"}
           (events/agent-tool-result 1 "search_documents" true "Found 12 chunks")))
    (is (= {:event :graph/completed
            :steps-executed 4
            :duration-ms 99}
           (events/graph-completed 4 99)))))

(deftest normalize-execution-event-prefers-canonical-key
  (testing "legacy :type execution events are normalized and validated"
    (is (= :stage/started
           (:event (events/normalize-execution-event {:type :stage/started :stage :plan}))))
    (is (= :stage/started
           (:event (events/normalize-execution-event {:event :stage/started :type :ignored :stage :plan}))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"missing required keys"
         (events/normalize-execution-event {:event :request/failed})))))

(deftest normalize-execution-event-rejects-missing-event-key
  (testing "events with neither :event nor :type are rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"missing :event"
         (events/normalize-execution-event {:stage :plan})))))

(deftest progress-translation-produces-playground-events
  (testing "canonical skill progress is translated to execution events"
    (is (= [(events/stage-started :skills-query-planning "Plan Queries")]
           (events/progress->execution-events {:event :step/started :step-id :plan})))
    (is (= [{:event :step/defaulted
             :step-id :skills-retrieval
             :skill-id :builtin/retrieval
             :duration-ms 12}]
           (events/progress->execution-events {:event :step/defaulted
                                               :step-id :retrieve
                                               :skill-id :builtin/retrieval
                                               :duration-ms 12})))
    (is (= [(events/tool-result {:tool "search_documents"
                                 :summary "Found 12 chunks"
                                 :ok? true
                                 :iteration 0})]
           (events/progress->execution-events {:event :agent/tool-result
                                               :tool "search_documents"
                                               :result-summary "Found 12 chunks"
                                               :ok? true
                                               :iteration 0})))))

;; --- Additional coverage ---

(deftest response-finalized-constructor
  (testing "zero-arity returns minimal event"
    (is (= {:event :response/finalized} (events/response-finalized))))
  (testing "one-arity merges payload under canonical :event key"
    (let [result (events/response-finalized {:text "done" :citations [1 2]})]
      (is (= :response/finalized (:event result)))
      (is (= "done" (:text result)))
      (is (= [1 2] (:citations result)))))
  (testing "payload :event key is overridden to :response/finalized"
    (is (= :response/finalized
           (:event (events/response-finalized {:event :something/else}))))))

(deftest emit-agent-error-constructor
  (testing "emit-agent-error produces :agent/error event kind"
    (is (= {:event :agent/error :iteration 3 :error "timeout"}
           (events/emit-agent-error 3 "timeout")))))

(deftest agent-exhausted-constructor
  (testing "agent-exhausted produces :agent/exhausted event kind"
    (is (= {:event :agent/exhausted :iteration 10 :max-iterations 10}
           (events/agent-exhausted 10 10)))))

(deftest stage-flow-key-classification
  (testing "agentic stages map to :agentic"
    (is (= :agentic (events/stage-flow-key :agent-iteration)))
    (is (= :agentic (events/stage-flow-key :agent-tool-call))))
  (testing "skills-only stages map to :skills"
    (is (= :skills (events/stage-flow-key :skills-retrieval)))
    (is (= :skills (events/stage-flow-key :skills-rerank))))
  (testing "removed classic stages fall back to :legacy"
    (is (= :legacy (events/stage-flow-key :phrase-search)))
    (is (= :legacy (events/stage-flow-key :rerank))))
  (testing "unknown stages fall back to :legacy"
    (is (= :legacy (events/stage-flow-key :unknown-stage)))))

(deftest flow-steps-returns-ordered-step-maps
  (testing "flow-steps returns stages with labels for the correct flow"
    (let [steps (events/flow-steps :agent-iteration)]
      (is (vector? steps))
      (is (every? #(and (:stage %) (:label %)) steps))
      (is (= :skills-init (:stage (first steps))))))
  (testing "unknown stage falls back to legacy flow"
    (let [steps (events/flow-steps :unknown)]
      (is (= :skills-query-planning (:stage (first steps)))))))

(deftest all-constructors-pass-validation
  (testing "every constructor produces an event that passes assert-canonical-execution-event!"
    (let [events [(events/request-started "r1" "hello")
                  (events/request-failed "oops")
                  (events/response-chunk "delta")
                  (events/response-finalized)
                  (events/response-finalized {:text "done"})
                  (events/stage-started :plan "Plan")
                  (events/stage-completed :plan "Plan")
                  (events/stage-completed :plan "Plan" 10)
                  (events/tool-called {:tool "search"})
                  (events/tool-result {:tool "search"})
                  (events/warning-raised {:code :test})
                  (events/step-started :plan :builtin/planner)
                  (events/step-completed :plan :builtin/planner 42)
                  (events/step-skipped :plan :builtin/planner)
                  (events/step-defaulted :plan :builtin/planner 5)
                  (events/step-failed :plan :builtin/planner 5 "err")
                  (events/graph-completed 3 100)
                  (events/agent-iteration-started 0 10)
                  (events/agent-tool-call 0 "search" {:q "hi"})
                  (events/agent-tool-result 0 "search" true "ok")
                  (events/agent-turn-completed 0 "thinking" [{:tool "search"}])
                  (events/agent-exhausted 5 5)
                  (events/emit-agent-error 2 "failed")
                  (events/agent-finalized 3 500)]]
      (doseq [e events]
        (is (= e (events/assert-canonical-execution-event! e))
            (str "validation failed for " (:event e)))))))

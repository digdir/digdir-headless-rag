(ns digdir.playground.action-trace-test
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.playground.action-trace :as action-trace]))

(deftest graph-progress-becomes-one-stable-action-record
  (let [started {:event :step/started
                 :step-id :plan
                 :skill-id :builtin/query-planner}
        completed {:event :step/completed
                   :step-id :plan
                   :skill-id :builtin/query-planner
                   :duration-ms 42
                   :outputs {:queries ["first" "second"]
                             :user-intent "intent"}}
        running-trace (action-trace/record-progress [] started)
        completed-trace (action-trace/record-progress running-trace completed)]
    (is (= 1 (count running-trace)))
    (is (= :running (get-in running-trace [0 :status])))
    (is (= 1 (count completed-trace)) "completion replaces rather than duplicates")
    (is (= {:id :plan
            :skill-id :builtin/query-planner
            :kind :plan
            :status :ok
            :duration-ms 42
            :result {:queries ["first" "second"]
                     :user-intent "intent"}}
           (first completed-trace)))))

(deftest action-results-are-semantic-and-bounded
  (testing "retrieval stores attribution rather than its complete chunk payload"
    (let [result (action-trace/canonical-result
                  :builtin/retrieval
                  {:chunks (vec (repeat 100 {:chunk_id "large" :content_markdown "..."}))
                   :search-attribution {:merged 87 :phrase 21 :content 50 :metadata 16
                                        :auto-filter-fallback true}})]
      (is (= {:candidate-count 87
              :phrase-count 21
              :content-count 50
              :metadata-count 16
              :auto-filter-applied nil
              :auto-filter-fallback true}
             result))
      (is (not (contains? result :chunks)))))
  (testing "rerank keeps at most twenty compact source identities"
    (let [chunks (mapv (fn [n]
                         {:chunk_id (str "c" n)
                          :doc_num (str "d" n)
                          :title (str "Source " n)
                          :content_markdown (apply str (repeat 1000 "x"))})
                       (range 30))
          result (action-trace/canonical-result :builtin/rerank {:chunks chunks})]
      (is (= 30 (:selected-count result)))
      (is (= 30 (:distinct-document-count result)))
      (is (= 20 (count (:chunks result))))
      (is (not (contains? (first (:chunks result)) :content_markdown))))))

(deftest overview-results-need-no-post-run-inference
  (let [synthesis (action-trace/canonical-result
                   :builtin/overview-synthesis
                   {:overview-response "Documented [1]"
                    :citations [{:index 1}]
                    :insufficient-context false})
        finalize (action-trace/canonical-result
                  :builtin/overview-finalize
                  {:response "Documented [1]"
                   :overview-declined? false
                   :citations [{:index 1}]})]
    (is (= "Documented [1]" (:response synthesis)))
    (is (= 1 (:citation-count synthesis)))
    (is (true? (:published? finalize)))
    (is (= 1 (:citation-count finalize)))))

(deftest agent-internal-steps-do-not-duplicate-agent-trace
  (is (nil? (action-trace/progress->action
             {:event :step/completed
              :step-id :agent
              :skill-id :builtin/agent
              :outputs {:trace [{:iteration 0}]}}))))

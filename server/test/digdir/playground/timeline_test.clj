(ns digdir.playground.timeline-test
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.playground.timeline :as timeline]))

(deftest timeline-from-agent-trace-preserves-tool-order
  (testing "Tool calls are flattened in deterministic order with sequence ids"
    (let [trace [{:iteration 1
                  :tool-calls [{:tool "search_documents"}
                               {:tool "rerank_results"}]}
                 {:iteration 2
                  :tool-calls [{:tool "generate_response"}]}]
          tl (timeline/timeline-from-agent-trace trace)]
      (is (= ["search_documents" "rerank_results" "generate_response"]
             (mapv :tool tl)))
      (is (= [0 1 2] (mapv :seq tl))))))

(deftest extract-search-phrases-from-trace-deduplicates-queries
  (testing "Search phrases are extracted from search_documents calls and deduplicated"
    (let [trace [{:tool-calls [{:tool "search_documents"
                                :args {:queries ["policy" "rule"]}}
                               {:tool "rerank_results"
                                :args {:top-k 5}}]}
                 {:tool-calls [{:tool "search_documents"
                                :args {:queries ["rule" "audit"]}}]}]]
      (is (= ["policy" "rule" "audit"]
             (timeline/extract-search-phrases-from-trace trace))))))

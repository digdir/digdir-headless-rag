(ns digdir.util.logging-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [digdir.util.logging :as log-utils]))

(deftest test-truncate-strings
  (testing "String truncation"
    (testing "Short strings are unchanged"
      (is (= "hello" (log-utils/truncate-for-logging "hello"))))

    (testing "Long strings are truncated with ellipsis"
      (let [long-str (apply str (repeat 150 "a"))
            result (log-utils/truncate-for-logging long-str)]
        (is (= 103 (count result))) ; 100 chars + "..."
        (is (str/ends-with? result "..."))
        (is (str/starts-with? result "aaaa"))))

    (testing "Custom max length"
      (let [result (log-utils/truncate-for-logging "hello world" {:max-string-len 5})]
        (is (= "hello..." result))))))

(deftest test-truncate-collections
  (testing "Vector truncation"
    (testing "Small vectors are unchanged"
      (is (= [1 2 3] (log-utils/truncate-for-logging [1 2 3]))))

    (testing "Large vectors are truncated"
      (let [result (log-utils/truncate-for-logging (vec (range 20)))]
        (is (= 11 (count result))) ; 10 items + truncation message
        (is (= (vec (range 10)) (vec (take 10 result))))
        (is (str/includes? (str (last result)) "10 more items"))))

    (testing "Custom max items"
      (let [result (log-utils/truncate-for-logging [1 2 3 4 5] {:max-coll-items 3})]
        (is (= 4 (count result)))
        (is (= [1 2 3] (vec (take 3 result)))))))

  (testing "List truncation"
    (let [result (log-utils/truncate-for-logging (list 1 2 3 4 5 6 7 8 9 10 11 12))]
      (is (= 11 (count result)))
      (is (= (list 1 2 3 4 5 6 7 8 9 10) (take 10 result)))))

  (testing "LazySeq handling"
    (let [lazy-seq (map inc (range 100))
          result (log-utils/truncate-for-logging lazy-seq)]
      (is (= 11 (count result)))
      (is (= (list 1 2 3 4 5 6 7 8 9 10) (take 10 result)))
      (is (= "<more items>" (last result)))))

  (testing "Set truncation"
    (let [result (log-utils/truncate-for-logging (set (range 15)))]
      (is (set? result))
      (is (<= (count result) 11)))))

(deftest test-truncate-maps
  (testing "Map value truncation"
    (testing "Values are recursively truncated"
      (let [result (log-utils/truncate-for-logging
                     {:key1 "short"
                      :key2 (apply str (repeat 150 "b"))})]
        (is (= "short" (:key1 result)))
        (is (str/ends-with? (:key2 result) "..."))
        (is (= 103 (count (:key2 result))))))

    (testing "Nested maps are handled"
      (let [result (log-utils/truncate-for-logging
                     {:outer {:inner (apply str (repeat 150 "c"))}})]
        (is (str/ends-with? (get-in result [:outer :inner]) "..."))))))

(deftest test-nested-structures
  (testing "Complex nested structure"
    (let [data {:job-id "job-123"
                :results [{:text (apply str (repeat 200 "x"))
                          :metadata {:count 100
                                    :tags (vec (range 20))}}
                         {:text "short"}]
                :status :completed}
          result (log-utils/truncate-for-logging data)]

      (testing "Top level structure is preserved"
        (is (= "job-123" (:job-id result)))
        (is (= :completed (:status result))))

      (testing "Nested strings are truncated"
        (is (str/ends-with? (-> result :results first :text) "...")))

      (testing "Nested collections are truncated"
        (is (= 11 (count (-> result :results first :metadata :tags))))))))

(deftest test-depth-limiting
  (testing "Deep nesting is limited"
    (let [deep-data {:l1 {:l2 {:l3 {:l4 {:l5 {:l6 "deep"}}}}}}
          result (log-utils/truncate-for-logging deep-data {:max-depth 3})]
      (is (map? result))
      (is (map? (:l1 result)))
      (is (map? (-> result :l1 :l2)))
      (is (string? (-> result :l1 :l2 :l3)))
      (is (str/includes? (-> result :l1 :l2 :l3) "max-depth")))))

(deftest test-primitive-types
  (testing "Primitives pass through unchanged"
    (is (= 42 (log-utils/truncate-for-logging 42)))
    (is (= :keyword (log-utils/truncate-for-logging :keyword)))
    (is (= 'symbol (log-utils/truncate-for-logging 'symbol)))
    (is (= true (log-utils/truncate-for-logging true)))
    (is (= false (log-utils/truncate-for-logging false)))
    (is (nil? (log-utils/truncate-for-logging nil)))
    (let [uuid (java.util.UUID/randomUUID)]
      (is (= uuid (log-utils/truncate-for-logging uuid))))))

(deftest test-real-world-example
  (testing "Example from provided log output"
    (let [data {:job-id "graph-7ada6475-9a3b-4f3d-ae28-13fdf8c6117c"
                :node-id #uuid "f4491dde-cccf-42dd-9157-ed24c5d12b69"
                :result {:type :typesense
                        :query {:field "title"
                                :q "samfunssikkerhet 2021 -tillegg"
                                :type "Tildelingsbrev"}
                        :answer [{:structured {:title "Tildelingsbrev Direktoratet for samfunnssikkerhet og beredskap 2025"
                                              :type "Tildelingsbrev"
                                              :doc_num "330084"}
                                 :text (apply str (repeat 500 "long text "))}]}}
          result (log-utils/truncate-for-logging data {:max-string-len 50})]

      (testing "Structure is preserved"
        (is (= (:job-id data) (:job-id result)))
        (is (= (:node-id data) (:node-id result)))
        (is (= :typesense (-> result :result :type))))

      (testing "Long text is truncated"
        (let [text (-> result :result :answer first :text)]
          (is (= 53 (count text))) ; 50 + "..."
          (is (str/ends-with? text "..."))))

      (testing "Short strings are unchanged"
        (is (= "330084" (-> result :result :answer first :structured :doc_num)))))))

(deftest test-helper-functions
  (testing "truncate-string helper"
    (is (= "hello..." (log-utils/truncate-string "hello world" 5)))
    (is (= "hi" (log-utils/truncate-string "hi" 5))))

  (testing "truncate-collection helper"
    (is (= '(0 1 2 "<7 more items>")
           (log-utils/truncate-collection (range 10) 3))))

  (testing "safe-pr-str helper"
    (let [data {:key (apply str (repeat 150 "x"))}
          result (log-utils/safe-pr-str data {:max-string-len 10})]
      (is (string? result))
      (is (str/includes? result "...")))))

;; Run all tests
(defn run-tests []
  (clojure.test/run-tests 'digdir.util.logging-test))

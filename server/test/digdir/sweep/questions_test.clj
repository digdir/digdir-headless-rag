(ns digdir.sweep.questions-test
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.sweep.questions :as q]))

(deftest fixture-loads-and-validates
  (testing "The shipped fixture loads cleanly and passes schema validation.
            Any future hand-edit that introduces a typo (bad regex,
            unknown tag, etc.) will fail this test."
    (is (some? (q/validate!)))))

(deftest live-rows-have-required-shape
  (testing "Every live (non-tombstone) row has the keys the sweep runner
            depends on."
    (let [rows (q/load-questions!)]
      (is (pos? (count rows)) "fixture must not be empty")
      (doseq [r rows]
        (is (string? (:id r)) (str "id must be string: " (pr-str r)))
        (is (string? (:query r)) (str "query must be string for " (:id r)))
        (is (vector? (:golden-chunk-ids r))
            (str "golden-chunk-ids must be vector for " (:id r)))
        (is (string? (:expected-answer-pattern r))
            (str "expected-answer-pattern must be string for " (:id r)))
        (is (set? (:tags r))
            (str "tags must be set for " (:id r)))
        (is (contains? q/known-datasets (:dataset r))
            (str "dataset known for " (:id r)))
        (is (contains? q/known-sources (:source r))
            (str "source known for " (:id r)))))))

(deftest ids-are-unique
  (testing "Duplicate ids would cause the sweep runner to overwrite
            results from one row with another."
    (let [rows (q/load-questions!)
          ids (map :id rows)]
      (is (= (count ids) (count (set ids)))
          (str "duplicate ids: "
               (->> ids frequencies
                    (filter #(> (val %) 1))
                    (map key)))))))

(deftest tags-are-from-known-vocab
  (testing "Every tag on every row is in the known vocabulary. New tags
            must be added to digdir.sweep.questions/known-tags first."
    (let [rows (q/load-questions!)
          bad (for [r rows
                    t (:tags r)
                    :when (not (contains? q/all-known-tags t))]
                [(:id r) t])]
      (is (empty? bad)
          (str "unknown tags found: " (pr-str bad))))))

(deftest validate-rejects-unknown-tag
  (testing "Hand-crafted fixture with an unknown tag fails validation."
    (let [bad-fixture
          {:version 1
           :questions
           [{:id "bad"
             :dataset :public-docs
             :query "test"
             :golden-chunk-ids []
             :expected-answer-pattern "x"
             :tags #{:not-a-real-tag :altinn-3-general}
             :source :new
             :grounding-mode :answer-only}]}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"failed validation"
                            (q/validate! bad-fixture))))))

(deftest validate-rejects-bad-regex
  (testing "Malformed :expected-answer-pattern fails validation."
    (let [bad-fixture
          {:version 1
           :questions
           [{:id "bad"
             :dataset :public-docs
             :query "test"
             :golden-chunk-ids []
             :expected-answer-pattern "[unclosed"
             :tags #{:altinn-3-general}
             :source :new
             :grounding-mode :answer-only}]}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"failed validation"
                            (q/validate! bad-fixture))))))

(deftest validate-rejects-duplicate-ids
  (testing "Duplicate :id values fail validation even if both rows
            individually pass schema checks."
    (let [bad-fixture
          {:version 1
           :questions
           [{:id "dup" :dataset :public-docs :query "a"
             :golden-chunk-ids [] :expected-answer-pattern "x"
             :tags #{:altinn-3-general} :source :new
             :grounding-mode :answer-only}
            {:id "dup" :dataset :public-docs :query "b"
             :golden-chunk-ids [] :expected-answer-pattern "y"
             :tags #{:altinn-3-general} :source :new
             :grounding-mode :answer-only}]}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"failed validation"
                            (q/validate! bad-fixture))))))

(deftest validate-rejects-chunks-mode-without-chunks
  (testing ":chunks+answer (default) grounding requires non-empty
            :golden-chunk-ids."
    (let [bad-fixture
          {:version 1
           :questions
           [{:id "no-chunks"
             :dataset :public-docs
             :query "test"
             :golden-chunk-ids []
             :expected-answer-pattern "x"
             :tags #{:altinn-3-general}
             :source :new}]}] ;; no :grounding-mode → defaults to :chunks+answer
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"failed validation"
                            (q/validate! bad-fixture))))))

(deftest summary-shape
  (testing "summary returns the structure the dev REPL expects so quick
            inspections don't break when the schema evolves."
    (let [s (q/summary)]
      (is (pos? (:total s)))
      (is (map? (:by-dataset s)))
      (is (map? (:by-source s)))
      (is (map? (:by-grounding-mode s)))
      (is (map? (:by-domain s))))))

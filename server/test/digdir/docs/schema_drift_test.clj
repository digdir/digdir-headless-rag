(ns digdir.docs.schema-drift-test
  "The detector for #377, proved RED in both directions before it is trusted.

   A drift checker that has only ever been seen green is indistinguishable from
   one that cannot fire — which is the same defect it exists to catch, one layer
   up. So the load-bearing tests here are the failing ones, and the third state:
   `:unreachable` must not read as a pass."
  (:require [clojure.test :refer [deftest testing is]]
            [clj-http.client :as http]
            [digdir.docs.schema-drift :as drift]))

;; ============================================================================
;; The pure comparison, red in BOTH directions
;; ============================================================================

(deftest identical-field-sets-are-clean
  (let [d (drift/compare-fields #{"a" "b"} #{"a" "b"})]
    (is (= [] (:missing-from-collection d)))
    (is (= [] (:missing-from-file d)))
    (is (false? (drift/drift? d)))))

(deftest RED-a-field-in-the-file-but-not-the-collection
  (testing "the #377 direction: written to documents but not indexed"
    (let [d (drift/compare-fields #{"doc_num" "total_chunks" "retrieval_attempts"}
                                  #{"doc_num"})]
      (is (= ["retrieval_attempts" "total_chunks"] (:missing-from-collection d)))
      (is (= [] (:missing-from-file d)))
      (is (true? (drift/drift? d))))))

(deftest RED-a-field-in-the-collection-but-not-the-file
  (testing "the other direction: re-creating from the file would drop it"
    (let [d (drift/compare-fields #{"doc_num"}
                                  #{"doc_num" "legacy_field"})]
      (is (= [] (:missing-from-collection d)))
      (is (= ["legacy_field"] (:missing-from-file d)))
      (is (true? (drift/drift? d))))))

(deftest RED-drift-in-both-directions-at-once
  (let [d (drift/compare-fields #{"a" "only_in_file"} #{"a" "only_in_collection"})]
    (is (= ["only_in_file"] (:missing-from-collection d)))
    (is (= ["only_in_collection"] (:missing-from-file d)))
    (is (true? (drift/drift? d)))))

;; ============================================================================
;; The third state — the one that must not collapse into a pass
;; ============================================================================

(deftest unreachable-is-not-ok
  (testing "a collection that cannot be read is UNKNOWN, never clean"
    (with-redefs [http/get (fn [& _] (throw (ex-info "connection refused" {})))]
      (let [r (drift/check-collection {:uri "http://nowhere:8108" :key "k"} "some_docs")]
        (is (= :unreachable (:status r)))
        (is (false? (drift/ok? r)) "must not read as a pass")
        (is (some? (:reason r)))))))

(deftest a-report-always-says-what-it-looked-at
  (testing "including when the look failed — collection names are shared across instances"
    (with-redefs [http/get (fn [& _] (throw (ex-info "connection refused" {})))]
      (let [r (drift/check-collection {:uri "http://box-a:8108" :key "k"} "shared_name")
            lines (drift/report-lines r)]
        (is (= {:host "http://box-a:8108" :collection "shared_name"} (:checked r)))
        (is (some #(re-find #"shared_name on http://box-a:8108" %) lines))
        (is (some #(re-find #"UNKNOWN, not clean" %) lines)
            "the wording has to stop a reader treating it as a pass")))))

(deftest ok-is-only-ok-when-the-comparison-ran
  (is (true? (drift/ok? {:status :ok})))
  (is (false? (drift/ok? {:status :drift})))
  (is (false? (drift/ok? {:status :unreachable})))
  (is (false? (drift/ok? {}))
      "an empty report is not a pass either"))

;; ============================================================================
;; The report names the consequence, not just the field
;; ============================================================================

(deftest the-drift-report-explains-why-it-matters
  (let [lines (drift/report-lines
                {:status :drift
                 :checked {:host "http://h:8108" :collection "c"}
                 :declared-count 13 :live-count 7
                 :missing-from-collection ["retrieval_attempts" "total_chunks"]
                 :missing-from-file []})]
    (is (some #(re-find #"NOT indexed" %) lines)
        "a reader must learn the field is invisible to filters, not merely absent")
    (is (some #(re-find #"filter_by" %) lines))
    (is (some #(re-find #"never alters" %) lines)
        "and why it happened, so the fix is not looked for in the wrong place")))

;; ============================================================================
;; Against the real schema file
;; ============================================================================

(deftest the-real-schema-file-parses-and-declares-the-retrieval-record
  (let [declared (drift/declared-fields)]
    (is (contains? declared "doc_num"))
    (is (every? declared ["last_retrieval_success_at" "first_retrieval_failure_at"
                          "last_retrieval_failure_at" "consecutive_retrieval_failures"
                          "retrieval_attempts"])
        "the #325 fields are in the file — which is exactly why the collection not
         having them is the interesting question")))

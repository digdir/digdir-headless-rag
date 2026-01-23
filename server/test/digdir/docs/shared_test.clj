(ns digdir.docs.shared-test
  "Tests for shared utility functions used across digdir.docs.* namespaces.
   These functions will be extracted to digdir.docs.pipeline.core during refactoring."
  (:require [clojure.test :refer [deftest testing is are]]
            [digdir.docs.loader :as loader]
            [digdir.docs.test-fixtures :as fixtures]))

;; ============================================================================
;; sha256-short-hash tests
;; ============================================================================

(deftest sha256-short-hash-deterministic
  (testing "Same input produces same hash"
    (let [input "test string"
          hash1 (loader/sha256-short-hash input)
          hash2 (loader/sha256-short-hash input)]
      (is (= hash1 hash2)))))

(deftest sha256-short-hash-different-inputs
  (testing "Different inputs produce different hashes"
    (let [hash1 (loader/sha256-short-hash "input 1")
          hash2 (loader/sha256-short-hash "input 2")]
      (is (not= hash1 hash2)))))

(deftest sha256-short-hash-length
  (testing "Hash is 12 characters long"
    (is (= 12 (count (loader/sha256-short-hash "any input"))))
    (is (= 12 (count (loader/sha256-short-hash {:complex "data" :with [1 2 3]}))))
    (is (= 12 (count (loader/sha256-short-hash [1 2 3 4 5]))))))

(deftest sha256-short-hash-handles-various-types
  (testing "Handles various input types"
    (is (string? (loader/sha256-short-hash "string")))
    (is (string? (loader/sha256-short-hash 12345)))
    (is (string? (loader/sha256-short-hash {:map "value"})))
    (is (string? (loader/sha256-short-hash [1 2 3])))
    (is (string? (loader/sha256-short-hash nil)))
    (is (string? (loader/sha256-short-hash "")))))

(deftest sha256-short-hash-empty-and-nil
  (testing "Empty string and nil produce different hashes"
    (let [empty-hash (loader/sha256-short-hash "")
          nil-hash (loader/sha256-short-hash nil)]
      (is (not= empty-hash nil-hash)))))

;; ============================================================================
;; => (function composition) tests
;; ============================================================================

(deftest compose-left-to-right
  (testing "Composes functions left to right"
    (let [add1 inc
          double #(* 2 %)
          composed (loader/=> add1 double)]
      ;; (=> add1 double) should be: first add1, then double
      ;; So (=> add1 double) on 5 = double(add1(5)) = double(6) = 12
      (is (= 12 (composed 5))))))

(deftest compose-single-function
  (testing "Single function returns itself"
    (let [add1 inc
          composed (loader/=> add1)]
      (is (= 6 (composed 5))))))

(deftest compose-multiple-functions
  (testing "Composes multiple functions"
    (let [add1 inc
          double #(* 2 %)
          square #(* % %)
          composed (loader/=> add1 double square)]
      ;; 5 -> add1 -> 6 -> double -> 12 -> square -> 144
      (is (= 144 (composed 5))))))

(deftest compose-with-string-functions
  (testing "Works with string functions"
    (let [upper clojure.string/upper-case
          trim clojure.string/trim
          composed (loader/=> trim upper)]
      (is (= "HELLO" (composed "  hello  "))))))

;; ============================================================================
;; exponential-backoff tests
;; ============================================================================

(deftest exponential-backoff-returns-n-delays
  (testing "Returns exactly n delays"
    (is (= 5 (count (loader/exponential-backoff 5))))
    (is (= 1 (count (loader/exponential-backoff 1))))
    (is (= 10 (count (loader/exponential-backoff 10))))))

(deftest exponential-backoff-zero
  (testing "Returns empty for 0"
    (is (empty? (loader/exponential-backoff 0)))))

(deftest exponential-backoff-increasing
  (testing "Delays generally increase (exponentially)"
    (let [delays (loader/exponential-backoff 5)
          ;; Remove jitter effect by checking base pattern
          ;; First delay starts around 1000ms
          first-delay (first delays)]
      ;; First delay should be around 1000ms (with jitter: 1000-1500)
      (is (>= first-delay 1000))
      (is (<= first-delay 1500))
      ;; Last delay should be much larger
      (is (> (last delays) first-delay)))))

(deftest exponential-backoff-positive
  (testing "All delays are positive"
    (let [delays (loader/exponential-backoff 5)]
      (is (every? pos? delays)))))

;; ============================================================================
;; worth-retrying? tests
;; ============================================================================

(deftest worth-retrying-default-true
  (testing "Returns true for regular exceptions"
    (is (true? (loader/worth-retrying? (Exception. "generic error"))))))

(deftest worth-retrying-explicit-false
  (testing "Returns false when :worth-retrying is false in ex-data"
    (let [error (ex-info "not retryable" {:worth-retrying false})]
      (is (false? (loader/worth-retrying? error))))))

(deftest worth-retrying-explicit-true
  (testing "Returns true when :worth-retrying is true in ex-data"
    (let [error (ex-info "retryable" {:worth-retrying true})]
      (is (true? (loader/worth-retrying? error))))))

(deftest worth-retrying-checks-cause-chain
  (testing "Checks exception cause chain for :worth-retrying"
    (let [root-cause (ex-info "root" {:worth-retrying false})
          wrapper (ex-info "wrapper" {} root-cause)]
      (is (false? (loader/worth-retrying? wrapper))))))

(deftest worth-retrying-nested-exception
  (testing "Handles deeply nested exceptions"
    (let [root (ex-info "root" {:worth-retrying false})
          level1 (ex-info "level1" {} root)
          level2 (ex-info "level2" {} level1)]
      (is (false? (loader/worth-retrying? level2))))))

;; ============================================================================
;; split-url-extension tests
;; ============================================================================

(deftest split-url-extension-with-extension
  (testing "Splits URL with extension"
    (is (= ["https://example.com/file" ".pdf"]
           (loader/split-url-extension "https://example.com/file.pdf")))
    (is (= ["https://example.com/path/to/doc" ".docx"]
           (loader/split-url-extension "https://example.com/path/to/doc.docx")))))

(deftest split-url-extension-without-extension
  (testing "Returns nil extension when no extension"
    (is (= ["https://example.com/path/file" nil]
           (loader/split-url-extension "https://example.com/path/file")))))

(deftest split-url-extension-complex-urls
  (testing "Handles complex URLs"
    ;; URL with port
    (is (= ["https://example.com:8080/file" ".pdf"]
           (loader/split-url-extension "https://example.com:8080/file.pdf")))
    ;; URL with path segments
    (is (= ["https://cdn.example.com/a/b/c/d" ".png"]
           (loader/split-url-extension "https://cdn.example.com/a/b/c/d.png")))))

;; ============================================================================
;; extract-year-from-date tests
;; ============================================================================

(deftest extract-year-full-date
  (testing "Extracts year from full ISO date"
    (is (= 2024 (loader/extract-year-from-date "2024-01-15")))
    (is (= 2023 (loader/extract-year-from-date "2023-12-31")))))

(deftest extract-year-year-only
  (testing "Extracts year from year-only string"
    (is (= 2024 (loader/extract-year-from-date "2024")))
    (is (= 1999 (loader/extract-year-from-date "1999")))))

(deftest extract-year-with-time
  (testing "Extracts year from datetime string"
    (is (= 2024 (loader/extract-year-from-date "2024-01-15T10:30:00Z")))))

(deftest extract-year-nil-and-empty
  (testing "Returns nil for nil or empty input"
    (is (nil? (loader/extract-year-from-date nil)))
    (is (nil? (loader/extract-year-from-date "")))
    (is (nil? (loader/extract-year-from-date "abc")))))

(deftest extract-year-short-string
  (testing "Returns nil for strings shorter than 4 characters"
    (is (nil? (loader/extract-year-from-date "202")))
    (is (nil? (loader/extract-year-from-date "20")))))

;; ============================================================================
;; fill-in-doc-fields tests
;; ============================================================================

(deftest fill-in-doc-fields-basic
  (testing "Converts id to string and sets doc_num"
    (let [doc {:id 12345 :title "Test"}
          result (loader/fill-in-doc-fields doc)]
      (is (= "12345" (:id result)))
      (is (= "12345" (:doc_num result))))))

(deftest fill-in-doc-fields-concerned-years-range
  (testing "Creates range from concerned_year_from to concerned_year_to"
    (let [doc {:id 1 :concerned_year_from 2020 :concerned_year_to 2023}
          result (loader/fill-in-doc-fields doc)]
      (is (= [2020 2021 2022 2023] (:concerned_years result))))))

(deftest fill-in-doc-fields-concerned-year-from-only
  (testing "Uses concerned_year_from when no to"
    (let [doc {:id 1 :concerned_year_from 2022}
          result (loader/fill-in-doc-fields doc)]
      (is (= [2022] (:concerned_years result))))))

(deftest fill-in-doc-fields-concerned-year-to-only
  (testing "Uses concerned_year_to when no from"
    (let [doc {:id 1 :concerned_year_to 2023}
          result (loader/fill-in-doc-fields doc)]
      (is (= [2023] (:concerned_years result))))))

(deftest fill-in-doc-fields-concerned-year-single
  (testing "Uses single concerned_year"
    (let [doc {:id 1 :concerned_year 2021}
          result (loader/fill-in-doc-fields doc)]
      (is (= [2021] (:concerned_years result))))))

(deftest fill-in-doc-fields-fallback-to-publish-date
  (testing "Falls back to publish_date year when no concerned year info"
    (let [doc {:id 1 :publish_date "2024-06-15"}
          result (loader/fill-in-doc-fields doc)]
      (is (= [2024] (:concerned_years result))))))

(deftest fill-in-doc-fields-empty-years
  (testing "Returns empty vector when no year info available"
    (let [doc {:id 1 :title "No year info"}
          result (loader/fill-in-doc-fields doc)]
      (is (= [] (:concerned_years result))))))

;; ============================================================================
;; Integration tests for utility composition
;; ============================================================================

(deftest utilities-work-together
  (testing "Utilities can be composed for document processing"
    (let [doc {:id 12345
               :title "Test Document"
               :publish_date "2024-01-15"
               :content "Some content here"}
          processed (loader/fill-in-doc-fields doc)
          content-hash (loader/sha256-short-hash (:content doc))]
      (is (= "12345" (:id processed)))
      (is (= "12345" (:doc_num processed)))
      (is (= [2024] (:concerned_years processed)))
      (is (= 12 (count content-hash))))))

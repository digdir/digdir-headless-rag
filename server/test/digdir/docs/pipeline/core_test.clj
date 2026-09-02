(ns digdir.docs.pipeline.core-test
  "Tests for digdir.docs.pipeline.core - shared utility functions."
  (:require [clojure.test :refer [deftest testing is ]]
            [clojure.string :as str]
            [digdir.docs.pipeline.core :as core]))

;; ============================================================================
;; sha256-short-hash Tests
;; ============================================================================

(deftest sha256-short-hash-deterministic
  (testing "Same input produces same hash"
    (let [input "test string"
          hash1 (core/sha256-short-hash input)
          hash2 (core/sha256-short-hash input)]
      (is (= hash1 hash2)))))

(deftest sha256-short-hash-different-inputs
  (testing "Different inputs produce different hashes"
    (let [hash1 (core/sha256-short-hash "input 1")
          hash2 (core/sha256-short-hash "input 2")]
      (is (not= hash1 hash2)))))

(deftest sha256-short-hash-length
  (testing "Hash is 12 characters long"
    (is (= 12 (count (core/sha256-short-hash "any input"))))
    (is (= 12 (count (core/sha256-short-hash {:complex "data"}))))
    (is (= 12 (count (core/sha256-short-hash [1 2 3]))))))

(deftest sha256-short-hash-handles-types
  (testing "Handles various input types"
    (is (string? (core/sha256-short-hash "string")))
    (is (string? (core/sha256-short-hash 12345)))
    (is (string? (core/sha256-short-hash {:map "value"})))
    (is (string? (core/sha256-short-hash [1 2 3])))
    (is (string? (core/sha256-short-hash nil)))))

;; ============================================================================
;; => (function composition) Tests
;; ============================================================================

(deftest compose-left-to-right
  (testing "Composes functions left to right"
    (let [add1 inc
          double #(* 2 %)
          composed (core/=> add1 double)]
      ;; 5 -> add1 -> 6 -> double -> 12
      (is (= 12 (composed 5))))))

(deftest compose-single-function
  (testing "Single function returns itself"
    (let [composed (core/=> inc)]
      (is (= 6 (composed 5))))))

(deftest compose-multiple-functions
  (testing "Composes multiple functions"
    (let [composed (core/=> inc #(* 2 %) #(* % %))]
      ;; 5 -> inc -> 6 -> double -> 12 -> square -> 144
      (is (= 144 (composed 5))))))

(deftest compose-with-string-functions
  (testing "Works with string functions"
    (let [composed (core/=> str/trim str/upper-case)]
      (is (= "HELLO" (composed "  hello  "))))))

;; ============================================================================
;; exponential-backoff Tests
;; ============================================================================

(deftest exponential-backoff-returns-n-delays
  (testing "Returns exactly n delays"
    (is (= 5 (count (core/exponential-backoff 5))))
    (is (= 1 (count (core/exponential-backoff 1))))))

(deftest exponential-backoff-zero
  (testing "Returns empty for 0"
    (is (empty? (core/exponential-backoff 0)))))

(deftest exponential-backoff-increasing
  (testing "Delays generally increase"
    (let [delays (core/exponential-backoff 4)
          first-delay (first delays)]
      ;; First delay around 1000-1500ms
      (is (>= first-delay 1000))
      (is (<= first-delay 1500))
      ;; Later delays should be larger
      (is (> (last delays) first-delay)))))

(deftest exponential-backoff-positive
  (testing "All delays are positive"
    (is (every? pos? (core/exponential-backoff 5)))))

;; ============================================================================
;; worth-retrying? Tests
;; ============================================================================

(deftest worth-retrying-connection-errors
  (testing "Returns true for connection errors"
    (is (true? (core/worth-retrying? (Exception. "Connection refused"))))
    (is (true? (core/worth-retrying? (Exception. "Connection reset"))))
    (is (true? (core/worth-retrying? (Exception. "timeout"))))))

(deftest worth-retrying-http-errors
  (testing "Returns true for transient HTTP errors"
    (is (true? (core/worth-retrying? (Exception. "HTTP/1.1 0"))))
    (is (true? (core/worth-retrying? (Exception. "503"))))
    (is (true? (core/worth-retrying? (Exception. "429"))))))

(deftest worth-retrying-other-errors
  (testing "Returns false for non-retryable errors"
    (is (false? (core/worth-retrying? (Exception. "File not found"))))
    (is (false? (core/worth-retrying? (Exception. "Invalid input"))))))

;; ============================================================================
;; split-url-extension Tests
;; ============================================================================

(deftest split-url-extension-with-extension
  (testing "Splits URL with extension"
    (is (= ["https://example.com/file" "pdf"]
           (core/split-url-extension "https://example.com/file.pdf")))
    (is (= ["https://example.com/path/doc" "docx"]
           (core/split-url-extension "https://example.com/path/doc.docx")))))

(deftest split-url-extension-without-extension
  (testing "Returns nil extension when no extension"
    (is (= ["https://example.com/path/file" nil]
           (core/split-url-extension "https://example.com/path/file")))))

;; ============================================================================
;; extract-year-from-date Tests
;; ============================================================================

(deftest extract-year-full-date
  (testing "Extracts year from full ISO date"
    (is (= "2024" (core/extract-year-from-date "2024-01-15")))
    (is (= "2023" (core/extract-year-from-date "2023-12-31")))))

(deftest extract-year-year-only
  (testing "Extracts year from year-only string"
    (is (= "2024" (core/extract-year-from-date "2024")))))

(deftest extract-year-nil-and-empty
  (testing "Returns nil for nil or empty input"
    (is (nil? (core/extract-year-from-date nil)))
    (is (nil? (core/extract-year-from-date "")))))

;; ============================================================================
;; fill-in-doc-fields Tests
;; ============================================================================

(deftest fill-in-doc-fields-basic
  (testing "Fills in required fields"
    (let [doc {:id "doc-1" :url "http://example.com/test"}
          result (core/fill-in-doc-fields doc :website)]
      (is (string? (:id result)))
      (is (= "doc-1" (:id result)))
      (is (= "doc-1" (:doc_num result)))
      (is (= "website" (:type result))))))

(deftest fill-in-doc-fields-generates-id
  (testing "Generates ID from URL when not provided"
    (let [doc {:url "http://example.com/test"}
          result (core/fill-in-doc-fields doc :website)]
      (is (string? (:id result)))
      (is (= 12 (count (:id result)))))))

(deftest fill-in-doc-fields-preserves-existing
  (testing "Preserves existing fields"
    (let [doc {:id "custom-id" :title "Custom Title" :url "http://test"}
          result (core/fill-in-doc-fields doc :website)]
      (is (= "custom-id" (:id result)))
      (is (= "Custom Title" (:title result))))))

(deftest fill-in-doc-fields-default-title
  (testing "Uses default title when none provided"
    (let [doc {:id "doc-1" :url "http://example.com/test"}
          result (core/fill-in-doc-fields doc :folder)]
      (is (= "Untitled" (:title result))))))

(ns digdir.docs.shared-test
  "Tests for shared utility functions used across digdir.docs.* namespaces.
   These functions will be extracted to digdir.docs.pipeline.core during refactoring."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is ]]
            [digdir.docs.loader :as loader]))

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

(deftest fill-in-doc-fields-does-not-fabricate-from-publish-date
  ;; This test previously asserted [2024] here - it pinned the publish-date
  ;; fallback, which means it certified the defect #238 reported. Inverted
  ;; deliberately rather than deleted, so the old expectation stays visible.
  (testing "A publish date alone is NOT concerned-year information"
    (let [doc {:id 1 :publish_date "2024-06-15"}
          result (loader/fill-in-doc-fields doc)]
      (is (= [] (:concerned_years result))
          (str "publish_date must not be substituted for a concerned year: it "
               "was wrong for 216 of 2638 single-year-title documents, and it "
               "produced a value nothing downstream could tell from a real one")))))

(deftest fill-in-doc-fields-still-uses-every-real-source
  ;; The counterpart to the test above: removing the fallback must not have
  ;; weakened the branches that DO carry concerned-year information. Asserted
  ;; here together so a future edit cannot quietly drop one of them.
  (testing "range, from-only, to-only and scalar all still populate"
    (is (= [2020 2021 2022 2023]
           (:concerned_years (loader/fill-in-doc-fields
                              {:id 1 :concerned_year_from 2020 :concerned_year_to 2023}))))
    (is (= [2022] (:concerned_years (loader/fill-in-doc-fields
                                     {:id 1 :concerned_year_from 2022}))))
    (is (= [2023] (:concerned_years (loader/fill-in-doc-fields
                                     {:id 1 :concerned_year_to 2023}))))
    (is (= [2021] (:concerned_years (loader/fill-in-doc-fields
                                     {:id 1 :concerned_year 2021})))))
  (testing "and a publish date does not override a real one"
    (is (= [2019] (:concerned_years (loader/fill-in-doc-fields
                                     {:id 1 :concerned_year 2019
                                      :publish_date "2024-06-15"}))))))

(deftest fill-in-doc-fields-empty-years
  (testing "Returns empty vector when no year info available"
    (let [doc {:id 1 :title "No year info"}
          result (loader/fill-in-doc-fields doc)]
      (is (= [] (:concerned_years result))))))

(deftest file-digests-flattens-the-nested-sha256s
  ;; #228. The digests exist inside :files, but that is an array of objects and
  ;; the collection is created with enable_nested_fields false - which Typesense
  ;; will not let you change on an existing collection. A flat sibling is what
  ;; makes a sha256 lookup reachable by a schema PATCH instead of a rebuild.
  (testing "one file"
    (is (= ["aaa"] (loader/file-digests {:files [{:sha256 "aaa"}]}))))

  (testing "several files are ALL represented"
    ;; The 6 shared-attachment groups in #228 are documents carrying more than
    ;; one file, where a digest shared with another document is legitimate. A
    ;; per-document LIST lets that be stated; a single scalar could not.
    (is (= ["aaa" "bbb"]
           (loader/file-digests {:files [{:sha256 "aaa"} {:sha256 "bbb"}]}))))

  (testing "duplicates within one document collapse"
    (is (= ["aaa"] (loader/file-digests {:files [{:sha256 "aaa"} {:sha256 "aaa"}]}))))

  (testing "no files, and files without a digest, yield an empty vector"
    (is (= [] (loader/file-digests {})))
    (is (= [] (loader/file-digests {:files []})))
    (is (= [] (loader/file-digests {:files [{:filename "x.pdf"}]})))))

(deftest indexable-files-drops-the-ingest-scratch-path
  ;; #253. :path is a java.io.File/createTempFile on whichever host ran the
  ;; ingest, assoc'd by mk-require-url-file and deleted when that run ends - so
  ;; the stored copy has ALWAYS been wrong by the time anyone reads it, sitting
  ;; beside size, pages, sha256 and mimetype with nothing marking it as scratch.
  (testing "the scratch path does not reach the collection"
    (is (= [{:sha256 "aaa" :pages 3 :mimetype "application/pdf"}]
           (loader/indexable-files
            {:files [{:sha256 "aaa" :pages 3 :mimetype "application/pdf"
                      :path "/tmp/pdf-18048139745140566600.pdf"}]}))))

  (testing "every other file attribute is retained"
    ;; The point is to drop ONE meaningless key, not to slim the entry down.
    (let [kept (first (loader/indexable-files
                       {:files [{:sha256 "aaa" :pages 3 :size 12 :id 7
                                 :filename "x.pdf" :description "Hoveddokument"
                                 :mimetype "application/pdf" :path "/tmp/x"}]}))]
      (is (= #{:sha256 :pages :size :id :filename :description :mimetype}
             (set (keys kept)))
          "key set, so a field silently dropped alongside :path would show up")))

  (testing "documents with no files are unaffected"
    (is (= [] (loader/indexable-files {})))
    (is (= [] (loader/indexable-files {:files []})))))

(deftest prepare-doc-strips-the-scratch-path-from-what-it-stores
  (testing "the projection carries files without :path"
    (let [prepared (loader/prepare-doc {:id "1" :doc_num "1" :title "t"
                                        :files [{:sha256 "aaa" :path "/tmp/gone.pdf"}]
                                        :chunks []})]
      (is (= [{:sha256 "aaa"}] (:files prepared)))
      (is (= ["aaa"] (:file_sha256 prepared))
          "and the digest still derives correctly from the stripped entries"))))

(deftest prepare-doc-emits-the-flat-digest-field
  (testing "file_sha256 reaches the collection alongside the nested files"
    (let [prepared (loader/prepare-doc {:id "1" :doc_num "1" :title "t"
                                        :files [{:sha256 "aaa" :pages 3}
                                                {:sha256 "bbb" :pages 1}]
                                        :chunks []})]
      (is (= ["aaa" "bbb"] (:file_sha256 prepared))
          "the flat field is what a PATCH can later index")
      (is (= [{:sha256 "aaa" :pages 3} {:sha256 "bbb" :pages 1}] (:files prepared))
          "and the nested original is retained, not replaced"))))

(deftest prepare-doc-keeps-the-concerned-year-range-fields
  ;; #238. These two are the FIRST branches of fill-in-doc-fields' cond and the
  ;; only fields that can express a multi-year document - 29% of the ground
  ;; truth in #228 was ranges or several years, not single years. They were
  ;; dropped by this select-keys, which left the collection unable to tell a
  ;; range-supplied concerned_years from a fabricated one.
  (testing "the range fields survive the projection into Typesense"
    (let [prepared (loader/prepare-doc {:id "1"
                                        :doc_num "1"
                                        :title "Rapport 2020-2024"
                                        :concerned_year_from 2020
                                        :concerned_year_to 2024
                                        :concerned_years [2020 2021 2022 2023 2024]
                                        :publish_date "2025-03-01"
                                        :chunks []})]
      (is (= 2020 (:concerned_year_from prepared))
          "concerned_year_from must reach the collection")
      (is (= 2024 (:concerned_year_to prepared))
          "concerned_year_to must reach the collection")
      (is (= [2020 2021 2022 2023 2024] (:concerned_years prepared))
          "and the derived list is unchanged by restoring them"))))

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
      ;; Was [2024], derived from :publish_date. That document states no
      ;; concerned year, so the honest answer is none (#238).
      (is (= [] (:concerned_years processed)))
      (is (= 12 (count content-hash))))))

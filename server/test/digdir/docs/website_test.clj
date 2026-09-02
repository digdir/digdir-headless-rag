(ns digdir.docs.website-test
  "Tests for digdir.docs.website namespace."
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.string :as str]
            [digdir.docs.website :as website]
            [digdir.docs.test-fixtures :as fixtures]))

;; ============================================================================
;; URL Helper Functions Tests
;; ============================================================================

(deftest make-relative-url-strips-base
  (testing "Strips base URL from absolute URL"
    (is (= "/en/about/index.md"
           (website/make-relative-url "http://localhost:1313" "http://localhost:1313/en/about/index.md")))))

(deftest make-relative-url-with-nil-base
  (testing "Returns URL unchanged when base is nil"
    (is (= "http://localhost:1313/en/about/index.md"
           (website/make-relative-url nil "http://localhost:1313/en/about/index.md")))))

(deftest make-relative-url-non-matching
  (testing "Returns URL unchanged when it doesn't start with base"
    (is (= "http://example.com/test.md"
           (website/make-relative-url "http://localhost:1313" "http://example.com/test.md")))))

(deftest make-absolute-url-prepends-base
  (testing "Prepends base URL to relative URL"
    (is (= "http://localhost:1313/en/about/index.md"
           (website/make-absolute-url "http://localhost:1313" "/en/about/index.md")))))

(deftest make-absolute-url-already-absolute
  (testing "Leaves absolute URLs unchanged"
    (is (= "http://example.com/test.md"
           (website/make-absolute-url "http://localhost:1313" "http://example.com/test.md")))))

(deftest make-absolute-url-nil-base
  (testing "Returns URL unchanged when base is nil"
    (is (= "/en/about/index.md"
           (website/make-absolute-url nil "/en/about/index.md")))))

;; ============================================================================
;; Title Extraction Tests
;; ============================================================================

(deftest extract-title-from-url-with-index
  (testing "Extracts parent directory name from index.md URLs"
    (is (= "About"
           (website/extract-title-from-url "http://localhost:1313/en/about/index.md")))))

(deftest extract-title-from-url-relative
  (testing "Works with relative URLs"
    (is (= "About"
           (website/extract-title-from-url "/en/about/index.md")))))

(deftest extract-title-from-url-regular-file
  (testing "Extracts filename without extension"
    (is (= "Architecture"
           (website/extract-title-from-url "/en/authorization/architecture.md")))))

(deftest extract-title-from-url-replaces-dashes
  (testing "Replaces dashes with spaces"
    (is (= "Getting started"
           (website/extract-title-from-url "/docs/getting-started.md")))))

(deftest extract-title-from-url-replaces-underscores
  (testing "Replaces underscores with spaces"
    (is (= "Api reference"
           (website/extract-title-from-url "/docs/api_reference.md")))))

(deftest extract-title-from-url-capitalizes
  (testing "Capitalizes the title"
    (let [title (website/extract-title-from-url "/docs/overview.md")]
      (is (= "Overview" title)))))

;; ============================================================================
;; Sitemap Parsing Tests
;; ============================================================================

(deftest extract-urls-from-sitemap-basic
  (testing "Extracts URLs from valid sitemap XML"
    (let [sitemap-xml (clojure.xml/parse (java.io.ByteArrayInputStream.
                                          (.getBytes fixtures/sample-sitemap-xml)))
          urls (website/extract-urls-from-sitemap sitemap-xml)]
      (is (= 3 (count urls)))
      (is (every? :loc urls))
      (is (every? :lastmod urls)))))

(deftest extract-urls-from-sitemap-with-base-url
  (testing "Applies base URL to extracted URLs"
    (let [sitemap-xml (clojure.xml/parse (java.io.ByteArrayInputStream.
                                          (.getBytes fixtures/sample-sitemap-xml)))
          urls (website/extract-urls-from-sitemap sitemap-xml "http://base.com")]
      ;; URLs in the sample already have http://example.com, so base shouldn't change them
      (is (every? #(str/starts-with? (:loc %) "http://") urls)))))

(deftest filter-markdown-urls-keeps-md
  (testing "Keeps only .md URLs"
    (let [sitemap-xml (clojure.xml/parse (java.io.ByteArrayInputStream.
                                          (.getBytes fixtures/sample-sitemap-xml)))
          all-urls (website/extract-urls-from-sitemap sitemap-xml)
          md-urls (website/filter-markdown-urls all-urls)]
      ;; Sample has 2 .md files and 1 .html file
      (is (= 2 (count md-urls)))
      (is (every? #(str/ends-with? (:loc %) ".md") md-urls)))))

(deftest filter-markdown-urls-excludes-html
  (testing "Excludes .html URLs"
    (let [urls [{:loc "http://example.com/page.md"}
                {:loc "http://example.com/page.html"}
                {:loc "http://example.com/other.md"}]
          filtered (website/filter-markdown-urls urls)]
      (is (= 2 (count filtered)))
      (is (not-any? #(str/ends-with? (:loc %) ".html") filtered)))))

;; ============================================================================
;; url-to-doc Tests
;; ============================================================================

(deftest url-to-doc-creates-valid-doc
  (testing "Creates document from URL entry"
    (let [url-entry {:loc "http://localhost:1313/en/about/index.md"
                     :lastmod "2024-01-15"}
          doc (website/url-to-doc url-entry)]
      (is (string? (:id doc)))
      (is (= (:id doc) (:doc_num doc)))
      (is (= "http://localhost:1313/en/about/index.md" (:url doc)))
      (is (= "2024-01-15" (:lastmod doc)))
      (is (= "About" (:title doc)))
      (is (= "website" (:type doc))))))

(deftest url-to-doc-generates-consistent-id
  (testing "Same URL produces same document ID"
    (let [url-entry {:loc "http://example.com/test.md" :lastmod "2024-01-15"}
          doc1 (website/url-to-doc url-entry)
          doc2 (website/url-to-doc url-entry)]
      (is (= (:id doc1) (:id doc2))))))

(deftest url-to-doc-different-urls-different-ids
  (testing "Different URLs produce different document IDs"
    (let [doc1 (website/url-to-doc {:loc "http://example.com/page1.md" :lastmod "2024-01-15"})
          doc2 (website/url-to-doc {:loc "http://example.com/page2.md" :lastmod "2024-01-15"})]
      (is (not= (:id doc1) (:id doc2))))))

;; ============================================================================
;; Integration: URL utilities work together
;; ============================================================================

(deftest url-utilities-roundtrip
  (testing "URL utilities can round-trip"
    (let [base "http://localhost:1313"
          absolute "http://localhost:1313/en/guide/intro.md"
          relative (website/make-relative-url base absolute)
          back-to-absolute (website/make-absolute-url base relative)]
      (is (= "/en/guide/intro.md" relative))
      (is (= "http://localhost:1313/en/guide/intro.md" back-to-absolute)))))

(deftest sitemap-to-docs-workflow
  (testing "Can process sitemap to documents"
    (let [sitemap-xml (clojure.xml/parse (java.io.ByteArrayInputStream.
                                          (.getBytes fixtures/sample-sitemap-xml)))
          urls (-> sitemap-xml
                   website/extract-urls-from-sitemap
                   website/filter-markdown-urls)
          docs (map website/url-to-doc urls)]
      (is (= 2 (count docs)))
      (is (every? fixtures/valid-doc? docs)))))

(ns digdir.docs.website-test
  "Tests for digdir.docs.website namespace."
  (:require [clojure.xml :as xml]
            [clojure.test :refer [deftest testing is ]]
            [clojure.string :as str]
            [digdir.docs.website :as website]
            [digdir.docs.test-fixtures :as fixtures])
  (:import (java.io ByteArrayInputStream)
           (java.net InetAddress)))

(defn- address
  [& octets]
  (InetAddress/getByAddress
   (byte-array (map #(unchecked-byte (int %)) octets))))

(deftest fetch-policy-rejects-non-http-and-untrusted-hosts
  (let [policy (assoc website/default-fetch-policy
                      :allowed-hosts #{"docs.example.com"})]
    (binding [website/*host-resolver* (fn [_] [(address 93 184 216 34)])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"HTTP or HTTPS"
                            (website/validate-fetch-uri! "file:///etc/passwd" policy)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not allowed"
                            (website/validate-fetch-uri! "https://evil.example/a.md" policy)))
      (is (instance? java.net.URI
                     (website/validate-fetch-uri! "https://docs.example.com/a.md" policy))))))

(deftest fetch-policy-blocks-every-private-or-reserved-answer
  (let [policy (assoc website/default-fetch-policy
                      :allowed-hosts #{"docs.example.com"})]
    (doseq [blocked [(address 127 0 0 1)
                     (address 10 0 0 1)
                     (address 169 254 169 254)
                     (address 172 16 0 1)
                     (address 192 168 0 1)
                     (address 100 64 0 1)
                     (address 192 0 2 1)
                     (address 198 51 100 1)
                     (address 203 0 113 1)]]
      (binding [website/*host-resolver* (fn [_] [(address 93 184 216 34) blocked])]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"private or reserved"
                              (website/validate-fetch-uri!
                               "https://docs.example.com/a.md" policy)))))))

(deftest website-policy-does-not-trust-sitemap-provided-hosts
  (let [policy (website/website-fetch-policy
                {:sitemap/url "https://docs.example.com/sitemap.xml"
                 :base-url "https://content.example.com"})]
    (is (= #{"docs.example.com" "content.example.com"}
           (:allowed-hosts policy)))
    (binding [website/*host-resolver* (fn [_] [(address 93 184 216 34)])]
      (is (thrown? clojure.lang.ExceptionInfo
                   (website/validate-fetch-uri! "https://metadata.example/a.md"
                                                policy))))))

(deftest response-reader-enforces-byte-limit-without-content-length
  (is (= "abcd"
         (String. ^bytes (website/read-bounded-bytes
                          (ByteArrayInputStream. (.getBytes "abcd" "UTF-8")) 4)
                  "UTF-8")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"size limit"
                        (website/read-bounded-bytes
                         (ByteArrayInputStream. (.getBytes "abcde" "UTF-8")) 4))))

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

(deftest extract-sub-sitemap-urls-from-index
  (testing "Pulls every <sitemap><loc> out of a sitemapindex"
    (let [sitemap-xml (clojure.xml/parse (java.io.ByteArrayInputStream.
                                          (.getBytes fixtures/sample-sitemapindex-xml)))
          locs (vec (website/extract-sub-sitemap-urls sitemap-xml))]
      (is (= ["http://example.com/nb/sitemap-markdown.xml"
              "http://example.com/en/sitemap-markdown.xml"]
             locs)))))

(deftest parse-sitemap-recurses-sitemapindex
  (testing "When given a sitemapindex root, parse-sitemap recurses into each child"
    (let [index-xml (clojure.xml/parse (java.io.ByteArrayInputStream.
                                        (.getBytes fixtures/sample-sitemapindex-xml)))
          leaf-xml (clojure.xml/parse (java.io.ByteArrayInputStream.
                                       (.getBytes fixtures/sample-sitemap-xml)))
          fetched (atom [])]
      (with-redefs [website/fetch-sitemap
                    (fn [url]
                      (swap! fetched conj url)
                      (if (str/includes? url "sitemap-markdown.xml")
                        leaf-xml
                        index-xml))]
        (let [urls (vec (website/parse-sitemap "http://example.com/root-sitemap.xml"))]
          ;; Root + 2 leaf fetches
          (is (= ["http://example.com/root-sitemap.xml"
                  "http://example.com/nb/sitemap-markdown.xml"
                  "http://example.com/en/sitemap-markdown.xml"]
                 @fetched))
          ;; Each leaf yields 2 .md urls (page3.html filtered out) -> 4 total
          (is (= 4 (count urls)))
          (is (every? #(str/ends-with? (:loc %) ".md") urls)))))))

(deftest parse-sitemap-urlset-unchanged
  (testing "When given a plain urlset, parse-sitemap returns its markdown urls without recursing"
    (let [leaf-xml (clojure.xml/parse (java.io.ByteArrayInputStream.
                                       (.getBytes fixtures/sample-sitemap-xml)))
          fetched (atom [])]
      (with-redefs [website/fetch-sitemap
                    (fn [url]
                      (swap! fetched conj url)
                      leaf-xml)]
        (let [urls (vec (website/parse-sitemap "http://example.com/sitemap.xml"))]
          (is (= ["http://example.com/sitemap.xml"] @fetched))
          (is (= 2 (count urls))))))))

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

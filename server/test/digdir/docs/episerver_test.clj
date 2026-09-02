(ns digdir.docs.episerver-test
  "Tests for digdir.docs.episerver namespace."
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.string :as str]
            [digdir.docs.episerver :as episerver]
            [digdir.docs.test-fixtures :as fixtures]))

;; ============================================================================
;; HTML Stripping Tests
;; ============================================================================

(deftest strip-html-tags-basic
  (testing "Removes basic HTML tags"
    (is (= "Hello World"
           (episerver/strip-html-tags "<p>Hello World</p>")))))

(deftest strip-html-tags-nested
  (testing "Removes nested HTML tags"
    (is (= "Hello World"
           (episerver/strip-html-tags "<div><p><span>Hello</span> <b>World</b></p></div>")))))

(deftest strip-html-tags-preserves-text
  (testing "Preserves text content"
    (is (str/includes? (episerver/strip-html-tags "<p>Some text</p><p>More text</p>")
                       "Some text"))
    (is (str/includes? (episerver/strip-html-tags "<p>Some text</p><p>More text</p>")
                       "More text"))))

(deftest strip-html-tags-handles-entities
  (testing "Converts HTML entities"
    (is (= "foo & bar"
           (episerver/strip-html-tags "foo &amp; bar")))
    (is (= "foo < bar"
           (episerver/strip-html-tags "foo &lt; bar")))
    (is (= "foo > bar"
           (episerver/strip-html-tags "foo &gt; bar")))))

(deftest strip-html-tags-handles-norwegian
  (testing "Converts Norwegian character entities"
    (is (= "æ ø å"
           (episerver/strip-html-tags "&aelig; &oslash; &aring;")))
    (is (= "Æ Ø Å"
           (episerver/strip-html-tags "&AElig; &Oslash; &Aring;")))))

(deftest strip-html-tags-nil-input
  (testing "Returns nil for nil input"
    (is (nil? (episerver/strip-html-tags nil)))))

(deftest strip-html-tags-collapses-whitespace
  (testing "Collapses multiple whitespaces"
    (is (= "Hello World"
           (episerver/strip-html-tags "Hello    World")))))

;; ============================================================================
;; HTML to Markdown Tests
;; ============================================================================

(deftest html-to-markdown-headings
  (testing "Converts HTML headings to Markdown"
    (is (str/includes? (episerver/html-to-markdown-simple "<h1>Title</h1>")
                       "# Title"))
    (is (str/includes? (episerver/html-to-markdown-simple "<h2>Section</h2>")
                       "## Section"))
    (is (str/includes? (episerver/html-to-markdown-simple "<h3>Subsection</h3>")
                       "### Subsection"))))

(deftest html-to-markdown-paragraphs
  (testing "Converts paragraphs"
    (let [result (episerver/html-to-markdown-simple "<p>First paragraph</p><p>Second paragraph</p>")]
      (is (str/includes? result "First paragraph"))
      (is (str/includes? result "Second paragraph")))))

(deftest html-to-markdown-lists
  (testing "Converts lists to markdown"
    (let [result (episerver/html-to-markdown-simple "<ul><li>Item 1</li><li>Item 2</li></ul>")]
      (is (str/includes? result "- Item 1"))
      (is (str/includes? result "- Item 2")))))

(deftest html-to-markdown-bold
  (testing "Converts bold text"
    (is (str/includes? (episerver/html-to-markdown-simple "<strong>bold</strong>")
                       "**bold**"))
    (is (str/includes? (episerver/html-to-markdown-simple "<b>bold</b>")
                       "**bold**"))))

(deftest html-to-markdown-italic
  (testing "Converts italic text"
    (is (str/includes? (episerver/html-to-markdown-simple "<em>italic</em>")
                       "*italic*"))
    (is (str/includes? (episerver/html-to-markdown-simple "<i>italic</i>")
                       "*italic*"))))

(deftest html-to-markdown-links
  (testing "Converts links to markdown"
    (is (str/includes? (episerver/html-to-markdown-simple "<a href=\"http://example.com\">Link</a>")
                       "[Link](http://example.com)"))))

(deftest html-to-markdown-line-breaks
  (testing "Converts br tags to newlines"
    (is (str/includes? (episerver/html-to-markdown-simple "Line 1<br>Line 2")
                       "\n"))))

(deftest html-to-markdown-handles-entities
  (testing "Converts HTML entities in markdown output"
    (let [result (episerver/html-to-markdown-simple "<p>Test &amp; example</p>")]
      (is (str/includes? result "Test & example")))))

(deftest html-to-markdown-nil-input
  (testing "Returns nil for nil input"
    (is (nil? (episerver/html-to-markdown-simple nil)))))

;; ============================================================================
;; merge-content-fields Tests
;; ============================================================================

(deftest merge-content-fields-all-parts
  (testing "Merges all content fields"
    (let [props {"Heading" {:Value "<h1>Title</h1>"}
                 "MainIntro" {:Value "<p>Introduction</p>"}
                 "MainBody" {:Value "<p>Body content</p>"}}
          result (episerver/merge-content-fields props)]
      (is (str/includes? result "Title"))
      (is (str/includes? result "Introduction"))
      (is (str/includes? result "Body content")))))

(deftest merge-content-fields-missing-parts
  (testing "Handles missing fields gracefully"
    (let [props {"MainBody" {:Value "<p>Body only</p>"}}
          result (episerver/merge-content-fields props)]
      (is (str/includes? result "Body only")))))

(deftest merge-content-fields-empty
  (testing "Handles empty props"
    (let [result (episerver/merge-content-fields {})]
      (is (= "" result)))))

;; ============================================================================
;; Property Extraction Tests
;; ============================================================================

(deftest get-property-value-basic
  (testing "Gets property value from property sequence"
    ;; get-property-value expects a sequence of property maps
    (let [props [{:Name "PageName" :Value "Test Page"}
                 {:Name "OtherProp" :Value "Other Value"}]]
      (is (= "Test Page" (episerver/get-property-value props "PageName"))))))

(deftest get-property-value-missing
  (testing "Returns nil for missing property"
    (let [props [{:Name "PageName" :Value "Test Page"}]]
      (is (nil? (episerver/get-property-value props "NonExistent"))))))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(deftest html-conversion-complex-document
  (testing "Converts complex HTML document to markdown"
    (let [html "<h1>Main Title</h1>
                <p>Introduction paragraph with <strong>bold</strong> and <em>italic</em>.</p>
                <h2>Section 1</h2>
                <p>Content here.</p>
                <ul>
                  <li>Item 1</li>
                  <li>Item 2</li>
                </ul>"
          result (episerver/html-to-markdown-simple html)]
      (is (str/includes? result "# Main Title"))
      (is (str/includes? result "## Section 1"))
      (is (str/includes? result "**bold**"))
      (is (str/includes? result "*italic*"))
      (is (str/includes? result "- Item 1"))
      (is (str/includes? result "- Item 2")))))

(deftest html-conversion-norwegian-content
  (testing "Handles Norwegian content properly"
    (let [html "<h1>Blåbærsyltetøy</h1><p>Ærlighet varer lengst.</p>"
          result (episerver/html-to-markdown-simple html)]
      ;; The characters should pass through (they're not HTML entities)
      (is (str/includes? result "Blåbærsyltetøy"))
      (is (str/includes? result "Ærlighet")))))

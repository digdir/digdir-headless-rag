(ns digdir.docs.test-fixtures
  "Shared test fixtures and utilities for digdir.docs.* tests."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; ============================================================================
;; Sample Markdown Content
;; ============================================================================

(def sample-markdown-simple
  "# Main Title

Introduction paragraph with some content.

## Section 1

Section 1 content goes here.

## Section 2

Section 2 content goes here.")

(def sample-markdown-nested
  "# Document Title

Overview text.

## Chapter 1

Chapter 1 introduction.

### Section 1.1

Section 1.1 content.

### Section 1.2

Section 1.2 content.

## Chapter 2

Chapter 2 content.")

(def sample-markdown-no-headers
  "This is a document without any headers.

It just has plain paragraphs of text.

Multiple paragraphs in fact.")

(def sample-markdown-long
  (str "# Long Document\n\n"
       (str/join "\n\n" (repeat 50 "This is a paragraph with enough content to make the document reasonably long for testing chunking with minimum length requirements."))))

;; ============================================================================
;; Sample Documents
;; ============================================================================

(def sample-folder-doc
  {:id "folder-doc-1"
   :doc_num "folder-doc-1"
   :path "/test/docs/sample.md"
   :title "Sample Document"
   :lastmod "2024-01-15T10:00:00Z"
   :type "folder"
   :markdown sample-markdown-simple})

(def sample-website-doc
  {:id "website-doc-1"
   :doc_num "website-doc-1"
   :url "http://example.com/docs/sample.md"
   :title "Sample Page"
   :lastmod "2024-01-15"
   :type "website"
   :markdown sample-markdown-simple})

(def sample-episerver-doc
  {:id "episerver-doc-1"
   :doc_num "episerver-doc-1"
   :page_guid "abc-123-def-456"
   :page_type "ArticlePage"
   :url_segment "sample-article"
   :title "Sample Article"
   :type "episerver"
   :markdown sample-markdown-simple})

(def sample-kudos-doc
  {:id "12345"
   :doc_num "12345"
   :title "Government Report 2024"
   :authors ["Author One" "Author Two"]
   :orgs_long ["Ministry of Example" "Department of Testing"]
   :concerned_years [2024]
   :publish_date "2024-01-15"
   :type "kudos"
   :markdown sample-markdown-simple})

;; ============================================================================
;; Sample Chunks
;; ============================================================================

(def sample-chunks
  [{:chunk_id "chunk-hash-0"
    :doc_num "doc-1"
    :chunk_index 0
    :content_markdown "# Main Title\n\nIntroduction paragraph with some content."
    :metadata "Main Title"}
   {:chunk_id "chunk-hash-1"
    :doc_num "doc-1"
    :chunk_index 1
    :content_markdown "## Section 1\n\nSection 1 content goes here."
    :metadata "Section 1"}
   {:chunk_id "chunk-hash-2"
    :doc_num "doc-1"
    :chunk_index 2
    :content_markdown "## Section 2\n\nSection 2 content goes here."
    :metadata "Section 2"}])

(def sample-chunks-with-phrases
  (mapv #(assoc % :search-phrases ["phrase 1" "phrase 2" "phrase 3"])
        sample-chunks))

;; ============================================================================
;; Sample Configurations
;; ============================================================================

(def sample-pipeline-config
  {:parallelism/documents 2
   :parallelism/store 1
   :fault-tolerance/max-document-failures 3
   :chunks/strategy :header-based
   :chunks/minimum-length 50
   :chunks/maximum-length 10000
   :search-phrases/model "gpt-4o"
   :search-phrases/fallback-model "gpt-3.5-turbo"
   :search-phrases/prompt "Generate search phrases for: REPLACE_ME"
   :store/coll-prefix "test_"})

(def sample-folder-config
  (merge sample-pipeline-config
         {:folder/path "/test/docs"
          :base-path "/test/docs"
          :base-url "https://docs.example.com/"
          :files/limit 100}))

(def sample-website-config
  (merge sample-pipeline-config
         {:sitemap/url "http://localhost:1313/sitemap.xml"
          :base-url "http://localhost:1313"
          :urls/limit 100}))

(def sample-episerver-config
  (merge sample-pipeline-config
         {:xml/path "/test/episerver.xml"
          :language "no"
          :include-page-types #{"ArticlePage" "StandardPage"}
          :pages/limit 100}))

(def sample-kudos-config
  (merge sample-pipeline-config
         {:kudos/starting-page 1
          :documents/types ["Veileder" "Rundskriv"]
          :documents/limit 100}))

;; ============================================================================
;; Sample XML Content
;; ============================================================================

(def sample-sitemap-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">
  <url>
    <loc>http://example.com/docs/page1.md</loc>
    <lastmod>2024-01-15</lastmod>
  </url>
  <url>
    <loc>http://example.com/docs/page2.md</loc>
    <lastmod>2024-01-16</lastmod>
  </url>
  <url>
    <loc>http://example.com/docs/page3.html</loc>
    <lastmod>2024-01-17</lastmod>
  </url>
</urlset>")

(def sample-episerver-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<ArrayOfExportedPage xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">
  <ExportedPage>
    <PageGuid>abc-123</PageGuid>
    <PageType>ArticlePage</PageType>
    <PageLanguage>no</PageLanguage>
    <IsDeleted>false</IsDeleted>
    <Properties>
      <ExportedProperty>
        <Name>PageName</Name>
        <Value>Test Article</Value>
      </ExportedProperty>
      <ExportedProperty>
        <Name>MainIntro</Name>
        <Value>&lt;p&gt;Introduction text&lt;/p&gt;</Value>
      </ExportedProperty>
      <ExportedProperty>
        <Name>MainBody</Name>
        <Value>&lt;h2&gt;Section&lt;/h2&gt;&lt;p&gt;Body content&lt;/p&gt;</Value>
      </ExportedProperty>
    </Properties>
  </ExportedPage>
  <ExportedPage>
    <PageGuid>def-456</PageGuid>
    <PageType>StandardPage</PageType>
    <PageLanguage>en</PageLanguage>
    <IsDeleted>true</IsDeleted>
    <Properties>
      <ExportedProperty>
        <Name>PageName</Name>
        <Value>Deleted Page</Value>
      </ExportedProperty>
    </Properties>
  </ExportedPage>
</ArrayOfExportedPage>")

;; ============================================================================
;; Temp Directory Fixture
;; ============================================================================

(defn with-temp-dir
  "Execute function f with a temporary directory, cleanup after."
  [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "digdir-test-" (System/currentTimeMillis)))]
    (.mkdirs dir)
    (try
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))]
          (.delete file))))))

(defn create-temp-file
  "Create a temp file with given content in the specified directory."
  [dir filename content]
  (let [file (io/file dir filename)]
    (.mkdirs (.getParentFile file))
    (spit file content)
    file))

(defn create-markdown-files
  "Create a set of markdown files in a temp directory for testing.
   Returns the directory."
  [dir]
  (create-temp-file dir "doc1.md" sample-markdown-simple)
  (create-temp-file dir "doc2.md" sample-markdown-nested)
  (create-temp-file dir "subdir/doc3.md" sample-markdown-simple)
  (create-temp-file dir "not-markdown.txt" "This is not markdown")
  dir)

;; ============================================================================
;; Mock TypeSense Client
;; ============================================================================

(defn create-mock-ts-store
  "Creates an in-memory mock TypeSense store.
   Returns a map with mock functions and an atom for inspecting state."
  []
  (let [store (atom {:collections {} :documents {}})]
    {:store store

     :create-collection!
     (fn [_ schema]
       (let [name (:name schema)]
         (if (get-in @store [:collections name])
           (throw (ex-info "Collection already exists"
                          {:type :typesense.client/conflict}))
           (do (swap! store assoc-in [:collections name] schema)
               schema))))

     :retrieve-document
     (fn [_ coll id]
       (if-let [doc (get-in @store [:documents coll id])]
         doc
         (throw (ex-info "Document not found"
                        {:type :typesense.client/not-found}))))

     :upsert-document!
     (fn [_ coll doc]
       (swap! store assoc-in [:documents coll (:id doc)] doc)
       doc)

     :upsert-documents!
     (fn [_ coll docs]
       (doseq [doc docs]
         (swap! store assoc-in [:documents coll (or (:id doc) (:chunk_id doc))] doc))
       {:num_success (count docs)})}))

(def mock-ts-admin
  "Mock TypeSense admin client config."
  {:uri "http://mock-typesense:8108" :api-key "mock-api-key"})

;; ============================================================================
;; Mock OpenAI Client
;; ============================================================================

(defn create-mock-openai
  "Creates a mock OpenAI API response generator.
   Returns a function that can be used with with-redefs."
  ([]
   (create-mock-openai ["search phrase 1" "search phrase 2" "search phrase 3"]))
  ([phrases]
   (fn [_ _request]
     {:choices [{:message {:content (str/join ", " phrases)}}]})))

(defn create-failing-openai
  "Creates a mock OpenAI API that fails with the given exception."
  [exception]
  (fn [_ _request]
    (throw exception)))

;; ============================================================================
;; Assertion Helpers
;; ============================================================================

(defn has-keys?
  "Check if map has all specified keys."
  [m & ks]
  (every? #(contains? m %) ks))

(defn valid-chunk?
  "Validate that a chunk has required fields."
  [chunk]
  (and (has-keys? chunk :chunk_id :doc_num :chunk_index :content_markdown)
       (string? (:chunk_id chunk))
       (string? (:doc_num chunk))
       (integer? (:chunk_index chunk))
       (string? (:content_markdown chunk))))

(defn valid-doc?
  "Validate that a document has required fields."
  [doc]
  (and (has-keys? doc :id :doc_num :title :type)
       (string? (:id doc))
       (string? (:doc_num doc))
       (string? (:title doc))
       (string? (:type doc))))

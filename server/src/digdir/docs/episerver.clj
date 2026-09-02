(ns digdir.docs.episerver
  "EPiServer document source - imports content from EPiServer XML exports.

   This pipeline:
   1. Parses EPiServer XML export file
   2. Filters by language, published status, and page type
   3. Converts HTML content to markdown
   4. Chunks content by headers
   5. Generates search phrases via LLM
   6. Stores documents, chunks, and phrases in TypeSense"
  {:clj-kondo/ignore true}
  (:require [digdir.docs.pipeline.core :as core]
            [digdir.docs.pipeline.storage :as storage]
            [digdir.docs.pipeline.orchestration :as orch]
            [digdir.docs.pipeline.search-phrases :as search-phrases]
            [digdir.docs.pipeline.telemetry :as telemetry]
            [digdir.docs.pipeline.protocol :as proto]
            [clojure.string :as str]
            [clojure.data.xml :as xml]
            [clojure.java.io :as jio]
            [missionary.core :as m]
            [net.cgrand.xforms.rfs :as rfs]
            [taoensso.telemere :as t]
            [hyperfiddle.rcf :refer [tests tap %]]))

;; ============================================================================
;; XML Parsing Functions (Streaming)
;; ============================================================================

(defn extract-pages-streaming
  "Lazily extracts TransferContentData elements from XML file without loading entire file into memory.
   Returns a lazy sequence of page elements."
  [xml-path]
  (t/event! :episerver/parsing-xml {:data {:path xml-path}})
  (let [input-stream (jio/input-stream xml-path)
        _ (let [mark-bytes (.markSupported input-stream)]
            (when mark-bytes
              (.mark input-stream 3)
              (let [b1 (.read input-stream)
                    b2 (.read input-stream)
                    b3 (.read input-stream)]
                (when-not (and (= b1 0xEF) (= b2 0xBB) (= b3 0xBF))
                  (.reset input-stream)))))
        reader (jio/reader input-stream :encoding "UTF-8")
        parsed (xml/parse reader :namespace-aware false :support-dtd false)
        pages-node (first (filter #(= :pages (:tag %)) (:content parsed)))]
    (when pages-node
      (let [transfer-data-elements (filter #(= :TransferContentData (:tag %)) (:content pages-node))]
        (t/event! :episerver/found-pages {:data {:count (count transfer-data-elements)}})
        transfer-data-elements))))

;; ============================================================================
;; Property Extraction
;; ============================================================================

(defn extract-raw-properties
  "Extracts RawProperty elements from a page"
  [page]
  (let [raw-content (first (filter #(= :RawContentData (:tag %)) (:content page)))
        property-node (first (filter #(= :Property (:tag %)) (:content raw-content)))]
    (filter #(= :RawProperty (:tag %)) (:content property-node))))

(defn parse-raw-property
  "Parses a single RawProperty XML element into a map"
  [raw-prop]
  (let [content (:content raw-prop)
        get-text (fn [tag-name]
                   (let [elem (first (filter #(= tag-name (:tag %)) content))]
                     (first (:content elem))))]
    {:Name (get-text :Name)
     :Value (get-text :Value)
     :Type (get-text :Type)
     :IsNull (get-text :IsNull)
     :IsLanguageSpecific (get-text :IsLanguageSpecific)
     :PropertyDefinitionID (get-text :PropertyDefinitionID)}))

(defn extract-page-properties
  "Extracts all properties from a page into a convenient map"
  [page]
  (let [raw-props (extract-raw-properties page)
        parsed-props (map parse-raw-property raw-props)]
    (into {} (map (fn [p] [(:Name p) p]) parsed-props))))

(defn get-property-value
  "Gets the value of a property by name from a page's properties.
   Returns nil if not found or if IsNull is true."
  [properties property-name]
  (let [prop (first (filter #(= property-name (get-in % [:Name])) properties))]
    (when (and prop (not= "true" (get-in prop [:IsNull])))
      (get-in prop [:Value]))))

;; ============================================================================
;; Page Filtering
;; ============================================================================

(defn filter-published-pages
  "Filters pages to only include published pages (PageWorkStatus = 4)"
  [pages]
  (t/event! :episerver/filtering-published)
  (filter (fn [page]
            (let [props (extract-page-properties page)
                  status (get-in props ["PageWorkStatus" :Value])]
              (= "4" status)))
          pages))

(defn filter-by-language
  "Filters pages by language"
  [pages language]
  (t/event! :episerver/filtering-by-language {:data {:language language}})
  (filter (fn [page]
            (let [props (extract-page-properties page)
                  lang (get-in props ["PageLanguageBranch" :Value])]
              (= language lang)))
          pages))

(defn filter-deleted-pages
  "Filters out deleted pages (PageDeleted = False)"
  [pages]
  (t/event! :episerver/filtering-deleted)
  (filter (fn [page]
            (let [props (extract-page-properties page)
                  deleted (get-in props ["PageDeleted" :Value])]
              (= "False" deleted)))
          pages))

;; ============================================================================
;; HTML to Markdown Conversion
;; ============================================================================

(defn strip-html-tags
  "Simple HTML tag stripping - removes all HTML tags"
  [html]
  (when html
    (-> html
        (str/replace #"<[^>]*>" " ")
        (str/replace #"&nbsp;" " ")
        (str/replace #"&amp;" "&")
        (str/replace #"&lt;" "<")
        (str/replace #"&gt;" ">")
        (str/replace #"&quot;" "\"")
        (str/replace #"&oslash;" "ø")
        (str/replace #"&aring;" "å")
        (str/replace #"&aelig;" "æ")
        (str/replace #"&Oslash;" "Ø")
        (str/replace #"&Aring;" "Å")
        (str/replace #"&AElig;" "Æ")
        (str/replace #"\s+" " ")
        str/trim)))

(defn html-to-markdown-simple
  "Basic HTML to Markdown conversion"
  [html]
  (when html
    (-> html
        ;; Headers
        (str/replace #"<h1[^>]*>(.*?)</h1>" "# $1\n\n")
        (str/replace #"<h2[^>]*>(.*?)</h2>" "## $1\n\n")
        (str/replace #"<h3[^>]*>(.*?)</h3>" "### $1\n\n")
        (str/replace #"<h4[^>]*>(.*?)</h4>" "#### $1\n\n")
        (str/replace #"<h5[^>]*>(.*?)</h5>" "##### $1\n\n")
        (str/replace #"<h6[^>]*>(.*?)</h6>" "###### $1\n\n")
        ;; Lists
        (str/replace #"<li[^>]*>(.*?)</li>" "- $1\n")
        (str/replace #"<ul[^>]*>" "\n")
        (str/replace #"</ul>" "\n")
        (str/replace #"<ol[^>]*>" "\n")
        (str/replace #"</ol>" "\n")
        ;; Paragraphs
        (str/replace #"<p[^>]*>(.*?)</p>" "$1\n\n")
        ;; Bold and italic
        (str/replace #"<strong[^>]*>(.*?)</strong>" "**$1**")
        (str/replace #"<b[^>]*>(.*?)</b>" "**$1**")
        (str/replace #"<em[^>]*>(.*?)</em>" "*$1*")
        (str/replace #"<i[^>]*>(.*?)</i>" "*$1*")
        ;; Links
        (str/replace #"<a[^>]*href=[\"']([^\"']*)[\"'][^>]*>(.*?)</a>" "[$2]($1)")
        ;; Line breaks
        (str/replace #"<br\s*/?>" "\n")
        ;; Divs
        (str/replace #"<div[^>]*>" "\n")
        (str/replace #"</div>" "\n")
        ;; EPiServer specific
        (str/replace #"<div[^>]*data-contentguid[^>]*>.*?</div>" "")
        ;; HTML entities
        (str/replace #"&nbsp;" " ")
        (str/replace #"&amp;" "&")
        (str/replace #"&lt;" "<")
        (str/replace #"&gt;" ">")
        (str/replace #"&quot;" "\"")
        (str/replace #"&oslash;" "ø")
        (str/replace #"&aring;" "å")
        (str/replace #"&aelig;" "æ")
        (str/replace #"&Oslash;" "Ø")
        (str/replace #"&Aring;" "Å")
        (str/replace #"&AElig;" "Æ")
        (str/replace #"&ndash;" "–")
        (str/replace #"&mdash;" "—")
        ;; Remove remaining tags
        (str/replace #"<[^>]*>" "")
        ;; Clean whitespace
        (str/replace #"\n\n\n+" "\n\n")
        str/trim)))

(defn merge-content-fields
  "Merges MainIntro, MainBody and other content fields into a single markdown string"
  [props]
  (let [main-intro (get-in props ["MainIntro" :Value])
        main-body (get-in props ["MainBody" :Value])
        heading (get-in props ["Heading" :Value])
        parts (filter some? [heading main-intro main-body])]
    (str/join "\n\n" (map html-to-markdown-simple parts))))

;; ============================================================================
;; Document Structure
;; ============================================================================

(defn build-url-from-segment
  "Builds a URL from the PageURLSegment."
  [url-segment]
  (if url-segment
    (str "/" url-segment)
    "/unknown"))

(defn page-to-doc
  "Converts an EPiServer page to document structure"
  [page]
  (let [props (extract-page-properties page)
        page-guid (get-in props ["PageGUID" :Value])
        page-name (get-in props ["PageName" :Value])
        url-segment (get-in props ["PageURLSegment" :Value])
        language (get-in props ["PageLanguageBranch" :Value])
        page-type (get-in props ["PageTypeName" :Value])
        page-changed (get-in props ["PageChanged" :Value])
        page-saved (get-in props ["PageSaved" :Value])
        meta-keywords (get-in props ["MetaKeywords" :Value])
        meta-description (get-in props ["MetaDescription" :Value])
        content-markdown (merge-content-fields props)
        doc-id (core/sha256-short-hash page-guid)]
    {:id doc-id
     :doc_num doc-id
     :page_guid page-guid
     :title (or page-name "Untitled")
     :url (build-url-from-segment url-segment)
     :url_segment (or url-segment "")
     :language (or language "no")
     :page_type (or page-type "Unknown")
     :lastmod (or page-changed page-saved)
     :type "episerver"
     :content_markdown content-markdown
     :meta_keywords (or meta-keywords "")
     :meta_description (or meta-description "")}))

;; ============================================================================
;; TypeSense Schemas
;; ============================================================================

(defn episerver-docs-schema [collection-name]
  {:name collection-name
   :fields [{:facet true :index true :name "id" :optional false :sort true :type "string"}
            {:facet true :index true :name "doc_num" :optional false :sort true :type "string"}
            {:facet true :index true :name "page_guid" :optional false :sort true :type "string"}
            {:facet false :index true :locale "en" :name "title" :optional false :sort false :type "string"}
            {:facet true :index true :name "url" :optional false :sort false :type "string"}
            {:facet true :index true :name "url_segment" :optional false :sort false :type "string"}
            {:facet true :index true :name "language" :optional false :sort true :type "string"}
            {:facet true :index true :name "page_type" :optional false :sort true :type "string"}
            {:facet true :index true :name "lastmod" :optional true :sort true :type "string"}
            {:facet true :index true :name "type" :optional false :sort false :type "string"}
            {:facet false :index true :name "meta_keywords" :optional true :sort false :type "string"}
            {:facet false :index true :name "meta_description" :optional true :sort false :type "string"}
            {:facet false :index true :name "total_chunks" :optional true :sort true :type "int32"}]})

(defn episerver-chunks-schema [[docs-collection-name chunks-collection-name :as coll-ids]]
  {:name chunks-collection-name
   :fields [{:facet true :index true :name "chunk_id" :optional false :sort true :type "string"}
            {:facet true :index true :name "doc_num" :optional false
             :reference (str docs-collection-name ".doc_num")
             :async_reference true
             :sort false :type "string"}
            {:facet true :index true :name "chunk_index" :optional false :sort true :type "int32"}
            {:facet false :index true :locale "en" :name "content_markdown" :optional false :sort false :type "string"}
            {:facet false :index true :name "metadata" :optional true :sort false :type "string"}
            {:facet false :index true :name "content_length" :optional true :sort true :type "int32"}
            {:facet true :index true :name "page_guid" :optional false :sort false :type "string"}]})

(defn episerver-phrases-schema
  [[docs-collection-name chunks-collection-name phrases-collection-name :as coll-ids]]
  {:default_sorting_field "chunk_id"
   :fields
   [{:facet true :index true :name "chunk_id" :optional false :sort true :type "string"}
    {:async_reference false :facet true :index true :name "doc_num" :optional false
     :reference (str docs-collection-name ".doc_num") :sort true :type "string"}
    {:facet false :index true :name "search_phrase" :optional false :sort false :type "string"}
    {:embed {:from ["search_phrase"] :model_config {:model_name "ts/all-MiniLM-L12-v2"}}
     :facet false :hnsw_params {:M 16 :ef_construction 200} :index true :name "phrase_vec"
     :num_dim 384 :optional true :sort false :type "float[]" :vec_dist "cosine"}]
   :name phrases-collection-name})

;; ============================================================================
;; Document Preparation Functions
;; ============================================================================

(defn prepare-episerver-doc
  "Prepares document for storage"
  [config doc]
  (-> doc
      (select-keys [:id :doc_num :page_guid :title :url :url_segment
                    :language :page_type :lastmod :type
                    :meta_keywords :meta_description :total_chunks])))

(defn prepare-episerver-chunks
  "Prepares chunks for storage"
  [config chunks]
  (mapv #(select-keys % [:chunk_id :doc_num :chunk_index
                         :content_markdown :content_length :metadata :page_guid])
        chunks))

;; ============================================================================
;; Backward Compatibility Functions
;; ============================================================================

(defn coll-ids
  "Generates collection IDs for documents, chunks, and phrases based on config."
  [config]
  (storage/coll-ids config))

(defn create-stores
  "Creates all three collections."
  [config]
  (storage/create-collections!
   config
   episerver-docs-schema
   episerver-chunks-schema
   episerver-phrases-schema))

;; ============================================================================
;; Configuration
;; ============================================================================

(def wview
  {:xml/path "cache/episerver-data/epix.xml"
   :language "no"
   :include-page-types []

   :parallelism/documents 2
   :parallelism/store 1
   :fault-tolerance/max-document-failures 10

   :pages/offset 0
   :pages/limit 30000

   :chunks/hash-changer 1
   :chunks/strategy :header-based
   :chunks/minimum-length 333
   :chunks/maximum-length (* 2 128000)

   :search-phrases/hash-changer 1
   :search-phrases/model "gpt-4o"
   :search-phrases/fallback-model :google/gemma-3-27b-it
   :search-phrases/prompt search-phrases/default-search-phrases-prompt

   :store/coll-prefix "episerver_"})

;; ============================================================================
;; Pipeline Implementation
;; ============================================================================

(defn mk-chunk-doc-t
  "Chunks a document"
  [config doc]
  (m/via m/blk (proto/chunk-document config doc :page_guid)))

(defn mk-prepare-document-t
  "Prepares a complete document from an EPiServer page"
  [config page]
  (m/sp
   (as-> page doc
     (page-to-doc doc)
     (m/? (mk-chunk-doc-t config doc))
     (m/? (search-phrases/mk-distill-doc-search-phrases-t config doc "episerver"))
     (do (core/say "Prepared EPiServer doc")
         (t/event! :episerver/document-prepared {:data {:title (:title doc)}})
         doc))))

(defn mk-store-document-t
  "Stores a prepared document"
  [config doc]
  (m/via m/blk
         (storage/store-complete-document!
          config doc
          prepare-episerver-doc
          (fn [chunks] (prepare-episerver-chunks config chunks)))))

(defn mk-filter-pages-f
  "Filters pages by limit/offset and page type"
  [config pages]
  (let [{:pages/keys [limit offset include-page-types]
         :or {limit 1000 offset 0}} config]
    (t/log! ["Processing pages with offset=" offset "limit=" limit])
    (m/eduction
     (comp
      (filter (fn [page]
                (if (seq include-page-types)
                  (let [props (extract-page-properties page)
                        page-type (get-in props ["PageTypeName" :Value])]
                    (contains? (set include-page-types) page-type))
                  true))))
     (drop offset)
     (take limit)
     pages)))

(defn mk-prepare-documents-f
  "Prepares documents in parallel"
  [config pages-f]
  (orch/mk-prepare-documents-f config mk-prepare-document-t pages-f :episerver))

(defn mk-store-documents-f
  "Stores documents in parallel"
  [config documents-f]
  (orch/mk-store-documents-f config mk-store-document-t documents-f))

(defn mk-materialize-t
  "Creates the complete materialization task"
  [config]
  (m/sp
    (t/event! :episerver/materializing {:data {:config config :colls (coll-ids config)}})

    (m/? (m/via m/blk (create-stores config)))

    (let [xml-path (:xml/path config)
          all-pages (m/? (m/via m/blk (extract-pages-streaming xml-path)))
          published-pages (m/? (m/via m/blk (filter-published-pages all-pages)))
          _ (t/event! :episerver/published-pages {:data {:count (count published-pages)}})
          not-deleted (m/? (m/via m/blk (filter-deleted-pages published-pages)))
          _ (t/event! :episerver/not-deleted-pages {:data {:count (count not-deleted)}})
          lang-filtered (m/? (m/via m/blk (filter-by-language not-deleted (:language config))))
          _ (t/event! :episerver/language-filtered {:data {:count (count lang-filtered)}})
          pages-flow (m/seed lang-filtered)
          filtered-flow (mk-filter-pages-f config pages-flow)]

      (m/?
       (m/reduce
        rfs/last
        (mk-store-documents-f config (mk-prepare-documents-f config filtered-flow)))))))

;; ============================================================================
;; Entry Points
;; ============================================================================

(defn -main [& args]
  (telemetry/start-job! (core/run-task-async (mk-materialize-t wview))))

(defn stop-job []
  (when (telemetry/stop-job!)
    (t/event! :episerver/job-cancelled)))

;; Re-export telemetry atoms for backward compatibility
(def !transient-telemetry-aggregate telemetry/!transient-telemetry-aggregate)
(def !signal-window telemetry/!signal-window)
(def !start-job-button-disabled? telemetry/!start-job-button-disabled?)
(def !job-canceller telemetry/!job-canceller)
(def !store-threads telemetry/!store-threads)

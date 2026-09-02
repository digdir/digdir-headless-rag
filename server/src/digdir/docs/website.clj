(ns digdir.docs.website
  "Website document source - imports markdown from sitemap URLs.

   This pipeline:
   1. Fetches and parses a sitemap XML
   2. Downloads markdown files from the URLs
   3. Chunks content by headers
   4. Generates search phrases via LLM
   5. Stores documents, chunks, and phrases in TypeSense"
  {:clj-kondo/ignore true}
  (:require [digdir.docs.pipeline.core :as core]
            [digdir.docs.pipeline.storage :as storage]
            [digdir.docs.pipeline.orchestration :as orch]
            [digdir.docs.pipeline.search-phrases :as search-phrases]
            [digdir.docs.pipeline.telemetry :as telemetry]
            [digdir.docs.pipeline.protocol :as proto]
            [clojure.string :as str]
            [clojure.xml :as xml]
            [clojure.java.io :as jio]
            [missionary.core :as m]
            [taoensso.telemere :as t]
            [hyperfiddle.rcf :refer [tests tap %]]))

;; ============================================================================
;; URL Helper Functions
;; ============================================================================

(defn make-relative-url
  "Converts an absolute URL to a relative URL by removing the base-url"
  [base-url absolute-url]
  (if (and base-url (str/starts-with? absolute-url base-url))
    (subs absolute-url (count base-url))
    absolute-url))

(defn make-absolute-url
  "Converts a relative URL to an absolute URL by prepending the base-url"
  [base-url relative-url]
  (if (and base-url (not (str/starts-with? relative-url "http")))
    (str base-url relative-url)
    relative-url))

(tests
 "URL conversion functions"
 (make-relative-url "http://localhost:1313" "http://localhost:1313/en/about/index.md")
 := "/en/about/index.md"

 (make-absolute-url "http://localhost:1313" "/en/about/index.md")
 := "http://localhost:1313/en/about/index.md"

 (make-absolute-url "http://localhost:1313" "http://example.com/test.md")
 := "http://example.com/test.md"

 (make-relative-url nil "http://localhost:1313/en/about/index.md")
 := "http://localhost:1313/en/about/index.md")

;; ============================================================================
;; Sitemap Parsing
;; ============================================================================

(defn fetch-sitemap
  "Fetches sitemap XML from a URL and parses it"
  [url]
  (t/event! :website/fetching-sitemap {:data {:url url}})
  (with-open [in (jio/input-stream url)]
    (xml/parse in)))

(defn extract-urls-from-sitemap
  "Extracts URL entries from parsed sitemap XML structure.
   Returns seq of maps with :loc and :lastmod keys."
  ([sitemap-xml] (extract-urls-from-sitemap sitemap-xml nil))
  ([sitemap-xml base-url]
   (let [urlset (if (= :urlset (:tag sitemap-xml))
                  sitemap-xml
                  (first (filter #(= :urlset (:tag %)) (:content sitemap-xml))))
         urls (filter #(= :url (:tag %)) (:content urlset))]
     (for [url urls]
       (let [children (:content url)
             loc-node (first (filter #(= :loc (:tag %)) children))
             lastmod-node (first (filter #(= :lastmod (:tag %)) children))
             loc (first (:content loc-node))]
         {:loc (if base-url (make-absolute-url base-url loc) loc)
          :lastmod (when lastmod-node (first (:content lastmod-node)))})))))

(defn filter-markdown-urls
  "Filters URL entries to only include .md files"
  [url-entries]
  (filter #(str/ends-with? (:loc %) ".md") url-entries))

(defn parse-sitemap
  "Fetches and parses sitemap, returning filtered markdown URLs."
  ([url] (parse-sitemap url nil))
  ([url base-url]
   (-> url
       fetch-sitemap
       (extract-urls-from-sitemap base-url)
       filter-markdown-urls)))

;; ============================================================================
;; Markdown Fetching
;; ============================================================================

(defn mk-cached-fetch-markdown-t
  "Fetches markdown from URL with caching based on lastmod timestamp"
  [url lastmod]
  (m/via m/blk
         (let [cache-key (str (core/sha256-short-hash url) "-" (core/sha256-short-hash (or lastmod "")))
               cache-dir "cache/website-md/"
               cache-path (str cache-dir cache-key ".md")
               file (jio/file cache-path)]

           (when-not (java.io.File/.exists (jio/file cache-dir))
             (jio/make-parents cache-path))

           (if (java.io.File/.exists file)
             (do
               (t/event! :website/markdown-cache-hit {:data {:url url}})
               (slurp file))
             (do
               (t/event! :website/markdown-cache-miss {:data {:url url :lastmod lastmod}})
               (try
                 (let [md (slurp url)]
                   (spit file md)
                   (t/event! :website/markdown-cached {:data {:url url}})
                   md)
                 (catch Exception e
                   (t/error! {:id :website/markdown-fetch-error
                              :msg ["Failed to fetch markdown from" url]} e)
                   (throw e))))))))

;; ============================================================================
;; Document Structure
;; ============================================================================

(defn extract-title-from-url
  "Extracts a title from the URL path."
  [url]
  (let [path (-> url
                 (str/replace #"^https?://[^/]+" "")
                 (str/replace #"/index\.md$" "")
                 (str/replace #"\.md$" "")
                 (str/split #"/")
                 last)]
    (-> path
        (str/replace #"-" " ")
        (str/replace #"_" " ")
        str/capitalize)))

(tests
 "Title extraction from URLs"
 (extract-title-from-url "http://localhost:1313/en/about/index.md")
 := "About"

 (extract-title-from-url "/en/about/index.md")
 := "About"

 (extract-title-from-url "/en/authorization/architecture.md")
 := "Architecture")

(defn url-to-doc
  "Converts a sitemap URL entry to document structure."
  [url-entry]
  (let [{:keys [loc lastmod]} url-entry
        doc-id (core/sha256-short-hash loc)]
    {:id doc-id
     :doc_num doc-id
     :url loc
     :lastmod lastmod
     :title (extract-title-from-url loc)
     :type "website"}))

;; ============================================================================
;; TypeSense Schemas
;; ============================================================================

(defn website-docs-schema [collection-name]
  {:name collection-name
   :fields [{:facet true :index true :name "id" :optional false :sort true :type "string"}
            {:facet true :index true :name "doc_num" :optional false :sort true :type "string"}
            {:facet false :index true :locale "en" :name "title" :optional false :sort false :type "string"}
            {:facet true :index true :name "url" :optional false :sort false :type "string"}
            {:facet true :index true :name "lastmod" :optional true :sort true :type "string"}
            {:facet true :index true :name "type" :optional false :sort false :type "string"}]})

(defn website-chunks-schema [[docs-collection-name chunks-collection-name :as coll-ids]]
  {:name chunks-collection-name
   :fields [{:facet true :index true :name "chunk_id" :optional false :sort true :type "string"}
            {:facet true :index true :name "doc_num" :optional false
             :reference (str docs-collection-name ".doc_num")
             :async_reference true
             :sort false :type "string"}
            {:facet true :index true :name "chunk_index" :optional false :sort true :type "int32"}
            {:facet false :index true :locale "en" :name "content_markdown" :optional false :sort false :type "string"}
            {:facet false :index true :name "metadata" :optional true :sort false :type "string"}
            {:facet true :index true :name "url" :optional false :sort false :type "string"}]})

(defn website-phrases-schema
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

(defn prepare-website-doc
  "Prepares document for storage, converting absolute URL to relative"
  [config doc]
  (let [base-url (:base-url config)]
    (-> doc
        (update :url #(make-relative-url base-url %))
        (select-keys [:id :doc_num :title :url :lastmod :type]))))

(defn prepare-website-chunks
  "Prepares chunks for storage"
  [config chunks]
  (let [base-url (:base-url config)]
    (mapv #(-> %
               (update :url (fn [url] (make-relative-url base-url url)))
               (select-keys [:chunk_id :doc_num :chunk_index :content_markdown :metadata :url]))
          chunks)))

;; ============================================================================
;; Backward Compatibility Functions
;; ============================================================================

;; These functions maintain backward compatibility with existing tests

(defn extract-ns-from-map
  "Extracts all keys from map m that have namespace ns or start with ns."
  [m ns]
  (storage/extract-ns-from-map m ns))

(defn wview-hash
  "Generates a hash of config values in a specific namespace."
  [config ns]
  (storage/config-hash config ns))

(defn coll-ids
  "Generates collection IDs for documents, chunks, and phrases based on config."
  [config]
  (storage/coll-ids config))

(defn document-inserted?
  "Checks if a document with the given ID exists in the collection."
  [_config coll-name doc]
  (storage/document-inserted? coll-name doc))

(defn create-website-docs-coll
  "Creates the documents collection."
  [name]
  (storage/create-collection! (website-docs-schema name)))

(defn create-website-chunks-coll
  "Creates the chunks collection."
  [config]
  (let [ids (coll-ids config)]
    (storage/create-collection! (website-chunks-schema ids))))

(defn create-website-phrases-coll
  "Creates the phrases collection."
  [config]
  (let [ids (coll-ids config)]
    (storage/create-collection! (website-phrases-schema ids))))

(defn create-stores
  "Creates all three collections."
  [config]
  (storage/create-collections!
   config
   website-docs-schema
   website-chunks-schema
   website-phrases-schema))

(defn mk-filter-url-entries-f
  "Filters URL entries with limit/offset and deduplication."
  [config url-entries]
  (orch/mk-filter-url-entries-f config url-entries))

;; ============================================================================
;; Configuration
;; ============================================================================

(def wview
  {:sitemap/url "/nb/sitemap-markdown.xml"
   :base-url "http://localhost:1313"

   :parallelism/documents 3
   :parallelism/store 1
   :fault-tolerance/max-document-failures 10

   :urls/offset 0
   :urls/limit 30000

   :chunks/hash-changer 1
   :chunks/strategy :header-based
   :chunks/minimum-length 333
   :chunks/maximum-length (* 2 128000)

   :search-phrases/hash-changer 1
   :search-phrases/model "gpt-4o"
   :search-phrases/fallback-model :google/gemma-3-27b-it
   :search-phrases/prompt search-phrases/default-search-phrases-prompt

   :store/coll-prefix "website_"})

;; ============================================================================
;; Pipeline Implementation
;; ============================================================================

(defn mk-fetch-markdown-t
  "Fetches markdown content for a URL entry"
  [config url-entry]
  (m/sp
   (let [{:keys [loc lastmod]} url-entry
         doc (url-to-doc url-entry)
         markdown (m/? (mk-cached-fetch-markdown-t loc lastmod))]
     (assoc doc :content_markdown markdown))))

(defn mk-chunk-doc-t
  "Chunks a document"
  [config doc]
  (m/via m/blk (proto/chunk-document config doc :url)))

(defn mk-prepare-document-t
  "Prepares a complete document from a URL entry"
  [config url-entry]
  (m/sp
   (as-> url-entry doc
     (m/? (mk-fetch-markdown-t config doc))
     (m/? (mk-chunk-doc-t config doc))
     (m/? (search-phrases/mk-distill-doc-search-phrases-t config doc "website"))
     (do (core/say "Prepared website doc")
         (t/event! :website/document-prepared {:data {:url (:url doc)}})
         doc))))

(defn mk-store-document-t
  "Stores a prepared document"
  [config doc]
  (m/via m/blk
         (storage/store-complete-document!
          config doc
          prepare-website-doc
          (fn [chunks] (prepare-website-chunks config chunks)))))

(defn mk-prepare-documents-f
  "Prepares documents in parallel"
  [config url-entries-f]
  (orch/mk-prepare-documents-f config mk-prepare-document-t url-entries-f :website))

(defn mk-store-documents-f
  "Stores documents in parallel"
  [config documents-f]
  (orch/mk-store-documents-f config mk-store-document-t documents-f))

(defn mk-materialize-t
  "Creates the complete materialization task"
  [config]
  (m/sp
    (t/event! :website/materializing {:data {:config config :colls (coll-ids config)}})

    (m/? (m/via m/blk (create-stores config)))

    (let [base-url (:base-url config)
          sitemap-url (make-absolute-url base-url (:sitemap/url config))
          _ (t/event! :website/parsing-sitemap {:data {:url sitemap-url}})
          url-entries (m/? (m/via m/blk (parse-sitemap sitemap-url base-url)))
          _ (t/event! :website/found-urls {:data {:count (count url-entries)}})
          url-entries-flow (m/seed url-entries)
          filtered-flow (mk-filter-url-entries-f config url-entries-flow)]

      (m/?
       (m/reduce
        net.cgrand.xforms.rfs/last
        (mk-store-documents-f config (mk-prepare-documents-f config filtered-flow)))))))

;; ============================================================================
;; Entry Points
;; ============================================================================

(defn -main [& args]
  (telemetry/start-job! (core/run-task-async (mk-materialize-t wview))))

(defn stop-job []
  (when (telemetry/stop-job!)
    (t/event! :website/job-cancelled)))

;; Re-export telemetry atoms for backward compatibility
(def !transient-telemetry-aggregate telemetry/!transient-telemetry-aggregate)
(def !signal-window telemetry/!signal-window)
(def !start-job-button-disabled? telemetry/!start-job-button-disabled?)
(def !job-canceller telemetry/!job-canceller)
(def !store-threads telemetry/!store-threads)

(ns digdir.docs.folder
  "Folder document source - imports markdown from local directories.

   This pipeline:
   1. Recursively scans a directory for .md files
   2. Reads markdown file contents
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
            [clojure.java.io :as jio]
            [missionary.core :as m]
            [tick.core :as tick]
            [taoensso.telemere :as t]
            [hyperfiddle.rcf :refer [tests tap %]]))

;; ============================================================================
;; Path Helper Functions
;; ============================================================================

(defn normalize-path
  "Normalizes a file path, expanding ~ to home directory"
  [path]
  (if (str/starts-with? path "~")
    (str/replace-first path "~" (System/getProperty "user.home"))
    path))

(defn make-relative-path
  "Converts an absolute file path to a relative path by removing the base-path"
  [base-path absolute-path]
  (if (and base-path (str/starts-with? absolute-path base-path))
    (subs absolute-path (count base-path))
    absolute-path))

(defn make-absolute-path
  "Converts a relative path to an absolute path by prepending the base-path"
  [base-path relative-path]
  (if (and base-path (not (str/starts-with? relative-path "/")))
    (str base-path relative-path)
    relative-path))

(defn make-url
  "Generates a URL by concatenating base-url with relative-path and stripping .md extension"
  [base-url relative-path]
  (when (and base-url relative-path)
    (let [path-without-md (str/replace relative-path #"\.md$" "")
          clean-path (if (str/starts-with? path-without-md "/")
                       (subs path-without-md 1)
                       path-without-md)]
      (str base-url clean-path))))

(tests
 "Path conversion functions"
 (make-relative-path "/home/user/docs/" "/home/user/docs/en/about.md")
 := "en/about.md"

 (make-absolute-path "/home/user/docs/" "en/about.md")
 := "/home/user/docs/en/about.md"

 (make-relative-path nil "/home/user/docs/en/about.md")
 := "/home/user/docs/en/about.md"

 "URL generation"
 (make-url "https://docs.digdir.no/docs/" "/en/about.md")
 := "https://docs.digdir.no/docs/en/about"

 (make-url "https://docs.digdir.no/docs/" "en/about.md")
 := "https://docs.digdir.no/docs/en/about")

;; ============================================================================
;; Folder Crawling
;; ============================================================================

(defn find-markdown-files
  "Recursively finds all .md files in a directory.
   Returns seq of maps with :path and :lastmod keys."
  [root-dir]
  (let [root-file (jio/file root-dir)]
    (when (.exists root-file)
      (t/event! :folder/scanning-directory {:data {:path root-dir}})
      (let [files (file-seq root-file)
            md-files (filter #(and (.isFile %)
                                   (str/ends-with? (.getName %) ".md"))
                             files)]
        (map (fn [file]
               {:path (.getAbsolutePath file)
                :lastmod (tick/instant (.lastModified file))})
             md-files)))))

(tests
 "Folder crawling"
 (fn? find-markdown-files) := true)

;; ============================================================================
;; Markdown Reading
;; ============================================================================

(defn mk-cached-read-markdown-t
  "Reads markdown from file with caching based on lastmod timestamp"
  [file-path lastmod]
  (m/via m/blk
         (let [cache-key (str (core/sha256-short-hash file-path) "-" (core/sha256-short-hash (str lastmod)))
               cache-dir "cache/folder-md/"
               cache-path (str cache-dir cache-key ".md")
               cache-file (jio/file cache-path)]

           (when-not (java.io.File/.exists (jio/file cache-dir))
             (jio/make-parents cache-path))

           (if (java.io.File/.exists cache-file)
             (do
               (t/event! :folder/markdown-cache-hit {:data {:path file-path}})
               (slurp cache-file))
             (do
               (t/event! :folder/markdown-cache-miss {:data {:path file-path :lastmod lastmod}})
               (try
                 (let [md (slurp file-path)]
                   (spit cache-file md)
                   (t/event! :folder/markdown-cached {:data {:path file-path}})
                   md)
                 (catch Exception e
                   (t/error! {:id :folder/markdown-read-error
                              :msg ["Failed to read markdown from" file-path]} e)
                   (throw e))))))))

;; ============================================================================
;; Document Structure
;; ============================================================================

(defn extract-title-from-path
  "Extracts a title from the file path.
   Uses the filename without extension and converts to title case."
  [file-path]
  (let [filename (-> file-path
                     (str/split #"/")
                     last
                     (str/replace #"\.md$" ""))]
    (-> filename
        (str/replace #"-" " ")
        (str/replace #"_" " ")
        str/capitalize)))

(tests
 "Title extraction from file paths"
 (extract-title-from-path "/home/user/docs/en/about/index.md")
 := "Index"

 (extract-title-from-path "/home/user/docs/en/authorization/architecture.md")
 := "Architecture")

(defn path-to-doc
  "Converts a file entry to document structure."
  [file-entry]
  (let [{:keys [path lastmod]} file-entry
        doc-id (core/sha256-short-hash path)]
    {:id doc-id
     :doc_num doc-id
     :path path
     :lastmod (str lastmod)
     :title (extract-title-from-path path)
     :type "folder"}))

;; ============================================================================
;; TypeSense Schemas
;; ============================================================================

(defn folder-docs-schema [collection-name]
  {:name collection-name
   :fields [{:facet true :index true :name "id" :optional false :sort true :type "string"}
            {:facet true :index true :name "doc_num" :optional false :sort true :type "string"}
            {:facet false :index true :locale "en" :name "title" :optional false :sort false :type "string"}
            {:facet true :index true :name "path" :optional false :sort false :type "string"}
            {:facet true :index true :name "url" :optional true :sort false :type "string"}
            {:facet true :index true :name "lastmod" :optional true :sort true :type "string"}
            {:facet true :index true :name "type" :optional false :sort false :type "string"}]})

(defn folder-chunks-schema [[docs-collection-name chunks-collection-name :as coll-ids]]
  {:name chunks-collection-name
   :fields [{:facet true :index true :name "chunk_id" :optional false :sort true :type "string"}
            {:facet true :index true :name "doc_num" :optional false
             :reference (str docs-collection-name ".doc_num")
             :async_reference true
             :sort false :type "string"}
            {:facet true :index true :name "chunk_index" :optional false :sort true :type "int32"}
            {:facet false :index true :locale "en" :name "content_markdown" :optional false :sort false :type "string"}
            {:facet false :index true :name "metadata" :optional true :sort false :type "string"}
            {:facet true :index true :name "path" :optional false :sort false :type "string"}]})

(defn folder-phrases-schema
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

(defn prepare-folder-doc
  "Prepares document for storage, converting absolute path to relative and generating URL"
  [config doc]
  (let [base-path (:base-path config)
        base-url (:base-url config)
        relative-path (make-relative-path base-path (:path doc))
        url (make-url base-url relative-path)]
    (-> doc
        (assoc :path relative-path)
        (assoc :url url)
        (select-keys [:id :doc_num :title :path :url :lastmod :type]))))

(defn prepare-folder-chunks
  "Prepares chunks for storage"
  [config chunks]
  (let [base-path (:base-path config)]
    (mapv #(-> %
               (update :path (fn [path] (make-relative-path base-path path)))
               (select-keys [:chunk_id :doc_num :chunk_index :content_markdown :metadata :path]))
          chunks)))

;; ============================================================================
;; Backward Compatibility Functions
;; ============================================================================

(defn coll-ids
  "Generates collection IDs for documents, chunks, and phrases based on config."
  [config]
  (storage/coll-ids config))

(defn document-inserted?
  "Checks if a document with the given ID exists in the collection."
  [_config coll-name doc]
  (storage/document-inserted? coll-name doc))

(defn create-stores
  "Creates all three collections."
  [config]
  (storage/create-collections!
   config
   folder-docs-schema
   folder-chunks-schema
   folder-phrases-schema))

(defn mk-filter-file-entries-f
  "Filters file entries with limit/offset and deduplication."
  [config file-entries]
  (orch/mk-filter-file-entries-f config file-entries))

;; ============================================================================
;; Configuration
;; ============================================================================

(def wview
  {:folder/path "~/dev/digdir/docs-digdir-no/_export/markdown/"
   :base-path (normalize-path "~/dev/digdir/docs-digdir-no/_export/markdown/")
   :base-url "https://docs.digdir.no/docs/"

   :parallelism/documents 3
   :parallelism/store 1
   :fault-tolerance/max-document-failures 10

   :files/offset 0
   :files/limit 300000

   :chunks/hash-changer 1
   :chunks/strategy :header-based
   :chunks/minimum-length 333
   :chunks/maximum-length (* 2 128000)

   :search-phrases/hash-changer 1
   :search-phrases/model "gpt-4o"
   :search-phrases/fallback-model :google/gemma-3-27b-it
   :search-phrases/prompt search-phrases/default-search-phrases-prompt

   :store/coll-prefix "folder_"})

;; ============================================================================
;; Pipeline Implementation
;; ============================================================================

(defn mk-read-markdown-t
  "Reads markdown content for a file entry"
  [config file-entry]
  (m/sp
   (let [{:keys [path lastmod]} file-entry
         doc (path-to-doc file-entry)
         markdown (m/? (mk-cached-read-markdown-t path lastmod))]
     (assoc doc :content_markdown markdown))))

(defn mk-chunk-doc-t
  "Chunks a document"
  [config doc]
  (m/via m/blk (proto/chunk-document config doc :path)))

(defn mk-prepare-document-t
  "Prepares a complete document from a file entry"
  [config file-entry]
  (m/sp
   (as-> file-entry doc
     (m/? (mk-read-markdown-t config doc))
     (m/? (mk-chunk-doc-t config doc))
     (m/? (search-phrases/mk-distill-doc-search-phrases-t config doc "folder"))
     (do (core/say "Prepared folder doc")
         (t/event! :folder/document-prepared {:data {:path (:path doc)}})
         doc))))

(defn mk-store-document-t
  "Stores a prepared document"
  [config doc]
  (m/via m/blk
         (storage/store-complete-document!
          config doc
          prepare-folder-doc
          (fn [chunks] (prepare-folder-chunks config chunks)))))

(defn mk-prepare-documents-f
  "Prepares documents in parallel"
  [config file-entries-f]
  (orch/mk-prepare-documents-f config mk-prepare-document-t file-entries-f :folder))

(defn mk-store-documents-f
  "Stores documents in parallel"
  [config documents-f]
  (orch/mk-store-documents-f config mk-store-document-t documents-f))

(defn mk-materialize-t
  "Creates the complete materialization task"
  [config]
  (m/sp
    (t/event! :folder/materializing {:data {:config config :colls (coll-ids config)}})

    (m/? (m/via m/blk (create-stores config)))

    (let [folder-path (normalize-path (:folder/path config))
          _ (t/event! :folder/scanning-folder {:data {:path folder-path}})
          file-entries (m/? (m/via m/blk (find-markdown-files folder-path)))
          _ (t/event! :folder/found-files {:data {:count (count file-entries)}})
          file-entries-flow (m/seed file-entries)
          filtered-flow (mk-filter-file-entries-f config file-entries-flow)]

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
    (t/event! :folder/job-cancelled)))

;; Re-export telemetry atoms for backward compatibility
(def !transient-telemetry-aggregate telemetry/!transient-telemetry-aggregate)
(def !signal-window telemetry/!signal-window)
(def !start-job-button-disabled? telemetry/!start-job-button-disabled?)
(def !job-canceller telemetry/!job-canceller)
(def !store-threads telemetry/!store-threads)

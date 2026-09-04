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

(defn resolved-corpus-path
  "The configured path as an operator can act on it: absolute AND normalized.

   `.getAbsolutePath` alone renders `./demo-corpus` as `/app/./demo-corpus`,
   which is correct and still hard to read in an error message — and this
   message exists to be read. Normalizing collapses the `.` segment."
  [path]
  (-> (jio/file path) .toPath .toAbsolutePath .normalize .toString))

(defn find-markdown-files
  "Recursively finds all .md files in a directory.
   Returns seq of maps with :path and :lastmod keys.

   ⚠️ THROWS when the directory is absent, rather than returning nil (#556).
   The nil was read downstream as \"no documents\", so a materialization against
   a path that does not exist COMPLETED with status `completed`, `errorMessage`
   null and 0 documents processed — a failure shaped exactly like an empty
   corpus. Nothing was wrong from the pipeline's point of view: it was handed
   nothing and faithfully processed nothing.

   The message names the RESOLVED ABSOLUTE path. That is the whole point: the
   shipped demo config says `./demo-corpus`, which resolves against the JVM's
   cwd — `/app` inside the container — and a relative path in an error message
   is what let this hide."
  [root-dir]
  (let [root-file (jio/file root-dir)
        absolute (resolved-corpus-path root-dir)]
    (when-not (.exists root-file)
      (throw (ex-info (str "Corpus directory does not exist: " absolute)
                      {:type :folder/corpus-directory-missing
                       :configured-path root-dir
                       :resolved-path absolute
                       :cwd (System/getProperty "user.dir")})))
    (when-not (.isDirectory root-file)
      (throw (ex-info (str "Corpus path is not a directory: " absolute)
                      {:type :folder/corpus-path-not-a-directory
                       :configured-path root-dir
                       :resolved-path absolute})))
    (do
      (t/event! :folder/scanning-directory {:data {:path root-dir}})
      (let [files (file-seq root-file)
            md-files (filter #(and (.isFile %)
                                   (str/ends-with? (.getName %) ".md"))
                             files)]
        (map (fn [file]
               {:path (.getAbsolutePath file)
                :lastmod (tick/instant (.lastModified file))})
             md-files)))))

(defn ensure-documents-found!
  "Throw when a scan of an EXISTING directory yielded nothing (#556).

   Deliberately separate from the missing-directory refusal in
   `find-markdown-files`, because the two remedies do not overlap: that one
   means a wrong path or an absent mount, this one means the corpus was never
   fetched or its files are not `.md`. Collapsing both into a single
   \"no documents\" is the conflation that made the original bug invisible —
   the same shape as a nil that means both \"lookup failed\" and \"no such
   value\"."
  [file-entries folder-path]
  (when (empty? file-entries)
    (let [absolute (resolved-corpus-path folder-path)]
      (throw (ex-info (str "Corpus directory contains no .md files: " absolute)
                      {:type :folder/corpus-directory-empty
                       :configured-path folder-path
                       :resolved-path absolute}))))
  file-entries)

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
            {:facet true :index true :name "type" :optional false :sort false :type "string"}
            {:facet false :index true :name "total_chunks" :optional true :sort true :type "int32"}]})

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
            {:facet false :index true :name "content_length" :optional true :sort true :type "int32"}
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
        (select-keys [:id :doc_num :title :path :url :lastmod :type :total_chunks]))))

(defn prepare-folder-chunks
  "Prepares chunks for storage"
  [config chunks]
  (let [base-path (:base-path config)]
    (mapv #(-> %
               (update :path (fn [path] (make-relative-path base-path path)))
               (select-keys [:chunk_id :doc_num :chunk_index :content_markdown :content_length :metadata :path]))
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
  ;; `config` was `_config`: the tenant was threaded to this boundary and
  ;; discarded here, which is how the storage layer ended up resolving
  ;; Typesense with no tenant at all (#476).
  [config coll-name doc]
  (storage/document-inserted? config coll-name doc))

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

(def default-folder-path
  "Where `wview` looks for markdown when nothing overrides it.

   ⚠️ THIS IS A DEV-CONVENIENCE DEFAULT, NOT THE PRODUCTION PATH. A real
   materialization takes `:folder/path` from dataset config — see
   `:folder-path` in `digdir.pipeline.materialization`'s source key map — so
   this value is only reached by `-main` and by the admin UI's import panel.

   It used to be `~/dev/digdir/docs-digdir-no/_export/markdown/` — one
   developer's home directory, in shipped code (#490). Its own siblings
   establish the convention it broke: `episerver.clj` defaults to a relative
   `cache/episerver-data/epix.xml` and `website.clj` to `http://localhost:1313`.
   Two of three were portable.

   `DOCS_FOLDER_PATH` is read so the workflow that path was serving still works
   — the developer who needs their own export points the variable at it instead
   of editing shipped code. Read at load time, which is adequate for a dev
   default; `digdir.setup.demo-dataset/corpus-directory` reads at call time
   because a deployment can move it."
  (or (System/getenv "DOCS_FOLDER_PATH") "cache/docs-export/markdown/"))

(def wview
  {:folder/path default-folder-path
   :base-path (normalize-path default-folder-path)
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
          _ (ensure-documents-found! file-entries folder-path)
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

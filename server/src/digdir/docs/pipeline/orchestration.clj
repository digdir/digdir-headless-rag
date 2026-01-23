(ns digdir.docs.pipeline.orchestration
  "Pipeline orchestration using Missionary for document processing.

   This namespace provides the core flow patterns used by all document
   pipelines:
   - Parallel document preparation with fault tolerance
   - Parallel document storage with rate limiting
   - Complete pipeline materialization"
  (:require [missionary.core :as m]
            [net.cgrand.xforms.rfs :as rfs]
            [medley.core :as y]
            [taoensso.telemere :as t]
            [digdir.docs.pipeline.core :as core]
            [digdir.docs.pipeline.telemetry :as telemetry]))

;; ============================================================================
;; Document Preparation Flow
;; ============================================================================

(defn mk-prepare-documents-f
  "Creates a Missionary flow that prepares documents in parallel.

   Parameters:
   - config: must include :parallelism/documents and :fault-tolerance/max-document-failures
   - prepare-doc-t: (fn [config entry] -> Missionary task returning prepared doc)
   - entries-f: Missionary flow of raw entries (url-entries, file-entries, etc.)
   - pipeline-name: keyword for telemetry (e.g., :website-loading, :folder-loading)

   Features:
   - Processes up to :parallelism/documents entries concurrently
   - Tracks failures and stops at max-document-failures
   - Emits m/amb (empty) for recoverable failures to continue processing"
  [config prepare-doc-t entries-f pipeline-name]
  (m/ap
    (let [prepare-failures (atom 0)
          entry (m/?> (:parallelism/documents config) entries-f)]
      (try
        (t/event! (keyword (name pipeline-name) "handling-entry")
                  {:data {:entry (select-keys entry [:loc :path :id])}})
        (m/? (prepare-doc-t config entry))
        (catch Exception e
          (let [failures (swap! prepare-failures inc)
                terminal? (= failures (:fault-tolerance/max-document-failures config))]
            (if terminal?
              (core/say (str "FATAL: " failures " documents failed. Shutting down"))
              (core/say (str "WARNING: " failures " documents failed. Skipping")))

            (t/error! {:id (keyword (name pipeline-name)
                                    (if terminal? "terminal-failure" "recoverable-failure"))
                       :data {:failures failures
                              :entry (select-keys entry [:loc :path :id])}}
                      e)
            (when terminal? (throw e))
            (m/amb)))))))

;; ============================================================================
;; Document Storage Flow
;; ============================================================================

(defn mk-store-documents-f
  "Creates a Missionary flow that stores documents in parallel.

   Parameters:
   - config: must include :parallelism/store
   - store-doc-t: (fn [config doc] -> Missionary task)
   - documents-f: Missionary flow of prepared documents

   Features:
   - Processes up to :parallelism/store documents concurrently
   - Tracks active store threads via telemetry/!store-threads"
  [config store-doc-t documents-f]
  (m/ap
    (let [doc (m/?> (:parallelism/store config) documents-f)
          _ (swap! telemetry/!store-threads inc)
          doc (m/? (store-doc-t config doc))
          _ (swap! telemetry/!store-threads dec)]
      doc)))

;; ============================================================================
;; Entry Filtering Flows
;; ============================================================================

(defn mk-filter-entries-f
  "Creates a Missionary flow that filters entries with limit/offset.

   Parameters:
   - config: contains limit/offset keys (namespace determined by key-ns)
   - entries-f: Missionary flow of entries
   - key-ns: keyword namespace for limit/offset (e.g., 'urls' or 'files')
   - distinct-key: key to use for deduplication (e.g., :loc or :path)

   Returns flow with:
   - Duplicates removed (by distinct-key)
   - First 'offset' entries dropped
   - At most 'limit' entries taken"
  [config entries-f key-ns distinct-key]
  (let [limit-key (keyword key-ns "limit")
        offset-key (keyword key-ns "offset")
        limit (get config limit-key 1000)
        offset (get config offset-key 0)]
    (t/log! ["Processing entries with offset=" offset "limit=" limit])
    (m/eduction (y/distinct-by distinct-key)
                (drop offset)
                (take limit)
                entries-f)))

(defn mk-filter-url-entries-f
  "Convenience wrapper for filtering URL entries (website sources).
   Uses :urls/limit and :urls/offset, dedupes by :loc"
  [config entries-f]
  (mk-filter-entries-f config entries-f "urls" :loc))

(defn mk-filter-file-entries-f
  "Convenience wrapper for filtering file entries (folder sources).
   Uses :files/limit and :files/offset, dedupes by :path"
  [config entries-f]
  (mk-filter-entries-f config entries-f "files" :path))

;; ============================================================================
;; Complete Pipeline Execution
;; ============================================================================

(defn mk-materialize-t
  "Creates a complete materialization task for a document pipeline.

   Parameters:
   - config: pipeline configuration
   - source-entries-t: Missionary task returning seq of source entries
   - filter-entries-fn: (fn [config entries-f] -> filtered-entries-f)
   - prepare-doc-t: (fn [config entry] -> task returning prepared doc)
   - store-doc-t: (fn [config doc] -> task)
   - pipeline-name: keyword for telemetry

   Returns a Missionary task that:
   1. Fetches source entries
   2. Filters and dedupes
   3. Prepares documents (parallel)
   4. Stores documents (parallel)
   5. Returns the last stored document"
  [config source-entries-t filter-entries-fn prepare-doc-t store-doc-t pipeline-name]
  (m/sp
    (t/event! (keyword (name pipeline-name) "starting")
              {:data {:config (select-keys config [:parallelism/documents
                                                   :parallelism/store
                                                   :fault-tolerance/max-document-failures])}})

    (let [entries (m/? source-entries-t)
          _ (t/event! (keyword (name pipeline-name) "source-entries-fetched")
                      {:data {:count (count entries)}})
          entries-flow (m/seed entries)
          filtered-flow (filter-entries-fn config entries-flow)
          prepared-flow (mk-prepare-documents-f config prepare-doc-t filtered-flow pipeline-name)
          stored-flow (mk-store-documents-f config store-doc-t prepared-flow)]

      (m/? (m/reduce rfs/last stored-flow)))))

;; ============================================================================
;; Job Management
;; ============================================================================

(defn run-pipeline!
  "Runs a pipeline task asynchronously with job management.

   Parameters:
   - pipeline-t: Missionary task to run
   - pipeline-name: keyword for telemetry

   Sets up proper cancel handling and button state."
  [pipeline-t pipeline-name]
  (t/event! (keyword (name pipeline-name) "job-starting"))
  (telemetry/start-job! (core/run-task-async pipeline-t)))

(defn stop-pipeline!
  "Stops the currently running pipeline."
  [pipeline-name]
  (when (telemetry/stop-job!)
    (t/event! (keyword (name pipeline-name) "job-cancelled"))))

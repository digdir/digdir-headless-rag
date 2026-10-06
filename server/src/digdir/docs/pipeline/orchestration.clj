(ns digdir.docs.pipeline.orchestration
  "Pipeline orchestration using Missionary for document processing.

   This namespace provides the core flow patterns used by all document
   pipelines:
   - Parallel document preparation with fault tolerance
   - Parallel document storage with rate limiting
   - Complete pipeline materialization"
  (:require [clojure.string :as str]
            [missionary.core :as m]
            [net.cgrand.xforms.rfs :as rfs]
            [medley.core :as y]
            [taoensso.telemere :as t]
            [digdir.docs.pipeline.core :as core]
            [digdir.docs.pipeline.telemetry :as telemetry]))

;; ============================================================================
;; A run's failures: ONE record, shared by its prepare and store steps
;; ============================================================================
;;
;; A document that fails to PREPARE (fetch, parse, LLM) and a document whose
;; rows Typesense REFUSED are the same event for a run: this document could not
;; be ingested. Both count against the one `:fault-tolerance/max-document-failures`
;; budget, in this record, and the run fails when it is reached. The record also
;; says what failed, so the run can report it: a failure is never silent.

(defn failure-record
  "A fresh record of one run's documents and failures."
  []
  (atom {:seen 0 :stored 0 :failed 0 :prepare-failed 0 :refused []}))

(defn with-failure-record
  "`config` carrying the run's failure record under `:fault-tolerance/failures`,
   adding a fresh one unless the caller (the executor) handed one in. Call it
   ONCE per run, where both flows are built, so they share it."
  [config]
  (if (:fault-tolerance/failures config)
    config
    (assoc config :fault-tolerance/failures (failure-record))))

(defn record-of
  "The run's failure record in `config` (a fresh one when none was handed in)."
  [config]
  (or (:fault-tolerance/failures config) (failure-record)))

(defn count-failure!
  "Count one failed document in the run's record; throw when the run's budget is
   reached. `detail` is merged into the record (a refusal adds itself to
   `:refused`)."
  [config record detail cause]
  (let [max-failures (:fault-tolerance/max-document-failures config)
        {:keys [failed]} (swap! record (fn [r]
                                         (cond-> (update r :failed inc)
                                           (= :prepare (:kind detail)) (update :prepare-failed inc)
                                           (= :rows-refused (:kind detail)) (update :refused conj (dissoc detail :kind)))))]
    (when (and max-failures (>= failed max-failures))
      (throw (ex-info (str "the document-failure budget (" max-failures ") was reached")
                      {:type :digdir.pipeline/failure-budget-reached
                       :max-document-failures max-failures
                       :failed failed}
                      cause)))
    failed))

(def ^:private max-summary-collections 3)
(def ^:private max-summary-ids 5)
(def ^:private max-summary-length 1000)

(defn failure-summary
  "A run's failures in one line a person can act on, from its record, or nil when
   nothing failed: how many of how many documents failed, the refusals by
   collection (ids and the first error), and the prepare failures. No document
   bodies. At most `max-summary-length` characters."
  [{:keys [seen failed prepare-failed refused]}]
  (when (pos? (or failed 0))
    (let [by-coll (->> refused (group-by :collection) (sort-by key) (take max-summary-collections))
          refusals (when (seq refused)
                     (str (count refused) " refused by Typesense ("
                          (str/join "; " (for [[coll rs] by-coll
                                               :let [ids (take max-summary-ids (remove nil? (mapcat :ids rs)))]]
                                           (str coll ": " (if (seq ids)
                                                            (str "ids " (str/join ", " ids))
                                                            (str (reduce + (map :refused rs)) " rows without ids"))
                                                "; first error: " (:first-error (first rs)))))
                          ")"))
          prepares (when (pos? prepare-failed) (str prepare-failed " failed to prepare"))
          s (str failed " of " seen " documents failed: " (str/join ", " (remove nil? [refusals prepares])))]
      (if (> (count s) max-summary-length) (str (subs s 0 (- max-summary-length 3)) "...") s))))

(defn check-stored!
  "Fail a run that saw at least one source entry and stored no document: it
   ingested nothing, whatever its tolerance allowed one document at a time. The
   count is the run's own (documents whose store returned), not telemetry. An
   EMPTY source (nothing seen) completes."
  [config]
  (let [{:keys [seen stored] :as r} @(record-of config)]
    (when (and (pos? seen) (zero? stored))
      (throw (ex-info (str "0 of " seen " documents stored"
                           (when-let [summary (failure-summary r)] (str ": " summary)))
                      {:type :digdir.storage/nothing-stored
                       :seen seen})))))

(defn tolerate-refusal
  "The store step's catch: a document whose rows Typesense refused
   (`:digdir.storage/rows-refused`, exactly that and nothing broader) is a
   document failure. It is counted against the run's budget, named in one
   `:pipeline/document-refused` event, and dropped. Its OLD revision stays:
   storage threw before that document's orphan delete. Any other exception is
   rethrown, and fails the run."
  [config record doc e]
  (let [d (ex-data e)]
    (if (= :digdir.storage/rows-refused (:type d))
      (let [detail {:kind :rows-refused
                    :document-id (:id doc)
                    :collection (:collection d)
                    :refused (:refused d)
                    :sent (:sent d)
                    :ids (vec (take max-summary-ids (map :id (:refused-sample d))))
                    :first-error (:error (first (:refused-sample d)))}]
        (t/event! :pipeline/document-refused {:level :warn :data (dissoc detail :kind)})
        (count-failure! config record detail e))
      (throw e))))

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
    (let [record (record-of config)
          entry (m/?> (:parallelism/documents config) entries-f)]
      (swap! record update :seen inc)
      (try
        (t/event! (keyword (name pipeline-name) "handling-entry")
                  {:data {:entry (select-keys entry [:loc :path :id])}})
        (m/? (prepare-doc-t config entry))
        (catch Exception e
          (let [max-failures (:fault-tolerance/max-document-failures config)
                failures (inc (:failed @record))
                terminal? (and max-failures (>= failures max-failures))]
            (if terminal?
              (core/say (str "FATAL: " failures " documents failed. Shutting down"))
              (core/say (str "WARNING: " failures " documents failed. Skipping")))

            (t/error! {:id (keyword (name pipeline-name)
                                    (if terminal? "terminal-failure" "recoverable-failure"))
                       :data {:failures failures
                              :entry (select-keys entry [:loc :path :id])}}
                      e)
            (count-failure! config record {:kind :prepare} e)
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
    (let [record (record-of config)
          doc (m/?> (:parallelism/store config) documents-f)]
      (swap! telemetry/!store-threads inc)
      ;; A store that throws must not leave the gauge counting it.
      (try
        (let [stored (m/? (store-doc-t config doc))]
          (swap! record update :stored inc)
          stored)
        (catch clojure.lang.ExceptionInfo e
          (tolerate-refusal config record doc e)
          (m/amb))
        (finally (swap! telemetry/!store-threads dec))))))

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
  (let [config (with-failure-record config)]
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
          stored-flow (mk-store-documents-f config store-doc-t prepared-flow)
          last-stored (m/? (m/reduce rfs/last stored-flow))]
      (check-stored! config)
      last-stored))))

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

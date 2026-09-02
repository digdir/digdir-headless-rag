(ns digdir.pipeline.executor
  "Pipeline execution orchestration.

   Executes pipelines by loading config, dispatching to appropriate loader,
   and tracking execution status."
  (:require [datahike.api :as d]
            [missionary.core :as m]
            [taoensso.telemere :as t]
            [digdir.pipeline.core :as pipeline]
            [digdir.pipeline.collections :as collections]
            [digdir.pipeline.materialization :as materialization]
            [nano-id.core :refer [nano-id]]))

;; =============================================================================
;; In-Memory Progress Tracking
;; =============================================================================
;;
;; While an execution is running we keep a per-execution progress map in a
;; global atom keyed by execution-id. A Telemere handler (registered with a
;; per-execution handler-id) inspects signals as they fire and updates this
;; atom. Key milestones are periodically flushed to the DB so:
;;
;;  - The UI can read live progress directly from this atom via e/server
;;  - Refreshing the page after a restart still shows the last-flushed counts
;;  - Historical executions (no longer in this atom) still have meaningful
;;    documents-processed / documents-failed / documents-total numbers in DB

(defonce !executions-progress
  ;; {execution-id {:total-urls N :prepared N :stored N :failures N
  ;;                :already-exists N :cache-hits N :cache-misses N
  ;;                :phrase-cache-hits N :phrase-cache-misses N
  ;;                :phrases-reused N :phrases-generated N
  ;;                :last-event-id keyword :last-event-at #inst}}
  ;;
  ;; Two cache layers tracked:
  ;; - :cache-hits / :cache-misses        — markdown fetch cache (per document)
  ;; - :phrase-cache-hits / -misses        — search-phrases cache (per chunk)
  ;; - :phrases-reused                     — total phrase strings replayed from cache
  ;; - :phrases-generated                  — total phrase strings produced by LLM
  (atom {}))

(defonce !execution-events-version
  ;; A monotonic counter incremented on execution lifecycle transitions
  ;; (start, completion, failure, cancellation). UI components that need
  ;; to reactively re-fetch on these transitions can `e/watch` this
  ;; integer instead of watching the whole DB conn — far cheaper, since
  ;; this only bumps a handful of times per run while a conn watch would
  ;; re-fire on every progress flush.
  (atom 0))

(defonce !executions-futures
  ;; {execution-id <java.util.concurrent.Future>}
  ;;
  ;; The Java future running each in-flight pipeline. Populated by
  ;; execute-pipeline-async! and cleared in its finally block. Used by
  ;; cancel-execution! to actually interrupt the running thread — the
  ;; underlying Missionary task honors thread interrupts cooperatively,
  ;; so future-cancel here unwinds the in-progress work instead of
  ;; just flipping the DB status and letting the run continue.
  (atom {}))

(defn- bump-lifecycle-version!
  "Increment the execution-lifecycle version counter. Called from the
   executor at phase transitions; the UI reacts to the change."
  []
  (swap! !execution-events-version inc))

(def ^:private empty-progress
  {:total-urls nil
   :prepared 0
   :stored 0
   :failures 0
   :already-exists 0
   :cache-hits 0
   :cache-misses 0
   :phrase-cache-hits 0
   :phrase-cache-misses 0
   :phrases-reused 0
   :phrases-generated 0
   :content-filtered 0
   ;; Ring buffer of recent error/rejection events with enough structured
   ;; detail (chunk_id, URL, category, severity, model, message) for an
   ;; operator to track down the offending source content without grepping
   ;; the server logs. Capped — see `max-errors` below.
   :errors []
   :last-event-id nil
   :last-event-at nil})

(def ^:private max-errors
  "How many recent error events to retain per execution. The UI shows them
   in a collapsible section; older entries get dropped FIFO."
  50)

(defn- now-inst [] (java.util.Date.))

(defn- coerce-string
  "Turn arbitrary signal values (keywords, numbers, nested data) into a short
   string suitable for display."
  [v]
  (cond
    (nil? v) nil
    (string? v) v
    (keyword? v) (str (symbol v))
    :else (pr-str v)))

(defn- record-error
  "Append an error event to the per-execution :errors ring buffer."
  [progress event]
  (let [errors (or (:errors progress) [])
        trimmed (if (>= (count errors) max-errors)
                  (subvec errors (- (count errors) (dec max-errors)))
                  errors)]
    (assoc progress :errors (conj trimmed (assoc event :at (now-inst))))))

(defn- update-progress-from-signal
  "Pure reducer: given the current progress map for one execution and a Telemere
   signal, return the updated map. Source-type-agnostic — we look at signal
   `:id` keywords that the various loaders fire. New loaders just need to fire
   the shared `:pipeline/*` events to be picked up here."
  [progress signal]
  (let [sig-id (:id signal)
        sig-name (name sig-id)
        data (:data signal)
        progress (assoc progress
                        :last-event-id sig-id
                        :last-event-at (now-inst))]
    (cond
      ;; Total URL/document count discovered by sitemap parse / source fetch.
      ;; Loaders fire one of these — we listen to both source-specific and
      ;; orchestration-shared variants.
      (or (= "found-urls" sig-name)
          (= "source-entries-fetched" sig-name))
      (assoc progress :total-urls (:count data))

      ;; A document has been prepared (fetched + chunked + phrased)
      (= "document-prepared" sig-name)
      (update progress :prepared inc)

      ;; A document has been upserted into Typesense (the storage step)
      (= :pipeline/upserting-document sig-id)
      (update progress :stored inc)

      ;; Document already existed in the docs collection, store step skipped
      (= :pipeline/document-already-exists sig-id)
      (update progress :already-exists inc)

      ;; Markdown cache observations (only meaningful for website source)
      (= :website/markdown-cache-hit sig-id)
      (update progress :cache-hits inc)

      (= :website/markdown-cache-miss sig-id)
      (update progress :cache-misses inc)

      ;; Per-chunk search-phrase cache observations. A cache-hit replays
      ;; previously-generated phrases without an LLM call; a cache-miss
      ;; spends a token. The :count carried on each event lets us roll up
      ;; "phrases reused" vs "phrases generated" totals separately from
      ;; the chunk-count totals.
      (= :search-phrases/cache-hit sig-id)
      (-> progress
          (update :phrase-cache-hits inc)
          (update :phrases-reused + (or (:count data) 0)))

      (= :search-phrases/cache-miss sig-id)
      (update progress :phrase-cache-misses inc)

      (= :search-phrases/generated sig-id)
      (update progress :phrases-generated + (or (:count data) 0))

      ;; A chunk's search-phrase generation was rejected by Azure's content
      ;; filter (primary or fallback). Surfaced separately so it doesn't get
      ;; conflated with infra failures, and so the UI can show a hint that
      ;; the corpus contains policy-sensitive content the operator can act on.
      (= :search-phrases/content-filtered sig-id)
      (-> progress
          (update :content-filtered inc)
          (record-error
           {:kind :content-filter
            :stage (:stage data)
            :chunk-id (:chunk_id data)
            :doc-num (:doc_num data)
            :chunk-index (:chunk_index data)
            :location (or (:url data) (:path data))
            :model (coerce-string (:model data))
            :triggered (vec (:triggered data))
            :message (:error-message data)}))

      ;; LLM/HTTP errors from the search-phrase generators.
      (or (= :search-phrases/primary-model-error sig-id)
          (= :search-phrases/fallback-model-error sig-id))
      (record-error
       progress
       {:kind (if (= :search-phrases/fallback-model-error sig-id)
                :fallback-model-error
                :primary-model-error)
        :chunk-id (:chunk_id data)
        :doc-num (:doc_num data)
        :chunk-index (:chunk_index data)
        :location (or (:url data) (:path data))
        :model (coerce-string (:model data))
        :message (or (some-> (:parsed-error data) :error-message)
                     (some-> signal :error str))})

      ;; Failure events fired by orchestration's prepare-documents flow.
      ;; The orchestration emits `<pipeline-name>/recoverable-failure` and
      ;; `<pipeline-name>/terminal-failure`, so we recognize on the name.
      (or (= "recoverable-failure" sig-name)
          (= "terminal-failure" sig-name)
          (= :pipeline/store-document-error sig-id))
      (-> progress
          (update :failures inc)
          (record-error
           {:kind (cond
                    (= "terminal-failure" sig-name) :terminal-failure
                    (= :pipeline/store-document-error sig-id) :store-error
                    :else :recoverable-failure)
            :failures (:failures data)
            :location (or (get-in data [:entry :loc])
                          (get-in data [:entry :path])
                          (:url data))
            :doc-id (or (get-in data [:entry :id])
                        (:id data))
            :message (some-> signal :error str)}))

      :else progress)))

(defn- progress-handler-id [execution-id]
  (keyword "digdir.pipeline.executor" (str "progress-" execution-id)))

(defn register-progress-handler!
  "Register a Telemere handler that captures progress for one execution.
   Initializes the execution's slot in !executions-progress and returns the
   handler-id used for cleanup."
  [execution-id]
  (swap! !executions-progress assoc execution-id
         (assoc empty-progress :started-at (now-inst)))
  (let [hid (progress-handler-id execution-id)]
    (t/add-handler!
     hid
     (fn
       ([signal]
        (swap! !executions-progress update execution-id
               (fn [p] (update-progress-from-signal (or p empty-progress) signal))))
       ([])))
    hid))

(defn unregister-progress-handler!
  "Remove the Telemere handler for an execution. Leaves the progress map in
   !executions-progress so the UI can keep reading the final counts until the
   slot is explicitly cleared."
  [handler-id]
  (t/remove-handler! handler-id))

(defn forget-progress!
  "Drop the in-memory progress entry for an execution (e.g. when the UI is
   done displaying it). Optional — the entry is small and survives until
   forgotten or until process restart."
  [execution-id]
  (swap! !executions-progress dissoc execution-id))

(defn get-execution-progress
  "Look up the live in-memory progress for an execution. Returns nil if the
   execution isn't tracked (older execution, or process was restarted)."
  [execution-id]
  (get @!executions-progress execution-id))

(defn list-execution-progress
  "Snapshot all in-memory progress entries. Returns a map of execution-id -> progress."
  []
  @!executions-progress)

;; =============================================================================
;; Execution Tracking
;; =============================================================================

(defn create-execution-record!
  "Create a new dataset execution record in the database.

   Args:
     conn - Datahike connection
     dataset-id - Dataset ID being executed
     user-id - User who started the execution

   Returns: execution ID"
  [conn dataset-id user-id]
  (let [execution-id (str "exec-" (nano-id))
        now (java.util.Date.)]
    (d/transact conn
                {:tx-data [{:pipeline-execution/id execution-id
                            :pipeline-execution/pipeline-id dataset-id
                            :pipeline-execution/status :running
                            :pipeline-execution/started-at now
                            :pipeline-execution/documents-processed 0
                            :pipeline-execution/documents-failed 0
                            :pipeline-execution/started-by user-id}]})
    execution-id))

(defn update-execution-status!
  "Update execution status.

   Args:
     conn - Datahike connection
     execution-id - Execution ID
     status - New status (:running, :completed, :failed, :cancelled)
     opts - Optional map with:
       :documents-processed - Number of documents processed
       :documents-failed - Number of documents failed
       :documents-total - Total documents discovered for this run (if known)
       :error-message - Error message (for failed status)"
  [conn execution-id status & [{:keys [documents-processed documents-failed
                                       documents-total error-message]}]]
  (let [db @conn
        eid (d/q '[:find ?e .
                   :in $ ?id
                   :where [?e :pipeline-execution/id ?id]]
                 db execution-id)
        update-map (cond-> {:db/id eid
                            :pipeline-execution/status status}
                     (#{:completed :failed :cancelled} status)
                     (assoc :pipeline-execution/completed-at (java.util.Date.))

                     documents-processed
                     (assoc :pipeline-execution/documents-processed documents-processed)

                     documents-failed
                     (assoc :pipeline-execution/documents-failed documents-failed)

                     documents-total
                     (assoc :pipeline-execution/documents-total documents-total)

                     error-message
                     (assoc :pipeline-execution/error-message error-message))]
    (d/transact conn {:tx-data [update-map]})))

(defn flush-progress-to-db!
  "Persist the current in-memory progress for an execution to its DB record.
   Idempotent — only writes the counters that have changed (avoids redundant
   Datahike transactions when nothing new has happened).

   Args:
     conn - Datahike connection
     execution-id - Execution ID
     prev-snapshot - Last snapshot we flushed (so we can skip if unchanged).
                     Pass nil on the first flush.

   Returns: the snapshot that was flushed (or prev-snapshot if no flush occurred)."
  [conn execution-id prev-snapshot]
  (when-let [progress (get-execution-progress execution-id)]
    (let [stored (or (:stored progress) 0)
          failures (or (:failures progress) 0)
          total (:total-urls progress)
          snapshot {:stored stored :failures failures :total total}]
      (if (= snapshot prev-snapshot)
        prev-snapshot
        (let [db @conn
              eid (d/q '[:find ?e .
                         :in $ ?id
                         :where [?e :pipeline-execution/id ?id]]
                       db execution-id)]
          (when eid
            (let [tx-map (cond-> {:db/id eid
                                  :pipeline-execution/documents-processed stored
                                  :pipeline-execution/documents-failed failures
                                  :pipeline-execution/last-progress-at (now-inst)}
                           total (assoc :pipeline-execution/documents-total total))]
              (d/transact conn {:tx-data [tx-map]})))
          snapshot)))))

(defn get-execution
  "Get execution record by ID.

   Args:
     db - Datahike database value
     execution-id - Execution ID

   Returns: Execution map or nil"
  [db execution-id]
  (d/q '[:find (pull ?e [*]) .
         :in $ ?id
         :where [?e :pipeline-execution/id ?id]]
       db execution-id))

(defn list-executions
  "List executions for a dataset.

   Args:
     db - Datahike database value
     dataset-id - Dataset ID

   Returns: Vector of execution maps, sorted by start time (newest first)"
  [db dataset-id]
  (let [executions (d/q '[:find [(pull ?e [*]) ...]
                          :in $ ?pipeline-id
                          :where
                          [?e :pipeline-execution/pipeline-id ?pipeline-id]]
                        db dataset-id)]
    (vec (sort-by :pipeline-execution/started-at #(compare %2 %1) executions))))

;; =============================================================================
;; Loader Dispatch
;; =============================================================================

(defn convert-pipeline-config-to-loader-format
  "Backward-compatible alias for the canonical Dataset-root -> loader mapping."
  [pipeline-config]
  (materialization/dataset-config->loader-config pipeline-config))

(defn dispatch-to-loader
  "Dispatch dataset execution to appropriate loader based on source type.

   Args:
     dataset-config - Dataset config map
     loader-config - Loader-formatted config map

   Returns: Missionary task that executes the dataset"
  [dataset-config loader-config]
  (let [source-type (:source-type dataset-config)]
    (case source-type
      :kudos
      (do
        (require 'digdir.pipeline.loaders.kudos)
        ((resolve 'digdir.pipeline.loaders.kudos/mk-materialize-t) loader-config))

      :website
      (do
        (require 'digdir.pipeline.loaders.website)
        ((resolve 'digdir.pipeline.loaders.website/mk-materialize-t) loader-config))

      :folder
      (do
        (require 'digdir.pipeline.loaders.folder)
        ((resolve 'digdir.pipeline.loaders.folder/mk-materialize-t) loader-config))

      :episerver
      (do
        (require 'digdir.pipeline.loaders.episerver)
        ((resolve 'digdir.pipeline.loaders.episerver/mk-materialize-t) loader-config))

      ;; Default error
      (throw (ex-info "Unknown dataset source type"
                      {:source-type source-type
                       :dataset-id (:id dataset-config)})))))

;; =============================================================================
;; Pipeline Execution
;; =============================================================================

(defn- final-progress-counts
  "Read the in-memory progress one last time and return the values we want to
   persist when an execution finishes (success, failure, or cancellation)."
  [execution-id]
  (when-let [progress (get-execution-progress execution-id)]
    {:documents-processed (or (:stored progress) 0)
     :documents-failed (or (:failures progress) 0)
     :documents-total (:total-urls progress)}))

(defn execute-pipeline!
  "Execute a dataset materialization.

   Responsibilities here are deliberately narrow: resolve config, dispatch
   to the appropriate loader, and update the execution record with
   completion status / final progress counts. Progress-handler registration
   and the background DB flusher are owned by `execute-pipeline-async!` so
   the UI sees the new execution synchronously with the click that started
   it (not after the missionary task spins up).

   Args:
     conn - Datahike connection
     tenant - Tenant identifier
     tenant-config-key - Environment
     pipeline-name - Pipeline name (legacy terminology for dataset instance)
     master-key - Encryption key
     user-id - User who started execution
     opts - Optional map. `:execution-id` reuses an existing record
            (required when called from `execute-pipeline-async!` so the id
            returned to the HTTP caller is the one the executor updates).

   Returns: Missionary task that resolves to execution-id"
  ([conn tenant tenant-config-key pipeline-name master-key user-id]
   (execute-pipeline! conn tenant tenant-config-key pipeline-name master-key user-id {}))
  ([conn tenant tenant-config-key pipeline-name master-key user-id
    {:keys [execution-id]}]
  (m/sp
   (try
     (let [db @conn
           dataset-config (pipeline/get-dataset db tenant tenant-config-key pipeline-name master-key)
           _ (when-not dataset-config
               (throw (ex-info "Dataset not found"
                               {:tenant tenant
                                :tenant-config-key tenant-config-key
                                :pipeline-name pipeline-name})))

           dataset-id (:id dataset-config)

           collection-names (collections/get-or-generate-collection-names
                             dataset-config conn master-key)
           dataset-config-with-colls (merge dataset-config collection-names)

           loader-config (convert-pipeline-config-to-loader-format dataset-config-with-colls)

           execution-id (or execution-id
                            (create-execution-record! conn dataset-id user-id))

           _ (t/event! :pipeline/executing
                       {:data {:execution-id execution-id
                               :dataset-id dataset-id
                               :source-type (:source-type dataset-config)
                               :collections collection-names}})]

       (try
         (let [loader-task (dispatch-to-loader dataset-config-with-colls loader-config)
               _ (m/? loader-task)
               final (final-progress-counts execution-id)]

           (update-execution-status! conn execution-id :completed
                                     (merge {:documents-processed 0
                                             :documents-failed 0}
                                            final))

           (t/event! :pipeline/completed
                     {:data {:execution-id execution-id
                             :dataset-id dataset-id}})

           execution-id)

         (catch Exception e
           (let [final (final-progress-counts execution-id)]
             (update-execution-status! conn execution-id :failed
                                       (merge {:error-message (.getMessage e)}
                                              final)))

           (t/error! {:id :pipeline/failed
                      :data {:execution-id execution-id
                             :dataset-id dataset-id
                             :error (.getMessage e)}})

           (throw e))))

     (catch Exception e
       (t/error! {:id :pipeline/execution-error
                  :data {:tenant tenant
                         :tenant-config-key tenant-config-key
                         :pipeline-name pipeline-name
                         :error (.getMessage e)}})
       (throw e))))))

(defn- start-progress-flusher!
  "Spawn a Java future that flushes the in-memory progress for one execution
   to its DB record every ~1.5s. Returns a `stop!` closure that halts the
   flusher and cancels the underlying future."
  [conn execution-id]
  (let [!running? (atom true)
        !last-snapshot (atom nil)
        fut (future
              (try
                (while @!running?
                  (Thread/sleep 1500)
                  (try
                    (reset! !last-snapshot
                            (flush-progress-to-db! conn execution-id
                                                   @!last-snapshot))
                    (catch InterruptedException _
                      (throw (InterruptedException.)))
                    (catch Throwable t
                      (t/error! {:id :pipeline/progress-flush-error
                                 :data {:execution-id execution-id
                                        :error (.getMessage t)}}))))
                (catch InterruptedException _ nil)))]
    (fn stop! []
      (reset! !running? false)
      (try (future-cancel fut) (catch Throwable _ nil)))))

(defn cancel-execution!
  "Cancel a running pipeline execution. Flips DB status to :cancelled
   AND interrupts the running future so the work actually halts (vs.
   the prior behavior, which only set the status and let the run
   continue to completion).

   Args:
     conn - Datahike connection
     execution-id - Execution ID

   Returns: true if cancelled, false if not running"
  [conn execution-id]
  (let [db @conn
        execution (get-execution db execution-id)]
    (if (= :running (:pipeline-execution/status execution))
      (do
        (update-execution-status! conn execution-id :cancelled)
        (when-let [fut (get @!executions-futures execution-id)]
          (try (future-cancel fut) (catch Throwable _ nil))
          (swap! !executions-futures dissoc execution-id))
        (t/event! :pipeline/cancelled {:data {:execution-id execution-id}})
        (bump-lifecycle-version!)
        true)
      false)))

;; =============================================================================
;; Async Execution
;; =============================================================================

(defn execute-pipeline-async!
  "Execute a dataset materialization asynchronously in a background thread.

   Side effects happen in this order, ALL before the future spawns, so the
   click handler that called us blocks just long enough for the UI to see
   the new execution before the response returns to the browser:

     1. Create the execution record (DB write).
     2. Register the per-execution Telemere progress handler — this puts a
        fresh entry in `!executions-progress`, which Electric watchers in
        the operator console pick up on the next reactive frame, so the
        running row + progress bar render without the user pressing Refresh.
     3. Start the background DB flusher.

   The future then runs the loader, and a try/finally tears down the
   handler + flusher whether the run succeeds or throws.

   Returns: execution-id (the run continues in a background thread)."
  [conn tenant tenant-config-key pipeline-name master-key user-id]
  (let [db @conn
        dataset-config (pipeline/get-dataset db tenant tenant-config-key pipeline-name master-key)
        dataset-id (:id dataset-config)
        execution-id (create-execution-record! conn dataset-id user-id)
        progress-handler-id (register-progress-handler! execution-id)
        stop-flusher! (start-progress-flusher! conn execution-id)
        execution-task (execute-pipeline! conn tenant tenant-config-key
                                          pipeline-name master-key user-id
                                          {:execution-id execution-id})
        ;; Bump the lifecycle version BEFORE returning so the click handler
        ;; that called us sees the new value before the swap! refresh-counter
        ;; on the client side runs. UI re-fetches happen on the next frame.
        _ (bump-lifecycle-version!)
        fut (future
              (try
                (m/? execution-task)
                (catch InterruptedException _
                  ;; Future was cancelled via cancel-execution!. The DB
                  ;; status was already flipped there; nothing more to log.
                  nil)
                (catch Exception e
                  (t/error! {:id :pipeline/async-execution-error
                             :data {:execution-id execution-id
                                    :error (.getMessage e)}}))
                (finally
                  (stop-flusher!)
                  (try (unregister-progress-handler! progress-handler-id)
                       (catch Throwable _ nil))
                  ;; One last flush so the UI's DB-derived numbers match the
                  ;; in-memory atom's final counts before the handler is gone.
                  (try (flush-progress-to-db! conn execution-id nil)
                       (catch Throwable _ nil))
                  ;; Remove from the futures atom so cancel-execution!
                  ;; doesn't try to cancel a completed future.
                  (swap! !executions-futures dissoc execution-id)
                  ;; Signal completion to any UI watching the version counter,
                  ;; so the row flips from :running to :completed/:failed without
                  ;; the user pressing Refresh.
                  (bump-lifecycle-version!))))]

    ;; Register the future so cancel-execution! can find and interrupt it.
    (swap! !executions-futures assoc execution-id fut)

    execution-id))


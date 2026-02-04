(ns digdir.pipeline.executor
  "Pipeline execution orchestration.

   Executes pipelines by loading config, dispatching to appropriate loader,
   and tracking execution status."
  (:require [datahike.api :as d]
            [missionary.core :as m]
            [taoensso.telemere :as t]
            [digdir.pipeline.core :as pipeline]
            [digdir.pipeline.collections :as collections]
            [digdir.config.db :as config-db]
            [nano-id.core :refer [nano-id]]))

;; =============================================================================
;; Execution Tracking
;; =============================================================================

(defn create-execution-record!
  "Create a new pipeline execution record in the database.

   Args:
     conn - Datahike connection
     pipeline-id - Pipeline ID being executed
     user-id - User who started the execution

   Returns: execution ID"
  [conn pipeline-id user-id]
  (let [execution-id (str "exec-" (nano-id))
        now (java.util.Date.)]
    (d/transact conn
                {:tx-data [{:pipeline-execution/id execution-id
                            :pipeline-execution/pipeline-id pipeline-id
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
       :error-message - Error message (for failed status)"
  [conn execution-id status & [{:keys [documents-processed documents-failed error-message]}]]
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

                     error-message
                     (assoc :pipeline-execution/error-message error-message))]
    (d/transact conn {:tx-data [update-map]})))

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
  "List executions for a pipeline.

   Args:
     db - Datahike database value
     pipeline-id - Pipeline ID

   Returns: Vector of execution maps, sorted by start time (newest first)"
  [db pipeline-id]
  (let [executions (d/q '[:find [(pull ?e [*]) ...]
                          :in $ ?pipeline-id
                          :where
                          [?e :pipeline-execution/pipeline-id ?pipeline-id]]
                        db pipeline-id)]
    (vec (sort-by :pipeline-execution/started-at #(compare %2 %1) executions))))

;; =============================================================================
;; Loader Dispatch
;; =============================================================================

(defn convert-pipeline-config-to-loader-format
  "Convert pipeline config from DB format to loader format.
   Maps pipeline.* properties to the format expected by loaders.

   Args:
     pipeline-config - Pipeline config map from DB

   Returns: Loader config map"
  [pipeline-config]
  (let [source-type (:source-type pipeline-config)]
    (cond-> {}
      ;; Source config
      (= source-type :kudos)
      (assoc :kudos/use-preprod? (:use-preprod pipeline-config false)
             :kudos/starting-page 1
             :documents/types (:document-types pipeline-config #{})
             :documents/limit (:document-limit pipeline-config 20000)
             :documents/offset (:document-offset pipeline-config 0)
             :documents/transducer (:document-transducer pipeline-config nil))

      (= source-type :website)
      (assoc :sitemap/url (:sitemap-url pipeline-config)
             :base-url (:base-url pipeline-config "http://localhost:1313")
             :urls/limit (:document-limit pipeline-config 30000)
             :urls/offset (:document-offset pipeline-config 0))

      (= source-type :folder)
      (assoc :folder/path (:folder-path pipeline-config)
             :files/limit (:document-limit pipeline-config 300000)
             :files/offset (:document-offset pipeline-config 0))

      (= source-type :episerver)
      (assoc :episerver/api-endpoint (:api-endpoint pipeline-config)
             :episerver/use-preprod? (:use-preprod pipeline-config false)
             :pages/limit (:document-limit pipeline-config 100000)
             :pages/offset (:document-offset pipeline-config 0))

      ;; Chunking config
      true
      (assoc :chunks/strategy (:chunk-strategy pipeline-config :header-based)
             :chunks/minimum-length (:chunk-minimum-length pipeline-config 333)
             :chunks/maximum-length (:chunk-maximum-length pipeline-config (* 2 128000))
             :chunks/hash-changer 1)

      ;; Search phrases config
      true
      (assoc :search-phrases/model (:search-phrases-model pipeline-config "gpt-4o")
             :search-phrases/fallback-model (:search-phrases-fallback pipeline-config :google/gemma-3-27b-it)
             :search-phrases/prompt (:search-phrases-prompt pipeline-config "Generate search phrases for: REPLACE_ME")
             :search-phrases/hash-changer 1)

      ;; Storage config
      true
      (assoc :store/coll-prefix (:collection-prefix pipeline-config "pipeline_"))

      ;; Parallelism config
      true
      (assoc :parallelism/documents (:parallelism-documents pipeline-config 3)
             :parallelism/store (:parallelism-store pipeline-config 1))

      ;; Fault tolerance
      true
      (assoc :fault-tolerance/max-document-failures (:max-document-failures pipeline-config 10)))))

(defn dispatch-to-loader
  "Dispatch pipeline execution to appropriate loader based on source type.

   Args:
     pipeline-config - Pipeline config map
     loader-config - Loader-formatted config map

   Returns: Missionary task that executes the pipeline"
  [pipeline-config loader-config]
  (let [source-type (:source-type pipeline-config)]
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
      (throw (ex-info "Unknown pipeline source type"
                      {:source-type source-type
                       :pipeline-id (:id pipeline-config)})))))

;; =============================================================================
;; Pipeline Execution
;; =============================================================================

(defn execute-pipeline!
  "Execute a pipeline.

   This is the main orchestration function that:
   1. Loads pipeline config from DB
   2. Creates execution record
   3. Dispatches to appropriate loader
   4. Tracks progress
   5. Updates execution status

   Args:
     conn - Datahike connection
     tenant - Tenant identifier
     environment - Environment
     pipeline-name - Pipeline name
     master-key - Encryption key
     user-id - User who started execution

   Returns: Missionary task that resolves to execution-id"
  [conn tenant environment pipeline-name master-key user-id]
  (m/sp
   (try
     (let [db @conn
           ;; Load pipeline config
           pipeline-config (pipeline/get-pipeline db tenant environment pipeline-name master-key)
           _ (when-not pipeline-config
               (throw (ex-info "Pipeline not found"
                               {:tenant tenant
                                :environment environment
                                :pipeline-name pipeline-name})))

           pipeline-id (:id pipeline-config)

           ;; Generate/track collection names
           collection-names (collections/get-or-generate-collection-names
                             pipeline-config conn master-key)
           pipeline-config-with-colls (merge pipeline-config collection-names)

           ;; Convert to loader format
           loader-config (convert-pipeline-config-to-loader-format pipeline-config-with-colls)

           ;; Create execution record
           execution-id (create-execution-record! conn pipeline-id user-id)

           _ (t/event! :pipeline/executing
                       {:data {:execution-id execution-id
                               :pipeline-id pipeline-id
                               :source-type (:source-type pipeline-config)
                               :collections collection-names}})]

       (try
         ;; Execute the pipeline
         (let [loader-task (dispatch-to-loader pipeline-config-with-colls loader-config)
               result (m/? loader-task)]

           ;; Mark as completed
           (update-execution-status! conn execution-id :completed
                                     {:documents-processed (or (:documents-processed result) 0)
                                      :documents-failed (or (:documents-failed result) 0)})

           (t/event! :pipeline/completed
                     {:data {:execution-id execution-id
                             :pipeline-id pipeline-id}})

           execution-id)

         (catch Exception e
           ;; Mark as failed
           (update-execution-status! conn execution-id :failed
                                     {:error-message (.getMessage e)})

           (t/error! {:id :pipeline/failed
                      :data {:execution-id execution-id
                             :pipeline-id pipeline-id
                             :error (.getMessage e)}})

           (throw e))))

     (catch Exception e
       (t/error! {:id :pipeline/execution-error
                  :data {:tenant tenant
                         :environment environment
                         :pipeline-name pipeline-name
                         :error (.getMessage e)}})
       (throw e)))))

(defn cancel-execution!
  "Cancel a running pipeline execution.

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
        (t/event! :pipeline/cancelled {:data {:execution-id execution-id}})
        true)
      false)))

;; =============================================================================
;; Async Execution
;; =============================================================================

(defn execute-pipeline-async!
  "Execute a pipeline asynchronously in a background thread.

   Args:
     conn - Datahike connection
     tenant - Tenant identifier
     environment - Environment
     pipeline-name - Pipeline name
     master-key - Encryption key
     user-id - User who started execution

   Returns: execution-id (execution runs in background)"
  [conn tenant environment pipeline-name master-key user-id]
  (let [execution-task (execute-pipeline! conn tenant environment pipeline-name master-key user-id)
        ;; Create execution record immediately
        db @conn
        pipeline-config (pipeline/get-pipeline db tenant environment pipeline-name master-key)
        pipeline-id (:id pipeline-config)
        execution-id (create-execution-record! conn pipeline-id user-id)]

    ;; Start execution in background
    (future
      (try
        (m/? execution-task)
        (catch Exception e
          (t/error! {:id :pipeline/async-execution-error
                     :data {:execution-id execution-id
                            :error (.getMessage e)}}))))

    execution-id))

(comment
  ;; Execute a pipeline synchronously
  (m/? (execute-pipeline! (config-db/get-conn)
                          "ka"
                          "prod"
                          "main-pipeline"
                          "master-key"
                          "user@example.com"))

  ;; Execute asynchronously
  (execute-pipeline-async! (config-db/get-conn)
                           "ka"
                           "prod"
                           "main-pipeline"
                           "master-key"
                           "user@example.com")

  ;; List executions
  (list-executions @(config-db/get-conn) "ka:prod:main-pipeline")

  ;; Get execution status
  (get-execution @(config-db/get-conn) "exec-abc123"))

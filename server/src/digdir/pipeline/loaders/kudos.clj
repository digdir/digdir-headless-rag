(ns digdir.pipeline.loaders.kudos
  "Kudos document loader for pipelines.

   Wraps the existing Kudos loader (digdir.docs.loader) to work with
   pipeline configurations from the database."
  (:require [digdir.docs.loader :as loader]
            [missionary.core :as m]
            [taoensso.telemere :as t]))

;; =============================================================================
;; Pipeline Execution
;; =============================================================================

(defn mk-materialize-t
  "Execute Kudos pipeline with the given configuration.

   This wraps the existing digdir.docs.loader/mk-materialize-t function
   to work with pipeline configs.

   Args:
     config - Pipeline configuration map in loader format (see executor for conversion)

   Returns: Missionary task that executes the pipeline"
  [config]
  (m/sp
   (t/event! :pipeline.kudos/starting {:data {:config (dissoc config :master-key)}})

   (try
     ;; Delegate to existing Kudos loader
     (let [result (m/? (loader/mk-materialize-t config))]
       (t/event! :pipeline.kudos/completed {:data {:result result}})
       result)

     (catch Exception e
       (t/error! {:id :pipeline.kudos/failed :error e})
       (throw e)))))

(defn mk-import-single-document-t
  "Import a single Kudos document by ID.

   Args:
     config - Pipeline configuration map
     doc-id - Document ID to import

   Returns: Missionary task that imports the document"
  [config doc-id]
  (m/sp
   (t/event! :pipeline.kudos/importing-single {:data {:doc-id doc-id}})

   (try
     (let [result (m/? (loader/mk-import-single-document-t config doc-id))]
       (t/event! :pipeline.kudos/single-imported {:data {:doc-id doc-id}})
       result)

     (catch Exception e
       (t/error! {:id :pipeline.kudos/single-failed :data {:doc-id doc-id} :error e})
       (throw e)))))

(comment
  ;; Example usage with pipeline config
  (let [config {:kudos/use-preprod? true
                :kudos/starting-page 1
                :documents/types #{"Årsrapport"}
                :documents/limit 100
                :documents/offset 0
                :chunks/strategy :header-based
                :chunks/minimum-length 333
                :chunks/maximum-length 256000
                :search-phrases/model "gpt-4o"
                :search-phrases/fallback-model :dphn/Dolphin-Mistral-24B-Venice-Edition
                :search-phrases/prompt "Generate search phrases..."
                :store/coll-prefix "test_pipeline_"
                :parallelism/documents 3
                :parallelism/store 1
                :fault-tolerance/max-document-failures 10}]

    ;; Execute pipeline
    (m/? (mk-materialize-t config))

    ;; Import single document
    (m/? (mk-import-single-document-t config 12345))))

(ns digdir.pipeline.loaders.folder
  "Folder markdown loader for pipelines.

   Wraps the existing folder loader (digdir.docs.folder) to work with
   pipeline configurations from the database."
  (:require [digdir.docs.folder :as folder]
            [missionary.core :as m]
            [taoensso.telemere :as t]))

;; =============================================================================
;; Pipeline Execution
;; =============================================================================

(defn mk-materialize-t
  "Execute folder pipeline with the given configuration.

   This wraps the existing digdir.docs.folder/mk-materialize-t function
   to work with pipeline configs.

   Args:
     config - Pipeline configuration map in loader format with keys:
       :folder/path - Path to folder containing markdown files
       :files/limit - Max files to process
       :files/offset - Files to skip
       :chunks/strategy - Chunking strategy (:header-based, :semantic, etc.)
       :chunks/minimum-length - Min chunk size
       :chunks/maximum-length - Max chunk size
       :search-phrases/model - LLM model for phrase generation
       :search-phrases/fallback-model - Fallback model
       :search-phrases/prompt - Prompt template
       :store/coll-prefix - TypeSense collection prefix
       :parallelism/documents - Parallel document processing
       :parallelism/store - Parallel storage operations
       :fault-tolerance/max-document-failures - Max failures before stopping

   Returns: Missionary task that executes the pipeline"
  [config]
  (m/sp
   (t/event! :pipeline.folder/starting {:data {:config (dissoc config :master-key)}})

   (try
     ;; Delegate to existing folder loader
     (let [result (m/? (folder/mk-materialize-t config))]
       (t/event! :pipeline.folder/completed {:data {:result result}})
       result)

     (catch Exception e
       (t/error! {:id :pipeline.folder/failed :error e})
       (throw e)))))

(comment
  ;; Example usage with pipeline config
  (let [config {:folder/path "/path/to/markdown/files"
                :files/limit 1000
                :files/offset 0
                :chunks/strategy :header-based
                :chunks/minimum-length 333
                :chunks/maximum-length 256000
                :search-phrases/model "gpt-4o"
                :search-phrases/fallback-model :google/gemma-3-27b-it
                :search-phrases/prompt "Generate search phrases..."
                :store/coll-prefix "folder_pipeline_"
                :parallelism/documents 3
                :parallelism/store 1
                :fault-tolerance/max-document-failures 10}]

    ;; Execute pipeline
    (m/? (mk-materialize-t config))))

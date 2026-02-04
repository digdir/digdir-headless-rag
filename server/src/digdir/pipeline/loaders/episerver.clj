(ns digdir.pipeline.loaders.episerver
  "EPiServer/Optimizely loader for pipelines.

   Wraps the existing EPiServer loader (digdir.docs.episerver) to work with
   pipeline configurations from the database."
  (:require [digdir.docs.episerver :as episerver]
            [missionary.core :as m]
            [taoensso.telemere :as t]))

;; =============================================================================
;; Pipeline Execution
;; =============================================================================

(defn mk-materialize-t
  "Execute EPiServer pipeline with the given configuration.

   This wraps the existing digdir.docs.episerver/mk-materialize-t function
   to work with pipeline configs.

   Args:
     config - Pipeline configuration map in loader format with keys:
       :episerver/api-endpoint - EPiServer API endpoint URL
       :episerver/use-preprod? - Whether to use preprod API
       :pages/limit - Max pages to process
       :pages/offset - Pages to skip
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
   (t/event! :pipeline.episerver/starting {:data {:config (dissoc config :master-key)}})

   (try
     ;; Delegate to existing EPiServer loader
     (let [result (m/? (episerver/mk-materialize-t config))]
       (t/event! :pipeline.episerver/completed {:data {:result result}})
       result)

     (catch Exception e
       (t/error! :pipeline.episerver/failed {} e)
       (throw e)))))

(comment
  ;; Example usage with pipeline config
  (let [config {:episerver/api-endpoint "https://api.episerver.com/content"
                :episerver/use-preprod? false
                :pages/limit 1000
                :pages/offset 0
                :chunks/strategy :header-based
                :chunks/minimum-length 333
                :chunks/maximum-length 256000
                :search-phrases/model "gpt-4o"
                :search-phrases/fallback-model :google/gemma-3-27b-it
                :search-phrases/prompt "Generate search phrases..."
                :store/coll-prefix "episerver_pipeline_"
                :parallelism/documents 3
                :parallelism/store 1
                :fault-tolerance/max-document-failures 10}]

    ;; Execute pipeline
    (m/? (mk-materialize-t config))))

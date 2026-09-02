(ns digdir.pipeline.loaders.website
  "Website markdown loader for pipelines.

   Wraps the existing website loader (digdir.docs.website) to work with
   pipeline configurations from the database."
  (:require [digdir.docs.website :as website]
            [missionary.core :as m]
            [taoensso.telemere :as t]))

;; =============================================================================
;; Pipeline Execution
;; =============================================================================

(defn mk-materialize-t
  "Execute website pipeline with the given configuration.

   This wraps the existing digdir.docs.website/mk-materialize-t function
   to work with pipeline configs.

   Args:
     config - Pipeline configuration map in loader format with keys:
       :sitemap/url - URL path to sitemap (e.g., \"/nb/sitemap-markdown.xml\")
       :base-url - Base URL of website (e.g., \"http://localhost:1313\")
       :urls/limit - Max URLs to process
       :urls/offset - URLs to skip
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
   (t/event! :pipeline.website/starting {:data {:config (dissoc config :master-key)}})

   (try
     ;; Delegate to existing website loader
     (let [result (m/? (website/mk-materialize-t config))]
       (t/event! :pipeline.website/completed {:data {:result result}})
       result)

     (catch Exception e
       (t/error! {:id :pipeline.website/failed :error e})
       (throw e)))))

(comment
  ;; Example usage with pipeline config
  (let [config {:sitemap/url "/nb/sitemap-markdown.xml"
                :base-url "http://localhost:1313"
                :urls/limit 100
                :urls/offset 0
                :chunks/strategy :header-based
                :chunks/minimum-length 333
                :chunks/maximum-length 256000
                :search-phrases/model "gpt-4o"
                :search-phrases/fallback-model :google/gemma-3-27b-it
                :search-phrases/prompt "Generate search phrases..."
                :store/coll-prefix "website_pipeline_"
                :parallelism/documents 3
                :parallelism/store 1
                :fault-tolerance/max-document-failures 10}]

    ;; Execute pipeline
    (m/? (mk-materialize-t config))))

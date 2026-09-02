(ns digdir.pipeline.skills.context
  "Skill execution context building and service resolution.

   Builds execution contexts from pipeline configuration and
   resolves service clients (Typesense, Azure OpenAI, ColBERT)
   for skill execution."
  (:require [digdir.config.accessor :as cfg]
            [digdir.config.db :as config-db]
            [digdir.rag.typesense :as ts-utils]
            [digdir.rag.skills.core :as skills-core]))

;; =============================================================================
;; Service Resolution
;; =============================================================================

(defn resolve-typesense-client
  "Resolve TypeSense client from config.

   Args:
     opts - Map with :tenant and :environment

   Returns: TypeSense settings map or nil"
  [opts]
  (ts-utils/make-ts-settings opts))

(defn resolve-azure-openai-client
  "Resolve Azure OpenAI configuration.

   Returns: Map with :api-key, :api-endpoint, :api-version, :deployment-name"
  []
  {:api-key (cfg/get :services :azure-openai :api-key)
   :api-endpoint (cfg/get :services :azure-openai :api-endpoint)
   :api-version (cfg/get :services :azure-openai :api-version)
   :deployment-name (cfg/get :services :azure-openai :deployment-name)})

(defn resolve-colbert-client
  "Resolve ColBERT reranker configuration.

   Returns: Map with :api-url and :api-key"
  []
  {:api-url (cfg/get :services :colbert :api-url)
   :api-key (cfg/get :services :colbert :api-key)})

(defn resolve-all-services
  "Resolve all available service clients.

   Args:
     opts - Map with :tenant and :environment

   Returns: Map of service keyword to client/config"
  [opts]
  {:typesense (resolve-typesense-client opts)
   :azure-openai (resolve-azure-openai-client)
   :colbert (resolve-colbert-client)})

;; =============================================================================
;; Context Building
;; =============================================================================

(defn build-execution-context
  "Build an execution context for skill invocation.

   Args:
     skill-id - Keyword identifying the skill
     inputs - Map of input data for the skill
     opts - Map with:
       :tenant - Tenant identifier
       :environment - Environment
       :entity - Entity identifier (optional)
       :pipeline-config - Full pipeline configuration
       :parameters - Override parameters for the skill
       :execution-id - Optional execution ID for tracking

   Returns: ExecutionContext map ready for skill execution"
  [skill-id inputs {:keys [tenant environment entity pipeline-config parameters execution-id]}]
  (let [services (resolve-all-services {:tenant tenant :environment environment})
        resolved-params (or parameters {})]
    (skills-core/make-execution-context
      skill-id
      inputs
      resolved-params
      services
      (or pipeline-config {})
      (when execution-id {:execution-id execution-id}))))

(defn build-context-from-pipeline
  "Build execution context from a pipeline configuration.

   This is a convenience function for skills invoked during pipeline execution.

   Args:
     skill-id - Keyword identifying the skill
     inputs - Map of input data
     pipeline-config - Pipeline configuration map containing:
       :tenant, :environment, :entity, plus skill-specific params

   Returns: ExecutionContext map"
  [skill-id inputs pipeline-config]
  (build-execution-context
    skill-id
    inputs
    {:tenant (:tenant pipeline-config)
     :environment (:environment pipeline-config)
     :entity (:entity pipeline-config)
     :pipeline-config pipeline-config
     :parameters (select-keys pipeline-config
                              [:model :temperature :top-k :max-tokens :prompt])}))

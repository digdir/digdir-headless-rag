(ns digdir.skills.context
  "Skill execution context building and service resolution.

   Builds execution contexts from dataset configuration and resolves the
   TypeSense client (which is invocation-scoped). Azure OpenAI and ColBERT
   values are NOT pre-resolved here — skills look them up dynamically at
   use time so per-tenant overrides (e.g. bring-your-own-Azure-key) are
   honored on each call."
  (:require [digdir.config.accessor :as cfg]
            [digdir.execution.scope :as scope]
            [digdir.rag.typesense :as ts-utils]
            [digdir.rag.skills.core :as skills-core]))

;; =============================================================================
;; Service Resolution
;; =============================================================================

(defn resolve-typesense-client
  "Resolve TypeSense client from config.

   Args:
     opts - Map with :tenant and :runtime-config-key

   Returns: TypeSense settings map or nil"
  [opts]
  (ts-utils/make-ts-settings opts))

(defn resolve-all-services
  "Resolve invocation-scoped service clients.

   Only TypeSense is pre-resolved here — Azure OpenAI and ColBERT must be
   resolved at each use site with the correct tenant so tenant overrides
   take effect. Skills access those via cfg/get directly.

   Args:
     opts - Map with :tenant and :runtime-config-key

   Returns: Map with :typesense (may include more in the future)"
  [opts]
  {:typesense (resolve-typesense-client opts)})

;; =============================================================================
;; Context Building
;; =============================================================================

(defn normalize-dataset-ref
  "Normalize a dataset ref to canonical dataset fields."
  [dataset-ref]
  (scope/normalize-dataset-ref dataset-ref))

(defn resolve-dataset-context
  "Resolve tenant/dataset-config-key scope and collection bindings for a dataset ref."
  [dataset-ref]
  (scope/resolve-dataset-context-by-ref! dataset-ref))

(defn- resolve-validate-io?
  "Read system.io-validation.enabled from the platform config for the tenant.
   Returns nil when no tenant is available (test code, scope-less calls)."
  [tenant]
  (when tenant
    (boolean (cfg/get {:tenant tenant :default false}
                      :system :io-validation :enabled))))

(defn apply-dataset-context
  "Enrich inputs and opts with explicit dataset-ref and agent scope.

   Existing explicit collection inputs win over dataset-derived defaults."
  [inputs {:keys [tenant dataset-config-key tenant-config-key skill-params dataset-ref agent-id entity] :as opts}]
  (let [dataset-ref (or (normalize-dataset-ref dataset-ref)
                        (normalize-dataset-ref (:dataset-ref skill-params))
                        (normalize-dataset-ref (:dataset-ref inputs)))
        needs-dataset-resolution? (and dataset-ref
                                       (or (nil? tenant)
                                           (nil? dataset-config-key)
                                           (nil? (:docs-collection inputs))
                                           (nil? (:chunks-collection inputs))
                                           (nil? (:phrases-collection inputs))))
        dataset-context (when needs-dataset-resolution?
                          (resolve-dataset-context dataset-ref))
        tenant (or tenant (:tenant dataset-context))
        dataset-config-key (or dataset-config-key
                               (:dataset-config-key dataset-context))
        agent-id (or agent-id
                     (:agent-id skill-params)
                     (:agent-id inputs))
        ;; Merge collections from dataset-context, but let explicit inputs win
        resolved-inputs (merge (:dataset-inputs dataset-context)
                               inputs
                               (when dataset-ref {:dataset-ref dataset-ref}))
        validate-io? (if (contains? opts :validate-io?)
                       (:validate-io? opts)
                       (resolve-validate-io? tenant))
        resolved-opts (cond-> (scope/assoc-execution-scope opts
                                                           {:tenant tenant
                                                            :dataset-config-key dataset-config-key
                                                            :dataset-ref dataset-ref
                                                            :agent-id agent-id
                                                            :entity entity})
                        tenant-config-key (assoc :tenant-config-key tenant-config-key)
                        (some? validate-io?) (assoc :validate-io? validate-io?))]
    {:inputs resolved-inputs
     :opts resolved-opts
     :dataset-ref dataset-ref
     :agent-id agent-id}))

(defn build-execution-context
  "Build a complete execution context for a skill.

   Args:
     skill-id - Keyword identifying the skill
     inputs - Map of input data for the skill
     opts - Map with:
       :tenant - Tenant identifier
       :dataset-config-key - dataset config selector
       :tenant-config-key - runtime-scope selector when provided separately
       :agent-id - Agent identifier (optional)
       :dataset-ref - Dataset ref {:tenant :dataset-config-key} (optional)
       :entity - Entity identifier (optional)
       :skill-params - Skill parameter overrides by skill-id
       :parameters - Override parameters for the skill
       :execution-id - Optional execution ID for tracking

   Returns: ExecutionContext map ready for skill execution"
  [skill-id inputs {:keys [parameters execution-id] :as opts}]
  (let [{:keys [inputs opts dataset-ref agent-id]} (apply-dataset-context inputs opts)
        services (resolve-all-services {:tenant (:tenant opts)
                                        :runtime-config-key (:runtime-config-key opts)})
        resolved-params (or parameters {})
        validate-io? (:validate-io? opts)]
    (skills-core/make-execution-context
      skill-id
      inputs
      resolved-params
      services
      (:skill-params opts)
      (cond-> {}
        execution-id (assoc :execution-id execution-id)
        agent-id (assoc :agent-id agent-id)
        dataset-ref (assoc :dataset-ref dataset-ref)
        (some? validate-io?) (assoc :validate-io? validate-io?)))))

(defn build-context-from-skill-params
  "Build execution context from skill parameters.

   This is a convenience function for skills invoked during skill graph execution.

   Args:
     skill-id - Keyword identifying the skill
     inputs - Map of input data
     skill-params - Skill parameters map containing:
       :tenant, :dataset-config-key/:tenant-config-key, :runtime-config-key, :entity, plus skill-specific params

   Returns: ExecutionContext map"
  [skill-id inputs skill-params]
  (build-execution-context
     skill-id
     inputs
     {:tenant (:tenant skill-params)
     :dataset-config-key (or (:dataset-config-key skill-params)
                             (:tenant-config-key skill-params))
     :runtime-config-key (or (:runtime-config-key skill-params)
                             (:tenant-config-key skill-params))
     :agent-id (:agent-id skill-params)
     :dataset-ref (:dataset-ref skill-params)
     :entity (:entity skill-params)
     :skill-params skill-params
     :parameters (select-keys skill-params
                              [:model :temperature :top-k :max-tokens :prompt])}))

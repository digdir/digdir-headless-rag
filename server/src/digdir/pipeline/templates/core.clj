(ns digdir.pipeline.templates.core
  "Template management - CRUD operations and instantiation.

   Templates are pre-defined skill graphs that can be:
   - Instantiated with specific tenant/environment settings
   - Customized with parameter overrides
   - Stored and retrieved from the template registry"
  (:require [digdir.pipeline.skills.graph.schema :as schema]
            [nano-id.core :refer [nano-id]]))

;; =============================================================================
;; Template Registry
;; =============================================================================

(defonce ^{:private true
           :doc "Atom containing registered templates, keyed by template ID"}
  !template-registry
  (atom {}))

(defn register-template!
  "Register a template in the registry.

   Args:
     template - Template map with :id, :name, :description, :graph

   Returns: The registered template"
  [template]
  (let [template-id (or (:id template)
                        (keyword (str "template/" (nano-id 8))))]
    (schema/fully-validate-graph! (:graph template))
    (let [registered (assoc template :id template-id)]
      (swap! !template-registry assoc template-id registered)
      registered)))

(defn get-template
  "Get a template by ID from the registry.

   Args:
     template-id - Keyword identifier for the template

   Returns: Template map or nil"
  [template-id]
  (get @!template-registry template-id))

(defn list-templates
  "List all registered templates.

   Returns: Sequence of template maps"
  []
  (vals @!template-registry))

(defn list-template-ids
  "List all registered template IDs.

   Returns: Sequence of template ID keywords"
  []
  (keys @!template-registry))

(defn unregister-template!
  "Remove a template from the registry.

   Args:
     template-id - Keyword identifier

   Returns: The removed template or nil"
  [template-id]
  (let [template (get-template template-id)]
    (swap! !template-registry dissoc template-id)
    template))

(defn clear-registry!
  "Clear all templates. Primarily for testing."
  []
  (reset! !template-registry {}))

;; =============================================================================
;; Template Instantiation
;; =============================================================================

(defn instantiate-template
  "Instantiate a template with specific tenant/environment settings.

   Args:
     template-id - Template ID keyword
     tenant - Tenant identifier
     environment - Environment
     overrides - Map of parameter overrides

   Returns: Instantiated template ready for execution"
  [template-id tenant environment overrides]
  (let [template (get-template template-id)]
    (when-not template
      (throw (ex-info "Template not found"
                      {:template-id template-id
                       :available (list-template-ids)})))
    (-> template
        (assoc :tenant tenant
               :environment environment
               :instantiated-at (System/currentTimeMillis))
        (update :parameters merge overrides))))

(defn instantiate-graph
  "Instantiate a template and return just the graph with context.

   Args:
     template-id - Template ID keyword
     tenant - Tenant identifier
     environment - Environment
     overrides - Map of parameter overrides

   Returns: Map with :graph and :execution-opts"
  [template-id tenant environment overrides]
  (let [instantiated (instantiate-template template-id tenant environment overrides)]
    {:graph (:graph instantiated)
     :execution-opts {:tenant tenant
                      :environment environment
                      :pipeline-config (merge
                                         (:parameters instantiated)
                                         overrides)}}))

;; =============================================================================
;; Template Creation Helpers
;; =============================================================================

(defn make-template
  "Create a template definition.

   Args:
     id - Template ID keyword
     name - Human-readable name
     description - Description
     graph - Skill graph definition
     opts - Optional map with :parameters, :tags, :version

   Returns: Template map"
  [id name description graph & [opts]]
  (merge
    {:id id
     :name name
     :description description
     :graph graph}
    opts))

(defn make-step
  "Create a graph step definition.

   Args:
     id - Step ID keyword
     skill - Skill ID keyword
     inputs - Map of input-name -> input-ref
     opts - Optional map with :parameters, :condition, :on-error

   Returns: Step map"
  [id skill inputs & [opts]]
  (merge
    {:id id
     :skill skill
     :inputs inputs}
    opts))

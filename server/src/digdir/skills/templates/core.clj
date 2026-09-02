(ns digdir.skills.templates.core
  "Template management - CRUD operations and instantiation.

   Templates are pre-defined skill graphs that can be:
   - Instantiated with specific tenant/runtime-config-key settings
   - Customized with parameter overrides
   - Stored and retrieved from the template registry"
  (:require [digdir.skills.graph.schema :as schema]
            [digdir.config.accessor :as cfg]
            [digdir.config.db :as config-db]
            [nano-id.core :refer [nano-id]]))

;; =============================================================================
;; Template Registry
;; =============================================================================

(defonce ^{:private true
           :doc "Atom containing registered templates, keyed by template ID"}
  !skill-graph-registry
  (atom {}))

(defn register-skill-graph!
  "Register a skill graph in the registry.

   Args:
     skill-graph - Skill graph map with :id, :name, :description, :graph

   Returns: The registered skill graph"
  [skill-graph]
  (let [skill-graph-id (or (:id skill-graph)
                           (keyword (str "skill-graph/" (nano-id 8))))]
    (schema/fully-validate-graph! (:graph skill-graph))
    (let [registered (assoc skill-graph :id skill-graph-id)]
      (swap! !skill-graph-registry assoc skill-graph-id registered)
      registered)))

(defn get-skill-graph
  "Get a skill graph by ID from the registry.

   Args:
     skill-graph-id - Keyword identifier for the skill graph

   Returns: Skill graph map or nil"
  [skill-graph-id]
  (get @!skill-graph-registry skill-graph-id))

(defn list-skill-graphs
  "List all registered skill graphs.

   Returns: Sequence of skill graph maps"
  []
  (vals @!skill-graph-registry))

(defn list-skill-graph-ids
  "List all registered skill graph IDs.

   Returns: Sequence of skill graph ID keywords"
  []
  (keys @!skill-graph-registry))

(defn unregister-skill-graph!
  "Remove a skill graph from the registry.

   Args:
     skill-graph-id - Keyword identifier

   Returns: The removed skill graph or nil"
  [skill-graph-id]
  (let [skill-graph (get-skill-graph skill-graph-id)]
    (swap! !skill-graph-registry dissoc skill-graph-id)
    skill-graph))

(defn clear-registry!
  "Clear all skill graphs. Primarily for testing."
  []
  (reset! !skill-graph-registry {}))

;; =============================================================================
;; Template Instantiation
;; =============================================================================

(defn resolve-pipeline-id-for-dataset
  "Resolve a pipeline-id from a dataset-ref.
   
   Looks for a materialization pipeline associated with the dataset
   that matches the provided dataset-config-key."
  [_tenant dataset-ref]
  (let [dataset-id (:dataset-id dataset-ref)
        dataset-config-key (:dataset-config-key dataset-ref)
        conn (config-db/get-conn)]
    (when (and conn dataset-id)
      (let [db @conn
            pipelines (config-db/list-dataset-pipelines db)
            matching (filter (fn [p]
                               (and (= dataset-id (get-in p [:dataset.pipeline/dataset :dataset/id]))
                                    (= dataset-config-key (or (:dataset.pipeline/tenant-config-key p)
                                                              (:tenant-config-key p))))) ;; Fallback for older schema
                             pipelines)]
        (:dataset.pipeline/id (first matching))))))

(defn instantiate-skill-graph
  "Instantiate a skill graph with specific tenant/runtime-config-key settings.

   Args:
     skill-graph-id - Skill graph ID keyword
     tenant - Tenant identifier
     runtime-config-key - runtime-config-key
     dataset-ref - dataset-ref map {:tenant, :dataset-config-key}
     overrides - Map of parameter overrides

   Returns: Instantiated skill graph ready for execution"
  [skill-graph-id tenant runtime-config-key dataset-ref overrides]
  (let [skill-graph (get-skill-graph skill-graph-id)]
    (when-not skill-graph
      (throw (ex-info "Skill graph not found"
                      {:skill-graph-id skill-graph-id
                       :available (list-skill-graph-ids)})))
    
    ;; Hydrate parameters from config roots
    (let [;; 1. Load Runtime config (Agent/Bot behavior)
          runtime-params (when runtime-config-key
                           (cfg/get-runtime-skill-config-v2
                             {:tenant tenant
                              :tenant-config-key runtime-config-key
                              :agent-id (str skill-graph-id)}))
          
          ;; 2. Load Dataset/Pipeline config (Materialization/Data settings)
          pipeline-id (resolve-pipeline-id-for-dataset tenant dataset-ref)
          dataset-params (when pipeline-id
                           (cfg/get-dataset-pipeline-config-v2
                             {:tenant tenant
                              :pipeline-id pipeline-id}))
          
          ;; Merge order: Skill Defaults < Runtime Config < Dataset Config < Call Overrides
          hydrated-params (merge
                            (or (:parameters skill-graph) {})
                            (or runtime-params {})
                            (or dataset-params {})
                            (or overrides {}))]
      
      (-> skill-graph
          (assoc :tenant tenant
                 :runtime-config-key runtime-config-key
                 :dataset-ref dataset-ref
                 :instantiated-at (System/currentTimeMillis))
          (assoc :parameters hydrated-params)))))

(defn instantiate-graph
  "Instantiate a template and return just the graph with context.

   Args:
     template-id - Template ID keyword
     tenant - Tenant identifier
     runtime-config-key - runtime-config-key
     dataset-ref - dataset-ref map
     overrides - Map of parameter overrides

   Returns: Map with :graph and :execution-opts"
  [template-id tenant runtime-config-key dataset-ref overrides]
  (let [instantiated (instantiate-skill-graph template-id tenant runtime-config-key dataset-ref overrides)]
    {:graph (:graph instantiated)
     :execution-opts {:tenant tenant
                      :runtime-config-key runtime-config-key
                      :dataset-ref dataset-ref
                      :skill-params (merge
                                         (:parameters instantiated)
                                         overrides)}}))

;; =============================================================================
;; Template Creation Helpers
;; =============================================================================

(def agent-tool-input-schema
  "Malli schema for the single input shape shared by every user-facing
   skill graph (preserved built-ins and docs/* alike). Reflected by the
   MCP server in Phase 3 to derive each tool's `inputSchema`.

   `:claim` is optional everywhere; the fact-checker is the only graph
   that reads it (defaults to :user-query when absent). Collections are
   never user inputs — they come from the agent's bound dataset-config."
  [:map
   [:user-query [:string {:min 1}]]
   [:conversation-history {:optional true} [:vector :map]]
   [:model {:optional true} :string]
   [:temperature {:optional true} :double]
   [:claim {:optional true} :string]])

(defn make-skill-graph
  "Create a skill graph definition.

   Args:
     id - Skill graph ID keyword
     name - Human-readable name
     description - Description
     graph - Skill graph definition
     opts - Optional map with :parameters, :tags, :version, :input-schema

   The :input-schema field is a Malli schema describing the graph's
   inputs as seen by external callers (Phase 3 MCP tools). When absent
   the graph is treated as internal (e.g. inner sub-graphs invoked via
   foreach) and is not surfaced as a tool.

   Returns: Skill graph map"
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

(ns digdir.pipeline.core
  "Pipeline CRUD operations.

   Pipelines unify data loading and query-time configuration for RAG.
   Uses the same config system as entities, with pipeline.* namespaced properties."
  (:require [datahike.api :as d]
            [nano-id.core :refer [nano-id]]
            [clojure.string :as str]
            [digdir.config.db :as config-db]
            [digdir.config.accessor :as config]))

;; =============================================================================
;; Pipeline ID Generation
;; =============================================================================

(defn make-pipeline-id
  "Generate a pipeline ID from tenant, environment, and pipeline name.

   Format: tenant:env:pipeline-name
   Examples:
   - 'ka:prod:main-pipeline'
   - 'altinn:test:website-pipeline'
   - '_:_:default-pipeline' (global default)"
  [tenant environment pipeline-name]
  (str (or tenant "_") ":" (or environment "_") ":" pipeline-name))

(defn parse-pipeline-id
  "Parse a pipeline ID into its components.

   Returns: {:tenant string-or-nil, :environment string-or-nil, :pipeline-name string}"
  [pipeline-id]
  (let [[tenant env pipeline-name] (str/split pipeline-id #":" 3)]
    {:tenant (when (not= tenant "_") tenant)
     :environment (when (not= env "_") env)
     :pipeline-name pipeline-name}))

;; =============================================================================
;; Pipeline Operations
;; =============================================================================

(defn get-pipeline
  "Get pipeline configuration by ID.
   Resolves all pipeline properties using 8-level inheritance.

   Args:
     db - Datahike database value
     tenant - Tenant identifier
     environment - Environment
     pipeline-name - Pipeline name
     master-key - Encryption key

   Returns: Map with all pipeline properties, or nil if not found"
  [db tenant environment pipeline-name master-key]
  (let [pipeline-id (make-pipeline-id tenant environment pipeline-name)]
    (reduce
     (fn [acc prop-path]
       (let [definition (config-db/get-definition db prop-path)]
         (if definition
           ;; Resolve value WITH pipeline scope - enables full inheritance
           (let [resolved (config-db/resolve-value db tenant environment pipeline-name prop-path)]
             (if resolved
               (let [prop-name (get config-db/path-to-pipeline-property prop-path)
                     decoded (config-db/decode-value (:config/value resolved)
                                                      (:config-def/value-type definition)
                                                      (:config-def/encrypted? definition)
                                                      master-key)]
                 (assoc acc prop-name decoded))
               acc))
           acc)))
     {:id pipeline-id
      :tenant tenant
      :environment environment
      :pipeline-name pipeline-name}
     config-db/pipeline-property-paths)))

(defn create-pipeline!
  "Create a new pipeline with the given properties.

   Args:
     conn - Datahike connection
     opts - Map with:
       :tenant - Tenant identifier
       :environment - Environment
       :pipeline-name - Unique pipeline name within tenant/env
       :properties - Map of property keyword to value (e.g., {:name \"Main Pipeline\" :source-type :kudos})
       :master-key - Encryption key

   Uses scope-based design with pipeline-name in the entity field.
   Paths use pipeline.* namespace prefixes."
  [conn {:keys [tenant environment pipeline-name properties master-key]}]
  (doseq [[prop value] properties]
    (when value
      (let [path (config-db/pipeline-property-path prop)
            ;; Prompts are under pipeline.generate.prompt.* path
            is-prompt? (and path (str/starts-with? path "pipeline.generate.prompt."))]
        ;; Ensure definition exists (generic, shared across all pipelines)
        (when (and path (not (config-db/get-definition @conn path)))
          (config-db/upsert-definition! conn
                                        {:path path
                                         :value-type (cond
                                                       (boolean? value) :boolean
                                                       (number? value) :number
                                                       (or (map? value) (vector? value)) :edn
                                                       :else :string)
                                         :encrypted? false
                                         :multiline? is-prompt?
                                         :description (str "Pipeline property: " (name prop))
                                         :category :pipelines
                                         :service :llm
                                         :sensitivity :internal
                                         :function (if is-prompt? :prompts :settings)}))
        ;; Set the value WITH pipeline scope (using entity field)
        (when path
          (config-db/set-value! conn {:tenant tenant
                                      :environment environment
                                      :entity pipeline-name  ; <-- Pipeline name in SCOPE field
                                      :path path
                                      :value value
                                      :master-key master-key})))))
  (make-pipeline-id tenant environment pipeline-name))

(defn update-pipeline!
  "Update pipeline properties. Only updates provided properties.

   Args:
     conn - Datahike connection
     opts - Map with:
       :tenant - Tenant identifier
       :environment - Environment
       :pipeline-name - Pipeline name
       :properties - Map of properties to update
       :master-key - Encryption key"
  [conn {:keys [tenant environment pipeline-name properties master-key]}]
  (create-pipeline! conn {:tenant tenant
                          :environment environment
                          :pipeline-name pipeline-name
                          :properties properties
                          :master-key master-key}))

(defn delete-pipeline!
  "Hard delete all configs for a pipeline.
   Removes all pipeline.* values that have this pipeline-name in scope.

   Args:
     conn - Datahike connection
     tenant - Tenant identifier
     environment - Environment
     pipeline-name - Pipeline name"
  [conn tenant environment pipeline-name]
  (let [db @conn
        ;; Find all config values for this pipeline scope
        pipeline-configs (d/q '[:find [(pull ?e [:config/id :db/id]) ...]
                                :in $ ?tenant ?env ?pipeline-name
                                :where
                                [?e :config/tenant ?tenant]
                                [?e :config/environment ?env]
                                [?e :config/entity ?pipeline-name]]
                              db tenant environment pipeline-name)]
    (when (seq pipeline-configs)
      (d/transact conn {:tx-data (mapv #(vector :db/retractEntity (:db/id %)) pipeline-configs)}))))

(defn soft-delete-pipeline!
  "Soft-delete a pipeline by setting :config/deleted-at on all its configs.
   Preserves data for audit purposes. Returns count of configs marked deleted.

   Args:
     conn - Datahike connection
     tenant - Tenant identifier
     environment - Environment
     pipeline-name - Pipeline name"
  [conn tenant environment pipeline-name]
  (let [db @conn
        now (System/currentTimeMillis)
        ;; Find all config values for this pipeline scope that are not already deleted
        pipeline-configs (d/q '[:find [(pull ?e [:db/id :config/id]) ...]
                                :in $ ?tenant ?env ?pipeline-name
                                :where
                                [?e :config/tenant ?tenant]
                                [?e :config/environment ?env]
                                [?e :config/entity ?pipeline-name]
                                (not [?e :config/deleted-at])]
                              db tenant environment pipeline-name)]
    (when (seq pipeline-configs)
      (d/transact conn {:tx-data (mapv (fn [{eid :db/id}]
                                         {:db/id eid
                                          :config/deleted-at now})
                                       pipeline-configs)}))
    (count pipeline-configs)))

(defn list-pipelines
  "Get all active (non-deleted) pipeline names for a tenant/environment.
   Queries for distinct entity values in :config/entity field where path starts with 'pipeline.',
   excluding any entries that have been soft-deleted.

   Args:
     db - Datahike database value
     tenant - Tenant identifier (nil for all tenants)
     environment - Environment (nil for all environments)

   Returns: Vector of pipeline names"
  [db tenant environment]
  (let [pipelines (cond
                    ;; Both tenant and environment specified
                    (and tenant environment)
                    (d/q '[:find [?pipeline ...]
                           :in $ ?tenant ?env
                           :where
                           [?def :config-def/path ?path]
                           [(clojure.string/starts-with? ?path "pipeline.")]
                           [?e :config/definition ?def]
                           [?e :config/tenant ?tenant]
                           [?e :config/environment ?env]
                           [?e :config/entity ?pipeline]
                           [(some? ?pipeline)]
                           (not [?e :config/deleted-at])]
                         db tenant environment)

                    ;; Only tenant specified
                    tenant
                    (d/q '[:find [?pipeline ...]
                           :in $ ?tenant
                           :where
                           [?def :config-def/path ?path]
                           [(clojure.string/starts-with? ?path "pipeline.")]
                           [?e :config/definition ?def]
                           [?e :config/tenant ?tenant]
                           [?e :config/entity ?pipeline]
                           [(some? ?pipeline)]
                           (not [?e :config/deleted-at])]
                         db tenant)

                    ;; Only environment specified
                    environment
                    (d/q '[:find [?pipeline ...]
                           :in $ ?env
                           :where
                           [?def :config-def/path ?path]
                           [(clojure.string/starts-with? ?path "pipeline.")]
                           [?e :config/definition ?def]
                           [?e :config/environment ?env]
                           [?e :config/entity ?pipeline]
                           [(some? ?pipeline)]
                           (not [?e :config/deleted-at])]
                         db environment)

                    ;; No filters - all pipelines
                    :else
                    (d/q '[:find [?pipeline ...]
                           :where
                           [?def :config-def/path ?path]
                           [(clojure.string/starts-with? ?path "pipeline.")]
                           [?e :config/definition ?def]
                           [?e :config/entity ?pipeline]
                           [(some? ?pipeline)]
                           (not [?e :config/deleted-at])]
                         db))]
    (-> pipelines distinct sort vec)))

(defn list-all-pipelines
  "Get all active (non-deleted) pipeline names across all tenants and environments.
   Returns a sorted vector of unique pipeline names."
  [db]
  (list-pipelines db nil nil))

(defn duplicate-pipeline!
  "Duplicate an existing pipeline with a new name.
   Copies all pipeline.* properties from source pipeline to new pipeline.

   Args:
     conn - Datahike connection
     opts - Map with:
       :tenant - Tenant identifier
       :environment - Environment
       :source-pipeline-name - Pipeline to copy from
       :new-pipeline-name - New pipeline name
       :master-key - Encryption key

   Returns: new pipeline ID"
  [conn {:keys [tenant environment source-pipeline-name new-pipeline-name master-key]}]
  (let [db @conn
        ;; Get source pipeline's resolved properties
        source-props (get-pipeline db tenant environment source-pipeline-name master-key)
        ;; Remove metadata fields
        props-to-copy (dissoc source-props :id :tenant :environment :pipeline-name)]
    (when (empty? props-to-copy)
      (throw (ex-info "Source pipeline not found or has no properties"
                      {:tenant tenant
                       :environment environment
                       :source-pipeline-name source-pipeline-name})))
    (create-pipeline! conn {:tenant tenant
                            :environment environment
                            :pipeline-name new-pipeline-name
                            :properties props-to-copy
                            :master-key master-key})
    (make-pipeline-id tenant environment new-pipeline-name)))

(comment
  ;; Usage examples:

  ;; Create a pipeline
  (create-pipeline! (config-db/get-conn)
                    {:tenant "ka"
                     :environment "prod"
                     :pipeline-name "main-pipeline"
                     :properties {:name "Main Pipeline"
                                  :source-type :kudos
                                  :chunk-strategy :semantic
                                  :rerank-enabled true}
                     :master-key "my-master-key"})

  ;; Get a pipeline
  (get-pipeline @(config-db/get-conn) "ka" "prod" "main-pipeline" "my-master-key")

  ;; List pipelines
  (list-pipelines @(config-db/get-conn) "ka" "prod")

  ;; Update a pipeline
  (update-pipeline! (config-db/get-conn)
                    {:tenant "ka"
                     :environment "prod"
                     :pipeline-name "main-pipeline"
                     :properties {:rerank-enabled false}
                     :master-key "my-master-key"})

  ;; Duplicate a pipeline
  (duplicate-pipeline! (config-db/get-conn)
                       {:tenant "ka"
                        :environment "prod"
                        :source-pipeline-name "main-pipeline"
                        :new-pipeline-name "backup-pipeline"
                        :master-key "my-master-key"})

  ;; Soft delete a pipeline
  (soft-delete-pipeline! (config-db/get-conn) "ka" "prod" "main-pipeline"))

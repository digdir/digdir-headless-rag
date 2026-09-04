(ns digdir.pipeline.core
  "Pipeline CRUD operations.

   Pipelines unify data loading and query-time configuration for RAG.
   Uses the same config system as pipelines, with pipeline.* namespaced properties."
  (:require
            [clojure.string :as str]
            [digdir.config.db :as config-db]
            [digdir.pipeline.model :as model]))

;; =============================================================================
;; Pipeline ID Generation
;; =============================================================================

(defn make-pipeline-id
  "Generate a pipeline ID from tenant, tenant-config-key, and pipeline name.

   Format: tenant:tenant-config-key:pipeline-name
   Examples:
   - 'ka:default:main-pipeline'
  - 'altinn:prod:website-pipeline'
  - '_:_:default-pipeline' (global default)"
  [tenant tenant-config-key pipeline-name]
  (model/make-pipeline-id tenant tenant-config-key pipeline-name))

(defn parse-pipeline-id
  "Parse a pipeline ID into its components.

   Returns: {:tenant string-or-nil, :tenant-config-key string-or-nil, :pipeline-name string}"
  [pipeline-id]
  (model/parse-pipeline-id pipeline-id))

;; =============================================================================
;; Pipeline Operations
;; =============================================================================

(def source-specific-properties model/source-specific-properties)
(def source-required-properties model/source-required-properties)
(def source-shared-properties model/source-shared-properties)
(def all-source-types model/all-source-types)
(def all-source-specific-properties model/all-source-specific-properties)

(defn infer-source-type
  "Infer source type from present source-specific properties.

   Returns the unique matching source type when inference is unambiguous,
   otherwise nil."
  [properties]
  (model/infer-source-type properties))

(defn validate-source-config!
  "Validate source-specific pipeline properties.
   - On create, source type is required and required source fields must be present.
   - On update, source type is optional unless source-specific fields are being changed."
  [properties {:keys [require-source-type?] :or {require-source-type? false}}]
  (model/validate-source-config! properties {:require-source-type? require-source-type?}))

(defn get-dataset
  "Get dataset configuration by ID.
   Resolves all dataset/materialization properties through the Dataset V2 tree.

   Args:
     db - Datahike database value
     tenant - Tenant identifier
     tenant-config-key - Node tenant-config-key (replaces environment)
     pipeline-name - Dataset/Pipeline name
     master-key - Encryption key

   Returns: Map with all dataset properties, or nil if not found"
  [db tenant tenant-config-key pipeline-name master-key]
  (when-let [dataset-config (config-db/get-dataset db tenant tenant-config-key pipeline-name master-key)]
    (assoc dataset-config
           :id (make-pipeline-id tenant tenant-config-key pipeline-name)
           :tenant tenant
           :tenant-config-key tenant-config-key
           :pipeline-name pipeline-name)))

(defn- dataset-materialization-tenant-config-key
  [tenant-config-key pipeline-name]
  (config-db/default-dataset-tenant-config-key tenant-config-key pipeline-name))

(defn- ensure-pipeline-property-definitions!
  [conn properties]
  (doseq [[prop value] properties]
    (when value
      (when-let [path (config-db/pipeline-property-path prop)]
        (let [is-prompt? (str/starts-with? path "pipeline.generate.prompt.")]
          (when-not (config-db/get-definition @conn path)
            (config-db/upsert-definition! conn
                                          {:path path
                                           :root :dataset
                                           :value-type (cond
                                                         (boolean? value) :boolean
                                                         (number? value) :number
                                                         (keyword? value) :edn
                                                         (or (map? value) (vector? value)) :edn
                                                         :else :string)
                                           :encrypted? false
                                           :multiline? is-prompt?
                                           :description (str "Pipeline property: " (name prop))
                                           :category :pipelines
                                           :service :llm
                                           :sensitivity :internal
                                           :function (if is-prompt? :prompts :settings)}))
          (config-db/ensure-definition-root! conn path :dataset))))))

(defn create-dataset!
  "Create a durable parent dataset.

   Accepts an optional explicit dataset ID. If omitted, a new opaque ID is generated."
  [conn {:keys [dataset-id name description enabled?]
         :or {enabled? true}}]
  (config-db/create-dataset! conn (cond-> {:name name
                                           :enabled? enabled?}
                                    dataset-id (assoc :dataset-id dataset-id)
                                    (some? description) (assoc :description description))))

(defn update-dataset!
  "Update mutable dataset attributes on the durable parent dataset record."
  [conn opts]
  (config-db/update-dataset! conn opts))

(defn get-dataset-record
  "Get a durable parent dataset record by ID."
  [db dataset-id]
  (config-db/get-dataset-record db dataset-id))

(defn list-dataset-records
  "List durable parent dataset records."
  [db]
  (config-db/list-dataset-records db))

(defn- resolve-pipeline-dataset-id
  [db {:keys [dataset-id pipeline-name]}]
  (cond
    dataset-id
    (do
      (or (config-db/get-dataset-record db dataset-id)
          (throw (ex-info "Dataset not found"
                          {:status 404
                           :dataset-id dataset-id
                           :pipeline-name pipeline-name})))
      dataset-id)

    :else
    (if-let [existing-pipeline (config-db/get-dataset-pipeline db pipeline-name)]
      (get-in existing-pipeline [:dataset.pipeline/dataset :dataset/id])
      (throw (ex-info "dataset-id is required when creating a pipeline"
                      {:status 400
                       :pipeline-name pipeline-name})))))

(defn- ensure-pipeline-records!
  [conn dataset-id pipeline-name _properties]
  (let [db @conn]
    (or (config-db/get-dataset-record db dataset-id)
        (throw (ex-info "Dataset not found"
                        {:status 404
                         :dataset-id dataset-id
                         :pipeline-name pipeline-name})))
    (if-let [_dataset-pipeline (config-db/get-dataset-pipeline db pipeline-name)]
      (config-db/update-dataset-pipeline! conn {:pipeline-id pipeline-name
                                                :dataset-id dataset-id
                                                :enabled? true})
      (config-db/create-dataset-pipeline! conn {:pipeline-id pipeline-name
                                                :dataset-id dataset-id}))))

(defn- ensure-dataset-tree!
  [conn {:keys [tenant tenant-config-key dataset-id pipeline-name properties master-key]}]
  (when (str/blank? tenant)
    (throw (ex-info "Pipeline CRUD now requires a tenant-local dataset tree"
                    {:pipeline-name pipeline-name
                     :tenant-config-key tenant-config-key})))
  (let [dataset-id (resolve-pipeline-dataset-id @conn {:dataset-id dataset-id
                                                       :pipeline-name pipeline-name})]
    (ensure-pipeline-property-definitions! conn properties)
    (ensure-pipeline-records! conn dataset-id pipeline-name properties)
    (let [db @conn
          ;; ⚠️ #509: ONE BASE NODE PER DATASET, KEYED BY DATASET-ID.
          ;;
          ;; This lookup used to be `(tenant, :dataset, "default")` — tenant-scoped,
          ;; not dataset-scoped — so a tenant's SECOND dataset found the FIRST one's
          ;; base node and quietly hung its materialization tree there. Nothing threw.
          ;; `pipeline.storage.*` values live on the base node, so the second dataset
          ;; then RESOLVED the first one's collections and a query against it searched
          ;; the wrong corpus: confident answers from another dataset's documents, with
          ;; no error anywhere. Latent only because every tenant we ship has one
          ;; dataset — `test-multi-tenant-pipelines` has exercised a two-dataset tenant
          ;; the whole time.
          base-node-id (config-db/dataset-base-node-id tenant dataset-id)
          existing-base (config-db/get-config-node db base-node-id)
          ;; ⚠️ AND WHY THE FIRST DATASET KEEPS THE LITERAL "default".
          ;; `create-config-node!` enforces (tenant, root, tenant-config-key)
          ;; uniqueness, so the key cannot simply be "default" for every dataset. It
          ;; also cannot be the dataset-id for EVERY dataset: a sweep for `:dataset`
          ;; + "default" found four paths that resolve a tenant's dataset ROOT by that
          ;; exact key — `api/routes/datasets.clj` (twice, including
          ;; `authorize-dataset-materialization-request!`, which 404s without it),
          ;; `config/ops/ownership.clj`'s pin-all-globals, and `config/db.clj`'s
          ;; `tenant-root-node!`. Keying every dataset by its id would break those for
          ;; any tenant created after this change, including its FIRST dataset.
          ;;
          ;; So: the tenant's first dataset remains the dataset root under "default",
          ;; and subsequent datasets are keyed by their own dataset-id — which is
          ;; exactly the key `resolve-dataset-runtime-node!` already looks them up by.
          ;; Existing nodes are untouched.
          tenant-has-dataset-root? (some? (config-db/get-config-node-by-tenant-config-key
                                           db tenant :dataset "default"))
          base-tenant-config-key (if tenant-has-dataset-root? dataset-id "default")
          materialization-node-id (config-db/dataset-materialization-node-id tenant tenant-config-key dataset-id pipeline-name)
          materialization-tenant-config-key (dataset-materialization-tenant-config-key tenant-config-key pipeline-name)]
      (when-not existing-base
        (config-db/create-config-node! conn {:root :dataset
                                             :tenant tenant
                                             :node-id base-node-id
                                             :label "Default"
                                             :tenant-config-key base-tenant-config-key}))
      (if-let [leaf (config-db/get-config-node db materialization-node-id)]
        (do
          (config-db/update-config-node! conn {:node-id materialization-node-id
                                               :label (str pipeline-name " Materialization")
                                               :tenant-config-key materialization-tenant-config-key
                                               :enabled? true})
          (when-not (= base-node-id (get-in leaf [:config.node/parent :config.node/id]))
            (config-db/set-config-node-parent! conn {:node-id materialization-node-id
                                                     :parent-id base-node-id})))
        (config-db/create-config-node! conn {:root :dataset
                                             :tenant tenant
                                             :node-id materialization-node-id
                                             :label (str pipeline-name " Materialization")
                                             :tenant-config-key materialization-tenant-config-key
                                             :parent-id base-node-id
                                             :enabled? true}))
      (doseq [[prop value] properties]
        (when (some? value)
          (when-let [path (config-db/pipeline-property-path prop)]
            (config-db/set-node-value! conn {:root :dataset
                                             :tenant tenant
                                             :node-id materialization-node-id
                                             :path path
                                             :value value
                                             :master-key master-key}))))
      materialization-node-id)))

(defn- upsert-pipeline-properties!
  "Persist provided pipeline properties through the Dataset V2 tree."
  [conn opts]
  (ensure-dataset-tree! conn opts)
  (:pipeline-name opts))

(defn create-pipeline!
  "Create a new pipeline with the given properties.

   Args:
     conn - Datahike connection
     opts - Map with:
       :tenant - Tenant identifier
       :tenant-config-key - Node tenant-config-key (e.g. 'prod', 'dev')
       :pipeline-name - Unique pipeline name within tenant/env
       :properties - Map of property keyword to value (e.g., {:name \"Main Pipeline\" :source-type :kudos})
       :master-key - Encryption key

   Persists durable dataset/pipeline records plus tenant-local dataset-tree values."
  [conn {:keys [tenant tenant-config-key pipeline-name properties _master-key] :as opts}]
  (validate-source-config! properties {:require-source-type? false})
  (upsert-pipeline-properties! conn opts)
  (make-pipeline-id tenant tenant-config-key pipeline-name))

(defn update-pipeline!
  "Update pipeline properties. Only updates provided properties.

   Args:
     conn - Datahike connection
     opts - Map with:
       :tenant - Tenant identifier
       :tenant-config-key - Node tenant-config-key (e.g. 'prod', 'dev')
       :pipeline-name - Pipeline name
       :properties - Map of properties to update
       :master-key - Encryption key"
  [conn {:keys [tenant tenant-config-key pipeline-name properties _master-key] :as opts}]
  (validate-source-config! properties {:require-source-type? false})
  (upsert-pipeline-properties! conn opts)
  (make-pipeline-id tenant tenant-config-key pipeline-name))

(defn delete-pipeline!
  "Disable a pipeline in the Dataset V2 model."
  [conn tenant tenant-config-key pipeline-name]
  (when-let [dataset-pipeline (config-db/get-dataset-pipeline @conn pipeline-name)]
    (let [dataset-id (get-in dataset-pipeline [:dataset.pipeline/dataset :dataset/id])]
      (config-db/update-dataset-pipeline! conn {:pipeline-id pipeline-name
                                                :enabled? false})
      (when-let [selected-node (config-db/get-config-node @conn
                                                          (config-db/dataset-materialization-node-id tenant
                                                                                                     tenant-config-key
                                                                                                     dataset-id
                                                                                                     pipeline-name))]
      (config-db/update-config-node! conn {:node-id (:config.node/id selected-node)
                                           :enabled? false}))
      1)))

(defn soft-delete-pipeline!
  "Soft-delete a pipeline by disabling its V2 durable record and selected node."
  [conn tenant tenant-config-key pipeline-name]
  (delete-pipeline! conn tenant tenant-config-key pipeline-name))

(defn list-datasets
  "List enabled datasets through the Dataset V2 model."
  [db tenant tenant-config-key]
  (config-db/list-datasets db tenant tenant-config-key))

(defn list-all-pipelines
  "Get all active (non-deleted) pipeline names across all tenants and tenant-config-keys.
   Returns a sorted vector of unique pipeline names."
  [db]
  (list-datasets db nil nil))

(defn duplicate-pipeline!
  "Duplicate an existing pipeline with a new name.
   Copies all pipeline.* properties from source pipeline to new pipeline.

   Args:
     conn - Datahike connection
     opts - Map with:
       :tenant - Tenant identifier
       :tenant-config-key - Node tenant-config-key
       :source-pipeline-name - Pipeline to copy from
       :new-pipeline-name - New pipeline name
       :master-key - Encryption key

   Returns: new pipeline ID"
  [conn {:keys [tenant tenant-config-key source-pipeline-name new-pipeline-name master-key]}]
  (let [db @conn
        ;; Get source pipeline's resolved properties
        source-props (get-dataset db tenant tenant-config-key source-pipeline-name master-key)
        ;; Remove metadata fields
        props-to-copy (dissoc source-props
                              :id :tenant :tenant-config-key :pipeline-name
                              :dataset-id :dataset-node-id)]
    (when (empty? props-to-copy)
      (throw (ex-info "Source pipeline not found or has no properties"
                      {:tenant tenant
                       :tenant-config-key tenant-config-key
                       :source-pipeline-name source-pipeline-name})))
    (create-pipeline! conn {:tenant tenant
                            :tenant-config-key tenant-config-key
                            :dataset-id (:dataset-id source-props)
                            :pipeline-name new-pipeline-name
                            :properties props-to-copy
                            :master-key master-key})
    (make-pipeline-id tenant tenant-config-key new-pipeline-name)))

(comment
  ;; Usage examples:

  ;; Create a pipeline
  (create-pipeline! (config-db/get-conn)
                    {:tenant "ka"
                     :tenant-config-key "prod"
                     :pipeline-name "main-pipeline"
                     :properties {:name "Main Pipeline"
                                  :source-type :kudos
                                  :chunk-strategy :semantic
                                  :rerank-enabled true}
                     :master-key "my-master-key"})

  ;; Get a pipeline
  (get-dataset @(config-db/get-conn) "ka" "prod" "main-pipeline" "my-master-key")


  ;; Update a pipeline
  (update-pipeline! (config-db/get-conn)
                    {:tenant "ka"
                     :tenant-config-key "prod"
                     :pipeline-name "main-pipeline"
                     :properties {:rerank-enabled false}
                     :master-key "my-master-key"})

  ;; Duplicate a pipeline
  (duplicate-pipeline! (config-db/get-conn)
                       {:tenant "ka"
                        :tenant-config-key "prod"
                        :source-pipeline-name "main-pipeline"
                        :new-pipeline-name "backup-pipeline"
                        :master-key "my-master-key"})

  ;; Soft delete a pipeline
  (soft-delete-pipeline! (config-db/get-conn) "ka" "prod" "main-pipeline"))

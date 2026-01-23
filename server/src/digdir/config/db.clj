(ns digdir.config.db
  "Database operations for configuration management.

   Provides CRUD operations for config definitions and values,
   with multi-dimensional resolution following 8-level precedence order.

   Resolution is based on dimension count (more specific = higher priority),
   with tiebreaker: entity > tenant > environment.

   Levels (least to most specific):
   1. Global (0 dimensions)
   2. Environment (1 dim)
   3. Tenant (1 dim, higher priority)
   4. Entity (1 dim, highest priority)
   5. Tenant+Environment (2 dims)
   6. Entity+Environment (2 dims)
   7. Entity+Tenant (2 dims, highest priority)
   8. Entity+Tenant+Environment (3 dims)"
  (:require [datahike.api :as d]
            [nano-id.core :refer [nano-id]]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [digdir.config.schema :as schema]
            [digdir.config.crypto :as crypto]
            [digdir.config.audit :as audit]))

;; =============================================================================
;; Connection Management
;; =============================================================================

(defonce ^:private !config-conn (atom nil))

(defn set-conn!
  "Set the Datahike connection for config operations."
  [conn]
  (reset! !config-conn conn))

(defn get-conn
  "Get the Datahike connection for config operations.
   Falls back to main app database if not explicitly set."
  []
  (or @!config-conn
      (try
        (require 'digdir.data.db)
        ((resolve 'digdir.data.db/get-conn))
        (catch Exception _ nil))))

(defn ensure-schema!
  "Ensure config schema is transacted to the database.
   Safe to call multiple times - Datahike ignores duplicate schema."
  [conn]
  (try
    (d/transact conn {:tx-data schema/config-migration-schema})
    (catch Exception e
      ;; Ignore "attribute already exists" errors
      (when-not (re-find #"already exists" (str (.getMessage e)))
        (throw e)))))

;; =============================================================================
;; Config ID Generation
;; =============================================================================

(defn make-config-id
  "Generate a config ID from tenant, environment, entity, and path.

   Format: tenant:env:entity:path
   Examples:
   - 'ka:prod:my-bot:services.azure-openai.api-key' (entity-specific)
   - 'ka:prod:_:services.azure-openai.api-key' (tenant+env, no entity)
   - 'ka:_:my-bot:services.azure-openai.api-key' (tenant+entity, no env)
   - '_:_:_:services.azure-openai.api-key' (global default)"
  [tenant environment entity path]
  (str (or tenant "_") ":" (or environment "_") ":" (or entity "_") ":" path))

(defn parse-config-id
  "Parse a config ID into its components.

   Returns: {:tenant string-or-nil, :environment string-or-nil, :entity string-or-nil, :path string}"
  [config-id]
  (let [[tenant env entity path] (str/split config-id #":" 4)]
    {:tenant (when (not= tenant "_") tenant)
     :environment (when (not= env "_") env)
     :entity (when (not= entity "_") entity)
     :path path}))

;; =============================================================================
;; Config Definition Operations
;; =============================================================================

(defn get-definition
  "Get a config definition by path."
  [db path]
  (d/q '[:find (pull ?e [*]) .
         :in $ ?path
         :where [?e :config-def/path ?path]]
       db path))

(defn get-all-definitions
  "Get all config definitions."
  [db]
  (d/q '[:find [(pull ?e [*]) ...]
         :where [?e :config-def/path]]
       db))

(defn get-definitions-by-category
  "Get config definitions filtered by category."
  [db category]
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?category
         :where
         [?e :config-def/path]
         [?e :config-def/category ?category]]
       db category))

(defn build-definition-tx-data
  "Build transaction data for a config definition without transacting.

   Used for batch operations. Returns a single tx-data map."
  [db {:keys [path value-type encrypted? multiline? description category
              service sensitivity function]
       :or {value-type :string
            encrypted? false
            multiline? false
            category :general
            service :other
            sensitivity :internal
            function :settings}}
   now]
  (let [existing (get-definition db path)]
    (cond-> {:config-def/path path
             :config-def/value-type value-type
             :config-def/encrypted? encrypted?
             :config-def/multiline? multiline?
             :config-def/category category
             :config-def/service service
             :config-def/sensitivity sensitivity
             :config-def/function function}
      (not existing) (assoc :config-def/created-at now)
      description (assoc :config-def/description description))))

(defn upsert-definition!
  "Create or update a config definition.

   Args:
     conn - Datahike connection
     definition - Map with keys:
       :path (required) - Config path
       :value-type - :string, :boolean, :number, :edn (default :string)
       :encrypted? - Whether values should be encrypted (default false)
       :multiline? - Whether to use multiline modal editor (default false)
       :description - Human-readable description
       :category - :services, :auth, :chat, :features, :i18n
       :service - ABAC: :llm, :search, :auth, :storage, :email, :other
       :sensitivity - ABAC: :public, :internal, :admin-only, :secret
       :function - ABAC: :prompts, :credentials, :settings, :features, :i18n"
  [conn {:keys [path value-type encrypted? multiline? description category
                service sensitivity function]
         :or {value-type :string
              encrypted? false
              multiline? false
              category :general
              service :other
              sensitivity :internal
              function :settings}}]
  (let [now (System/currentTimeMillis)
        tx-data (build-definition-tx-data @conn
                                          {:path path
                                           :value-type value-type
                                           :encrypted? encrypted?
                                           :multiline? multiline?
                                           :description description
                                           :category category
                                           :service service
                                           :sensitivity sensitivity
                                           :function function}
                                          now)]
    (d/transact conn {:tx-data [tx-data]})))

(defn upsert-definitions-batch!
  "Create or update multiple config definitions in a single transaction.

   Args:
     conn - Datahike connection
     definitions - Collection of definition maps (same format as upsert-definition!)

   Returns: Transaction result"
  [conn definitions]
  (when (seq definitions)
    (let [now (System/currentTimeMillis)
          db @conn
          tx-data (mapv #(build-definition-tx-data db % now) definitions)]
      (d/transact conn {:tx-data tx-data}))))

;; =============================================================================
;; Config Value Operations
;; =============================================================================

(defn get-value-entity
  "Get a config value entity by tenant, environment, entity, and path."
  [db tenant environment entity path]
  (let [config-id (make-config-id tenant environment entity path)]
    (d/q '[:find (pull ?e [* {:config/definition [*]}]) .
           :in $ ?id
           :where [?e :config/id ?id]]
         db config-id)))

(defn get-raw-value
  "Get the raw (possibly encrypted) value string."
  [db tenant environment entity path]
  (:config/value (get-value-entity db tenant environment entity path)))

(defn get-all-values-for-path
  "Get all config values for a given path across all tenant/env/entity combinations."
  [db path]
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?path
         :where
         [?def :config-def/path ?path]
         [?e :config/definition ?def]]
       db path))

;; =============================================================================
;; Layered Resolution
;; =============================================================================

(defn resolve-value
  "Resolve a config value following 8-level multi-dimensional precedence order.

   Resolution based on dimension count (more specific = higher priority),
   with tiebreaker: entity > tenant > environment.

   Order (most to least specific):
   1. Entity+Tenant+Environment (3 dims)
   2. Entity+Tenant (2 dims, highest priority at 2 dims)
   3. Entity+Environment (2 dims)
   4. Tenant+Environment (2 dims)
   5. Entity (1 dim, highest priority at 1 dim)
   6. Tenant (1 dim)
   7. Environment (1 dim)
   8. Global (0 dims)

   Returns the value entity or nil if not found at any level."
  [db tenant environment entity path]
  (let [resolution-order [[entity tenant environment]    ; 3 dims
                          [entity tenant nil]             ; 2 dims: entity+tenant
                          [entity nil environment]        ; 2 dims: entity+env
                          [nil tenant environment]        ; 2 dims: tenant+env
                          [entity nil nil]                ; 1 dim: entity
                          [nil tenant nil]                ; 1 dim: tenant
                          [nil nil environment]           ; 1 dim: env
                          [nil nil nil]]]                 ; 0 dims: global
    (some (fn [[ent t env]]
            (get-value-entity db t env ent path))
          resolution-order)))

(defn resolve-value-with-level
  "Like resolve-value but also returns which level matched.

   Returns: {:value entity, :level keyword} or nil if not found.
   Levels (from most to least specific):
     :entity-tenant-env, :entity-tenant, :entity-env, :tenant-env,
     :entity, :tenant, :environment, :global"
  [db tenant environment entity path]
  (let [levels [[:entity-tenant-env entity tenant environment]
                [:entity-tenant entity tenant nil]
                [:entity-env entity nil environment]
                [:tenant-env nil tenant environment]
                [:entity entity nil nil]
                [:tenant nil tenant nil]
                [:environment nil nil environment]
                [:global nil nil nil]]]
    (some (fn [[level ent t env]]
            (when-let [value-entity (get-value-entity db t env ent path)]
              {:value value-entity :level level}))
          levels)))

;; =============================================================================
;; Value Decoding
;; =============================================================================

(defn decode-value
  "Decode a stored config value based on its type.

   Args:
     raw-value - String value from database
     value-type - :string, :boolean, :number, :edn
     encrypted? - Whether value is encrypted
     master-key - Master key for decryption (required if encrypted)"
  [raw-value value-type encrypted? master-key]
  (let [decrypted (if encrypted?
                    (crypto/decrypt raw-value master-key)
                    raw-value)]
    (case value-type
      :string decrypted
      :boolean (= decrypted "true")
      :number (edn/read-string decrypted)
      :edn (edn/read-string decrypted)
      decrypted)))

(defn encode-value
  "Encode a value for storage based on its type.

   Args:
     value - The value to encode
     value-type - :string, :boolean, :number, :edn
     encrypted? - Whether to encrypt the value
     master-key - Master key for encryption (required if encrypted)"
  [value value-type encrypted? master-key]
  (let [string-val (case value-type
                     :string (str value)
                     :boolean (str value)
                     :number (str value)
                     :edn (pr-str value)
                     (str value))]
    (if encrypted?
      (crypto/encrypt string-val master-key)
      string-val)))

;; =============================================================================
;; CRUD Operations
;; =============================================================================

(defn set-value!
  "Set a config value.

   Args:
     conn - Datahike connection
     opts - Map with:
       :tenant - Tenant identifier (nil for global)
       :environment - Environment (nil for tenant default)
       :entity - Entity identifier (nil for non-entity-scoped)
       :path - Config path
       :value - The value to store
       :master-key - Encryption key (required if definition has encrypted?=true)
       :skip-audit? - If true, skip audit logging (for migrations)
       :user-email - User email for audit log
       :user-id - User ID for audit log
       :ip-address - IP address for audit log

   Returns:
     :created - if a new value was created
     :updated - if an existing value was changed
     :unchanged - if the value was already the same"
  [conn {:keys [tenant environment entity path value master-key
                skip-audit? user-email user-id ip-address]}]
  (let [db @conn
        definition (get-definition db path)
        _ (when-not definition
            (throw (ex-info "Config definition not found" {:path path})))
        value-type (:config-def/value-type definition)
        encrypted? (:config-def/encrypted? definition)
        encoded (encode-value value value-type encrypted? master-key)
        config-id (make-config-id tenant environment entity path)
        now (System/currentTimeMillis)
        existing (get-value-entity db tenant environment entity path)
        existing-raw (:config/value existing)

        ;; Check if value actually changed
        value-changed? (or (nil? existing)
                           (not= existing-raw encoded))

        action (cond
                 (nil? existing) :created
                 value-changed? :updated
                 :else :unchanged)]

    (when value-changed?
      ;; Only transact if value changed
      (let [tx-data (cond-> {:config/id config-id
                             :config/definition [:config-def/path path]
                             :config/value encoded
                             :config/updated-at now}
                      (not existing) (assoc :config/created-at now)
                      tenant (assoc :config/tenant tenant)
                      environment (assoc :config/environment environment)
                      entity (assoc :config/entity entity))]
        (d/transact conn {:tx-data [tx-data]})

        ;; Log to audit (unless skipped)
        (when-not skip-audit?
          (audit/log-change! conn
                             {:path path
                              :tenant tenant
                              :environment environment
                              :action (if existing :update :create)
                              :previous-value existing-raw
                              :new-value value
                              :encrypted? encrypted?
                              :user-email user-email
                              :user-id user-id
                              :ip-address ip-address}))))

    action))

(defn delete-value!
  "Delete a config value."
  [conn tenant environment entity path]
  (let [config-id (make-config-id tenant environment entity path)]
    (d/transact conn {:tx-data [[:db/retractEntity [:config/id config-id]]]})))

(defn build-value-tx-data
  "Build transaction data for a config value without transacting.

   Used for batch operations. Returns tx-data map or nil if unchanged.

   Args:
     db - Datahike database value
     opts - Map with:
       :tenant, :environment, :entity, :path, :value, :master-key

   Returns: {:tx-data map :action keyword} or nil if value unchanged"
  [db {:keys [tenant environment entity path value master-key]}]
  (let [definition (get-definition db path)
        _ (when-not definition
            (throw (ex-info "Config definition not found" {:path path})))
        value-type (:config-def/value-type definition)
        encrypted? (:config-def/encrypted? definition)
        encoded (encode-value value value-type encrypted? master-key)
        config-id (make-config-id tenant environment entity path)
        now (System/currentTimeMillis)
        existing (get-value-entity db tenant environment entity path)
        existing-raw (:config/value existing)
        value-changed? (or (nil? existing)
                           (not= existing-raw encoded))]
    (when value-changed?
      {:tx-data (cond-> {:config/id config-id
                         :config/definition [:config-def/path path]
                         :config/value encoded
                         :config/updated-at now}
                  (not existing) (assoc :config/created-at now)
                  tenant (assoc :config/tenant tenant)
                  environment (assoc :config/environment environment)
                  entity (assoc :config/entity entity))
       :action (if existing :updated :created)})))

(defn set-values-batch!
  "Set multiple config values in a single transaction.

   Args:
     conn - Datahike connection
     values - Collection of value maps (same format as set-value!)
     opts - Options:
       :skip-audit? - If true, skip audit logging (default true for batch)

   Returns: Map with :created, :updated, :unchanged counts"
  [conn values & [{:keys [skip-audit?] :or {skip-audit? true}}]]
  (when (seq values)
    (let [db @conn
          results (keep #(build-value-tx-data db %) values)
          tx-data (mapv :tx-data results)
          created (count (filter #(= :created (:action %)) results))
          updated (count (filter #(= :updated (:action %)) results))
          unchanged (- (count values) (count results))]
      (when (seq tx-data)
        (d/transact conn {:tx-data tx-data}))
      {:created created
       :updated updated
       :unchanged unchanged})))

;; =============================================================================
;; Bulk Operations
;; =============================================================================

(defn load-resolved-config
  "Load all config for a tenant+environment+entity into a nested map.

   This resolves each config path using the 6-level layered resolution,
   decrypts values as needed, and builds a nested map structure
   compatible with the existing config access patterns.

   Args:
     db - Datahike database value
     tenant - Current tenant
     environment - Current environment
     entity - Current entity (nil for non-entity-scoped resolution)
     master-key - Master key for decryption

   Returns: Nested config map like {:services {:azure-openai {:api-key \"...\"}}}}"
  [db tenant environment entity master-key]
  (let [all-defs (get-all-definitions db)]
    (reduce
     (fn [acc definition]
       (let [path (:config-def/path definition)
             value-type (:config-def/value-type definition)
             encrypted? (:config-def/encrypted? definition)
             resolved (resolve-value db tenant environment entity path)]
         (if resolved
           (let [raw-value (:config/value resolved)
                 decoded (decode-value raw-value value-type encrypted? master-key)
                 path-keys (mapv keyword (str/split path #"\."))]
             (assoc-in acc path-keys decoded))
           acc)))
     {}
     all-defs)))

(defn list-configs-by-category
  "List config values for a category, with resolved values.

   Returns a list of maps with definition info and resolved values."
  [db tenant environment entity category]
  (let [definitions (get-definitions-by-category db category)]
    (for [definition definitions]
      (let [path (:config-def/path definition)
            resolved (resolve-value-with-level db tenant environment entity path)]
        (merge definition
               {:resolved-value (:value resolved)
                :resolution-level (:level resolved)})))))

;; =============================================================================
;; Inheritance Table Data
;; =============================================================================

(defn get-values-at-all-levels
  "Get config values at each hierarchy level for a path.
   Returns values at EXACT levels (not resolved), keyed by level descriptor.

   Level keys (using multi-dimensional model):
   - :global                                          ; 0 dims
   - [:env \"prod\"]                                  ; 1 dim
   - [:tenant \"ka\"]                                 ; 1 dim
   - [:entity \"bot1\"]                               ; 1 dim (NEW)
   - [:tenant-env \"ka\" \"prod\"]                    ; 2 dims
   - [:entity-env \"bot1\" \"prod\"]                  ; 2 dims (NEW)
   - [:entity-tenant \"bot1\" \"ka\"]                 ; 2 dims (NEW naming)
   - [:entity-tenant-env \"bot1\" \"ka\" \"prod\"]    ; 3 dims (NEW naming)

   Args:
     db - Datahike database value
     path - Config path
     selected-tenants - Set of tenant IDs to include
     selected-environments - Set of environments to include
     selected-entities - Set of entity-ids (flat set, multi-dimensional model)

   Returns: Map of level-key -> value-entity"
  [db path selected-tenants selected-environments selected-entities]
  (let [all-values (get-all-values-for-path db path)]
    (reduce
     (fn [acc value-entity]
       (let [tenant (:config/tenant value-entity)
             env (:config/environment value-entity)
             entity (:config/entity value-entity)
             ;; Determine level key based on which dimensions are present
             ;; Order: entity > tenant > environment in key naming
             level-key (cond
                         (and entity tenant env) [:entity-tenant-env entity tenant env]
                         (and entity tenant) [:entity-tenant entity tenant]
                         (and entity env) [:entity-env entity env]
                         (and tenant env) [:tenant-env tenant env]
                         entity [:entity entity]
                         tenant [:tenant tenant]
                         env [:env env]
                         :else :global)
             ;; Check if this level should be included based on selections
             ;; selected-entities is now a flat set of entity IDs
             include? (case (if (keyword? level-key) level-key (first level-key))
                        :global true
                        :env (or (empty? selected-environments)
                                 (contains? selected-environments env))
                        :tenant (or (empty? selected-tenants)
                                    (contains? selected-tenants tenant))
                        :entity (or (empty? selected-entities)
                                    (contains? selected-entities entity))
                        :tenant-env (and (or (empty? selected-tenants)
                                             (contains? selected-tenants tenant))
                                         (or (empty? selected-environments)
                                             (contains? selected-environments env)))
                        :entity-env (and (or (empty? selected-entities)
                                             (contains? selected-entities entity))
                                         (or (empty? selected-environments)
                                             (contains? selected-environments env)))
                        :entity-tenant (and (or (empty? selected-tenants)
                                                (contains? selected-tenants tenant))
                                            (or (empty? selected-entities)
                                                (contains? selected-entities entity)))
                        :entity-tenant-env (and (or (empty? selected-tenants)
                                                    (contains? selected-tenants tenant))
                                                (or (empty? selected-environments)
                                                    (contains? selected-environments env))
                                                (or (empty? selected-entities)
                                                    (contains? selected-entities entity))))]
         (if include?
           (assoc acc level-key value-entity)
           acc)))
     {}
     all-values)))

(defn get-inheritance-table-data
  "Get all config data organized for the inheritance table UI.

   Args:
     db - Datahike database value
     selected-tenants - Set of tenant IDs
     selected-environments - Set of environments
     selected-entities - Map of {tenant -> set of entity-ids}

   Returns:
     {:definitions [...all config definitions...]
      :path-values {path {level-key value-entity ...} ...}
      :categories [...unique categories...]}"
  [db selected-tenants selected-environments selected-entities]
  (let [all-defs (get-all-definitions db)
        path-values (reduce
                     (fn [acc def]
                       (let [path (:config-def/path def)
                             values (get-values-at-all-levels db path
                                                              selected-tenants
                                                              selected-environments
                                                              selected-entities)]
                         (assoc acc path values)))
                     {}
                     all-defs)
        categories (vec (distinct (keep :config-def/category all-defs)))]
    {:definitions (vec all-defs)
     :path-values path-values
     :categories categories}))

;; =============================================================================
;; Entity Operations (Scope-Based Design)
;; =============================================================================

;; Entity property mapping: keyword -> database path
;; Organized by functional area with kebab-case naming
(def entity-property-to-path
  "Maps entity property keywords to their database paths.
   Organized by functional area:
   - ui.* - Display/identity properties
   - retrieval.collection.* - Typesense collection names
   - retrieval.rerank.* - ColBERT reranking parameters
   - retrieval.context.* - LLM context window parameters
   - generate.prompt.* - Prompt templates"
  {;; UI/Identity
   :name                      "ui.name"
   :image                     "ui.image"
   ;; Collections
   :docs-collection           "retrieval.collection.docs"
   :chunks-collection         "retrieval.collection.chunks"
   :phrases-collection        "retrieval.collection.phrases"
   ;; Rerank
   :rerank-enabled            "retrieval.rerank.enabled"
   :rerank-top-k              "retrieval.rerank.top-k"
   :rerank-max-chunk-length   "retrieval.rerank.max-chunk-length"
   :rerank-max-total-length   "retrieval.rerank.max-total-length"
   ;; Context
   :context-top-k             "retrieval.context.top-k"
   :context-max-docs          "retrieval.context.max-docs"
   :context-max-chunk-length  "retrieval.context.max-chunk-length"
   :context-max-total-length  "retrieval.context.max-total-length"
   ;; Prompts
   :phrase-gen-prompt         "generate.prompt.phrase-gen"
   :prompt-query-relax        "generate.prompt.query-relax"
   :prompt-rag-generate       "generate.prompt.rag-generate"})

;; Reverse mapping: database path -> keyword
(def path-to-entity-property
  "Reverse mapping from database paths to entity property keywords."
  (into {} (map (fn [[k v]] [v k]) entity-property-to-path)))

(def entity-property-paths
  "Config paths for entity properties.
   These paths are used with entity scope (entity UUID in scope field, not path).
   Enables full 6-level inheritance for entity properties."
  (set (vals entity-property-to-path)))

(def entity-properties
  "Standard properties that entities can have (as keywords)."
  (set (keys entity-property-to-path)))

(defn entity-property-path
  "Get the config path for an entity property.
   Example: (entity-property-path :name) => \"ui.name\"
            (entity-property-path :docs-collection) => \"retrieval.collection.docs\""
  [property]
  (get entity-property-to-path property))

(defn list-tenants
  "Get all registered tenants from the config database.
   Returns a sorted vector of tenant ID strings."
  [db]
  (let [tenants (d/q '[:find [?id ...]
                       :where
                       [?e :tenant/id ?id]]
                     db)]
    (-> tenants sort vec)))

(defn get-tenant
  "Get a tenant entity by ID.
   Returns map with :tenant/id, :tenant/name, :tenant/created-at, :tenant/created-by
   or nil if not found."
  [db tenant-id]
  (d/q '[:find (pull ?e [:tenant/id :tenant/name :tenant/created-at :tenant/created-by]) .
         :in $ ?id
         :where [?e :tenant/id ?id]]
       db tenant-id))

(defn get-tenant-name
  "Get the display name for a tenant. Returns the name or the ID if no name is set."
  [db tenant-id]
  (or (d/q '[:find ?name .
             :in $ ?id
             :where
             [?e :tenant/id ?id]
             [?e :tenant/name ?name]]
           db tenant-id)
      tenant-id))

(defn get-all-tenant-names
  "Get a map of tenant-id -> display name for all tenants."
  [db]
  (into {}
        (d/q '[:find ?id ?name
               :where
               [?e :tenant/id ?id]
               [?e :tenant/name ?name]]
             db)))

(defn register-tenant!
  "Register a new tenant in the config database.
   Creates a tenant entity with the given ID and optional name.
   This is idempotent - calling it multiple times for the same tenant is safe.

   Args:
     conn - Datahike connection
     tenant-id - Unique tenant identifier (stable, used in config values)
     opts - Optional map with:
       :name - Display name (defaults to tenant-id)
       :created-by - User ID who created the tenant"
  ([conn tenant-id]
   (register-tenant! conn tenant-id {}))
  ([conn tenant-id {:keys [name created-by] :or {created-by "system"}}]
   (let [db @conn]
     ;; Only create if not already registered
     (when-not (get-tenant db tenant-id)
       (d/transact conn [{:tenant/id tenant-id
                          :tenant/name (or name tenant-id)
                          :tenant/created-at (java.util.Date.)
                          :tenant/created-by created-by}])))))

(defn rename-tenant!
  "Rename a tenant (change its display name).
   The tenant ID remains stable - only the display name changes.
   This does not affect any config values referencing the tenant.

   Args:
     conn - Datahike connection
     tenant-id - The tenant's stable ID
     new-name - The new display name

   Returns true if renamed, false if tenant not found."
  [conn tenant-id new-name]
  (let [db @conn
        tenant-eid (d/q '[:find ?e .
                          :in $ ?id
                          :where [?e :tenant/id ?id]]
                        db tenant-id)]
    (if tenant-eid
      (do
        (d/transact conn [{:db/id tenant-eid
                           :tenant/name new-name}])
        true)
      false)))

(defn list-entities
  "Get all active (non-deleted) entity IDs for a tenant.
   Queries for distinct entity values in :config/entity field,
   excluding any entries that have been soft-deleted."
  [db tenant]
  (let [entities (if tenant
                   ;; Query entities for specific tenant (excluding deleted)
                   (d/q '[:find [?entity ...]
                          :in $ ?tenant
                          :where
                          [?e :config/tenant ?tenant]
                          [?e :config/entity ?entity]
                          [(some? ?entity)]
                          (not [?e :config/deleted-at])]
                        db tenant)
                   ;; Query all entities across all tenants (excluding deleted)
                   (d/q '[:find [?entity ...]
                          :where
                          [?e :config/entity ?entity]
                          [(some? ?entity)]
                          (not [?e :config/deleted-at])]
                        db))]
    (-> entities distinct sort vec)))

(defn list-all-entities
  "Get all active (non-deleted) entity IDs across all tenants.
   Returns a sorted vector of unique entity IDs."
  [db]
  (list-entities db nil))

(defn get-entities-by-tenant
  "Get all entities grouped by tenant for the entity selector.

   Args:
     db - Datahike database value
     tenants - Collection of tenant IDs

   Returns: Map of {tenant -> [entity-ids]}"
  [db tenants]
  (reduce
   (fn [acc tenant]
     (assoc acc tenant (list-entities db tenant)))
   {}
   tenants))

(defn get-entity-names
  "Get a map of entity-id -> name for entities, using proper inheritance resolution.

   Resolves the ui.name config value for each entity in the given tenant,
   following the 6-level inheritance chain (entity -> tenant -> global).

   Args:
     db - Datahike database value
     tenant - Tenant identifier
     entity-ids - Collection of entity IDs to resolve names for

   Returns: Map of {entity-id -> name}"
  [db tenant entity-ids]
  (reduce
   (fn [acc entity-id]
     (if-let [resolved (resolve-value db tenant nil entity-id "ui.name")]
       (assoc acc entity-id (:config/value resolved))
       acc))
   {}
   entity-ids))

(defn get-entity
  "Get entity configuration by ID.
   Resolves all entity properties using 6-level inheritance with entity scope."
  [db tenant environment entity-id master-key]
  (reduce
   (fn [acc prop-path]
     (let [definition (get-definition db prop-path)]
       (if definition
         ;; Resolve value WITH entity scope - enables full inheritance
         (let [resolved (resolve-value db tenant environment entity-id prop-path)]
           (if resolved
             (let [prop-name (get path-to-entity-property prop-path)
                   decoded (decode-value (:config/value resolved)
                                         (:config-def/value-type definition)
                                         (:config-def/encrypted? definition)
                                         master-key)]
               (assoc acc prop-name decoded))
             acc))
         acc)))
   {:id entity-id}
   entity-property-paths))

(defn create-entity!
  "Create a new entity with the given properties.

   Args:
     conn - Datahike connection
     opts - Map with:
       :tenant - Tenant identifier
       :environment - Environment (optional)
       :entity-id - Unique entity identifier
       :properties - Map of property name to value (e.g., {:name \"My Bot\" :image \"bot.png\"})
       :master-key - Encryption key

   Uses scope-based design with entity-id in the scope field.
   Paths use functional area prefixes (ui.*, retrieval.*, generate.*)."
  [conn {:keys [tenant environment entity-id properties master-key]}]
  (doseq [[prop value] properties]
    (when value
      (let [path (entity-property-path prop)
            ;; Prompts are under generate.prompt.* path
            is-prompt? (and path (str/starts-with? path "generate.prompt."))]
        ;; Ensure definition exists (generic, shared across all entities)
        (when (and path (not (get-definition @conn path)))
          (upsert-definition! conn
                              {:path path
                               :value-type (cond
                                             (boolean? value) :boolean
                                             (number? value) :number
                                             (or (map? value) (vector? value)) :edn
                                             :else :string)
                               :encrypted? false
                               :multiline? is-prompt?
                               :description (str "Entity property: " (name prop))
                               :category :entities
                               :service :llm
                               :sensitivity :internal
                               :function (if is-prompt? :prompts :settings)}))
        ;; Set the value WITH entity scope
        (when path
          (set-value! conn {:tenant tenant
                            :environment environment
                            :entity entity-id  ; <-- Entity in SCOPE field
                            :path path
                            :value value
                            :master-key master-key})))))
  entity-id)

(defn update-entity!
  "Update entity properties. Only updates provided properties."
  [conn {:keys [tenant environment entity-id properties master-key]}]
  (create-entity! conn {:tenant tenant
                        :environment environment
                        :entity-id entity-id
                        :properties properties
                        :master-key master-key}))

(defn delete-entity!
  "Delete all configs for an entity.
   Removes all entity.* values that have this entity-id in scope.
   Uses batch transaction for efficiency."
  [conn tenant entity-id]
  (let [db @conn
        ;; Find all config values for this entity scope
        entity-configs (d/q '[:find [(pull ?e [:config/id :db/id]) ...]
                              :in $ ?tenant ?entity-id
                              :where
                              [?e :config/tenant ?tenant]
                              [?e :config/entity ?entity-id]]
                            db tenant entity-id)]
    (when (seq entity-configs)
      (d/transact conn {:tx-data (mapv #(vector :db/retractEntity (:db/id %)) entity-configs)}))))

(defn soft-delete-entity!
  "Soft-delete an entity by setting :config/deleted-at on all its configs.
   Preserves data for audit purposes. Returns count of configs marked deleted."
  [conn tenant entity-id]
  (let [db @conn
        now (System/currentTimeMillis)
        ;; Find all config values for this entity scope that are not already deleted
        entity-configs (d/q '[:find [(pull ?e [:db/id :config/id]) ...]
                              :in $ ?tenant ?entity-id
                              :where
                              [?e :config/tenant ?tenant]
                              [?e :config/entity ?entity-id]
                              (not [?e :config/deleted-at])]
                            db tenant entity-id)]
    (when (seq entity-configs)
      (d/transact conn {:tx-data (mapv (fn [{eid :db/id}]
                                         {:db/id eid
                                          :config/deleted-at now})
                                       entity-configs)}))
    (count entity-configs)))

(defn duplicate-entity!
  "Duplicate an existing entity with a new ID.
   Copies all entity.* properties from source entity to new entity.

   Args:
     conn - Datahike connection
     opts - Map with:
       :tenant - Tenant identifier
       :environment - Environment (optional)
       :source-entity-id - Entity to copy from
       :new-entity-id - New entity identifier
       :master-key - Encryption key

   Returns: new entity ID"
  [conn {:keys [tenant environment source-entity-id new-entity-id master-key]}]
  (let [db @conn
        ;; Get source entity's resolved properties
        source-props (get-entity db tenant environment source-entity-id master-key)
        ;; Remove :id as we'll use the new entity ID
        props-to-copy (dissoc source-props :id)]
    (when (empty? props-to-copy)
      (throw (ex-info "Source entity not found or has no properties"
                      {:tenant tenant :source-entity-id source-entity-id})))
    (create-entity! conn {:tenant tenant
                          :environment environment
                          :entity-id new-entity-id
                          :properties props-to-copy
                          :master-key master-key})
    new-entity-id))

;; =============================================================================
;; Initialization
;; =============================================================================

(defn init-config-db!
  "Initialize the config database with schema and default permissions.

   Args:
     conn - Datahike connection
     seed-permissions? - Whether to seed default permissions (default true)
     sync-admins? - Whether to sync admin permissions from ADMIN_USER_EMAILS (default true)"
  [conn & {:keys [seed-permissions? sync-admins?]
           :or {seed-permissions? true sync-admins? true}}]
  (ensure-schema! conn)
  (when seed-permissions?
    (let [now (System/currentTimeMillis)]
      (doseq [perm schema/default-permissions]
        (try
          (d/transact conn {:tx-data [(assoc perm :permission/created-at now)]})
          (catch Exception e
            ;; Ignore "already exists" errors for idempotency
            (when-not (re-find #"unique constraint" (str (.getMessage e)))
              (throw e)))))))
  ;; Sync admin permissions from ADMIN_USER_EMAILS env var
  (when sync-admins?
    (try
      (require 'digdir.config.permissions)
      (when-let [sync-fn (resolve 'digdir.config.permissions/sync-admin-permissions!)]
        (sync-fn conn))
      (catch Exception e
        (println "Note: Could not sync admin permissions:" (.getMessage e))))))

(comment
  ;; Usage examples:

  ;; Set up a definition
  (upsert-definition! (get-conn)
                      {:path "services.azure-openai.api-key"
                       :value-type :string
                       :encrypted? true
                       :description "Azure OpenAI API key"
                       :category :services
                       :service :llm
                       :sensitivity :secret
                       :function :credentials})

  ;; Set a global value (no tenant, env, or entity)
  (set-value! (get-conn)
              {:tenant nil
               :environment nil
               :entity nil
               :path "services.azure-openai.api-key"
               :value "sk-abc123"
               :master-key "my-master-key"})

  ;; Set a tenant+env value
  (set-value! (get-conn)
              {:tenant "ka"
               :environment "prod"
               :entity nil
               :path "services.azure-openai.api-key"
               :value "sk-prod-key"
               :master-key "my-master-key"})

  ;; Set an entity-specific value
  (set-value! (get-conn)
              {:tenant "ka"
               :environment "prod"
               :entity "my-bot"
               :path "services.azure-openai.deployment-name"
               :value "gpt-4-turbo"
               :master-key "my-master-key"})

  ;; Resolve a value with entity context (uses 6-level resolution)
  (resolve-value @(get-conn) "ka" "prod" "my-bot" "services.azure-openai.api-key")

  ;; Resolve without entity (will not check entity-specific levels)
  (resolve-value @(get-conn) "ka" "prod" nil "services.azure-openai.api-key"))

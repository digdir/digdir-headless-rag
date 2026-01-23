(ns digdir.config.ops
  "Database operations for backup, restore, and clone functionality.

   Provides:
   - Export: Full database or tenant-scoped backups in JSON format
   - Import: Restore from backups with conflict resolution
   - Clone: Copy tenant or environment configurations

   All operations support dry-run mode for previewing changes."
  (:require [datahike.api :as d]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [digdir.config.db :as config-db]
            [digdir.config.crypto :as crypto])
  (:import [java.time Instant]
           [java.time.format DateTimeFormatter]))

;; =============================================================================
;; Constants
;; =============================================================================

(def ^:private export-version "1.0")

(def ^:private encryption-metadata
  {:method "aes-256-gcm"
   :key-derivation "pbkdf2-sha256"
   :iterations 100000})

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- iso-timestamp
  "Generate ISO-8601 timestamp string."
  []
  (.format DateTimeFormatter/ISO_INSTANT (Instant/now)))

(defn- clean-db-entity
  "Remove internal Datahike fields from an entity map."
  [entity]
  (dissoc entity :db/id))

(defn- keyword->string
  "Convert keyword to string for JSON serialization."
  [k]
  (if (keyword? k)
    (subs (str k) 1)  ; Remove leading colon
    k))

(defn- serialize-entity
  "Serialize an entity for JSON export.
   Converts keywords to strings for JSON compatibility."
  [entity]
  (reduce-kv
   (fn [acc k v]
     (let [str-key (keyword->string k)
           str-val (cond
                     (keyword? v) (keyword->string v)
                     (map? v) (serialize-entity v)
                     :else v)]
       (assoc acc str-key str-val)))
   {}
   entity))

;; =============================================================================
;; Export - Definitions
;; =============================================================================

(defn- export-definitions
  "Export all config definitions.

   Args:
     db - Datahike database value

   Returns: Vector of definition maps"
  [db]
  (->> (config-db/get-all-definitions db)
       (mapv (comp serialize-entity clean-db-entity))))

;; =============================================================================
;; Export - Values
;; =============================================================================

(defn- get-all-values
  "Get all config values from the database."
  [db]
  (d/q '[:find [(pull ?e [* {:config/definition [:config-def/path
                                                  :config-def/encrypted?
                                                  :config-def/value-type]}]) ...]
         :where [?e :config/id]]
       db))

(defn- get-tenant-values
  "Get all config values for a specific tenant."
  [db tenant]
  (d/q '[:find [(pull ?e [* {:config/definition [:config-def/path
                                                  :config-def/encrypted?
                                                  :config-def/value-type]}]) ...]
         :in $ ?tenant
         :where
         [?e :config/tenant ?tenant]]
       db tenant))

(defn- re-encrypt-value-for-export
  "Re-encrypt an encrypted value with the export password.

   Args:
     raw-value - The encrypted value from database
     master-key - Current master key
     export-password - Password to encrypt with for export

   Returns: Re-encrypted value string"
  [raw-value master-key export-password]
  (crypto/re-encrypt raw-value master-key export-password))

(defn- prepare-value-for-export
  "Prepare a config value for export.

   Handles re-encryption of secrets with export password."
  [value-entity master-key export-password]
  (let [definition (:config/definition value-entity)
        encrypted? (:config-def/encrypted? definition)
        raw-value (:config/value value-entity)
        ;; Re-encrypt if needed
        exported-value (if (and encrypted? raw-value master-key export-password)
                         (re-encrypt-value-for-export raw-value master-key export-password)
                         raw-value)
        ;; Clean up the entity for export
        export-entity (-> value-entity
                          (dissoc :config/definition)
                          (assoc :config/value exported-value)
                          clean-db-entity
                          serialize-entity)]
    ;; Add the path reference as a simple string
    (assoc export-entity "config/definition-path" (:config-def/path definition))))

(defn- export-values
  "Export config values, optionally filtered by tenant.

   Args:
     db - Datahike database value
     tenant - Tenant to filter by (nil for all)
     master-key - Master key for decryption
     export-password - Password to encrypt secrets with

   Returns: Vector of value maps"
  [db tenant master-key export-password]
  (let [values (if tenant
                 (get-tenant-values db tenant)
                 (get-all-values db))]
    (mapv #(prepare-value-for-export % master-key export-password) values)))

;; =============================================================================
;; Export - Audit
;; =============================================================================

(defn- get-all-audit
  "Get all audit records."
  [db]
  (d/q '[:find [(pull ?e [*]) ...]
         :where [?e :audit/id]]
       db))

(defn- get-tenant-audit
  "Get audit records for a specific tenant."
  [db tenant]
  (d/q '[:find [(pull ?e [*]) ...]
         :in $ ?tenant
         :where
         [?e :audit/tenant ?tenant]]
       db tenant))

(defn- export-audit
  "Export audit records, optionally filtered by tenant.

   Args:
     db - Datahike database value
     tenant - Tenant to filter by (nil for all)

   Returns: Vector of audit maps"
  [db tenant]
  (let [records (if tenant
                  (get-tenant-audit db tenant)
                  (get-all-audit db))]
    (->> records
         (mapv (fn [record]
                 (-> record
                     (dissoc :audit/config-def :audit/config-value)  ; Remove refs
                     clean-db-entity
                     serialize-entity)))
         (sort-by #(get % "audit/timestamp")))))

;; =============================================================================
;; Export - Main Functions
;; =============================================================================

(defn- build-export-preview
  "Build a preview of what would be exported."
  [db tenant include-audit?]
  (let [all-defs (config-db/get-all-definitions db)
        values (if tenant
                 (get-tenant-values db tenant)
                 (get-all-values db))
        audit-count (if include-audit?
                      (count (if tenant
                               (get-tenant-audit db tenant)
                               (get-all-audit db)))
                      0)]
    {:definitions {:count (count all-defs)}
     :values {:count (count values)
              :by-tenant (frequencies (map :config/tenant values))
              :encrypted-count (count (filter #(get-in % [:config/definition :config-def/encrypted?]) values))}
     :audit {:count audit-count}}))

(defn export-full
  "Export full database backup.

   Args:
     conn - Datahike connection
     opts - Map with:
       :master-key - Master key for decryption (required for encrypted values)
       :export-password - Password to encrypt secrets with (required for encrypted values)
       :include-audit? - Include audit history (default true)
       :dry-run? - Preview only, don't generate export (default false)

   Returns:
     If dry-run?: Preview map with counts
     Otherwise: Export data map ready for JSON serialization"
  [conn {:keys [master-key export-password include-audit? dry-run?]
         :or {include-audit? true dry-run? false}}]
  (let [db @conn]
    (if dry-run?
      (build-export-preview db nil include-audit?)
      {:version export-version
       :exported-at (iso-timestamp)
       :scope "full"
       :tenant nil
       :encryption encryption-metadata
       :data {:definitions (export-definitions db)
              :values (export-values db nil master-key export-password)
              :audit (when include-audit? (export-audit db nil))}})))

(defn export-tenant
  "Export tenant-scoped backup.

   Args:
     conn - Datahike connection
     tenant - Tenant identifier
     opts - Same as export-full

   Returns: Same as export-full, but scoped to tenant"
  [conn tenant {:keys [master-key export-password include-audit? dry-run?]
                :or {include-audit? true dry-run? false}}]
  (let [db @conn]
    (if dry-run?
      (build-export-preview db tenant include-audit?)
      {:version export-version
       :exported-at (iso-timestamp)
       :scope "tenant"
       :tenant tenant
       :encryption encryption-metadata
       :data {:definitions (export-definitions db)  ; Always include all definitions
              :values (export-values db tenant master-key export-password)
              :audit (when include-audit? (export-audit db tenant))}})))

;; =============================================================================
;; Import - Definitions
;; =============================================================================

(def ^:private definition-keyword-fields
  "Fields that should be converted to keywords when deserializing definitions."
  #{:config-def/value-type :config-def/category :config-def/service
    :config-def/sensitivity :config-def/function})

(defn- deserialize-definition
  "Deserialize a definition from JSON import.
   Converts namespaced keys (config-def/path) to simple keys (:path)
   as expected by upsert-definitions-batch!."
  [def-map]
  (reduce-kv
   (fn [acc k v]
     (let [;; Convert "config-def/path" or :config-def/path to :path
           key-str (name (if (keyword? k) k (keyword k)))
           ;; Strip "config-def/" prefix if present
           simple-key (keyword (str/replace key-str #"^config-def/" ""))
           ;; Convert enum fields to keywords
           kw-val (if (contains? definition-keyword-fields (keyword (str "config-def/" (name simple-key))))
                    (keyword v)
                    v)]
       (assoc acc simple-key kw-val)))
   {}
   def-map))

(defn- preview-definition-import
  "Preview what would happen if definitions were imported."
  [db definitions]
  (let [existing-paths (set (map :config-def/path (config-db/get-all-definitions db)))
        ;; Deserialized definitions use :path, not :config-def/path
        new-defs (remove #(contains? existing-paths (:path %)) definitions)]
    {:would-create (count new-defs)
     :existing (count (filter #(contains? existing-paths (:path %)) definitions))
     :total (count definitions)}))

(defn- import-definitions!
  "Import definitions into the database.

   Definitions are upserted (create if new, update if exists)."
  [conn definitions]
  (log/info "Importing definitions" {:count (count definitions)})
  (config-db/upsert-definitions-batch! conn definitions)
  (log/info "Definitions imported successfully" {:count (count definitions)})
  {:created (count definitions)})

;; =============================================================================
;; Import - Values
;; =============================================================================

(defn- deserialize-value
  "Deserialize a config value from JSON import."
  [value-map]
  (reduce-kv
   (fn [acc k v]
     (assoc acc (keyword k) v))
   {}
   value-map))

(defn- re-encrypt-value-for-import
  "Re-encrypt an imported value with the master key.

   Args:
     exported-value - Value encrypted with export password
     export-password - Password used during export
     master-key - Master key to encrypt with

   Returns: Re-encrypted value string"
  [exported-value export-password master-key]
  (crypto/re-encrypt exported-value export-password master-key))

(defn- preview-value-import
  "Preview what would happen if values were imported."
  [db values on-conflict]
  (reduce
   (fn [acc value]
     (let [path (get value :config/definition-path (get value "config/definition-path"))
           tenant (:config/tenant value)
           env (:config/environment value)
           entity (:config/entity value)
           existing (config-db/get-value-entity db tenant env entity path)]
       (cond
         (nil? existing)
         (update acc :would-create inc)

         (= on-conflict :overwrite)
         (update acc :would-overwrite inc)

         :else  ; :skip
         (update acc :would-skip inc))))
   {:would-create 0 :would-overwrite 0 :would-skip 0 :total (count values)}
   values))

(defn- import-values!
  "Import config values into the database using batch transactions.

   Args:
     conn - Datahike connection
     values - Values to import
     master-key - Master key for encryption
     export-password - Password values are encrypted with
     on-conflict - :overwrite or :skip
     progress-atom - Optional atom to report progress to UI"
  [conn values master-key export-password on-conflict progress-atom]
  (log/info "Importing config values" {:count (count values) :on-conflict on-conflict})
  (let [db @conn
        total (count values)
        batch-size 100]  ; Process in batches of 100
    ;; Initialize progress
    (when progress-atom
      (reset! progress-atom {:phase :values
                             :current 0
                             :total total
                             :percent 0
                             :status :running}))

    ;; First pass: categorize all values and build tx-data
    (log/info "Categorizing values for batch import...")
    (let [now (System/currentTimeMillis)
          categorized
          (reduce
           (fn [acc value]
             (let [path (get value :config/definition-path (get value "config/definition-path"))
                   definition (config-db/get-definition db path)]
               (if-not definition
                 (update acc :missing-definition inc)
                 (let [tenant (:config/tenant value)
                       env (:config/environment value)
                       entity (:config/entity value)
                       existing (config-db/get-value-entity db tenant env entity path)
                       encrypted? (:config-def/encrypted? definition)
                       raw-value (:config/value value)
                       final-value (if (and encrypted? raw-value export-password master-key)
                                     (re-encrypt-value-for-import raw-value export-password master-key)
                                     raw-value)]
                   (cond
                     ;; New value - build create tx-data
                     (nil? existing)
                     (let [tx-data (cond-> {:config/id (config-db/make-config-id tenant env entity path)
                                            :config/definition [:config-def/path path]
                                            :config/value final-value
                                            :config/created-at now
                                            :config/updated-at now}
                                     tenant (assoc :config/tenant tenant)
                                     env (assoc :config/environment env)
                                     entity (assoc :config/entity entity))]
                       (update acc :creates conj tx-data))

                     ;; Existing value with overwrite - build update tx-data
                     (= on-conflict :overwrite)
                     (let [tx-data {:config/id (config-db/make-config-id tenant env entity path)
                                    :config/definition [:config-def/path path]
                                    :config/value final-value
                                    :config/updated-at now}]
                       (update acc :updates conj tx-data))

                     ;; Skip existing
                     :else
                     (update acc :skipped inc))))))
           {:creates [] :updates [] :skipped 0 :missing-definition 0}
           values)

          creates (:creates categorized)
          updates (:updates categorized)
          skipped (:skipped categorized)
          missing (:missing-definition categorized)]

      (log/info "Value categorization complete"
                {:to-create (count creates)
                 :to-update (count updates)
                 :skipped skipped
                 :missing-definition missing})

      ;; Batch transact creates
      (when (seq creates)
        (log/info "Batch creating values..." {:count (count creates)})
        (doseq [[batch-idx batch] (map-indexed vector (partition-all batch-size creates))]
          (d/transact conn {:tx-data (vec batch)})
          (when progress-atom
            (let [processed (min (* (inc batch-idx) batch-size) (count creates))
                  percent (quot (* 50 processed) (max 1 (count creates)))]  ; Creates are 0-50%
              (swap! progress-atom assoc
                     :current processed
                     :percent percent
                     :phase :creating)))
          (log/debug "Created batch" {:batch (inc batch-idx) :size (count batch)})))

      ;; Batch transact updates
      (when (seq updates)
        (log/info "Batch updating values..." {:count (count updates)})
        (doseq [[batch-idx batch] (map-indexed vector (partition-all batch-size updates))]
          (d/transact conn {:tx-data (vec batch)})
          (when progress-atom
            (let [processed (min (* (inc batch-idx) batch-size) (count updates))
                  percent (+ 50 (quot (* 50 processed) (max 1 (count updates))))]  ; Updates are 50-100%
              (swap! progress-atom assoc
                     :current (+ (count creates) processed)
                     :percent percent
                     :phase :updating)))
          (log/debug "Updated batch" {:batch (inc batch-idx) :size (count batch)})))

      (let [final-results {:created (count creates)
                           :updated (count updates)
                           :skipped skipped
                           :missing-definition missing}]
        (log/info "Config values import complete" final-results)
        (when progress-atom
          (swap! progress-atom assoc
                 :status :complete
                 :percent 100
                 :results final-results))
        final-results))))

;; =============================================================================
;; Import - Audit
;; =============================================================================

(defn- deserialize-audit
  "Deserialize an audit record from JSON import."
  [audit-map]
  (reduce-kv
   (fn [acc k v]
     (let [kw-key (keyword k)
           kw-val (if (= kw-key :audit/action)
                    (keyword v)
                    v)]
       (assoc acc kw-key kw-val)))
   {}
   audit-map))

(defn- import-audit!
  "Import audit records into the database.

   Audit records are inserted with their original timestamps and IDs."
  [conn audit-records]
  (when (seq audit-records)
    (let [tx-data (mapv (fn [record]
                          (-> record
                              (select-keys [:audit/id :audit/timestamp :audit/action
                                            :audit/config-path :audit/tenant :audit/environment
                                            :audit/previous-value :audit/new-value
                                            :audit/user-email :audit/user-id :audit/ip-address])
                              ;; Add definition reference if path exists
                              (cond-> (:audit/config-path record)
                                (assoc :audit/config-def [:config-def/path (:audit/config-path record)]))))
                        audit-records)]
      (d/transact conn {:tx-data tx-data})))
  {:imported (count audit-records)})

;; =============================================================================
;; Import - Main Function
;; =============================================================================

(defn import-data
  "Import data from a backup.

   Args:
     conn - Datahike connection
     data - Export data map (from export-full or export-tenant)
     opts - Map with:
       :master-key - Master key for encryption (required for encrypted values)
       :export-password - Password values are encrypted with (required for encrypted values)
       :on-conflict - :overwrite or :skip (default :skip)
       :dry-run? - Preview only, don't import (default false)
       :progress-atom - Optional atom to report progress to UI

   Returns:
     If dry-run?: Preview map with counts
     Otherwise: Result map with import statistics"
  [conn data {:keys [master-key export-password on-conflict dry-run? progress-atom]
              :or {on-conflict :skip dry-run? false}}]
  (log/info "Starting config import"
            {:version (:version data)
             :scope (:scope data)
             :exported-at (:exported-at data)
             :on-conflict on-conflict
             :dry-run? dry-run?})
  ;; Initialize progress
  (when progress-atom
    (reset! progress-atom {:phase :parsing
                           :status :running
                           :message "Parsing import data..."}))
  (let [db @conn
        raw-definitions (get-in data [:data :definitions] (get-in data ["data" "definitions"]))
        raw-values (get-in data [:data :values] (get-in data ["data" "values"]))
        raw-audit (get-in data [:data :audit] (get-in data ["data" "audit"]))]

    (log/info "Parsed import data"
              {:definitions-count (count raw-definitions)
               :values-count (count raw-values)
               :audit-count (count raw-audit)})

    (when progress-atom
      (swap! progress-atom assoc
             :phase :parsed
             :definitions-count (count raw-definitions)
             :values-count (count raw-values)
             :message (str "Found " (count raw-definitions) " definitions, "
                          (count raw-values) " values")))

    (let [;; Deserialize
          definitions (mapv deserialize-definition raw-definitions)
          values (mapv deserialize-value raw-values)
          audit-records (mapv deserialize-audit (or raw-audit []))]

      (if dry-run?
        (let [result {:definitions (preview-definition-import db definitions)
                      :values (preview-value-import db values on-conflict)
                      :audit {:count (count audit-records)}}]
          (log/info "Dry-run import preview" result)
          (when progress-atom
            (reset! progress-atom {:phase :preview
                                   :status :complete
                                   :result result}))
          result)

        (do
          ;; Import definitions first
          (when progress-atom
            (swap! progress-atom assoc
                   :phase :definitions
                   :message (str "Importing " (count definitions) " definitions...")))
          (let [def-result (import-definitions! conn definitions)]
            (when progress-atom
              (swap! progress-atom assoc
                     :definitions-result def-result))
            ;; Import values
            (when progress-atom
              (swap! progress-atom assoc
                     :phase :values
                     :message (str "Importing " (count values) " values...")))
            (let [val-result (import-values! conn values master-key export-password on-conflict progress-atom)
                  audit-result (when (seq audit-records)
                                (import-audit! conn audit-records))
                  result {:definitions def-result
                          :values val-result
                          :audit audit-result}]
              (log/info "Import completed successfully" result)
              (when progress-atom
                (reset! progress-atom {:phase :complete
                                       :status :complete
                                       :result result
                                       :message "Import completed successfully"}))
              result)))))))

;; =============================================================================
;; Clone Functions
;; =============================================================================

(defn clone-tenant
  "Clone all configuration from one tenant to another.

   Args:
     conn - Datahike connection
     source-tenant - Tenant to copy from
     target-tenant - Tenant to copy to
     opts - Map with:
       :master-key - Master key for encryption (reserved for future use)
       :exclude-entities? - If true, don't copy entity-scoped configs (default false)
       :dry-run? - Preview only (default false)

   Returns:
     If dry-run?: Preview map with counts
     Otherwise: Result map with clone statistics"
  [conn source-tenant target-tenant {:keys [_master-key exclude-entities? dry-run?]
                                      :or {exclude-entities? false dry-run? false}}]
  (let [db @conn
        source-values (get-tenant-values db source-tenant)
        ;; Filter out entity-scoped if requested
        values-to-clone (if exclude-entities?
                          (remove :config/entity source-values)
                          source-values)]

    (if dry-run?
      {:source-tenant source-tenant
       :target-tenant target-tenant
       :values {:total (count values-to-clone)
                :with-entities (count (filter :config/entity values-to-clone))
                :without-entities (count (remove :config/entity values-to-clone))}}

      ;; Perform clone
      (let [results (atom {:created 0 :skipped 0})]
        (doseq [value values-to-clone]
          (let [definition (:config/definition value)
                path (:config-def/path definition)
                env (:config/environment value)
                entity (:config/entity value)
                existing (config-db/get-value-entity db target-tenant env entity path)]
            (if existing
              (swap! results update :skipped inc)
              (let [raw-value (:config/value value)]
                (d/transact conn {:tx-data [{:config/id (config-db/make-config-id target-tenant env entity path)
                                             :config/definition [:config-def/path path]
                                             :config/value raw-value
                                             :config/tenant target-tenant
                                             :config/environment env
                                             :config/entity entity
                                             :config/created-at (System/currentTimeMillis)
                                             :config/updated-at (System/currentTimeMillis)}]})
                (swap! results update :created inc)))))
        @results))))

(defn clone-environment
  "Clone all configuration from one environment to another within a tenant.

   Args:
     conn - Datahike connection
     tenant - Tenant identifier
     source-env - Environment to copy from
     target-env - Environment to copy to
     opts - Map with:
       :master-key - Master key for encryption (reserved for future use)
       :exclude-entities? - If true, don't copy entity-scoped configs (default false)
       :dry-run? - Preview only (default false)

   Returns:
     If dry-run?: Preview map with counts
     Otherwise: Result map with clone statistics"
  [conn tenant source-env target-env {:keys [_master-key exclude-entities? dry-run?]
                                       :or {exclude-entities? false dry-run? false}}]
  (let [db @conn
        ;; Get values for this tenant+environment
        source-values (d/q '[:find [(pull ?e [* {:config/definition [:config-def/path
                                                                      :config-def/encrypted?]}]) ...]
                             :in $ ?tenant ?env
                             :where
                             [?e :config/tenant ?tenant]
                             [?e :config/environment ?env]]
                           db tenant source-env)
        ;; Filter out entity-scoped if requested
        values-to-clone (if exclude-entities?
                          (remove :config/entity source-values)
                          source-values)]

    (if dry-run?
      {:tenant tenant
       :source-environment source-env
       :target-environment target-env
       :values {:total (count values-to-clone)
                :with-entities (count (filter :config/entity values-to-clone))
                :without-entities (count (remove :config/entity values-to-clone))}}

      ;; Perform clone
      (let [results (atom {:created 0 :skipped 0})]
        (doseq [value values-to-clone]
          (let [definition (:config/definition value)
                path (:config-def/path definition)
                entity (:config/entity value)
                existing (config-db/get-value-entity db tenant target-env entity path)]
            (if existing
              (swap! results update :skipped inc)
              (let [raw-value (:config/value value)]
                (d/transact conn {:tx-data [{:config/id (config-db/make-config-id tenant target-env entity path)
                                             :config/definition [:config-def/path path]
                                             :config/value raw-value
                                             :config/tenant tenant
                                             :config/environment target-env
                                             :config/entity entity
                                             :config/created-at (System/currentTimeMillis)
                                             :config/updated-at (System/currentTimeMillis)}]})
                (swap! results update :created inc)))))
        @results))))

;; =============================================================================
;; File I/O
;; =============================================================================

(defn export-to-file
  "Export data and write to a JSON file.

   Args:
     conn - Datahike connection
     file-path - Path to write JSON file
     opts - Export options (same as export-full, plus :tenant for tenant export)

   Returns: Map with :file-path and export statistics"
  [conn file-path {:keys [tenant] :as opts}]
  (let [export-fn (if tenant export-tenant export-full)
        export-args (if tenant [conn tenant opts] [conn opts])
        data (apply export-fn export-args)]
    (spit file-path (json/write-str data :escape-slash false))
    {:file-path file-path
     :scope (or (:scope data) (if tenant "tenant" "full"))
     :definitions (count (get-in data [:data :definitions]))
     :values (count (get-in data [:data :values]))
     :audit (count (get-in data [:data :audit]))}))

(defn import-from-file
  "Read a JSON file and import the data.

   Args:
     conn - Datahike connection
     file-path - Path to JSON file
     opts - Import options (same as import-data)

   Returns: Import result map"
  [conn file-path opts]
  (log/info "Reading import file" {:file-path file-path})
  (let [data (json/read-str (slurp file-path) :key-fn keyword)]
    (log/info "Import file parsed successfully" {:file-path file-path})
    (import-data conn data opts)))



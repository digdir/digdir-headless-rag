(ns digdir.config.accessor
  "Primary config accessor API.

   Replaces (get-in config/config [:services :azure-openai :api-key]) with:
     (cfg/get :services :azure-openai :api-key)

   Features:
   - Automatic decryption of encrypted values
   - 8-level multi-dimensional resolution based on dimension count
   - Entity-specific config overrides with {:entity \"id\"} option
   - Caching with configurable TTL
   - Database-backed configuration (requires CONFIG_MASTER_KEY environment variable)

   Resolution Order (most to least specific):
   - 3 dims: entity+tenant+env
   - 2 dims: entity+tenant > entity+env > tenant+env (tiebreaker: entity > tenant > env)
   - 1 dim:  entity > tenant > env
   - 0 dims: global"
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as str]
            [digdir.config.core :as core]
            [digdir.config.db :as config-db]))

;; =============================================================================
;; Runtime Config Cache
;; =============================================================================

;; Cache for non-entity-scoped config (global cache)
(defonce ^:private !config-cache (atom nil))
(defonce ^:private !cache-timestamp (atom 0))
(def ^:private cache-ttl-ms 60000) ; 1 minute TTL

(defn- cache-expired?
  "Check if the config cache has expired."
  []
  (> (- (System/currentTimeMillis) @!cache-timestamp) cache-ttl-ms))

(defn- ensure-cache!
  "Ensure the config cache is populated and fresh.
   This cache is for non-entity-scoped resolution (entity=nil)."
  []
  (when (or (nil? @!config-cache) (cache-expired?))
    (let [conn (config-db/get-conn)]
      (when conn
        (let [tenant (core/get-tenant)
              env (core/get-environment)
              master-key (core/get-master-key)]
          (reset! !config-cache
                  (config-db/load-resolved-config @conn tenant env nil master-key))
          (reset! !cache-timestamp (System/currentTimeMillis)))))))

(defn invalidate-cache!
  "Invalidate the config cache, forcing a reload on next access."
  []
  (reset! !config-cache nil)
  (reset! !cache-timestamp 0))

;; =============================================================================
;; Path Utilities
;; =============================================================================

(defn- normalize-path
  "Normalize a path to a vector of keywords.

   Accepts:
   - Vector: [:services :azure-openai :api-key]
   - Keywords: :services :azure-openai :api-key
   - String: \"services.azure-openai.api-key\""
  [path-or-keys]
  (cond
    (vector? path-or-keys)
    path-or-keys

    (string? path-or-keys)
    (mapv keyword (str/split path-or-keys #"\."))

    (keyword? path-or-keys)
    [path-or-keys]

    :else
    (vec path-or-keys)))

(defn- path->string
  "Convert a path to a dot-separated string."
  [path]
  (str/join "." (map name path)))

;; =============================================================================
;; Primary Accessor
;; =============================================================================

(defn- parse-get-args
  "Parse arguments to get function.
   Supports:
   - (get :a :b :c)
   - (get [:a :b :c])
   - (get :a :b :c \"default\")
   - (get :a :b :c {:entity \"my-bot\"})
   - (get :a :b :c {:entity \"my-bot\" :default \"val\"})

   Returns: {:path [keywords], :entity string-or-nil, :default any}"
  [args]
  (let [last-arg (last args)
        has-opts? (and (map? last-arg) (or (contains? last-arg :entity)
                                            (contains? last-arg :default)))
        opts (if has-opts? last-arg {})
        path-args (if has-opts? (butlast args) args)
        ;; Check if last path-arg is a non-keyword default value
        [path-args default] (if (and (not has-opts?)
                                     (> (count path-args) 1)
                                     (not (keyword? (last path-args))))
                              [(butlast path-args) (last path-args)]
                              [path-args (:default opts)])
        ;; Normalize path
        path (if (and (= 1 (count path-args))
                      (or (vector? (first path-args))
                          (string? (first path-args))))
               (normalize-path (first path-args))
               (vec path-args))]
    {:path path
     :entity (:entity opts)
     :default default}))

(defn get
  "Get a config value from database.

   Requires database mode (CONFIG_MASTER_KEY must be set).
   Supports entity-specific config overrides via {:entity \"id\"} option.

   Usage:
     (get :services :azure-openai :api-key)
     (get [:services :azure-openai :api-key])
     (get [:services :azure-openai :api-key] \"default-value\")
     (get :services :azure-openai :deployment-name {:entity \"my-bot\"})

   Args:
     path - Config path as keywords, vector, or dot-separated string
     default - Default value if not found (optional)
     opts - Options map with :entity and/or :default (optional)"
  ([& args]
   (let [{:keys [path entity default]} (parse-get-args args)]

     (when-not (core/use-db-config?)
       (throw (ex-info "Database config required. Set CONFIG_MASTER_KEY environment variable."
                       {:path path})))

     (if entity
       ;; Entity-specific: resolve directly from DB (no cache)
       (let [conn (config-db/get-conn)]
         (if conn
           (let [db @conn
                 tenant (core/get-tenant)
                 env (core/get-environment)
                 master-key (core/get-master-key)
                 path-str (path->string path)
                 definition (config-db/get-definition db path-str)]
             (if definition
               (let [resolved (config-db/resolve-value db tenant env entity path-str)]
                 (if resolved
                   (config-db/decode-value (:config/value resolved)
                                           (:config-def/value-type definition)
                                           (:config-def/encrypted? definition)
                                           master-key)
                   default))
               default))
           (throw (ex-info "Database connection not available" {:path path}))))

       ;; No entity: use cache
       (do
         (ensure-cache!)
         (let [cached-value (get-in @!config-cache path ::not-found)]
           (if (= cached-value ::not-found)
             default
             cached-value)))))))

(defn get-raw
  "Get the raw (possibly encrypted) value without decryption.

   Useful for admin UI display.
   Supports entity via last opts map: {:entity \"id\"}"
  [& args]
  (let [{:keys [path entity]} (parse-get-args args)
        path-str (path->string path)
        conn (config-db/get-conn)]
    (when conn
      (config-db/get-raw-value @conn
                               (core/get-tenant)
                               (core/get-environment)
                               entity
                               path-str))))

;; =============================================================================
;; Setters (with audit logging)
;; =============================================================================

(defn set!
  "Set a config value with audit logging.

   Args:
     path - Config path as keywords, vector, or string
     value - The value to set
     opts - Map with:
       :user-email - Email of user making the change (required for audit)
       :user-id - ID of user making the change
       :ip-address - IP address of requester
       :tenant - Override tenant (default: current tenant)
       :environment - Override environment (default: current environment)
       :entity - Entity to set value for (nil for non-entity-scoped)

   Example:
     (set! [:services :azure-openai :deployment-name] \"gpt-4o\"
           {:user-email \"admin@digdir.no\" :user-id \"abc123\"})
     (set! [:services :azure-openai :deployment-name] \"gpt-4-turbo\"
           {:entity \"my-bot\" :user-email \"admin@digdir.no\"})"
  [path value opts]
  (let [path-vec (normalize-path path)
        path-str (path->string path-vec)
        conn (config-db/get-conn)
        tenant (or (:tenant opts) (core/get-tenant))
        env (or (:environment opts) (core/get-environment))
        entity (:entity opts)
        master-key (core/get-master-key)]

    (when-not conn
      (throw (ex-info "Database connection not available" {})))

    ;; Get previous value for audit
    (let [db @conn
          previous (config-db/resolve-value db tenant env entity path-str)
          previous-value (:config/value previous)
          definition (config-db/get-definition db path-str)]

      ;; Set the new value
      (config-db/set-value! conn
                            {:tenant tenant
                             :environment env
                             :entity entity
                             :path path-str
                             :value value
                             :master-key master-key})

      ;; Log audit (if audit ns is available)
      (when-let [log-fn (try
                          (require 'digdir.config.audit)
                          (resolve 'digdir.config.audit/log-change!)
                          (catch Exception _ nil))]
        (log-fn conn
                {:path path-str
                 :tenant tenant
                 :environment env
                 :entity entity
                 :action (if previous :update :create)
                 :previous-value previous-value
                 :new-value value
                 :encrypted? (:config-def/encrypted? definition)
                 :user-email (:user-email opts)
                 :user-id (:user-id opts)
                 :ip-address (:ip-address opts)}))

      ;; Invalidate cache
      (invalidate-cache!)

      :ok)))

(defn delete!
  "Delete a config value.

   Args:
     path - Config path
     opts - Map with audit info (same as set!, including :entity)"
  [path opts]
  (let [path-vec (normalize-path path)
        path-str (path->string path-vec)
        conn (config-db/get-conn)
        tenant (or (:tenant opts) (core/get-tenant))
        env (or (:environment opts) (core/get-environment))
        entity (:entity opts)]

    (when-not conn
      (throw (ex-info "Database connection not available" {})))

    ;; Get previous value for audit
    (let [db @conn
          previous (config-db/resolve-value db tenant env entity path-str)
          previous-value (:config/value previous)
          definition (config-db/get-definition db path-str)]

      ;; Delete the value
      (config-db/delete-value! conn tenant env entity path-str)

      ;; Log audit
      (when-let [log-fn (try
                          (require 'digdir.config.audit)
                          (resolve 'digdir.config.audit/log-change!)
                          (catch Exception _ nil))]
        (log-fn conn
                {:path path-str
                 :tenant tenant
                 :environment env
                 :entity entity
                 :action :delete
                 :previous-value previous-value
                 :new-value nil
                 :encrypted? (:config-def/encrypted? definition)
                 :user-email (:user-email opts)
                 :user-id (:user-id opts)
                 :ip-address (:ip-address opts)}))

      ;; Invalidate cache
      (invalidate-cache!)

      :ok)))

;; =============================================================================
;; Permission-Checked Access
;; =============================================================================

(defn- check-permission!
  "Check if a user has permission for an action on a path.
   Throws ex-info if permission is denied."
  [conn user-id path-str action]
  (when conn
    (when-let [check-fn (try
                          (require 'digdir.config.permissions)
                          (resolve 'digdir.config.permissions/can-access?)
                          (catch Exception _ nil))]
      (when-not (check-fn @conn user-id path-str action)
        (throw (ex-info "Permission denied"
                        {:path path-str :user-id user-id :action action}))))))

(defn get-if-allowed
  "Get a config value only if the user has permission.

   Returns the value if allowed, or throws an exception if denied.

   Args:
     user-id - User ID to check permissions for
     path - Config path"
  [user-id & path-parts]
  (let [path (if (and (= 1 (count path-parts))
                      (or (vector? (first path-parts))
                          (string? (first path-parts))))
               (normalize-path (first path-parts))
               (vec path-parts))
        path-str (path->string path)
        conn (config-db/get-conn)]

    (check-permission! conn user-id path-str :read)
    (apply get path)))

(defn set-if-allowed!
  "Set a config value only if the user has permission.

   Args:
     user-id - User ID to check permissions for
     path - Config path
     value - The value to set
     opts - Same as set! but user-id/user-email will be inferred if not provided"
  [user-id path value opts]
  (let [path-vec (normalize-path path)
        path-str (path->string path-vec)
        conn (config-db/get-conn)]

    (check-permission! conn user-id path-str :write)
    (#'set! path value (merge {:user-id user-id} opts))))

(defn delete-if-allowed!
  "Delete a config value only if the user has permission.

   Args:
     user-id - User ID to check permissions for
     path - Config path
     opts - Same as delete!"
  [user-id path opts]
  (let [path-vec (normalize-path path)
        path-str (path->string path-vec)
        conn (config-db/get-conn)]

    (check-permission! conn user-id path-str :write)
    (delete! path (merge {:user-id user-id} opts))))

(defn evaluate-access
  "Evaluate a user's access to a config path without fetching the value.

   Returns: {:allowed? bool :matched-permission id :reason string}

   Args:
     user-id - User ID to check
     path - Config path
     action - :read or :write"
  [user-id path action]
  (let [path-str (if (string? path) path (path->string (normalize-path path)))
        conn (config-db/get-conn)]
    (if conn
      (if-let [eval-fn (try
                         (require 'digdir.config.permissions)
                         (resolve 'digdir.config.permissions/evaluate-access)
                         (catch Exception _ nil))]
        (eval-fn @conn user-id path-str action)
        {:allowed? true :reason "Permissions module not loaded"})
      {:allowed? false :reason "Database not connected"})))

;; =============================================================================
;; Bulk Access
;; =============================================================================

(defn get-all
  "Get all config values as a nested map.

   This returns the full resolved config for the current tenant/environment."
  []
  (when-not (core/use-db-config?)
    (throw (ex-info "Database config required. Set CONFIG_MASTER_KEY environment variable." {})))
  (ensure-cache!)
  @!config-cache)

(defn get-section
  "Get a section of config as a map.

   Example: (get-section :services :azure-openai)
   Returns: {:api-key \"...\" :endpoint \"...\" ...}"
  [& path-parts]
  (let [path (vec path-parts)]
    (get-in (get-all) path)))

;; =============================================================================
;; Entity Access
;; =============================================================================

(defn get-for-entity
  "Get a config value for a specific entity.
   Shorthand for (get path {:entity entity-id})

   Usage:
     (get-for-entity \"my-bot\" :services :azure-openai :deployment-name)"
  [entity-id & path-parts]
  (apply get (concat path-parts [{:entity entity-id}])))

(defn get-entity
  "Get full entity configuration by ID.
   Returns a map with all entity properties, or nil if entity not found.

   Example:
     (get-entity \"my-bot\")
     => {:id \"my-bot\" :name \"My Bot\" :image \"bot.png\" ...}"
  [entity-id]
  (when-not (core/use-db-config?)
    (throw (ex-info "Database config required. Set CONFIG_MASTER_KEY environment variable."
                    {:entity entity-id})))
  (when-let [conn (config-db/get-conn)]
    (config-db/get-entity @conn
                          (core/get-tenant)
                          (core/get-environment)
                          entity-id
                          (core/get-master-key))))

(defn list-entities
  "List all entity IDs for the current tenant.

   Returns a vector of entity ID strings."
  []
  (when-not (core/use-db-config?)
    (throw (ex-info "Database config required. Set CONFIG_MASTER_KEY environment variable." {})))
  (when-let [conn (config-db/get-conn)]
    (config-db/list-entities @conn (core/get-tenant))))

(defn get-entities
  "Get all entity configurations as a vector of maps.
   Each map contains the full entity configuration.

   Returns: [{:id \"...\" :name \"...\" ...} ...]"
  []
  (when-not (core/use-db-config?)
    (throw (ex-info "Database config required. Set CONFIG_MASTER_KEY environment variable." {})))
  (let [entity-ids (list-entities)]
    (vec (keep get-entity entity-ids))))

(defn get-entities-for-tenant
  "Get all entity configurations for a specific tenant.
   Returns a vector of entity maps with their full configuration.

   Args:
     tenant - Tenant identifier

   Returns: [{:id \"...\" :name \"...\" ...} ...]"
  [tenant]
  (when-not (core/use-db-config?)
    (throw (ex-info "Database config required. Set CONFIG_MASTER_KEY environment variable."
                    {:tenant tenant})))
  (when-let [conn (config-db/get-conn)]
    (let [entity-ids (config-db/list-entities @conn tenant)
          env (core/get-environment)
          master-key (core/get-master-key)]
      (vec (keep #(config-db/get-entity @conn tenant env % master-key)
                 entity-ids)))))

;; =============================================================================
;; Migration Helpers
;; =============================================================================

(comment
  (require '[digdir.config.accessor :as cfg])

  ;; Get a config value
  (cfg/get :services :azure-openai :api-key)
  (cfg/get [:services :azure-openai :api-key])
  (cfg/get [:services :azure-openai :api-key] "default")

  ;; Get with entity-specific override
  (cfg/get :services :azure-openai :deployment-name {:entity "my-bot"})
  (cfg/get-for-entity "my-bot" :services :azure-openai :deployment-name)

  ;; Set a value
  (cfg/set! [:services :azure-openai :deployment-name] "gpt-4o"
            {:user-email "admin@digdir.no" :user-id "abc123"})

  ;; Set entity-specific value
  (cfg/set! [:services :azure-openai :deployment-name] "gpt-4-turbo"
            {:entity "my-bot" :user-email "admin@digdir.no" :user-id "abc123"})

  ;; Entity management
  (cfg/list-entities)         ;; => ["my-bot" "other-bot"]
  (cfg/get-entity "my-bot")   ;; => {:id "my-bot" :name "My Bot" :image "..." ...}
  (cfg/get-entities)          ;; => [{:id "my-bot" ...} {:id "other-bot" ...}]

  ;; Permission-checked access
  (cfg/get-if-allowed "user-123" :services :azure-openai :api-key)
  (cfg/set-if-allowed! "user-123" [:services :azure-openai :deployment] "gpt-4o"
                       {:user-email "admin@digdir.no"})
  (cfg/evaluate-access "user-123" [:services :azure-openai :api-key] :read)

  ;; Get entire section
  (get-section :services :azure-openai))

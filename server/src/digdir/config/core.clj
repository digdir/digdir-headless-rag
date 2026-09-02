(ns digdir.config.core
  "Configuration management using database storage.

   All runtime configuration is loaded from Datahike database.
   A minimal bootstrap configuration is loaded from environment variables
   to establish database connection, after which all config comes from DB.

   Required environment variables:
   - ADH_POSTGRES_URL, ADH_POSTGRES_USER, ADH_POSTGRES_PWD (database connection)
   - CONFIG_MASTER_KEY (encryption key for secrets)
   - JWT_SECRET (JWT signing secret for authentication)
   - TENANT (e.g., 'ka', 'altinn')
   - ENV (e.g., 'prod', 'test', 'dev')

   For config access, use digdir.config.accessor namespace.")

;; =============================================================================
;; Bootstrap Configuration
;; =============================================================================

(defn load-bootstrap-config
  "Load minimal configuration from environment variables only.

   This provides just enough config to connect to the database,
   after which runtime config is loaded from DB.

   Returns nil if required env vars are missing."
  []
  (let [postgres-url (System/getenv "ADH_POSTGRES_URL")
        postgres-user (System/getenv "ADH_POSTGRES_USER")
        postgres-pwd (System/getenv "ADH_POSTGRES_PWD")
        postgres-table (System/getenv "ADH_POSTGRES_TABLE")
        master-key (System/getenv "CONFIG_MASTER_KEY")
        jwt-secret (System/getenv "JWT_SECRET")
        tenant (System/getenv "TENANT")
        env (System/getenv "ENV")]
    (when (and postgres-url master-key jwt-secret)
      {:db {:remote {:store {:backend :jdbc
                             :dbtype "postgresql"
                             :jdbcUrl postgres-url
                             :user postgres-user
                             :password postgres-pwd
                             :table postgres-table}
                     :schema-flexibility :read
                     :keep-history? false}}
       :db-env :remote
       :config-master-key master-key
       :jwt-secret jwt-secret
       :tenant tenant
       :environment env})))

(defonce !bootstrap-config (atom (load-bootstrap-config)))
(defonce !runtime-config (atom nil))

(defn use-db-config?
  "Check if database-based config should be used.

   Returns true if CONFIG_MASTER_KEY is set (indicates DB mode)."
  []
  (some? (System/getenv "CONFIG_MASTER_KEY")))

(defn get-tenant
  "Get current tenant from bootstrap config or environment."
  []
  (or (:tenant @!bootstrap-config)
      (System/getenv "TENANT")))

(defn get-environment
  "Get current environment from bootstrap config or environment."
  []
  (or (:environment @!bootstrap-config)
      (System/getenv "ENV")))

(defn get-master-key
  "Get the master encryption key from bootstrap config or environment."
  []
  (:config-master-key @!bootstrap-config))

(defn get-jwt-secret
  "Get the JWT signing secret from bootstrap config or environment."
  []
  (:jwt-secret @!bootstrap-config))

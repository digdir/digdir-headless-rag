(ns digdir.setup
  "Interactive setup wizard for initial system configuration."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [datahike.api :as d]
            [nano-id.core :refer [nano-id]]
            [digdir.config.db :as config-db]
            [digdir.config.ops :as config-ops]
            [digdir.config.permissions :as perms]
            [digdir.auth.migration :as migration]
            [digdir.config.core :as config-core]
            [digdir.data.db :as data-db]))

(defn print-header []
  (println "")
  (println "========================================")
  (println "  RAG Service - Initial Setup Wizard")
  (println "========================================")
  (println ""))

(defn print-section [title]
  (println "")
  (println (str "--- " title " ---"))
  (println ""))

(defn prompt [message]
  (print (str message ": "))
  (flush)
  (str/trim (read-line)))

(defn prompt-required [message]
  (loop []
    (let [input (prompt message)]
      (if (str/blank? input)
        (do
          (println "  This field is required. Please enter a value.")
          (recur))
        input))))

(defn prompt-yn [message default]
  (let [hint (if default "[Y/n]" "[y/N]")
        input (str/lower-case (prompt (str message " " hint)))]
    (cond
      (str/blank? input) default
      (= input "y") true
      (= input "n") false
      :else (do
              (println "  Please enter 'y' or 'n'")
              (recur message default)))))

(defn check-env-vars []
  (print-section "Environment Variables Check")
  (let [required-vars ["ADH_POSTGRES_URL" "ADH_POSTGRES_USER" "ADH_POSTGRES_PWD"
                       "JWT_SECRET" "CONFIG_MASTER_KEY"]
        optional-vars ["ADMIN_USER_EMAILS" "AZURE_OPENAI_API_KEY" "TYPESENSE_API_KEY" "TYPESENSE_API_KEY_ADMIN"]
        check-var (fn [var-name]
                    (let [value (System/getenv var-name)
                          status (if (str/blank? value) "MISSING" "OK")]
                      (println (format "  %-25s %s" var-name status))
                      (not (str/blank? value))))]

    (println "Required environment variables:")
    (let [required-ok (every? identity (map check-var required-vars))]
      (println "")
      (println "Optional environment variables:")
      (doseq [v optional-vars]
        (check-var v))
      (println "")

      (when-not required-ok
        (println "WARNING: Some required environment variables are missing.")
        (println "Please set them before running the service.")
        (println "")
        (println "Example (add to your shell profile or mise.toml):")
        (println "  export ADH_POSTGRES_URL=postgresql://user:pass@host:5432/dbname")
        (println "  export ADH_POSTGRES_USER=your_user")
        (println "  export ADH_POSTGRES_PWD=your_password")
        (println "  export JWT_SECRET=your-secret-key-at-least-32-chars")
        (println "  export CONFIG_MASTER_KEY=your-32-char-encryption-key")
        (println ""))

      required-ok)))

(defn check-database-connection []
  (print-section "Database Connection Check")
  (try
    (let [conn (config-db/get-conn)]
      (println "  Database connection: OK")
      (println (str "  Connection: " (type @conn)))
      true)
    (catch Exception e
      (println "  Database connection: FAILED")
      (println (str "  Error: " (.getMessage e)))
      (println "")
      (println "Please check your database configuration:")
      (println "  - ADH_POSTGRES_URL should be a valid JDBC URL")
      (println "  - ADH_POSTGRES_USER and ADH_POSTGRES_PWD should be set")
      false)))

(defn get-db-config
  "Get the database configuration from bootstrap config."
  []
  (let [bootstrap @config-core/!bootstrap-config
        db-env (:db-env bootstrap)]
    (get-in bootstrap [:db db-env])))

(defn reset-database! []
  (print-section "Database Reset")
  (println "WARNING: This will DELETE ALL DATA in the database!")
  (println "This includes:")
  (println "  - All users and permissions")
  (println "  - All configuration values")
  (println "  - All conversations and threads")
  (println "  - All audit logs")
  (println "")
  (println "This action CANNOT be undone.")
  (println "")

  (when (prompt-yn "Are you sure you want to reset the database?" false)
    (println "")
    (println "Type 'RESET' to confirm:")
    (let [confirmation (prompt "Confirmation")]
      (if (= confirmation "RESET")
        (do
          (println "")
          (println "Resetting database...")
          (try
            (let [cfg (get-db-config)]
              (if cfg
                (do
                  ;; Delete existing database
                  (when (d/database-exists? cfg)
                    (d/delete-database cfg)
                    (println "  Deleted existing database."))
                  ;; Create new database
                  (d/create-database cfg)
                  (println "  Created new database.")
                  ;; Connect and initialize schema
                  (let [conn (d/connect cfg)]
                    ;; Transact base schema
                    (d/transact conn {:tx-data data-db/dh-schema})
                    (println "  Initialized base schema.")
                    ;; Set the connection for config-db
                    (config-db/set-conn! conn)
                    ;; Initialize config schema and permissions
                    (config-db/init-config-db! conn)
                    (println "  Initialized config schema and default permissions."))
                  (println "")
                  (println "Database reset complete!")
                  (println "")
                  (println "NOTE: You will need to restart the application after setup")
                  (println "      for the new database connection to take effect.")
                  true)
                (do
                  (println "  Error: Could not find database configuration.")
                  (println "  Make sure bootstrap config is properly loaded.")
                  false)))
            (catch Exception e
              (println (str "  Error resetting database: " (.getMessage e)))
              (.printStackTrace e)
              false)))
        (do
          (println "Reset cancelled - confirmation did not match.")
          false)))))

(defn get-global-config
  "Get a global config value (tenant=nil, env=nil, entity=nil)."
  [path]
  (let [db @(config-db/get-conn)]
    (config-db/get-raw-value db nil nil nil path)))

(defn ensure-config-definition!
  "Ensure a config definition exists, creating it if needed."
  [path opts]
  (let [conn (config-db/get-conn)
        db @conn
        existing (config-db/get-definition db path)]
    (when-not existing
      (config-db/upsert-definition! conn (merge {:path path} opts)))))

(defn set-global-config!
  "Set a global config value."
  [path value]
  (let [conn (config-db/get-conn)
        master-key (config-core/get-master-key)]
    (config-db/set-value! conn {:tenant nil
                                :environment nil
                                :entity nil
                                :path path
                                :value value
                                :master-key master-key
                                :skip-audit? true
                                :user-email "setup-wizard"
                                :user-id "system"})))

(defn prompt-with-default [message default]
  "Prompt for input with a default value shown."
  (let [hint (if default (str " [" default "]") "")
        input (prompt (str message hint))]
    (if (str/blank? input)
      default
      input)))

(defn prompt-secret [message]
  "Prompt for a secret value (won't show existing value)."
  (print (str message ": "))
  (flush)
  (str/trim (read-line)))

(defn ensure-auth-config-definitions!
  "Ensure auth config definitions exist in the database."
  []
  (ensure-config-definition! "services.auth.jwt-token-expiry-hours"
                             {:value-type :number
                              :description "JWT token expiry time in hours"
                              :category :auth
                              :service :auth
                              :sensitivity :admin-only
                              :function :settings})
  (ensure-config-definition! "services.auth.session-max-age"
                             {:value-type :number
                              :description "Session cookie max age in seconds"
                              :category :auth
                              :service :auth
                              :sensitivity :admin-only
                              :function :settings})
  (ensure-config-definition! "services.auth.secure-cookies?"
                             {:value-type :boolean
                              :description "Use secure cookies (HTTPS only)"
                              :category :auth
                              :service :auth
                              :sensitivity :admin-only
                              :function :settings})
  (ensure-config-definition! "services.auth.cookie-domain"
                             {:value-type :string
                              :description "Cookie domain (optional, for cross-subdomain auth)"
                              :category :auth
                              :service :auth
                              :sensitivity :admin-only
                              :function :settings})
  (ensure-config-definition! "services.auth.jwt-cookie-max-age"
                             {:value-type :number
                              :description "JWT cookie max age in seconds"
                              :category :auth
                              :service :auth
                              :sensitivity :admin-only
                              :function :settings})
  (ensure-config-definition! "services.auth.jwt-secret"
                             {:value-type :string
                              :encrypted? true
                              :description "JWT signing secret"
                              :category :auth
                              :service :auth
                              :sensitivity :secret
                              :function :credentials})
  (ensure-config-definition! "services.auth.admin-user-emails"
                             {:value-type :string
                              :description "Comma-separated list of admin user emails"
                              :category :auth
                              :service :auth
                              :sensitivity :admin-only
                              :function :settings})
  (ensure-config-definition! "services.auth.approved-domains"
                             {:value-type :edn
                              :description "Set of approved email domains for authentication"
                              :category :auth
                              :service :auth
                              :sensitivity :admin-only
                              :function :settings})
  (ensure-config-definition! "services.auth.use-db"
                             {:value-type :boolean
                              :description "Use database for authentication instead of domain whitelist"
                              :category :auth
                              :service :auth
                              :sensitivity :admin-only
                              :function :settings}))

(defn setup-auth-config []
  (print-section "Authentication Configuration")
  (println "Configure authentication settings for the system.")
  (println "")

  ;; Ensure config definitions exist
  (ensure-auth-config-definitions!)

  ;; Check if already configured
  (let [existing-expiry (get-global-config "services.auth.jwt-token-expiry-hours")
        already-configured? (some? existing-expiry)]

    (when already-configured?
      (println "Auth configuration found:")
      (println (str "  JWT token expiry: " existing-expiry " hours"))
      (println ""))

    (when (or (not already-configured?)
              (prompt-yn "Reconfigure auth settings?" false))

      (when-not already-configured?
        (println "No auth configuration found. Setting up defaults..."))

      (println "")

      ;; JWT token expiry (default 24 hours)
      (let [current-expiry (or (get-global-config "services.auth.jwt-token-expiry-hours") 24)
            expiry-str (prompt-with-default "JWT token expiry (hours)" (str current-expiry))
            expiry (try (Integer/parseInt expiry-str) (catch Exception _ current-expiry))]
        (set-global-config! "services.auth.jwt-token-expiry-hours" expiry)
        (println (str "  Set JWT token expiry: " expiry " hours")))

      ;; Session max age (default 1 hour = 3600 seconds)
      (let [current-session (or (get-global-config "services.auth.session-max-age") 3600)
            session-str (prompt-with-default "Session max age (seconds)" (str current-session))
            session-age (try (Integer/parseInt session-str) (catch Exception _ current-session))]
        (set-global-config! "services.auth.session-max-age" session-age)
        (println (str "  Set session max age: " session-age " seconds")))

      ;; Secure cookies (default false for dev, should be true for prod)
      (let [current-secure (get-global-config "services.auth.secure-cookies?")
            secure? (if (nil? current-secure)
                      (prompt-yn "Use secure cookies? (requires HTTPS)" false)
                      (prompt-yn "Use secure cookies? (requires HTTPS)" current-secure))]
        (set-global-config! "services.auth.secure-cookies?" secure?)
        (println (str "  Set secure cookies: " secure?)))

      (println "")
      (println "Auth configuration complete."))))

(defn ensure-email-config-definitions!
  "Ensure Scaleway TEM config definitions exist in the database."
  []
  (println "Ensuring email config definitions exist...")
  (ensure-config-definition! "services.scaleway-tem.region"
                             {:value-type :string
                              :description "Scaleway region for TEM service"
                              :category :services
                              :service :email
                              :sensitivity :public
                              :function :settings})
  (ensure-config-definition! "services.scaleway-tem.project-id"
                             {:value-type :string
                              :description "Scaleway project ID"
                              :category :services
                              :service :email
                              :sensitivity :internal
                              :function :settings})
  (ensure-config-definition! "services.scaleway-tem.api-key"
                             {:value-type :string
                              :encrypted? true
                              :description "Scaleway API key for TEM service"
                              :category :services
                              :service :email
                              :sensitivity :secret
                              :function :credentials})
  (ensure-config-definition! "services.scaleway-tem.from-email"
                             {:value-type :string
                              :description "From email address for outgoing emails"
                              :category :services
                              :service :email
                              :sensitivity :public
                              :function :settings}))

(defn setup-email-config []
  (print-section "Email Configuration (Scaleway TEM)")
  (println "The system uses Scaleway Transactional Email to send login codes.")
  (println "You can get these values from your Scaleway console.")
  (println "")

  ;; Ensure config definitions exist
  (ensure-email-config-definitions!)
  (println "")

  ;; Check if already configured
  (let [existing-api-key (get-global-config "services.scaleway-tem.api-key")
        existing-project-id (get-global-config "services.scaleway-tem.project-id")
        already-configured? (and existing-api-key existing-project-id)]

    (when already-configured?
      (println "Email configuration found:")
      (println "  Project ID: [configured]")
      (println "  API Key: [configured]")
      (println ""))

    (when (or (not already-configured?)
              (prompt-yn "Configure email settings?" (not already-configured?)))

      (when already-configured?
        (println ""))

      (when-not already-configured?
        (println "No email configuration found. Let's set it up.")
        (println ""))

      ;; Get region (optional, has default)
      (let [current-region (or (get-global-config "services.scaleway-tem.region") "fr-par")
            region (prompt-with-default "Scaleway region" current-region)]
        (when (not= region current-region)
          (set-global-config! "services.scaleway-tem.region" region)
          (println (str "  Set region: " region))))

      ;; Get project ID (required)
      (let [current-project-id (get-global-config "services.scaleway-tem.project-id")]
        (println "")
        (if current-project-id
          (println (str "Current project ID: " current-project-id))
          (println "Project ID is required."))
        (let [project-id (prompt-with-default "Scaleway project ID" current-project-id)]
          (when (and project-id (not= project-id current-project-id))
            (set-global-config! "services.scaleway-tem.project-id" project-id)
            (println (str "  Set project ID: " project-id)))))

      ;; Get API key (required, secret)
      (println "")
      (if existing-api-key
        (println "API key is already configured. Enter a new value to change it, or press Enter to keep.")
        (println "API key is required."))
      (let [api-key (prompt-secret "Scaleway API key (SCW_SECRET_KEY)")]
        (when (not (str/blank? api-key))
          (set-global-config! "services.scaleway-tem.api-key" api-key)
          (println "  API key updated.")))

      ;; Get from email (optional, has default)
      (let [current-from-email (or (get-global-config "services.scaleway-tem.from-email") "no-reply@digdir.cloud")]
        (println "")
        (let [from-email (prompt-with-default "From email address" current-from-email)]
          (when (not= from-email current-from-email)
            (set-global-config! "services.scaleway-tem.from-email" from-email)
            (println (str "  Set from email: " from-email)))))

      (println "")
      (println "Email configuration complete."))))

(defn ensure-typesense-config-definitions!
  "Ensure Typesense config definitions exist in the database."
  []
  (ensure-config-definition! "services.typesense.api-host"
                             {:value-type :string
                              :description "Typesense API host"
                              :category :services
                              :service :search
                              :sensitivity :internal
                              :function :settings})
  (ensure-config-definition! "services.typesense.api-tls"
                             {:value-type :boolean
                              :description "Use TLS for Typesense API"
                              :category :services
                              :service :search
                              :sensitivity :internal
                              :function :settings})
  (ensure-config-definition! "services.typesense.api-key-admin"
                             {:value-type :string
                              :encrypted? true
                              :description "Typesense admin API key"
                              :category :services
                              :service :search
                              :sensitivity :internal
                              :function :settings})
  (ensure-config-definition! "services.typesense.collection-prefix"
                             {:value-type :string
                              :description "Prefix for Typesense collection names"
                              :category :services
                              :service :search
                              :sensitivity :internal
                              :function :settings}))

(defn ensure-azure-openai-config-definitions!
  "Ensure Azure OpenAI config definitions exist in the database."
  []
  (ensure-config-definition! "services.azure-openai.api-key"
                             {:value-type :string
                              :encrypted? true
                              :description "Azure OpenAI API key"
                              :category :services
                              :service :llm
                              :sensitivity :internal
                              :function :settings})
  (ensure-config-definition! "services.azure-openai.api-endpoint"
                             {:value-type :string
                              :description "Azure OpenAI API endpoint URL"
                              :category :services
                              :service :llm
                              :sensitivity :internal
                              :function :settings})
  (ensure-config-definition! "services.azure-openai.deployment-name"
                             {:value-type :string
                              :description "Azure OpenAI deployment name"
                              :category :services
                              :service :llm
                              :sensitivity :internal
                              :function :settings})
  (ensure-config-definition! "services.azure-openai.api-version"
                             {:value-type :string
                              :description "Azure OpenAI API version"
                              :category :services
                              :service :llm
                              :sensitivity :internal
                              :function :settings})
  (ensure-config-definition! "services.azure-openai.model-name"
                             {:value-type :string
                              :description "Azure OpenAI model name (for non-Azure OpenAI)"
                              :category :services
                              :service :llm
                              :sensitivity :internal
                              :function :settings})
  (ensure-config-definition! "services.azure-openai.use-azure-openai-api"
                             {:value-type :boolean
                              :description "Use Azure OpenAI API instead of standard OpenAI"
                              :category :services
                              :service :llm
                              :sensitivity :internal
                              :function :settings}))

(defn ensure-other-services-config-definitions!
  "Ensure other service config definitions exist in the database."
  []
  ;; OpenRouter
  (ensure-config-definition! "services.openrouter.api-key"
                             {:value-type :string
                              :encrypted? true
                              :description "OpenRouter API key"
                              :category :services
                              :service :llm
                              :sensitivity :internal
                              :function :settings})
  ;; ColBERT
  (ensure-config-definition! "services.colbert.api-url"
                             {:value-type :string
                              :description "ColBERT reranking service URL"
                              :category :services
                              :service :search
                              :sensitivity :internal
                              :function :settings})
  (ensure-config-definition! "services.colbert.api-key"
                             {:value-type :string
                              :encrypted? true
                              :description "ColBERT reranking service API key"
                              :category :services
                              :service :search
                              :sensitivity :internal
                              :function :settings})
  ;; Marker
  (ensure-config-definition! "services.marker.api-url"
                             {:value-type :string
                              :encrypted? true
                              :description "Marker document parser API URL"
                              :category :services
                              :service :llm
                              :sensitivity :secret
                              :function :credentials})
  (ensure-config-definition! "services.marker.api-key"
                             {:value-type :string
                              :encrypted? true
                              :description "Marker document parser API key"
                              :category :services
                              :service :llm
                              :sensitivity :secret
                              :function :credentials})
  ;; Rate limiting
  (ensure-config-definition! "services.rate-limiting.trust-x-forwarded-for"
                             {:value-type :boolean
                              :description "Trust X-Forwarded-For header for rate limiting"
                              :category :services
                              :service :auth
                              :sensitivity :internal
                              :function :settings}))

(defn ensure-entity-config-definitions!
  "Ensure entity-scoped config definitions exist in the database."
  []
  ;; UI/Identity
  (ensure-config-definition! "ui.name"
                             {:value-type :string
                              :description "Entity property: display name"
                              :category :entities
                              :service :llm
                              :sensitivity :internal
                              :function :settings})
  (ensure-config-definition! "ui.image"
                             {:value-type :string
                              :description "Entity property: image/logo filename"
                              :category :entities
                              :service :llm
                              :sensitivity :internal
                              :function :settings})
  ;; Retrieval collections
  (ensure-config-definition! "retrieval.collection.docs"
                             {:value-type :string
                              :description "Entity property: Typesense documents collection name"
                              :category :entities
                              :service :search
                              :sensitivity :internal
                              :function :settings})
  (ensure-config-definition! "retrieval.collection.chunks"
                             {:value-type :string
                              :description "Entity property: Typesense chunks collection name"
                              :category :entities
                              :service :search
                              :sensitivity :internal
                              :function :settings})
  (ensure-config-definition! "retrieval.collection.phrases"
                             {:value-type :string
                              :description "Entity property: Typesense phrases collection name"
                              :category :entities
                              :service :search
                              :sensitivity :internal
                              :function :settings})
  ;; Rerank settings
  (ensure-config-definition! "retrieval.rerank.enabled"
                             {:value-type :boolean
                              :description "Entity property: enable ColBERT reranking"
                              :category :entities
                              :service :search
                              :sensitivity :internal
                              :function :settings})
  (ensure-config-definition! "retrieval.rerank.top-k"
                             {:value-type :number
                              :description "Entity property: number of chunks to rerank"
                              :category :entities
                              :service :search
                              :sensitivity :internal
                              :function :settings})
  (ensure-config-definition! "retrieval.rerank.max-chunk-length"
                             {:value-type :number
                              :description "Entity property: max chunk length for reranking"
                              :category :entities
                              :service :search
                              :sensitivity :internal
                              :function :settings})
  (ensure-config-definition! "retrieval.rerank.max-total-length"
                             {:value-type :number
                              :description "Entity property: max total length for reranking"
                              :category :entities
                              :service :search
                              :sensitivity :internal
                              :function :settings})
  ;; Context settings
  (ensure-config-definition! "retrieval.context.top-k"
                             {:value-type :number
                              :description "Entity property: top-k chunks for context window"
                              :category :entities
                              :service :search
                              :sensitivity :internal
                              :function :settings})
  (ensure-config-definition! "retrieval.context.max-docs"
                             {:value-type :number
                              :description "Entity property: max documents for context window"
                              :category :entities
                              :service :search
                              :sensitivity :internal
                              :function :settings})
  (ensure-config-definition! "retrieval.context.max-chunk-length"
                             {:value-type :number
                              :description "Entity property: max chunk length for context"
                              :category :entities
                              :service :search
                              :sensitivity :internal
                              :function :settings})
  (ensure-config-definition! "retrieval.context.max-total-length"
                             {:value-type :number
                              :description "Entity property: max total length for context"
                              :category :entities
                              :service :search
                              :sensitivity :internal
                              :function :settings})
  ;; Prompt templates
  (ensure-config-definition! "generate.prompt.phrase-gen"
                             {:value-type :string
                              :multiline? true
                              :description "Entity property: phrase generation prompt template"
                              :category :entities
                              :service :llm
                              :sensitivity :internal
                              :function :prompts})
  (ensure-config-definition! "generate.prompt.query-relax"
                             {:value-type :string
                              :multiline? true
                              :description "Entity property: query relaxation prompt template"
                              :category :entities
                              :service :llm
                              :sensitivity :internal
                              :function :prompts})
  (ensure-config-definition! "generate.prompt.rag-generate"
                             {:value-type :string
                              :multiline? true
                              :description "Entity property: RAG generation prompt template"
                              :category :entities
                              :service :llm
                              :sensitivity :internal
                              :function :prompts}))

(defn ensure-all-config-definitions!
  "Ensure all config definitions exist in the database.
   This is called after import to make sure all required definitions are present."
  []
  (println "Ensuring all config definitions exist...")
  (ensure-auth-config-definitions!)
  (ensure-email-config-definitions!)
  (ensure-typesense-config-definitions!)
  (ensure-azure-openai-config-definitions!)
  (ensure-other-services-config-definitions!)
  (ensure-entity-config-definitions!)
  (println "  All 45 config definitions ensured."))

(defn setup-typesense-defaults []
  "Set up default values for Typesense configuration."
  (ensure-typesense-config-definitions!)
  (let [current-prefix (get-global-config "services.typesense.collection-prefix")]
    (when-not current-prefix
      (set-global-config! "services.typesense.collection-prefix" "digdir_rag_")
      (println "  Set default Typesense collection prefix: digdir_rag_"))))

(defn resolve-file-path
  "Resolve a file path, trying multiple strategies:
   1. Absolute path as-is
   2. Relative to current working directory
   3. Relative to user home directory (if starts with ~)"
  [path]
  (let [expanded-path (if (str/starts-with? path "~")
                        (str/replace-first path "~" (System/getProperty "user.home"))
                        path)
        ;; Try as-is first (handles absolute paths)
        file1 (io/file expanded-path)]
    (if (.exists file1)
      file1
      ;; Only try relative to cwd if path is not absolute
      (when-not (.isAbsolute file1)
        (let [cwd (System/getProperty "user.dir")
              file2 (io/file cwd expanded-path)]
          (when (.exists file2)
            file2))))))

(defn prompt-for-import-file
  "Prompt user for a file path with retry support.
   Returns the resolved File object or nil if user skips."
  []
  (loop []
    (let [file-path (prompt "Enter the path to the JSON file (or 'skip' to continue without importing)")]
      (cond
        (str/blank? file-path)
        (do
          (println "  No path provided.")
          (recur))

        (= (str/lower-case file-path) "skip")
        (do
          (println "  Skipping import.")
          nil)

        :else
        (let [resolved-file (resolve-file-path file-path)
              is-absolute? (.isAbsolute (io/file file-path))]
          (if resolved-file
            (do
              (println (str "  Found file: " (.getAbsolutePath resolved-file)))
              resolved-file)
            (do
              (println (str "  File not found: " file-path))
              (when-not is-absolute?
                (println "  Tried:")
                (println (str "    - " file-path))
                (println (str "    - " (System/getProperty "user.dir") "/" file-path)))
              (println "")
              (if (prompt-yn "Try another path?" true)
                (recur)
                (do
                  (println "  Skipping import.")
                  nil)))))))))

(defn do-import-from-file!
  "Perform the actual import from a resolved file."
  [file on-conflict]
  (let [file-path (.getAbsolutePath file)]
    ;; Dry-run first
    (when (prompt-yn "Preview import first (dry-run)?" true)
      (println "")
      (println "Running dry-run preview...")
      (try
        (let [master-key (config-core/get-master-key)
              result (config-ops/import-from-file
                      (config-db/get-conn)
                      file-path
                      {:master-key master-key
                       :export-password master-key
                       :on-conflict on-conflict
                       :dry-run? true})]
          (println "")
          (println "Dry-run results:")
          (println (str "  Definitions: " (get-in result [:definitions :total] 0)
                       " total, " (get-in result [:definitions :would-create] 0) " new"))
          (println (str "  Values: " (get-in result [:values :total] 0) " total"))
          (when (= on-conflict :skip)
            (println (str "    Would create: " (get-in result [:values :would-create] 0)))
            (println (str "    Would skip: " (get-in result [:values :would-skip] 0))))
          (when (= on-conflict :overwrite)
            (println (str "    Would create: " (get-in result [:values :would-create] 0)))
            (println (str "    Would overwrite: " (get-in result [:values :would-overwrite] 0))))
          (println ""))
        (catch Exception e
          (println (str "  Error during dry-run: " (.getMessage e)))
          (println ""))))

    ;; Actual import
    (when (prompt-yn "Proceed with import?" true)
      (println "")
      (println "Importing configuration...")
      (try
        (let [master-key (config-core/get-master-key)
              result (config-ops/import-from-file
                      (config-db/get-conn)
                      file-path
                      {:master-key master-key
                       :export-password master-key
                       :on-conflict on-conflict
                       :dry-run? false})]
          (println "")
          (println "Import complete!")
          (println (str "  Definitions created: " (get-in result [:definitions :created] 0)))
          (println (str "  Values created: " (get-in result [:values :created] 0)))
          (println (str "  Values updated: " (get-in result [:values :updated] 0)))
          (println (str "  Values skipped: " (get-in result [:values :skipped] 0)))
          (when-let [missing (get-in result [:values :missing-definition])]
            (when (pos? missing)
              (println (str "  Values with missing definitions: " missing)))))
        (catch Exception e
          (println (str "  Error during import: " (.getMessage e)))
          (.printStackTrace e))))
    (println "")))

(defn setup-import-config []
  (print-section "Import Configuration")
  (println "You can import configuration from a JSON export file.")
  (println "This is useful for restoring from a backup or migrating data.")
  (println "")
  (println (str "Current directory: " (System/getProperty "user.dir")))
  (println "")

  (when (prompt-yn "Import configuration from a file?" false)
    (println "")
    (when-let [file (prompt-for-import-file)]
      (println "")
      ;; Ask for conflict resolution
      (println "When importing, if a value already exists:")
      (println "  - 'skip' will keep the existing value")
      (println "  - 'overwrite' will replace with the imported value")
      (let [conflict-choice (prompt-with-default "Conflict resolution (skip/overwrite)" "skip")
            on-conflict (if (= conflict-choice "overwrite") :overwrite :skip)]
        (println "")
        (do-import-from-file! file on-conflict)))))

(defn get-all-users []
  (let [conn (config-db/get-conn)]
    (d/q '[:find [(pull ?e [:user/id :user/email {:user/permissions [:permission/id :permission/name]}]) ...]
           :where [?e :user/id]]
         @conn)))

(defn get-admin-users []
  (let [conn (config-db/get-conn)
        db @conn]
    (d/q '[:find [(pull ?u [:user/id :user/email]) ...]
           :where
           [?p :permission/id "admin-full"]
           [?u :user/permissions ?p]]
         db)))

(defn create-admin-user!
  "Create a new user with admin-full permission."
  [email]
  (let [conn (config-db/get-conn)
        user-id (nano-id)]
    ;; Create user
    (d/transact conn [{:user/id user-id
                       :user/email email
                       :user/created (str (java.time.Instant/now))
                       :user/created-by user-id}])
    ;; Grant admin-full permission
    (perms/grant-permission! conn user-id "admin-full")
    (println (str "  Created admin user: " email))
    user-id))

(defn valid-email? [email]
  (and (string? email)
       (re-matches #".+@.+\..+" email)))

(defn add-admin-user-interactive! []
  "Interactively add admin users. Returns number of users added."
  (loop [added 0]
    (println "")
    (let [email (prompt "Enter admin email (or press Enter to finish)")]
      (if (str/blank? email)
        (do
          (when (pos? added)
            (println (str "Added " added " admin user(s).")))
          added)
        (if (valid-email? email)
          (do
            ;; Check if user already exists
            (let [db @(config-db/get-conn)
                  existing-user (perms/get-user-by-email db email)]
              (if existing-user
                (do
                  ;; User exists - check if already admin
                  (if (perms/is-admin? db (:user/id existing-user))
                    (println (str "  User " email " is already an admin."))
                    (do
                      ;; Grant admin permission to existing user
                      (perms/grant-permission! (config-db/get-conn) (:user/id existing-user) "admin-full")
                      (println (str "  Granted admin access to existing user: " email))))
                  (recur added))
                (do
                  ;; Create new admin user
                  (create-admin-user! email)
                  (recur (inc added))))))
          (do
            (println "  Invalid email format. Please try again.")
            (recur added)))))))

(defn setup-admin-users []
  (print-section "Admin User Setup")
  (println "The system requires at least one admin user to manage access.")
  (println "Admins can:")
  (println "  - Log into the admin portal")
  (println "  - Create and manage other users")
  (println "  - Grant and revoke permissions")
  (println "")

  (let [existing-admins (get-admin-users)]
    ;; Show existing admins
    (if (seq existing-admins)
      (do
        (println (str "Current admin users (" (count existing-admins) "):"))
        (doseq [{:user/keys [email]} (sort-by :user/email existing-admins)]
          (println (str "  - " email)))
        (println ""))
      (do
        (println "No admin users found.")
        (println "")))

    ;; Check for ADMIN_USER_EMAILS env var (for initial setup)
    (let [admin-emails-env (System/getenv "ADMIN_USER_EMAILS")]
      (when (and (empty? existing-admins) (not (str/blank? admin-emails-env)))
        (println "Found ADMIN_USER_EMAILS environment variable.")
        (when (prompt-yn "Create admin users from ADMIN_USER_EMAILS?" true)
          (migration/migrate-admin-users! (config-db/get-conn))
          (println ""))))

    ;; Always offer to add more admins
    (let [has-admins? (seq (get-admin-users))]
      (if has-admins?
        (when (prompt-yn "Add more admin users?" false)
          (add-admin-user-interactive!))
        (do
          (println "You need at least one admin user to manage the system.")
          (add-admin-user-interactive!)
          ;; Check again if we still have no admins
          (when (empty? (get-admin-users))
            (println "")
            (println "WARNING: No admin users configured!")
            (println "You will need to run setup again to add an admin.")))))))

(defn show-legacy-migration-option []
  (print-section "Legacy Migration")
  (println "If you are migrating from a domain-whitelist based system,")
  (println "you can run a migration to grant permissions to existing users.")
  (println "")
  (when (prompt-yn "Run legacy auth migration?" false)
    (let [cleanup? (prompt-yn "Also cleanup legacy domain entities?" false)]
      (migration/migrate-legacy-auth! (config-db/get-conn) {:cleanup? cleanup?}))))

(defn get-existing-tenants
  "Get list of existing tenants from the config database."
  []
  (let [conn (config-db/get-conn)]
    (when conn
      (config-db/list-tenants @conn))))

(defn setup-entity!
  "Set up a new entity with default values.
   In the multi-dimensional model, entities are created at the entity-only level
   (tenant=nil, environment=nil) so they can be shared across all tenants."
  [entity-id entity-name]
  (let [conn (config-db/get-conn)
        master-key (config-core/get-master-key)
        collection-prefix (or (get-global-config "services.typesense.collection-prefix") "digdir_rag_")]
    (println (str "  Creating entity: " entity-id))
    (config-db/create-entity!
     conn
     {:tenant nil         ; Entity-only level (multi-dimensional model)
      :environment nil    ; Entity-only level
      :entity-id entity-id
      :properties {:name entity-name
                   :docs-collection (str collection-prefix entity-id "_docs")
                   :chunks-collection (str collection-prefix entity-id "_chunks")
                   :phrases-collection (str collection-prefix entity-id "_phrases")
                   :rerank-enabled true
                   :rerank-top-k 30
                   :rerank-max-chunk-length 400
                   :rerank-max-total-length 16000
                   :context-top-k 10
                   :context-max-docs 5
                   :context-max-chunk-length 2000
                   :context-max-total-length 8000}
      :master-key master-key})
    (println (str "  Entity created: " entity-id " (" entity-name ")"))))

;; Keep old name for backwards compatibility
(defn setup-tenant-entity!
  "Deprecated: Use setup-entity! instead.
   Creates entity at entity-only level (tenant parameter is ignored)."
  [_tenant entity-id entity-name]
  (setup-entity! entity-id entity-name))

(defn setup-tenant-config []
  (print-section "Tenant Configuration")
  (println "Tenants are isolated configuration namespaces for multi-tenant deployments.")
  (println "Each tenant can have its own entities (bots/assistants), API keys, and settings.")
  (println "")

  (let [existing-tenants (get-existing-tenants)]
    (when (seq existing-tenants)
      (println "Existing tenants:")
      (doseq [t existing-tenants]
        (println (str "  - " t)))
      (println ""))

    (when (prompt-yn "Set up a new tenant?" false)
      (println "")
      (let [tenant-id (prompt-required "Enter tenant ID (lowercase, no spaces, e.g., 'mycompany')")]
        (if (contains? (set existing-tenants) tenant-id)
          (println (str "  Tenant '" tenant-id "' already exists."))
          (do
            ;; Prompt for display name
            (let [default-name (str/capitalize tenant-id)
                  tenant-name (prompt-with-default "Tenant display name" default-name)]
              (println (str "  Creating tenant: " tenant-id " (" tenant-name ")"))
              ;; Register the tenant in the database with name
              (config-db/register-tenant! (config-db/get-conn) tenant-id {:name tenant-name})
              (println (str "  Tenant '" tenant-id "' registered.")))
            (println "")

            ;; Optionally clone from existing tenant
            (when (and (seq existing-tenants)
                       (prompt-yn "Clone configuration from an existing tenant?" false))
              (println "Available tenants to clone from:")
              (doseq [[idx t] (map-indexed vector existing-tenants)]
                (println (str "  " (inc idx) ". " t)))
              (let [choice (prompt "Enter tenant number to clone from")
                    source-idx (try (dec (Integer/parseInt choice)) (catch Exception _ -1))]
                (when (and (>= source-idx 0) (< source-idx (count existing-tenants)))
                  (let [source-tenant (nth existing-tenants source-idx)]
                    (println (str "  Cloning configuration from: " source-tenant))
                    (try
                      (config-ops/clone-tenant
                       (config-db/get-conn)
                       source-tenant
                       tenant-id
                       {:master-key (config-core/get-master-key)})
                      (println "  Configuration cloned successfully.")
                      (catch Exception e
                        (println (str "  Warning: Could not clone configuration: " (.getMessage e)))))))))

            ;; Optionally create an entity
            (println "")
            (when (prompt-yn "Create an entity (bot/assistant) for this tenant?" true)
              (println "")
              (println "Note: Entities can be shared across tenants and environments,")
              (println "      so a generic name (e.g., 'Support Assistant') can be appropriate.")
              (println "")
              (let [default-name (str tenant-id " Assistant")
                    entity-name (prompt-with-default "Entity display name" default-name)
                    ;; Generate slug from display name
                    generated-id (-> entity-name
                                     str/lower-case
                                     (str/replace #"[^a-z0-9\s-]" "")  ; Remove special chars
                                     str/trim
                                     (str/replace #"\s+" "-"))         ; Spaces to hyphens
                    entity-id (prompt-with-default "Entity ID" generated-id)]
                (try
                  (setup-tenant-entity! tenant-id entity-id entity-name)
                  (catch Exception e
                    (println (str "  Error creating entity: " (.getMessage e)))))))

            (println "")
            (println (str "Tenant '" tenant-id "' setup complete."))
            (println "")
            (println "Next steps:")
            (println "  1. Configure service API keys for this tenant in the admin UI")
            (println "  2. Create additional entities as needed")
            (println "  3. Set up API keys for external access")))))))

(defn show-summary []
  (print-section "Configuration Summary")

  (let [all-users (get-all-users)
        admin-users (get-admin-users)
        users-with-perms (filter #(seq (:user/permissions %)) all-users)]

    (println "System users:")
    (println (str "  Total users: " (count all-users)))
    (println (str "  Users with permissions: " (count users-with-perms)))
    (println (str "  Admin users: " (count admin-users)))

    (when (seq admin-users)
      (println "")
      (println "Admin users:")
      (doseq [{:user/keys [email]} admin-users]
        (println (str "  " email))))

    (when (zero? (count users-with-perms))
      (println "")
      (println "WARNING: No users have permissions!")
      (println "Run the setup wizard again to create an admin user."))

    (println "")
    (println "User Management:")
    (println "  Admins can manage users via the API:")
    (println "    POST /api/users - Create user")
    (println "    GET /api/users - List users")
    (println "    PUT /api/users/:id/permissions - Update permissions")
    (println "    DELETE /api/users/:id - Delete user")
    (println "")
    (println "To start the service, run:")
    (println "  bb dev")
    (println "")))

(defn -main [& args]
  (print-header)

  (let [env-ok (check-env-vars)]
    (if-not env-ok
      (do
        (println "Please set the required environment variables and run setup again.")
        (System/exit 1))
      (do
        ;; Offer database reset option first
        (print-section "Database Options")
        (println "You can reset the database to start fresh, or continue with existing data.")
        (println "")
        (let [reset-requested (prompt-yn "Reset database? (WARNING: deletes all data)" false)
              reset-done (when reset-requested (reset-database!))]

          ;; Continue with setup if reset succeeded or wasn't requested
          (when (or (not reset-requested) reset-done)
            (when (check-database-connection)
              ;; Offer import early so imported config can be used
              (setup-import-config)
              ;; Ensure all definitions exist (in case import failed or was skipped)
              (ensure-all-config-definitions!)
              (setup-auth-config)
              (setup-email-config)
              ;; Set up defaults for services
              (setup-typesense-defaults)
              (setup-admin-users)
              (setup-tenant-config)
              (show-legacy-migration-option)
              (show-summary)
              (println "Setup complete!")
              (println ""))))))))

(ns digdir.setup.workflow "Interactive setup wizard for initial system configuration." (:require [clojure.string :as str] [clojure.java.io :as io] [datahike.api :as d] [nano-id.core :refer [nano-id]] [digdir.config.db :as config-db] [digdir.config.ops.bootstrap :as config-bootstrap] [digdir.config.ops.sync :as config-sync] [digdir.config.permissions :as perms] [digdir.auth.migration :as migration] [digdir.config.core :as config-core] [digdir.setup.config :as setup-config] [digdir.setup.llm :as setup-llm] [digdir.setup.common :as setup-common]))

(defn print-header [] (setup-common/print-header))
(defn print-section [title] (setup-common/print-section title))
(defn prompt [message] (setup-common/prompt message))
(defn prompt-required [message] (setup-common/prompt-required message))
(defn prompt-yn [message default] (setup-common/prompt-yn message default))
(defn check-env-vars [] (setup-common/check-env-vars))
(defn check-database-connection [] (setup-common/check-database-connection))
(defn get-db-config [] (setup-common/get-db-config))
(defn reset-database! [] (setup-common/reset-database!))

(def ^:private platform-defaults-tenant
  setup-common/platform-defaults-tenant)

(declare ensure-all-config-definitions!)

(defn get-global-config
  "Get a setup-time default Platform config value from the internal defaults tree."
  [path]
  (setup-common/get-global-config path))

(defn prompt-with-default [message default]
  (setup-common/prompt-with-default message default))

(defn prompt-secret [message]
  (setup-common/prompt-secret message))

(defn ensure-config-definition!
  [path opts]
  (setup-config/ensure-config-definition! path opts))

(defn set-global-config!
  [path value]
  (setup-config/set-global-config! path value))

(defn ensure-email-config-definitions!
  []
  (setup-config/ensure-email-config-definitions!))

(defn ensure-typesense-config-definitions!
  []
  (setup-config/ensure-typesense-config-definitions!))

(defn ensure-azure-openai-config-definitions!
  []
  (setup-config/ensure-azure-openai-config-definitions!))

(defn ensure-other-services-config-definitions!
  []
  (setup-config/ensure-other-services-config-definitions!))

(defn ensure-pipeline-config-definitions!
  []
  (setup-config/ensure-pipeline-config-definitions!))

(defn ensure-skill-config-definitions!
  []
  (setup-config/ensure-skill-config-definitions!))

(defn ensure-all-config-definitions!
  []
  (setup-config/ensure-all-config-definitions!))

(defn setup-typesense-defaults
  []
  (setup-config/setup-typesense-defaults))

(defn setup-email-config [] (print-section "Email Configuration (Scaleway TEM)") (println "The system uses Scaleway Transactional Email to send login codes.") (println "You can get these values from your Scaleway console.") (println "") (ensure-email-config-definitions!) (println "") (let [existing-api-key (get-global-config "services.scaleway-tem.api-key") existing-project-id (get-global-config "services.scaleway-tem.project-id") already-configured? (and existing-api-key existing-project-id)] (when already-configured? (println "Email configuration found:") (println "  Project ID: [configured]") (println "  API Key: [configured]") (println "")) (when (or (not already-configured?) (prompt-yn "Configure email settings?" (not already-configured?))) (when already-configured? (println "")) (when-not already-configured? (println "No email configuration found. Let's set it up.") (println "")) (let [current-region (or (get-global-config "services.scaleway-tem.region") "fr-par") region (prompt-with-default "Scaleway region" current-region)] (when (not= region current-region) (set-global-config! "services.scaleway-tem.region" region) (println (str "  Set region: " region)))) (let [current-project-id (get-global-config "services.scaleway-tem.project-id")] (println "") (if current-project-id (println (str "Current project ID: " current-project-id)) (println "Project ID is required.")) (let [project-id (prompt-with-default "Scaleway project ID" current-project-id)] (when (and project-id (not= project-id current-project-id)) (set-global-config! "services.scaleway-tem.project-id" project-id) (println (str "  Set project ID: " project-id))))) (println "") (if existing-api-key (println "API key is already configured. Enter a new value to change it, or press Enter to keep.") (println "API key is required.")) (let [api-key (prompt-secret "Scaleway API key (SCW_SECRET_KEY)")] (when (not (str/blank? api-key)) (set-global-config! "services.scaleway-tem.api-key" api-key) (println "  API key updated."))) (let [current-from-email (or (get-global-config "services.scaleway-tem.from-email") "no-reply@digdir.cloud")] (println "") (let [from-email (prompt-with-default "From email address" current-from-email)] (when (not= from-email current-from-email) (set-global-config! "services.scaleway-tem.from-email" from-email) (println (str "  Set from email: " from-email))))) (println "") (println "Email configuration complete."))))

(defn resolve-file-path "Resolve a file path, trying multiple strategies:\n   1. Absolute path as-is\n   2. Relative to current working directory\n   3. Relative to user home directory (if starts with ~)" [path] (let [expanded-path (if (str/starts-with? path "~") (str/replace-first path "~" (System/getProperty "user.home")) path) file1 (io/file expanded-path)] (if (.exists file1) file1 (when-not (.isAbsolute file1) (let [cwd (System/getProperty "user.dir") file2 (io/file cwd expanded-path)] (when (.exists file2) file2))))))

(defn prompt-for-import-file "Prompt user for a file path with retry support.\n   Returns the resolved File object or nil if user skips." [] (loop [] (let [file-path (setup-common/read-answer "Enter the path to the JSON file (or 'skip' to continue without importing)")] (cond (setup-common/eof? file-path) (do (println "  No input available - skipping import.") nil) (str/blank? file-path) (do (println "  No path provided.") (recur)) (= (str/lower-case file-path) "skip") (do (println "  Skipping import.") nil) :else (let [resolved-file (resolve-file-path file-path) is-absolute? (.isAbsolute (io/file file-path))] (if resolved-file (do (println (str "  Found file: " (.getAbsolutePath resolved-file))) resolved-file) (do (println (str "  File not found: " file-path)) (when-not is-absolute? (println "  Tried:") (println (str "    - " file-path)) (println (str "    - " (System/getProperty "user.dir") "/" file-path))) (println "") (if (prompt-yn "Try another path?" true) (recur) (do (println "  Skipping import.") nil)))))))))

(defn do-import-from-file! "Perform the actual import from a resolved file." [file on-conflict] (let [file-path (.getAbsolutePath file)] (when (prompt-yn "Preview import first (dry-run)?" true) (println "") (println "Running dry-run preview...") (try (let [master-key (config-core/get-master-key) result (config-sync/import-from-file (config-db/get-conn) file-path {:master-key master-key, :export-password master-key, :on-conflict on-conflict, :dry-run? true})] (println "") (println "Dry-run results:") (println (str "  Definitions: " (get-in result [:definitions :total] 0) " total, " (get-in result [:definitions :would-create] 0) " new")) (println (str "  Nodes: " (get-in result [:nodes :total] 0) " total")) (println (str "  Bindings: " (get-in result [:bindings :total] 0) " total")) (println (str "  Node values: " (get-in result [:node-values :total] 0) " total")) (println "")) (catch Exception e (println (str "  Error during dry-run: " (.getMessage e))) (println "")))) (when (prompt-yn "Proceed with import?" true) (println "") (println "Importing configuration...") (try (let [master-key (config-core/get-master-key) result (config-sync/import-from-file (config-db/get-conn) file-path {:master-key master-key, :export-password master-key, :on-conflict on-conflict, :dry-run? false})] (println "") (println "Import complete!") (println (str "  Definitions created: " (get-in result [:definitions :created] 0))) (println (str "  Nodes created: " (get-in result [:nodes :created] 0))) (println (str "  Nodes updated: " (get-in result [:nodes :updated] 0))) (println (str "  Bindings created: " (get-in result [:bindings :created] 0))) (println (str "  Bindings updated: " (get-in result [:bindings :updated] 0))) (println (str "  Node values created: " (get-in result [:node-values :created] 0))) (println (str "  Node values updated: " (get-in result [:node-values :updated] 0))) (println (str "  Node values skipped: " (get-in result [:node-values :skipped] 0)))) (catch Exception e (println (str "  Error during import: " (.getMessage e))) (.printStackTrace e)))) (println "")))

(defn setup-import-config [] (print-section "Import Configuration") (println "You can import configuration from a JSON export file.") (println "This is useful for restoring from a backup or migrating data.") (println "") (println (str "Current directory: " (System/getProperty "user.dir"))) (println "") (when (prompt-yn "Import configuration from a file?" false) (println "") (when-let [file (prompt-for-import-file)] (println "") (println "When importing, if a config entity already exists:") (println "  - 'skip' will keep the existing entity") (println "  - 'overwrite' will replace or update the imported entity") (let [conflict-choice (prompt-with-default "Conflict resolution (skip/overwrite)" "skip") on-conflict (if (= conflict-choice "overwrite") :overwrite :skip)] (println "") (do-import-from-file! file on-conflict)))))

(defn get-all-users [] (let [conn (config-db/get-conn)] (d/q (quote [:find [(pull ?e [:user/id :user/email #:user{:permissions [:permission/id :permission/name]}]) ...] :where [?e :user/id]]) (clojure.core/deref conn))))

(defn get-admin-users [] (let [conn (config-db/get-conn) db (clojure.core/deref conn)] (d/q (quote [:find [(pull ?u [:user/id :user/email]) ...] :where [?p :permission/id "admin-full"] [?u :user/permissions ?p]]) db)))

(defn create-admin-user! "Create a new user with admin-full permission." [email] (let [conn (config-db/get-conn) user-id (nano-id)] (d/transact conn [#:user{:id user-id, :email email, :preferred-language "en", :created (str (java.time.Instant/now)), :created-by user-id}]) (perms/grant-permission! conn user-id "admin-full") (println (str "  Created admin user: " email)) user-id))

(defn valid-email? [email] (and (string? email) (re-matches (re-pattern ".+@.+\\..+") email)))

(defn add-admin-user-interactive! "Interactively add admin users. Returns number of users added." [] (loop [added 0] (println "") (let [email (prompt "Enter admin email (or press Enter to finish)")] (if (str/blank? email) (do (when (pos? added) (println (str "Added " added " admin user(s)."))) added) (if (valid-email? email) (let [db (clojure.core/deref (config-db/get-conn)) existing-user (perms/get-user-by-email db email)] (if existing-user (do (if (perms/is-admin? db (:user/id existing-user)) (println (str "  User " email " is already an admin.")) (do (perms/grant-permission! (config-db/get-conn) (:user/id existing-user) "admin-full") (println (str "  Granted admin access to existing user: " email)))) (recur added)) (do (create-admin-user! email) (recur (inc added))))) (do (println "  Invalid email format. Please try again.") (recur added)))))))

(defn setup-admin-users [] (print-section "Admin User Setup") (println "The system requires at least one admin user to manage access.") (println "Admins can:") (println "  - Log into the admin portal") (println "  - Create and manage other users") (println "  - Grant and revoke permissions") (println "") (let [existing-admins (get-admin-users)] (if (seq existing-admins) (do (println (str "Current admin users (" (count existing-admins) "):")) (doseq [#:user{:keys [email]} (sort-by :user/email existing-admins)] (println (str "  - " email))) (println "")) (do (println "No admin users found.") (println ""))) (let [admin-emails-env (System/getenv "ADMIN_USER_EMAILS")] (when (and (empty? existing-admins) (not (str/blank? admin-emails-env))) (println "Found ADMIN_USER_EMAILS environment variable.") (when (prompt-yn "Create admin users from ADMIN_USER_EMAILS?" true) (migration/migrate-admin-users! (config-db/get-conn)) (println "")))) (let [has-admins? (seq (get-admin-users))] (if has-admins? (when (prompt-yn "Add more admin users?" false) (add-admin-user-interactive!)) (do (println "You need at least one admin user to manage the system.") (add-admin-user-interactive!) (when (empty? (get-admin-users)) (println "") (println "WARNING: No admin users configured!") (println "You will need to run setup again to add an admin.")))))))

(defn show-legacy-migration-option [] (print-section "Legacy Migration") (println "If you are migrating from a domain-whitelist based system,") (println "you can run a migration to grant permissions to existing users.") (println "") (when (prompt-yn "Run legacy auth migration?" false) (let [cleanup? (prompt-yn "Also cleanup legacy domain entities?" false)] (migration/migrate-legacy-auth! (config-db/get-conn) {:cleanup? cleanup?}))))

(defn get-existing-tenants "Get list of existing tenants from the config database." [] (let [conn (config-db/get-conn)] (when conn (config-db/list-tenants (clojure.core/deref conn)))))

(def default-runtime-bootstrap-agent-id "builtin/agent-rag-agent")

(def default-runtime-bootstrap-values
  {:retrieval-top-k 100
   :rerank-enabled true
   :retrieval-max-per-document 10
   :synthesis-max-docs 5
   :rerank-context-min-chunks 8
   :rerank-top-k 30
   :rerank-context-relative-score-threshold 0.85
   :retrieval-query-aware-boost true
   ;; Mode-namespaced rerank knobs — RAG-mode values shrink chunks for LLM
   ;; synthesis budgets; retrieval-mode values let raw chunk return be larger.
   ;;
   ;; RAG budget raised 400 -> 4000 (#463). 400 was below the chunk length of
   ;; essentially every corpus we run: measured on production's KUDOS chunks,
   ;; 95.9% of 713,923 chunks exceed 400 (median 1,354, p95 7,163), so the
   ;; reranker was scoring a ~360-character window of a typical chunk and its
   ;; judgement of that chunk was uninformed — silently, with nothing logged.
   ;; 4000 matches the retrieval-mode value below, so the two modes now agree.
   :rerank-rag-max-chunk-length 4000
   :rerank-rag-max-total-length 16000
   :rerank-rag-max-context-length 8000
   :rerank-rag-context-top-k 10
   :rerank-rag-context-max-chunk-length 2000
   :rerank-retrieval-max-chunk-length 4000
   :rerank-retrieval-max-total-length 32000
   :rerank-retrieval-max-context-length 32000
   :rerank-retrieval-context-top-k 10
   :rerank-retrieval-context-max-chunk-length 4000})

(defn- resolved-platform-default-values
  [tenant-id]
  (try
    (let [conn (config-db/get-conn)]
      (if conn
        (config-db/load-resolved-config @conn tenant-id "default" (config-core/get-master-key))
        {}))
    (catch clojure.lang.ExceptionInfo _
      {})))

(defn- strip-inherit-owned
  "Remove paths whose definition has :config-def/ownership :inherit. Those paths
   are served from the __global__ tenant at read time and should not be forked
   into a new tenant's tree at bootstrap."
  [db values]
  (into {}
        (remove (fn [[path _]]
                  (= :inherit (:config-def/ownership (config-db/get-definition db path)))))
        values))

(defn bootstrap-tenant-platform-tree! "Bootstrap the default V2 platform tree for a tenant/config-key.\n\n   By default this copies the current setup-time Platform defaults tree into the\n   tenant's canonical `default` Platform node. Inherit-owned definitions are\n   skipped — new tenants read them live from the __global__ tenant." ([tenant-id] (bootstrap-tenant-platform-tree! tenant-id {})) ([tenant-id {:keys [tenant-name created-by platform-values tenant-config-key], :or {created-by "setup"}}] (ensure-all-config-definitions!) (let [conn (or (config-db/get-conn) (throw (ex-info "Config database connection not available" {:tenant tenant-id}))) raw-values (or platform-values (resolved-platform-default-values platform-defaults-tenant)) fork-values (strip-inherit-owned @conn raw-values)] (config-bootstrap/bootstrap-config-tree! conn {:root :platform, :tenant tenant-id, :tenant-name (or tenant-name tenant-id), :created-by created-by, :single-node? true, :base-tenant-config-key (or tenant-config-key "default"), :base-values fork-values, :master-key (config-core/get-master-key)}))))

(defn- ensure-dataset-entities! [conn dataset-id pipeline-id] (when-not (config-db/get-dataset-record (clojure.core/deref conn) dataset-id) (config-db/create-dataset! conn {:dataset-id dataset-id, :name dataset-id})) (when-not (config-db/get-dataset-pipeline (clojure.core/deref conn) pipeline-id) (config-db/create-dataset-pipeline! conn {:pipeline-id pipeline-id, :dataset-id dataset-id})))

(defn bootstrap-tenant-dataset-tree! "Bootstrap the default V2 dataset/materialization tree for one tenant/tenant-config-key/pipeline.\n\n   This now creates the dataset/materialization tree directly from explicit values\n   or an empty leaf, rather than reading retired tuple-scoped pipeline config." ([tenant-id tenant-config-key pipeline-id] (bootstrap-tenant-dataset-tree! tenant-id tenant-config-key pipeline-id {})) ([tenant-id tenant-config-key pipeline-id {:keys [tenant-name created-by dataset-id dataset-values], :or {created-by "setup"}}] (ensure-pipeline-config-definitions!) (let [conn (or (config-db/get-conn) (throw (ex-info "Config database connection not available" {:tenant tenant-id, :tenant-config-key tenant-config-key, :pipeline-id pipeline-id}))) dataset-id (or dataset-id pipeline-id) dataset-values (merge {:name pipeline-id} (or dataset-values {}))] (ensure-dataset-entities! conn dataset-id pipeline-id) (config-bootstrap/bootstrap-dataset-tree! conn {:tenant tenant-id, :materialization-tenant-config-key (config-db/default-dataset-tenant-config-key tenant-config-key pipeline-id), :base-node-id (str "dataset/" tenant-id "/default"), :materialization-label (str pipeline-id " Materialization"), :dataset-id dataset-id, :materialization-node-id (str "dataset/" tenant-id "/" (or tenant-config-key "_") "/" pipeline-id "/materialization"), :created-by created-by, :tenant-name (or tenant-name tenant-id), :dataset-values dataset-values, :master-key (config-core/get-master-key), :pipeline-id pipeline-id}))))

(defn bootstrap-tenant-runtime-tree! "Bootstrap the default V2 runtime tree for a tenant.\n\n   This seeds the canonical runtime `default` node for the default builtin\n   playground agent so the V2 runtime resolver can be exercised in dev/test without manual config-tree\n   transactions." ([tenant-id] (bootstrap-tenant-runtime-tree! tenant-id {})) ([tenant-id {:keys [tenant-name created-by agent-id dataset-id runtime-values], :or {created-by "setup", agent-id default-runtime-bootstrap-agent-id, runtime-values default-runtime-bootstrap-values}}] (ensure-skill-config-definitions!) (when-not (config-db/get-conn) (throw (ex-info "Config database connection not available" {:tenant tenant-id}))) (config-bootstrap/bootstrap-runtime-tree! (config-db/get-conn) {:tenant tenant-id, :tenant-name (or tenant-name tenant-id), :created-by created-by, :single-node? true, :agent-id agent-id, :dataset-id dataset-id, :runtime-values runtime-values, :master-key (config-core/get-master-key)})))

(defn setup-llm-provider
  "Pick the LLM provider the agent's calls go to (Azure OpenAI, or any
   OpenAI-compatible server including a local one). See `digdir.setup.llm`."
  []
  (setup-llm/setup-llm-provider))

(defn setup-tenant-config [] (print-section "Tenant Configuration") (println "Tenants are isolated configuration namespaces for multi-tenant deployments.") (println "Each tenant can have its own pipelines (bots/assistants), API keys, and settings.") (println "") (let [existing-tenants (get-existing-tenants)] (when (seq existing-tenants) (println "Existing tenants:") (doseq [t existing-tenants] (println (str "  - " t))) (println "")) (when (prompt-yn "Set up a new tenant?" false) (println "") (let [tenant-id (prompt-required "Enter tenant ID (lowercase, no spaces, e.g., 'mycompany')")] (if (contains? (set existing-tenants) tenant-id) (println (str "  Tenant '" tenant-id "' already exists.")) (do (let [default-name (str/capitalize tenant-id) tenant-name (prompt-with-default "Tenant display name" default-name)] (println (str "  Creating tenant: " tenant-id " (" tenant-name ")")) (config-db/register-tenant! (config-db/get-conn) tenant-id {:name tenant-name}) (println (str "  Tenant '" tenant-id "' registered.")) (try (bootstrap-tenant-platform-tree! tenant-id {:tenant-name tenant-name}) (println (str "  Seeded canonical default V2 platform node for tenant '" tenant-id "'.")) (catch Exception e (println (str "  Warning: Could not seed default V2 platform tree: " (.getMessage e))))) (try (bootstrap-tenant-runtime-tree! tenant-id {:tenant-name tenant-name}) (println (str "  Seeded canonical default V2 runtime node for tenant '" tenant-id "' using agent '" default-runtime-bootstrap-agent-id "'.")) (catch Exception e (println (str "  Warning: Could not seed default V2 runtime tree: " (.getMessage e)))))) (println "") (println "") (println "") (println (str "Tenant '" tenant-id "' setup complete.")) (println "") (println "Next steps:") (println "  1. Configure service API keys for this tenant in the Operator Console") (println "  2. Create additional pipelines as needed") (println "  3. Set up API keys for external access")))))))

(defn show-summary [] (print-section "Configuration Summary") (let [all-users (get-all-users) admin-users (get-admin-users) users-with-perms (filter (fn* [p1__3767#] (seq (:user/permissions p1__3767#))) all-users)] (println "System users:") (println (str "  Total users: " (count all-users))) (println (str "  Users with permissions: " (count users-with-perms))) (println (str "  Admin users: " (count admin-users))) (when (seq admin-users) (println "") (println "Admin users:") (doseq [#:user{:keys [email]} admin-users] (println (str "  " email)))) (when (zero? (count users-with-perms)) (println "") (println "WARNING: No users have permissions!") (println "Run the setup wizard again to create an admin user.")) (println "") (println "User Management:") (println "  Admins can manage users via the API:") (println "    POST /api/users - Create user") (println "    GET /api/users - List users") (println "    PUT /api/users/:id/permissions - Update permissions") (println "    DELETE /api/users/:id - Delete user") (println "") (println "To start the service, run:") (println "  bb dev") (println "")))

(defn -main [& _args] (print-header) (let [env-ok (check-env-vars)] (if-not env-ok (do (println "Please set the required environment variables and run setup again.") (System/exit 1)) (do (print-section "Database Options") (println "You can reset the database to start fresh, or continue with existing data.") (println "") (let [reset-requested (prompt-yn "Reset database? (WARNING: deletes all data)" false) reset-done (when reset-requested (reset-database!))] (when (or (not reset-requested) reset-done) (when (check-database-connection) (setup-import-config) (ensure-all-config-definitions!) (setup-email-config) (setup-typesense-defaults) (setup-admin-users) (setup-tenant-config) (setup-llm-provider) (show-legacy-migration-option) (show-summary) (println "Setup complete!") (println "")))))) (flush) (System/exit 0)))

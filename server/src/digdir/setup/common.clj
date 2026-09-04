(ns digdir.setup.common
  "Shared setup helpers extracted from `digdir.setup`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [digdir.config.accessor :as accessor]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.config.env-bridge :as env-bridge]
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

(def eof
  "Sentinel `read-answer` returns when stdin is closed or exhausted."
  ::eof)

(defn eof? [answer] (= eof answer))

(defn read-answer
  "Print `message` and read one trimmed line from stdin, or `eof` when stdin is
   closed or exhausted.

   `read-line` returns nil at end of input, and passing that straight to
   `str/trim` is what used to end the wizard in a NullPointerException.

   Callers that can accept a blank answer should use `prompt`, which folds EOF
   into \"\" so a closed stdin reads the same as pressing Enter. Callers that
   loop until they get a usable answer must handle `eof` themselves, or they
   spin forever on an exhausted stream."
  [message]
  (print (str message ": "))
  (flush)
  (if-let [line (read-line)]
    (str/trim line)
    eof))

(defn exit!
  "Indirection over System/exit so a test can observe the exit rather than
   having the test JVM killed under it."
  [status]
  (System/exit status))

(defn- abort-no-input!
  "End of input on a prompt that has no default to fall back on. Explains and
   exits non-zero instead of looping forever on an exhausted stdin."
  [message]
  (println "")
  (println (str "ERROR: no input available for a required value (" message ")."))
  (println "stdin is closed or exhausted, and this prompt has no default.")
  (println "Run `bb setup` interactively, or pipe an answer for every prompt.")
  (exit! 1))

(defn prompt
  "Prompt for one line. A closed or exhausted stdin reads as empty, so every
   caller that already treats blank as \"use the default\", \"skip\" or
   \"finish\" behaves the same way when the wizard is scripted."
  [message]
  (let [answer (read-answer message)]
    (if (eof? answer) "" answer)))

(defn prompt-required [message]
  (loop []
    (let [answer (read-answer message)]
      (cond
        ;; Not (recur): blank is retryable, end-of-input is not.
        (eof? answer) (abort-no-input! message)

        (str/blank? answer)
        (do
          (println "  This field is required. Please enter a value.")
          (recur))

        :else answer))))

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

(def ^:private database-services
  "The two backend families, as `digdir.config.env-bridge` groups them.

   DERIVED from the table's own `:alternative-group`, not restated. This set
   and the boot gate in `digdir.boot.required-env` have to agree about which
   services are alternatives, and two copies of that answer are free to
   disagree — the defect class #521 is about."
  (env-bridge/services-in-alternative-group :database))

(def ^:private file-backend-vars
  (env-bridge/env-vars-for-service :database-file))

(def ^:private postgres-backend-vars
  (env-bridge/env-vars-for-service :database-postgres))

(defn selected-db-backend
  "Which database backend the current environment selects.

   Mirrors `digdir.config.core/load-bootstrap-config`: DATAHIKE_FILE_PATH picks
   the local file backend and takes precedence over ADH_POSTGRES_URL, which
   picks the remote JDBC/Postgres backend. Returns :local, :remote, or nil when
   neither pointer is set.

   The 1-arity takes an env lookup fn so the precedence is testable without
   mutating the process environment."
  ([] (selected-db-backend #(System/getenv %)))
  ([getenv]
   (cond
     (not (str/blank? (getenv "DATAHIKE_FILE_PATH"))) :local
     (not (str/blank? (getenv "ADH_POSTGRES_URL"))) :remote
     :else nil)))

(defn check-env-vars []
  (print-section "Environment Variables Check")
  ;; Every list below the database pointers is DERIVED from
  ;; `digdir.config.env-bridge/env-config-bindings`, never typed here. The
  ;; hand-maintained version of this table listed `TYPESENSE_API_KEY` - a
  ;; variable nothing in the running system consults - and reported
  ;; `TYPESENSE_API_KEY_ADMIN` as merely "optional" while Typesense read its
  ;; key only from the config DB, so setting it changed nothing. A table that
  ;; reports on variables the system ignores is worse than no table: it sends
  ;; someone away satisfied.
  (let [backend (selected-db-backend)
        db-vars (case backend
                  :local file-backend-vars
                  :remote postgres-backend-vars
                  ;; No pointer set at all - list both options so the reader can
                  ;; see which one to pick.
                  (concat file-backend-vars (take 1 postgres-backend-vars)))
        ;; The backend families are boot-tier too, but they are a CHOICE
        ;; between two groups rather than a flat list - `db-vars` above has
        ;; already picked the right one, so they must not be appended twice.
        required-vars (concat db-vars
                              (->> (env-bridge/bindings-for-tier :boot)
                                   (remove #(contains? database-services (:service %)))
                                   (mapv :env-var)))
        ;; The LLM half of this screen is grouped by which provider the switch
        ;; selects, not left flat. Flat, it read as one Azure block - and TWO
        ;; of the six AZURE_OPENAI_* rows are the variables the LOCAL path
        ;; sets, so a reader following onboarding 4a met AZURE_OPENAI_USE_AZURE
        ;; under a heading that looked like it did not apply to them. The
        ;; naming trap 4a exists to defuse had reappeared on the one screen
        ;; whose job is to list these. Grouping is derived from the :provider
        ;; tag, so there is still no second list.
        azure-vars (env-bridge/first-query-bindings :azure)
        local-vars (env-bridge/first-query-bindings :openai-compatible)
        both (filterv (fn [b] (some #(= (:env-var b) (:env-var %)) local-vars)) azure-vars)
        both-names (set (map :env-var both))
        only (fn [bs] (remove #(contains? both-names (:env-var %)) bs))
        azure-only (only azure-vars)
        local-only (only local-vars)
        local-names (set (map :env-var local-only))
        ;; OPENAI_API_ENDPOINT / OPENAI_API_KEY sit at :optional because only
        ;; one of the two paths uses them. On that path they are the two
        ;; variables it cannot work without, so they are shown above with their
        ;; provider and removed from here rather than listed twice.
        optional-vars (->> (env-bridge/bindings-for-tier :optional)
                           (remove #(contains? local-names (:env-var %)))
                           (mapv :env-var))
        check-var (fn [var-name]
                    (let [value (System/getenv var-name)
                          status (if (str/blank? value) "MISSING" "OK")
                          ;; Marks which lines are credentials. The status
                          ;; column is safe to paste into a chat window; this
                          ;; says which of the VALUES behind it are not.
                          kind (if (env-bridge/secret-env-var? var-name) "secret" "")]
                      (println (format "  %-30s %-8s %s" var-name status kind))
                      (not (str/blank? value))))]
    (println (case backend
               :local "Database backend: local file (DATAHIKE_FILE_PATH)"
               :remote "Database backend: remote Postgres (ADH_POSTGRES_URL)"
               "Database backend: NONE SELECTED"))
    (println "")
    (println "Required environment variables:")
    ;; mapv, not a lazy map: `every?` may stop consuming at the first false, so
    ;; the side-effecting prints have to be forced before the check runs.
    (let [required-ok (and (some? backend)
                           (every? identity (mapv check-var required-vars)))]
      (println "")
      (println "Needed for a real query (written into the config DB on import):")
      (doseq [b both]
        (check-var (:env-var b)))
      (println "")
      (println "  ...then ONE LLM provider. AZURE_OPENAI_USE_AZURE above picks which,")
      (println "  and the services.azure-openai.* config family drives BOTH — the name")
      (println "  is historical, not a scope. See docs/onboarding.md §4a.")
      (println "")
      (println "  with AZURE_OPENAI_USE_AZURE=true (the shipped default) — Azure OpenAI:")
      (doseq [b azure-only]
        (check-var (:env-var b)))
      (println "")
      (println "  with AZURE_OPENAI_USE_AZURE=false — any OpenAI-compatible server,")
      (println "  local or hosted (these are NOT Azure, despite two of the names):")
      (doseq [b local-only]
        (check-var (:env-var b)))
      (println "")
      (println "Optional environment variables:")
      (doseq [v optional-vars]
        (check-var v))
      (println "")
      (when-not required-ok
        (println "WARNING: Some required environment variables are missing.")
        (println "Please set them before running the service.")
        (println "")
        (println "Pick ONE database backend:")
        (println "")
        (println "  # Local file - preferred for dev/test, needs no Postgres:")
        (println "  export DATAHIKE_FILE_PATH=./local-db/dh_dev_v1")
        (println "")
        (println "  # ...or remote Postgres:")
        (println "  export ADH_POSTGRES_URL=postgresql://user:pass@host:5432/dbname")
        (println "  export ADH_POSTGRES_USER=your_user")
        (println "  export ADH_POSTGRES_PWD=your_password")
        (println "")
        (println "Always required (add to your shell profile or mise.toml):")
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
      (case (selected-db-backend)
        :local (do
                 (println "  - DATAHIKE_FILE_PATH must point at a path this process can write")
                 (println (str "  - Current value: " (System/getenv "DATAHIKE_FILE_PATH"))))
        :remote (do
                  (println "  - ADH_POSTGRES_URL should be a valid JDBC URL")
                  (println "  - ADH_POSTGRES_USER and ADH_POSTGRES_PWD should be set"))
        (println "  - Set DATAHIKE_FILE_PATH (local file) or ADH_POSTGRES_URL (Postgres)"))
      false)))

(defn get-db-config
  "Get the database configuration from bootstrap config.

   `load-bootstrap-config` stores the store config under the :db-env key itself
   (`{:local {...} :db-env :local}`), which is also how `digdir.data.db/init-db!`
   reads it - there is no intermediate :db level."
  []
  (let [bootstrap @config-core/!bootstrap-config
        db-env (:db-env bootstrap)]
    (get bootstrap db-env)))

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
                  (when (d/database-exists? cfg)
                    (d/delete-database cfg)
                    (println "  Deleted existing database."))
                  (d/create-database cfg)
                  (println "  Created new database.")
                  (let [conn (d/connect cfg)]
                    (d/transact conn {:tx-data data-db/dh-schema})
                    (println "  Initialized base schema.")
                    (config-db/set-conn! conn)
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

(def platform-defaults-tenant
  config-core/platform-defaults-tenant)

(defn get-global-config
  "Read a setup-time platform config value. Inherit-owned paths come from the
   __global__ tenant; fork-owned paths come from the __platform-defaults__ seed
   tree."
  [path]
  (when-let [conn (config-db/get-conn)]
    (try
      (let [definition (config-db/get-definition @conn path)
            tenant (if (= :inherit (:config-def/ownership definition))
                     config-core/global-tenant
                     platform-defaults-tenant)]
        (accessor/get-platform-value path {:tenant tenant
                                           :tenant-config-key "default"
                                           :default nil}))
      (catch Exception _ nil))))

(defn prompt-with-default
  "Prompt for input with a default value shown."
  [message default]
  (let [hint (if default (str " [" default "]") "")
        input (prompt (str message hint))]
    (if (str/blank? input)
      default
      input)))

(defn prompt-secret
  "Prompt for a secret value (won't echo an existing value back). Same input
   handling as `prompt` — kept as its own fn so masking can be added here
   later without touching call sites."
  [message]
  (prompt message))

;; =============================================================================
;; Refusing to seed while the server is running (#493)
;; =============================================================================

(def ^:private seed-guard-override
  "DIGDIR_ALLOW_SEED_WITH_SERVER_RUNNING")

(defn- health-2xx?
  "True only when host:port answers `/up` with a 2xx.

   ⚠️ A bare TCP accept is NOT enough, and it fails in BOTH directions: any
   unrelated process holding the port produces a FALSE REFUSAL that blocks a
   legitimate seed, and a half-dead JVM still holding its listening socket reads
   as healthy. Only a real health response distinguishes them.

   `/up` is public and unauthenticated (`digdir.api.http/auth-routes`), so this
   needs no credential."
  [host port]
  (try
    (let [url (.toURL (java.net.URI. (str "http://" host ":" port "/up")))
          conn ^java.net.HttpURLConnection (.openConnection url)]
      (try
        (doto conn
          (.setRequestMethod "GET")
          (.setConnectTimeout 500)
          (.setReadTimeout 500)
          (.setInstanceFollowRedirects false))
        (<= 200 (.getResponseCode conn) 299)
        (finally (.disconnect conn))))
    (catch Exception _ false)))

(defn- parse-address [s]
  (let [[h p] (str/split (str/trim s) #":" 2)]
    (when-not (str/blank? h)
      [h (or (some-> p str/trim parse-long) 8080)])))

(defn probe-addresses
  "Every address a server sharing this database could be answering on.

   THREE DOORS, and a guard that checks fewer passes cleanly from the one it
   cannot see — each of the first two was MEASURED to be blind to the other:

     127.0.0.1:<container-port>  `docker compose exec` runs INSIDE the server's
                                 own container, where the server is on loopback.
     digdir-rag:<container-port> `docker compose run` runs in a SEPARATE
                                 container on the same network, where loopback
                                 is that container's own and reaches nothing.
     127.0.0.1:<HTTP_PORT>       `bb setup` runs on the HOST, against a `bb dev`
                                 that listens on HTTP_PORT (default 8081,
                                 `dev.cljc`). That variable exists so sibling
                                 worktrees run in parallel, so the port is
                                 per-worktree and the container addresses never
                                 see it."
  []
  (if-let [override (System/getenv "DIGDIR_SEED_GUARD_ADDRESSES")]
    (vec (keep parse-address (str/split override #",")))
    (let [container-port (or (some-> (System/getenv "PORT") str/trim parse-long) 8080)
          host-port (or (some-> (System/getenv "HTTP_PORT") str/trim parse-long) 8081)]
      (vec (distinct [["127.0.0.1" container-port]
                      ["digdir-rag" container-port]
                      ["127.0.0.1" host-port]])))))

(defn running-server-address
  "\"host:port\" of a live server, or nil when none answers.

   The 1-arity takes the addresses explicitly so a test can point it at a real
   HTTP server it started itself — a probe that cannot be made to find something
   is a probe that proves nothing."
  ([] (running-server-address (probe-addresses)))
  ([addresses]
   (when-let [[host port] (first (filter (fn [[h p]] (health-2xx? h p)) addresses))]
     (str host ":" port))))

(defn refuse-if-server-running!
  "Exit non-zero when a server is already up, because seeding past it silently
   loses the write.

   ⚠️ MEASURED, twice, on two independent fresh container stacks (#493): with the
   server running, `digdir.setup.first-admin` reported `[CREATED] … ✔ created 1`
   and the account did not exist afterwards. A second JVM reading the same store
   reported `can-login? -> false` and zero admins IMMEDIATELY after the command
   and again 45s later, and the documented `docker compose restart` did NOT
   recover it. With the server stopped, the identical command persisted — a
   re-run reported `[SKIPPED] Already has admin-full`.

   So the failure is not a stale connection on the server's side, and a restart
   is not a remedy: the write never lands. Ordering the operations correctly
   removes the restart rather than re-ordering it — on a fresh volume seeded with
   the server down, login succeeded on the FIRST attempt with no restart at all.

   The command reports success either way, which is what makes it worth refusing
   rather than documenting."
  ([command-label] (refuse-if-server-running! command-label (probe-addresses)))
  ([command-label addresses]
   (when-not (= "true" (some-> (System/getenv seed-guard-override) str/trim str/lower-case))
     (when-let [addr (running-server-address addresses)]
       (println)
       (println (str "  ✖ Refusing: a server is already answering /up at " addr "."))
       (println)
       (println (str "    " command-label " writes to the config database from its own"))
       (println "    JVM. While the server is up that write is LOST — the command")
       (println "    still prints success, and the value is simply not there")
       (println "    afterwards. Restarting the server does not recover it.")
       (println)
       (println "    Stop the server, seed, then start it again:")
       (println)
       (println "        docker compose stop digdir-rag")
       (println "        docker compose run --rm --no-deps --entrypoint java digdir-rag \\")
       (println (str "          -cp /app/app.jar clojure.main -m " command-label))
       (println "        docker compose start digdir-rag")
       (println)
       (println "    On a host checkout, stop `bb dev` instead.")
       (println)
       (println (str "    If that server does not share this database, set "
                     seed-guard-override "=true"))
       (println "    to proceed anyway.")
       (println)
       (flush)
       (exit! 1)))))

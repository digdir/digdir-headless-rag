(ns digdir.config.core
  "Configuration management using database storage.

   All runtime configuration is loaded from Datahike database.
   A minimal bootstrap configuration is loaded from environment variables
   to establish database connection, after which all config comes from DB.

   Required environment variables:
   - ADH_POSTGRES_URL, ADH_POSTGRES_USER, ADH_POSTGRES_PWD (database connection)
   - CONFIG_MASTER_KEY (encryption key for secrets)
   - JWT_SECRET (JWT signing secret for authentication)

   Tenant and tenant-config-key are supplied per request by API clients;
   the server does not carry ambient tenant context.

   For config access, use digdir.config.accessor namespace."
  (:require [clojure.string :as str]))

;; =============================================================================
;; Bootstrap Configuration
;; =============================================================================

(defn load-bootstrap-config
  "Load minimal configuration from environment variables only.

   This provides just enough config to connect to the database,
   after which runtime config is loaded from DB.

   Supports two backend modes:
   1. Local File (preferred for dev/test): Set DATAHIKE_FILE_PATH
   2. Remote JDBC/Postgres: Set ADH_POSTGRES_URL

   Returns nil if required env vars are missing — see
   `missing-bootstrap-requirements`, which says WHICH ones and is what turns
   that nil into an error naming itself at the point of use."
  []
  (let [file-path (System/getenv "DATAHIKE_FILE_PATH")
        postgres-url (System/getenv "ADH_POSTGRES_URL")
        postgres-user (System/getenv "ADH_POSTGRES_USER")
        postgres-pwd (System/getenv "ADH_POSTGRES_PWD")
        postgres-table (System/getenv "ADH_POSTGRES_TABLE")
        master-key (System/getenv "CONFIG_MASTER_KEY")
        jwt-secret (System/getenv "JWT_SECRET")
        db-config (cond
                    (not-empty file-path)
                    {:local {:store {:backend :file
                                     :path file-path
                                     ;; Pin :scope so the store identity is stable across
                                     ;; network changes. Datahike's :file backend defaults
                                     ;; :scope to the machine hostname/IP, which causes the
                                     ;; config-does-not-match-stored-db check to fail after
                                     ;; the machine's IP changes. The value below matches
                                     ;; DBs seeded with the datahike default on 127.0.0.1.
                                     :scope "127.0.0.1"}
                             :schema-flexibility :read
                             :keep-history? false
                             ;; Allow re-opening a DB whose stored config drifted
                             ;; from the runtime config — e.g. DBs seeded before
                             ;; the :scope pin landed (whose stored :scope is the
                             ;; original machine IP), or local datasets carried
                             ;; between worktrees / machines. We still pin :scope
                             ;; above for new DBs; this flag only relaxes the
                             ;; *check*, not the actual identity used for fresh writes.
                             :allow-unsafe-config true}
                     :db-env :local}

                    (not-empty postgres-url)
                    {:remote {:store {:backend :jdbc
                                      :dbtype "postgresql"
                                      :jdbcUrl postgres-url
                                      :user postgres-user
                                      :password postgres-pwd
                                      :table postgres-table}
                              :schema-flexibility :read
                              :keep-history? false}
                     :db-env :remote}

                    :else nil)]
    (when (and db-config master-key jwt-secret)
      (merge db-config
             {:config-master-key master-key
              :jwt-secret jwt-secret}))))

(def bootstrap-requirements
  "What `load-bootstrap-config` needs, and what to do when each is absent.

   `load-bootstrap-config` ends in `(when (and db-config master-key jwt-secret)`,
   so it returns nil on THREE distinct causes that are invisible to the caller:
   no database pointer, no master key, no JWT secret. Those are different
   problems with different remedies, and until #326 all three surfaced
   identically as `NullPointerException at datahike.writer/transact!` several
   layers downstream, naming nothing.

   This is the data that lets the error name itself. It is a vector so the
   message lists causes in the order a reader should act on them."
  [{:id :database-pointer
    :env-vars ["DATAHIKE_FILE_PATH" "ADH_POSTGRES_URL"]
    :what "No database pointer is set, so there is nothing to connect to."
    :remedy (str "    Set ONE of:\n"
                 "    export DATAHIKE_FILE_PATH=./local-db/dh_dev_v1"
                 "   # local file, no Postgres needed (preferred for dev)\n"
                 "    export ADH_POSTGRES_URL=postgresql://user:pass@host:5432/dbname"
                 "   # ...or remote Postgres")}
   {:id :config-master-key
    :env-vars ["CONFIG_MASTER_KEY"]
    :what "CONFIG_MASTER_KEY is not set. It decrypts every encrypted value in the config DB, so it cannot itself live there."
    :remedy "    export CONFIG_MASTER_KEY=your-32-char-encryption-key"}
   {:id :jwt-secret
    :env-vars ["JWT_SECRET"]
    :what "JWT_SECRET is not set. It signs admin session tokens."
    :remedy "    export JWT_SECRET=$(openssl rand -base64 32)"}])

(defn missing-bootstrap-requirements
  "The entries of `bootstrap-requirements` this environment does not satisfy.

   A requirement is met when ANY of its `:env-vars` is set and non-blank —
   which is what makes the database pointer one requirement with two spellings
   rather than two requirements, matching `load-bootstrap-config`'s own `cond`.

   The 1-arity takes an env lookup fn so this is testable without mutating the
   JVM's environment, the same seam shape as
   `digdir.setup.common/selected-db-backend`."
  ([] (missing-bootstrap-requirements #(System/getenv %)))
  ([getenv]
   (vec (remove (fn [{:keys [env-vars]}]
                  (some #(not (str/blank? (getenv %))) env-vars))
                bootstrap-requirements))))

(defn bootstrap-config-error
  "The exception to throw when the bootstrap config could not be loaded.

   Names every missing variable in ONE message rather than the first, so a
   single run tells a newcomer everything they have to set. Includes the cwd,
   because relative paths resolve against it and that is the detail the
   directory pre-flight in `digdir.data.db` already carries — this matches that
   standard rather than inventing a looser one.

   NEVER carries a value: the variable NAMES are the whole payload."
  ([] (bootstrap-config-error (missing-bootstrap-requirements)))
  ([missing]
   (ex-info
    (str "Cannot start: required bootstrap environment variable(s) are not set.\n"
         (str/join "\n"
                   (map (fn [{:keys [what remedy]}]
                          (str "\n  - " what "\n" remedy))
                        missing))
         "\n\n  (cwd=" (System/getProperty "user.dir")
         " — relative paths resolve against this.)\n"
         "  These are read from the environment before any config database is"
         " opened, so they cannot be supplied by `bb config-set` or the"
         " committed snapshot. See .env.example and docs/onboarding.md §3.")
    {:missing (mapv :id missing)
     :env-vars (vec (mapcat :env-vars missing))
     :cwd (System/getProperty "user.dir")})))

(defonce !bootstrap-config (atom (load-bootstrap-config)))
(defonce !runtime-config (atom nil))

(def platform-defaults-tenant
  "Internal tenant used for setup-time platform defaults.
   Values under this sentinel are copied (forked) into each new tenant's tree
   at registration time. System-developer edits here do not propagate."
  "__platform-defaults__")

(def global-tenant
  "Sentinel tenant string that owns the live global (inherit) baseline.
   Definitions with :config-def/ownership :inherit resolve to values under
   this tenant when the requesting tenant's chain is exhausted. Edits here
   propagate to every unpinned tenant on next read."
  "__global__")

(defn global-tenant? [t] (= t global-tenant))

(defn use-db-config?
  "Check if database-based config should be used.

   Returns true if CONFIG_MASTER_KEY is set (indicates DB mode)."
  []
  (some? (System/getenv "CONFIG_MASTER_KEY")))

;; ---------------------------------------------------------------------------
;; The two accessors below return nil when the bootstrap config is absent, which
;; is the shape #326 removed one file away. MEASURED 2026-08-28 and deliberately
;; LEFT AS IS — the enumeration is recorded here so the next reader does not have
;; to redo it, and does not "fix" it on the strength of the resemblance.
;;
;; Why nil is not reachable in a way that matters:
;;
;;   1. `!bootstrap-config` is a `defonce`, so nil is a PROCESS-WIDE property
;;      settled at namespace load — not something a single call site can hit.
;;   2. Since #326, `digdir.data.db/init-db!` THROWS when it is nil, and both
;;      `data.db/get-conn` and `config.db/get-conn` funnel through it. So in a
;;      process where these could return nil, nothing can obtain a connection.
;;   3. All 56 production call sites were enumerated. 51 have a connection in
;;      scope (they pass the key alongside a `conn` into `config.db`, which is
;;      what a master key is FOR — encrypting values in that database). The 5
;;      that do not are:
;;        - `auth/core.clj` `secret` — the only production consumer of
;;          `get-jwt-secret`, and it is already
;;          `(or (get-jwt-secret) (throw (ex-info "JWT_SECRET must be configured" ...)))`
;;          inside a `delay`. Already an error at the point of use, naming
;;          itself — exactly what `digdir.secrets` asks for.
;;        - four `config/ui/*.cljc` sites, all `(e/server (common/get-master-key))`
;;          in Electric UI, which only runs inside a booted server — and the
;;          server cannot boot without the connection that (2) guards.
;;   4. Even if a path did reach it, a nil key fails LOUDLY at use rather than
;;      silently: measured under `env -i`, `crypto/encrypt` throws
;;      NullPointerException "master_key is null" and `crypto/decrypt` throws
;;      NegativeArraySizeException.
;;
;; What would change this answer: a new caller that uses the master key WITHOUT
;; a connection — e.g. encrypting something for a destination other than the
;; config DB. That is the case to re-check, not the accessors themselves.
;;
;; Note the honest limit of (4): "loud" is not the same as "names itself".
;; `NegativeArraySizeException: -12` is loud and tells the reader nothing. It is
;; acceptable only because (1)-(3) mean nobody arrives there.

(defn get-master-key
  "Get the master encryption key from bootstrap config or environment.

   Returns nil when no bootstrap config loaded — see the comment above for why
   that is unreachable in practice rather than an oversight."
  []
  (:config-master-key @!bootstrap-config))

(defn get-jwt-secret
  "Get the JWT signing secret from bootstrap config or environment.

   Returns nil when no bootstrap config loaded; its one production consumer
   (`digdir.auth.core/secret`) turns that into a named error. See above."
  []
  (:jwt-secret @!bootstrap-config))

;; =============================================================================
;; Server-Scoped Settings (read from env vars)
;; =============================================================================
;;
;; These settings apply to the server process itself and cannot be overridden
;; per-tenant: they are consulted before any tenant is identified (session
;; cookies, JWT parameters, rate-limit proxy trust) or are pre-auth policy
;; (approved email domains). Each has a code-level default so the server boots
;; without explicit configuration.

(defn- env-str
  [k]
  (when-let [v (System/getenv k)]
    (not-empty v)))

(defn- env-long
  [k default]
  (if-let [v (env-str k)]
    (Long/parseLong v)
    default))

(defn- env-bool
  [k default]
  (if-let [v (env-str k)]
    (contains? #{"true" "1" "yes" "on"} (str/lower-case v))
    default))

(defn- env-set
  [k default]
  (if-let [v (env-str k)]
    (into #{} (remove str/blank?) (map str/trim (str/split v #"\s+")))
    default))

(defn auth-approved-domains
  "Set of email domains allowed to sign in during legacy domain-whitelist
   migration. Read from AUTH_APPROVED_DOMAINS (whitespace-separated)."
  []
  (env-set "AUTH_APPROVED_DOMAINS" #{}))

(defn auth-cookie-domain
  "Cookie domain attribute for the auth cookie, or nil for host-only.
   Read from AUTH_COOKIE_DOMAIN."
  []
  (env-str "AUTH_COOKIE_DOMAIN"))

(defn auth-jwt-cookie-max-age
  "Max-age (seconds) for the JWT auth cookie.
   Read from AUTH_JWT_COOKIE_MAX_AGE_SECONDS; defaults to 7 days."
  []
  (env-long "AUTH_JWT_COOKIE_MAX_AGE_SECONDS" (* 7 24 60 60)))

(defn auth-jwt-token-expiry-hours
  "JWT token expiry in hours.
   Read from AUTH_JWT_TOKEN_EXPIRY_HOURS; defaults to 168 (7 days)."
  []
  (env-long "AUTH_JWT_TOKEN_EXPIRY_HOURS" 168))

(defn auth-secure-cookies?
  "Whether to mark cookies Secure (HTTPS only).
   Read from AUTH_SECURE_COOKIES; defaults to false."
  []
  (env-bool "AUTH_SECURE_COOKIES" false))

(defn auth-session-max-age
  "Max-age (seconds) for the short-lived session cookie used during login.
   Read from AUTH_SESSION_MAX_AGE_SECONDS; defaults to 3600 (1 hour)."
  []
  (env-long "AUTH_SESSION_MAX_AGE_SECONDS" 3600))

(defn rate-limit-trust-x-forwarded-for?
  "Whether to trust the X-Forwarded-For header for client-IP identification.
   Read from RATE_LIMIT_TRUST_X_FORWARDED_FOR; defaults to false."
  []
  (env-bool "RATE_LIMIT_TRUST_X_FORWARDED_FOR" false))

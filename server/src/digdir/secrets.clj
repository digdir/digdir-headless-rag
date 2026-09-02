(ns digdir.secrets
  "Get a secret by name. That is the whole interface (#22).

   ## Two tiers, by necessity rather than by drift

   `CONFIG_MASTER_KEY` decrypts the config database, so it cannot live in the
   config database. That single fact splits secrets in two, and any design
   starting from \"one source with pluggable backends\" is wrong before it is
   written:

   - **Tier 0 — bootstrap.** Resolved BEFORE config exists: the master key,
     the JWT secret, the database pointer. These are what this namespace is
     for, and why it depends on nothing in `digdir.config.*` — a dependency
     the other way round would be the circularity the tiers exist to avoid.
   - **Tier 1 — service credentials.** Live IN the config database, encrypted
     at rest, read through `digdir.config.accessor/get`. The Typesense admin
     key is one of these. **They are deliberately not reachable from here.**
     Fronting them too would give one value two doors, which is the failure
     this issue exists to prevent.

   A third group exists today and is the reason this namespace has callers
   now: credentials read straight from the environment at request time
   (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`), belonging to neither tier and
   degrading silently when absent. Those are the first migrations.

   ## The interface is `get me this secret by name`, and nothing else

   No rotation, no versioning, no leasing, no dynamic credentials. Each of
   those is a reason the abstraction stops being thin and becomes a
   lowest-common-denominator wrapper that fits none of the backends well.

   ## Resolution order is DATA, not the order of a `cond`

   `*resolution-order*` is a vector, and the rule is stated once here:
   **the environment wins.** Every existing reader in this repository is
   env-first by construction (59 direct `System/getenv` call sites, measured
   in `docs/secrets-inventory.md`), so any other order would silently change
   what a running system resolves. A vault is consulted only where the
   environment is silent.

   ## A missing secret is an error, at the point of use, naming itself

   `get!` throws. There is deliberately no arity that returns `nil`, because
   this repository's most-repeated defect is a configuration error arriving
   later as something else — an authentication failure at a provider, one
   layer from its cause. `digdir.llm.anthropic` substituted the literal
   string \"Not set\" for an absent key; that is the shape being removed.

   ## Never log a secret VALUE

   Names only — see `redact` and the comment at every point where a value is
   in scope. A resolved secret must never reach a log line, an exception
   message, `ex-data`, or a debug print."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Declared secrets
;; ---------------------------------------------------------------------------

(def declared
  "Every secret this layer resolves, keyed by its stable internal name.

   Declaring them is what lets an absent one name itself, and it is also what
   makes the DYNAMIC call sites auditable. 8 of the 59 env reads in this
   repository pass a computed name rather than a literal, and tracing those
   eight by hand turned up 17 names a direct grep never sees. A computed name
   that is not declared here throws; today the same shape returns `nil`.

   `:env-var` is the environment variable the value arrives in. It is also
   the name a GitHub Actions secret must be mapped to, because GitHub exposes
   secrets to a job as environment variables — see `backends`."
  {:config-master-key {:env-var "CONFIG_MASTER_KEY" :tier :bootstrap
                       :doc "Decrypts the config database. Tier 0 by definition."}
   :jwt-secret        {:env-var "JWT_SECRET" :tier :bootstrap
                       :doc "Signs admin session tokens and the session cookie store."}
   :postgres-password {:env-var "ADH_POSTGRES_PWD" :tier :bootstrap
                       :doc "Datahike JDBC backend password, when Postgres is the store."}
   :openai-api-key    {:env-var "OPENAI_API_KEY" :tier :runtime
                       :doc "OpenAI-compatible provider key, read per request."}
   :anthropic-api-key {:env-var "ANTHROPIC_API_KEY" :tier :runtime
                       :doc "Anthropic provider key, read per request."}})

(defn secret-names
  "Every declared secret name. Enumerable on purpose: a set you cannot list
   is a set you cannot audit."
  []
  (set (keys declared)))

;; ---------------------------------------------------------------------------
;; Backends
;; ---------------------------------------------------------------------------

(defmulti resolve-secret
  "Resolve `spec` via one backend. Returns a non-blank string, or nil when
   this backend simply does not have it — nil here means ABSENT, never
   \"failed\"; a backend that cannot function throws."
  (fn [backend _spec] backend))

(def ^:dynamic *env-lookup*
  "How the `:env` backend reads the environment.

   A seam, because `System/getenv` is a static method and cannot be redefined
   — without this there is no way to test the layer's own behaviour without
   mutating the JVM's environment. One arity, matching `System/getenv`'s
   single-argument form: a stub's arity is a claim about the producer."
  (fn [^String n] (System/getenv n)))

(defmethod resolve-secret :env
  [_ {:keys [env-var]}]
  (not-empty (some-> (*env-lookup* env-var) str/trim)))

(defmethod resolve-secret :github-actions
  [_ spec]
  ;; Deliberately the same mechanism as :env, and NOT a copy of it. GitHub
  ;; delivers secrets to a job as environment variables; there is no in-process
  ;; API to read them, so resolution is identical and only PROVISIONING
  ;; differs. It is named separately so configuration can say where a value is
  ;; meant to come from and so a missing one can say "expected as a GitHub
  ;; Actions secret mapped into env" rather than "set this variable".
  (resolve-secret :env spec))

(defonce azure-key-vault-resolver
  ;; (fn [spec] -> value-or-nil), or nil when not installed.
  ;;
  ;; A seam rather than a shipped HTTP client, on purpose. There is no Azure
  ;; environment reachable from here to exercise a real Key Vault call
  ;; against, and an unexercised credential path is worse than an absent one:
  ;; it would look supported and fail in the one place nobody can test. The
  ;; resolution POSITION is fixed and documented; installing a resolver is all
  ;; that is left, and it is a small, testable surface.
  (atom nil))

(defmethod resolve-secret :azure-key-vault
  [_ spec]
  (if-let [f @azure-key-vault-resolver]
    (f spec)
    (throw (ex-info (str "Secrets: :azure-key-vault is in the resolution order but no "
                         "resolver is installed. Install one via "
                         "digdir.secrets/azure-key-vault-resolver, or remove the backend "
                         "from digdir.secrets/*resolution-order*.")
                    {:backend :azure-key-vault
                     :secret (:name spec)}))))

(defmethod resolve-secret :default
  [backend spec]
  (throw (ex-info (str "Secrets: unknown backend " (pr-str backend))
                  {:backend backend :secret (:name spec)
                   :known (vec (sort (keys (methods resolve-secret))))})))

(def ^:dynamic *resolution-order*
  "Backends consulted in order, first non-nil wins.

   **The environment wins**, and that is a decision rather than an accident —
   see the namespace docstring. Rebind (or `alter-var-root`) to add a vault:
   `[:env :azure-key-vault]`."
  [:env])

;; ---------------------------------------------------------------------------
;; Lookup
;; ---------------------------------------------------------------------------

(defn redact
  "What a secret is allowed to look like outside this namespace.

   ⚠️ NEVER LOG, PRINT OR ATTACH A SECRET VALUE. If you are here because you
   want to add the value to a debug line, add `(redact v)` instead — the whole
   point of this layer is that a value has exactly one destination, which is
   the caller that asked for it. Values must not reach logs, exception
   messages, `ex-data`, or a REPL transcript."
  [v]
  (if (str/blank? v) "<absent>" (str "<redacted:" (count v) " chars>")))

(defn- spec-for
  [secret-name]
  (or (get declared secret-name)
      (throw (ex-info (str "Secrets: " (pr-str secret-name) " is not a declared secret. "
                           "Add it to digdir.secrets/declared — a name resolved at "
                           "runtime that nothing declares would otherwise resolve to "
                           "nothing, silently.")
                      {:secret secret-name
                       :declared (vec (sort (secret-names)))}))))

(defn get!
  "The secret named `secret-name`, or throw.

   Throws — rather than returning nil — when nothing in `*resolution-order*`
   has it. The exception names the secret, the environment variable it was
   expected in, and the backends tried. **It never carries the value.**"
  [secret-name]
  (let [spec (assoc (spec-for secret-name) :name secret-name)
        order *resolution-order*]
    (or (some (fn [backend] (resolve-secret backend spec)) order)
        (throw (ex-info (str "Missing secret " (pr-str secret-name) ": set "
                             (:env-var spec)
                             (when (some #{:github-actions} order)
                               (str " (in CI, a GitHub Actions secret mapped to "
                                    (:env-var spec) ")"))
                             ". Tried " (pr-str order) ".")
                        ;; Names only. A value must never appear in ex-data —
                        ;; ex-data reaches logs and error reporters.
                        {;; The one failure that means "nothing has it", as
                         ;; opposed to "you asked wrongly" or "a backend is
                         ;; misconfigured". `present?` keys on this; without it
                         ;; those three collapse into one value. Names only.
                         :reason :absent
                         :secret secret-name
                         :env-var (:env-var spec)
                         :tier (:tier spec)
                         :backends-tried (vec order)})))))

(defn- absent?
  "True only for the failure that means nothing has this secret."
  [e]
  (= :absent (:reason (ex-data e))))

(defn present?
  "Whether `secret-name` resolves, without returning or logging its value.

   For diagnostics that report configuration status — `bb setup` reports
   MISSING/OK per variable — so they do not each reimplement a lookup.

   ONLY ABSENCE RETURNS FALSE. An undeclared name and a misconfigured backend
   PROPAGATE, and that distinction is the whole point of the function. A
   blanket `(catch ExceptionInfo _ nil)` here would report `:openai-api-kye`
   as MISSING, sending an operator to set a variable that is already set — the
   silent-nil this layer exists to remove, reintroduced in the one function
   diagnostics actually call. Same for a resolution order naming a vault with
   no resolver installed must not read as every secret being missing."
  [secret-name]
  (try (some? (get! secret-name))
       (catch clojure.lang.ExceptionInfo e
         (if (absent? e) false (throw e)))))

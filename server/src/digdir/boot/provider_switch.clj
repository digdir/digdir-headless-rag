(ns digdir.boot.provider-switch
  "Refuse to boot on the one provider configuration that cannot be intentional:
   a complete Azure credential set and no provider switch.

   ## The failure this replaces

   A user supplied `AZURE_OPENAI_API_KEY`, `AZURE_OPENAI_API_ENDPOINT` and
   `AZURE_OPENAI_DEPLOYMENT_NAME`, left `AZURE_OPENAI_USE_AZURE` unset because
   nothing asked them for it, and every query failed with

       Missing secret :openai-api-key: set OPENAI_API_KEY. Tried [:env].

   a message about the OTHER provider. Nothing was broken; the switch is unset,
   unset means NOT Azure, and the runtime faithfully took the local path they
   had not configured. `scripts/setup-env.sh` now asks for the switch, and this
   catches the state regardless of how the configuration got there.

   ## ⚠️ WHY THIS READS CONFIG AND NOT THE ENVIRONMENT

   `digdir.llm.provider/configured-provider` — the ONE read of the provider
   decision, which the runtime routes by too — resolves it from the CONFIG
   DATABASE, per tenant: `services.llm.provider`, with the legacy boolean
   `services.azure-openai.use-azure-openai-api` as its fallback. The environment
   is a WRITE path — the env-bridge seeds `AZURE_OPENAI_USE_AZURE` (the legacy
   spelling) to `services.llm.provider` — and the runtime never
   reads the variable again.

   So a check that inspected the environment would refuse a tenant configured
   correctly through `bb config-set`, the admin UI or an import, where the value
   is in the database and the variable is unset. That is not a hypothetical
   difference: a verifier disagreeing with the runtime about THIS EXACT SETTING
   is what produced the original defect (#500), where the site with a default
   reported `:azure` and the site without one is what ran. This asks the
   question the same way the runtime does.

   ## ⚠️ UNSET IS NOT THE SAME AS FALSE, AND ONLY UNSET IS A CONTRADICTION

   Unset is the CORRECT state for someone running an OpenAI-compatible server,
   and a check that fired on them would break a working configuration to fix a
   broken one. Explicit `false` is a choice and is left alone — including with
   Azure credentials present, which is a real combination: credentials kept for
   later while running locally now.

   The refusal therefore requires BOTH halves: every Azure credential present
   AND the switch carrying no value at all. An incomplete credential set is not
   this defect and is not refused.

   ## Names only, never values

   One of the three paths holds an encrypted API key. This reads it to ask
   whether it is THERE and nothing else; no value reaches the message, the
   `ex-data` or the log."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [digdir.config.accessor :as accessor]
            [digdir.config.db :as config-db]
            [digdir.config.env-bridge :as env-bridge]
            [digdir.llm.provider :as provider]
            [digdir.secrets :as secrets]))

(def azure-credential-paths
  "The three values that together mean somebody intended to use Azure.

   All three, not any one: a lone endpoint is a leftover, while a key AND an
   endpoint AND a deployment name is a decision."
  ["services.azure-openai.api-key"
   "services.azure-openai.api-endpoint"
   "services.azure-openai.deployment-name"])

(def switch-env-var
  "The variable an operator actually sets: its legacy spelling seeds
   `services.llm.provider`. Named in the refusal beside that path
   because a config path is not something anyone can act on without being told
   where to put it."
  "AZURE_OPENAI_USE_AZURE")

(def override-env-var
  "Escape hatch, mirroring the existing boot-check idiom."
  "DIGDIR_ALLOW_UNSET_PROVIDER_SWITCH")

(def credentials-override-env-var
  "Escape hatch for the UPGRADE refusal - its own, because it lets a
   different state through than the switch override does."
  "DIGDIR_ALLOW_UNSEEDED_LLM_CREDENTIALS")

(def branch-credentials
  "Per provider branch: the value that says the tenant is CONFIGURED for LLM
   use, and the credentials the runtime now reads for it per tenant, refusing
   rather than borrowing the environment.

   The openai-compatible branch's configured signal is `:model-decision`, not a
   path: since Phase 4 of the provider-resolver change the model has two spellings, and asking the legacy
   one alone would stop recognising a tenant that migrated to
   `services.llm.model` - the upgrade refusal would then skip exactly the
   tenant it exists for. `read-credentials` resolves it through
   `provider/configured-model`, which reads both.

   The configured signal is what keeps the upgrade refusal off `__global__` and
   every tenant that never makes an LLM call - by construction, not by a
   hand-kept exclusion list, the same way the switch check requires a complete
   Azure credential set. The variable that seeds each credential is NOT listed
   here: it is derived from the env-bridge table, so the refusal cannot name a
   variable the bridge does not actually read."
  {:openai-compatible {:configured :model-decision
                       :credentials ["services.llm.api-key" "services.llm.api-endpoint"]}
   :azure             {:configured "services.azure-openai.deployment-name"
                       :credentials ["services.azure-openai.api-key" "services.azure-openai.api-endpoint"]}})

(defn- present?
  "Whether a resolved config value counts as supplied. Blank is absent: an
   empty string is what a half-filled `.env` bridges in."
  [v]
  (if (string? v) (not (str/blank? v)) (some? v)))

(defn contradiction?
  "The one combination that cannot be intentional.

   Pure, and takes already-resolved values, so the three cases in the issue are
   testable without a database:

     {:credentials [k e d] :switch nil}    => true   — refuse
     {:credentials [k e d] :switch true}   => false  — boots
     {:credentials [nil nil nil] :switch nil} => false — deliberate local path"
  [{:keys [credentials switch]}]
  (and (= (count azure-credential-paths) (count credentials))
       (every? present? credentials)
       (nil? switch)))

(defn unseeded-credentials
  "The credentials a tenant's branch will now refuse for, that the environment
   could have supplied: [{:path :env-var}], sorted. Empty unless the tenant is
   CONFIGURED for LLM use on that branch.

   Pure, over already-resolved values, so every case is testable without a
   database:

     {:provider :openai-compatible|:azure
      :configured <the branch's configured-signal value>
      :credentials {path value}
      :env-present #{variable-name}}

   Fires only when all three hold: the tenant is configured on that branch, a
   credential is unset in config, and the variable that seeds it IS set - i.e.
   exactly the state that worked before the provider-resolver change by borrowing the environment
   and now refuses. A tenant with neither the config nor the variable is not
   an upgrade break (it never worked), and is left to its first call's own
   refusal."
  [{:keys [configured credentials env-present]}]
  (if-not (present? configured)
    []
    (->> credentials
         (keep (fn [[path v]]
                 (let [var (env-bridge/env-var-for-path path)]
                   (when (and (not (present? v)) var (contains? env-present var))
                     {:path path :env-var var}))))
         (sort-by :path)
         vec)))

(defn- read-credentials
  "The resolved values `unseeded-credentials` needs for `tenant`, read the way
   the runtime reads them: the provider through `provider/selected-provider`
   and the paths through `accessor/get` - no defaults of this check's own."
  [tenant]
  (let [provider (provider/selected-provider tenant)
        {:keys [configured credentials]} (get branch-credentials provider)
        read-path (fn [p] (apply accessor/get {:tenant tenant} (map keyword (str/split p #"\."))))]
    {:provider provider
     ;; `:model-decision` is the openai-compatible branch's
     ;; configured signal, and it reads BOTH model paths through the one read.
     :configured (if (= :model-decision configured)
                   (provider/configured-model tenant)
                   (read-path configured))
     :credentials (into {} (map (juxt identity read-path)) credentials)
     :env-present (into #{} (comp (keep env-bridge/env-var-for-path)
                                  (filter env-bridge/env-var-present?))
                        credentials)}))

(defn- read-tenant
  "Resolve the four values for one tenant.

   `nil` on ANY failure, deliberately: a tenant whose config cannot be read is
   one this check knows nothing about, and refusing to boot over an unrelated
   resolution error would be worse than the defect. A check that cannot answer
   must not answer."
  [tenant]
  (try
    {:tenant tenant
     :credentials (mapv #(accessor/get-platform-value % {:tenant tenant})
                        azure-credential-paths)
     ;; No :default — a default here would erase the difference between
     ;; "unset" and "chosen false", which is the whole discrimination.
     :switch (provider/configured-provider tenant)
     ;; Its own try: a credential read that cannot answer must not take the
     ;; tenant out of the SWITCH check above - each check declines on its own.
     :credential-state (try (read-credentials tenant) (catch Throwable _ nil))}
    (catch Throwable _ nil)))

(defn- read-tenants
  "The tenants this check could actually resolve.

   `read-tenant` answers `nil` for a tenant it cannot read, and dropping it here
   is how the check declines to answer. That makes the count of what SURVIVES
   the only honest basis for `:checked` — counting the input instead reports a
   tenant as examined when its read threw. See `check!`."
  [tenants]
  (vec (keep read-tenant tenants)))

(defn contradictory-tenants
  "Tenant ids in the contradictory state, sorted. Never carries a value."
  [tenants]
  (->> (read-tenants tenants)
       (filter contradiction?)
       (map :tenant)
       sort
       vec))

(defn- all-tenants []
  (try
    (config-db/list-tenants @(config-db/get-conn))
    ;; Boot may reach this before a config database exists at all — a fresh
    ;; deployment has no tenants and nothing to contradict.
    (catch Throwable _ [])))

(defn override-engaged?
  ([] (override-engaged? (System/getenv override-env-var)))
  ;; Case-INSENSITIVE, matching `digdir.setup.common`'s seed guard and the
  ;; RAG env flags. An escape hatch is reached for under pressure, and a
  ;; `TRUE` that silently fails to engage presents as "the documented override
  ;; does not work" — the operator is already dealing with a refusal and now
  ;; has a second, invisible one. The value is still required to SAY true;
  ;; only its casing is forgiven.
  ([raw] (= "true" (some-> raw str/trim str/lower-case))))

(defn credentials-override-engaged?
  "Whether the upgrade refusal's escape hatch is set. Read through the
   `digdir.secrets` environment seam, so a test binds one door."
  []
  (override-engaged? (secrets/*env-lookup* credentials-override-env-var)))

(defn check!
  "Refuse to start when any tenant supplies Azure credentials and no switch.

   Returns a summary when it does not refuse — `{:checked n :unreadable n
   :violations [...] :overridden? bool}`, the shape the sibling boot checks
   return.

   `:checked` counts the tenants actually RESOLVED, not the tenants offered, so
   a vacuous run is visible: 0 means nothing was examined. Counting the input
   would report a tenant as checked when its config read threw, which is the
   one case where this check knows nothing — and it is logged at boot by both
   entrypoints, so `{:checked 3 :violations []}` over three unreadable tenants
   would read as healthy. `:unreadable` names that remainder rather than
   leaving it to be inferred from a number that is missing.

   Throws `ex-info` rather than exiting, so the boot path decides how to die."
  ([] (check! (all-tenants)))
  ([tenants]
   (let [examined   (read-tenants tenants)
         violations (->> examined (filter contradiction?) (map :tenant) sort vec)
         credential-violations (->> examined
                                    (mapcat (fn [{:keys [tenant credential-state]}]
                                              (map #(assoc % :tenant tenant)
                                                   (unseeded-credentials credential-state))))
                                    (sort-by (juxt :tenant :path))
                                    (mapv #(select-keys % [:tenant :path :env-var])))
         summary {:checked (count examined)
                  :unreadable (- (count tenants) (count examined))
                  :violations violations
                  :overridden? (override-engaged?)
                  :credential-violations credential-violations
                  :credentials-overridden? (credentials-override-engaged?)}]
     (when (seq credential-violations)
       (let [listing (str/join "; " (map (fn [{:keys [tenant path env-var]}]
                                           (str tenant ": " path " (" env-var " is set)"))
                                         credential-violations))]
         (if (credentials-override-engaged?)
           (log/warn (str "UNSEEDED LLM CREDENTIALS, allowed by " credentials-override-env-var
                          "=true: " listing ". These tenants' LLM calls will refuse: since the provider-resolver change "
                          "the runtime reads these per tenant and no longer falls back to the environment."))
           (throw (ex-info
                    (str "Refusing to start: " (count credential-violations)
                         " LLM credential(s) are unset in config while the variable that seeds "
                         "them is set in the environment - " listing ". Since the provider-resolver change the runtime "
                         "reads these per tenant from config and no longer borrows the process "
                         "environment, so these tenants' LLM calls would refuse. Seed them with "
                         "the server stopped: re-run the tenant seeder, or `bb config-set <path> "
                         "<value> <tenant> platform default` for each. To boot anyway, set "
                         credentials-override-env-var "=true.")
                    {:credential-violations credential-violations
                     :checked (count examined)
                     :override-env-var credentials-override-env-var})))))
     (cond
       (empty? violations)
       summary

       (override-engaged?)
       (do
         (log/warn (str "UNSET PROVIDER SWITCH, allowed by " override-env-var
                        "=true: " (str/join ", " violations)
                        ". These tenants supply Azure credentials while "
                        switch-env-var " is unset, so every query will take the "
                        "OpenAI-compatible path and fail asking for "
                        "services.llm.api-key."))
         summary)

       :else
       (throw (ex-info
                (str "Refusing to start: " (count violations)
                     " tenant(s) supply a complete Azure credential set while "
                     "the provider switch is unset — " (str/join ", " violations)
                     ". Unset means NOT Azure, so every query would take the "
                     "OpenAI-compatible path and fail asking for "
                     "`services.llm.api-key`, which names a provider "
                     "you did not configure. Choose the provider: `bb config-set "
                     "services.llm.provider :azure <tenant> platform default` (or "
                     ":openai-compatible if you meant to run against an "
                     "OpenAI-compatible server), or its legacy variable "
                     switch-env-var "=true / " switch-env-var "=false and "
                     "re-run the tenant seeder with the server stopped. To boot "
                     "anyway, set " override-env-var "=true.")
                {:violations violations
                 :checked (count examined)
                 :unreadable (- (count tenants) (count examined))
                 :switch-env-var switch-env-var
                 :override-env-var override-env-var}))))))

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

   `digdir.config.accessor/use-azure-openai-api?` resolves the switch from the
   CONFIG DATABASE, per tenant. The environment is a WRITE path — the env-bridge
   seeds `AZURE_OPENAI_USE_AZURE` to `services.azure-openai.use-azure-openai-api`
   and the runtime never reads the variable again.

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
            [digdir.config.db :as config-db]))

(def azure-credential-paths
  "The three values that together mean somebody intended to use Azure.

   All three, not any one: a lone endpoint is a leftover, while a key AND an
   endpoint AND a deployment name is a decision."
  ["services.azure-openai.api-key"
   "services.azure-openai.api-endpoint"
   "services.azure-openai.deployment-name"])

(def switch-path
  "services.azure-openai.use-azure-openai-api")

(def switch-env-var
  "The variable an operator actually sets. Named in the refusal because
   `services.azure-openai.use-azure-openai-api` is not something anyone can act
   on without being told where to put it."
  "AZURE_OPENAI_USE_AZURE")

(def override-env-var
  "Escape hatch, mirroring the existing boot-check idiom."
  "DIGDIR_ALLOW_UNSET_PROVIDER_SWITCH")

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
     :switch (accessor/get-platform-value switch-path {:tenant tenant})}
    (catch Throwable _ nil)))

(defn contradictory-tenants
  "Tenant ids in the contradictory state, sorted. Never carries a value."
  [tenants]
  (->> tenants
       (keep read-tenant)
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
  ([raw] (= "true" (some-> raw str/trim))))

(defn check!
  "Refuse to start when any tenant supplies Azure credentials and no switch.

   Returns a summary when it does not refuse — `{:checked n :violations [...]
   :overridden? bool}`, the shape the sibling boot checks return. `:checked` is
   what makes a vacuous run visible: 0 means no tenants were examined.

   Throws `ex-info` rather than exiting, so the boot path decides how to die."
  ([] (check! (all-tenants)))
  ([tenants]
   (let [violations (contradictory-tenants tenants)
         summary {:checked (count tenants)
                  :violations violations
                  :overridden? (override-engaged?)}]
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
                        "OPENAI_API_KEY."))
         summary)

       :else
       (throw (ex-info
                (str "Refusing to start: " (count violations)
                     " tenant(s) supply a complete Azure credential set while "
                     "the provider switch is unset — " (str/join ", " violations)
                     ". Unset means NOT Azure, so every query would take the "
                     "OpenAI-compatible path and fail with "
                     "`Missing secret :openai-api-key`, which names a provider "
                     "you did not configure. Set " switch-env-var "=true and "
                     "re-run the tenant seeder with the server stopped, or set "
                     switch-env-var "=false if you meant to run against an "
                     "OpenAI-compatible server. To boot anyway, set "
                     override-env-var "=true.")
                {:violations violations
                 :checked (count tenants)
                 :switch-env-var switch-env-var
                 :override-env-var override-env-var}))))))

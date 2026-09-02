(ns digdir.e2e.seed
  "Boot-time auto-seed for the E2E test harness.

   Gated behind `E2E_API_KEY` — a no-op in real production where that
   env var is never set. When the env var IS present, on each server
   start this namespace:

     1. Seeds the built-in agents (idempotent — same as the operator
        console's seed flow).
     2. Ensures an API key with the exact plaintext from `E2E_API_KEY`
        exists in the config DB.
     3. Writes any AZURE_OPENAI_* env vars to the platform config DB
        so the LLM call path resolves them via the standard
        `cfg/get {:tenant t} :services :azure-openai ...` chain. The
        env-var -> config-path mapping and the write itself now live in
        `digdir.config.env-bridge`, shared with the import path; this
        namespace supplies only the :azure-openai scoping.

   All three steps are idempotent. The docker-compose E2E stack stays
   self-contained: `server/e2e/.env` carries the values and both the
   dev server and MCPO read from it."
  (:require [clojure.string :as str]
            [digdir.agents.db :as agents-db]
            [digdir.config.api-keys :as api-keys]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.config.env-bridge :as env-bridge]
            [digdir.config.ops.bootstrap :as config-bootstrap]
            [digdir.skills.api :as skills-api]
            [taoensso.telemere :as t]))

(defn key-already-stored?
  "True when an API key with this value exists in hashed or legacy storage. Exposed
   (non-private) so tests can stub it."
  [conn plaintext]
  (api-keys/api-key-stored? conn plaintext))

(defn seed-azure-config-from-env!
  "Write any AZURE_OPENAI_* env vars to the platform config DB on the
   target tenant's own Platform/default node. Skips paths whose env
   var is unset. Idempotent — `set-node-value!` is upsert.

   The target tenant is taken from the TENANT env var (the same one
   used elsewhere in the E2E seed). The values are written directly
   to that tenant's node rather than to __global__, because the
   Azure config definitions aren't declared `:ownership :inherit` —
   `cfg/get` only walks up to __global__ for inherit-owned paths.

   The work is `digdir.config.env-bridge/seed-config-from-env!`, scoped to
   :azure-openai. Scoped deliberately: the shared bridge writes every service
   whose variable is set, and the E2E stack must keep writing exactly what it
   wrote before — its `server/e2e/.env` is the definition of that stack, not
   whatever else happens to be exported in the shell that starts it."
  [conn tenant]
  (let [{:keys [paths-written error]}
        (env-bridge/seed-config-from-env! conn tenant {:services #{:azure-openai}})]
    (when error
      (throw (ex-info "Target tenant has no Platform/default node"
                      {:tenant tenant :reason error})))
    {:azure-paths-written paths-written}))

(def e2e-fixture-agents
  "Two e2e-only fixture agents used by the Layer-C Playwright tests for
   agent-scoped skill-params:

   - `e2e/altinn-docs-default` — empty `:skill-params`. The resolved
     skill-params for this agent reflect only the dataset/hardcoded
     defaults.
   - `e2e/altinn-docs-tuned` — embeds the Round-5 winning phrase-only-
     with-caps configuration. The resolved skill-params for this agent
     surface those overrides.

   Both reuse the existing built-in `builtin/agent-rag-graph-*` skill
   graphs; no new graphs needed."
  [{:id "e2e/altinn-docs-default"
    :name "E2E Default Agent"
    :description "E2E fixture: no skill-params overrides; falls through to dataset + code defaults."
    :instructions "Answer with whatever the default retrieval / rerank pipeline produces."
    :default-skill-graph "builtin/agent-rag-graph-faithful"
    :allowed-skill-graphs ["builtin/agent-rag-graph-faithful"
                           "builtin/agent-rag-graph-bundled"]
    :allowed-dataset-scopes []
    :guardrails {}
    :skill-params {}
    :enabled? true}

   {:id "e2e/altinn-docs-tuned"
    :name "E2E Tuned Agent"
    :description "E2E fixture: carries the Round-5 phrase-only-with-caps skill-params on the agent."
    :instructions "Answer using phrase-only retrieval with strategy contribution caps."
    :default-skill-graph "builtin/agent-rag-graph-faithful"
    :allowed-skill-graphs ["builtin/agent-rag-graph-faithful"
                           "builtin/agent-rag-graph-bundled"]
    :allowed-dataset-scopes []
    :guardrails {}
    :skill-params {:builtin/retrieval {:strategy-weights {:content 0.0 :phrase 1.0 :metadata 0.0}
                                       :strategy-contribution-caps {:phrase 5 :content 0 :metadata 0}
                                       :retrieve-top-k 100}
                   :builtin/rerank {:top-k 20}}
    :enabled? true}])

(defn seed-e2e-fixture-agents!
  "Upsert the two `e2e/altinn-docs-*` fixture agents. Idempotent — uses
   the same upsert path as the built-in agents."
  [conn]
  (mapv #(agents-db/upsert-agent! conn %) e2e-fixture-agents))

(defn- register-e2e-tenant!
  "Register the TENANT env var's tenant with an empty Platform root
   node. Without this, `cfg/get {:tenant t} :services ...` fails with
   'no nodes exist for this tenant/root' even when the values are
   present at `__global__/platform/default` (the resolver walks
   tenant-first before inheriting up). Idempotent —
   `bootstrap-config-tree!` reuses existing nodes."
  [conn]
  (when-let [tenant (System/getenv "TENANT")]
    (when-not (str/blank? tenant)
      (config-bootstrap/bootstrap-config-tree!
        conn
        {:root :platform
         :tenant tenant
         :tenant-name tenant
         :created-by "e2e-seed"
         :single-node? true
         :base-values {}
         :master-key (config-core/get-master-key)})
      tenant)))

(defn seed!
  "Apply the seed against `conn`: agents + an API key with `api-key`
   as plaintext + (optional) Azure config from AZURE_OPENAI_* env
   vars + Platform root node for the configured tenant. Idempotent —
   safe to call repeatedly. Returns a report map.

   Split from `maybe-seed!` so tests can drive it with an in-memory
   connection instead of mucking with env vars."
  [conn api-key]
  (skills-api/initialize!)
  (let [agents-before (count (agents-db/list-enabled-agents @conn))
        _ (agents-db/seed-builtin-agents! conn)
        _ (seed-e2e-fixture-agents! conn)
        agents-after (count (agents-db/list-enabled-agents @conn))
        key-existed? (key-already-stored? conn api-key)
        _ (when-not key-existed?
            (api-keys/store-api-key
              conn api-key "e2e-harness" "e2e-seed"
              {:scopes #{:query}
               :user-email "e2e@local"}))
        tenant-registered (register-e2e-tenant! conn)
        azure-report (if tenant-registered
                       (seed-azure-config-from-env! conn tenant-registered)
                       {:azure-paths-written []})]
    (merge {:seeded? true
            :agents-before agents-before
            :agents-after agents-after
            :api-key-existed? key-existed?
            :tenant-registered tenant-registered}
           azure-report)))

(defn maybe-seed!
  "Boot hook: invoke `seed!` when `E2E_API_KEY` is set in the
   environment. No-op in production (env var absent). Logs warnings
   on partial failure but does not throw — boot must not be blocked
   by a seed glitch."
  []
  (let [api-key (System/getenv "E2E_API_KEY")
        gated? (str/blank? api-key)]
    (if gated?
      {:seeded? false :reason :not-gated}
      (try
        (let [result (seed! (config-db/get-conn) api-key)]
          (t/log! :info [:e2e/seeded
                         (assoc result
                                :api-key-prefix (subs api-key 0 (min 8 (count api-key))))])
          result)
        (catch Throwable e
          (t/log! :warn [:e2e/seed-failed {:error (.getMessage e)
                                            :ex-data (ex-data e)}])
          {:seeded? false :reason :error :error (.getMessage e)})))))

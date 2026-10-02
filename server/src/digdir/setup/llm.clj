(ns digdir.setup.llm
  "The `bb setup` section that picks which LLM provider a tenant talks to.

   ## What the choice is, and where it lives

   The provider is `services.llm.provider` - `:azure` or `:openai-compatible` -
   read by `digdir.llm.provider` (with the legacy
   `services.azure-openai.use-azure-openai-api` boolean as its fallback, which
   this wizard no longer writes):

     :azure             → an Azure call spec (`:impl :azure`), reading
                          `services.azure-openai.{api-key,api-endpoint,deployment-name}`.
     :openai-compatible → a plain OpenAI-compatible `/chat/completions`, with the
                          model from `services.azure-openai.model-name` (a family
                          named after Azure - the trap this section exists to
                          defuse, until Phase 4 of the provider-resolver change moves it) and the endpoint
                          and key from the tenant's own `services.llm.*`.

   ## The key is printed, not prompted

   The wizard writes the endpoint it just probed. It does not prompt for the
   key, like the Azure branch does not: it prints the `bb config-set` line. The
   key must be non-empty even when the local server ignores it - the resolver
   refuses a missing key rather than sending a keyless request, and no longer
   borrows OPENAI_API_KEY from the environment.

   ## Why the write is per-tenant and not `set-global-config!`

   Every other setup section writes through `digdir.setup.config/set-global-config!`,
   which lands a fork-owned path — and `services.azure-openai.*` is fork-owned,
   having no `:ownership` on its definition — in the `__platform-defaults__`
   seed tree. That tree is copied into a tenant only by
   `bootstrap-tenant-platform-tree!`, so a write there does not change what an
   ALREADY-BOOTSTRAPPED tenant resolves, and the documented first run imports a
   snapshot whose tenants are already bootstrapped.

   Measured rather than reasoned: writing
   `services.azure-openai.model-name` to a sentinel string
   through `set-global-config!`, then reading it back in a FRESH process, gives
   `__platform-defaults__` the sentinel while `digdir` and
   `public-sector-knowledge` keep their own value. A `set-global-config!` here
   would report success and change nothing for the tenants anyone queries.

   The values therefore go to each tenant's own platform `default` node, exactly
   where `bb config-set <path> <value> <tenant> platform default` puts them —
   with `__platform-defaults__` seeded alongside, so a tenant created later
   inherits the choice instead of reverting.

   Nothing here writes anything unless the operator picks a provider, so a
   scripted `bb setup < /dev/null` leaves the Azure path untouched."
  (:require [clojure.string :as str]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.setup.common :as setup-common]
            [digdir.setup.config :as setup-config]))

(def default-local-endpoint
  "LM Studio's default OpenAI-compatible base URL. Ollama's is
   http://localhost:11434/v1; llama.cpp and vLLM vary by launch flag."
  "http://localhost:1234/v1")

(def ^:private provider-path "services.llm.provider")
(def ^:private endpoint-path "services.llm.api-endpoint")
(def ^:private model-path
  ;; the wizard writes services.llm.model. The legacy
  ;; services.azure-openai.model-name is still READ as the fallback, so an
  ;; existing install keeps working until its value is migrated.
  "services.llm.model")

(defn embedding-model?
  "Whether a model id from `/v1/models` looks like an embedding model.

   `/v1/models` lists everything the server has loaded, embeddings included,
   and offering one as the chat model produces a failure at the first call
   rather than at the prompt."
  [id]
  (let [s (str/lower-case (str id))]
    (or (str/includes? s "embed")
        (str/includes? s "rerank"))))

(defn probe-models
  "Ask an OpenAI-compatible server what it has loaded.

   Returns `{:ok? true :models [id ...]}`, or `{:ok? false :error \"...\"}`.
   Never throws: a server that is not running yet is an ordinary answer here,
   not a reason to abort the wizard."
  [endpoint]
  (try
    (let [url (str (str/replace (str endpoint) #"/+$" "") "/models")
          resp (http/get url {:socket-timeout 5000
                              :connection-timeout 5000
                              :throw-exceptions false
                              :as :string})]
      (if (= 200 (:status resp))
        (let [ids (->> (get (json/parse-string (:body resp) true) :data)
                       (keep :id)
                       vec)]
          {:ok? true :models ids})
        {:ok? false :error (str "HTTP " (:status resp) " from " url)}))
    (catch Exception e
      {:ok? false :error (str (.getSimpleName (class e)) ": " (.getMessage e))})))

(defn- platform-default-node-id
  "The tenant's canonical platform `default` node, or nil when it has none."
  [db tenant]
  (:config.node/id (config-db/get-config-node-by-tenant-config-key db tenant :platform "default")))

(defn set-tenant-llm-values!
  "Write `values` (a {path value} map) onto one tenant's platform `default`
   node. Returns `{:tenant t :written [paths]}` or `{:tenant t :error \"...\"}`."
  [conn tenant values]
  (if-let [node-id (platform-default-node-id @conn tenant)]
    (do
      (doseq [[path value] values]
        (config-db/set-node-value! conn {:root :platform
                                         :tenant tenant
                                         :node-id node-id
                                         :path path
                                         :value value
                                         :master-key (config-core/get-master-key)}))
      {:tenant tenant :written (vec (keys values))})
    {:tenant tenant :error "no platform `default` node — run the tenant section first"}))

(defn real-tenants
  "Registered tenants minus the two internal pseudo-tenants.

   `config-db/list-tenants` returns `__global__` and `__platform-defaults__`
   alongside the real ones. `__global__` has no platform `default` node at all,
   and `__platform-defaults__` is a seed tree rather than something anyone
   queries — offering either as a choice invites picking the one that cannot
   work."
  [tenants]
  (vec (remove #{config-core/global-tenant setup-common/platform-defaults-tenant} tenants)))

(defn wizard-values
  "The config values a provider choice writes, per tenant. Pure, so the choice
   is testable without the prompts. Writes the NEW key, never the
   legacy boolean."
  [choice {:keys [endpoint model]}]
  (case choice
    :openai-compatible (cond-> {provider-path :openai-compatible}
                         (not (str/blank? endpoint)) (assoc endpoint-path endpoint)
                         (not (str/blank? model)) (assoc model-path model))
    :azure {provider-path :azure}))

(defn- print-key-instructions
  "The one openai-compatible setting the wizard does not write: the key."
  []
  (println "")
  (println "The key is set per tenant, not prompted for here:")
  (println "  bb config-set services.llm.api-key '\"local\"' <tenant> platform default")
  (println "It must be NON-EMPTY even though a local server ignores it - any placeholder")
  (println "works. (OPENAI_API_KEY in the environment reaches config only by seeding - the")
  (println "demo tenant's `bb demo-tenant`, an import, or the E2E boot seed; LLM calls no longer read the environment.)"))

(defn- choose-tenants
  "Which tenants to write to. Defaults to all of them, because the snapshot
   ships two and configuring only one leaves the other quietly on Azure."
  [tenants]
  (println "")
  (println "Tenants that will be pointed at this provider:")
  (doseq [t tenants] (println (str "  - " t)))
  (let [answer (setup-common/prompt-with-default
                "Apply to (comma-separated list, or 'all')" "all")]
    (if (= "all" (str/lower-case (str/trim (str answer))))
      (vec tenants)
      (let [asked (->> (str/split (str answer) #",")
                       (map str/trim)
                       (remove str/blank?)
                       set)
            known (set tenants)
            unknown (remove known asked)]
        (when (seq unknown)
          (println (str "  Ignoring unknown tenant(s): " (str/join ", " unknown))))
        (vec (filter known tenants))))))

(defn- choose-local-model
  "Endpoint + model for the local branch. Probes the endpoint so the model name
   is picked from what is actually loaded rather than typed from memory."
  []
  (let [endpoint (setup-common/prompt-with-default
                  "Local OpenAI-compatible base URL"
                  (or (not-empty (System/getenv "OPENAI_API_ENDPOINT"))
                      default-local-endpoint))
        {:keys [ok? models error]} (probe-models endpoint)
        chat-models (remove embedding-model? models)]
    (if ok?
      (do
        (println (str "  Reached " endpoint " — " (count models) " model(s) loaded."))
        (doseq [m models]
          (println (str "    " m (when (embedding-model? m) "   (embedding — not usable as a chat model)"))))
        (when (empty? chat-models)
          (println "  No chat model is loaded. Load one in your local server before querying.")))
      (do
        (println (str "  Could not reach " endpoint ": " error))
        (println "  That is not fatal here — the endpoint is only read at query time.")
        (println "  Start the server (LM Studio: Developer → Start Server; Ollama: `ollama serve`)")
        (println "  and the same setting will work.")))
    {:endpoint endpoint
     :model (setup-common/prompt-with-default
             "Model name (must match the id the server reports)"
             (first chat-models))}))

(defn- apply-to-tenants!
  [conn targets values]
  (doseq [t targets]
    (let [{:keys [error written]} (set-tenant-llm-values! conn t values)]
      (if error
        (println (str "  " t ": " error))
        (println (str "  " t ": set " (str/join ", " written)))))))

(defn- seed-platform-defaults!
  "Write the same values into the `__platform-defaults__` seed tree, so a tenant
   created later by the tenant section inherits this choice instead of silently
   reverting to Azure. Best effort: a fresh DB may not have that tree yet, and
   that is not a reason to fail the section."
  [conn values]
  (let [{:keys [error]} (set-tenant-llm-values! conn setup-common/platform-defaults-tenant values)]
    (if error
      (println (str "  (platform defaults not seeded: " error
                    " — tenants created later will start on the other provider)"))
      (println "  (also seeded the platform defaults, so tenants created later inherit this)"))))

(defn setup-llm-provider
  "Interactive section: pick the provider the agent's LLM calls go to.

   Default answer is 'leave unchanged', so a scripted run
   (`bb setup < /dev/null`) writes nothing."
  []
  (setup-common/print-section "LLM Provider")
  (println "Which provider should the agent's LLM calls go to?")
  (println "")
  ;; Named as EXAMPLES, not as a tested list. All of them reach the same
  ;; `digdir.llm.client` code path — one OpenAI-compatible POST regardless of
  ;; what answers it — so the support claim is true by construction; but only
  ;; LM Studio has been run through this path, so a bare list of four would
  ;; imply a coverage nobody has. See docs/onboarding.md §4a step 0.
  (println "  1) Any OpenAI-compatible server on this machine — anything that speaks")
  (println "     /v1/chat/completions. LM Studio, Ollama, vLLM and llama.cpp are examples.")
  (println "     No cloud account and no cloud credentials.")
  (println "  2) Azure OpenAI — the cloud path; needs an Azure endpoint and key.")
  (println "  3) Leave unchanged.")
  (println "")
  (println "The choice is stored per tenant as services.llm.provider. The model name")
  (println "still lives in `services.azure-openai.model-name` for both paths - a family")
  (println "named after Azure for historical reasons.")
  (setup-config/ensure-azure-openai-config-definitions!)
  (setup-config/ensure-llm-config-definitions!)
  (let [choice (setup-common/prompt-with-default "Choose" "3")
        conn (config-db/get-conn)
        tenants (when conn (real-tenants (config-db/list-tenants @conn)))]
    (cond
      (= "3" choice)
      (println "  Left unchanged.")

      (empty? tenants)
      (println "  No tenants exist yet — import a config snapshot or create a tenant first.")

      (= "1" choice)
      (let [local (choose-local-model)
            targets (choose-tenants tenants)
            values (wizard-values :openai-compatible local)]
        (println "")
        (apply-to-tenants! conn targets values)
        (seed-platform-defaults! conn values)
        (print-key-instructions))

      (= "2" choice)
      (let [targets (choose-tenants tenants)
            values (wizard-values :azure {})]
        (println "")
        (apply-to-tenants! conn targets values)
        (seed-platform-defaults! conn values)
        (println "")
        (println "Azure also needs these, per tenant, which this section does not prompt for:")
        (println "  bb config-set services.azure-openai.api-endpoint '\"https://<res>.openai.azure.com\"' <tenant> platform default")
        (println "  bb config-set services.azure-openai.deployment-name '\"<deployment>\"' <tenant> platform default")
        (println "  bb config-set services.azure-openai.api-key '\"<key>\"' <tenant> platform default"))

      :else
      (println (str "  Unrecognised choice " (pr-str choice) " — leaving unchanged.")))))

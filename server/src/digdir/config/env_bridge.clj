(ns digdir.config.env-bridge
  "Which environment variable supplies which piece of configuration — once.

   ## Why this exists

   A fresh clone arrives with the schema, the agents, the graphs and a config
   snapshot. What it does NOT arrive with is credentials, and there was no
   supported way to supply them: `digdir.rag.typesense/make-ts-settings`
   resolves its key only from the config database, so a newcomer with a
   perfectly good `TYPESENSE_API_KEY_ADMIN` in their environment still could
   not reach a working query. The mechanism to fix that already existed — it
   was `azure-env->config-path` inside `digdir.e2e.seed`, e2e-only and
   Azure-only. This namespace is that mechanism with those two limits removed.

   ## One table, three readers

   `env-config-bindings` is the single source of truth. Three surfaces read it
   and none of them keeps its own list:

     1. `seed-config-from-env!` — writes env-supplied values into the config DB
        on the import path, so setting variables is sufficient.
     2. `digdir.config.verify` — turns \"this value cannot be resolved\" into
        \"set THIS variable\", so the check hands over a shopping list.
     3. `digdir.setup.common/check-env-vars` — the MISSING/OK table. It used to
        report on `TYPESENSE_API_KEY`, a variable nothing in the running system
        consults; deriving the list from here is what makes it true.

   A service named in two places would drift, and the drift would be invisible
   in exactly the way the original defect was: everything looks configured and
   the failure arrives several layers later as a vendor's 401.

   ## This is a WRITE path, not a second read door

   `digdir.secrets` deliberately refuses to front service credentials, because
   giving one value two doors is the failure that issue exists to prevent. That
   still holds: nothing here resolves a credential for use. This seeds the
   config database from the environment, and the value is read afterwards
   through `digdir.config.accessor` like every other config value — one door.

   The environment wins on write, which is the same rule `digdir.secrets`
   states for read: a value supplied by an environment variable overwrites what
   the snapshot shipped. That is deliberate — the shipped snapshot's secrets
   are sealed with a master key a fresh checkout does not have (#279), so a
   bridge that yielded to the stored value would never fix anything.

   ## Never a value

   Every function here reports PATHS and VARIABLE NAMES. No return value, log
   line or exception carries a configuration value; `digdir.secrets/redact` is
   the only way one is allowed to be characterised."
  (:require [clojure.string :as str]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.secrets :as secrets]))

;; ---------------------------------------------------------------------------
;; The table
;; ---------------------------------------------------------------------------

(def env-config-bindings
  "Every environment variable that supplies configuration, in checklist order.

   Keys:

     :env-var      the environment variable name.
     :path         the dotted config path it supplies, or nil when it supplies
                   something that cannot live in the config DB.
     :service      groups the row; also what `seed-config-from-env!` filters on.
     :destination  where the value has to END UP for the runtime to use it:
                     :config-db   — written into the config DB by the bridge.
                     :environment — read straight from the environment at use
                                    time. The bridge must NOT write these: a
                                    value at that path would be ignored, which
                                    is precisely the misleading state
                                    `digdir.config.db/env-migrated-paths` was
                                    introduced to describe.
                     :bootstrap   — needed BEFORE the config DB can be read, so
                                    it has no path by construction.
     :value-type   :string or :boolean — matches the config definition's own
                   value-type, and drives coercion of the env var's string.
     :tier         :boot (the server refuses to start without it), :query (it
                   boots, but a real query needs it) or :optional. The same
                   three groups `.env.example` already uses.
     :secret?      true when the value must never be echoed, logged or printed.
     :what         one line for a human reading a checklist."
  [;; --- Tier 0: the database pointer. Pick ONE family ----------------------
   ;; Listed here, not in a second list in `setup/common.clj`, so the MISSING/OK
   ;; table's credential markers are right for these rows too. Which family
   ;; WINS is precedence logic and stays in `selected-db-backend`, mirroring
   ;; `digdir.config.core/load-bootstrap-config`.
   {:env-var "DATAHIKE_FILE_PATH" :path nil :service :database-file
    :destination :bootstrap :tier :boot :secret? false
    :what "Local Datahike file-store path. Preferred for dev; needs no Postgres."}

   {:env-var "ADH_POSTGRES_URL" :path nil :service :database-postgres
    :destination :bootstrap :tier :boot :secret? true
    :what "Postgres-backed Datahike URL. Secret because such URLs routinely embed the password."}

   {:env-var "ADH_POSTGRES_USER" :path nil :service :database-postgres
    :destination :bootstrap :tier :boot :secret? false
    :what "Postgres user."}

   {:env-var "ADH_POSTGRES_PWD" :path nil :service :database-postgres
    :destination :bootstrap :tier :boot :secret? true
    :what "Postgres password."}

   ;; --- Tier 0: needed before configuration can be read at all ---------------
   {:env-var "CONFIG_MASTER_KEY" :path nil :service :bootstrap
    :destination :bootstrap :tier :boot :secret? true
    :what "Decrypts every encrypted value in the config DB. Cannot live in the config DB."}

   {:env-var "JWT_SECRET" :path "services.auth.jwt-secret" :service :auth
    :destination :environment :tier :boot :secret? true
    :what "Signs admin session tokens. Read from the environment; the config-DB value at this path is not consulted."}

   ;; --- Typesense: the retrieval backend ------------------------------------
   {:env-var "TYPESENSE_API_HOST" :path "services.typesense.api-host" :service :typesense
    :destination :config-db :value-type :string :tier :query :secret? false
    :what "Host serving the document/chunk/phrase collections."}

   {:env-var "TYPESENSE_API_TLS" :path "services.typesense.api-tls" :service :typesense
    :destination :config-db :value-type :boolean :tier :query :secret? false
    :what "Whether to reach that host over https."}

   {:env-var "TYPESENSE_API_KEY_ADMIN" :path "services.typesense.api-key-admin" :service :typesense
    :destination :config-db :value-type :string :tier :query :secret? true
    :what "Typesense admin API key."}

   {:env-var "TYPESENSE_COLLECTION_PREFIX" :path "services.typesense.collection-prefix" :service :typesense
    :destination :config-db :value-type :string :tier :optional :secret? false
    :what "Prefix for Typesense collection names."}

   ;; --- Azure OpenAI: the LLM the shipped graphs call by default ------------
   {:env-var "AZURE_OPENAI_API_KEY" :path "services.azure-openai.api-key" :service :azure-openai :provider :azure
    :destination :config-db :value-type :string :tier :query :secret? true
    :what "Azure OpenAI API key."}

   {:env-var "AZURE_OPENAI_API_ENDPOINT" :path "services.azure-openai.api-endpoint" :service :azure-openai :provider :azure
    :destination :config-db :value-type :string :tier :query :secret? false
    :what "Azure OpenAI endpoint base URL."}

   {:env-var "AZURE_OPENAI_DEPLOYMENT_NAME" :path "services.azure-openai.deployment-name" :service :azure-openai :provider :azure
    :destination :config-db :value-type :string :tier :query :secret? false
    :what "Deployment name to call."}

   {:env-var "AZURE_OPENAI_MODEL_NAME" :path "services.azure-openai.model-name" :service :azure-openai :provider :openai-compatible
    :destination :config-db :value-type :string :tier :query :secret? false
    :what "Model name sent in the request body."}

   {:env-var "AZURE_OPENAI_API_VERSION" :path "services.azure-openai.api-version" :service :azure-openai :provider :azure
    :destination :config-db :value-type :string :tier :query :secret? false
    :what "Azure OpenAI API version."}

   {:env-var "AZURE_OPENAI_USE_AZURE" :path "services.azure-openai.use-azure-openai-api" :service :azure-openai
    :destination :config-db :value-type :boolean :tier :query :secret? false
    :what "Whether to use the Azure API shape rather than the OpenAI one."}

   ;; --- Optional services ---------------------------------------------------
   {:env-var "COLBERT_API_URL" :path "services.colbert.api-url" :service :colbert
    :destination :config-db :value-type :string :tier :optional :secret? false
    :what "ColBERT reranking service URL. Retrieval works without it, less well."}

   {:env-var "COLBERT_API_KEY" :path "services.colbert.api-key" :service :colbert
    :destination :config-db :value-type :string :tier :optional :secret? true
    :what "ColBERT reranking service API key."}

   {:env-var "LMSTUDIO_API_ENDPOINT" :path "services.lmstudio.api-endpoint" :service :lmstudio
    :destination :config-db :value-type :string :tier :optional :secret? false
    :what "Base URL of a local OpenAI-compatible endpoint, e.g. http://localhost:1234."}

   {:env-var "LMSTUDIO_API_KEY" :path "services.lmstudio.api-key" :service :lmstudio
    :destination :config-db :value-type :string :tier :optional :secret? true
    :what "Key for that endpoint. LM Studio accepts any non-empty string."}

   {:env-var "LMSTUDIO_MODEL" :path "services.lmstudio.model" :service :lmstudio
    :destination :config-db :value-type :string :tier :optional :secret? false
    :what "Model name to send to that endpoint."}

   {:env-var "OPENROUTER_API_KEY" :path "services.openrouter.api-key" :service :openrouter
    :destination :config-db :value-type :string :tier :optional :secret? true
    :what "OpenRouter API key."}

   {:env-var "MARKER_API_URL" :path "services.marker.api-url" :service :marker
    :destination :config-db :value-type :string :tier :optional :secret? true
    :what "Marker document-parser URL, used during ingest."}

   {:env-var "MARKER_API_KEY" :path "services.marker.api-key" :service :marker
    :destination :config-db :value-type :string :tier :optional :secret? true
    :what "Marker document-parser API key."}

   {:env-var "SCALEWAY_TEM_API_KEY" :path "services.scaleway-tem.api-key" :service :scaleway-tem
    :destination :config-db :value-type :string :tier :optional :secret? true
    :what "Scaleway Transactional Email key. Only outbound email needs it."}

   ;; The half of the local-model path that NO config write can reach:
   ;; `digdir.llm.client` reads these from the environment on every call, and
   ;; there is no `services.openai.*` definition anywhere for a value to live
   ;; at - so unlike the two rows above there is not even an ignored config
   ;; path to name. They are here so the one table stays the whole answer to
   ;; "what does this system read from the environment", which is what lets
   ;; the setup checklist be derived rather than kept in parallel. (#314)
   {:env-var "OPENAI_API_ENDPOINT" :path nil :service :openai-compatible :provider :openai-compatible
    :destination :environment :tier :optional :secret? false
    :what "Base URL of an OpenAI-compatible endpoint, read per call from the environment."}

   {:env-var "OPENAI_API_KEY" :path nil :service :openai-compatible :provider :openai-compatible
    :destination :environment :tier :optional :secret? true
    :what "Key for that endpoint, read per call from the environment. Must be non-empty even for a server that ignores it."}

   {:env-var "ADMIN_USER_EMAILS" :path "services.auth.admin-user-emails" :service :auth
    :destination :environment :tier :optional :secret? false
    :what "Emails granted admin-full on setup/sync. Read from the environment; the config-DB value at this path is not consulted."}])

;; ---------------------------------------------------------------------------
;; Lookups — the only way the other surfaces should reach the table
;; ---------------------------------------------------------------------------

(defn bridged?
  "True for bindings the bridge is allowed to WRITE into the config DB."
  [binding]
  (= :config-db (:destination binding)))

(defn bindings-for-service
  "Every binding belonging to `service`."
  [service]
  (filterv #(= service (:service %)) env-config-bindings))

(def ^:private first-query-extra
  "Variables a first query needs that do NOT sit in the :query tier.

   The OPENAI_* pair is at :optional because only one of the two provider paths
   uses it — but on that path it is as required as anything else."
  #{"OPENAI_API_ENDPOINT" "OPENAI_API_KEY"})

(defn first-query-bindings
  "The bindings a first real query needs for `wanted-provider`.

   Deliberately not the same set as
   `digdir.config.verify/runtime-required-service-paths`, and the difference is
   the point. That list answers a reachability question - can the runtime SEE
   its own config - and holds the five paths read with a tenant and no
   tenant-config-key. This one answers a supply question: what must a NEWCOMER
   provide. `services.azure-openai.api-key` is the clearest divergence: it is
   not on the reachability list, it ships no value since #279, and without it
   the first LLM call fails - so a check built only on the other list reports
   a clean bill of health to an install that cannot answer a question.

   `wanted-provider` is :azure, :openai-compatible, or :any for the union. It
   matters because ONE family of settings drives both paths, with
   `services.azure-openai.use-azure-openai-api` as the switch - read at the
   call sites in `digdir.skills.builtin.agent.loop` and `digdir.sweep.judge`:

     true  -> api-key, api-endpoint and deployment-name, from config
     false -> model-name from config, and the endpoint and key from
              OPENAI_API_ENDPOINT / OPENAI_API_KEY in the environment

   Ignoring the switch would demand an AZURE_OPENAI_API_KEY from someone who
   deliberately chose the local path and must not set one. A checklist that
   cries wolf on a supported configuration is one people learn to skip, which
   costs more than the check was worth. (#314 made that path first-class.)"
  ([] (first-query-bindings :any))
  ([wanted-provider]
   ;; NB: do not destructure :provider here - it would shadow the parameter.
   (filterv (fn [b]
              (and (or (= :query (:tier b))
                       (contains? first-query-extra (:env-var b)))
                   (or (= :any wanted-provider)
                       (nil? (:provider b))
                       (= wanted-provider (:provider b)))))
            env-config-bindings)))

(defn binding-for-path
  "The binding that supplies `path` (a dotted string), or nil.

   nil is a real answer and must be reported as such: it means no environment
   variable supplies that path, so the reader needs `bb config-set`, not a
   variable name they will search for and never find."
  [path]
  ;; `some?` guards the rows that have no config path at all (the bootstrap
  ;; secrets, and the OPENAI_* pair the client reads per call). Without it a
  ;; nil argument would match the first of them and confidently name the wrong
  ;; variable.
  (when (some? path)
    (first (filter #(= path (:path %)) env-config-bindings))))

(defn binding-for-env-var
  "The binding named `env-var`, or nil."
  [env-var]
  (when (some? env-var)
    (first (filter #(= env-var (:env-var %)) env-config-bindings))))

(defn env-var-for-path
  "The environment variable name that supplies `path`, or nil."
  [path]
  (:env-var (binding-for-path path)))

(defn- binding-for-id
  "The binding a FINDING identifies. Findings key on the config path where
   there is one and on the variable name where there is not - the OPENAI_*
   pair has no config path at all - so both have to resolve here. Looking up
   only by path told a reader that OPENAI_API_ENDPOINT was supplied by no
   environment variable and should be set with `bb config-set`, which is wrong
   in both halves of one sentence."
  [id]
  (or (binding-for-path id) (binding-for-env-var id)))

(defn bindings-for-tier
  "Bindings in `tier`, in table order."
  [tier]
  (filterv #(= tier (:tier %)) env-config-bindings))

(defn env-vars-for-tier
  "Environment variable names in `tier`, in table order."
  [tier]
  (mapv :env-var (bindings-for-tier tier)))

(defn env-vars-for-service
  "Environment variable names belonging to `service`, in table order."
  [service]
  (mapv :env-var (bindings-for-service service)))

(defn secret-env-var?
  "Whether `env-var` carries a credential rather than a setting.

   For diagnostics that print a checklist: a reader pasting their config into a
   chat window should be able to see which lines they must not. Unknown
   variables are treated as secret - the safe direction for a question whose
   wrong answer discloses one."
  [env-var]
  (if-let [b (first (filter #(= env-var (:env-var %)) env-config-bindings))]
    (boolean (:secret? b))
    true))

(defn env-var->config-path
  "The `{env-var-name config-path}` map for `service` — the shape
   `digdir.e2e.seed` carried before this namespace existed, derived rather
   than repeated."
  [service]
  (into {} (map (juxt :env-var :path)) (filter bridged? (bindings-for-service service))))

;; ---------------------------------------------------------------------------
;; Reading the environment
;; ---------------------------------------------------------------------------

(defn- getenv
  "Reads through `digdir.secrets/*env-lookup*` rather than calling
   `System/getenv` directly, so a test can bind one seam instead of mutating
   the JVM's environment. Same one-arity shape as `System/getenv`."
  [env-var]
  (secrets/*env-lookup* env-var))

(defn- parse-bool [s]
  (contains? #{"true" "1" "yes" "on"} (str/lower-case (str/trim (str s)))))

(defn coerce-value
  "The env var's string as the type the config definition expects.

   Trimmed, because surrounding whitespace in a credential is always a
   transcription accident and produces an authentication failure one layer
   away from its cause."
  [{:keys [value-type]} raw]
  (if (= :boolean value-type)
    (parse-bool raw)
    (str/trim raw)))

(defn env-var-present?
  "Whether `env-var` is set to something non-blank. Never returns the value."
  [env-var]
  (not (str/blank? (getenv env-var))))

(defn supplied-env-vars
  "Names of the table's variables that are set in this environment.

   Names only — this is the diagnostic that says WHICH doors are open without
   saying what is behind any of them."
  []
  (filterv env-var-present? (mapv :env-var env-config-bindings)))

;; ---------------------------------------------------------------------------
;; The shopping list
;; ---------------------------------------------------------------------------

(defn supply-instruction
  "How to supply `path`, as one line a newcomer can act on.

   Three genuinely different answers, and collapsing them would send someone
   to set a variable that does nothing:
     - an environment variable writes it into the config DB;
     - an environment variable IS the value and the config DB copy is ignored;
     - nothing supplies it from the environment at all."
  [id]
  (let [{:keys [env-var destination what path]} (binding-for-id id)]
    (cond
      (nil? env-var)
      (str id " — no environment variable supplies this; set it with "
           "`bb config-set " id " <value> <tenant> platform default`")

      (= :environment destination)
      (str env-var " — " what)

      :else
      (str env-var " → " (or path id) (when what (str " — " what))))))

(defn shopping-list
  "One `supply-instruction` line per path, de-duplicated, in table order.

   The point of the ordering is that a reader works down it: the table is
   already sorted boot → query → optional."
  [ids]
  (let [wanted (set ids)
        ;; A binding is matched by EITHER key, so the OPENAI_* rows - which
        ;; have no path - keep their place in the table order instead of being
        ;; swept into the unknown tail.
        known (->> env-config-bindings
                   (keep (fn [b] (first (filter wanted [(:path b) (:env-var b)]))))
                   distinct)
        unknown (sort (remove (set known) wanted))]
    (mapv supply-instruction (concat known unknown))))

;; ---------------------------------------------------------------------------
;; The bridge
;; ---------------------------------------------------------------------------

(defn seed-config-from-env!
  "Write every env-supplied config value onto `tenant`'s Platform/default node.

   Skips bindings whose variable is unset or blank, and bindings whose
   destination is not the config DB. Idempotent — `set-node-value!` is an
   upsert and reports :created / :updated / :unchanged, which is what this
   returns per path.

   Writes to the tenant's own Platform/default node rather than to __global__,
   because the `services.*` definitions are not declared `:ownership :inherit`
   — `cfg/get` only walks up to __global__ for inherit-owned paths. That node
   is also the node the runtime ENTERS at (#275), so a value written here is
   reachable by construction.

   Options:
     :services — only bridge these service keywords. `digdir.e2e.seed` passes
                 #{:azure-openai} so the e2e stack keeps writing exactly what
                 it wrote before this namespace existed.

   Returns {:tenant :paths-written :actions :skipped}, never a value. A missing
   Platform/default node comes back as {:error :no-platform-default-node}
   rather than throwing: this runs on the import path, and which end should
   change is not a decision an import is allowed to make."
  ([conn tenant] (seed-config-from-env! conn tenant nil))
  ([conn tenant {:keys [services]}]
   (if-let [node (config-db/get-config-node-by-tenant-config-key
                   @conn tenant :platform "default")]
     (let [master-key (config-core/get-master-key)
           candidates (cond->> (filter bridged? env-config-bindings)
                        (seq services) (filter #(contains? (set services) (:service %))))]
       (reduce
         (fn [acc {:keys [env-var path] :as binding}]
           (let [raw (getenv env-var)]
             (if (str/blank? raw)
               acc
               (try
                 (let [action (config-db/set-node-value!
                                conn
                                {:root :platform
                                 :tenant tenant
                                 :node-id (:config.node/id node)
                                 :path path
                                 :value (coerce-value binding raw)
                                 :master-key master-key})]
                   (-> acc
                       (update :paths-written conj path)
                       (assoc-in [:actions path] action)))
                 ;; A path whose definition is absent must not fail an import
                 ;; that is otherwise fine. Report it and carry on — the
                 ;; message names the path, never the value.
                 (catch Exception e
                   (update acc :skipped conj
                           {:path path :env-var env-var :reason (.getMessage e)}))))))
         {:tenant tenant :paths-written [] :actions {} :skipped []}
         candidates))
     {:tenant tenant :paths-written [] :actions {} :skipped []
      :error :no-platform-default-node})))

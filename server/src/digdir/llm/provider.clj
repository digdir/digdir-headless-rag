(ns digdir.llm.provider
  "Which LLM provider a tenant uses, and the complete call spec for it.

   ⚠️ ONE READ OF THE PROVIDER SWITCH, DELIBERATELY — the Azure-switch default mismatch, and the provider-resolver change.

   The switch (`services.azure-openai.use-azure-openai-api`) used to be read in
   two places with OPPOSITE defaults and different resolution paths: the
   verifier asked `get-platform-value` with `{:default true}` and reported
   `:azure`, while the runtime asked `cfg/get` with no default at all and, on an
   unset value, fell through to the generic OpenAI path and failed with
   `Missing secret :openai-api-key`. A verifier that disagrees with the runtime
   is worse than no verifier, because it turns \"I checked\" into false
   confidence — and it did: this exact question was investigated, reported
   checked-and-clear from the site WITH the default, and the site WITHOUT one is
   the one that runs.

   The fix for the Azure-switch default mismatch made `accessor/use-azure-openai?` the intended single read,
   but four other sites still read the switch directly and agreed with it only
   by coincidence. This namespace is now the only code that reads it:
   `configured-provider` below. Everything else asks `selected-provider` or `resolve`.

   The semantics are the RUNTIME's, because the runtime is what decides where a
   request actually goes: UNSET MEANS NOT AZURE. If the product wants the
   opposite default, change it HERE, once, and every caller moves with it —
   which is the property that was missing.

   ## Two paths, one read

   `services.llm.provider` (`:azure | :openai-compatible`) is the decision; the
   boolean above is its legacy spelling and the FALLBACK when the new key is
   unset. Both are read here and nowhere else: `configured-provider-with-trace` says
   which decided - `:llm-provider`, `:legacy-switch`, or `:neither` - and never
   names a path that did not decide.

   ## The model, the same shape

   `services.llm.model` is the model; `services.azure-openai.model-name` is its
   legacy spelling and the FALLBACK, so an operator can migrate values in any
   order with nothing broken in between. Both are read here and nowhere else:
   `configured-model-with-trace` says which answered - `:llm-model`,
   `:legacy-model-name`, or `:neither`.

   Every site that ASKS about the model comes here, not just the one that reads
   it to route. `config/verify` and `digdir.boot.provider-switch` asked about
   the legacy path directly, and a presence check of one raw path reports a
   migrated tenant unconfigured while the runtime routes it fine - the Azure-switch default mismatch at the
   verifier, exactly. Azure's model is its deployment name, which is genuinely
   Azure-only and stays where it is.

   ## Credentials: config, or refuse

   `resolve` never returns a nil `:api-key` or `:api-endpoint`. A nil handed
   to the transport is not a failure but a borrowing: wkok fills it from
   AZURE_OPENAI_API_*, and `digdir.llm.client` used to fill it from
   OPENAI_API_* - a per-tenant setting silently replaced by a process-global
   one. A missing credential refuses here, naming its path.

   The one non-config source is the sweep's run-override (see
   `install-run-override!`), which only src-dev installs.

   ## Vocabulary

   - `selected-provider` answers in the system's vocabulary, `:azure |
     :openai-compatible` — the same values as `services.llm.provider`, the
     verifier and the env-bridge tags.
   - A spec's `:impl` is the TRANSPORT key, `:azure | :openai`, which
     `digdir.llm.client` and wkok dispatch on. wkok 0.23.0's `(case impl …)` has
     no default clause, so `:openai-compatible` must never reach it; `impl-for`
     is the one translation."
  (:refer-clojure :exclude [resolve])
  (:require [clojure.string :as str]
            [digdir.config.accessor :as accessor]))

(def ^:private provider-keys
  "`services.llm.provider`, spelled ONCE: the switch-reads census counts every
   code mention of the path as a read."
  [:services :llm :provider])

(def ^:private provider-path (str/join "." (map name provider-keys)))

(def ^:private vocabulary #{:azure :openai-compatible})

(defn- provider-key-value
  "`services.llm.provider` for `tenant`: `:azure`, `:openai-compatible`, or nil
   when unset. Anything else refuses, naming the path - `:azure-openai` was the
   spelling of the KEYWORD selectors (`services.search-phrases.provider`,
   retired by Phase 3 of the provider-resolver change) and is the likeliest thing to be typed here by
   analogy; routing it to the default would be a silent wrong answer."
  [tenant]
  (let [v (apply accessor/get {:tenant tenant} provider-keys)]
    (cond
      ;; Blank is absent, the house rule (the env bridge skips blanks; the boot
      ;; guard's `present?` agrees): it falls through to the legacy boolean.
      (or (nil? v) (and (string? v) (str/blank? v))) nil
      (contains? vocabulary v) v
      :else (throw (ex-info (str provider-path " is " (pr-str v) " for tenant " (pr-str tenant)
                                 "; it must be :azure or :openai-compatible")
                            {:path provider-path :tenant tenant :allowed vocabulary})))))

(defn configured-provider-with-trace
  "THE one read of the provider decision, and which path made it:

     {:value :azure | :openai-compatible | nil
      :source :llm-provider | :legacy-switch | :neither}

   `services.llm.provider` wins when set; otherwise the legacy boolean decides
   (true -> :azure, false -> :openai-compatible); otherwise NEITHER did, and the
   record says so rather than naming a path that did not decide.

   Throws when a path has no config definition registered, or when the tenant
   has no platform tree and no global fallback (`:tenant-root-missing`) -
   callers decide what those mean; `selected-provider` decides for routing."
  [tenant]
  (if-some [p (provider-key-value tenant)]
    {:value p :source :llm-provider}
    (let [b (accessor/get {:tenant tenant} :services :azure-openai :use-azure-openai-api)]
      (if (nil? b)
        {:value nil :source :neither}
        {:value (if b :azure :openai-compatible) :source :legacy-switch}))))

(defn configured-provider
  "The provider decision for `tenant`: `:azure`, `:openai-compatible`, or nil
   when NEITHER path is set. The value of `configured-provider-with-trace`.

   nil is kept apart from a chosen value because `digdir.boot.provider-switch`
   refuses to boot on complete Azure credentials with NO decision, and must
   leave a deliberate :openai-compatible alone."
  [tenant]
  (:value (configured-provider-with-trace tenant)))

(defn selected-provider
  "Which provider `tenant` routes LLM traffic to: `:azure` or
   `:openai-compatible`. UNSET MEANS NOT AZURE.

   A tenant with no platform tree has not chosen a provider, which is the same
   state as an unset switch, so `:tenant-root-missing` answers
   `:openai-compatible`. Only that one condition is swallowed: the verifier
   before the Azure-switch default mismatch caught EVERY exception and answered Azure, which is how a
   deployment with no config at all got reported as `:azure`. A switch with no
   definition registered still throws."
  [tenant]
  (if (= :azure (try
                  (configured-provider tenant)
                  (catch clojure.lang.ExceptionInfo e
                    (if (= :tenant-root-missing (:kind (ex-data e)))
                      nil
                      (throw e)))))
    :azure
    :openai-compatible))

;; ---------------------------------------------------------------------------
;; The model: two paths, one read
;; ---------------------------------------------------------------------------

(def ^:private model-keys
  "`services.llm.model`, spelled ONCE: the model census counts every code
   mention of the path as a read."
  [:services :llm :model])

(def ^:private legacy-model-keys
  "`services.azure-openai.model-name`, the model's legacy spelling and the
   FALLBACK until its values are migrated. Spelled ONCE, for the same reason."
  [:services :azure-openai :model-name])

(def ^:private deployment-keys
  "Azure's deployment name, which IS the model there. Genuinely Azure-only, so
   Phase 4 leaves it where it is."
  [:services :azure-openai :deployment-name])

(def ^:private model-path (str/join "." (map name model-keys)))
(def ^:private legacy-model-path (str/join "." (map name legacy-model-keys)))
(def ^:private deployment-path (str/join "." (map name deployment-keys)))

(defn- present
  "The value, or nil when it is absent. Blank is absent - the house rule the env
   bridge and the boot guard already follow - so a blank falls through to the
   fallback instead of becoming a model nobody can serve."
  [v]
  (when-not (and (string? v) (str/blank? v)) v))

(defn configured-model-with-trace
  "THE one read of the model on the openai-compatible branch, and which path
   made it:

     {:value <model or nil>
      :source :llm-model | :legacy-model-name | :neither}

   `services.llm.model` wins when set; otherwise the legacy
   `services.azure-openai.model-name` answers; otherwise NEITHER did, and the
   record says so rather than naming a path that did not decide. That is the
   same rule as `configured-provider-with-trace`, and it exists for the same
   reason: a source that names a path which did not answer is worse than none,
   because it will be believed.

   Every site that ASKS about the model comes here - the verifier and the boot
   guard included. A presence check of one raw path would report a migrated
   tenant unconfigured while the runtime routes it fine, which is the Azure-switch default mismatch at the
   verifier."
  [tenant]
  (if-some [m (present (apply accessor/get {:tenant tenant} model-keys))]
    {:value m :source :llm-model}
    (if-some [legacy (present (apply accessor/get {:tenant tenant} legacy-model-keys))]
      {:value legacy :source :legacy-model-name}
      {:value nil :source :neither})))

(defn configured-model
  "The model for `tenant` on the openai-compatible branch, or nil when neither
   path is set. The value of `configured-model-with-trace`."
  [tenant]
  (:value (configured-model-with-trace tenant)))

(def ^:private model-source-paths
  {:llm-model model-path :legacy-model-name legacy-model-path})

(defn- default-model-with-trace
  "The provider's default model and where it came from: the deployment name on
   Azure, and on the openai-compatible branch whichever model path answered."
  [tenant provider]
  (if (= :azure provider)
    {:value (apply accessor/get {:tenant tenant} deployment-keys)
     :source {:from :config :path deployment-path}}
    (let [{:keys [value source]} (configured-model-with-trace tenant)]
      {:value value
       :source (if-some [p (model-source-paths source)]
                 {:from :config :path p}
                 {:from :neither})})))

(defn- default-model
  "The provider's default model: the deployment name on Azure, the model
   otherwise. The rule every call site used to spell out as
   `(if azure deployment-name model-name)`."
  [tenant provider]
  (:value (default-model-with-trace tenant provider)))

(defn model-for
  "The model a call for `tenant` uses: `model` when given, else the provider's
   default. Exactly the old `(or model (if azure deployment-name model-name))`:
   a given `model` short-circuits, so nothing — not even the switch — is read.

   Reads no credential. For a site that picks its model in one place and makes
   the call somewhere else; a site that does both should take `:model` from
   `resolve` instead, with one resolution."
  ([tenant] (model-for tenant nil))
  ([tenant model]
   (or model (default-model tenant (selected-provider tenant)))))

(def ^:private impl-for
  "provider → the transport key `digdir.llm.client` and wkok dispatch on."
  {:azure :azure
   :openai-compatible :openai})

(defonce ^:private run-override (atom nil))

(defn install-run-override!
  "SWEEP/BENCHMARK ONLY - called from src-dev, never from src (guarded). Supply
   the OpenAI-compatible endpoint AND key for every call this process makes on
   that branch, in place of the tenant's `services.llm.*`.

   ⚠️ SCOPE: process-global - ONE override per JVM, exactly the scope the
   sweep's `OPENAI_API_ENDPOINT` / `OPENAI_API_KEY` environment already had
   (`runner.clj` reads it once per process; its `env-metadata-keys` is a
   manifest capture, not a per-arm matrix). A sweep that needs two endpoints
   CONCURRENTLY in one process needs a different shape; this one would give
   every arm the last pair installed.

   An atom and not a dynamic binding on purpose: a binding must be conveyed onto
   every executor the agent loop uses, and one that is not falls back SILENTLY
   to config - a sweep arm sent to the wrong host with nothing in the record,
   the failure `env-metadata-keys` was written after.

   The pair travels together: a half-pair refuses, naming the missing variable."
  [{:keys [api-endpoint api-key]}]
  (let [missing (cond-> []
                  (str/blank? api-endpoint) (conj "OPENAI_API_ENDPOINT")
                  (str/blank? api-key) (conj "OPENAI_API_KEY"))]
    (when (seq missing)
      (throw (ex-info (str "Refusing a half-pair run-override: " (str/join " and " missing)
                           " missing. The endpoint and its key travel together.")
                      {:missing missing})))
    (reset! run-override {:api-endpoint api-endpoint :api-key api-key})
    nil))

(defn clear-run-override!
  "Remove the sweep's run-override; `services.llm.*` resolves again."
  []
  (reset! run-override nil)
  nil)

(defn- required-credential
  "One credential from config, tagged with its source - or a refusal naming
   the path. Never nil: see the namespace docstring."
  [tenant path-keys]
  (let [path (str/join "." (map name path-keys))
        v (try
            (apply accessor/get {:tenant tenant} path-keys)
            (catch clojure.lang.ExceptionInfo e
              ;; A tenant with no platform tree used to reach the transport with
              ;; nil credentials and borrow the process environment. It has no
              ;; per-tenant credential to give, so it refuses - naming the path,
              ;; and keeping `:kind` for callers that decide on it.
              (if (= :tenant-root-missing (:kind (ex-data e)))
                (throw (ex-info (str path " cannot be resolved for tenant " (pr-str tenant)
                                     ": it has no platform tree, so it has no credential of its own.")
                                {:path path :tenant tenant :kind :tenant-root-missing}
                                e))
                (throw e))))]
    (when (str/blank? (some-> v str))
      (throw (ex-info (str path " is unset for tenant " (pr-str tenant)
                           ", so its LLM calls cannot be made. Set it with `bb config-set "
                           path " <value> " tenant " platform default`.")
                      {:path path :tenant tenant})))
    [v {:from :config :path path}]))

(defn resolve
  "The complete call spec for `tenant`'s provider:

     {:provider        :azure | :openai-compatible
      :impl            :azure | :openai        ; the transport key
      :model           the caller's `:model` when given, else the provider's
                       default — the deployment name on Azure, the model name
                       otherwise
      :api-key         :api-endpoint
      :provider/source {:api-key {...} :api-endpoint {...} :model {...}}}

   `opts` takes the caller's `:model`. The default is read ONLY when that is
   nil, reproducing the `(or model (if azure deployment-name model-name))` every
   call site used to spell out: a caller that names a model never read the
   default, and on a tenant with no platform tree that read throws.

   Pass it straight to `digdir.llm.client/create-chat-completion` (or
   `digdir.llm.openai/streaming-chat-completion`) as opts; no call site needs
   to know which provider is in play.

   Each branch reads only its own credentials: an openai-compatible tenant
   never has its Azure credentials read, nor an Azure tenant its
   `services.llm.*`. Every credential comes from config (or, on the
   openai-compatible branch, the sweep's run-override) and is NEVER nil - a
   missing one refuses here, naming its path, instead of reaching a transport
   that would fill it from the process environment. This
   namespace never reads the environment.

   `:provider/source` says where each credential came from, for the call
   record (`digdir.llm.provenance`). It is a plain key rather than metadata so
   that a call site which drops it leaves it visibly ABSENT — recorded as
   `:untagged` — instead of silently lost."
  ([tenant] (resolve tenant {}))
  ([tenant {:keys [model]}]
   (let [provider (selected-provider tenant)]
     (if (= :azure provider)
       (let [{m :value model-source :source} (if (some? model)
                                               {:value model :source {:from :caller}}
                                               (default-model-with-trace tenant provider))
             [api-key key-source] (required-credential tenant [:services :azure-openai :api-key])
             [api-endpoint endpoint-source] (required-credential tenant [:services :azure-openai :api-endpoint])]
         {:provider provider
          :impl (impl-for provider)
          :model m
          :api-key api-key
          :api-endpoint api-endpoint
          :provider/source {:api-key key-source :api-endpoint endpoint-source :model model-source}})
       (let [{m :value model-source :source} (if (some? model)
                                               {:value model :source {:from :caller}}
                                               (default-model-with-trace tenant provider))
             override @run-override
             [api-key key-source] (if override
                                    [(:api-key override) {:from :run-override}]
                                    (required-credential tenant [:services :llm :api-key]))
             [api-endpoint endpoint-source] (if override
                                              [(:api-endpoint override) {:from :run-override}]
                                              (required-credential tenant [:services :llm :api-endpoint]))]
         {:provider provider
          :impl (impl-for provider)
          :model m
          :api-key api-key
          :api-endpoint api-endpoint
          :provider/source {:api-key key-source :api-endpoint endpoint-source :model model-source}})))))

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
   `switch-value` below. Everything else asks `selected-provider` or `resolve`.

   The semantics are the RUNTIME's, because the runtime is what decides where a
   request actually goes: UNSET MEANS NOT AZURE. If the product wants the
   opposite default, change it HERE, once, and every caller moves with it —
   which is the property that was missing.

   ## Vocabulary

   - `selected-provider` answers in the system's vocabulary, `:azure |
     :openai-compatible` — the same values as `services.llm.provider`, the
     verifier and the env-bridge tags.
   - A spec's `:impl` is the TRANSPORT key, `:azure | :openai`, which
     `digdir.llm.client` and wkok dispatch on. wkok 0.23.0's `(case impl …)` has
     no default clause, so `:openai-compatible` must never reach it; `impl-for`
     is the one translation."
  (:refer-clojure :exclude [resolve])
  (:require [digdir.config.accessor :as accessor]))

(defn switch-value
  "The provider switch for `tenant`, raw: `true`, `false`, or `nil` when unset.
   THE one read of it.

   Kept raw because unset and `false` are different answers to
   `digdir.boot.provider-switch`, which refuses to boot on complete Azure
   credentials with the switch UNSET and must leave a deliberate `false` alone.

   Throws when the switch has no config definition registered, or when the
   tenant has no platform tree and no global fallback (`:tenant-root-missing`)
   — callers decide what those mean; `selected-provider` decides for routing."
  [tenant]
  (accessor/get {:tenant tenant} :services :azure-openai :use-azure-openai-api))

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
  (if (try
        (boolean (switch-value tenant))
        (catch clojure.lang.ExceptionInfo e
          (if (= :tenant-root-missing (:kind (ex-data e)))
            false
            (throw e))))
    :azure
    :openai-compatible))

(defn- default-model
  "The provider's default model: the deployment name on Azure, the model name
   otherwise. The rule every call site used to spell out as
   `(if azure deployment-name model-name)`."
  [tenant provider]
  (if (= :azure provider)
    (accessor/get {:tenant tenant} :services :azure-openai :deployment-name)
    (accessor/get {:tenant tenant} :services :azure-openai :model-name)))

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

(defn- azure-credential
  "Read one Azure credential and tag where it came from. `:present? false`
   means the transport will fall back to its own env read (wkok's
   `AZURE_OPENAI_API_*`) — the tag says so rather than hiding it."
  [tenant k]
  (let [v (accessor/get {:tenant tenant} :services :azure-openai k)]
    [v {:from :config
        :path (str "services.azure-openai." (name k))
        :present? (some? v)}]))

(defn resolve
  "The complete call spec for `tenant`'s provider:

     {:provider        :azure | :openai-compatible
      :impl            :azure | :openai        ; the transport key
      :model           the caller's `:model` when given, else the provider's
                       default — the deployment name on Azure, the model name
                       otherwise
      :api-key         :api-endpoint
      :provider/source {:api-key {...} :api-endpoint {...}}}

   `opts` takes the caller's `:model`. The default is read ONLY when that is
   nil, reproducing the `(or model (if azure deployment-name model-name))` every
   call site used to spell out: a caller that names a model never read the
   default, and on a tenant with no platform tree that read throws.

   Pass it straight to `digdir.llm.client/create-chat-completion` (or
   `digdir.llm.openai/streaming-chat-completion`) as opts; no call site needs
   to know which provider is in play.

   Reads only what each branch read before this existed: an openai-compatible
   tenant never has its Azure credentials read. The credentials of that branch
   are left nil — the transport resolves them exactly as it did when those
   call sites passed no opts at all. This namespace never reads the
   environment.

   `:provider/source` says where each credential came from, for the call
   record (`digdir.llm.provenance`). It is a plain key rather than metadata so
   that a call site which drops it leaves it visibly ABSENT — recorded as
   `:untagged` — instead of silently lost."
  ([tenant] (resolve tenant {}))
  ([tenant {:keys [model]}]
   (let [provider (selected-provider tenant)]
     (if (= :azure provider)
       (let [model (or model (default-model tenant provider))
             [api-key key-source] (azure-credential tenant :api-key)
             [api-endpoint endpoint-source] (azure-credential tenant :api-endpoint)]
         {:provider provider
          :impl (impl-for provider)
          :model model
          :api-key api-key
          :api-endpoint api-endpoint
          :provider/source {:api-key key-source :api-endpoint endpoint-source}})
       {:provider provider
        :impl (impl-for provider)
        :model (or model (default-model tenant provider))
        :api-key nil
        :api-endpoint nil
        :provider/source {:api-key {:from :unresolved}
                          :api-endpoint {:from :unresolved}}}))))

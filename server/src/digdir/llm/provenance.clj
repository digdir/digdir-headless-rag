(ns digdir.llm.provenance
  "Where each LLM call's parameters came from, and where the call went.

   Two kinds of event land in one ambient sink:
   - `:parameters`, recorded by the graph runner per `execute-step`: which of
     the four parameter layers supplied `:model`, `:temperature`,
     `:max-tokens`, … (`runner/resolve-step-parameters-with-trace`);
   - `:llm-call`, recorded at the transport of each chat-completion chokepoint
     — `digdir.llm.client` (both branches) and
     `digdir.llm.openai/streaming-chat-completion`: the branch, the endpoint
     host, whether a key was present, and the model/temperature/max-tokens
     trio as the caller passed it and as it left.

   Why both: the provider fallback that picks a model when no layer sets one
   runs INSIDE each skill, below the runner's merge. A record taken only at
   the merge says \"no layer set :model\" whatever that fallback chose, so it
   cannot see a change to the fallback. The wire record can.

   Why an ambient binding rather than a return value: same reasoning as
   `digdir.llm.client/*usage-writes*` — the step that owns the call sits
   several frames above it, and threading records out through every skill's
   result would change their contracts. Same contract too: the binding is
   conveyed by `future` but NOT by a raw `Thread.` or a bare executor, so a
   call made there goes unrecorded. Pair any check on these records with an
   absolute one (expected call count), or an unrecorded call reads as
   \"nothing changed\".

   Never a credential: key PRESENCE and where it came from, never its value;
   endpoint HOST, never the full URL."
  (:require [clojure.string :as str]))

(def ^:dynamic *sink*
  "When bound to an atom holding a vector, `record!` appends events to it."
  nil)

(defn record!
  "Append `event` to the ambient sink, if one is bound. Always returns nil."
  [event]
  (when-some [sink *sink*]
    (swap! sink conj event))
  nil)

(defn capture
  "Run `f` with a fresh sink. Returns `{:result <f's value> :events [...]}`,
   events in the order they were recorded."
  [f]
  (let [sink (atom [])
        result (binding [*sink* sink] (f))]
    {:result result :events @sink}))

;; =============================================================================
;; The wire record
;; =============================================================================

(def ^:private trio-keys
  "The request-body keys a call record carries. Nothing else from the body is
   recorded — messages, tools and prompts stay out."
  [:model :temperature :max_tokens :max_completion_tokens])

(defn- trio [params]
  (select-keys params trio-keys))

(defn endpoint-host
  "`host` or `host:port` of `endpoint`; nil when it has no parseable host.
   Never the path or query."
  [endpoint]
  (try
    (let [uri (java.net.URI. (str endpoint))
          host (.getHost uri)
          port (.getPort uri)]
      (when host
        (if (neg? port) host (str host ":" port))))
    (catch Exception _ nil)))

(def ^:private wkok-env
  "The env vars wkok 0.23.0 falls back to when an opt is nil, per impl
   (`wkok/openai_clojure/openai.clj` `add-headers` / `override-api-endpoint`;
   `azure.clj` `add-authentication-header` / `override-api-endpoint`), and the
   base URL its openai impl uses when both are absent. Azure has none."
  {:azure  {:endpoint "AZURE_OPENAI_API_ENDPOINT" :key "AZURE_OPENAI_API_KEY" :default nil}
   :openai {:endpoint "OPENAI_API_ENDPOINT" :key "OPENAI_API_KEY"
            :default "https://api.openai.com/v1"}})

(defn wkok-destination
  "Where a wkok call will go and whether it will carry a key, given the `opts`
   wkok receives.

   A RE-DERIVATION of wkok's own rule, not an observation — wkok reads the
   environment internally whenever an opt is nil, on BOTH impls, so a tenant
   with no configured key silently uses the process-global one. That door is
   inside a jar; this is the only visibility into it. `:endpoint-rederived?`
   and `:key-rederived?` are true exactly when that opt was nil and the rule
   was applied.

   Returns the facts `record-call!` takes. The key itself is never returned."
  [branch opts]
  (let [{env-endpoint :endpoint env-key :key default :default} (wkok-env branch)
        opt-endpoint (:api-endpoint opts)
        opt-key (:api-key opts)
        from-env-endpoint (System/getenv env-endpoint)
        from-env-key (System/getenv env-key)
        endpoint-from (cond (some? opt-endpoint) :opts
                            (some? from-env-endpoint) :env
                            (some? default) :default)
        key-from (cond (some? opt-key) :opts
                       (some? from-env-key) :env)]
    {:endpoint (or opt-endpoint from-env-endpoint default)
     :endpoint-from endpoint-from
     :endpoint-rederived? (not= :opts endpoint-from)
     :key-present? (not (str/blank? (or opt-key from-env-key)))
     :key-from key-from
     :key-rederived? (not= :opts key-from)}))

(defn record-call!
  "Record one LLM call as it is about to reach the transport. A no-op when no
   sink is bound, and it never throws: a failure building the record drops
   the record, never the call.

   `caller` is the request as the call site passed it; `pre-normalize` is it
   after any env merge; `sent` is the body as it leaves (after
   `model-params/normalize-request`). `env-applied` is the set of body keys
   the `OPENAI_*` env overrode.

   `source` is the `:provider/source` tag `digdir.llm.provider/resolve` puts on
   the spec — the only place that knows whether a credential came from config.
   An absent tag, or one without `:from`, records `:untagged`: an explicit
   ABSENCE. It never defaults to a plausible source, because a guard reading a
   defaulted `:config` would pass exactly when a call site had dropped the tag."
  [{:keys [path branch endpoint endpoint-from endpoint-rederived? key-present? key-from
           key-rederived? caller pre-normalize sent env-applied source]}]
  (when *sink*
    (try
      (record! {:event :llm-call
                :path path
                :branch branch
                :endpoint-host (endpoint-host endpoint)
                :endpoint-from endpoint-from
                :endpoint-rederived? (boolean endpoint-rederived?)
                :key-present? (boolean key-present?)
                :key-from key-from
                :key-rederived? (boolean key-rederived?)
                :key-source (or (get-in source [:api-key :from]) :untagged)
                :endpoint-source (or (get-in source [:api-endpoint :from]) :untagged)
                :caller (trio caller)
                :sent (trio sent)
                :env-applied (set env-applied)
                :normalized? (not= (trio (or pre-normalize caller)) (trio sent))})
      (catch Throwable _ nil)))
  nil)

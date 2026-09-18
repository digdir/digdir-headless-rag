(ns digdir.telemetry.langfuse
  "Emit `invoke-rag` runs to Langfuse over OTLP/HTTP.

   ## What reaches Langfuse, and from where

   One trace per `invoke-rag` call, which is the canonical entry every surface
   converges on — `/v1/chat/completions` (Open WebUI), the Playground, and MCP
   `tools/call` all route through it. So instrumenting THERE rather than at the
   three callers means one seam instead of three, and nothing is missed when a
   fourth surface is added.

       root span  (the run: query in, answer out)
         └── generation  (one per LLM call that reported tokens)

   ## Deliberately NOT the full graph hierarchy, yet

   The `digdir.skills.events` vocabulary (`:step/started`, `:tool/call`,
   `:agent/iteration-started`, …) maps cleanly onto nested spans and is the
   obvious next slice. It is left out of this one because it needs live
   parent-id threading through the progress-fn, while the shape below is
   derivable from the RESULT alone — so this version cannot perturb a running
   query no matter what it gets wrong.

   ## Failure policy: silence, never propagation

   Tracing is an observer. A Langfuse outage, a bad key, a timeout, or a
   serialization bug must never turn a working RAG answer into an error, so
   every path here is wrapped and logged at :warn. The emit itself runs on a
   future: the HTTP round-trip to langfuse.digdir.cloud is ~100ms and the
   caller should not wait for it.

   ⚠️ This SENDS THE QUESTION AND THE ANSWER off the box. That is the entire
   point of tracing, and the destination is Digdir's own instance — but it is
   the reason `LANGFUSE_ENABLED` exists and defaults to off."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            ;; Not aliased to `str`: this namespace uses clojure.core/str
            ;; heavily and the shadow would be a silent footgun.
            [clojure.string]
            [digdir.config.accessor :as cfg]
            [digdir.skills.usage :as usage]
            [taoensso.telemere :as t])
  (:import [java.security SecureRandom]
           [java.util Base64]))

;; --- configuration -------------------------------------------------------
;;
;; Read from the environment, not the config DB. The config DB is per-tenant
;; and resolved per request; tracing is a property of the DEPLOYMENT, and a
;; run that fails before tenant resolution still deserves a trace.

(defn- env [k] (not-empty (System/getenv k)))

(defn enabled?
  "True when tracing is switched on AND actually configured.

   Both halves matter. `LANGFUSE_ENABLED=true` with no keys is a
   misconfiguration that would otherwise fail once per query, forever, in the
   logs — so an unconfigured instance is simply off, and says so once."
  []
  (boolean (and (= "true" (some-> (env "LANGFUSE_ENABLED") .trim .toLowerCase))
                (env "LANGFUSE_PUBLIC_KEY")
                (env "LANGFUSE_SECRET_KEY")
                (env "LANGFUSE_BASE_URL"))))

(def ^:private client-task-marker
  "How an Open WebUI housekeeping prompt announces itself.

   Open WebUI calls /v1/chat/completions a SECOND time after every message to
   generate follow-up suggestions, and again for conversation titles, tags and
   search queries — all four on by default (`ENABLE_*_GENERATION` in its
   config.py). Those calls run the whole agent: measured on this stack, a
   follow-up generation cost 1265 tokens against 4189 for the question that
   triggered it, a ~30% overhead of UI chrome in the trace list.

   That is not our feature and not our cost model, so it is not our trace.

   ⚠️ WHY A PROMPT PREFIX, WHICH IS A WEAK SIGNAL. Open WebUI DOES mark these
   properly — `routers/tasks.py` sets `metadata.task` on the payload — but
   `routers/openai.py:1490` does `payload.pop(\"metadata\", None)` before the
   outbound request, so the marker never reaches us. The prompt body is all
   that arrives. Verified against the image we run (0.11.3): five of the six
   DEFAULT_*_GENERATION_PROMPT_TEMPLATE constants open with exactly this
   string; only emoji generation differs.

   The limitation that follows: an operator who customises
   FOLLOW_UP_GENERATION_PROMPT_TEMPLATE away from the default drops out of the
   match and their task runs start being traced again. Skips are logged, so
   that shows up as traces appearing rather than as silence."
  "### Task:")

(defn- client-task-prompt?
  "True when this query is a chat client's own housekeeping, not a user's
   question. Leading whitespace is tolerated; nothing else about the body is
   inspected."
  [user-query]
  (and (string? user-query)
       (clojure.string/starts-with? (clojure.string/triml user-query)
                                    client-task-marker)))

(defn- trace-client-tasks?
  "Opt back IN to tracing the client's housekeeping calls. Off by default —
   they are noise in every view Langfuse offers — but they are real spend, so
   there has to be a way to look at them."
  []
  (= "true" (some-> (env "LANGFUSE_TRACE_CLIENT_TASKS") .trim .toLowerCase)))

(defn- auth-header []
  (str "Basic "
       (.encodeToString (Base64/getEncoder)
                        (.getBytes (str (env "LANGFUSE_PUBLIC_KEY") ":"
                                        (env "LANGFUSE_SECRET_KEY"))
                                   "UTF-8"))))

(defn- traces-url []
  (str (clojure.string/replace (env "LANGFUSE_BASE_URL") #"/+$" "")
       "/api/public/otel/v1/traces"))

;; --- OTLP primitives -----------------------------------------------------

(def ^:private ^SecureRandom rng (SecureRandom.))

(defn- hex
  "`n` random bytes as lowercase hex. OTLP wants 16-byte trace ids and
   8-byte span ids, both hex-encoded."
  [n]
  (let [bs (byte-array n)]
    (.nextBytes rng bs)
    (apply str (map #(format "%02x" (bit-and % 0xff)) bs))))

(def ^:private max-attr-chars
  "Cap on any single string attribute. A run that retrieved 40 chunks can
   carry hundreds of kilobytes of context, and an OTLP payload that large is
   rejected by some collectors and useless in a UI either way. Truncation is
   marked so a reader does not mistake it for the model stopping early."
  10000)

(defn- clamp [s]
  (let [s (str s)]
    (if (> (count s) max-attr-chars)
      (str (subs s 0 max-attr-chars) "…[truncated]")
      s)))

(defn- attr [k v]
  {:key (name k) :value {:stringValue (clamp v)}})

(defn- json-attr [k v]
  (attr k (json/generate-string v)))

(defn- now-nanos [] (* (System/currentTimeMillis) 1000000))

(defn- resolved-model
  "The model this run was CONFIGURED to use, or nil. A fallback, not the
   primary source.

   The primary source is `usage/timing-model`, which reads `:llm-model` off the
   individual stage timing — the model the provider echoed back for that call.
   This function covers the case that key is legitimately absent, because every
   writer of `:llm-model` guards on the response carrying one.

   Resolved the way the RUNTIME resolves it, per tenant, via
   `cfg/use-azure-openai?` — deliberately the one read of that switch (#500).
   Mirrors `digdir.llm.openai/create-chat-completion`.

   ⚠️ Being run-level, it reports the same value for every call, so a
   per-skill model override is invisible here. That is exactly why it is the
   fallback and not the first choice.

   Runs inside the emit future, so a config read that throws costs a trace
   attribute and never the request."
  [tenant]
  (try
    (when tenant
      (if (cfg/use-azure-openai? tenant)
        (cfg/get {:tenant tenant} :services :azure-openai :deployment-name)
        (cfg/get {:tenant tenant} :services :azure-openai :model-name)))
    (catch Exception _ nil)))

;; --- span construction ---------------------------------------------------

(defn- trace-attrs
  "Attributes Langfuse reads at TRACE level. The documentation is explicit
   that these must be repeated on every span in the trace, not only the root,
   for filtering and aggregation to work — so they are merged into each."
  [{:keys [agent-id tenant session-id]}]
  (cond-> [(attr :langfuse.trace.name (or agent-id "digdir-rag"))]
    (env "LANGFUSE_ENVIRONMENT") (conj (attr :langfuse.environment (env "LANGFUSE_ENVIRONMENT")))
    session-id                   (conj (attr :langfuse.session.id session-id))
    tenant                       (conj (attr :tenant tenant))))

(defn- root-span
  [{:keys [trace-id span-id start-ns end-ns user-query result ctx]}]
  {:traceId trace-id
   :spanId span-id
   :name (or (:agent-id ctx) "invoke-rag")
   :kind 1
   :startTimeUnixNano (str start-ns)
   :endTimeUnixNano (str end-ns)
   :attributes (into (trace-attrs ctx)
                     (cond-> [(attr :langfuse.observation.type "span")
                              (json-attr :langfuse.observation.input user-query)
                              (json-attr :langfuse.observation.output (:response result))
                              (attr :digdir.status (name (or (:status result) :unknown)))
                              (attr :digdir.chunks (count (:chunks result)))]
                       (:skill-graph-id ctx)
                       (conj (attr :digdir.skill-graph (str (:skill-graph-id ctx))))
                       ;; A failed run is worth MORE in a trace than a
                       ;; successful one, so the error rides along rather than
                       ;; being filtered out by the caller.
                       (:error result)
                       (conj (json-attr :langfuse.observation.level "ERROR")
                             (attr :digdir.error (str (get-in result [:error :error-message]))))))})

(defn- generation-span
  "One LLM call, as a Langfuse `generation`.

   `langfuse.observation.type` is set explicitly because explicit type
   declarations take precedence over convention-based inference — which means
   this does not depend on Langfuse guessing right from the other attributes."
  [{:keys [trace-id parent-id start-ns end-ns ctx model]} timing]
  (when-let [u (usage/call-usage timing)]
    (let [dur (or (:duration-ms timing) 0)
          end (min end-ns (+ start-ns (* dur 1000000)))]
      {:traceId trace-id
       :spanId (hex 8)
       :parentSpanId parent-id
       :name (str (or (:stage timing) "llm-call"))
       :kind 1
       :startTimeUnixNano (str start-ns)
       :endTimeUnixNano (str (max end start-ns))
       :attributes (let [;; PER-CALL first: `:llm-model` is what the provider
                         ;; echoed back for THIS call, so it survives a
                         ;; per-skill model override that the run-level
                         ;; fallback would paper over. nil is legitimate — the
                         ;; writers only set it when the response carried one.
                         m (or (usage/timing-model timing) model)]
                     (into (trace-attrs ctx)
                           (cond-> [(attr :langfuse.observation.type "generation")
                                    (json-attr :langfuse.observation.usage_details u)]
                             m (conj (attr :gen_ai.request.model m)))))})))

(defn- payload
  [{:keys [user-query result ctx start-ns end-ns]}]
  (let [trace-id (hex 16)
        root-id (hex 8)
        ;; Resolved ONCE per run, not per generation: it is one config read
        ;; and the value is identical for every call in the loop.
        model (or (:model ctx) (resolved-model (:tenant ctx)))
        root (root-span {:trace-id trace-id :span-id root-id
                         :start-ns start-ns :end-ns end-ns
                         :user-query user-query :result result :ctx ctx})
        gens (keep #(generation-span {:trace-id trace-id :parent-id root-id
                                      :start-ns start-ns :end-ns end-ns
                                      :ctx ctx :model model}
                                     %)
                   (usage/stage-timings result))]
    {:trace-id trace-id
     :body {:resourceSpans
            [{:resource {:attributes [(attr :service.name "digdir-rag")]}
              :scopeSpans [{:scope {:name "digdir.telemetry.langfuse"}
                            :spans (into [root] gens)}]}]}}))

;; --- emit ----------------------------------------------------------------

(defn- post! [body]
  (http/post (traces-url)
             {:body (json/generate-string body)
              :content-type :json
              :headers {"Authorization" (auth-header)
                        ;; Without this, v4 may hold data for up to 10 minutes
                        ;; before it appears — which reads as "tracing is
                        ;; broken" to anyone checking after a query.
                        "x-langfuse-ingestion-version" "4"}
              :throw-exceptions false
              :socket-timeout 10000
              :connection-timeout 5000}))

(defn emit-invocation!
  "Send one `invoke-rag` run to Langfuse. Fire-and-forget; returns nil.

   No-op when `enabled?` is false, so an instance without Langfuse configured
   pays one map lookup per query and nothing else."
  [{:keys [user-query result ctx start-ns end-ns]}]
  (when (enabled?)
    (if (and (client-task-prompt? user-query)
             (not (trace-client-tasks?)))
      ;; Logged rather than dropped in silence: a trace that never arrives is
      ;; otherwise indistinguishable from tracing being broken, and this is
      ;; the one code path that deliberately produces nothing.
      (t/log! :debug [:langfuse/skipped-client-task
                      {:reason :client-housekeeping-prompt
                       :override "LANGFUSE_TRACE_CLIENT_TASKS=true"}])
      (future
      (try
        (let [{:keys [trace-id body]} (payload {:user-query user-query :result result
                                                :ctx ctx :start-ns start-ns :end-ns end-ns})
              {:keys [status]} (post! body)]
          (if (<= 200 status 299)
            (t/log! :debug [:langfuse/emitted {:trace-id trace-id :status status}])
            (t/log! :warn [:langfuse/rejected {:trace-id trace-id :status status}])))
        (catch Exception e
          ;; Swallowed on purpose — see the failure policy in the ns docstring.
          (t/log! :warn [:langfuse/emit-failed {:error (.getMessage e)}]))))))
  nil)

(defn wrap-invocation
  "Time `f`, emit the result to Langfuse, return the result untouched.

   Shaped as a wrapper so the call site reads as one expression and there is
   no way to return early past the emit."
  [{:keys [user-query ctx]} f]
  (let [start (now-nanos)
        result (f)]
    (emit-invocation! {:user-query user-query :result result :ctx ctx
                       :start-ns start :end-ns (now-nanos)})
    result))

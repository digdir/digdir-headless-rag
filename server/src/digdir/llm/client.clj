(ns digdir.llm.client
  "Faithful OpenAI-compatible chat-completion client (clj-http).

   Drop-in replacement for `wkok.openai-clojure.api/create-chat-completion` at
   NON-STREAMING call sites. Built because wkok routes through martian + a bundled
   OpenAPI spec and COERCES the request body against that spec, silently STRIPPING
   any key the spec doesn't know — notably `reasoning_effort` (OpenAI added it
   after wkok's spec was generated). Dropping it leaves reasoning-class local
   models (gemma-4, qwen3.6 via LM Studio) spending the whole token budget on a
   hidden thinking channel, so the visible answer comes back empty (or the call
   takes 10-100x longer). Verified: wkok sends 0 effect for `reasoning_effort`;
   a direct POST honours it (0 reasoning tokens, clean answer).

   This client POSTs the body VERBATIM, so `reasoning_effort`, `tools`,
   `response_format`, and any future param pass through untouched. It returns the
   parsed JSON in wkok's response shape:
     {:choices [{:message {:content ... :tool_calls ...} :finish_reason ...}]
      :usage {...}}
   so existing consumers ( (-> resp :choices first :message :content),
   (:tool_calls message), (:finish_reason choice), (:usage resp) ) are unchanged.

   `:impl :azure` is DELEGATED to wkok unchanged: the Azure cloud path works and
   doesn't hit the reasoning-param problem (its models aren't the local reasoning
   ones), and replicating Azure's deployment-URL + api-version shape buys nothing.
   Note the stripping above is specific to wkok's *openai* impl — its *azure* impl
   POSTs the body verbatim (verified against a local echo server: both
   `max_completion_tokens` and `reasoning_effort` arrive intact). That is why
   `digdir.llm.model-params` normalization is applied on the Azure branch too and
   actually reaches the wire.

   Streaming is intentionally out of scope — only the interactive UI streams; the
   sweep / agent-eval path is non-streaming (see agent/loop.clj `call-llm`).

   HTTP 429 is retried with backoff on BOTH branches, mirroring
   `digdir.llm.anthropic` so rate-limit behaviour stops being provider-dependent
   (see `with-429-retry`)."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [digdir.llm.model-params :as model-params]
            [digdir.secrets :as secrets]
            [taoensso.telemere :as t]
            [wkok.openai-clojure.api :as wkok]))

(def ^:private default-openai-endpoint "https://api.openai.com/v1")

(defn- env-num
  "Parse a numeric env var (Long or Double) via EDN; nil if unset/non-numeric."
  [k]
  (when-let [s (some-> (System/getenv k) str/trim not-empty)]
    (let [v (try (edn/read-string s) (catch Exception _ nil))]
      (when (number? v) v))))

(def ^:private env->body-key
  "Env var → request-body key for inference params we want to control globally for
   local-model runs without editing call sites. Includes non-OpenAI-standard knobs
   (top_k, min_p, repetition_penalty) that LM Studio/llama.cpp accept on the body —
   e.g. Qwen3.6's recommended thinking-mode set: temp 1.0, top_p 0.95, top_k 20,
   min_p 0.0, presence_penalty 1.5. When set, these OVERRIDE the caller's value
   (so the agent's hardcoded `temperature 0.3` can be corrected from the env)."
  {"OPENAI_TEMPERATURE"        :temperature
   "OPENAI_TOP_P"              :top_p
   "OPENAI_TOP_K"              :top_k
   "OPENAI_MIN_P"              :min_p
   "OPENAI_PRESENCE_PENALTY"   :presence_penalty
   "OPENAI_REPETITION_PENALTY" :repetition_penalty
   "OPENAI_MAX_TOKENS"         :max_tokens})

(defn- env-inference-overrides
  "Map of request-body params set from env (empty when none set)."
  []
  (into {} (keep (fn [[envk bodyk]]
                   (when-let [v (env-num envk)] [bodyk v]))
                 env->body-key)))

(defn- env-flag?
  "Truthy boolean env var (true/1/yes/on)."
  [k]
  (contains? #{"true" "1" "yes" "on"} (some-> (System/getenv k) str/trim str/lower-case)))

(def ^:private no-think-prefill
  "Trailing assistant turn that forces NON-thinking mode on Qwen3.6 GGUF/MTP via
   LM Studio. The model's `enable_thinking` chat-template flag is a no-op there, but
   prefilling a CLOSED think block makes the model continue *after* it — measured:
   reasoning_tokens 0 and ~6x faster than thinking mode. Single-turn ONLY (don't
   append to the multi-turn agent conversation)."
  {:role "assistant" :content "<think></think>"})

(defn- openai-compat-completion
  "Direct clj-http POST to an OpenAI-compatible `/chat/completions`. Endpoint and
   key come from opts, else the `OPENAI_API_ENDPOINT` / `OPENAI_API_KEY` env vars
   (the same vars wkok's openai impl honours), else the public OpenAI default.
   When `OPENAI_REASONING_EFFORT` is set and the body doesn't already specify it,
   it's injected — this is how we turn reasoning off/level for local models
   globally without editing every call site. Sampling/length params
   (`OPENAI_TEMPERATURE`, `OPENAI_TOP_P`, `OPENAI_TOP_K`, `OPENAI_MIN_P`,
   `OPENAI_PRESENCE_PENALTY`, `OPENAI_REPETITION_PENALTY`, `OPENAI_MAX_TOKENS`) are
   injected the same way and OVERRIDE the caller's value — so e.g. the agent's
   hardcoded `temperature 0.3` can be set to a model's recommended values per run.
   `OPENAI_DISABLE_THINKING=true` appends a closed-`<think>` assistant turn to force
   non-thinking mode on models where the `enable_thinking` flag is a no-op (Qwen3.6
   GGUF in LM Studio) — single-turn callers only (e.g. the judge), not the agent."
  [params {:keys [api-key api-endpoint]}]
  (let [endpoint (or api-endpoint
                     (System/getenv "OPENAI_API_ENDPOINT")
                     default-openai-endpoint)
        ;; Was `(System/getenv "OPENAI_API_KEY")`, which yielded nil when unset
        ;; and sent a keyless request — the provider then failed to authenticate,
        ;; one layer away from the actual cause. Now it fails here, naming the
        ;; secret (#22). An explicit `:api-key` in opts still wins.
        api-key  (or api-key (secrets/get! :openai-api-key))
        effort   (System/getenv "OPENAI_REASONING_EFFORT")
        body     (cond-> (merge params (env-inference-overrides))
                   (env-flag? "OPENAI_DISABLE_THINKING")
                   (update :messages (fnil conj []) no-think-prefill)
                   ;; `OPENAI_PRESERVE_THINKING=true` keeps prior-turn reasoning in
                   ;; context across the multi-turn agent loop (Qwen3.6 card: improves
                   ;; decision consistency + reduces redundant reasoning). Merged into
                   ;; `chat_template_kwargs` so a caller-supplied map isn't clobbered,
                   ;; and the client passes it through verbatim (never strips it).
                   (env-flag? "OPENAI_PRESERVE_THINKING")
                   (update :chat_template_kwargs (fnil assoc {}) :preserve_thinking true)
                   ;; `OPENAI_ENABLE_THINKING=false` turns OFF the model's reasoning
                   ;; channel for the multi-turn agent loop by setting `enable_thinking`
                   ;; false in `chat_template_kwargs` (Qwen3.6 GGUF honours it). Unlike
                   ;; OPENAI_DISABLE_THINKING (which appends a closed-think prefill and is
                   ;; single-turn ONLY), this is safe across the agent loop. Used to make
                   ;; the bandwidth-bound dense arms tractable on GB10 (thinking generates
                   ;; ~2000 tok/call at ~8 tok/s) — applied to ALL arms for a controlled
                   ;; non-thinking comparison.
                   (= "false" (some-> (System/getenv "OPENAI_ENABLE_THINKING")
                                      str/trim str/lower-case))
                   (update :chat_template_kwargs (fnil assoc {}) :enable_thinking false)
                   (and effort
                        (not (contains? params :reasoning_effort)))
                   (assoc :reasoning_effort effort)
                   ;; Last step: rename/drop params the target model family
                   ;; rejects (GPT-5 wants `max_completion_tokens`, not
                   ;; `max_tokens`). Runs AFTER the env merge above so an
                   ;; `OPENAI_MAX_TOKENS`-injected cap is normalized too.
                   :always model-params/normalize-request)]
    (:body (http/post (str endpoint "/chat/completions")
                      (cond-> {:content-type :json
                               :body (json/encode body)
                               :as :json
                               ;; Per-call read timeout. Default 10 min; raise via
                               ;; OPENAI_SOCKET_TIMEOUT_MS for slow reasoning models whose
                               ;; long-reasoning calls exceed it (e.g. Kimi K2.6 at ~10 tok/s
                               ;; on the HPC allocation can need >10 min for a 6k-token call).
                               :socket-timeout (or (some-> (System/getenv "OPENAI_SOCKET_TIMEOUT_MS")
                                                           str/trim parse-long)
                                                   600000)
                               :connection-timeout 60000}
                        api-key (assoc :headers {"Authorization" (str "Bearer " api-key)}))))))

(def ^:private default-max-429-retries
  "Retries (beyond the first attempt) before a 429 is surfaced to the caller.
   `digdir.llm.anthropic` retries 429 forever; this client bounds it — see
   `with-429-retry`. Override with `OPENAI_MAX_RETRIES` (0 disables retrying)."
  3)

(def ^:private default-429-delay-ms
  "Wait when the provider sends no usable `Retry-After` — 60s, the same fallback
   `digdir.llm.anthropic` uses. Override with `OPENAI_RETRY_DELAY_MS`."
  60000)

(defn- sleep-ms!
  "Indirection so tests can pin the backoff without actually waiting."
  [ms]
  (Thread/sleep (long ms)))

(defn- rate-limited?
  "True for the HTTP 429 both branches raise: clj-http and wkok (openai and
   azure impls alike) throw `ExceptionInfo` carrying `:status` — verified
   against a local server returning 429."
  [e]
  (= 429 (:status (ex-data e))))

(defn- retry-after-ms
  "How long to wait before retrying `e`, honouring the provider's `Retry-After`
   (in seconds) like the Anthropic client does, else `default-429-delay-ms`.

   Header lookup is case-tolerant: clj-http's own header map is case-insensitive,
   but an exception constructed elsewhere may carry a plain map. `Retry-After`
   may also be an HTTP date rather than a number, which `parse-long` rejects —
   that falls back to the default delay rather than failing the call."
  [e]
  (let [headers (:headers (ex-data e))
        raw (or (get headers "retry-after")
                (get headers "Retry-After")
                (get headers :retry-after))
        seconds (some-> raw str str/trim parse-long)]
    (if (and seconds (pos? seconds))
      (* 1000 seconds)
      (or (env-num "OPENAI_RETRY_DELAY_MS") default-429-delay-ms))))

;; =============================================================================
;; Usage collection (#25)
;; =============================================================================

(def ^:dynamic *usage-writes*
  "When bound to an atom, every completion this namespace returns appends its
   `:usage` here.

   Why an ambient binding rather than a return value: the caller that records
   the stage timing sits several frames above the call, and threading usage out
   through each skill's `:outputs` would change four production result
   contracts to obtain the same information.

   Why a VECTOR rather than a single value: a double-write is then visible
   (count > 1 on a single-call stage) instead of silently overwriting. Today no
   retry can double-write, because every retry here is exception-driven and a
   throwing attempt returns no response — but that guarantee is CONDITIONAL. A
   retry on a successful-but-unsatisfactory response (retrying the nil-parse at
   query_planner.clj:688 is an obvious future improvement) would break it
   silently, and in the reassuring direction: llm-ms too big, other-ms too
   small, looking exactly like this fix having worked. Counting costs nothing
   now and is unavailable later.

   The binding is thread-local. It survives `future` (Clojure conveys bindings)
   but NOT a raw `Thread.` or a bare executor — see `capture-usage`'s contract
   and the `::unbound` sentinel at the recorder."
  nil)

(defn- record-usage!
  "Append `resp`'s usage to the ambient collector, if one is bound. Returns
   `resp` unchanged so this can wrap a call site transparently."
  [resp]
  (when-some [sink *usage-writes*]
    (swap! sink conj (:usage resp)))
  resp)

(defn capture-usage
  "Run `f` with a fresh usage collector.

   Returns `{:result <f's value> :usages [...] :usage-writes n}`, where
   `:usages` holds one entry per completion returned inside `f` — `nil`
   included, so a provider that sent no usage is distinguishable from a call
   that never happened. `:usage-writes` is the count, which is what makes a
   double-write detectable."
  [f]
  (let [sink (atom [])
        result (binding [*usage-writes* sink] (f))
        usages @sink]
    {:result result :usages usages :usage-writes (count usages)}))

(defn sum-usage
  "Sum a collection of OpenAI `:usage` maps into one. Returns nil when there is
   nothing to sum, so `no usage` stays distinguishable from `zero tokens`."
  [usages]
  (let [present (remove nil? usages)]
    (when (seq present)
      (reduce (fn [acc u]
                (-> acc
                    (update :prompt_tokens + (or (:prompt_tokens u) 0))
                    (update :completion_tokens + (or (:completion_tokens u) 0))
                    (update :total_tokens + (or (:total_tokens u) 0))))
              {:prompt_tokens 0 :completion_tokens 0 :total_tokens 0}
              present))))

(defn usage-summary
  "Stage-timing fields describing what a `capture-usage` span reported.

   `:usage-writes` is always present — 0 is a real answer, and it is the one
   that says the collector did not reach. `:usage` appears only when at least
   one completion reported it, so `no usage` stays distinguishable from
   `zero tokens`. Callers add `:usage-expected? true` when the span is known to
   call an LLM; that pair is what turns a missing `:usage` into a visible
   inconsistency rather than a silent reclassification into other-ms."
  [{:keys [usages usage-writes]}]
  (cond-> {:usage-writes (or usage-writes 0)}
    (seq (remove nil? usages)) (assoc :usage (sum-usage usages))))

(defn- with-429-retry
  "Call `f`, retrying HTTP 429 with backoff. `ctx` is merged into the rate-limit
   event and into the error raised once retries are exhausted.

   Mirrors `digdir.llm.anthropic`: honour `Retry-After`, else wait 60s, and log
   the rate limit. It deviates in ONE respect — Anthropic loops forever, which
   cannot satisfy \"exhausted retries produce a clear, attributable error\" and
   would hang every skill behind a rate-limited provider, since this is the
   chokepoint all of them share. So the retries are bounded and exhaustion
   throws, with the provider's own exception kept as the cause.

   Anything that is not a 429 propagates untouched, on the first attempt."
  [ctx f]
  (let [max-retries (long (or (env-num "OPENAI_MAX_RETRIES") default-max-429-retries))]
    (loop [attempt 1]
      (let [outcome (try
                      {:value (f)}
                      (catch clojure.lang.ExceptionInfo e
                        (if (rate-limited? e)
                          {:rate-limited e}
                          (throw e))))]
        (if (contains? outcome :value)
          (:value outcome)
          (let [e (:rate-limited outcome)]
            (if (> attempt max-retries)
              (throw (ex-info (str "LLM rate limited (HTTP 429): giving up after "
                                   attempt " attempt(s)")
                              (assoc ctx :status 429 :attempts attempt :max-retries max-retries)
                              e))
              (let [delay-ms (retry-after-ms e)]
                (t/event! :llm-client/ratelimit
                          {:data (assoc ctx :attempt attempt :delay-ms delay-ms)})
                (sleep-ms! delay-ms)
                (recur (inc attempt))))))))))

(defn create-chat-completion
  "Faithful, non-streaming drop-in for wkok's `create-chat-completion`.
   `:impl :azure` in opts → delegate to wkok (cloud path unchanged). Otherwise →
   clj-http POST with the request body passed through verbatim.

   Both branches retry HTTP 429 with backoff — see `with-429-retry`."
  ([params] (create-chat-completion params nil))
  ([params opts]
   ;; record-usage! wraps the RESULT of the retry loop, not each attempt: a
   ;; retried attempt threw, so it returned no response and has no usage.
   (record-usage!
     (with-429-retry
       {:model (:model params) :impl (or (:impl opts) :openai)}
       (fn []
         (if (= :azure (:impl opts))
           (wkok/create-chat-completion (model-params/normalize-request params) opts)
           (openai-compat-completion params opts)))))))

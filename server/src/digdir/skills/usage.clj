(ns digdir.skills.usage
  "Per-LLM-call token usage from an `invoke-rag` result.

   ## Why this namespace exists

   Three call sites needed the same two facts — *where does the agent put its
   stage timings* and *what did they cost* — and each had its own copy:

   | Where                                        | What it had                |
   |----------------------------------------------|----------------------------|
   | `digdir.api.routes.endpoints.openai-compat`  | `stage-timings`, `agent-usage` |
   | `digdir.sweep.runner` (src-dev)              | `find-stage-timings`       |
   | Langfuse tracing (#new)                      | would have been a third    |

   `openai-compat`'s own comment already named the problem: its
   `stage-timings` *\"Mirrors `digdir.sweep.runner/find-stage-timings`, which
   lives in src-dev and so is not on a production classpath.\"* A mirror is a
   copy that nothing checks, and this repository has been bitten twice by
   exactly that — #497 (two functions computing one collection name) and #500
   (two reads of one switch with OPPOSITE defaults, where the verifier
   disagreed with the runtime and turned \"I checked\" into false confidence).

   So the read lives here, once, on the production classpath, and the callers
   delegate. Same move #500 made when it moved the config read into `src`.

   ## The probe order is not arbitrary

   The agent surfaces stage timings in a different place depending on
   skill-graph topology, and the order below is the one `openai-compat` has
   been running in production. It is preserved verbatim rather than tidied:
   the list is empirical, and reordering it silently changes which topology
   wins."
  (:require [taoensso.telemere :as t]))

(defn stage-timings
  "Per-LLM-call stage timings from an `invoke-rag` result, or nil.

   Probes the five shapes the agent is known to use. Returns nil rather than
   an empty collection so callers can distinguish \"no timings\" from
   \"timings that reported nothing\"."
  [result]
  (or (not-empty (get-in result [:diagnostics :stage-timings]))
      (not-empty (get-in result [:diagnostics :outputs :trace :stage-timings]))
      (not-empty (get-in result [:diagnostics :outputs :workspace-final :stage-timings]))
      (not-empty (get-in result [:trace :stage-timings]))
      (not-empty (:stage-timings result))))

(defn token-count
  "Read a usage number under either casing — providers return snake_case,
   internal code sometimes normalises to kebab."
  [usage snake kebab]
  (or (get usage snake) (get usage kebab) 0))

(defn- reported?
  "True when this usage map carries a token count at all, under either casing."
  [u]
  (or (contains? u :prompt_tokens) (contains? u :prompt-tokens)
      (contains? u :completion_tokens) (contains? u :completion-tokens)))

(defn agent-usage
  "Token usage summed across the agent's LLM calls, or **nil** when the agent
   reported none.

   nil means the caller omits usage entirely. Reporting zeros instead would be
   a fabricated value that a cost-tracking client believes and acts on — `0`
   and `unknown` are not the same claim. Only emitted when at least one call
   actually carried a token count.

   Returned in OpenAI's snake_case shape because that is what its original
   caller renders directly into a `chat.completion` response."
  [result]
  (when-let [timings (stage-timings result)]
    (let [usages (keep :usage timings)]
      (when (some reported? usages)
        {:prompt_tokens (reduce + 0 (map #(token-count % :prompt_tokens :prompt-tokens) usages))
         :completion_tokens (reduce + 0 (map #(token-count % :completion_tokens :completion-tokens) usages))
         :total_tokens (reduce + 0 (map (fn [u]
                                          (or (get u :total_tokens)
                                              (get u :total-tokens)
                                              (+ (token-count u :prompt_tokens :prompt-tokens)
                                                 (token-count u :completion_tokens :completion-tokens))))
                                        usages))}))))

(defn call-usage
  "Usage for ONE stage-timing entry, in Langfuse's `usage_details` shape, or
   nil when that call reported nothing.

   Distinct from `agent-usage`, which sums the whole run: a tracing backend
   wants the per-call breakdown so a single expensive iteration is visible
   rather than averaged away."
  [timing]
  (let [u (:usage timing)]
    (when (and u (reported? u))
      {:input (token-count u :prompt_tokens :prompt-tokens)
       :output (token-count u :completion_tokens :completion-tokens)
       :total (or (get u :total_tokens)
                  (get u :total-tokens)
                  (+ (token-count u :prompt_tokens :prompt-tokens)
                     (token-count u :completion_tokens :completion-tokens)))})))

(defn timing-model
  "The model ONE LLM call actually ran against, or nil.

   The key is `:llm-model`, and it is neither `:model` nor `:model-name`:
   `normalize-stage-timing-entry` in `digdir.skills.builtin.agent.workspace` is
   an explicit ALLOWLIST — its own docstring warns that \"a key not named here
   is dropped silently\" — and `:llm-model` is the name on it.

   This is the **served** model, not the requested one. Every writer sets it as
   `(:model response)`, i.e. what the provider echoed back
   (`agent/iteration_bundled.clj:90`, `iteration_faithful.clj:80`,
   `agent/loop.clj:893`), or the resolved sub-skill model for a delegated call
   (`loop.clj:681`, `:749`). `digdir.sweep.runner` keeps the same distinction
   explicitly, as `:llm-model-requested` vs `:llm-model-served`.

   Legitimately nil: every writer guards on the response carrying a model, so a
   provider that returns none leaves the key off. Callers should fall back to
   the tenant's configured model rather than treat nil as an error — see
   `digdir.telemetry.langfuse/resolved-model`."
  [timing]
  (:llm-model timing))

(comment
  ;; Shape check against a live result, from a REPL with one to hand:
  (let [r {:diagnostics {:stage-timings [{:stage :llm-and-tools
                                          :usage {:prompt_tokens 796
                                                  :completion_tokens 41}}]}}]
    (t/log! :info [:usage/probe {:summed (agent-usage r)
                                 :per-call (map call-usage (stage-timings r))}])))

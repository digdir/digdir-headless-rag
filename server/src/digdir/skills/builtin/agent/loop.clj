(ns digdir.skills.builtin.agent.loop
  "Agentic loop - ReAct-style state machine and LLM orchestration."
  (:require [digdir.skills.builtin.agent.streaming :as streaming]
            [digdir.skills.builtin.agent.workspace :as workspace]
            [digdir.skills.builtin.agent.sufficiency :as sufficiency]
            [digdir.skills.builtin.agent.tools :as tools]
            [digdir.skills.builtin.synthesis :as synthesis]
            [digdir.skills.events :as events]
            [digdir.rag.skills.core :as skills]
            [digdir.config.accessor :as cfg]
            [digdir.llm.openai :as llm]
            [digdir.llm.client :as openai]
            [clojure.data.json :as json]
            [clojure.string :as str]))

;; =============================================================================
;; Prompts & Messages
;; =============================================================================

(def default-system-prompt
  ;; Round-3 OFAT (2026-05-24, sweep-2026-05-24T21-54-33-420874Z)
  ;; replaced the prior ~350-word prompt with this ~85-word version.
  ;; In an 18-question × 3-repeat sweep on `phrase-top20`, the minimal
  ;; variant matched the verbose baseline on recall@10 (20.4% vs 22.2%,
  ;; within SE) and beat it on hit-rate (+3.7pp), citation-recall
  ;; (+1.8pp), wall-clock (-7%), and prompt-tokens-per-call (-9%).
  ;; The dropped material — CRITICAL RULES, SMALL-DOC heuristic,
  ;; persistence guidance, iteration-budget text — wasn't moving recall
  ;; on top of what tool descriptions + sufficiency-gate feedback
  ;; already convey. See server/results/sweep-2026-05-24T21-54-33-420874Z/REPORT.md.
  "You are a research assistant with access to a document knowledge base. Answer the user's question accurately.

Always include your reasoning in your message text before making tool calls. Explain what you're about to do and why.

Workflow — repeat as needed:
1. SEARCH: Use search with diverse queries. Returns metadata only — no content.
2. READ: Use read_chunks to fetch full content of promising results.
3. RERANK: Use rerank_results to surface the most relevant chunks in your workspace.
4. GENERATE: Use generate_response to produce an answer.

After each tool call, the system runs a sufficiency gate. If it rejects, follow the hint; if it passes, finalize.")

(defn normalize-chat-role
  "Normalize supported role encodings to OpenAI chat role strings."
  [role]
  (let [r (cond
            (keyword? role) (name role)
            (string? role) (str/lower-case role)
            :else nil)]
    (when (#{"user" "assistant"} r) r)))

(defn build-initial-messages
  "Build initial chat messages for the agent loop.
   Includes prior conversation turns when provided, and appends current query.
   If history already ends with the current user query, avoid duplicating it."
  [system-prompt query conversation-history]
  (let [query-text (str/trim (or query ""))
        history-messages
        (->> (or conversation-history [])
             (keep (fn [m]
                     (let [role (normalize-chat-role (or (:role m) (:message/role m)))
                           content (str/trim (or (:text m) (:message/text m) ""))]
                       (when (and role (not (str/blank? content)))
                         {:role role :content content}))))
             vec)
        history-without-current
        (let [last-msg (last history-messages)]
          (if (and (= "user" (:role last-msg))
                   (= query-text (:content last-msg)))
            (pop history-messages)
            history-messages))
        system-msg {:role "system" :content (or system-prompt default-system-prompt)}
        user-msg {:role "user" :content query-text}]
    (into [system-msg] (conj history-without-current user-msg))))

;; =============================================================================
;; LLM Orchestration
;; =============================================================================

(defn- llm-opts
  "Build the wkok options map for Azure when configured; nil otherwise."
  [tenant]
  (when (llm/use-azure-openai tenant)
    {:api-key (cfg/get {:tenant tenant} :services :azure-openai :api-key)
     :api-endpoint (cfg/get {:tenant tenant} :services :azure-openai :api-endpoint)
     :impl :azure}))

(defn- stream-call!
  "Streaming branch of call-llm. Pipes per-token content deltas through a
   paragraph-or-timeout chunker, emitting :response/chunk events for each
   coherent unit. Returns the assembled OpenAI response in the same shape
   as the blocking path."
  [tenant params progress-fn]
  (let [emit-chunk (fn [chunk]
                     (events/emit-progress! progress-fn
                                            (events/response-chunk chunk)))
        chunker (streaming/make-chunker {:on-chunk emit-chunk})
        wkok-opts (llm-opts tenant)
        result (if wkok-opts
                 (llm/streaming-chat-completion
                   params
                   (assoc wkok-opts :on-content-delta (:on-delta chunker)))
                 (llm/streaming-chat-completion
                   params
                   {:on-content-delta (:on-delta chunker)}))]
    ((:close chunker))
    result))

(defn call-llm
  "Call the LLM with messages and optional tools.

   Uses wkok.openai-clojure.api directly (not litellm) because the
   multi-turn tool-use loop requires passing assistant messages with
   :tool_calls back in subsequent requests — litellm's transform-messages
   drops that key.

   When `progress-fn` is supplied via the 6-arity opts map, the call
   streams content deltas through `digdir.skills.builtin.agent.streaming`'s
   paragraph-or-250ms chunker and emits :response/chunk events as
   each coherent unit lands. Both arities return the same response
   shape: `{:choices [{:message {...} :finish_reason \"...\"}]}`.

   Returns the raw OpenAI API response."
  ([tenant messages tools model temperature]
   (call-llm tenant messages tools model temperature nil))
  ([tenant messages tools model temperature {:keys [progress-fn]}]
   (let [selected-model (or model
                            (if (llm/use-azure-openai tenant)
                              (cfg/get {:tenant tenant} :services :azure-openai :deployment-name)
                              (cfg/get {:tenant tenant} :services :azure-openai :model-name)))
         params (cond-> {:model selected-model
                         :messages messages
                         :temperature (or temperature 0.3)}
                  (seq tools) (assoc :tools tools))]
     (cond
       progress-fn
       (stream-call! tenant params progress-fn)

       (llm/use-azure-openai tenant)
       (openai/create-chat-completion params (llm-opts tenant))

       :else
       (openai/create-chat-completion params)))))

(defn summarize-llm-exception
  "Build a compact error string from an LLM exception."
  [e iteration]
  (let [msg (or (.getMessage e) "Unknown LLM error")
        status (or (get-in (ex-data e) [:error :status])
                   (get-in (ex-data e) [:status]))]
    (str "LLM request failed at iteration " iteration
         (when status (str " (status " status ")"))
         ": " msg)))

(defn truncate-text
  [s max-chars]
  (let [s (str (or s ""))]
    (if (> (count s) max-chars)
      (str (subs s 0 max-chars) "...")
      s)))

(defn build-fallback-messages
  "Build a compact, text-only message window for final best-effort answer generation."
  [messages fallback-msg]
  (let [max-system-chars 3000
        max-turn-content-chars 2200
        max-turn-messages 12
        system-msg (some #(when (= "system" (:role %)) %) messages)
        compact-turns (->> messages
                           (filter #(contains? #{"user" "assistant"} (:role %)))
                           (keep (fn [m]
                                   (let [content (truncate-text (:content m) max-turn-content-chars)]
                                     (when-not (str/blank? content)
                                       {:role (:role m)
                                        :content content}))))
                           (take-last max-turn-messages)
                           vec)
        compact-fallback (update fallback-msg :content truncate-text 1200)]
    (cond-> []
      system-msg (conj {:role "system"
                        :content (truncate-text (:content system-msg) max-system-chars)})
      true (into compact-turns)
      true (conj compact-fallback))))

(defn stored-workspace-response
  "Return the latest generated response when one exists, even if it was marked insufficient."
  [workspace]
  (some-> (:last-generated-response workspace) str not-empty))

(defn usable-workspace-response
  "Return the most recent generated response when it was not marked insufficient."
  [workspace]
  (let [response (stored-workspace-response workspace)]
    (when (and (some? response)
               (not (:last-generate-insufficient-context workspace)))
      response)))

(def ^:private max-sufficiency-rejections 5)

;; ---------------------------------------------------------------------------
;; No-tool-call scaffolding guard
;;
;; The local Qwen sometimes writes its SEARCH step as PROSE / pseudo-code
;; (`[SEARCH]`, `SEARCH(...)`, `<tool_code>print(SEARCH(...))`, a numbered
;; `1. SEARCH` workflow echo) instead of emitting an actual tool call. The loop
;; then finalizes on that text with no search ever having run — the scaffolding
;; leaks out as the "answer" (judged incorrect). Observed on the full-42 confirm:
;; 17/126 control + 9/126 snippet-AB runs never searched (e.g. authz-03 3/3 →
;; that question's whole regression). This guard re-prompts for a real tool call
;; instead of finalizing on the scaffolding. Orthogonal to the snippet work;
;; helps every config.
;; ---------------------------------------------------------------------------

(def ^:const max-no-tool-call-retries
  "Re-prompts allowed for a no-tool-call 'search as prose' turn before giving up
   and finalizing honestly. Bounds wasted iterations when the model never emits
   a real tool call (the outer loop's max-iterations is the hard backstop)."
  2)

(def no-tool-call-nudge
  "Corrective system message appended to a no-tool-call turn that never searched."
  (str "You produced a response WITHOUT searching the knowledge base. You must answer "
       "ONLY from retrieved documents, never from prior knowledge — so you MUST search "
       "FIRST. Emit an actual search_documents tool call now; do not answer from memory "
       "and do not write the query as prose, pseudo-code, a `[SEARCH]` block, or "
       "`<tool_code>`."))

(def no-tool-call-honest-miss
  "Replacement answer when the model only ever produced search scaffolding."
  "Could not retrieve information from the knowledge base to answer this question.")

(defn search-scaffold-response?
  "True when `text` looks like the model wrote its SEARCH step as prose or
   pseudo-code instead of emitting a real tool call (`[SEARCH]`, `SEARCH(...)`,
   `SEARCH queries=`, `<tool_code>`, a numbered `1. SEARCH` workflow echo,
   `print(SEARCH(...))`, a `Query alternatives:` plan, or Norwegian `La meg søke`).
   NO LONGER the re-prompt trigger (that is now shape-agnostic — see
   `no-tool-call-decision`); used only to decide whether an EXHAUSTED never-searched
   terminal is leaking scaffolding (→ honest miss) vs a real unsourced answer
   (→ pass through). Broadened to cover the shapes the local model actually emits."
  [text]
  (boolean
    (when (string? text)
      (re-find #"(?im)^\s*(\[SEARCH\]|SEARCH\s*[\(:]|SEARCH\s+quer|<tool_code>|\d+\.\s+SEARCH\b|Query alternatives|print\s*\(\s*SEARCH|La meg søke)"
               text))))

(defn no-tool-call-decision
  "Decide how to handle a no-tool-call (text-only) LLM turn. `enabled?` gates the
   guard (off → always finalize as before); `searched?` = has any search run this
   invocation; `retries` = prior re-route count this invocation; `direct-response`
   = the model's text.

   SHAPE-AGNOSTIC trigger: a no-tool-call terminal where NO search ever ran is a
   policy violation (the prompt mandates search-first), whether the text is search
   scaffolding (`SEARCH queries=…`, `La meg søke…`) OR an answer hallucinated from
   prior knowledge. So we re-prompt to force a real search regardless of shape —
   this is the load-bearing fix (the old regex trigger missed most real scaffolds
   AND never caught hallucinate-without-search; verified inert on the offender set).

   Returns {:action :retry :nudge <str>} while retry budget remains; else
   {:action :finalize :response <str>} — substituting an honest miss only for an
   EXHAUSTED terminal still leaking obvious scaffolding (cosmetic, regex-gated),
   otherwise passing the text through (a genuine post-search answer, the model's
   best-effort unsourced answer after exhausted retries, or the guard disabled)."
  [{:keys [enabled? searched? direct-response retries]}]
  (let [never-searched? (and enabled? (not searched?))]
    (cond
      (and never-searched? (< (or retries 0) max-no-tool-call-retries))
      {:action :retry :nudge no-tool-call-nudge}

      (and never-searched? (search-scaffold-response? direct-response))
      {:action :finalize :response no-tool-call-honest-miss}

      :else
      {:action :finalize :response (or direct-response "")})))

(defn active-query
  [messages]
  (some->> messages
           reverse
           (some (fn [message]
                   (when (= "user" (:role message))
                     (not-empty (str/trim (or (:content message) ""))))))))

(defn tools-for-phase
  [ambient-ctx phase]
  (case phase
    :finalize (->> (tools/agent-tool-definitions ambient-ctx)
                   (filter #(= "generate_response" (get-in % [:function :name])))
                   vec)
    :clarify nil
    (tools/agent-tool-definitions ambient-ctx)))

(defn gate-relevant-tool?
  [tool-name]
  (contains? #{"read_chunks" "rerank_results" "generate_response"}
             tool-name))

(declare enrichment-mode-agent?)

(defn should-run-sufficiency-gate?
  "True when at least one gate-relevant tool ran AND the active agent
   isn't in enrichment-mode. The latter check is what suppresses the
   sufficiency machinery for agents whose loops drive structured
   propose/apply/eval tools rather than Q&A finalization.

   Two arities so older call sites without ambient-ctx still work
   (they default to the old behavior — gate runs whenever
   tool-call-results contains a gate-relevant tool)."
  ([tool-call-results]
   (should-run-sufficiency-gate? tool-call-results nil))
  ([tool-call-results ambient-ctx]
   (boolean
    (and (not (enrichment-mode-agent? ambient-ctx))
         (some (comp gate-relevant-tool? :tool) tool-call-results)))))

(def ^:dynamic *enrichment-mode-agent-ids*
  "Agents whose ReAct loops are driven by enrichment / pipeline-control
   tools rather than Q&A finalization. Their goal is to call structured
   propose / apply / eval tools — they should NOT trigger the
   sufficiency-driven `generate_response` shortcut just because they
   happened to call `read_chunks` during corpus exploration.

   Empty after Phase 0 — the only ReAct enrichment agent
   (digdir.demo/self-improve-agent) was retired alongside the rest of
   the per-demo agents; its graph-structured replacements run under
   :builtin/docs-agent and don't use the ReAct loop. Dynamic so tests
   can rebind. If a future ReAct enrichment-style agent lands, promote
   this to a skill-param or a property on the agent record."
  #{})

(defn enrichment-mode-agent?
  "True when the current agent should skip the sufficiency-driven
   `generate_response` shortcut. Reads `:agent-id` from ambient-ctx
   (also checks the `:opts` sub-map, since the agent skill stuffs
   skill-params under there)."
  [ambient-ctx]
  (let [aid (or (:agent-id ambient-ctx)
                (get-in ambient-ctx [:opts :agent-id])
                (get-in ambient-ctx [:opts :skill-params :agent-id]))]
    (contains? *enrichment-mode-agent-ids* aid)))

(def ^:private response-validation-answer-types
  #{:comparison :explanation})

(defn last-shadow-read-decision
  [workspace]
  (last (or (:shadow-sufficiency-decisions workspace) [])))

(defn confident-read-signal-finalize?
  "True when aggregated read signals and the latest local evaluator agree on a
   decisive, well-scoped finalize. `aggregate-read-signals` always tags its
   output with :source :read-signals, so that tag is not checked here."
  [workspace]
  (let [shadow-decision (last-shadow-read-decision workspace)
        last-read-signal (:last-read-signal workspace)]
    (and (= :sufficient (:status shadow-decision))
         (= :support-found (:status last-read-signal))
         (= :aligned (:scope-assessment last-read-signal))
         (not (:degraded? last-read-signal))
         (contains? #{:llm :degraded-fallback} (:evaluation-mode last-read-signal))
         (empty? (or (:open-evidence-gaps workspace) []))
         (empty? (or (:evidence-contradictions workspace) [])))))

(defn should-run-response-validation?
  "Skip post-generate response validation only when we have decisive read-signal
   support for the finalize and the query is narrow enough that synthesis is
   unlikely to drift. Broad answer types (:comparison, :explanation) always
   validate because synthesis routinely introduces unsupported claims there.

   The other disjuncts in the earlier form (degraded?, scope ambiguous/wrong,
   open gaps, contradictions) are strictly implied by
   `(not confident-read-signal-finalize?)` and have been dropped."
  [workspace]
  (let [answer-type (get-in workspace [:last-query-intent :answer-type])]
    (or (contains? response-validation-answer-types answer-type)
        (not (confident-read-signal-finalize? workspace)))))

(defn count-sufficiency-rejections
  [workspace]
  (->> (concat (or (:sufficiency-decisions workspace) [])
               (or (:response-validations workspace) []))
       (remove #(= :sufficient (:status %)))
       count))

(defn shadow-gate-decision
  [workspace]
  (when-let [decision (last (or (:shadow-sufficiency-decisions workspace) []))]
    (when (contains? #{:sufficient :insufficient} (:status decision))
      {:source :shadow-read-signals
       :status (:status decision)
       :reasoning (str "Aggregated read signals: "
                       (name (or (:reason-code decision) :unknown)))
       :missing-info (mapv name (or (:missing-claims decision) []))
       :contradiction-detected? (boolean (seq (:contradictions decision)))
       :suggested-strategy (:suggested-strategy decision)
       :message "Shadow read-signal aggregation"})))

(defn response-validation-hint
  [query decision evidence-summary]
  (case (:suggested-strategy decision)
    :read-more
    (let [missing-info (or (:missing-info decision) [])
          chunk-ids (or (:unread-chunk-ids evidence-summary) [])
          range-hint (:unread-range evidence-summary)]
      (str "[SYSTEM: Response validation rejected the draft answer. "
           (:reasoning decision)
           (when (seq missing-info)
             (str " Missing info: " (str/join "; " missing-info) "."))
           " Read more from the current result set before generating again."
           (when (seq chunk-ids)
             (str " Suggested next call: read_chunks "
                  (json/write-str {:chunk_ids chunk-ids})
                  "."))
           (when range-hint
             (str " Or expand local context with read_chunks "
                  (json/write-str {:doc_num (:doc-num range-hint)
                                   :chunk_range (:chunk-range range-hint)})
                  "."))
           "]"))

    :ask-clarification
    (str "[SYSTEM: Response validation rejected the draft answer because the query is still ambiguous or off-topic. "
         (:reasoning decision)
         (when (seq (:missing-info decision))
           (str " Missing info: " (str/join "; " (:missing-info decision)) "."))
         " Ask the user a concise clarification question instead of generating another answer.]")

    :finalize
    (if (= :sufficient (:status decision))
      (str "[SYSTEM: Response validation passed. "
           (:reasoning decision)
           " Finalize the answer without more search or reading.]")
      (str "[SYSTEM: Response validation found unresolved gaps that cannot be closed within budget. "
           (:reasoning decision)
           (when (seq (:missing-info decision))
             (str " Missing info: " (str/join "; " (:missing-info decision)) "."))
           " Finalize with an explicit uncertainty or conflict explanation.]"))

    :re-search
    (str "[SYSTEM: Response validation rejected the draft answer. "
         (:reasoning decision)
         (when (seq (:missing-info decision))
           (str " Missing info: " (str/join "; " (:missing-info decision)) "."))
         (when-let [latest-queries (seq (:latest-search-queries evidence-summary))]
           (str " Avoid repeating the latest queries verbatim: " (pr-str latest-queries) "."))
         " Start a more targeted search, read the new hits, and then generate again.]")

    (str "[SYSTEM: Response validation rejected the draft answer for query "
         (pr-str query)
         ". "
         (:reasoning decision)
         "]")))

(defn enforce-sufficiency-limit
  "Force :finalize on the last allowed rejection so the next iteration produces
   an answer instead of another rejected tool call. Uses (dec limit) intentionally:
   at N-1 accumulated rejections we pre-empt the Nth, which would otherwise be
   followed by another LLM turn the caller can't usefully route."
  [workspace decision]
  (let [rejections (count-sufficiency-rejections workspace)]
    (if (and (not= :sufficient (:status decision))
             (>= rejections (dec max-sufficiency-rejections)))
      (-> decision
          (assoc :suggested-strategy :finalize)
          (update :reasoning #(str (or % "")
                                   " Reached the sufficiency retry limit; finalize with the best supported uncertainty or conflict explanation.")))
      decision)))

(defn gate-system-hint
  [query decision evidence-summary]
  (case (:suggested-strategy decision)
    :read-more (sufficiency/format-read-more-hint decision evidence-summary)
    :ask-clarification (sufficiency/format-clarification-hint decision)
    :grounding (sufficiency/format-grounding-hint decision)
    :finalize (if (= :sufficient (:status decision))
                (sufficiency/format-finalize-hint decision)
                (sufficiency/format-finalize-with-uncertainty-hint decision))
    :re-search
    (str "[SYSTEM: Sufficiency gate rejected the current evidence. "
         (:reasoning decision)
         (when (seq (:missing-info decision))
           (str " Missing info: " (str/join "; " (:missing-info decision)) "."))
         (when-let [latest-queries (seq (:latest-search-queries evidence-summary))]
           (str " Avoid repeating the latest queries verbatim: " (pr-str latest-queries) "."))
         " Start a more targeted search and then read the new hits before finalizing.]")
    (str "[SYSTEM: Sufficiency gate rejected the current evidence for query "
         (pr-str query)
         ". "
         (:reasoning decision)
         "]")))

(defn last-tool-result
  [tool-call-results tool-name]
  (some #(when (= tool-name (:tool %)) %) tool-call-results))

(defn generated-response-needs-retry?
  [workspace tool-call-results]
  (let [generate-result (last-tool-result tool-call-results "generate_response")
        response (or (:last-generated-response workspace)
                     (:result-summary generate-result))]
    (or (str/blank? response)
        (synthesis/insufficient-context-response? response))))

(defn clarification-request
  [decision]
  (let [question (or (:clarification-question decision)
                     (when-let [missing (seq (:missing-info decision))]
                       (str "I need one clarification before I continue: "
                            (first missing)))
                     (:reasoning decision)
                     "Can you clarify what you mean?")
        options (some->> (:options decision)
                         (keep not-empty)
                         vec
                         not-empty)
        context-summary (or (:context-summary decision)
                            (:reasoning decision)
                            "The current request is still ambiguous.")]
    (cond-> {:question question
             :context-summary context-summary}
      options (assoc :options options))))

(defn execute-tool-call-inline
  "Fallback dispatch when the :builtin/agent-tool-call skill isn't currently
   in the registry (e.g. after another test's `clear-registry!` wiped it).
   Preserves the legacy atom-based call so tests' `with-redefs` of
   `tools/execute-tool-call` continue to intercept this path. Wraps a local
   atom around the value-shape so the agent loop can stay pure."
  [tool-name args workspace-in ambient-ctx iteration]
  (let [!ws (atom workspace-in)
        raw (try
              (tools/execute-tool-call tool-name args !ws ambient-ctx iteration)
              (catch clojure.lang.ArityException _
                (let [start-time (System/currentTimeMillis)
                      result-text (tools/execute-tool-call tool-name args !ws ambient-ctx)
                      duration-ms (- (System/currentTimeMillis) start-time)
                      {:keys [stage sub-skill]} (tools/tool-stage-info tool-name)]
                  {:result-text result-text
                   :duration-ms duration-ms
                   :iteration iteration
                   :stage stage
                   :tool tool-name
                   :sub-skill sub-skill})))
        envelope (if (map? raw)
                   raw
                   ;; Test stubs (and the legacy 4-arity path) sometimes
                   ;; return a bare string. Wrap so the downstream pipeline,
                   ;; which expects a timing-envelope map, doesn't blow up.
                   {:result-text (str raw)
                    :duration-ms 0
                    :iteration iteration
                    :stage nil
                    :tool tool-name
                    :sub-skill nil})]
    (assoc envelope :workspace-out @!ws)))

(defn execute-tool-call-with-timing
  "Pure-shape dispatch: send the per-tool call through the :builtin/agent-tool-call
   skill in `:workspace-in` mode, returning the timing envelope plus
   `:workspace-out`. Falls back to `execute-tool-call-inline` when the skill
   isn't registered."
  [tool-name args workspace-in ambient-ctx iteration]
  (if-not (skills/get-skill :builtin/agent-tool-call)
    (execute-tool-call-inline tool-name args workspace-in ambient-ctx iteration)
    (let [result (skills/execute-skill
                  :builtin/agent-tool-call
                  {:inputs {:tool-name tool-name
                            :args args
                            :iteration iteration}
                   :parameters {}
                   :services {}
                   :skill-params {}
                   :workspace-in workspace-in
                   :ambient-ctx ambient-ctx})]
      (if (skills/result-success? result)
        (skills/get-result-outputs result)
        ;; Skill execution failed before reaching the inner execute-tool-call.
        ;; Surface a result-shaped map so the agent loop's downstream code path
        ;; doesn't need a special case. Preserve the pre-call workspace so
        ;; the loop's thread isn't broken.
        {:result-text (str "Error: " (or (get-in result [:error :error-message])
                                         "skill dispatch failed"))
         :duration-ms 0
         :iteration iteration
         :stage :tool-dispatch-error
         :tool tool-name
         :sub-skill nil
         :workspace-out workspace-in}))))

;; =============================================================================
;; Agentic Loop
;; =============================================================================

(defn enriched-tool-call-trace-entry
  "Canonical shape for a tool call stored in iteration-history and streamed in
   agent-turn-completed events. Keeps both surfaces in sync so live and post-run
   renderings agree on :ok?, :args-summary, etc."
  [tcr]
  {:tool (:tool tcr)
   :args (:args tcr)
   :args-summary (pr-str (select-keys (:args tcr) [:queries :query :chunk_ids :doc_num]))
   :effective-parameters (:effective-parameters tcr)
   :stage (:stage tcr)
   :sub-skill (:sub-skill tcr)
   :duration-ms (:duration-ms tcr)
   :ok? (if (contains? tcr :ok?)
          (:ok? tcr)
          (not (:error (:args tcr))))
   :result-summary (:result-summary tcr)})

(defn truncate-result-summary-for-stream
  "Trim :result-summary to keep event payloads small. Iteration-history keeps
   the full text; only the streamed event is truncated."
  [entry]
  (update entry :result-summary
          (fn [s]
            (let [s (str s)]
              (if (> (count s) 200) (subs s 0 200) s)))))

(defn synthesize-and-finalize
  "Pure-shape: call the synthesis sub-skill directly and return a finalized
   agent result. Takes workspace as a value, returns it under `:workspace`
   in the result map. Used to skip redundant LLM reasoning turns when
   sufficiency is already known."
  [query-text workspace ambient-ctx progress-fn iteration]
  (events/emit-progress! progress-fn
                         (events/agent-tool-call iteration "generate_response" {:query query-text}))
  (let [start-time (System/currentTimeMillis)
        workspace (dissoc workspace :last-sub-skill-model)
        {:keys [result-text ok? workspace-after usage-fields]}
        (try
          (let [envelope (tools/execute-tool-call-pure "generate_response"
                                                       {:query query-text}
                                                       workspace ambient-ctx iteration)
                text (:result-text envelope)]
            {:result-text text
             :ok? (not (str/blank? text))
             :workspace-after (:workspace-out envelope)
             ;; #25: the envelope DOES carry usage — execute-tool-call-pure
             ;; captures it — and this destructuring was discarding it before
             ;; the hand-built record map below could copy it.
             :usage-fields (select-keys envelope
                                        [:usage :usage-writes :usage-expected?])})
          (catch Exception e
            {:result-text (str "Error: " (.getMessage e))
             :ok? false
             :workspace-after workspace}))
        workspace workspace-after
        duration-ms (- (System/currentTimeMillis) start-time)
        sub-skill-model (:last-sub-skill-model workspace)
        reasoning "Sufficiency reached. Synthesizing final answer."
        trace-entry (enriched-tool-call-trace-entry
                     {:tool "generate_response"
                      :args {:query query-text}
                      :ok? ok?
                      :result-summary result-text
                      :duration-ms duration-ms
                      :stage :generate_response
                      :sub-skill :builtin/synthesis})
        workspace (workspace/record-turn workspace iteration reasoning [trace-entry])
        workspace (workspace/record-stage-timing
                    workspace
                    ;; SECOND hand-built timing map in this file (#25). This is
                    ;; the FORCED-synthesis path — "sufficiency reached", so the
                    ;; agent skips a reasoning turn and synthesises directly —
                    ;; and it is where :generate_response actually comes from on
                    ;; the bundled path. Not the tool dispatch, which is why
                    ;; plan_queries was fixed by the earlier merge and this was
                    ;; not. Rebuilt by hand, so nothing carried over unless
                    ;; named here.
                    (merge
                     usage-fields
                     (cond-> {:stage :generate_response
                              :iteration iteration
                              :tool "generate_response"
                              :sub-skill :builtin/synthesis
                              :duration-ms duration-ms
                              :status (if ok? :ok :error)}
                       sub-skill-model
                       (assoc :llm-model sub-skill-model))))
        iteration-stage-timings (->> (:stage-timings workspace)
                                     (filter #(= iteration (:iteration %)))
                                     vec)
        _ (events/emit-progress! progress-fn
                                 (events/agent-tool-result iteration "generate_response" ok? result-text))
        _ (events/emit-progress! progress-fn
                                 (assoc (events/agent-turn-completed
                                         iteration
                                         reasoning
                                         [(truncate-result-summary-for-stream trace-entry)])
                                        :stage-timings iteration-stage-timings))
        response (usable-workspace-response workspace)]
    (events/emit-progress! progress-fn
                           (events/agent-finalized iteration (count (or response ""))))
    {:response (or response result-text)
     :terminal-state :finalize
     :trace (:iteration-history workspace)
     :workspace workspace}))

(defn dispatch-one-tool-call
  "Pure-shape: dispatch a single tool-call and return [trace-entry, workspace'].
   The trace-entry includes :tool-call-id so the caller can build the tool-result
   message after sufficiency/validation hints have rewritten :result-summary."
  [tool-call workspace ambient-ctx iteration progress-fn]
  (let [fn-name (get-in tool-call [:function :name])
        args-str (get-in tool-call [:function :arguments])
        args (tools/parse-tool-args args-str)
        _ (events/emit-progress! progress-fn
                                 (events/agent-tool-call iteration fn-name args))
        effective-parameters (when-not (:error args)
                               (tools/effective-tool-parameters fn-name args ambient-ctx))
        workspace (dissoc workspace :last-sub-skill-model)
        tool-execution (if (:error args)
                         {:result-text (str "Error: " (:error args))
                          :duration-ms 0
                          :stage :tool-args-error
                          :tool fn-name
                          :workspace-out workspace}
                         (execute-tool-call-with-timing fn-name args workspace ambient-ctx iteration))
        workspace (or (:workspace-out tool-execution) workspace)
        result-text (:result-text tool-execution)
        duration-ms (:duration-ms tool-execution)
        sub-skill-model (:last-sub-skill-model workspace)
        workspace (if (some? duration-ms)
                    (workspace/record-stage-timing
                      workspace
                      ;; #25: carry the usage fields THROUGH from the envelope.
                      ;; This map is rebuilt BY HAND, field by field, so it is
                      ;; an allowlist with no declaration to inspect — worse
                      ;; than the declared kind, because there is nothing to
                      ;; grep for and nothing that looks like a filter. It is
                      ;; where the collector's value was being lost on the
                      ;; bundled path while :usage-expected?, derived at the
                      ;; recorder, correctly reported it missing.
                      (merge
                       (select-keys tool-execution
                                    [:usage :usage-writes :usage-expected?])
                       (cond-> {:stage (:stage tool-execution)
                               :iteration iteration
                               :tool (:tool tool-execution)
                               :sub-skill (:sub-skill tool-execution)
                               :duration-ms duration-ms
                               :status (if (:error args) :error :ok)}
                        effective-parameters
                        (assoc :detail (pr-str (select-keys effective-parameters
                                                            [:sub-skill :primary :dataset-ref :fallback-when-empty])))
                        sub-skill-model
                        (assoc :llm-model sub-skill-model))))
                    workspace)]
    (events/emit-progress! progress-fn
                           (events/agent-tool-result iteration fn-name
                                                     (not (:error args))
                                                     result-text))
    [{:tool fn-name
      :args args
      :effective-parameters effective-parameters
      :result-summary result-text
      :duration-ms duration-ms
      :stage (:stage tool-execution)
      :sub-skill (:sub-skill tool-execution)
      :tool-call-id (:id tool-call)}
     workspace]))

(defn agentic-loop
  "Run the ReAct agentic loop.

   Args:
     initial-messages - Starting messages (system + user)
     !workspace - Workspace atom
     ambient-ctx - Context for sub-skill execution
     opts - Map with :model, :temperature, :max-iterations,
            optional :sufficiency-llm-fn and :sufficiency-llm-temperature

  Returns:
     {:response string, :trace vector} on success
     {:error string, :trace vector} on max iterations exceeded

  Implementation: the external signature keeps the `!workspace` atom for
  backward compatibility with the ~17 call sites. Internally the body
  threads workspace as a plain value through `loop ... recur`; the atom is
  reset! to the final value before each terminal return so callers that
  deref it after see the same state they would have under the legacy
  imperative loop."
  [initial-messages !workspace ambient-ctx opts]
  (let [{:keys [model temperature max-iterations progress-fn
                sufficiency-llm-fn sufficiency-llm-temperature]} opts
        tenant (get-in ambient-ctx [:opts :tenant])
        max-iter (if (some? max-iterations) max-iterations 10)
        sufficiency-llm-fn (if (contains? opts :sufficiency-llm-fn)
                             sufficiency-llm-fn
                             (partial call-llm tenant))
        sufficiency-llm-temperature (if (contains? opts :sufficiency-llm-temperature)
                                      sufficiency-llm-temperature
                                      0.0)
        finalize (fn [workspace result]
                   ;; Persist the final workspace to the legacy atom so
                   ;; external observers that deref `!workspace` after the
                   ;; loop returns see the same state as before this refactor.
                   (reset! !workspace workspace)
                   result)
        result
        (loop [messages initial-messages
               workspace @!workspace
               iteration 0
               phase :default]
          ;; Cooperative cancellation. Streaming MCP clients that drop
          ;; mid-response trigger Future/cancel on the worker thread; we
          ;; check between iterations so the loop bails before the next
          ;; LLM call. In-flight LLM HTTP calls cancel via hato's
          ;; interrupt support; this catches the gap between LLM calls.
          (when (.isInterrupted (Thread/currentThread))
            (throw (InterruptedException. "Agent loop interrupted")))
          (events/emit-progress! progress-fn
                                 (events/agent-iteration-started iteration max-iter))
          (if (>= iteration max-iter)
            (let [fallback-response (usable-workspace-response workspace)
                  stored-response (stored-workspace-response workspace)
                  fallback-msg {:role "user"
                                :content (str "[SYSTEM: You have used all available iterations. "
                                              "Based on everything you have gathered so far, provide your best answer now. "
                                              "If you found partial information, share it. Do not call any more tools.")}
                  fallback-messages (build-fallback-messages messages fallback-msg)]
              (events/emit-progress! progress-fn
                                     (events/agent-exhausted iteration max-iter))
              (if fallback-response
                (do
                  (events/emit-progress! progress-fn
                                         (events/agent-finalized iteration
                                                                 (count fallback-response)))
                  (finalize workspace
                            {:response fallback-response
                             :terminal-state :finalize
                             :trace (:iteration-history workspace)
                             :exhausted true}))
                (try
                  (let [fallback-start (System/currentTimeMillis)
                        final-response (call-llm tenant fallback-messages nil model temperature
                                                  {:progress-fn progress-fn})
                        fallback-duration-ms (- (System/currentTimeMillis) fallback-start)
                        response-text (-> final-response :choices first :message :content)
                        workspace (workspace/record-stage-timing
                                    workspace
                                    ;; #25, twin of graphs.clj's fallback.
                                    (cond-> {:stage :agent-final-llm
                                             :iteration iteration
                                             :duration-ms fallback-duration-ms
                                             :status :ok
                                             :usage-expected? true}
                                      (:usage final-response)
                                      (assoc :usage (:usage final-response))))]
                    (events/emit-progress! progress-fn
                                           (events/agent-finalized iteration
                                                                   (count (or response-text ""))))
                    (finalize workspace
                              {:response (or response-text "Could not determine an answer within the iteration limit.")
                               :terminal-state :finalize
                               :trace (:iteration-history workspace)
                               :exhausted true}))
                  (catch Exception e
                    (if stored-response
                      (do
                        (events/emit-progress! progress-fn
                                               (events/agent-finalized iteration
                                                                       (count stored-response)))
                        (finalize workspace
                                  {:response stored-response
                                   :terminal-state :finalize
                                   :trace (:iteration-history workspace)
                                   :exhausted true}))
                      (do
                        (events/emit-progress! progress-fn
                                               (events/emit-agent-error iteration
                                                                        (summarize-llm-exception e iteration)))
                        (finalize workspace
                                  {:error (summarize-llm-exception e iteration)
                                   :trace (:iteration-history workspace)
                                   :exhausted true})))))))
            (let [llm-start (System/currentTimeMillis)
                  response (try
                             (call-llm tenant messages (tools-for-phase ambient-ctx phase)
                                       model temperature {:progress-fn progress-fn})
                             (catch Exception e
                               {:llm-exception e}))
                  llm-duration-ms (- (System/currentTimeMillis) llm-start)
                  workspace (workspace/record-stage-timing
                              workspace
                              (cond-> {:stage :agent-llm
                                       :iteration iteration
                                       :duration-ms llm-duration-ms
                                       :status (if (:llm-exception response) :error :ok)}
                                (:usage response) (assoc :usage (:usage response))
                                (:model response) (assoc :llm-model (:model response))
                                (not (:llm-exception response))
                                (assoc :finish-reason (get-in response [:choices 0 :finish_reason]))))]
              (if-let [e (:llm-exception response)]
                (do
                  (events/emit-progress! progress-fn
                                         (events/emit-agent-error iteration
                                                                  (summarize-llm-exception e iteration)))
                  (finalize workspace
                            {:error (summarize-llm-exception e iteration)
                             :trace (:iteration-history workspace)}))
                (let [choice (-> response :choices first)
                      message (:message choice)
                      finish-reason (:finish_reason choice)
                      tool-calls (:tool_calls message)
                      _ (when-let [content (not-empty (str/trim (or (:content message) "")))]
                          (events/emit-progress! progress-fn
                                                 (events/agent-thinking iteration content)))]
                  (if (and (seq tool-calls)
                           (or (= finish-reason "tool_calls")
                               (= finish-reason "stop")))
                    (let [workspace (assoc workspace :current-iteration iteration)
                          normalized-tool-calls (mapv tools/normalize-tool-call-for-request tool-calls)
                          assistant-msg {:role "assistant"
                                         :content (or (:content message) "")
                                         :tool_calls normalized-tool-calls}
                          {:keys [workspace tool-call-results]}
                          (reduce (fn [{:keys [workspace tool-call-results]} tool-call]
                                    (let [[entry workspace']
                                          (dispatch-one-tool-call tool-call workspace ambient-ctx iteration progress-fn)]
                                      {:workspace workspace'
                                       :tool-call-results (conj tool-call-results entry)}))
                                  {:workspace workspace :tool-call-results []}
                                  normalized-tool-calls)
                          query-text (or (:query ambient-ctx)
                                         (active-query messages)
                                         "")
                          workspace (workspace/evaluate-pending-reads workspace ambient-ctx iteration query-text)
                          generated-response-call? (boolean (last-tool-result tool-call-results "generate_response"))
                          workspace-after-tools workspace
                      ;; Shadow decisions drive hint injection in three cases:
                      ;;   - after a generate_response call (need to validate or retry),
                      ;;   - when the aggregate is a clean :sufficient (signal finalize),
                      ;;   - when the aggregate strategy is :re-search or :ask-clarification
                      ;;     (signal stagnation or scope ambiguity that plain :read-more
                      ;;     can't surface to the LLM on its own).
                      ;; Plain :read-more / :insufficient iterations let the raw tool
                      ;; result be the last message — the LLM's own scripted flow is
                      ;; adequate for "keep reading" and injecting a hint there would
                      ;; drown out budget-exhaustion markers the agent needs to see.
                      ;; `should-run-sufficiency-gate?` itself returns
                      ;; false for enrichment-mode agents (centralized
                      ;; so all three loop variants — imperative,
                      ;; bundled, faithful — inherit the suppression
                      ;; without each having to guard separately).
                      shadow-gate-raw-decision (when (should-run-sufficiency-gate? tool-call-results ambient-ctx)
                                                 (shadow-gate-decision workspace-after-tools))
                      shadow-read-decision (when (and shadow-gate-raw-decision
                                                      (or generated-response-call?
                                                          (= :sufficient (:status shadow-gate-raw-decision))
                                                          (contains? #{:re-search :ask-clarification}
                                                                     (:suggested-strategy shadow-gate-raw-decision))))
                                             shadow-gate-raw-decision)
                      ;; Range-read hint: when the agent just did a
                      ;; chunk-ids read and the local read-signal came back
                      ;; with a gap, detect if the doc the chunks came from
                      ;; has unread adjacent chunks. If so, append a
                      ;; copy-pasteable range-read tool call to the
                      ;; read_chunks tool-result so the LLM's next move
                      ;; expands within the same doc instead of re-searching.
                      read-chunks-called? (some #(= "read_chunks" (:tool %))
                                                tool-call-results)
                      last-read-signal-status (get-in workspace-after-tools
                                                      [:last-read-signal :status])
                      range-read-hint-candidate (when (and read-chunks-called?
                                                           (contains? #{:gap-remaining :unclear :conflicting}
                                                                      last-read-signal-status))
                                                  (workspace/range-read-suggestion
                                                   workspace-after-tools))
                      range-read-hint-text (some-> range-read-hint-candidate
                                                   workspace/range-read-hint-text)
                          tool-call-results (if range-read-hint-text
                                              (let [injected? (atom false)]
                                                (mapv (fn [tcr]
                                                        (if (and (not @injected?)
                                                                 (= "read_chunks" (:tool tcr)))
                                                          (do (reset! injected? true)
                                                              (update tcr :result-summary
                                                                      #(str % range-read-hint-text)))
                                                          tcr))
                                                      tool-call-results))
                                              tool-call-results)
                          workspace (if range-read-hint-candidate
                                      (workspace/record-stage-timing
                                        workspace
                                        {:stage :range-read-hint
                                         :iteration iteration
                                         :duration-ms 0
                                         :status :ok
                                         :detail (str "doc_num=" (:doc-num range-read-hint-candidate)
                                                      " unread=" (:unread-indices range-read-hint-candidate)
                                                      " read=" (:read-indices range-read-hint-candidate))})
                                      workspace)
                          sufficiency-evidence-summary (when (and (should-run-sufficiency-gate? tool-call-results ambient-ctx)
                                                                  (nil? shadow-read-decision))
                                                         (sufficiency/build-evidence-summary workspace query-text))
                          ;; Pre-filter: the keep-guard below only retains a
                          ;; decision when either a generate_response call fired
                          ;; this iteration (any status retained for validation)
                          ;; OR the decision is :sufficient with no unread
                          ;; evidence. When neither precondition can be met, any
                          ;; decision would be discarded — so skip the ~1.5–3s
                          ;; sufficiency LLM call entirely rather than burn it
                          ;; on a guaranteed-discarded result.
                          has-unread-evidence? (when sufficiency-evidence-summary
                                                 (or (seq (:unread-chunk-ids sufficiency-evidence-summary))
                                                     (some? (:unread-range sufficiency-evidence-summary))))
                          sufficiency-keepable? (when sufficiency-evidence-summary
                                                  (or generated-response-call?
                                                      (not has-unread-evidence?)))
                          sufficiency-start (when sufficiency-keepable? (System/currentTimeMillis))
                          sufficiency-decision (or shadow-read-decision
                                                   (when sufficiency-keepable?
                                                     (let [decision (-> (sufficiency/evaluate-sufficiency
                                                                          query-text
                                                                          sufficiency-evidence-summary
                                                                          {:llm-fn sufficiency-llm-fn
                                                                           :model model
                                                                           :temperature sufficiency-llm-temperature})
                                                                        (assoc :query query-text
                                                                               :source :llm-sufficiency-gate))
                                                           decision (enforce-sufficiency-limit workspace decision)]
                                                       (when (or generated-response-call?
                                                                 (and (= :sufficient (:status decision))
                                                                      (not has-unread-evidence?)))
                                                         decision))))
                          sufficiency-duration-ms (when sufficiency-start
                                                    (- (System/currentTimeMillis) sufficiency-start))
                          sufficiency-skipped-unkeepable?
                          (and sufficiency-evidence-summary (not sufficiency-keepable?))
                          sufficiency-hint-evidence-summary (when sufficiency-decision
                                                              (or sufficiency-evidence-summary
                                                                  (sufficiency/build-evidence-summary workspace query-text)))
                          sufficiency-hint (when sufficiency-decision
                                             (gate-system-hint query-text sufficiency-decision sufficiency-hint-evidence-summary))
                          tool-call-results
                          (if (and sufficiency-decision
                                   (not= :sufficient (:status sufficiency-decision))
                                   generated-response-call?)
                            (mapv (fn [tcr]
                                    (if (= "generate_response" (:tool tcr))
                                      (assoc tcr :result-summary sufficiency-hint)
                                      tcr))
                                  tool-call-results)
                            tool-call-results)
                          workspace (if sufficiency-decision
                                      (workspace/record-sufficiency-decision
                                        workspace
                                        (assoc sufficiency-decision :message sufficiency-hint))
                                      workspace)
                          workspace (if sufficiency-duration-ms
                                      (workspace/record-stage-timing
                                        workspace
                                        {:stage :sufficiency-gate
                                         :iteration iteration
                                         :duration-ms sufficiency-duration-ms
                                         :status (if sufficiency-decision :ok :skipped)})
                                      workspace)
                          workspace (if sufficiency-skipped-unkeepable?
                                      (workspace/record-stage-timing
                                        workspace
                                        {:stage :sufficiency-gate
                                         :iteration iteration
                                         :duration-ms 0
                                         :status :skipped
                                         :detail "unkeepable: unread evidence + no generate_response call"})
                                      workspace)
                          response-validation-evidence-summary
                          (when (and generated-response-call?
                                     (or (nil? sufficiency-decision)
                                         (= :sufficient (:status sufficiency-decision)))
                                     (should-run-response-validation? workspace))
                            (sufficiency/build-evidence-summary workspace query-text))
                          response-validation-start (when response-validation-evidence-summary
                                                      (System/currentTimeMillis))
                          response-validation-decision
                          (when response-validation-evidence-summary
                            (let [decision (-> (sufficiency/evaluate-sufficiency
                                                 query-text
                                                 response-validation-evidence-summary
                                                 {:llm-fn sufficiency-llm-fn
                                                  :model model
                                                  :temperature sufficiency-llm-temperature})
                                               (assoc :query query-text
                                                      :source :response-validation))]
                              (enforce-sufficiency-limit workspace decision)))
                          response-validation-duration-ms
                          (when response-validation-start
                            (- (System/currentTimeMillis) response-validation-start))
                          response-validation-hint-text
                          (when response-validation-decision
                            (response-validation-hint query-text
                                                      response-validation-decision
                                                      response-validation-evidence-summary))
                          tool-call-results
                          (if (and response-validation-decision
                                   (not= :sufficient (:status response-validation-decision))
                                   generated-response-call?)
                            (mapv (fn [tcr]
                                    (if (= "generate_response" (:tool tcr))
                                      (assoc tcr :result-summary response-validation-hint-text)
                                      tcr))
                                  tool-call-results)
                            tool-call-results)
                          workspace (if response-validation-decision
                                      (workspace/record-response-validation
                                        workspace
                                        (assoc response-validation-decision :message response-validation-hint-text))
                                      workspace)
                          workspace (if response-validation-duration-ms
                                      (workspace/record-stage-timing
                                        workspace
                                        {:stage :response-validation
                                         :iteration iteration
                                         :duration-ms response-validation-duration-ms
                                         :status (if response-validation-decision :ok :skipped)})
                                      workspace)
                          active-decision (or response-validation-decision sufficiency-decision)
                          active-hint (or response-validation-hint-text sufficiency-hint)
                          trace-entries (mapv (comp enriched-tool-call-trace-entry
                                                    #(dissoc % :tool-call-id))
                                              tool-call-results)
                          workspace (workspace/record-turn workspace iteration (:content message)
                                                           trace-entries)
                          iteration-stage-timings (->> (:stage-timings workspace)
                                                       (filter #(= iteration (:iteration %)))
                                                       vec)
                          _ (events/emit-progress! progress-fn
                                                   (assoc (events/agent-turn-completed
                                                           iteration
                                                           (when-let [r (:content message)]
                                                             (if (> (count r) 500) (subs r 0 500) r))
                                                           (mapv truncate-result-summary-for-stream trace-entries))
                                                          :stage-timings iteration-stage-timings))
                          tool-messages (mapv #(tools/build-tool-result-message (:tool-call-id %) (:result-summary %))
                                              tool-call-results)
                          updated-messages (into (conj (vec messages) assistant-msg) tool-messages)
                          generated-response (stored-workspace-response workspace)
                          generated-needs-retry? (generated-response-needs-retry? workspace tool-call-results)
                          workspace (if generated-response-call?
                                      (assoc workspace :last-generate-insufficient-context
                                             (or generated-needs-retry?
                                                 (and active-decision
                                                      (not= :sufficient (:status active-decision)))))
                                      workspace)]
                      (cond
                        (and active-decision
                             (= :ask-clarification (:suggested-strategy active-decision)))
                        (let [clarification (clarification-request active-decision)]
                          (finalize workspace
                                    {:response (:question clarification)
                                     :clarification-request clarification
                                     :terminal-state :clarify
                                     :trace (:iteration-history workspace)}))

                        (and generated-response-call?
                             (nil? active-decision)
                             (not generated-needs-retry?)
                             (not (str/blank? generated-response)))
                        (do
                          (events/emit-progress! progress-fn
                                                 (events/agent-finalized iteration
                                                                         (count (or generated-response ""))))
                          (finalize workspace
                                    {:response generated-response
                                     :terminal-state :finalize
                                     :trace (:iteration-history workspace)}))

                        ;; Shortcut: If sufficiency gate (shadow or LLM) says finalize, skip the next LLM turn and synthesize now.
                        ;; Suppressed for enrichment-mode agents — their task isn't to answer the user's example query,
                        ;; it's to call structured propose/apply/eval tools. Letting sufficiency force a synthesis bypasses
                        ;; the agent's own intent (see self-improve-agent smoke trace 2026-05-18T14-56-02Z).
                        (and active-decision
                             (= :finalize (:suggested-strategy active-decision))
                             (not generated-response-call?)
                             (not (enrichment-mode-agent? ambient-ctx)))
                        (let [synth-result (synthesize-and-finalize query-text workspace ambient-ctx progress-fn iteration)]
                          (finalize (:workspace synth-result) (dissoc synth-result :workspace)))

                        (and active-decision
                             (= :sufficient (:status active-decision))
                             generated-response-call?
                             (not generated-needs-retry?))
                        (do
                          (events/emit-progress! progress-fn
                                                 (events/agent-finalized iteration
                                                                         (count (or generated-response ""))))
                          (finalize workspace
                                    {:response generated-response
                                     :terminal-state :finalize
                                     :trace (:iteration-history workspace)}))

                        (and active-decision
                             (not= :sufficient (:status active-decision))
                             (= :finalize (:suggested-strategy active-decision))
                             generated-response-call?
                             (not (str/blank? generated-response)))
                        (do
                          (events/emit-progress! progress-fn
                                                 (events/agent-finalized iteration
                                                                         (count generated-response)))
                          (finalize workspace
                                    {:response generated-response
                                     :terminal-state :finalize
                                     :trace (:iteration-history workspace)}))

                        active-decision
                        (let [next-phase (case (:suggested-strategy active-decision)
                                           :finalize (if (= :sufficient (:status active-decision))
                                                       :finalize
                                                       (if generated-response-call?
                                                         :default
                                                         :finalize))
                                           :default)
                              updated-messages (conj updated-messages {:role "system"
                                                                       :content active-hint})]
                          (recur updated-messages workspace (inc iteration) next-phase))

                        :else
                        (recur updated-messages workspace (inc iteration) :default)))
                    (let [response-text (or (:content message) "")]
                      (events/emit-progress! progress-fn
                                             (events/agent-finalized iteration
                                                                     (count response-text)))
                      (finalize workspace
                                {:response response-text
                                 :terminal-state :finalize
                                 :trace (:iteration-history workspace)}))))))))]
    result))

(ns digdir.skills.builtin.agent.iteration-bundled
  "Phase 2.5.C — Bundled inner sub-graph for the agent ReAct loop.

   One iteration is expressed as four registered skills strung together
   inside :builtin/agent-iteration-bundled. Each bundle groups the
   imperative agentic-loop slice that performs a logical phase:

   1. :pick-state          — first step, picks prev-iter outputs or
                             setup's initial state (:builtin/agent-iter-state,
                             registered in agent.graphs)
   2. :llm-and-tools       — LLM call + tool-foreach dispatch
   3. :evaluate-evidence   — end-of-iter read-signal eval + range-read-hint
   4. :sufficiency-gates   — sufficiency gate + response-validation gate
   5. :record-and-route    — record-turn + decision routing → next-phase /
                             finalized?

   Bundles use the public helpers from agent.loop (promoted from
   defn- in a prep commit) so the imperative loop and the bundled
   variant share one canonical implementation per phase. The eval
   gate in 2.5.E compares this against :faithful (10-step, 2.5.D)
   to pick a winner."
  (:require [digdir.rag.skills.core :as skills]
            [digdir.skills.builtin.agent.loop :as agent-loop]
            [digdir.skills.builtin.agent.sufficiency :as sufficiency]
            [digdir.skills.builtin.agent.tools :as tools]
            [digdir.skills.builtin.agent.workspace :as workspace]
            [digdir.llm.client :as llm-client]
            [digdir.skills.events :as events]
            [digdir.skills.templates.core :as templates]
            [clojure.string :as str]))

;; =============================================================================
;; :builtin/agent-bundle-llm-and-tools
;; =============================================================================

(def agent-bundle-llm-and-tools-metadata
  {:skill-id :builtin/agent-bundle-llm-and-tools
   :name "Agent bundle: LLM + tool dispatch"
   :description "First active bundle of the bundled inner sub-graph. Calls the LLM with the iteration's messages + tools, then sequentially dispatches every tool call the LLM emitted via :builtin/agent-tool-call. Records :agent-llm and per-tool stage timings. Emits :agent-iteration-started / :agent-thinking / :agent-tool-call / :agent-tool-result events."
   :category :orchestration
   :inputs [:messages-in :workspace-in :phase :tools :ambient-ctx
            :model :temperature :iteration :max-iterations]
   :outputs [:workspace-after-tools :tool-call-results :assistant-msg
             :llm-error :no-tool-calls? :finish-reason :direct-response]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :orchestration :graph-cutover :bundled}})

(defn- ablated?
  "#390 part (1): is `stage` disabled for this run?

   Reads an OPT-IN set from skill-params — `{:builtin/agent {:ablate-stages
   #{:read-signal-eval}}}` — so it is INERT unless a caller asks for it. Exists
   to answer 'does this step change the outcome at all', which cannot be
   measured without being able to not-run the step. The three per-iteration
   stages are already GATED (they skip on some queries), so not-running them is
   a path the graph already takes rather than one this flag invents."
  [ambient-ctx stage]
  (boolean (some-> (get-in ambient-ctx [:opts :skill-params :builtin/agent :ablate-stages])
                   set
                   (contains? stage))))

(defn execute-agent-bundle-llm-and-tools
  [{:keys [inputs]}]
  (let [{:keys [messages-in workspace-in phase _tools ambient-ctx
                model temperature iteration max-iterations]} inputs
        tenant (get-in ambient-ctx [:opts :tenant])
        progress-fn (get-in ambient-ctx [:opts :progress-fn])
        _ (events/emit-progress! progress-fn
                                 (events/agent-iteration-started iteration
                                                                 (or max-iterations 10)))
        llm-start (System/currentTimeMillis)
        response (try
                   (agent-loop/call-llm tenant messages-in
                                        (agent-loop/tools-for-phase ambient-ctx phase)
                                        model temperature
                                        ;; 6-arity: streams content deltas as
                                        ;; :response/chunk events. The 5-arity
                                        ;; is silent — see #148.
                                        {:progress-fn progress-fn})
                   (catch Exception e {:llm-exception e}))
        llm-duration-ms (- (System/currentTimeMillis) llm-start)
        workspace (workspace/record-stage-timing
                    workspace-in
                    (cond-> {:stage :agent-llm
                             :iteration iteration
                             :duration-ms llm-duration-ms
                             :status (if (:llm-exception response) :error :ok)}
                      (:usage response) (assoc :usage (:usage response))
                      (:model response) (assoc :llm-model (:model response))
                      (not (:llm-exception response))
                      (assoc :finish-reason (get-in response [:choices 0 :finish_reason]))))]
    (if-let [e (:llm-exception response)]
      (let [err-msg (agent-loop/summarize-llm-exception e iteration)]
        (events/emit-progress! progress-fn
                               (events/emit-agent-error iteration err-msg))
        (skills/success-result
          {:workspace-after-tools workspace
           :tool-call-results []
           :assistant-msg nil
           :llm-error err-msg
           :no-tool-calls? false
           :finish-reason nil
           :direct-response nil}
          {}))
      (let [choice (-> response :choices first)
            message (:message choice)
            finish-reason (:finish_reason choice)
            tool-calls (:tool_calls message)
            _ (when-let [content (not-empty (str/trim (or (:content message) "")))]
                (events/emit-progress! progress-fn
                                       (events/agent-thinking iteration content)))]
        (if (and (seq tool-calls)
                 (or (= finish-reason "tool_calls") (= finish-reason "stop")))
          (let [workspace (assoc workspace :current-iteration iteration)
                normalized-tool-calls (mapv tools/normalize-tool-call-for-request tool-calls)
                assistant-msg {:role "assistant"
                               :content (or (:content message) "")
                               :tool_calls normalized-tool-calls}
                {:keys [workspace tool-call-results]}
                (reduce (fn [{:keys [workspace tool-call-results]} tool-call]
                          (let [[entry workspace']
                                (agent-loop/dispatch-one-tool-call
                                  tool-call workspace ambient-ctx iteration progress-fn)]
                            {:workspace workspace'
                             :tool-call-results (conj tool-call-results entry)}))
                        {:workspace workspace :tool-call-results []}
                        normalized-tool-calls)]
            (skills/success-result
              {:workspace-after-tools workspace
               :tool-call-results tool-call-results
               :assistant-msg assistant-msg
               :llm-error nil
               :no-tool-calls? false
               :finish-reason finish-reason
               :direct-response nil}
              {}))
          (skills/success-result
            {:workspace-after-tools workspace
             :tool-call-results []
             :assistant-msg {:role "assistant" :content (or (:content message) "")}
             :llm-error nil
             :no-tool-calls? true
             :finish-reason finish-reason
             :direct-response (or (:content message) "")}
            {}))))))

(def agent-bundle-llm-and-tools-skill
  {:metadata agent-bundle-llm-and-tools-metadata
   :execute execute-agent-bundle-llm-and-tools})

;; =============================================================================
;; :builtin/agent-bundle-evaluate-evidence
;; =============================================================================

(def agent-bundle-evaluate-evidence-metadata
  {:skill-id :builtin/agent-bundle-evaluate-evidence
   :name "Agent bundle: end-of-iteration evidence eval"
   :description "Runs the end-of-iteration read-signal evaluation over any chunks queued during this iteration's reads, computes the shadow-gate decision from accumulated read-signals, and injects a range-read nudge into the read_chunks tool-result when applicable. Records :range-read-hint stage timing if applied."
   :category :orchestration
   :inputs [:workspace-after-tools :tool-call-results :ambient-ctx :iteration]
   :outputs [:workspace-with-signals :shadow-decision :generated-response-call?
             :tool-call-results-after-hint]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :orchestration :graph-cutover :bundled}})

(defn execute-agent-bundle-evaluate-evidence
  [{:keys [inputs]}]
  (let [{:keys [workspace-after-tools tool-call-results ambient-ctx iteration]} inputs
        query-text (or (:query ambient-ctx) "")
        workspace (if (ablated? ambient-ctx :read-signal-eval)
                    workspace-after-tools
                    (workspace/evaluate-pending-reads
                      workspace-after-tools ambient-ctx iteration query-text))
        generated-response-call? (boolean
                                   (agent-loop/last-tool-result tool-call-results
                                                                "generate_response"))
        shadow-gate-raw-decision
        (when (agent-loop/should-run-sufficiency-gate? tool-call-results ambient-ctx)
          (agent-loop/shadow-gate-decision workspace))
        shadow-read-decision
        (when (and shadow-gate-raw-decision
                   (or generated-response-call?
                       (= :sufficient (:status shadow-gate-raw-decision))
                       (contains? #{:re-search :ask-clarification}
                                  (:suggested-strategy shadow-gate-raw-decision))))
          shadow-gate-raw-decision)
        read-chunks-called? (some #(= "read_chunks" (:tool %)) tool-call-results)
        last-read-signal-status (get-in workspace [:last-read-signal :status])
        range-read-hint-candidate
        (when (and read-chunks-called?
                   (contains? #{:gap-remaining :unclear :conflicting}
                              last-read-signal-status))
          (workspace/range-read-suggestion workspace))
        range-read-hint-text (some-> range-read-hint-candidate
                                     workspace/range-read-hint-text)
        tool-call-results-with-hint
        (if range-read-hint-text
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
                    workspace)]
    (skills/success-result
      {:workspace-with-signals workspace
       :shadow-decision shadow-read-decision
       :generated-response-call? generated-response-call?
       :tool-call-results-after-hint tool-call-results-with-hint}
      {})))

(def agent-bundle-evaluate-evidence-skill
  {:metadata agent-bundle-evaluate-evidence-metadata
   :execute execute-agent-bundle-evaluate-evidence})

;; =============================================================================
;; :builtin/agent-bundle-sufficiency-gates
;; =============================================================================

(def agent-bundle-sufficiency-gates-metadata
  {:skill-id :builtin/agent-bundle-sufficiency-gates
   :name "Agent bundle: sufficiency + response-validation gates"
   :description "Runs the sufficiency-gate LLM call (with keepability guards) and, when applicable, the separate response-validation-gate LLM call. Applies any hint text into the generate_response tool-result :result-summary so the next LLM iteration sees it. Records both decisions + stage timings into workspace."
   :category :orchestration
   :inputs [:workspace-with-signals :tool-call-results-after-hint
            :shadow-decision :generated-response-call?
            :ambient-ctx :model :iteration]
   :outputs [:workspace-after-gates :active-decision :active-hint
             :tool-call-results-final]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :orchestration :graph-cutover :bundled}})

(defn execute-agent-bundle-sufficiency-gates
  [{:keys [inputs]}]
  (let [{:keys [workspace-with-signals tool-call-results-after-hint
                shadow-decision generated-response-call?
                ambient-ctx model iteration]} inputs
        query-text (or (:query ambient-ctx) "")
        opts (:opts ambient-ctx)
        sufficiency-llm-fn (:sufficiency-llm-fn opts)
        sufficiency-llm-temperature (:sufficiency-llm-temperature opts)
        sufficiency-evidence-summary
        (when (and (agent-loop/should-run-sufficiency-gate? tool-call-results-after-hint ambient-ctx)
                   (nil? shadow-decision))
          (sufficiency/build-evidence-summary workspace-with-signals query-text))
        has-unread-evidence? (when sufficiency-evidence-summary
                               (or (seq (:unread-chunk-ids sufficiency-evidence-summary))
                                   (some? (:unread-range sufficiency-evidence-summary))))
        sufficiency-keepable? (when (and sufficiency-evidence-summary
                                         (not (ablated? ambient-ctx :sufficiency-gate)))
                                (or generated-response-call?
                                    (not has-unread-evidence?)))
        sufficiency-start (when sufficiency-keepable? (System/currentTimeMillis))
        ;; #25: this gate calls an LLM, so its usage must be captured like any
        ;; other LLM stage. An atom because the value is produced here and
        ;; recorded ~40 lines below, inside a different cond->.
        !sufficiency-usage (atom nil)
        sufficiency-decision
        (or shadow-decision
            (when sufficiency-keepable?
              (let [captured (llm-client/capture-usage
                               #(sufficiency/evaluate-sufficiency
                                  query-text
                                  sufficiency-evidence-summary
                                  {:llm-fn sufficiency-llm-fn
                                   :model model
                                   :temperature sufficiency-llm-temperature}))
                    _ (reset! !sufficiency-usage captured)
                    decision (-> (:result captured)
                                 (assoc :query query-text
                                        :source :llm-sufficiency-gate))
                    decision (agent-loop/enforce-sufficiency-limit
                               workspace-with-signals decision)]
                (when (or generated-response-call?
                          (and (= :sufficient (:status decision))
                               (not has-unread-evidence?)))
                  decision))))
        sufficiency-duration-ms (when sufficiency-start
                                  (- (System/currentTimeMillis) sufficiency-start))
        sufficiency-skipped-unkeepable?
        (and sufficiency-evidence-summary (not sufficiency-keepable?))
        ;; Deterministic grounding floor UNDER the LLM gate. An answer citing
        ;; nothing real is rejected on the produced text alone — no LLM call,
        ;; so it cannot itself hallucinate and adds no tokens. Kept as its own
        ;; binding rather than folded into `sufficiency-decision` because that
        ;; one carries the sufficiency stage's usage accounting: reusing it
        ;; would report an :ok gate with no usage write and read as a lost
        ;; usage in the #25 decomposition. Ablatable like the other gates.
        grounding-decision
        (when (and generated-response-call?
                   (not (ablated? ambient-ctx :grounding-gate)))
          (sufficiency/grounding-decision workspace-with-signals))
        ;; Grounding wins: it is a hard floor, and when it fires the LLM gate's
        ;; verdict is moot — an uncited answer is not finalizable regardless of
        ;; how sufficient the evidence looked.
        effective-decision (or grounding-decision sufficiency-decision)
        sufficiency-hint-evidence-summary
        (when effective-decision
          (or sufficiency-evidence-summary
              (sufficiency/build-evidence-summary workspace-with-signals query-text)))
        sufficiency-hint (when effective-decision
                           (agent-loop/gate-system-hint query-text effective-decision
                                                        sufficiency-hint-evidence-summary))
        tool-call-results
        (if (and effective-decision
                 (not= :sufficient (:status effective-decision))
                 generated-response-call?)
          (mapv (fn [tcr]
                  (if (= "generate_response" (:tool tcr))
                    (assoc tcr :result-summary sufficiency-hint)
                    tcr))
                tool-call-results-after-hint)
          tool-call-results-after-hint)
        workspace (if effective-decision
                    (workspace/record-sufficiency-decision
                      workspace-with-signals
                      (assoc effective-decision :message sufficiency-hint))
                    workspace-with-signals)
        workspace (if grounding-decision
                    (workspace/record-stage-timing
                      workspace
                      {:stage :grounding-gate
                       :iteration iteration
                       :duration-ms 0
                       :status :ok
                       ;; No LLM call by construction — declare it, or the
                       ;; sentinel defaults to "a call happened" and this
                       ;; reads as a missing usage (#25).
                       :usage-writes 0
                       :detail "deterministic: answer cited no valid chunk"})
                    workspace)
        workspace (if sufficiency-duration-ms
                    (workspace/record-stage-timing
                      workspace
                      (merge {:stage :sufficiency-gate
                              :iteration iteration
                              :duration-ms sufficiency-duration-ms
                              :status (if sufficiency-decision :ok :skipped)}
                             (llm-client/usage-summary @!sufficiency-usage)))
                    workspace)
        workspace (if sufficiency-skipped-unkeepable?
                    (workspace/record-stage-timing
                      workspace
                      {:stage :sufficiency-gate
                       :iteration iteration
                       :duration-ms 0
                       :status :skipped
                       ;; #25: this firing made NO LLM call, so it is a
                       ;; short-circuit rather than a lost usage. Without the
                       ;; explicit 0 the key is absent, the sentinel defaults it
                       ;; to "a call happened", and a SKIPPED gate is reported
                       ;; as a missing usage -- which is what the last three
                       ;; flagged rows were.
                       :usage-writes 0
                       :detail "unkeepable: unread evidence + no generate_response call"})
                    workspace)
        response-validation-evidence-summary
        (when (and (not (ablated? ambient-ctx :response-validation))
                   generated-response-call?
                   ;; `effective-decision`, not `sufficiency-decision`: when the
                   ;; deterministic grounding gate has already rejected the
                   ;; answer there is nothing for this LLM call to add, and
                   ;; running it would spend a call to re-litigate a settled
                   ;; verdict.
                   (or (nil? effective-decision)
                       (= :sufficient (:status effective-decision)))
                   (agent-loop/should-run-response-validation? workspace))
          (sufficiency/build-evidence-summary workspace query-text))
        response-validation-start (when response-validation-evidence-summary
                                    (System/currentTimeMillis))
        !response-validation-usage (atom nil)
        response-validation-decision
        (when response-validation-evidence-summary
          (let [captured (llm-client/capture-usage
                           #(sufficiency/evaluate-sufficiency
                              query-text
                              response-validation-evidence-summary
                              {:llm-fn sufficiency-llm-fn
                               :model model
                               :temperature sufficiency-llm-temperature}))
                _ (reset! !response-validation-usage captured)
                decision (-> (:result captured)
                             (assoc :query query-text
                                    :source :response-validation))]
            (agent-loop/enforce-sufficiency-limit workspace decision)))
        response-validation-duration-ms
        (when response-validation-start
          (- (System/currentTimeMillis) response-validation-start))
        response-validation-hint-text
        (when response-validation-decision
          (agent-loop/response-validation-hint query-text response-validation-decision
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
                      (assoc response-validation-decision
                             :message response-validation-hint-text))
                    workspace)
        workspace (if response-validation-duration-ms
                    (workspace/record-stage-timing
                      workspace
                      (merge {:stage :response-validation
                              :iteration iteration
                              :duration-ms response-validation-duration-ms
                              :status (if response-validation-decision :ok :skipped)}
                             (llm-client/usage-summary @!response-validation-usage)))
                    workspace)
        active-decision (or response-validation-decision
                            grounding-decision
                            sufficiency-decision)
        active-hint (or response-validation-hint-text sufficiency-hint)]
    (skills/success-result
      {:workspace-after-gates workspace
       :active-decision active-decision
       :active-hint active-hint
       :tool-call-results-final tool-call-results}
      {})))

(def agent-bundle-sufficiency-gates-skill
  {:metadata agent-bundle-sufficiency-gates-metadata
   :execute execute-agent-bundle-sufficiency-gates})

;; =============================================================================
;; :builtin/agent-bundle-record-and-route
;; =============================================================================

(def agent-bundle-record-and-route-metadata
  {:skill-id :builtin/agent-bundle-record-and-route
   :name "Agent bundle: record-turn + route"
   :description "Final bundle of the inner sub-graph. Records the iteration's turn (trace entries + budget snapshot) on workspace. Emits :agent-turn-completed. Decides whether to terminate (clarify / finalize) or continue with a next-phase. When LLM produced no tool calls or threw, packages a direct terminal result. Outputs feed the outer :loop's :until-output :finalized? check."
   :category :orchestration
   :inputs [:workspace-after-gates :tool-call-results-final
            :active-decision :active-hint :generated-response-call?
            :messages-in :assistant-msg :llm-error :no-tool-calls?
            :direct-response :ambient-ctx :iteration :model :temperature]
   :outputs [:messages-out :workspace-out :next-phase :finalized?
             :terminal-state :response :clarification-request]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :orchestration :graph-cutover :bundled}})

(defn- short-circuit-llm-error
  "When :llm-and-tools surfaced an LLM exception, package the error
   without touching gates and emit the same terminal envelope shape the
   imperative loop would have returned."
  [workspace-after-gates llm-error]
  {:messages-out []
   :workspace-out workspace-after-gates
   :next-phase nil
   :finalized? true
   :terminal-state :error
   :response llm-error})

(defn- short-circuit-no-tool-calls
  "When the LLM produced a text-only response with no tool calls, the
   imperative loop returns immediately with that response as the
   terminal answer. Mirror that here."
  [workspace-after-gates direct-response progress-fn iteration]
  (let [response-text (or direct-response "")]
    (events/emit-progress! progress-fn
                           (events/agent-finalized iteration (count response-text)))
    {:messages-out []
     :workspace-out workspace-after-gates
     :next-phase nil
     :finalized? true
     :terminal-state :finalize
     :response response-text}))

(defn execute-agent-bundle-record-and-route
  [{:keys [inputs]}]
  (let [{:keys [workspace-after-gates tool-call-results-final
                active-decision active-hint generated-response-call?
                messages-in assistant-msg llm-error no-tool-calls?
                direct-response ambient-ctx iteration _model _temperature]} inputs
        progress-fn (get-in ambient-ctx [:opts :progress-fn])]
    (cond
      llm-error
      (skills/success-result (short-circuit-llm-error workspace-after-gates llm-error) {})

      no-tool-calls?
      (let [searched? (boolean (seq (:search-history workspace-after-gates)))
            retries (or (:no-tool-call-retries workspace-after-gates) 0)
            ;; Off by default (eval-gate discipline). Enable via
            ;; :skill-params {:builtin/agent {:no-tool-call-retry true}}.
            guard-on? (boolean (get-in ambient-ctx [:opts :skill-params
                                                    :builtin/agent :no-tool-call-retry]))
            {:keys [action nudge response]}
            (agent-loop/no-tool-call-decision {:enabled? guard-on?
                                               :searched? searched?
                                               :direct-response direct-response
                                               :retries retries})]
        (if (= action :retry)
          ;; The model wrote a search as prose without ever calling the tool.
          ;; Re-prompt for a real tool call instead of finalizing on the
          ;; scaffolding: append the bad turn + a corrective nudge and continue.
          (skills/success-result
            {:messages-out (conj (vec messages-in)
                                 assistant-msg
                                 {:role "system" :content nudge})
             :workspace-out (assoc workspace-after-gates
                                   :no-tool-call-retries (inc retries))
             :next-phase :default
             :finalized? false}
            {})
          (skills/success-result (short-circuit-no-tool-calls workspace-after-gates
                                                              response
                                                              progress-fn iteration) {})))

      :else
      (let [trace-entries (mapv (comp agent-loop/enriched-tool-call-trace-entry
                                      #(dissoc % :tool-call-id))
                                tool-call-results-final)
            workspace (workspace/record-turn workspace-after-gates iteration
                                             (:content assistant-msg) trace-entries)
            iteration-stage-timings (->> (:stage-timings workspace)
                                         (filter #(= iteration (:iteration %)))
                                         vec)
            _ (events/emit-progress! progress-fn
                                     (assoc (events/agent-turn-completed
                                              iteration
                                              (when-let [r (:content assistant-msg)]
                                                (if (> (count r) 500) (subs r 0 500) r))
                                              (mapv agent-loop/truncate-result-summary-for-stream
                                                    trace-entries))
                                            :stage-timings iteration-stage-timings))
            tool-messages (mapv #(tools/build-tool-result-message (:tool-call-id %)
                                                                  (:result-summary %))
                                tool-call-results-final)
            updated-messages (into (conj (vec messages-in) assistant-msg) tool-messages)
            generated-response (agent-loop/stored-workspace-response workspace)
            generated-needs-retry? (agent-loop/generated-response-needs-retry?
                                     workspace tool-call-results-final)
            workspace (if generated-response-call?
                        (assoc workspace :last-generate-insufficient-context
                               (or generated-needs-retry?
                                   (and active-decision
                                        (not= :sufficient (:status active-decision)))))
                        workspace)
            query-text (or (:query ambient-ctx) "")]
        (cond
          (and active-decision
               (= :ask-clarification (:suggested-strategy active-decision)))
          (let [clarification (agent-loop/clarification-request active-decision)]
            (skills/success-result
              {:messages-out updated-messages
               :workspace-out workspace
               :next-phase nil
               :finalized? true
               :terminal-state :clarify
               :response (:question clarification)
               :clarification-request clarification}
              {}))

          (and generated-response-call?
               (nil? active-decision)
               (not generated-needs-retry?)
               (not (str/blank? generated-response)))
          (do (events/emit-progress! progress-fn
                                     (events/agent-finalized iteration
                                                             (count generated-response)))
              (skills/success-result
                {:messages-out updated-messages
                 :workspace-out workspace
                 :next-phase nil
                 :finalized? true
                 :terminal-state :finalize
                 :response generated-response}
                {}))

          (and active-decision
               (= :finalize (:suggested-strategy active-decision))
               (not generated-response-call?))
          (let [synth-result (agent-loop/synthesize-and-finalize
                               query-text workspace ambient-ctx progress-fn iteration)]
            (skills/success-result
              {:messages-out updated-messages
               :workspace-out (:workspace synth-result)
               :next-phase nil
               :finalized? true
               :terminal-state (:terminal-state synth-result)
               :response (:response synth-result)}
              {}))

          (and active-decision
               (= :sufficient (:status active-decision))
               generated-response-call?
               (not generated-needs-retry?))
          (do (events/emit-progress! progress-fn
                                     (events/agent-finalized iteration
                                                             (count (or generated-response ""))))
              (skills/success-result
                {:messages-out updated-messages
                 :workspace-out workspace
                 :next-phase nil
                 :finalized? true
                 :terminal-state :finalize
                 :response generated-response}
                {}))

          (and active-decision
               (not= :sufficient (:status active-decision))
               (= :finalize (:suggested-strategy active-decision))
               generated-response-call?
               (not (str/blank? generated-response)))
          (do (events/emit-progress! progress-fn
                                     (events/agent-finalized iteration
                                                             (count generated-response)))
              (skills/success-result
                {:messages-out updated-messages
                 :workspace-out workspace
                 :next-phase nil
                 :finalized? true
                 :terminal-state :finalize
                 :response generated-response}
                {}))

          active-decision
          (let [next-phase (case (:suggested-strategy active-decision)
                             :finalize (if (= :sufficient (:status active-decision))
                                         :finalize
                                         (if generated-response-call? :default :finalize))
                             :default)
                updated-messages-with-hint (conj updated-messages {:role "system"
                                                                   :content active-hint})]
            (skills/success-result
              {:messages-out updated-messages-with-hint
               :workspace-out workspace
               :next-phase next-phase
               :finalized? false}
              {}))

          :else
          (skills/success-result
            {:messages-out updated-messages
             :workspace-out workspace
             :next-phase :default
             :finalized? false}
            {}))))))

(def agent-bundle-record-and-route-skill
  {:metadata agent-bundle-record-and-route-metadata
   :execute execute-agent-bundle-record-and-route})

;; =============================================================================
;; Inner sub-graph definition
;; =============================================================================

(def agent-iteration-bundled-graph
  "One iteration of the bundled inner sub-graph.

   :pick-state → :llm-and-tools → :evaluate-evidence
                → :sufficiency-gates → :record-and-route

   The :record-and-route step's outputs (:messages-out / :workspace-out /
   :next-phase / :finalized?) are what the outer :react :loop step reads
   to decide whether to iterate."
  {:id :agent-iteration-bundled
   :name "Agent iteration (bundled)"
   :description "One iteration of the agent ReAct loop, expressed as four bundled skills."
   :inputs [:messages-init :workspace-init :ambient-ctx :tools
            :model :temperature :iter-prev :iteration :max-iterations]
   :outputs [:messages-out :workspace-out :next-phase :finalized?
             :terminal-state :response :clarification-request]
   :steps
   [{:id :pick-state
     :skill :builtin/agent-iter-state
     :inputs {:messages-init :$messages-init
              :workspace-init :$workspace-init
              :iter-prev :$iter-prev}}

    {:id :llm-and-tools
     :skill :builtin/agent-bundle-llm-and-tools
     :inputs {:messages-in [:pick-state :messages-in]
              :workspace-in [:pick-state :workspace-in]
              :phase [:pick-state :phase]
              :tools :$tools
              :ambient-ctx :$ambient-ctx
              :model :$model
              :temperature :$temperature
              :iteration :$iteration
              :max-iterations :$max-iterations}}

    {:id :evaluate-evidence
     :skill :builtin/agent-bundle-evaluate-evidence
     :inputs {:workspace-after-tools [:llm-and-tools :workspace-after-tools]
              :tool-call-results [:llm-and-tools :tool-call-results]
              :ambient-ctx :$ambient-ctx
              :iteration :$iteration}}

    {:id :sufficiency-gates
     :skill :builtin/agent-bundle-sufficiency-gates
     :inputs {:workspace-with-signals [:evaluate-evidence :workspace-with-signals]
              :tool-call-results-after-hint [:evaluate-evidence :tool-call-results-after-hint]
              :shadow-decision [:evaluate-evidence :shadow-decision]
              :generated-response-call? [:evaluate-evidence :generated-response-call?]
              :ambient-ctx :$ambient-ctx
              :model :$model
              :iteration :$iteration}}

    {:id :record-and-route
     :skill :builtin/agent-bundle-record-and-route
     :inputs {:workspace-after-gates [:sufficiency-gates :workspace-after-gates]
              :tool-call-results-final [:sufficiency-gates :tool-call-results-final]
              :active-decision [:sufficiency-gates :active-decision]
              :active-hint [:sufficiency-gates :active-hint]
              :generated-response-call? [:evaluate-evidence :generated-response-call?]
              :messages-in [:pick-state :messages-in]
              :assistant-msg [:llm-and-tools :assistant-msg]
              :llm-error [:llm-and-tools :llm-error]
              :no-tool-calls? [:llm-and-tools :no-tool-calls?]
              :direct-response [:llm-and-tools :direct-response]
              :ambient-ctx :$ambient-ctx
              :iteration :$iteration
              :model :$model
              :temperature :$temperature}}]})

(def agent-iteration-bundled-skill-graph
  (templates/make-skill-graph
    :builtin/agent-iteration-bundled
    "Agent iteration (bundled inner sub-graph)"
    "Phase 2.5.C — four-bundle inner sub-graph wired up for the bundled outer agent-rag-graph."
    agent-iteration-bundled-graph
    {:version "1.0.0"
     :tags #{:agent :react :graph-cutover :bundled}}))

;; =============================================================================
;; Registration
;; =============================================================================

(defn register!
  "Register the four bundle skills plus the inner sub-graph. Idempotent.
   Call from digdir.skills.init/initialize! after agent-graphs/register!."
  []
  (skills/register-skill! agent-bundle-llm-and-tools-skill)
  (skills/register-skill! agent-bundle-evaluate-evidence-skill)
  (skills/register-skill! agent-bundle-sufficiency-gates-skill)
  (skills/register-skill! agent-bundle-record-and-route-skill)
  (templates/register-skill-graph! agent-iteration-bundled-skill-graph))

(ns digdir.skills.builtin.agent.iteration-faithful
  "Phase 2.5.D — Faithful inner sub-graph for the agent ReAct loop.

   One iteration is expressed as ten registered skills, each carrying a
   single logical responsibility. This trades brevity for observability:
   every phase (LLM call, tool dispatch, evidence eval, shadow gate,
   range-read hint, sufficiency gate, response-validation gate, record-turn,
   route computation, route dispatch) becomes a separately invocable
   skill that 2.5.F can A/B replace independently if the eval gate
   suggests doing so.

   Pipeline:
     :pick-state                    (reuses :builtin/agent-iter-state)
   → :llm                           (LLM call + parse)
   → :tools                         (foreach-style dispatch over tool_calls)
   → :evaluate-pending-reads        (end-of-iter read-signal LLM call)
   → :shadow-gate                   (aggregate read-signals → shadow decision)
   → :range-read-hint               (nudge read_chunks tool-result)
   → :sufficiency-gate              (sufficiency LLM gate)
   → :response-validation-gate      (response-validation LLM gate)
   → :record-turn                   (trace + event emission)
   → :compute-active-decision       (derive :strategy keyword)
   → :route                         (:select on :strategy → branch skill)

   Compared to :bundled (2.5.C), the same total work runs but exposes
   each phase to the graph runner. The eval gate (2.5.E) compares
   :bundled and :faithful against :imperative on the public-docs
   suite; 2.5.F picks the winner."
  (:require [digdir.rag.skills.core :as skills]
            [digdir.skills.builtin.agent.loop :as agent-loop]
            [digdir.skills.builtin.agent.sufficiency :as sufficiency]
            [digdir.skills.builtin.agent.tools :as tools]
            [digdir.skills.builtin.agent.workspace :as workspace]
            [digdir.skills.events :as events]
            [digdir.skills.templates.core :as templates]
            [clojure.string :as str]))

;; =============================================================================
;; :builtin/agent-llm-call
;; =============================================================================

(def agent-llm-call-metadata
  {:skill-id :builtin/agent-llm-call
   :name "Agent: LLM call"
   :description "Run a single LLM round-trip with the iteration's messages and tools-for-phase. Records :agent-llm stage timing and emits :agent-iteration-started + :agent-thinking events. LLM exceptions surface as :llm-error in outputs (no throw)."
   :category :orchestration
   :inputs [:messages-in :workspace-in :phase :tools :ambient-ctx
            :model :temperature :iteration :max-iterations]
   :outputs [:workspace-after-llm :assistant-msg :normalized-tool-calls
             :finish-reason :llm-error :message-content]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :graph-cutover :faithful}})

(defn execute-agent-llm-call
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
                                        ;; 6-arity: see #148.
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
          {:workspace-after-llm workspace
           :assistant-msg nil
           :normalized-tool-calls []
           :finish-reason nil
           :llm-error err-msg
           :message-content nil}
          {}))
      (let [choice (-> response :choices first)
            message (:message choice)
            finish-reason (:finish_reason choice)
            tool-calls (:tool_calls message)
            normalized-tool-calls (when (seq tool-calls)
                                    (mapv tools/normalize-tool-call-for-request tool-calls))
            content (or (:content message) "")
            _ (when-let [c (not-empty (str/trim content))]
                (events/emit-progress! progress-fn
                                       (events/agent-thinking iteration c)))]
        (skills/success-result
          {:workspace-after-llm workspace
           :assistant-msg (cond-> {:role "assistant" :content content}
                            (seq normalized-tool-calls)
                            (assoc :tool_calls normalized-tool-calls))
           :normalized-tool-calls (or normalized-tool-calls [])
           :finish-reason finish-reason
           :llm-error nil
           :message-content content}
          {})))))

(def agent-llm-call-skill
  {:metadata agent-llm-call-metadata
   :execute execute-agent-llm-call})

;; =============================================================================
;; :builtin/agent-tools
;; =============================================================================

(def agent-tools-metadata
  {:skill-id :builtin/agent-tools
   :name "Agent: tool dispatch"
   :description "Sequentially dispatch the LLM's normalized tool calls via :builtin/agent-tool-call. Threads workspace through the reduce so tool-call ordering matters (search before read, etc.). When no tool calls fired (or finish-reason was 'stop' without tool_calls), this is a no-op that passes workspace through."
   :category :orchestration
   :inputs [:workspace-after-llm :normalized-tool-calls :finish-reason
            :ambient-ctx :iteration :llm-error]
   :outputs [:workspace-after-tools :tool-call-results :no-tool-calls?
             :direct-response]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :graph-cutover :faithful}})

(defn execute-agent-tools
  [{:keys [inputs]}]
  (let [{:keys [workspace-after-llm normalized-tool-calls finish-reason
                ambient-ctx iteration llm-error]} inputs
        progress-fn (get-in ambient-ctx [:opts :progress-fn])
        has-tool-calls? (and (seq normalized-tool-calls)
                             (or (= finish-reason "tool_calls")
                                 (= finish-reason "stop")))
        no-tool-calls? (and (nil? llm-error) (not has-tool-calls?))]
    (cond
      llm-error
      (skills/success-result
        {:workspace-after-tools workspace-after-llm
         :tool-call-results []
         :no-tool-calls? false
         :direct-response nil}
        {})

      no-tool-calls?
      ;; LLM produced text-only response; pass through. :direct-response
      ;; carries the message content that :record-turn / :route will
      ;; surface as the terminal answer.
      (skills/success-result
        {:workspace-after-tools workspace-after-llm
         :tool-call-results []
         :no-tool-calls? true
         :direct-response ""}  ;; Note: actual content lives in assistant-msg
        {})

      :else
      (let [workspace (assoc workspace-after-llm :current-iteration iteration)
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
           :no-tool-calls? false
           :direct-response nil}
          {})))))

(def agent-tools-skill
  {:metadata agent-tools-metadata
   :execute execute-agent-tools})

;; =============================================================================
;; :builtin/agent-evaluate-pending-reads
;; =============================================================================

(def agent-evaluate-pending-reads-metadata
  {:skill-id :builtin/agent-evaluate-pending-reads
   :name "Agent: evaluate pending reads"
   :description "Run the end-of-iteration read-signal evaluation over any chunks queued during this iteration's reads. Triggers a single LLM call collapsing N per-iteration read evaluations into one. No-op when nothing was queued."
   :category :orchestration
   :inputs [:workspace-after-tools :ambient-ctx :iteration]
   :outputs [:workspace-after-signals]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :graph-cutover :faithful}})

(defn execute-agent-evaluate-pending-reads
  [{:keys [inputs]}]
  (let [{:keys [workspace-after-tools ambient-ctx iteration]} inputs
        query-text (or (:query ambient-ctx) "")
        workspace (workspace/evaluate-pending-reads
                    workspace-after-tools ambient-ctx iteration query-text)]
    (skills/success-result {:workspace-after-signals workspace} {})))

(def agent-evaluate-pending-reads-skill
  {:metadata agent-evaluate-pending-reads-metadata
   :execute execute-agent-evaluate-pending-reads})

;; =============================================================================
;; :builtin/agent-shadow-gate
;; =============================================================================

(def agent-shadow-gate-metadata
  {:skill-id :builtin/agent-shadow-gate
   :name "Agent: shadow gate"
   :description "Compute the read-signal-derived shadow sufficiency decision and identify whether the iteration included a generate_response call. Filters to keep only 'interesting' decisions (gen-response, sufficient, re-search, ask-clarification) so subsequent gates can skip when not actionable."
   :category :orchestration
   :inputs [:workspace-after-signals :tool-call-results :ambient-ctx]
   :outputs [:shadow-decision :generated-response-call?]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :graph-cutover :faithful}})

(defn execute-agent-shadow-gate
  [{:keys [inputs]}]
  (let [{:keys [workspace-after-signals tool-call-results ambient-ctx]} inputs
        generated-response-call? (boolean
                                   (agent-loop/last-tool-result tool-call-results
                                                                "generate_response"))
        shadow-gate-raw-decision
        (when (agent-loop/should-run-sufficiency-gate? tool-call-results ambient-ctx)
          (agent-loop/shadow-gate-decision workspace-after-signals))
        shadow-decision
        (when (and shadow-gate-raw-decision
                   (or generated-response-call?
                       (= :sufficient (:status shadow-gate-raw-decision))
                       (contains? #{:re-search :ask-clarification}
                                  (:suggested-strategy shadow-gate-raw-decision))))
          shadow-gate-raw-decision)]
    (skills/success-result
      {:shadow-decision shadow-decision
       :generated-response-call? generated-response-call?}
      {})))

(def agent-shadow-gate-skill
  {:metadata agent-shadow-gate-metadata
   :execute execute-agent-shadow-gate})

;; =============================================================================
;; :builtin/agent-range-read-hint
;; =============================================================================

(def agent-range-read-hint-metadata
  {:skill-id :builtin/agent-range-read-hint
   :name "Agent: range-read hint"
   :description "When the iteration just performed a chunk-ids read and the local read-signal came back with a gap, detect whether the doc has unread adjacent chunks. If so, append a copy-pasteable range-read tool-call to the read_chunks tool-result so the next LLM iteration expands within the same doc instead of starting a new search."
   :category :orchestration
   :inputs [:workspace-after-signals :tool-call-results :iteration]
   :outputs [:workspace-after-hint :tool-call-results-after-hint]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :graph-cutover :faithful}})

(defn execute-agent-range-read-hint
  [{:keys [inputs]}]
  (let [{:keys [workspace-after-signals tool-call-results iteration]} inputs
        read-chunks-called? (some #(= "read_chunks" (:tool %)) tool-call-results)
        last-read-signal-status (get-in workspace-after-signals
                                        [:last-read-signal :status])
        candidate
        (when (and read-chunks-called?
                   (contains? #{:gap-remaining :unclear :conflicting}
                              last-read-signal-status))
          (workspace/range-read-suggestion workspace-after-signals))
        hint-text (some-> candidate workspace/range-read-hint-text)
        tool-call-results-after-hint
        (if hint-text
          (let [injected? (atom false)]
            (mapv (fn [tcr]
                    (if (and (not @injected?)
                             (= "read_chunks" (:tool tcr)))
                      (do (reset! injected? true)
                          (update tcr :result-summary #(str % hint-text)))
                      tcr))
                  tool-call-results))
          tool-call-results)
        workspace (if candidate
                    (workspace/record-stage-timing
                      workspace-after-signals
                      {:stage :range-read-hint
                       :iteration iteration
                       :duration-ms 0
                       :status :ok
                       :detail (str "doc_num=" (:doc-num candidate)
                                    " unread=" (:unread-indices candidate)
                                    " read=" (:read-indices candidate))})
                    workspace-after-signals)]
    (skills/success-result
      {:workspace-after-hint workspace
       :tool-call-results-after-hint tool-call-results-after-hint}
      {})))

(def agent-range-read-hint-skill
  {:metadata agent-range-read-hint-metadata
   :execute execute-agent-range-read-hint})

;; =============================================================================
;; :builtin/agent-sufficiency-gate
;; =============================================================================

(def agent-sufficiency-gate-metadata
  {:skill-id :builtin/agent-sufficiency-gate
   :name "Agent: sufficiency gate"
   :description "Run the sufficiency-gate LLM call with keepability guards. Applies hint text to generate_response tool-result when the decision is :not-sufficient. Skipped when the shadow gate already produced a decision or when the gate output would be discarded."
   :category :orchestration
   :inputs [:workspace-after-hint :tool-call-results-after-hint
            :shadow-decision :generated-response-call?
            :ambient-ctx :model :iteration]
   :outputs [:workspace-after-sufficiency :sufficiency-decision
             :sufficiency-hint :tool-call-results-with-sufficiency-hint]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :graph-cutover :faithful}})

(defn execute-agent-sufficiency-gate
  [{:keys [inputs]}]
  (let [{:keys [workspace-after-hint tool-call-results-after-hint
                shadow-decision generated-response-call?
                ambient-ctx model iteration]} inputs
        query-text (or (:query ambient-ctx) "")
        opts (:opts ambient-ctx)
        sufficiency-llm-fn (:sufficiency-llm-fn opts)
        sufficiency-llm-temperature (:sufficiency-llm-temperature opts)
        evidence-summary
        (when (and (agent-loop/should-run-sufficiency-gate?
                     tool-call-results-after-hint ambient-ctx)
                   (nil? shadow-decision))
          (sufficiency/build-evidence-summary workspace-after-hint query-text))
        has-unread-evidence? (when evidence-summary
                               (or (seq (:unread-chunk-ids evidence-summary))
                                   (some? (:unread-range evidence-summary))))
        keepable? (when evidence-summary
                    (or generated-response-call?
                        (not has-unread-evidence?)))
        gate-start (when keepable? (System/currentTimeMillis))
        decision
        (or shadow-decision
            (when keepable?
              (let [d (-> (sufficiency/evaluate-sufficiency
                            query-text evidence-summary
                            {:llm-fn sufficiency-llm-fn
                             :model model
                             :temperature sufficiency-llm-temperature})
                          (assoc :query query-text
                                 :source :llm-sufficiency-gate))
                    d (agent-loop/enforce-sufficiency-limit workspace-after-hint d)]
                (when (or generated-response-call?
                          (and (= :sufficient (:status d))
                               (not has-unread-evidence?)))
                  d))))
        gate-duration-ms (when gate-start (- (System/currentTimeMillis) gate-start))
        skipped-unkeepable? (and evidence-summary (not keepable?))
        hint-evidence-summary
        (when decision
          (or evidence-summary
              (sufficiency/build-evidence-summary workspace-after-hint query-text)))
        hint (when decision
               (agent-loop/gate-system-hint query-text decision hint-evidence-summary))
        tool-call-results
        (if (and decision
                 (not= :sufficient (:status decision))
                 generated-response-call?)
          (mapv (fn [tcr]
                  (if (= "generate_response" (:tool tcr))
                    (assoc tcr :result-summary hint)
                    tcr))
                tool-call-results-after-hint)
          tool-call-results-after-hint)
        workspace (cond-> workspace-after-hint
                    decision (workspace/record-sufficiency-decision
                               (assoc decision :message hint))
                    gate-duration-ms
                    (workspace/record-stage-timing
                      {:stage :sufficiency-gate
                       :iteration iteration
                       :duration-ms gate-duration-ms
                       :status (if decision :ok :skipped)})
                    skipped-unkeepable?
                    (workspace/record-stage-timing
                      {:stage :sufficiency-gate
                       :iteration iteration
                       :duration-ms 0
                       :status :skipped
                       :detail "unkeepable: unread evidence + no generate_response call"}))]
    (skills/success-result
      {:workspace-after-sufficiency workspace
       :sufficiency-decision decision
       :sufficiency-hint hint
       :tool-call-results-with-sufficiency-hint tool-call-results}
      {})))

(def agent-sufficiency-gate-skill
  {:metadata agent-sufficiency-gate-metadata
   :execute execute-agent-sufficiency-gate})

;; =============================================================================
;; :builtin/agent-response-validation-gate
;; =============================================================================

(def agent-response-validation-gate-metadata
  {:skill-id :builtin/agent-response-validation-gate
   :name "Agent: response-validation gate"
   :description "Run the post-generate response-validation LLM call. Only fires when (a) a generate_response call ran this iteration, (b) sufficiency-gate is nil or :sufficient, (c) the active query intent triggers response-validation (broad answer types or non-confident read signals)."
   :category :orchestration
   :inputs [:workspace-after-sufficiency :tool-call-results-with-sufficiency-hint
            :sufficiency-decision :generated-response-call?
            :ambient-ctx :model :iteration]
   :outputs [:workspace-after-validation :response-validation-decision
             :response-validation-hint :tool-call-results-final]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :graph-cutover :faithful}})

(defn execute-agent-response-validation-gate
  [{:keys [inputs]}]
  (let [{:keys [workspace-after-sufficiency tool-call-results-with-sufficiency-hint
                sufficiency-decision generated-response-call?
                ambient-ctx model iteration]} inputs
        query-text (or (:query ambient-ctx) "")
        opts (:opts ambient-ctx)
        sufficiency-llm-fn (:sufficiency-llm-fn opts)
        sufficiency-llm-temperature (:sufficiency-llm-temperature opts)
        evidence-summary
        (when (and generated-response-call?
                   (or (nil? sufficiency-decision)
                       (= :sufficient (:status sufficiency-decision)))
                   (agent-loop/should-run-response-validation? workspace-after-sufficiency))
          (sufficiency/build-evidence-summary workspace-after-sufficiency query-text))
        gate-start (when evidence-summary (System/currentTimeMillis))
        decision
        (when evidence-summary
          (let [d (-> (sufficiency/evaluate-sufficiency
                        query-text evidence-summary
                        {:llm-fn sufficiency-llm-fn
                         :model model
                         :temperature sufficiency-llm-temperature})
                      (assoc :query query-text
                             :source :response-validation))]
            (agent-loop/enforce-sufficiency-limit workspace-after-sufficiency d)))
        gate-duration-ms (when gate-start (- (System/currentTimeMillis) gate-start))
        hint (when decision
               (agent-loop/response-validation-hint query-text decision evidence-summary))
        tool-call-results
        (if (and decision
                 (not= :sufficient (:status decision))
                 generated-response-call?)
          (mapv (fn [tcr]
                  (if (= "generate_response" (:tool tcr))
                    (assoc tcr :result-summary hint)
                    tcr))
                tool-call-results-with-sufficiency-hint)
          tool-call-results-with-sufficiency-hint)
        workspace (cond-> workspace-after-sufficiency
                    decision (workspace/record-response-validation
                               (assoc decision :message hint))
                    gate-duration-ms
                    (workspace/record-stage-timing
                      {:stage :response-validation
                       :iteration iteration
                       :duration-ms gate-duration-ms
                       :status (if decision :ok :skipped)}))]
    (skills/success-result
      {:workspace-after-validation workspace
       :response-validation-decision decision
       :response-validation-hint hint
       :tool-call-results-final tool-call-results}
      {})))

(def agent-response-validation-gate-skill
  {:metadata agent-response-validation-gate-metadata
   :execute execute-agent-response-validation-gate})

;; =============================================================================
;; :builtin/agent-record-turn
;; =============================================================================

(def agent-record-turn-metadata
  {:skill-id :builtin/agent-record-turn
   :name "Agent: record turn"
   :description "Append the iteration's turn (reasoning + trace entries + budget snapshot) to workspace iteration-history. Emits :agent-turn-completed event with stage-timings for the current iteration."
   :category :orchestration
   :inputs [:workspace-after-validation :tool-call-results-final
            :assistant-msg :iteration :ambient-ctx]
   :outputs [:workspace-after-record :trace-entries]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :graph-cutover :faithful}})

(defn execute-agent-record-turn
  [{:keys [inputs]}]
  (let [{:keys [workspace-after-validation tool-call-results-final
                assistant-msg iteration ambient-ctx]} inputs
        progress-fn (get-in ambient-ctx [:opts :progress-fn])
        trace-entries (mapv (comp agent-loop/enriched-tool-call-trace-entry
                                  #(dissoc % :tool-call-id))
                            tool-call-results-final)
        workspace (workspace/record-turn workspace-after-validation iteration
                                         (:content assistant-msg) trace-entries)
        iteration-stage-timings (->> (:stage-timings workspace)
                                     (filter #(= iteration (:iteration %)))
                                     vec)]
    (events/emit-progress! progress-fn
                           (assoc (events/agent-turn-completed
                                    iteration
                                    (when-let [r (:content assistant-msg)]
                                      (if (> (count r) 500) (subs r 0 500) r))
                                    (mapv agent-loop/truncate-result-summary-for-stream
                                          trace-entries))
                                  :stage-timings iteration-stage-timings))
    (skills/success-result
      {:workspace-after-record workspace
       :trace-entries trace-entries}
      {})))

(def agent-record-turn-skill
  {:metadata agent-record-turn-metadata
   :execute execute-agent-record-turn})

;; =============================================================================
;; :builtin/agent-compute-route
;; =============================================================================

(def agent-compute-route-metadata
  {:skill-id :builtin/agent-compute-route
   :name "Agent: compute route strategy"
   :description "Derive the :strategy keyword the :route step's :select branches dispatch on. Resolves active-decision = (or response-validation-decision sufficiency-decision), determines whether the LLM produced an early-exit (llm-error or no-tool-calls), and maps everything to one of :error / :direct-response / :clarify / :synthesize-and-finalize / :accept-already-generated / :finalize-with-uncertainty / :continue."
   :category :orchestration
   :inputs [:workspace-after-record :sufficiency-decision
            :response-validation-decision :generated-response-call?
            :tool-call-results-final :llm-error :no-tool-calls?
            :sufficiency-hint :response-validation-hint]
   :outputs [:strategy :active-decision :active-hint :generated-response
             :generated-needs-retry?]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :graph-cutover :faithful}})

(defn execute-agent-compute-route
  [{:keys [inputs]}]
  (let [{:keys [workspace-after-record sufficiency-decision
                response-validation-decision generated-response-call?
                tool-call-results-final llm-error no-tool-calls?
                sufficiency-hint response-validation-hint]} inputs
        active-decision (or response-validation-decision sufficiency-decision)
        active-hint (or response-validation-hint sufficiency-hint)
        generated-response (agent-loop/stored-workspace-response workspace-after-record)
        generated-needs-retry? (agent-loop/generated-response-needs-retry?
                                 workspace-after-record tool-call-results-final)
        strategy
        (cond
          llm-error :error
          no-tool-calls? :direct-response
          (and active-decision
               (= :ask-clarification (:suggested-strategy active-decision)))
          :clarify
          (and generated-response-call?
               (nil? active-decision)
               (not generated-needs-retry?)
               (not (str/blank? generated-response)))
          :accept-already-generated
          (and active-decision
               (= :finalize (:suggested-strategy active-decision))
               (not generated-response-call?))
          :synthesize-and-finalize
          (and active-decision
               (= :sufficient (:status active-decision))
               generated-response-call?
               (not generated-needs-retry?))
          :accept-already-generated
          (and active-decision
               (not= :sufficient (:status active-decision))
               (= :finalize (:suggested-strategy active-decision))
               generated-response-call?
               (not (str/blank? generated-response)))
          :finalize-with-uncertainty
          :else :continue)]
    (skills/success-result
      {:strategy strategy
       :active-decision active-decision
       :active-hint active-hint
       :generated-response generated-response
       :generated-needs-retry? generated-needs-retry?}
      {})))

(def agent-compute-route-skill
  {:metadata agent-compute-route-metadata
   :execute execute-agent-compute-route})

;; =============================================================================
;; Route-branch skills
;; =============================================================================

(def agent-route-error-metadata
  {:skill-id :builtin/agent-route-error
   :name "Agent route: LLM error"
   :description "Terminal branch when :llm-and-tools surfaced an exception. Packages :error / :terminal-state :error / :finalized? true so the loop exits."
   :category :orchestration
   :inputs [:workspace-after-record :llm-error]
   :outputs [:messages-out :workspace-out :next-phase :finalized?
             :terminal-state :response]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :graph-cutover :faithful :route-branch}})

(defn execute-agent-route-error
  [{:keys [inputs]}]
  (skills/success-result
    {:messages-out []
     :workspace-out (:workspace-after-record inputs)
     :next-phase nil
     :finalized? true
     :terminal-state :error
     :response (:llm-error inputs)}
    {}))

(def agent-route-error-skill
  {:metadata agent-route-error-metadata
   :execute execute-agent-route-error})

(def agent-route-direct-response-metadata
  {:skill-id :builtin/agent-route-direct-response
   :name "Agent route: direct LLM response"
   :description "Terminal branch when LLM emitted text-only response (no tool calls). The assistant-msg's content becomes the terminal answer."
   :category :orchestration
   :inputs [:workspace-after-record :assistant-msg :iteration :ambient-ctx]
   :outputs [:messages-out :workspace-out :next-phase :finalized?
             :terminal-state :response]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :graph-cutover :faithful :route-branch}})

(defn execute-agent-route-direct-response
  [{:keys [inputs]}]
  (let [{:keys [workspace-after-record assistant-msg iteration ambient-ctx]} inputs
        progress-fn (get-in ambient-ctx [:opts :progress-fn])
        response-text (or (:content assistant-msg) "")]
    (events/emit-progress! progress-fn
                           (events/agent-finalized iteration (count response-text)))
    (skills/success-result
      {:messages-out []
       :workspace-out workspace-after-record
       :next-phase nil
       :finalized? true
       :terminal-state :finalize
       :response response-text}
      {})))

(def agent-route-direct-response-skill
  {:metadata agent-route-direct-response-metadata
   :execute execute-agent-route-direct-response})

(def agent-route-clarify-metadata
  {:skill-id :builtin/agent-route-clarify
   :name "Agent route: clarify"
   :description "Terminal branch when active-decision suggests :ask-clarification. Builds clarification-request payload."
   :category :orchestration
   :inputs [:workspace-after-record :active-decision]
   :outputs [:messages-out :workspace-out :next-phase :finalized?
             :terminal-state :response :clarification-request]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :graph-cutover :faithful :route-branch}})

(defn execute-agent-route-clarify
  [{:keys [inputs]}]
  (let [{:keys [workspace-after-record active-decision]} inputs
        clarification (agent-loop/clarification-request active-decision)]
    (skills/success-result
      {:messages-out []
       :workspace-out workspace-after-record
       :next-phase nil
       :finalized? true
       :terminal-state :clarify
       :response (:question clarification)
       :clarification-request clarification}
      {})))

(def agent-route-clarify-skill
  {:metadata agent-route-clarify-metadata
   :execute execute-agent-route-clarify})

(def agent-route-synthesize-metadata
  {:skill-id :builtin/agent-route-synthesize
   :name "Agent route: synthesize and finalize"
   :description "Terminal branch when active-decision says :finalize but no generate_response call has run yet. Calls the synthesis tool directly to produce the answer, then packages the terminal envelope."
   :category :orchestration
   :inputs [:workspace-after-record :ambient-ctx :iteration]
   :outputs [:messages-out :workspace-out :next-phase :finalized?
             :terminal-state :response]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :graph-cutover :faithful :route-branch}})

(defn execute-agent-route-synthesize
  [{:keys [inputs]}]
  (let [{:keys [workspace-after-record ambient-ctx iteration]} inputs
        query-text (or (:query ambient-ctx) "")
        progress-fn (get-in ambient-ctx [:opts :progress-fn])
        synth-result (agent-loop/synthesize-and-finalize
                       query-text workspace-after-record ambient-ctx
                       progress-fn iteration)]
    (skills/success-result
      {:messages-out []
       :workspace-out (:workspace synth-result)
       :next-phase nil
       :finalized? true
       :terminal-state (:terminal-state synth-result)
       :response (:response synth-result)}
      {})))

(def agent-route-synthesize-skill
  {:metadata agent-route-synthesize-metadata
   :execute execute-agent-route-synthesize})

(def agent-route-accept-metadata
  {:skill-id :builtin/agent-route-accept
   :name "Agent route: accept already-generated"
   :description "Terminal branch for :accept-already-generated / :finalize-with-uncertainty strategies. The agent's workspace already has the generated response; emit the terminal envelope and the agent-finalized event."
   :category :orchestration
   :inputs [:workspace-after-record :generated-response :iteration :ambient-ctx]
   :outputs [:messages-out :workspace-out :next-phase :finalized?
             :terminal-state :response]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :graph-cutover :faithful :route-branch}})

(defn execute-agent-route-accept
  [{:keys [inputs]}]
  (let [{:keys [workspace-after-record generated-response iteration ambient-ctx]} inputs
        progress-fn (get-in ambient-ctx [:opts :progress-fn])
        response (or generated-response "")]
    (events/emit-progress! progress-fn
                           (events/agent-finalized iteration (count response)))
    (skills/success-result
      {:messages-out []
       :workspace-out workspace-after-record
       :next-phase nil
       :finalized? true
       :terminal-state :finalize
       :response response}
      {})))

(def agent-route-accept-skill
  {:metadata agent-route-accept-metadata
   :execute execute-agent-route-accept})

(def agent-route-continue-metadata
  {:skill-id :builtin/agent-route-continue
   :name "Agent route: continue to next iteration"
   :description "Non-terminal branch. Builds the next iteration's messages-in (assistant-msg + tool-result messages + optional system hint) and computes the next-phase."
   :category :orchestration
   :inputs [:workspace-after-record :messages-in :assistant-msg
            :tool-call-results-final :active-decision :active-hint
            :generated-response-call?]
   :outputs [:messages-out :workspace-out :next-phase :finalized?]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :graph-cutover :faithful :route-branch}})

(defn execute-agent-route-continue
  [{:keys [inputs]}]
  (let [{:keys [workspace-after-record messages-in assistant-msg
                tool-call-results-final active-decision active-hint
                generated-response-call?]} inputs
        tool-messages (mapv #(tools/build-tool-result-message (:tool-call-id %)
                                                              (:result-summary %))
                            tool-call-results-final)
        base-messages (into (conj (vec messages-in) assistant-msg) tool-messages)
        next-phase (if active-decision
                     (case (:suggested-strategy active-decision)
                       :finalize (if (= :sufficient (:status active-decision))
                                   :finalize
                                   (if generated-response-call? :default :finalize))
                       :default)
                     :default)
        updated-messages (if (and active-decision active-hint)
                           (conj base-messages {:role "system" :content active-hint})
                           base-messages)]
    (skills/success-result
      {:messages-out updated-messages
       :workspace-out workspace-after-record
       :next-phase next-phase
       :finalized? false}
      {})))

(def agent-route-continue-skill
  {:metadata agent-route-continue-metadata
   :execute execute-agent-route-continue})

;; =============================================================================
;; Inner sub-graph definition
;; =============================================================================

(def agent-iteration-faithful-graph
  "One iteration of the faithful inner sub-graph. Ten regular steps plus a
   :select route that dispatches to one of six terminal/continue branch
   skills."
  {:id :agent-iteration-faithful
   :name "Agent iteration (faithful)"
   :description "Fine-grained 10-step inner sub-graph for the agent ReAct loop."
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

    {:id :llm
     :skill :builtin/agent-llm-call
     :inputs {:messages-in [:pick-state :messages-in]
              :workspace-in [:pick-state :workspace-in]
              :phase [:pick-state :phase]
              :tools :$tools
              :ambient-ctx :$ambient-ctx
              :model :$model
              :temperature :$temperature
              :iteration :$iteration
              :max-iterations :$max-iterations}}

    {:id :tools
     :skill :builtin/agent-tools
     :inputs {:workspace-after-llm [:llm :workspace-after-llm]
              :normalized-tool-calls [:llm :normalized-tool-calls]
              :finish-reason [:llm :finish-reason]
              :ambient-ctx :$ambient-ctx
              :iteration :$iteration
              :llm-error [:llm :llm-error]}}

    {:id :evaluate-pending-reads
     :skill :builtin/agent-evaluate-pending-reads
     :inputs {:workspace-after-tools [:tools :workspace-after-tools]
              :ambient-ctx :$ambient-ctx
              :iteration :$iteration}}

    {:id :shadow-gate
     :skill :builtin/agent-shadow-gate
     :inputs {:workspace-after-signals [:evaluate-pending-reads :workspace-after-signals]
              :tool-call-results [:tools :tool-call-results]
              :ambient-ctx :$ambient-ctx}}

    {:id :range-read-hint
     :skill :builtin/agent-range-read-hint
     :inputs {:workspace-after-signals [:evaluate-pending-reads :workspace-after-signals]
              :tool-call-results [:tools :tool-call-results]
              :iteration :$iteration}}

    {:id :sufficiency-gate
     :skill :builtin/agent-sufficiency-gate
     :inputs {:workspace-after-hint [:range-read-hint :workspace-after-hint]
              :tool-call-results-after-hint [:range-read-hint :tool-call-results-after-hint]
              :shadow-decision [:shadow-gate :shadow-decision]
              :generated-response-call? [:shadow-gate :generated-response-call?]
              :ambient-ctx :$ambient-ctx
              :model :$model
              :iteration :$iteration}}

    {:id :response-validation-gate
     :skill :builtin/agent-response-validation-gate
     :inputs {:workspace-after-sufficiency [:sufficiency-gate :workspace-after-sufficiency]
              :tool-call-results-with-sufficiency-hint [:sufficiency-gate :tool-call-results-with-sufficiency-hint]
              :sufficiency-decision [:sufficiency-gate :sufficiency-decision]
              :generated-response-call? [:shadow-gate :generated-response-call?]
              :ambient-ctx :$ambient-ctx
              :model :$model
              :iteration :$iteration}}

    {:id :record-turn
     :skill :builtin/agent-record-turn
     :inputs {:workspace-after-validation [:response-validation-gate :workspace-after-validation]
              :tool-call-results-final [:response-validation-gate :tool-call-results-final]
              :assistant-msg [:llm :assistant-msg]
              :iteration :$iteration
              :ambient-ctx :$ambient-ctx}}

    {:id :compute-route
     :skill :builtin/agent-compute-route
     :inputs {:workspace-after-record [:record-turn :workspace-after-record]
              :sufficiency-decision [:sufficiency-gate :sufficiency-decision]
              :response-validation-decision [:response-validation-gate :response-validation-decision]
              :generated-response-call? [:shadow-gate :generated-response-call?]
              :tool-call-results-final [:response-validation-gate :tool-call-results-final]
              :llm-error [:llm :llm-error]
              :no-tool-calls? [:tools :no-tool-calls?]
              :sufficiency-hint [:sufficiency-gate :sufficiency-hint]
              :response-validation-hint [:response-validation-gate :response-validation-hint]}}

    {:id :route
     :select {:on [:compute-route :strategy]}
     :branches
     {:error {:do {:skill :builtin/agent-route-error
                   :inputs {:workspace-after-record [:record-turn :workspace-after-record]
                            :llm-error [:llm :llm-error]}}}

      :direct-response {:do {:skill :builtin/agent-route-direct-response
                             :inputs {:workspace-after-record [:record-turn :workspace-after-record]
                                      :assistant-msg [:llm :assistant-msg]
                                      :iteration :$iteration
                                      :ambient-ctx :$ambient-ctx}}}

      :clarify {:do {:skill :builtin/agent-route-clarify
                     :inputs {:workspace-after-record [:record-turn :workspace-after-record]
                              :active-decision [:compute-route :active-decision]}}}

      :synthesize-and-finalize {:do {:skill :builtin/agent-route-synthesize
                                     :inputs {:workspace-after-record [:record-turn :workspace-after-record]
                                              :ambient-ctx :$ambient-ctx
                                              :iteration :$iteration}}}

      :accept-already-generated {:do {:skill :builtin/agent-route-accept
                                      :inputs {:workspace-after-record [:record-turn :workspace-after-record]
                                               :generated-response [:compute-route :generated-response]
                                               :iteration :$iteration
                                               :ambient-ctx :$ambient-ctx}}}

      :finalize-with-uncertainty {:do {:skill :builtin/agent-route-accept
                                       :inputs {:workspace-after-record [:record-turn :workspace-after-record]
                                                :generated-response [:compute-route :generated-response]
                                                :iteration :$iteration
                                                :ambient-ctx :$ambient-ctx}}}

      :continue {:do {:skill :builtin/agent-route-continue
                      :inputs {:workspace-after-record [:record-turn :workspace-after-record]
                               :messages-in [:pick-state :messages-in]
                               :assistant-msg [:llm :assistant-msg]
                               :tool-call-results-final [:response-validation-gate :tool-call-results-final]
                               :active-decision [:compute-route :active-decision]
                               :active-hint [:compute-route :active-hint]
                               :generated-response-call? [:shadow-gate :generated-response-call?]}}}}}]})

(def agent-iteration-faithful-skill-graph
  (templates/make-skill-graph
    :builtin/agent-iteration-faithful
    "Agent iteration (faithful inner sub-graph)"
    "Phase 2.5.D — fine-grained 10-step inner sub-graph wired up for the faithful outer agent-rag-graph."
    agent-iteration-faithful-graph
    {:version "1.0.0"
     :tags #{:agent :react :graph-cutover :faithful}}))

;; =============================================================================
;; Registration
;; =============================================================================

(defn register!
  "Register the 10 step skills, 6 route-branch skills, and the inner
   sub-graph. Idempotent."
  []
  (skills/register-skill! agent-llm-call-skill)
  (skills/register-skill! agent-tools-skill)
  (skills/register-skill! agent-evaluate-pending-reads-skill)
  (skills/register-skill! agent-shadow-gate-skill)
  (skills/register-skill! agent-range-read-hint-skill)
  (skills/register-skill! agent-sufficiency-gate-skill)
  (skills/register-skill! agent-response-validation-gate-skill)
  (skills/register-skill! agent-record-turn-skill)
  (skills/register-skill! agent-compute-route-skill)
  (skills/register-skill! agent-route-error-skill)
  (skills/register-skill! agent-route-direct-response-skill)
  (skills/register-skill! agent-route-clarify-skill)
  (skills/register-skill! agent-route-synthesize-skill)
  (skills/register-skill! agent-route-accept-skill)
  (skills/register-skill! agent-route-continue-skill)
  (templates/register-skill-graph! agent-iteration-faithful-skill-graph))

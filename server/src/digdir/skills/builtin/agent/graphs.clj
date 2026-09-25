(ns digdir.skills.builtin.agent.graphs
  "Phase 2.5 — Agent ReAct loop as graph composition.

   Defines the orchestration skills that the bundled (2.5.C) and faithful
   (2.5.D) inner sub-graphs share, plus the two outer graphs that wrap
   them. The outer shape is identical for both variants:

       :setup → :react (:loop, inner :sub-graph) → :finalize

   Only the :react step's inner :sub-graph reference differs (bundled
   vs faithful). The inner sub-graphs themselves are registered in
   `digdir.skills.builtin.agent.iteration-bundled` and `…iteration-faithful`
   respectively (forthcoming).

   This namespace registers three orchestration skills shared across
   variants:

   - :builtin/agent-setup       — build initial messages + workspace +
                                  ambient-ctx for the loop's first iter.
   - :builtin/agent-iter-state  — first-step picker used inside every
                                  inner sub-graph; on iter 0 falls back
                                  to setup's initial state, otherwise
                                  threads the previous iteration's outputs.
   - :builtin/agent-finalize    — consume the :loop's collected iterations
                                  and exhaustion flag; produce response,
                                  trace, terminal-state. Runs the
                                  max-iter LLM fallback when needed.

   And two skill-graph templates:
   - :builtin/agent-rag-graph-bundled
   - :builtin/agent-rag-graph-faithful"
  (:require [clojure.string :as str]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.builtin.agent.loop :as agent-loop]
            [digdir.skills.builtin.agent.workspace :as workspace]
            [digdir.skills.builtin.agent.tools :as tools]
            [digdir.skills.events :as events]
            [digdir.skills.templates.core :as templates]))

;; =============================================================================
;; :builtin/agent-setup
;; =============================================================================

(def agent-setup-metadata
  {:skill-id :builtin/agent-setup
   :name "Agent setup"
   :description "Build initial messages, workspace, and ambient-ctx for the agent ReAct loop. Phase 2.5 — first step of :builtin/agent-rag-graph-{bundled,faithful}."
   :category :orchestration
   :inputs [:query :conversation-history :docs-collection :chunks-collection
            :phrases-collection :system-prompt :budget-limits
            :ambient-ctx-opts]
   :outputs [:messages-init :workspace-init :ambient-ctx :tools]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :orchestration :graph-cutover}})

(defn execute-agent-setup
  "Body for :builtin/agent-setup. Pure-shape: takes plain inputs, returns
   initial workspace value, initial messages, and the ambient-ctx the inner
   steps will need to dispatch tools and call sub-skills.

   The bundled/faithful inner sub-graphs both rely on ambient-ctx :opts
   carrying the same fns the imperative agentic-loop pulls from its opts:
   :sufficiency-llm-fn, :sufficiency-llm-temperature, :read-signals-llm-fn.
   `ambient-ctx-opts` from the caller is expected to already include these
   (or have defaults that resolve them); :setup mirrors the imperative
   loop's defaulting logic — pass-through if present, otherwise (partial
   agent-loop/call-llm tenant)."
  [{:keys [inputs]}]
  (let [{:keys [query conversation-history docs-collection chunks-collection
                phrases-collection system-prompt budget-limits
                ambient-ctx-opts]} inputs
        query-intent (workspace/infer-query-intent query conversation-history)
        workspace (-> (workspace/fresh-workspace)
                      (assoc :budget-limits budget-limits)
                      (workspace/record-query-intent query-intent))
        messages (agent-loop/build-initial-messages system-prompt query conversation-history)
        tenant (get-in ambient-ctx-opts [:opts :tenant])
        sufficiency-llm-fn (or (get-in ambient-ctx-opts [:opts :sufficiency-llm-fn])
                               (partial agent-loop/call-llm tenant))
        sufficiency-llm-temperature (or (get-in ambient-ctx-opts [:opts :sufficiency-llm-temperature])
                                        0.0)
        ambient-ctx (-> ambient-ctx-opts
                        (assoc :docs-collection docs-collection
                               :chunks-collection chunks-collection
                               :phrases-collection phrases-collection
                               :query query
                               :conversation-history conversation-history)
                        (assoc-in [:opts :sufficiency-llm-fn] sufficiency-llm-fn)
                        (assoc-in [:opts :sufficiency-llm-temperature] sufficiency-llm-temperature))
        tools (tools/agent-tool-definitions ambient-ctx)]
    (skills/success-result
      {:messages-init messages
       :workspace-init workspace
       :ambient-ctx ambient-ctx
       :tools tools}
      {})))

(def agent-setup-skill
  {:metadata agent-setup-metadata
   :execute execute-agent-setup})

;; =============================================================================
;; :builtin/agent-iter-state
;; =============================================================================

(def agent-iter-state-metadata
  {:skill-id :builtin/agent-iter-state
   :name "Agent iteration state picker"
   :description "First step of any inner agent-iteration sub-graph. On iter 0 (when :iter-prev is empty) emits the initial messages/workspace from :setup; on later iters threads the previous iteration's :messages-out / :workspace-out / :next-phase forward."
   :category :orchestration
   :inputs [:messages-init :workspace-init :iter-prev]
   :outputs [:messages-in :workspace-in :phase]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :orchestration :graph-cutover :loop-state}})

(defn execute-agent-iter-state
  "Body for :builtin/agent-iter-state. The runner exposes the previous
   iteration's outputs map under :$iter (default-empty `{}` on iter 0).
   This skill normalizes that into the :messages-in / :workspace-in /
   :phase the rest of the inner sub-graph consumes."
  [{:keys [inputs]}]
  (let [{:keys [messages-init workspace-init iter-prev]} inputs
        prev-messages (:messages-out iter-prev)
        prev-workspace (:workspace-out iter-prev)
        prev-phase (:next-phase iter-prev)]
    (skills/success-result
      {:messages-in (or prev-messages messages-init)
       :workspace-in (or prev-workspace workspace-init)
       :phase (or prev-phase :default)}
      {})))

(def agent-iter-state-skill
  {:metadata agent-iter-state-metadata
   :execute execute-agent-iter-state})

;; =============================================================================
;; :builtin/agent-finalize
;; =============================================================================

(def agent-finalize-metadata
  {:skill-id :builtin/agent-finalize
   :name "Agent finalize"
   :description "Pick the terminal response from the :react :loop's collected iterations. If the loop exhausted (max-iter without :finalized?), run the same fallback LLM call the imperative loop uses. Returns response + terminal-state + trace + final workspace."
   :category :orchestration
   :inputs [:iterations :exhausted? :ambient-ctx :messages-init
            :workspace-init :model :temperature]
   :outputs [:response :clarification-request :terminal-state :trace
             :chunks :search-attributions :workspace-final :exhausted?]
   :parameters {}
   :version "1.0.0"
   :tags #{:agent :orchestration :graph-cutover}})

(defn- last-finalized-iteration
  "Pick the last collected iteration; its outputs carry the terminal
   :response / :terminal-state / :clarification-request when the loop
   exited via :until-output :finalized? truthy."
  [iterations]
  (last iterations))

(defn- fallback-on-exhaustion
  "Replicates the max-iter fallback the imperative `agentic-loop` runs when
   no iteration set :finalized?. Pure-shape: takes workspace, returns
   workspace and a result map.

   `messages` MUST be the ACCUMULATED loop conversation (assistant turns + tool
   results = the retrieved context), NOT the bare initial messages. Re-asking
   without the gathered context makes a reasoning-class local model emit
   search/planning scaffolding (\"[SEARCH] Queries: …\", \"I must first find …\")
   instead of an answer — the exact garbage Sweep-1 saw on retrieval misses."
  [workspace messages ambient-ctx model temperature iteration]
  (let [progress-fn (get-in ambient-ctx [:opts :progress-fn])
        tenant (get-in ambient-ctx [:opts :tenant])
        fallback-response (some-> (:last-generated-response workspace) str not-empty)
        stored-response fallback-response
        usable-response (when (and fallback-response
                                   (not (:last-generate-insufficient-context workspace)))
                          fallback-response)
        fallback-msg {:role "user"
                      :content (str "[SYSTEM: You have used all available iterations. "
                                    "Based on everything you have gathered so far, provide your best answer now. "
                                    "If you found partial information, share it. Do not call any more tools.")}]
    (events/emit-progress! progress-fn
                           (events/agent-exhausted iteration (count (or (:iteration-history workspace) []))))
    (if usable-response
      (do
        (events/emit-progress! progress-fn
                               (events/agent-finalized iteration (count usable-response)))
        {:workspace workspace
         :response usable-response
         :terminal-state :finalize
         :exhausted? true})
      (try
        (let [fallback-with-nudge (conj (vec messages) fallback-msg)
              fallback-start (System/currentTimeMillis)
              ;; 6-arity, matching the imperative loop's twin of this
              ;; fallback (loop.clj): the nudge's answer is user-facing text,
              ;; so it streams like any other answer. See #148.
              llm-response (agent-loop/call-llm tenant fallback-with-nudge nil
                                                model temperature
                                                {:progress-fn progress-fn})
              fallback-duration-ms (- (System/currentTimeMillis) fallback-start)
              response-text (-> llm-response :choices first :message :content)
              workspace (workspace/record-stage-timing
                          workspace
                          ;; #25: this stage wraps call-llm, whose response is
                          ;; right here — it was the single largest item in
                          ;; other-ms purely because nobody read :usage off it.
                          ;; :usage-expected? makes a future absence legible
                          ;; rather than silently reclassifying the stage.
                          (cond-> {:stage :agent-final-llm
                                   :iteration iteration
                                   :duration-ms fallback-duration-ms
                                   :status :ok
                                   :usage-expected? true}
                            (:usage llm-response) (assoc :usage (:usage llm-response))))]
          (events/emit-progress! progress-fn
                                 (events/agent-finalized iteration (count (or response-text ""))))
          {:workspace workspace
           ;; `not-empty`: an empty-string content ("") is TRUTHY in Clojure, so a
           ;; bare `(or response-text …)` would let a blank final answer through —
           ;; which is exactly what reasoning-class local models return here on a
           ;; retrieval miss. Guard it so exhaustion always yields a real response.
           :response (or (not-empty response-text) "Could not determine an answer within the iteration limit.")
           :terminal-state :finalize
           :exhausted? true})
        (catch Exception e
          (if stored-response
            (do
              (events/emit-progress! progress-fn
                                     (events/agent-finalized iteration (count stored-response)))
              {:workspace workspace
               :response stored-response
               :terminal-state :finalize
               :exhausted? true})
            (do
              (events/emit-progress! progress-fn
                                     (events/emit-agent-error iteration
                                                              (.getMessage e)))
              {:workspace workspace
               :error (.getMessage e)
               :exhausted? true})))))))

(defn execute-agent-finalize
  "Body for :builtin/agent-finalize. Reads the collected iterations from
   the loop and either picks the terminal iteration's outputs or runs the
   exhaustion-fallback LLM call."
  [{:keys [inputs]}]
  (let [{:keys [iterations exhausted? ambient-ctx messages-init
                workspace-init model temperature]} inputs
        iteration-count (count iterations)
        last-iter (last-finalized-iteration iterations)
        workspace-after-loop (or (:workspace-out last-iter) workspace-init)
        ;; Ground the best-answer-now fallback in the ACCUMULATED loop conversation
        ;; (assistant turns + tool results carry the retrieved context). The bare
        ;; `messages-init` re-asks context-free → the model emits search/planning
        ;; scaffolding instead of an answer (Sweep-1 garbage on retrieval misses).
        ;; The terminal iteration's :messages-out ends with tool-result messages
        ;; (no dangling tool_calls), so appending the nudge is well-formed; empty
        ;; (error/no-tool-call terminals) falls back to the initial messages.
        fallback-messages (or (not-empty (:messages-out last-iter)) messages-init)]
    (if exhausted?
      (let [{:keys [workspace response terminal-state error]}
            (fallback-on-exhaustion workspace-after-loop fallback-messages ambient-ctx
                                    model temperature iteration-count)]
        (skills/success-result
          (cond-> {:terminal-state terminal-state
                   :trace (:iteration-history workspace)
                   :chunks (workspace/chunks-for-output workspace)
                   :search-attributions (:search-attributions workspace)
                   :workspace-final workspace
                   :exhausted? true}
            response (assoc :response response)
            error (assoc :response error
                         :terminal-state :error))
          {}))
      ;; Non-exhausted: an iteration set :finalized?. Normally its :response is
      ;; the answer — but reasoning-class local models routinely mark themselves
      ;; finalized while emitting a BLANK response (empty synthesis on a retrieval
      ;; miss, or a `stop`/`tool_calls` turn with no content). a738945 guarded only
      ;; the exhaustion path, so these "complete-but-empty" runs slipped through
      ;; (Sweep-1: ~28% of local-agent runs). When the finalized response is blank
      ;; AND it isn't a clarification request, recover through the SAME guarded
      ;; best-answer-now fallback exhaustion uses — so a :complete run never
      ;; returns an empty answer.
      (let [last-response (:response last-iter)
            clar (:clarification-request last-iter)]
        (if (and (str/blank? (str last-response)) (not clar))
          (let [{:keys [workspace response terminal-state error]}
                (fallback-on-exhaustion workspace-after-loop fallback-messages ambient-ctx
                                        model temperature iteration-count)]
            (skills/success-result
              (cond-> {:terminal-state (or terminal-state :finalize)
                       :trace (:iteration-history workspace)
                       :chunks (workspace/chunks-for-output workspace)
                       :search-attributions (:search-attributions workspace)
                       :workspace-final workspace
                       :exhausted? false
                       ;; provenance: this run finalized empty and was recovered,
                       ;; distinct from a genuine iteration-cap exhaustion.
                       :recovered-empty-finalize? true}
                response (assoc :response response)
                error (assoc :response error
                             :terminal-state :error))
              {}))
          (skills/success-result
            {:response last-response
             :clarification-request clar
             :terminal-state (:terminal-state last-iter)
             :trace (:iteration-history workspace-after-loop)
             :chunks (workspace/chunks-for-output workspace-after-loop)
             :search-attributions (:search-attributions workspace-after-loop)
             :workspace-final workspace-after-loop
             :exhausted? false}
            {}))))))

(def agent-finalize-skill
  {:metadata agent-finalize-metadata
   :execute execute-agent-finalize})

;; =============================================================================
;; Outer graph factory
;; =============================================================================

(defn make-outer-graph
  "Build the outer agent-rag-graph data for a given inner sub-graph id.
   Used by both bundled (2.5.C) and faithful (2.5.D) registrations so the
   outer shape stays in lockstep across variants.

   The outer graph is:
       :setup → :react (:loop with inner :sub-graph) → :finalize
   The :react :loop iterates the inner sub-graph until it emits
   :finalized? truthy, or until :max-iterations is reached."
  [graph-id inner-sub-graph-id]
  {:id graph-id
   :name "Agentic RAG (graph composition)"
   :description "ReAct-style agent expressed as a graph composition. The :react step is a :loop of an inner sub-graph; the inner sub-graph encodes one iteration's worth of LLM call + tool dispatch + gates + routing."
   :inputs [:query :conversation-history :docs-collection :chunks-collection
            :phrases-collection :system-prompt :budget-limits
            :ambient-ctx-opts :model :temperature :max-iterations]
   :outputs [:response :clarification-request :terminal-state :trace
             :chunks :search-attributions :workspace-final :exhausted?]
   :steps
   [{:id :setup
     :skill :builtin/agent-setup
     :inputs {:query :$query
              :conversation-history :$conversation-history
              :docs-collection :$docs-collection
              :chunks-collection :$chunks-collection
              :phrases-collection :$phrases-collection
              :system-prompt :$system-prompt
              :budget-limits :$budget-limits
              :ambient-ctx-opts :$ambient-ctx-opts}}

    {:id :react
     :inputs {:messages-init [:setup :messages-init]
              :workspace-init [:setup :workspace-init]
              :ambient-ctx [:setup :ambient-ctx]
              :tools [:setup :tools]
              :model :$model
              :temperature :$temperature}
     :loop {:max-iterations 10  ;; TODO 2.5.C/D — flow :$max-iterations into here
            :until-output :finalized?
            :iteration-as :iter
            :iteration-index-as :i}
     :collect-as :iterations
     :do {:sub-graph
          {:graph-id inner-sub-graph-id
           :inputs {:messages-init :$messages-init
                    :workspace-init :$workspace-init
                    :ambient-ctx :$ambient-ctx
                    :tools :$tools
                    :model :$model
                    :temperature :$temperature
                    :iter-prev :$iter
                    :iteration :$i}}}}

    {:id :finalize
     :skill :builtin/agent-finalize
     :inputs {:iterations [:react :iterations]
              :exhausted? [:react :exhausted?]
              :ambient-ctx [:setup :ambient-ctx]
              :messages-init [:setup :messages-init]
              :workspace-init [:setup :workspace-init]
              :model :$model
              :temperature :$temperature}}]})

;; =============================================================================
;; Skill-graph definitions — bundled + faithful
;; =============================================================================

(def agent-rag-graph-bundled
  (templates/make-skill-graph
    :builtin/agent-rag-graph-bundled
    "Agentic RAG (graph, bundled inner)"
    "Phase 2.5.C inner shape: per-iteration body grouped into 4 coarse-grained skills (:llm-and-tools / :evaluate-evidence / :sufficiency-gates / :record-and-route)."
    (make-outer-graph :builtin/agent-rag-graph-bundled
                      :builtin/agent-iteration-bundled)
    {:version "1.0.0"
     :tags #{:rag :agent :react :graph-cutover :bundled}
     :input-schema templates/agent-tool-input-schema}))

(def agent-rag-graph-faithful
  (templates/make-skill-graph
    :builtin/agent-rag-graph-faithful
    "Agentic RAG (graph, faithful inner)"
    "Phase 2.5.D inner shape: per-iteration body decomposed into 10 fine-grained steps, one per logical phase (:llm, :tools, :evaluate-pending-reads, :shadow-gate, :range-read-hint, :sufficiency-gate, :response-validation-gate, :record-turn, :compute-route, :route)."
    (make-outer-graph :builtin/agent-rag-graph-faithful
                      :builtin/agent-iteration-faithful)
    {:version "1.0.0"
     :tags #{:rag :agent :react :graph-cutover :faithful}
     :input-schema templates/agent-tool-input-schema}))

;; =============================================================================
;; Registration
;; =============================================================================

(defn register!
  "Register the three orchestration skills and the two outer skill-graphs.
   Idempotent — re-registering an already-present skill overwrites; same
   for skill-graphs.

   The inner sub-graphs are NOT registered here. They land in 2.5.C
   (bundled) and 2.5.D (faithful). Attempting to execute either outer
   graph before those land will fail with a clear
   `:sub-graph/graph-not-found` error from the runner."
  []
  (skills/register-skill! agent-setup-skill)
  (skills/register-skill! agent-iter-state-skill)
  (skills/register-skill! agent-finalize-skill)
  (templates/register-skill-graph! agent-rag-graph-bundled)
  (templates/register-skill-graph! agent-rag-graph-faithful))

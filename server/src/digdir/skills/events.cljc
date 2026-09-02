(ns digdir.skills.events
  "Shared execution event helpers for skills and Playground.")

(def skills-step->playground-stage
  {:plan :skills-query-planning
   :retrieve :skills-retrieval
   :rerank :skills-rerank
   :generate :skills-generating
   :agent :agent-iteration})

(def stage-labels
  {:init "Initializing"
   :query-relax "Plan Queries"
   :phrase-search "Semantic Search"
   :metadata-search "Metadata Search"
   :content-search "Content Search"
   :merge "Merge Results"
   :retrieve "Retrieve Chunks"
   :rerank "Rerank"
   :generate "Generate"
   :skills-init "Initialize Skills"
   :skills-query-planning "Plan Queries"
   :skills-retrieval "Retrieve"
   :skills-rerank "Rerank"
   :skills-generating "Generate"
   :agent-iteration "Agent Iterate"
   :agent-tool-call "Tool Call"
   :agent-tool-result "Tool Result"
   :agent-finalize "Finalize"})

(def stage-flows
  {:skills [:skills-init :skills-query-planning :skills-retrieval :skills-rerank :skills-generating]
   :agentic [:skills-init :skills-query-planning :agent-iteration :agent-tool-call :agent-tool-result :agent-finalize :skills-generating]
   :legacy [:skills-query-planning :rerank :skills-generating]})

(defn stage-label
  [stage]
  (or (get stage-labels stage)
      (some-> stage name)))

(def ^:private stage->flow-key
  (merge
   (zipmap (:skills stage-flows) (repeat :skills))
   (zipmap (:agentic stage-flows) (repeat :agentic))))

(defn stage-flow-key
  [stage]
  (get stage->flow-key stage :legacy))

(defn flow-steps
  [stage]
  (let [flow-key (stage-flow-key stage)]
    (mapv (fn [s]
            {:stage s
             :label (stage-label s)})
          (get stage-flows flow-key (:legacy stage-flows)))))

(defn emit-progress!
  "Best-effort progress callback for execution observability.

   Swallows callback failures so execution never fails because of telemetry."
  [progress-fn payload]
  (when (fn? progress-fn)
    (try
      (progress-fn payload)
      (catch #?(:clj Exception :cljs :default) _
        nil))))

(defn graph-completed
  [steps-executed duration-ms]
  {:event :graph/completed
   :steps-executed steps-executed
   :duration-ms duration-ms})

(defn request-started
  [request-id query]
  {:event :request/started
   :request-id request-id
   :query query})

(defn request-failed
  [error]
  {:event :request/failed
   :error error})

(defn response-chunk
  [delta]
  {:event :response/chunk
   :delta delta})

(defn response-finalized
  ([] {:event :response/finalized})
  ([payload]
   (assoc payload :event :response/finalized)))

(defn stage-started
  [stage-id label]
  {:event :stage/started
   :stage stage-id
   :label label})

(defn stage-completed
  ([stage-id label]
   {:event :stage/completed
    :stage stage-id
    :label label})
  ([stage-id label duration-ms]
   {:event :stage/completed
    :stage stage-id
    :label label
    :duration-ms duration-ms}))

(defn tool-called
  [tool-call]
  {:event :tool/call
   :tool-call tool-call})

(defn tool-result
  [tool-result]
  {:event :tool/result
   :tool-result tool-result})

(defn warning-raised
  [warning]
  {:event :warning/raised
   :warning warning})

(defn step-started
  [step-id skill-id]
  {:event :step/started
   :step-id step-id
   :skill-id skill-id})

(defn step-completed
  "Emit a step-completed event. `outputs` is optional — when supplied,
   it carries the step's full outputs map so playground / observability
   layers can render per-step structured detail (added 2026-05-19 for
   the self-improve graph variants which otherwise had no live data
   to feed the Phase C.5 enrichment-detail panels).

   The 3-arity form preserves backwards compat with callers that don't
   yet thread outputs through."
  ([step-id skill-id duration-ms]
   (step-completed step-id skill-id duration-ms nil))
  ([step-id skill-id duration-ms outputs]
   (cond-> {:event :step/completed
            :step-id step-id
            :skill-id skill-id
            :duration-ms duration-ms}
     (some? outputs) (assoc :outputs outputs))))

(defn step-skipped
  [step-id skill-id]
  {:event :step/skipped
   :step-id step-id
   :skill-id skill-id
   :duration-ms 0})

(defn step-defaulted
  [step-id skill-id duration-ms]
  {:event :step/defaulted
   :step-id step-id
   :skill-id skill-id
   :duration-ms duration-ms})

(defn step-failed
  [step-id skill-id duration-ms error]
  {:event :step/failed
   :step-id step-id
   :skill-id skill-id
   :duration-ms duration-ms
   :error error})

(defn agent-iteration-started
  [iteration max-iterations]
  {:event :agent/iteration-started
   :iteration iteration
   :max-iterations max-iterations})

(defn agent-thinking
  "Emitted right after the LLM returns its response, before any tool calls execute.
   Carries the LLM's reasoning (`:content message`) so the UI can surface it live
   while tools are still running."
  [iteration reasoning]
  {:event :agent/thinking
   :iteration iteration
   :reasoning reasoning})

(defn agent-tool-call
  [iteration tool args]
  {:event :agent/tool-call
   :iteration iteration
   :tool tool
   :args args})

(defn agent-tool-result
  [iteration tool ok? result-summary]
  {:event :agent/tool-result
   :iteration iteration
   :tool tool
   :ok? ok?
   :result-summary result-summary})

(defn agent-turn-completed
  [iteration reasoning tool-calls]
  {:event :agent/turn-completed
   :iteration iteration
   :reasoning reasoning
   :tool-calls tool-calls})

(defn agent-exhausted
  [iteration max-iterations]
  {:event :agent/exhausted
   :iteration iteration
   :max-iterations max-iterations})

(defn emit-agent-error
  [iteration error]
  {:event :agent/error
   :iteration iteration
   :error error})

(defn agent-finalized
  [iteration response-length]
  {:event :agent/finalized
   :iteration iteration
   :response-length response-length})

(def ^:private required-execution-event-keys
  {:request/started #{:request-id :query}
   :request/failed #{:error}
   :response/chunk #{:delta}
   :response/finalized #{}
   :stage/started #{:stage}
   :stage/completed #{:stage}
   :tool/call #{:tool-call}
   :tool/result #{:tool-result}
   :warning/raised #{:warning}
   :step/started #{:step-id}
   :step/completed #{:step-id :duration-ms}
   :step/skipped #{:step-id}
   :step/defaulted #{:step-id :duration-ms}
   :step/failed #{:step-id :duration-ms :error}
   :graph/completed #{:steps-executed :duration-ms}
   :agent/iteration-started #{:iteration :max-iterations}
   :agent/turn-completed #{:iteration :tool-calls}
   :agent/exhausted #{:iteration :max-iterations}
   :agent/finalized #{:iteration :response-length}
   :agent/error #{:iteration :error}})

(defn assert-canonical-execution-event!
  [event]
  (let [event-kind (:event event)
        required-keys (get required-execution-event-keys event-kind)]
    (when (nil? event-kind)
      (throw (ex-info "Canonical execution event is missing :event"
                      {:event event})))
    (when required-keys
      (let [missing (seq (remove #(contains? event %) required-keys))]
        (when missing
          (throw (ex-info "Canonical execution event is missing required keys"
                          {:event event
                           :missing-keys (vec missing)})))))
    event))

(defn normalize-execution-event
  "Normalize execution events onto the canonical :event key."
  [event]
  (-> (cond-> event
        (and (nil? (:event event)) (:type event))
        (assoc :event (:type event)))
      (assert-canonical-execution-event!)))

(defn progress->execution-events
  "Translate skill progress events into canonical Playground execution events."
  ([progress]
   (progress->execution-events progress {}))
  ([progress {:keys [step->stage]
              :or {step->stage skills-step->playground-stage}}]
   (let [{:keys [event step-id duration-ms]} progress
         mapped-stage (or (get step->stage step-id) step-id)]
     (case event
       :step/started
       [(stage-started mapped-stage (stage-label mapped-stage))]

       :step/completed
       [(stage-completed mapped-stage (stage-label mapped-stage) duration-ms)]

       :step/skipped
       [(step-skipped mapped-stage (:skill-id progress))]

       :step/defaulted
       [(step-defaulted mapped-stage (:skill-id progress) duration-ms)]

       :graph/completed
       [(graph-completed (:steps-executed progress) duration-ms)]

       :agent/tool-call
       [(tool-called {:tool (:tool progress)
                      :args (:args progress)
                      :iteration (:iteration progress)})]

       :agent/tool-result
       [(tool-result {:tool (:tool progress)
                      :summary (:result-summary progress)
                      :ok? (:ok? progress)
                      :iteration (:iteration progress)})]

       :agent/exhausted
       [(warning-raised {:code :agent-exhausted
                         :message "Agent reached max iterations"})]

       :agent/error
       [(request-failed (:error progress))]

       :agent/iteration-started
       [(agent-iteration-started (:iteration progress) (:max-iterations progress))]

       :agent/turn-completed
       [(agent-turn-completed (:iteration progress)
                              (:reasoning progress)
                              (:tool-calls progress))]

       :agent/finalized
       [(agent-finalized (:iteration progress) (:response-length progress))]

       []))))

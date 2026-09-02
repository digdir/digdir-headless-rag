(ns digdir.playground.chat-session-integration-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [digdir.playground.chat-session :as chat-session]))

(defn- assistant
  [state]
  (:assistant state))

(defn- load-fixture
  [name]
  (let [path (str "fixtures/playground/chat_session/" name ".edn")
        resource (io/resource path)]
    (when-not resource
      (throw (ex-info (str "Missing fixture: " path) {:path path})))
    (edn/read-string (slurp resource))))

(deftest prompt-to-successful-response
  (testing "User prompt transitions to completed assistant response"
    (let [state (chat-session/replay (load-fixture "01_prompt_success"))]
      (is (= :complete (:status state)))
      (is (= "What is Digdir?" (-> state :messages first :text)))
      (is (= :complete (-> (assistant state) :status)))
      (is (= "Digdir is a Norwegian public-sector digitalization agency."
             (-> (assistant state) :text))))))

(deftest streaming-progressive-updates-then-finalize
  (testing "Streaming chunks are appended progressively then finalized"
    (let [events (load-fixture "02_streaming_finalize")
          states (rest (reductions chat-session/apply-event (chat-session/initial-state) events))]
      (is (= "Part 1" (-> states second assistant :text)))
      (is (= "Part 1 + Part 2" (-> states (nth 2) assistant :text)))
      (is (= :complete (-> states last :status)))
      (is (= "Part 1 + Part 2" (-> states last assistant :text))))))

(deftest tool-timeline-accumulates-in-order
  (testing "Tool calls project into a stable derived timeline"
    (let [state (chat-session/replay (load-fixture "03_tool_timeline"))
          live-view (chat-session/execution-events->live-view (:events state))]
      (is (= 2 (count (:tool-timeline live-view))))
      (is (= "search_documents" (get-in live-view [:tool-timeline 0 :tool])))
      (is (= "rerank_results" (get-in live-view [:tool-timeline 1 :tool])))
      (is (= 0 (get-in live-view [:tool-timeline 0 :seq])))
      (is (= 1 (get-in live-view [:tool-timeline 1 :seq]))))))

(deftest citations-bind-to-used-chunks
  (testing "Citation indices map to used chunk/source metadata"
    (let [state (chat-session/replay (load-fixture "04_citations"))
          citation (-> state assistant :citations first)]
      (is (= 1 (:index citation)))
      (is (= "c1" (:chunk-id citation)))
      (is (= "Annual Report" (get-in citation [:source :title])))
      (is (= "Governance > Board" (get-in citation [:source :heading-line]))))))

(deftest auto-filter-diagnostics-surfaced
  (testing "Auto-filter metadata is normalized into retrieval filter entries"
    (let [state (chat-session/replay (load-fixture "05_auto_filter"))
          entries (get-in state [:diagnostics :retrieval-filters])]
      (is (= 1 (count entries)))
      (is (= :auto (get-in entries [0 :type])))
      (is (= true (get-in entries [0 :fallback])))
      (is (= "country" (get-in entries [0 :filter :fields 0 :field]))))))

(deftest insufficient-context-retry-fallback-flow
  (testing "Insufficient context and retry/fallback flags are tracked through completion"
    (let [state (chat-session/replay (load-fixture "06_insufficient_retry_fallback"))]
      (is (= :complete (:status state)))
      (is (= true (get-in state [:flags :insufficient-context?])))
      (is (= true (get-in state [:flags :fallback-used?])))
      (is (= true (get-in state [:flags :retried?])))
      (is (= "Fallback answer" (-> state assistant :text))))))

(deftest backend-tool-errors-propagate-to-terminal-state
  (testing "Backend/tool failure yields error terminal state and assistant error message"
    (let [state (chat-session/replay (load-fixture "07_error"))]
      (is (= :error (:status state)))
      (is (= "Typesense unavailable" (:error state)))
      (is (= :error (-> state assistant :status)))
      (is (= "Typesense unavailable" (-> state assistant :error))))))

(deftest branch-visibility-selection
  (testing "Visible message path follows active branch selection from fixture tree"
    (let [{:keys [messages active-branch-path]} (load-fixture "08_branching_tree")
          visible (chat-session/get-visible-messages messages active-branch-path)]
      (is (= ["u1" "a1" "u2b" "a2b"] (mapv :message/id visible)))
      (is (chat-session/has-branches? messages "a1")))))

(deftest execution-stream-view-normalization
  (testing "Execution payload is normalized into stream view model"
    (let [view (chat-session/execution-stream-view {:status :running
                                                    :stage :rerank
                                                    :streaming-content ""
                                                    :action-trace [{:id :rerank
                                                                    :kind :rerank
                                                                    :status :running}]
                                                    :error nil})]
      (is (= true (:running? view)))
      (is (= true (:waiting? view)))
      (is (= :rerank (:stage view)))
      (is (= [{:id :rerank :kind :rerank :status :running}]
             (:action-trace view)))
      (is (some #(= :rerank (:stage %)) (:steps view)))
      (is (= true (some :past? (:steps view))))
      (is (= 1 (count (filter :current? (:steps view))))))))

(deftest execution-stream-view-nil-and-non-running-states
  (testing "Nil and non-running execution payloads produce explicit idle/non-running models"
    (let [nil-view (chat-session/execution-stream-view nil)
          done-view (chat-session/execution-stream-view {:status :complete
                                                         :stage :init
                                                         :streaming-content ""
                                                         :error nil})]
      (is (= false (:has-execution? nil-view)))
      (is (= false (:running? nil-view)))
      (is (= false (:waiting? nil-view)))
      (is (= true (:has-execution? done-view)))
      (is (= false (:running? done-view)))
      (is (= false (:waiting? done-view))))))

(deftest multi-turn-replay-resets-transient-request-state
  (testing "Starting a new request clears transient state that should not leak across turns"
    (let [events [{:event :request/started :request-id "turn-1" :query "First"}
                  {:event :tool/call :tool-call {:tool "search_documents" :args {:queries ["one"]}}}
                  {:event :response/insufficient-context}
                  {:event :request/retry}
                  {:event :response/finalized :text "First answer" :diagnostics {:k 1}}
                  {:event :request/started :request-id "turn-2" :query "Second"}]
          state (chat-session/replay events)
          live-view (chat-session/execution-events->live-view (:events state))]
      (is (= :running (:status state)))
      (is (= "turn-2" (get-in state [:request :id])))
      (is (empty? (:tool-timeline live-view)))
      (is (nil? (:diagnostics state)))
      (is (= {:insufficient-context? false
              :fallback-used? false
              :retried? false}
             (:flags state))))))

(deftest finalize-response-explicit-text-overrides-stream-buffer
  (testing "Finalize uses explicit text when provided even if chunks already accumulated"
    (let [state (chat-session/replay
                 [{:event :request/started :request-id "req-override" :query "Q"}
                  {:event :response/chunk :delta "partial"}
                  {:event :response/finalized :text "final explicit" :diagnostics {}}])]
      (is (= "final explicit" (-> state assistant :text))))))

(deftest clarification-finalize-sets-interactive-terminal-state
  (testing "Clarification responses stay interactive instead of looking like a normal final answer"
    (let [state (chat-session/replay
                 [{:event :request/started :request-id "req-clarify" :query "Which Digdir report?"}
                  {:event :response/finalized
                   :status :needs_clarification
                   :text "Which Digdir report do you mean: 2022 or 2023?"
                   :clarification-request {:question "Which Digdir report do you mean: 2022 or 2023?"
                                           :options ["2022" "2023"]
                                           :context-summary "Several report years match the query."}
                   :diagnostics {}}])]
      (is (= :needs-clarification (:status state)))
      (is (= :needs-clarification (-> state assistant :status)))
      (is (= "Which Digdir report do you mean: 2022 or 2023?" (-> state assistant :text)))
      (is (= ["2022" "2023"]
             (get-in state [:assistant :clarification-request :options]))))))

(deftest stage-tool-and-warning-events-accumulate-progress-state
  (testing "Execution events project stage history, tool results, warnings, and progress counters"
    (let [events [{:event :request/started :request-id "req-live" :query "Q"}
                  {:event :stage/started :stage :skills-query-planning :label "Planning queries" :ts 1000}
                  {:event :tool/call :tool-call {:tool "search_documents" :args {:queries ["q"]}}}
                  {:event :tool/result :tool-result {:tool "search_documents" :summary "Found 12 chunks" :ok? true}}
                  {:event :warning/raised :warning {:code :fallback-used :message "Used broadened retrieval"}}
                  {:event :stage/completed :stage :skills-query-planning :label "Planning queries" :duration-ms 42 :ts 1042}]
          state (chat-session/replay events)
          live-view (chat-session/execution-events->live-view (:events state))]
      (is (= :skills-query-planning (get-in live-view [:progress :current-stage])))
      (is (= "Planning queries" (get-in live-view [:progress :current-stage-label])))
      (is (= 1 (get-in live-view [:progress :stage-count])))
      (is (= 1 (get-in live-view [:progress :tool-call-count])))
      (is (= 1 (get-in live-view [:progress :tool-result-count])))
      (is (= 1 (get-in live-view [:progress :warning-count])))
      (is (= 2 (count (:stage-history live-view))))
      (is (= :started (get-in live-view [:stage-history 0 :status])))
      (is (= :completed (get-in live-view [:stage-history 1 :status])))
      (is (= "search_documents" (get-in live-view [:tool-timeline 0 :tool])))
      (is (= "search_documents" (get-in live-view [:tool-results 0 :tool])))
      (is (= :fallback-used (get-in live-view [:warnings 0 :code]))))))

(deftest execution-events-live-view-projection
  (testing "Execution events project into summary, counters, and timeline"
    (let [events [{:event :request/started :query "Q"}
                  {:event :stage/started :stage :skills-query-planning :label "Planning queries"}
                  {:event :tool/call :tool-call {:tool "search_documents" :args {:queries ["q"]}}}
                  {:event :tool/result :tool-result {:tool "search_documents" :summary "Found 12 chunks" :ok? true}}
                  {:event :warning/raised :warning {:code :auto-filter-fallback :message "Fallback used"}}]
          view (chat-session/execution-events->live-view events)]
      (is (= "Fallback used" (:summary view)))
      (is (= :skills-query-planning (:current-stage view)))
      (is (= 1 (:tool-call-count view)))
      (is (= 1 (:tool-result-count view)))
      (is (= 1 (:warning-count view)))
      (is (= 5 (count (:timeline view)))))))

(deftest execution-stream-view-includes-event-derived-fields
  (testing "execution-stream-view surfaces event-derived live summary and timeline"
    (let [view (chat-session/execution-stream-view
                {:status :running
                 :stage :skills-query-planning
                 :streaming-content "partial"
                 :events [{:event :stage/started :stage :skills-query-planning :label "Planning queries"}
                          {:event :tool/call :tool-call {:tool "search_documents"}}]
                 :error nil})]
      (is (= true (:running? view)))
      (is (= "Calling search_documents" (:live-summary view)))
      (is (= 2 (count (:timeline view))))
      (is (= 1 (:tool-call-count view))))))

(deftest execution-stream-view-includes-live-timing-breakdown
  (testing "execution-stream-view combines graph-stage timings and live agent-stage timings"
    (let [view (chat-session/execution-stream-view
                {:status :running
                 :stage :skills-retrieval
                 :streaming-content ""
                 :events [{:event :stage/started
                           :stage :skills-query-planning
                           :label "Planning queries"
                           :ts 1000}
                          {:event :stage/completed
                           :stage :skills-query-planning
                           :label "Planning queries"
                           :duration-ms 42
                           :ts 1042}
                          {:event :stage/started
                           :stage :skills-retrieval
                           :label "Retrieve"
                           :ts 1043}]
                 :live-agent-stage-timings [{:stage :agent-llm
                                             :iteration 0
                                             :duration-ms 12
                                             :status :ok}
                                            {:stage :search
                                             :iteration 0
                                             :tool "search_documents"
                                             :sub-skill :builtin/retrieval
                                             :duration-ms 34
                                             :status :ok}]
                 :live-agent-trace [{:iteration 0
                                     :reasoning "Searching"
                                     :tool-calls [{:tool "search_documents"
                                                   :args-summary "{:queries [\"q\"]}"
                                                   :effective-parameters {:sub-skill :builtin/retrieval}
                                                   :stage :search
                                                   :sub-skill :builtin/retrieval
                                                   :duration-ms 34
                                                   :ok? true
                                                   :result-summary "Found 12 chunks"}]}]
                 :error nil})]
      (is (pos? (or (:elapsed-ms view) 0)))
      (is (= 2 (count (:live-agent-stage-timings view))))
      (is (= [{:source :graph
               :label "skills-query-planning"
               :duration-ms 42}
              {:source :agent
               :label "LLM turn"
               :duration-ms 12}
              {:source :agent
               :label "Retrieval search"
               :duration-ms 34}]
             (mapv #(select-keys % [:source :label :duration-ms])
                   (:execution-timing-entries view)))))))

(deftest live-status-fixture-skills-projection
  (testing "Skills event stream fixture projects skills stage/counters"
    (let [events (load-fixture "10_live_status_skills")
          view (chat-session/execution-events->live-view events)]
      (is (= :skills-generating (:current-stage view)))
      (is (= "Generating response" (:summary view)))
      (is (= 0 (:tool-call-count view)))
      (is (= 0 (:warning-count view)))
      (is (= 9 (count (:timeline view)))))))

(deftest live-status-fixture-agentic-projection
  (testing "Agentic event stream fixture projects tool/warning activity"
    (let [events (load-fixture "11_live_status_agentic")
          view (chat-session/execution-events->live-view events)]
      (is (= :skills-generating (:current-stage view)))
      (is (= "Generating response" (:summary view)))
      (is (= 2 (:tool-call-count view)))
      (is (= 2 (:tool-result-count view)))
      (is (= 1 (:warning-count view)))
      (is (= 10 (count (:timeline view)))))))

(ns digdir.skills.builtin.agent.loop-behavior-test
  "Isolation-safe coverage of the agentic loop's core behaviors.

   Each test sets up its own atoms, stubs call-llm + execute-tool-call
   inline, and asserts on the loop's observable output. Nothing is
   shared between deftests; nothing depends on `bb test`-order init.

   Sibling file `agent_integration_test.clj` carries the older fixture-
   driven suite — those tests pass in the full suite but are sensitive
   to test ordering. This file is the regression net for the same
   behaviors, written for the cancellation work."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.skills.api :as skills-api]
            [digdir.skills.builtin.agent.loop :as agent-loop]
            [digdir.skills.builtin.agent.workspace :as workspace]))

(use-fixtures :once
  (fn [f]
    (skills-api/initialize!)
    (f)))

;; ---------------------------------------------------------------------------
;; Self-contained mock factories
;; ---------------------------------------------------------------------------

(defn- tool-call-resp
  [content tool-name args call-id]
  {:choices [{:finish_reason "tool_calls"
              :message {:content content
                        :tool_calls [{:id call-id
                                      :type "function"
                                      :function {:name tool-name
                                                 :arguments (json/write-str args)}}]}}]})

(defn- final-resp
  [content]
  {:choices [{:finish_reason "stop"
              :message {:content content}}]})

(defn- scripted-llm
  "Returns a fn that replays the given response sequence in order.
   Variadic-args so it works with both the 5-arity and 6-arity
   call-llm calls."
  [responses]
  (let [idx (atom -1)]
    (fn [& _]
      (let [i (swap! idx inc)]
        (when (>= i (count responses))
          (throw (ex-info "Scripted LLM ran out of responses"
                          {:index i :total (count responses)})))
        (nth responses i)))))

(defn- dispatch-recorder
  "Returns [stub !log]. `stub` replaces `loop/dispatch-one-tool-call` so
   we capture tool dispatch at the pure-shape boundary — above the
   skill-registry vs inline branch that varies depending on what
   else has initialized in the test JVM. Returns the same shape
   `dispatch-one-tool-call` returns: `[trace-entry workspace-out]`."
  [results]
  (let [!log (atom [])
        idx (atom -1)]
    [(fn [tool-call workspace _ambient-ctx iteration _progress-fn]
       (let [fn-name (get-in tool-call [:function :name])
             args-str (get-in tool-call [:function :arguments])
             _ (swap! !log conj {:tool fn-name :args args-str})
             i (swap! idx inc)
             result-text (if (< i (count results))
                           (let [r (nth results i)]
                             (if (string? r) r (json/write-str r)))
                           (str "default-result-" i))]
         [{:tool fn-name
           :args {}
           :args-summary "{}"
           :tool-call-id (:id tool-call)
           :result-summary result-text
           :duration-ms 0
           :ok? true
           :stage :test
           :sub-skill nil
           :iteration iteration}
          workspace]))
     !log]))

(defn- bare-ambient-ctx
  []
  {:docs-collection "docs"
   :chunks-collection "chunks"
   :phrases-collection "phrases"
   :conversation-history []
   :opts {:tenant "isolation-test" :environment "test"}})

(defn- run-loop
  "Run the agentic-loop with the given LLM script + tool results.
   Returns {:result :tool-log :workspace}."
  [{:keys [llm-responses tool-results max-iterations progress-fn]}]
  (let [!ws (workspace/create-workspace)
        [dispatch-stub !tool-log] (dispatch-recorder (or tool-results []))]
    (with-redefs [agent-loop/call-llm (scripted-llm llm-responses)
                  agent-loop/dispatch-one-tool-call dispatch-stub]
      (let [result (agent-loop/agentic-loop
                     [{:role "system" :content "test"}
                      {:role "user" :content "question"}]
                     !ws
                     (bare-ambient-ctx)
                     (cond-> {:max-iterations (or max-iterations 5)
                              :sufficiency-llm-fn nil}
                       progress-fn (assoc :progress-fn progress-fn)))]
        {:result result
         :tool-log @!tool-log
         :workspace @!ws}))))

;; ---------------------------------------------------------------------------
;; Behavior tests
;; ---------------------------------------------------------------------------

(deftest tool-calls-replay-in-script-order
  (testing "Agent loop drives tools in the order the LLM script specifies"
    (let [{:keys [tool-log result]}
          (run-loop {:llm-responses [(tool-call-resp "Plan first" "search_documents"
                                                     {:queries ["q1"]} "c1")
                                     (tool-call-resp "Now rerank" "rerank_results"
                                                     {} "c2")
                                     (tool-call-resp "Generate" "generate_response"
                                                     {:query "question"} "c3")
                                     (final-resp "Final answer here.")]
                     :tool-results [{:hits ["chunk-a" "chunk-b"]}
                                    {:reranked ["chunk-a"]}
                                    "Generated text."]
                     :max-iterations 6})]
      (is (= ["search_documents" "rerank_results" "generate_response"]
             (mapv :tool tool-log)))
      (is (= "Final answer here." (:response result)))
      (is (false? (boolean (:exhausted result)))))))

(deftest direct-final-response-skips-tool-calls
  (testing "If the LLM returns a stop response first, no tools are called"
    (let [{:keys [tool-log result]}
          (run-loop {:llm-responses [(final-resp "I already know.")]
                     :tool-results []
                     :max-iterations 4})]
      (is (empty? tool-log))
      (is (= "I already know." (:response result))))))

(deftest exhaustion-falls-back-to-stored-usable-response
  (testing "When max-iterations is reached and workspace has a sufficient
            generated response, the loop returns that response with :exhausted"
    (let [!ws (workspace/create-workspace)
          _ (swap! !ws assoc
                   :last-generated-response "Stored answer from an earlier iter."
                   :last-generate-insufficient-context false
                   :iteration-history [{:iteration 0
                                        :tool-calls [{:tool "generate_response"
                                                      :args {}
                                                      :result-summary "Stored"}]}])
          [dispatch-stub _] (dispatch-recorder [])
          result (with-redefs [agent-loop/call-llm
                                (fn [& _]
                                  (throw (ex-info "LLM should not be called when
                                                   max-iterations=0 and a stored
                                                   response exists" {})))
                                agent-loop/dispatch-one-tool-call dispatch-stub]
                   (agent-loop/agentic-loop
                     [{:role "system" :content "s"}
                      {:role "user" :content "q"}]
                     !ws
                     (bare-ambient-ctx)
                     {:max-iterations 0
                      :sufficiency-llm-fn nil}))]
      (is (= "Stored answer from an earlier iter." (:response result)))
      (is (true? (:exhausted result))))))

(deftest progress-fn-receives-iteration-and-turn-events
  (testing "Loop-owned progress events fire at the right boundaries.
            Per-tool-call events (:agent/tool-call, :agent/tool-result)
            are emitted *inside* dispatch-one-tool-call, which we stub
            out — those are covered by tests that exercise dispatch
            directly. Loop-level events are this test's surface."
    (let [!progress (atom [])
          progress-fn (fn [event] (swap! !progress conj (:event event)))
          {:keys [result]}
          (run-loop {:llm-responses [(tool-call-resp "go" "search_documents"
                                                     {:queries ["x"]} "c1")
                                     (final-resp "Done.")]
                     :tool-results ["search result"]
                     :max-iterations 3
                     :progress-fn progress-fn})
          events @!progress]
      (is (= "Done." (:response result)))
      (is (some #{:agent/iteration-started} events))
      (is (some #{:agent/turn-completed} events))
      (is (some #{:agent/finalized} events)))))

(deftest progress-fn-threaded-to-call-llm
  (testing "When :progress-fn is in opts, the 6-arity call-llm receives it.
            This is the contract Phase 4b's streaming path relies on."
    (let [seen-opts (atom nil)
          progress-fn (fn [_] nil)
          script (scripted-llm [(final-resp "x")])
          !ws (workspace/create-workspace)
          [dispatch-stub _] (dispatch-recorder [])]
      (with-redefs [agent-loop/call-llm
                    (fn [tenant messages tools model temperature & opts]
                      (reset! seen-opts (first opts))
                      (script tenant messages tools model temperature))
                    agent-loop/dispatch-one-tool-call dispatch-stub]
        (agent-loop/agentic-loop
          [{:role "system" :content "s"}
           {:role "user" :content "q"}]
          !ws
          (bare-ambient-ctx)
          {:max-iterations 2
           :sufficiency-llm-fn nil
           :progress-fn progress-fn}))
      (is (= progress-fn (:progress-fn @seen-opts))
          "Streaming branch must receive :progress-fn through opts."))))

(deftest llm-exception-yields-structured-error-not-throw
  (testing "An LLM call exception is caught and surfaced as :error
            instead of propagating out of the loop"
    (let [!ws (workspace/create-workspace)
          [dispatch-stub _] (dispatch-recorder [])
          result (with-redefs [agent-loop/call-llm
                                (fn [& _]
                                  (throw (ex-info "LLM 400"
                                                  {:status 400})))
                                agent-loop/dispatch-one-tool-call dispatch-stub]
                   (agent-loop/agentic-loop
                     [{:role "system" :content "s"}
                      {:role "user" :content "q"}]
                     !ws
                     (bare-ambient-ctx)
                     {:max-iterations 1
                      :sufficiency-llm-fn nil}))]
      (is (string? (:error result)))
      (is (re-find #"status 400" (:error result))))))

;; ---------------------------------------------------------------------------
;; No-tool-call scaffolding guard
;; ---------------------------------------------------------------------------

(deftest search-scaffold-response-matches-prose-search-shapes
  (testing "Scaffold shapes the local model actually emits are all detected"
    (is (agent-loop/search-scaffold-response? "[SEARCH]\nQuery alternatives: [\"a\" \"b\"]"))
    (is (agent-loop/search-scaffold-response? "SEARCH(query: 'altinn token samtykke')"))
    (is (agent-loop/search-scaffold-response? "SEARCH queries=[\"altinn broker file\"]")
        "paren-less SEARCH queries= (missed by the old regex)")
    (is (agent-loop/search-scaffold-response? "La meg søke i dokumentasjonen for å finne svaret.")
        "Norwegian 'let me search' prose (missed by the old regex)")
    (is (agent-loop/search-scaffold-response? "<tool_code>\nprint(SEARCH(q=\"x\"))\n</tool_code>"))
    (is (agent-loop/search-scaffold-response? "1. SEARCH (required first)"))
    (is (agent-loop/search-scaffold-response? "Query alternatives: [\"validering samtykketoken\"]")))
  (testing "Real answers and non-strings are NOT flagged"
    (is (not (agent-loop/search-scaffold-response?
               "Som tjenesteeier validerer du et samtykketoken ved å kontrollere consentRights [1].")))
    (is (not (agent-loop/search-scaffold-response?
               "You can search the portal, but the answer is that max size is 250 MB.")))
    (is (not (agent-loop/search-scaffold-response? nil)))
    (is (not (agent-loop/search-scaffold-response? "")))))

(deftest no-tool-call-decision-shape-agnostic-reprompt
  (testing "ANY never-searched terminal re-prompts (regardless of shape) while budget remains"
    (doseq [resp ["[SEARCH]\nQuery alternatives: []"            ; classic scaffold
                  "SEARCH queries=[\"x\"]"                       ; paren-less (old regex missed)
                  "La meg søke i dokumentasjonen…"               ; Norwegian prose (old regex missed)
                  "For å verifisere et samtykketoken sjekker du consentRights."]] ; hallucinated answer, no search
      (let [d (agent-loop/no-tool-call-decision
                {:enabled? true :searched? false :direct-response resp :retries 0})]
        (is (= :retry (:action d)) (str "should re-prompt: " resp))
        (is (re-find #"(?i)search" (:nudge d))))))
  (testing "Exhausted + still scaffolding → honest miss (don't leak scaffolding)"
    (let [d (agent-loop/no-tool-call-decision
              {:enabled? true :searched? false :direct-response "SEARCH queries=[\"x\"]"
               :retries agent-loop/max-no-tool-call-retries})]
      (is (= :finalize (:action d)))
      (is (= agent-loop/no-tool-call-honest-miss (:response d)))))
  (testing "Exhausted + a real (unsourced) answer → pass through as best-effort (not discarded)"
    (let [answer "For å verifisere et samtykketoken sjekker du consentRights."
          d (agent-loop/no-tool-call-decision
              {:enabled? true :searched? false :direct-response answer
               :retries agent-loop/max-no-tool-call-retries})]
      (is (= :finalize (:action d)))
      (is (= answer (:response d)) "a non-scaffold answer is not replaced by honest-miss")))
  (testing "A genuine post-search answer passes through unchanged (searched? true → never re-routed)"
    (let [answer "Maks filstørrelse er 250 MB [1]."
          d (agent-loop/no-tool-call-decision
              {:enabled? true :searched? true :direct-response answer :retries 0})]
      (is (= :finalize (:action d)))
      (is (= answer (:response d)))))
  (testing "Disabled guard (default): never-searched passes through unchanged"
    (let [scaffold "SEARCH queries=[\"x\"]"]
      (is (= scaffold (:response (agent-loop/no-tool-call-decision
                                   {:enabled? false :searched? false :direct-response scaffold :retries 0}))))
      (is (= :finalize (:action (agent-loop/no-tool-call-decision
                                  {:searched? false :direct-response scaffold :retries 0})))
          "absent :enabled? defaults to off"))))

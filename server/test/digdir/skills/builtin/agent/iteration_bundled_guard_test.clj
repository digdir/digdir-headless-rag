(ns digdir.skills.builtin.agent.iteration-bundled-guard-test
  "Deterministic integration coverage of the no-tool-call scaffolding guard's
   WIRING in the bundled iteration's record-and-route step. No LLM calls — we
   feed `execute-agent-bundle-record-and-route` a no-tool-call turn directly and
   assert it re-routes (vs finalizes) exactly as the flag / search-state /
   retry-budget dictate. Complements the pure `no-tool-call-decision` unit tests
   in loop_behavior_test by proving the loop actually continues with the nudge."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [digdir.skills.builtin.agent.iteration-bundled :as ib]
            [digdir.skills.builtin.agent.loop :as agent-loop]))

(def ^:private scaffold "[SEARCH]\nQuery alternatives: [\"a\" \"b\"]")

(defn- route
  "Run record-and-route for a no-tool-call turn and return its :outputs."
  [{:keys [workspace guard-on? direct-response]
    :or {workspace {} direct-response scaffold}}]
  (:outputs
   (ib/execute-agent-bundle-record-and-route
    {:inputs {:workspace-after-gates workspace
              :tool-call-results-final []
              :active-decision nil
              :active-hint nil
              :generated-response-call? false
              :messages-in [{:role "system" :content "s"} {:role "user" :content "q"}]
              :assistant-msg {:role "assistant" :content direct-response}
              :llm-error nil
              :no-tool-calls? true
              :direct-response direct-response
              :ambient-ctx {:opts {:skill-params
                                   {:builtin/agent (cond-> {}
                                                     guard-on? (assoc :no-tool-call-retry true))}}}
              :iteration 0
              :model nil
              :temperature nil}})))

(deftest guard-on-reroutes-scaffolding-instead-of-finalizing
  (testing "Never-searched scaffolding → continue the loop with a corrective nudge"
    (let [out (route {:guard-on? true})]
      (is (false? (:finalized? out)) "Does NOT finalize")
      (is (= :default (:next-phase out)) "Continues with a normal next iteration")
      (is (= 1 (:no-tool-call-retries (:workspace-out out))) "Retry counter incremented")
      (let [msgs (:messages-out out)
            sys (last msgs)]
        (is (= "system" (:role sys)))
        (is (re-find #"(?i)tool call" (:content sys)) "Corrective nudge appended")
        (is (some #(= scaffold (:content %)) msgs) "The bad assistant turn is preserved")))))

(deftest guard-on-reroutes-non-scaffold-never-searched-answer
  (testing "Shape-agnostic: a hallucinated-without-search answer (NOT scaffold) also re-routes"
    (let [answer "For å verifisere et samtykketoken sjekker du consentRights."
          out (route {:guard-on? true :direct-response answer})]
      (is (false? (:finalized? out)) "Does NOT finalize — re-prompts to search first")
      (is (= :default (:next-phase out)))
      (is (= 1 (:no-tool-call-retries (:workspace-out out))))
      (is (re-find #"(?i)search" (:content (last (:messages-out out))))))))

(deftest guard-off-finalizes-on-scaffolding-passthrough
  (testing "With the guard disabled (default), the old behavior is unchanged"
    (let [out (route {:guard-on? false})]
      (is (true? (:finalized? out)))
      (is (= :finalize (:terminal-state out)))
      (is (= scaffold (:response out)) "Scaffolding passes straight through"))))

(deftest guard-on-honest-miss-once-retries-exhausted
  (testing "After the retry cap, finalize with an honest miss (not the scaffolding)"
    (let [out (route {:guard-on? true
                      :workspace {:no-tool-call-retries agent-loop/max-no-tool-call-retries}})]
      (is (true? (:finalized? out)))
      (is (= agent-loop/no-tool-call-honest-miss (:response out)))
      (is (not (str/includes? (:response out) "[SEARCH]"))))))

(deftest guard-on-passes-through-genuine-post-search-answer
  (testing "A real answer after a search has run is finalized unchanged (not re-routed)"
    (let [answer "Som tjenesteeier kontrollerer du consentRights [1]."
          out (route {:guard-on? true
                      :workspace {:search-history [{:queries ["samtykke"]}]}
                      :direct-response answer})]
      (is (true? (:finalized? out)))
      (is (= answer (:response out))))))

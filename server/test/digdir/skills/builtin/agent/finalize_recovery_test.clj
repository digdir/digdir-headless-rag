(ns digdir.skills.builtin.agent.finalize-recovery-test
  "Covers the non-exhausted finalize recovery path in
   `digdir.skills.builtin.agent.graphs/execute-agent-finalize`: a reasoning-class
   local model can mark an iteration :finalized? while emitting a BLANK response
   (empty synthesis on a retrieval miss). The finalize step must not return that
   blank through to the caller — it recovers via the same guarded best-answer-now
   fallback the exhaustion path uses. Isolation-safe: pure-shape calls, the only
   external dependency (`call-llm`) is redef'd."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.skills.builtin.agent.graphs :as graphs]
            [digdir.skills.builtin.agent.loop :as agent-loop]
            [digdir.rag.skills.core :as skills]))

(defn- finalize
  "Drive execute-agent-finalize with a single collected iteration."
  [iteration]
  (-> (graphs/execute-agent-finalize
        {:inputs {:iterations [iteration]
                  :exhausted? false
                  :ambient-ctx {:opts {:tenant "test"}}
                  :messages-init [{:role "user" :content "q"}]
                  :workspace-init {}
                  :model "test-model"
                  :temperature 0.0}})
      (skills/get-result-outputs)))

(deftest passes-through-a-real-answer
  (testing "a non-blank finalized response is returned verbatim, no recovery"
    (let [out (finalize {:response "Real answer." :terminal-state :finalize :workspace-out {}})]
      (is (= "Real answer." (:response out)))
      (is (not (:recovered-empty-finalize? out)))
      (is (false? (:exhausted? out))))))

(deftest recovers-blank-from-stored-generation
  (testing "blank finalized response recovers the workspace's last generation (no LLM call)"
    (let [out (finalize {:response ""
                         :terminal-state :finalize
                         :workspace-out {:last-generated-response "Stored partial answer."}})]
      (is (= "Stored partial answer." (:response out)))
      (is (true? (:recovered-empty-finalize? out)))
      (is (false? (:exhausted? out))))))

(deftest preserves-a-clarification-request
  (testing "a blank response that IS a clarification request is left untouched"
    (let [out (finalize {:response ""
                         :clarification-request {:question "Which environment?"}
                         :terminal-state :needs-clarification
                         :workspace-out {}})]
      (is (= {:question "Which environment?"} (:clarification-request out)))
      (is (not (:recovered-empty-finalize? out)))
      (is (= :needs-clarification (:terminal-state out))))))

(deftest recovers-blank-via-llm-fallback
  (testing "blank finalized response with no stored generation recovers via the LLM nudge"
    (with-redefs [agent-loop/call-llm
                  (fn [& _] {:choices [{:message {:content "LLM fallback answer."}}]})]
      (let [out (finalize {:response "" :terminal-state :finalize :workspace-out {}})]
        (is (= "LLM fallback answer." (:response out)))
        (is (true? (:recovered-empty-finalize? out)))))))

(deftest fallback-is-grounded-in-accumulated-context
  (testing "the fallback LLM call reuses the loop's accumulated messages (retrieved context), not bare messages-init"
    (let [captured (atom nil)]
      (with-redefs [agent-loop/call-llm
                    (fn [_tenant messages & _]
                      (reset! captured messages)
                      {:choices [{:message {:content "Grounded answer."}}]})]
        (let [acc [{:role "system" :content "sys"}
                   {:role "user" :content "q"}
                   {:role "assistant" :content "" :tool_calls [{:id "c1"}]}
                   {:role "tool" :tool_call_id "c1" :content "RETRIEVED: the broker max is 2GB."}]
              out (finalize {:response ""
                             :terminal-state :finalize
                             :messages-out acc
                             :workspace-out {}})]
          (is (= "Grounded answer." (:response out)))
          ;; the captured messages must include the tool-result context, then the nudge
          (is (some #(= "tool" (:role %)) @captured) "accumulated tool-result context is present")
          (is (re-find #"all available iterations" (:content (last @captured))) "ends with the best-answer-now nudge"))))))

(deftest recovers-blank-to-sentinel-when-llm-also-empty
  (testing "if the LLM fallback also returns empty and nothing is stored, a non-blank sentinel is used"
    (with-redefs [agent-loop/call-llm
                  (fn [& _] {:choices [{:message {:content ""}}]})]
      (let [out (finalize {:response "" :terminal-state :finalize :workspace-out {}})]
        (is (seq (:response out)) "never returns a blank response")
        (is (= "Could not determine an answer within the iteration limit." (:response out)))
        (is (true? (:recovered-empty-finalize? out)))))))

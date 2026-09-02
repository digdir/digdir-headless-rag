(ns digdir.skills.builtin.agent.iteration-streaming-test
  "The graph agent variants must stream content deltas like the imperative
   loop does.

   `call-llm` is 5-or-6-arity and the arity IS the behaviour: only the 6-arity
   takes `{:progress-fn …}` and emits `:response/chunk` events (loop.clj is
   the sole emitter). So these assert the ARITY the call site takes, not just
   that a call happened — a `(fn [& _] …)` stub would pass either way, which
   is how the divergence in #148 stayed invisible."
  (:require [clojure.test :refer [deftest testing is]]
            [digdir.skills.builtin.agent.graphs :as graphs]
            [digdir.skills.builtin.agent.iteration-bundled :as bundled]
            [digdir.skills.builtin.agent.iteration-faithful :as faithful]
            [digdir.skills.builtin.agent.loop :as agent-loop]
            [digdir.test-utils :as tu]))

(def ^:private llm-response
  {:choices [{:message {:role "assistant" :content "An answer."}
              :finish_reason "stop"}]})

(defn- ambient-ctx []
  {:opts {:tenant "test-tenant"
          :progress-fn (fn [_event] nil)}})

(defn- iteration-inputs []
  {:messages-in [{:role "user" :content "hi"}]
   :workspace-in {}
   :phase :default
   :tools nil
   :ambient-ctx (ambient-ctx)
   :model "gpt-4o"
   :temperature 0.1
   :iteration 0
   :max-iterations 5})

(deftest bundled-iteration-passes-progress-fn-to-call-llm
  (testing "the bundled inner graph streams, like the imperative loop"
    (let [stub (tu/recording-fn llm-response)]
      (with-redefs [agent-loop/call-llm stub
                    agent-loop/tools-for-phase (fn [_ _] nil)]
        (bundled/execute-agent-bundle-llm-and-tools {:inputs (iteration-inputs)}))
      (is (= [6] (tu/arities stub))
          "5-arity drops progress-fn, so the bundled path emits no :response/chunk deltas"))))

(deftest faithful-iteration-passes-progress-fn-to-call-llm
  (testing "the faithful inner graph streams too"
    (let [stub (tu/recording-fn llm-response)]
      (with-redefs [agent-loop/call-llm stub
                    agent-loop/tools-for-phase (fn [_ _] nil)]
        (faithful/execute-agent-llm-call {:inputs (iteration-inputs)}))
      (is (= [6] (tu/arities stub))))))

(deftest exhaustion-nudge-passes-progress-fn-to-call-llm
  (testing "the graph fallback nudge streams, matching its imperative twin at loop.clj:811"
    (let [stub (tu/recording-fn llm-response)]
      (with-redefs [agent-loop/call-llm stub]
        (#'graphs/fallback-on-exhaustion
         {} [{:role "user" :content "hi"}] (ambient-ctx) "gpt-4o" 0.1 5))
      (is (= [6] (tu/arities stub))
          "the fallback answer is user-facing text; the imperative loop streams it"))))

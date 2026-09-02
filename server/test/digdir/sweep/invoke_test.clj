(ns digdir.sweep.invoke-test
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.skills.invoke :as invoke]
            [digdir.sweep.invoke :as sweep-invoke]
            [digdir.sweep.user-simulator :as sim]))

(defn- complete-result [response]
  {:status :complete
   :response response
   :clarification nil
   :chunks []
   :error nil})

(defn- clarification-result [question]
  {:status :needs-clarification
   :response ""
   :clarification {:question question
                   :options nil
                   :context-summary "(stubbed)"}
   :chunks []
   :error nil})

(deftest happy-path-no-clarification
  (testing "When invoke-rag returns :complete immediately, the loop is
            a passthrough — no simulator call, zero rounds."
    (let [sim-calls (atom 0)]
      (with-redefs [invoke/invoke-rag (fn [_] (complete-result "Dialogporten er en plattform."))
                    sim/respond-to-clarification
                    (fn [_] (swap! sim-calls inc) {:reply "should not be called"})]
        (let [r (sweep-invoke/invoke-with-clarification-loop
                  {:user-query "Hva er Dialogporten?"
                   :execution-scope {:tenant "digdir"}})]
          (is (= :complete (:status r)))
          (is (= "Dialogporten er en plattform." (:response r)))
          (is (= 0 (:clarification-rounds r)))
          (is (= 0 @sim-calls))
          (is (false? (:terminal-clarification? r))))))))

(deftest one-round-clarification-then-answer
  (testing "Clarification on turn 1, simulator replies, agent answers on turn 2.
            The simulator should see the ORIGINAL user-query (not the prior reply)
            so its anchor stays on the user's actual ask."
    (let [calls (atom [])
          sim-arg-snapshot (atom nil)]
      (with-redefs [invoke/invoke-rag
                    (fn [args]
                      (swap! calls conj (select-keys args [:user-query :conversation-history]))
                      (if (= 1 (count @calls))
                        (clarification-result "Hva mener du?")
                        (complete-result "Dialogporten er en plattform.")))
                    sim/respond-to-clarification
                    (fn [args]
                      (reset! sim-arg-snapshot args)
                      {:reply "Jeg mener en enkel definisjon."
                       :usage {:prompt-tokens 50 :completion-tokens 10}})]
        (let [r (sweep-invoke/invoke-with-clarification-loop
                  {:user-query "Hva er Dialogporten?"
                   :execution-scope {:tenant "digdir"}
                   :intent-hint "simple definition"})]
          (is (= :complete (:status r)))
          (is (= 1 (:clarification-rounds r)))
          (is (false? (:terminal-clarification? r)))
          (is (= 1 (count (:clarification-history r))))
          (is (= "Hva mener du?" (-> r :clarification-history first :question)))
          (is (= "Jeg mener en enkel definisjon." (-> r :clarification-history first :reply)))
          ;; Simulator anchored on original query, not the prior reply.
          (is (= "Hva er Dialogporten?" (:original-query @sim-arg-snapshot)))
          (is (= "simple definition" (:intent-hint @sim-arg-snapshot)))
          ;; Second invoke-rag call carries the conversation history.
          (let [second-call (second @calls)]
            (is (= "Jeg mener en enkel definisjon." (:user-query second-call)))
            (is (= [{:role "user"      :content "Hva er Dialogporten?"}
                    {:role "assistant" :content "Hva mener du?"}]
                   (:conversation-history second-call)))))))))

(deftest exhausts-rounds-and-marks-terminal
  (testing "When the agent keeps asking for clarification, the loop stops
            at max-clarification-rounds and marks :terminal-clarification?."
    (let [n-calls (atom 0)]
      (with-redefs [invoke/invoke-rag
                    (fn [_]
                      (swap! n-calls inc)
                      ;; Always ask back, no matter what.
                      (clarification-result (str "round " @n-calls)))
                    sim/respond-to-clarification
                    (fn [_] {:reply "ok proceed"})]
        (let [r (sweep-invoke/invoke-with-clarification-loop
                  {:user-query "Hva er Dialogporten?"
                   :execution-scope {:tenant "digdir"}
                   :max-clarification-rounds 2})]
          (is (= :needs-clarification (:status r)))
          (is (true? (:terminal-clarification? r)))
          (is (= 2 (:clarification-rounds r)))
          ;; 3 invoke-rag calls: initial + 2 rounds of clarification-mediated retry.
          (is (= 3 @n-calls)))))))

(deftest simulator-blank-reply-aborts-cleanly
  (testing "If the simulator returns a blank reply (e.g. Azure was down),
            the loop records the failed round and marks terminal."
    (with-redefs [invoke/invoke-rag (fn [_] (clarification-result "Hva mener du?"))
                  sim/respond-to-clarification
                  (fn [_] {:reply nil :error "azure 503"})]
      (let [r (sweep-invoke/invoke-with-clarification-loop
                {:user-query "Hva er Dialogporten?"
                 :execution-scope {:tenant "digdir"}})]
        (is (= :needs-clarification (:status r)))
        (is (true? (:terminal-clarification? r)))
        (is (= 0 (:clarification-rounds r)))
        (is (= 1 (count (:clarification-history r))))
        (is (= "azure 503" (-> r :clarification-history first :error)))))))

(deftest required-inputs-validated-eagerly
  (testing "Missing :user-query or :execution-scope throws."
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":user-query"
                          (sweep-invoke/invoke-with-clarification-loop
                            {:execution-scope {:tenant "digdir"}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":execution-scope"
                          (sweep-invoke/invoke-with-clarification-loop
                            {:user-query "x"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":tenant"
                          (sweep-invoke/invoke-with-clarification-loop
                            {:user-query "x"
                             :execution-scope {}})))))

(ns digdir.skills.builtin.agent.streaming-call-llm-test
  "Tests for call-llm's streaming branch — the bridge between the OpenAI
   per-token deltas and the MCP-visible :response/chunk events."
  (:require [clojure.test :refer [deftest testing is]]
            [digdir.llm.client :as llm-client]
            [digdir.llm.openai :as llm]
            [digdir.skills.builtin.agent.loop :as agent-loop]))

(defn- with-stub-streaming
  "Stub `streaming-chat-completion` to call `on-content-delta` with each
   element of `deltas` in order, then return `final-response`."
  [deltas final-response f]
  (with-redefs [llm/use-azure-openai (constantly false)
                llm/streaming-chat-completion
                (fn [_params {:keys [on-content-delta]}]
                  (when on-content-delta
                    (doseq [d deltas] (on-content-delta d)))
                  final-response)]
    (f)))

(deftest call-llm-streaming-emits-paragraph-chunks
  (testing "Per-token deltas land as paragraph-bounded :response/chunk events"
    (let [chunks (atom [])
          progress-fn (fn [event]
                        (when (= :response/chunk (:event event))
                          (swap! chunks conj (:delta event))))
          deltas ["First " "paragraph." "\n\n" "Second " "paragraph." "\n\n" "Tail"]]
      (with-stub-streaming deltas
        {:choices [{:message {:role "assistant"
                              :content "First paragraph.\n\nSecond paragraph.\n\nTail"}
                    :finish_reason "stop"}]}
        (fn []
          (let [resp (agent-loop/call-llm "test-tenant" [{:role "user" :content "go"}]
                                          nil "gpt-4o" 0.0
                                          {:progress-fn progress-fn})]
            (is (= ["First paragraph.\n\n" "Second paragraph.\n\n" "Tail"]
                   @chunks))
            (is (= "stop" (-> resp :choices first :finish_reason)))
            (is (= "First paragraph.\n\nSecond paragraph.\n\nTail"
                   (-> resp :choices first :message :content)))))))))

(deftest call-llm-without-progress-fn-uses-blocking-path
  (testing "Omitting progress-fn must keep the legacy blocking semantics"
    (let [streamed? (atom false)
          blocking? (atom false)]
      (with-redefs [llm/use-azure-openai (constantly false)
                    llm/streaming-chat-completion
                    (fn [& _]
                      (reset! streamed? true)
                      {:choices [{:message {:content ""}}]})
                    ;; `call-llm`'s blocking branch goes through
                    ;; `digdir.llm.client/create-chat-completion`, which only
                    ;; delegates to wkok on the `:impl :azure` path — the
                    ;; non-Azure default is a direct clj-http POST. Stubbing
                    ;; wkok here therefore intercepted nothing and the test
                    ;; issued a real request.
                    llm-client/create-chat-completion
                    (fn [& _]
                      (reset! blocking? true)
                      {:choices [{:message {:content "ok"}}]})]
        (agent-loop/call-llm "t" [{:role "user" :content "x"}] nil "gpt-4o" 0.1)
        (is (false? @streamed?))
        (is (true? @blocking?))))))

(deftest call-llm-streaming-flushes-trailing-content-on-close
  (testing "Final chunk is emitted even when the stream ends without \\n\\n"
    (let [chunks (atom [])
          progress-fn (fn [event]
                        (when (= :response/chunk (:event event))
                          (swap! chunks conj (:delta event))))
          deltas ["No paragraph break here, just text."]]
      (with-stub-streaming deltas
        {:choices [{:message {:role "assistant"
                              :content "No paragraph break here, just text."}
                    :finish_reason "stop"}]}
        (fn []
          (agent-loop/call-llm "t" [{:role "user" :content "x"}]
                               nil "gpt-4o" 0.0
                               {:progress-fn progress-fn})
          (is (= ["No paragraph break here, just text."] @chunks)))))))

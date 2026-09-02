(ns digdir.api.routes.endpoints.openai-compat-shape-test
  "The /v1 response must be parseable by a stock OpenAI SDK with no shim:
   schema-required keys present (null when unset), and no fabricated values —
   notably `usage`, where a zero is a lie a cost-tracking client will act on.

   Assertions run against the DECODED JSON body, not the Clojure map, because
   'the key is present with a null value' and 'the key is absent' are the same
   Clojure map lookup but different bytes on the wire."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string]
            [ring.core.protocols]
            [cheshire.core :as json]
            [digdir.agents.db :as agents-db]
            [digdir.api.routes.endpoints.openai-compat :as openai-compat]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.skills.api :as skills-api]
            [digdir.skills.invoke :as invoke]))

(def ^:private test-agent
  {:id "builtin/docs-agent"
   :name "Docs Agent"
   :default-skill-graph :docs/self-improve-graph
   :allowed-skill-graphs [:docs/self-improve-graph]
   :allowed-dataset-scopes [{:tenant "altinn-docs" :dataset-config-key "dev"}]
   :enabled? true})

(def ^:private principal
  {:api-key/agent-refs ["builtin/docs-agent"]
   :api-key/dataset-scopes [{:tenant "altinn-docs" :dataset-config-key "dev"}]
   :api-key/skill-graphs []
   :api-key/client-id "test-client"})

(def ^:private model-name "builtin.docs-agent__self-improve-graph")

(defn- request [& [body-overrides]]
  (merge principal
         {:body (json/generate-string
                 (merge {:model model-name
                         :messages [{:role "user" :content "hi"}]}
                        body-overrides))}))

(defn- with-stubs [invoke-result f]
  (with-redefs [config-db/get-conn (fn [] (atom :fake-config-conn))
                config-core/get-master-key (fn [] "master-key")
                agents-db/get-agent (fn [_ id] (when (= id (:id test-agent)) test-agent))
                agents-db/list-enabled-agents (fn [_] [test-agent])
                skills-api/initialize! (fn [] nil)
                skills-api/get-skill-graph-info (fn [g] {:id g :name (name g) :description "stub"})
                config-db/get-dataset-by-ref (fn [_ _ _] {:docs-collection "docs"
                                                          :chunks-collection "chunks"
                                                          :phrases-collection "phrases"})
                invoke/invoke-rag (fn [_] invoke-result)]
    (f)))

(def ^:private ok-result
  {:status :complete :response "An answer." :chunks [] :queries []
   :diagnostics {} :raw-result {} :error nil})

(defn- decoded-body [response]
  (json/parse-string (:body response) true))

(defn- chat [invoke-result & [body-overrides]]
  (with-stubs invoke-result
    (fn [] (openai-compat/chat-completions-handler (request body-overrides)))))

(deftest usage-is-never-fabricated
  (testing "no usage reported by the agent → the key is ABSENT, not zeroed"
    (let [body (decoded-body (chat ok-result))]
      (is (not (contains? body :usage))
          "usage reported as zeros; a cost-tracking client would believe it")))

  (testing "usage reported → real summed numbers appear"
    (let [result (assoc ok-result
                        :diagnostics
                        {:stage-timings [{:usage {:prompt_tokens 100 :completion_tokens 20
                                                  :total_tokens 120}}
                                         {:usage {:prompt_tokens 50 :completion_tokens 5
                                                  :total_tokens 55}}]})
          body (decoded-body (chat result))]
      (is (= 150 (get-in body [:usage :prompt_tokens])))
      (is (= 25 (get-in body [:usage :completion_tokens])))
      (is (= 175 (get-in body [:usage :total_tokens])))))

  (testing "stage timings without token numbers → still absent, not zeroed"
    (let [result (assoc ok-result :diagnostics {:stage-timings [{:stage :retrieval}]})
          body (decoded-body (chat result))]
      (is (not (contains? body :usage))))))

(deftest schema-required-keys-are-present-when-unset
  (testing "choices[0] carries logprobs and finish_reason, null when unset"
    (let [choice (first (:choices (decoded-body (chat ok-result))))]
      (is (contains? choice :finish_reason))
      (is (contains? choice :logprobs) "logprobs is schema-required; SDKs read it")
      (is (nil? (:logprobs choice)) "logprobs must be null, not fabricated"))))

(deftest error-objects-carry-param-and-a-status-appropriate-type
  (testing "4xx: client-side type, param present and null"
    (let [response (with-stubs ok-result
                     (fn [] (openai-compat/chat-completions-handler
                             (merge principal {:body (json/generate-string {:messages []})}))))
          err (:error (decoded-body response))]
      (is (= 400 (:status response)))
      (is (contains? err :param) "param is part of the documented error object")
      (is (nil? (:param err)))
      (is (= "invalid_request_error" (:type err)))
      (is (= "missing_model" (:code err)))))

  (testing "5xx: server-side type, not a client-side one"
    (let [response (chat (assoc ok-result :status :error
                                :error {:error-message "agent exploded"}))
          err (:error (decoded-body response))]
      (is (= 500 (:status response)))
      (is (= "server_error" (:type err))
          "a 5xx reported as invalid_request_error tells the client to fix its request")
      (is (contains? err :param))
      (is (nil? (:param err))))))

(defn- sse-chunks
  "Drive the streaming path and return the decoded JSON of each SSE frame
   (the `[DONE]` sentinel excluded)."
  [invoke-result]
  (with-stubs invoke-result
    (fn []
      ;; The drain must happen INSIDE the stubs: the handler returns a
      ;; StreamableResponseBody that has not run the agent yet, so stubbing
      ;; only around the handler call leaves the real agent to run at write
      ;; time — which is what this test was accidentally doing.
      (let [response (openai-compat/chat-completions-handler (request {:stream true}))
            out (java.io.ByteArrayOutputStream.)]
        (ring.core.protocols/write-body-to-stream (:body response) response out)
        (->> (clojure.string/split-lines (str out))
             (keep (fn [line]
                     (when (clojure.string/starts-with? line "data: ")
                       (let [payload (subs line 6)]
                             (when-not (= "[DONE]" payload)
                               (json/parse-string payload true))))))
             vec)))))

(deftest streaming-chunks-carry-the-required-keys
  (testing "every chunk's choice has logprobs and finish_reason, null until the end"
    (let [chunks (sse-chunks ok-result)]
      (is (seq chunks) "no SSE frames produced")
      (doseq [chunk chunks]
        (let [choice (first (:choices chunk))]
          (is (= "chat.completion.chunk" (:object chunk)))
          (is (contains? choice :logprobs) "logprobs missing from a stream chunk")
          (is (nil? (:logprobs choice)))
          (is (contains? choice :finish_reason)
              "finish_reason must be present and null before the final frame")))
      (is (= "stop" (:finish_reason (first (:choices (last chunks)))))
          "the terminating chunk should carry a real finish_reason"))))

(ns digdir.api.routes.endpoints.openai-compat-error-outcome-test
  "An upstream LLM failure must not be reported as a successful completion.

   The blocking path already answers 500; the streaming path cannot, because
   the 200 and its headers are on the wire before the agent runs. So the
   streaming path says so in `finish_reason` instead — see the decision
   recorded in docs/standards-alignment-findings.md (F19)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [digdir.agents.db :as agents-db]
            [digdir.api.routes.endpoints.openai-compat :as openai-compat]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.skills.api :as skills-api]
            [digdir.skills.invoke :as invoke]
            [ring.core.protocols]))

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

(defn- request [& [body-overrides]]
  (merge principal
         {:body (json/generate-string
                 (merge {:model "builtin.docs-agent__self-improve-graph"
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
  {:status :complete :response "A real answer." :chunks [] :queries []
   :diagnostics {} :raw-result {} :error nil})

(def ^:private failed-result
  "What invoke-rag returns when the agent's upstream LLM call fails —
   `agent/core.clj` maps an LLM exception to `skills/error-result`."
  {:status :error :response "" :chunks [] :queries []
   :diagnostics {} :raw-result {}
   :error {:error-type :agent-execution-failed
           :error-message "LLM call failed: 401 Unauthorized"}})

(defn- drain-sse
  "Drive the streaming body and decode its frames.

   The whole drain runs INSIDE the stubs: `chat-completions-handler` returns a
   StreamableResponseBody that has not run the agent yet, so stubbing only
   around the handler call leaves the real agent to run at write time."
  [invoke-fn]
  (with-redefs [invoke/invoke-rag invoke-fn]
    (let [response (openai-compat/chat-completions-handler (request {:stream true}))
          out (java.io.ByteArrayOutputStream.)]
      (ring.core.protocols/write-body-to-stream (:body response) response out)
      (->> (str/split-lines (str out))
           (keep (fn [line]
                   (when (str/starts-with? line "data: ")
                     (let [payload (subs line 6)]
                       (when-not (= "[DONE]" payload)
                         (json/parse-string payload true))))))
           vec))))

(defn- sse-chunks
  "Frames for a stream whose agent returns `invoke-result`."
  [invoke-result]
  (with-stubs invoke-result (fn [] (drain-sse (fn [_] invoke-result)))))

(defn- final-finish-reason [chunks]
  (->> chunks (keep #(:finish_reason (first (:choices %)))) last))

(deftest blocking-path-reports-failure-as-an-error-not-an-answer
  (testing "an upstream failure is a 5xx with an error object, not 200 + prose"
    (let [response (with-stubs failed-result
                     (fn [] (openai-compat/chat-completions-handler (request))))
          body (json/parse-string (:body response) true)]
      (is (= 500 (:status response)))
      (is (= "server_error" (get-in body [:error :type])))
      (is (nil? (:choices body)) "a failure must not present itself as a completion"))))

(deftest streaming-path-does-not-claim-a-normal-stop-on-failure
  (testing "the terminating chunk must not say the model stopped normally"
    (let [reason (final-finish-reason (sse-chunks failed-result))]
      (is (not= "stop" reason)
          "a failed call reported finish_reason=stop — indistinguishable from a real answer")
      (is (= "error" reason))))

  (testing "the error text still reaches the client as content, so chat UIs render something"
    (let [contents (->> (sse-chunks failed-result)
                        (keep #(get-in % [:choices 0 :delta :content]))
                        (str/join))]
      (is (str/includes? contents "401 Unauthorized"))))

  (testing "a successful stream is unchanged — still finish_reason stop"
    (let [chunks (sse-chunks ok-result)]
      (is (= "stop" (final-finish-reason chunks)))
      (is (str/includes? (->> chunks (keep #(get-in % [:choices 0 :delta :content])) (str/join))
                         "A real answer."))))

  (testing "a mid-stream exception is also reported as error, not stop"
    (let [chunks (with-stubs ok-result
                   (fn [] (drain-sse (fn [_] (throw (ex-info "boom mid-stream" {}))))))]
      (is (= "error" (final-finish-reason chunks))))))

;; ---------------------------------------------------------------------------
;; Error-object key set (#195)
;;
;; The assertion above reads error.type. A key that should not be in the error
;; object is invisible to it, and this is a surface where that matters: the
;; consumer is an external SDK rather than our own UI, and `openai-error`'s
;; docstring records that clients read error.type and error.code rather than
;; the HTTP status.
;;
;; The shape is genuinely closed — openai-error builds a four-key map with
;; :param always present (null when the error is not tied to a parameter,
;; because SDKs read it unconditionally) — so this asserts equality rather
;; than a required subset.

(deftest error-object-is-exactly-the-openai-error-shape
  (testing "message, type, param and code — no more, and param is always there"
    (let [response (with-stubs failed-result
                     (fn [] (openai-compat/chat-completions-handler (request))))
          body (json/parse-string (:body response) true)
          err (:error body)]
      (is (= #{:message :type :param :code} (set (keys err)))
          (str "openai error object keys drifted: " (pr-str (sort (keys err)))))
      (is (contains? err :param)
          "param is documented and read unconditionally by SDKs, so it must be present even when null"))))

;; ============================================================================
;; #434 — a first-run condition is not an internal error
;; ============================================================================

(deftest first-run-conditions-are-classified-not-guessed
  (testing "the tag drives the branch, and the message does not"
    ;; ⚠️ ASSERTING TAG-DRIVEN, NOT MESSAGE-DRIVEN. If the boundary matched on
    ;; exception text, rewording the message would silently return the 500 —
    ;; and rewording prose looks entirely safe, because nothing declares that
    ;; anything parses it.
    (let [kind #'openai-compat/client-error-kind]
      (is (nil? (@kind (RuntimeException. "No datasets are configured for this tenant")))
          "a plain exception whose MESSAGE matches must not be classified — only the tag counts")
      (is (= :no-datasets-configured
             (@kind (ex-info "anything at all" {:digdir/client-error :no-datasets-configured})))
          "the tag classifies regardless of the message")
      (is (nil? (@kind (ex-info "ordinary failure" {:some :data})))
          "POSITIVE CONTROL — an ex-info WITHOUT the tag is unclassified, so the
           boundary's 500 default is untouched for genuine faults"))))

(deftest the-two-first-run-cases-give-different-next-steps
  (testing "#434 — a correct status with an ambiguous message repeats the category error"
    (let [next-step #'openai-compat/client-error-next-step
          none (@next-step :no-datasets-configured)
          unknown (@next-step :dataset-ref-unknown)]
      (is (not= none unknown)
          "both conditions are client-side but the next steps differ — create a
           dataset, versus check the name you sent. One shared message would be
           a correct status that still cannot say which mistake was made")
      (is (str/includes? none "Create one")
          "the no-datasets message must name the step that fixes it")
      (is (str/includes? unknown "/api/datasets")
          "the unknown-ref message must point at where the valid names are"))))

(ns digdir.llm.streaming-response-shape-test
  "#305 — wkok's two client implementations disagree on the streaming
   response shape. The OpenAI/local path returns the core.async channel
   itself; the Azure path returns {:body <channel>}. `streaming-chat-completion`
   assumed the Azure shape, so every non-Azure provider threw

     No implementation of method: :take! of protocol: ReadPort found for class: nil

   before the first token — which is also why time-to-first-token could not be
   measured on the local incumbent (#25).

   Both shapes are exercised here on purpose: a fix verified only against the
   shape that was broken could break Azure silently, and Azure is what every
   deployment uses."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.llm.openai :as llm]
            [wkok.openai-clojure.api :as api]))

(defn- chan-of
  "A closed channel carrying `events`, buffered so puts never block."
  [events]
  (let [ch (async/chan (max 1 (count events)))]
    (doseq [e events] (async/>!! ch e))
    (async/close! ch)
    ch))

(def ^:private sse-events
  "Two content deltas and a finish, as an OpenAI-compatible server sends them."
  [{:choices [{:delta {:role "assistant"} :index 0}]}
   {:choices [{:delta {:content "Maskinporten "} :index 0}]}
   {:choices [{:delta {:content "er en løsning."} :index 0}]}
   {:choices [{:delta {} :finish_reason "stop" :index 0}]}])

(defn- assert-assembles [label]
  (let [deltas (atom [])
        resp (llm/streaming-chat-completion
               {:model "test-model" :messages [{:role "user" :content "hei"}]}
               {:on-content-delta #(swap! deltas conj %)})]
    (testing label
      (is (= "Maskinporten er en løsning."
             (get-in resp [:choices 0 :message :content]))
          "content is assembled from the deltas")
      (is (= "stop" (get-in resp [:choices 0 :finish_reason])))
      (is (= ["Maskinporten " "er en løsning."] @deltas)
          "on-content-delta fires per fragment, which is what TTFT times"))))

(deftest local-shape-bare-channel
  ;; The measured local shape: wkok returns the ManyToManyChannel itself and
  ;; (:body resp) is nil. This is the one that was broken.
  (with-redefs [api/create-chat-completion (fn [& _] (chan-of sse-events))]
    (assert-assembles "bare channel (OpenAI-compatible / local)")))

(deftest azure-shape-body-channel
  ;; The Azure shape, which already worked and must keep working.
  (with-redefs [api/create-chat-completion (fn [& _] {:body (chan-of sse-events)})]
    (assert-assembles "{:body channel} (Azure)")))

(deftest azure-shape-with-wkok-opts
  ;; Azure is reached through the two-arg wkok call (:api-key/:impl present),
  ;; a different branch inside streaming-chat-completion than the local path.
  (with-redefs [api/create-chat-completion (fn [_params opts]
                                             (is (= :azure (:impl opts)))
                                             (is (not (contains? opts :on-content-delta))
                                                 "on-content-delta is ours, not wkok's")
                                             {:body (chan-of sse-events)})]
    (let [resp (llm/streaming-chat-completion
                 {:model "gpt-4o" :messages [{:role "user" :content "hei"}]}
                 {:impl :azure :api-key "k" :api-endpoint "e"
                  :on-content-delta (fn [_])})]
      (is (= "Maskinporten er en løsning."
             (get-in resp [:choices 0 :message :content]))))))

(deftest third-shape-names-the-streaming-path
  ;; The error a reader actually gets. The old one named a core.async protocol
  ;; and sent people to the agent loop, three layers from the assumption.
  (with-redefs [api/create-chat-completion (fn [& _] {:status 200 :body nil})]
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         (llm/streaming-chat-completion {:model "m" :messages []})))
          msg (.getMessage ^Exception e)]
      (is (str/includes? (str/lower-case msg) "streaming")
          "the message names the streaming path")
      (is (not (str/includes? msg "ReadPort"))
          "and does not surface the core.async protocol error instead")
      (is (= :streaming (:llm/path (ex-data e)))
          "ex-data carries the path for a caller that wants to branch"))))

;; ---------------------------------------------------------------------------
;; Token usage on the streaming path.
;;
;; Found while verifying the shape fix against the real local endpoint: the
;; streaming response came back with :usage nil. #25's latency decomposition
;; classifies a stage as an LLM call by `(some? (:usage %))`, so nil usage
;; sends every LLM stage into :other-ms and reads :llm-ms as 0. The server
;; only sends the usage chunk when asked for it.
;; ---------------------------------------------------------------------------

(def ^:private usage-chunk
  ;; Measured shape from the local endpoint: choices is EMPTY on this one.
  {:choices []
   :usage {:prompt_tokens 13 :completion_tokens 300 :total_tokens 313
           :completion_tokens_details {:reasoning_tokens 300}}})

(deftest usage-chunk-is-assembled
  (with-redefs [api/create-chat-completion
                (fn [& _] (chan-of (concat sse-events [usage-chunk])))]
    (let [resp (llm/streaming-chat-completion {:model "m" :messages []})]
      (is (= 313 (get-in resp [:usage :total_tokens]))
          "usage reaches the assembled response, so :llm-ms sees an LLM call")
      (is (= "Maskinporten er en løsning."
             (get-in resp [:choices 0 :message :content]))
          "and the empty-choices chunk does not disturb the content"))))

(deftest requests-usage-on-both-branches
  ;; Renamed from `requests-usage-except-on-azure`. The Azure exclusion was
  ;; justified as "supported only from api-version 2024-06-01" — but wkok's
  ;; Azure impl hardcodes exactly `2024-06-01` (azure.clj `patch-params`), so
  ;; the condition was already met and withholding it only cost us the data.
  ;; Verified on the wire at that api-version: `stream` alone returns 200 with
  ;; no prompt_tokens in the stream; `stream` + `include_usage` returns 200
  ;; with them. Until this, every streamed call reported nil usage, so #25
  ;; classified Playground LLM calls as missing-usage and its token columns
  ;; read 0 — while the sweep runner, being non-streaming, always had usage.
  (testing "OpenAI-compatible: ask for the usage chunk"
    (let [!params (atom nil)]
      (with-redefs [api/create-chat-completion
                    (fn [params & _] (reset! !params params) (chan-of sse-events))]
        (llm/streaming-chat-completion {:model "m" :messages []})
        (is (= {:include_usage true} (:stream_options @!params))))))
  (testing "Azure: asks too, so Playground cost is measurable at all"
    (let [!params (atom nil)]
      (with-redefs [api/create-chat-completion
                    (fn [params _opts] (reset! !params params) {:body (chan-of sse-events)})]
        (llm/streaming-chat-completion {:model "m" :messages []}
                                       {:impl :azure :api-key "k" :api-endpoint "e"})
        (is (= {:include_usage true} (:stream_options @!params)))))))

(defn- chan-left-open
  "A channel carrying `events` that is deliberately NEVER closed — the shape
   wkok leaves behind on Azure."
  [events]
  (let [ch (async/chan (max 1 (count events)))]
    (doseq [e events] (async/>!! ch e))
    ch))

(deftest azure-stream-without-done-must-not-hang
  ;; `:stream/close?` is not sent on Azure: wkok's Azure impl serializes it into
  ;; the request body (400) and never forwards it to `sse/sse-events` anyway, so
  ;; it could not have closed the channel there even before it was removed.
  ;; `sse-events` closes the events channel only `(when close? ...)`, so on Azure
  ;; it is NEVER closed and termination rests solely on a `[DONE]` event.
  ;;
  ;; A stream cut short without `[DONE]` — provider error mid-stream, content
  ;; filter abort, dropped connection — therefore left `async/<!!` blocking
  ;; FOREVER, hanging the agent iteration with no timeout anywhere above it.
  ;; The drain loop needs its own idle bound; this pins that it has one.
  (with-redefs [api/create-chat-completion (fn [& _] {:body (chan-left-open sse-events)})
                digdir.llm.openai/stream-idle-timeout-ms (constantly 250)]
    (let [f (future (try (llm/streaming-chat-completion
                           {:model "gpt-5.6-sol" :messages []}
                           {:impl :azure :api-key "k" :api-endpoint "e"
                            :on-content-delta (fn [_])})
                         (catch Exception e e)))
          r (deref f 15000 ::blocked)]
      (is (not= ::blocked r)
          "the drain loop blocked forever on a stream that sent no [DONE]")
      (when (not= ::blocked r)
        (is (instance? clojure.lang.ExceptionInfo r)
            "an idle stream should surface a named error, not a silent partial answer")
        (is (= :streaming (:llm/path (ex-data r)))
            "the error must name the streaming path, like the no-channel error does")))))

(ns digdir.llm.model-params-test
  "Pins the per-model-family chat-completion parameter mapping, and the wiring
   that applies it on both branches of `digdir.llm.client/create-chat-completion`.

   No live provider is involved: the direct branch is exercised by redefining
   `clj-http.client/post` and reading the encoded request body; the Azure branch
   by redefining `wkok.openai-clojure.api/create-chat-completion` and capturing
   the params it would have sent.

   The STREAMING branch is covered here too. `digdir.llm.client` is
   non-streaming only, so it is not the single chokepoint its docstring implies:
   `digdir.llm.openai/streaming-chat-completion` is a second one, and the agent
   loop reaches it whenever a `progress-fn` is supplied — which is every
   playground run. A model-family mapping applied on only one of the two is the
   defect these tests exist to catch."
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [digdir.llm.client :as client]
            [digdir.llm.model-params :as model-params]
            [digdir.llm.openai :as llm]
            [wkok.openai-clojure.api :as wkok]))

(def ^:private messages [{:role "user" :content "hi"}])

(deftest gpt-5-family-detection
  (testing "GPT-5-family model and Azure-deployment names are recognised"
    (doseq [model ["gpt-5" "gpt5" "gpt-5.5" "gpt-5.5-chat" "gpt-5.4-mini"
                   "GPT-5" "azure-gpt-5.5" "gpt-5-mini"]]
      (is (true? (model-params/gpt-5-family? model))
          (str model " should be detected as GPT-5 family"))))

  (testing "other families — and a hypothetical gpt-50 — are not"
    (doseq [model ["gpt-4o" "gpt-4o-mini" "gpt-4" "gpt-3.5-turbo" "gpt-50"
                   "qwen3.6-35b-a3b" "gemma-4-12b" "claude-opus-5" nil ""]]
      (is (false? (model-params/gpt-5-family? model))
          (str (pr-str model) " should NOT be detected as GPT-5 family")))))

(deftest normalize-request-maps-max-tokens-for-gpt-5
  (testing "GPT-5: :max_tokens becomes :max_completion_tokens, value preserved"
    (is (= {:model "gpt-5.5" :messages messages :max_completion_tokens 30}
           (model-params/normalize-request
            {:model "gpt-5.5" :messages messages :max_tokens 30}))))

  (testing "a caller-supplied :max_completion_tokens wins; the rejected key is dropped"
    (is (= {:model "gpt-5.5" :messages messages :max_completion_tokens 128}
           (model-params/normalize-request
            {:model "gpt-5.5" :messages messages
             :max_tokens 30 :max_completion_tokens 128}))))

  (testing "nothing is invented when the caller sets no cap at all"
    (let [params {:model "gpt-5.5" :messages messages}]
      (is (= params (model-params/normalize-request params)))
      (is (not (contains? (model-params/normalize-request params)
                          :max_completion_tokens))))))

(deftest normalize-request-handles-gpt-5-temperature-constraint
  (testing "a non-default temperature is dropped (GPT-5 rejects it with a 400)"
    (is (= {:model "gpt-5.5" :messages messages}
           (model-params/normalize-request
            {:model "gpt-5.5" :messages messages :temperature 0.1}))))

  (testing "the one accepted value, 1, is preserved — int and double alike"
    (is (= 1 (:temperature (model-params/normalize-request
                            {:model "gpt-5.5" :messages messages :temperature 1}))))
    (is (= 1.0 (:temperature (model-params/normalize-request
                              {:model "gpt-5.5" :messages messages :temperature 1.0})))))

  (testing "an explicit nil temperature is dropped rather than sent as null"
    (is (not (contains? (model-params/normalize-request
                         {:model "gpt-5.5" :messages messages :temperature nil})
                        :temperature)))))

(deftest normalize-request-leaves-other-families-untouched
  (testing "GPT-4 and local models keep :max_tokens and :temperature verbatim"
    (doseq [model ["gpt-4o" "gpt-4o-mini" "qwen3.6-35b-a3b"]]
      (let [params {:model model :messages messages :max_tokens 30 :temperature 0.1}]
        (is (= params (model-params/normalize-request params))
            (str model " must be passed through unchanged")))))

  (testing "unrelated params survive normalization on the GPT-5 path"
    (let [normalized (model-params/normalize-request
                      {:model "gpt-5.5" :messages messages :max_tokens 30
                       :temperature 0.1 :tools [:t] :response_format {:type "json_object"}
                       :stream false})]
      (is (= messages (:messages normalized)))
      (is (= [:t] (:tools normalized)))
      (is (= {:type "json_object"} (:response_format normalized)))
      (is (false? (:stream normalized))))))

(defn- capture-direct-body
  "Run `client/create-chat-completion` down the non-Azure branch, returning the
   decoded JSON request body that would have been POSTed."
  [params]
  (let [!body (atom nil)]
    (with-redefs [http/post (fn [_url opts]
                              (reset! !body (json/parse-string (:body opts) true))
                              {:body {:choices []}})]
      (client/create-chat-completion params {:api-key "k" :api-endpoint "http://localhost"}))
    @!body))

(defn- capture-azure-params
  "Run `client/create-chat-completion` down the `:impl :azure` branch, returning
   the params handed to wkok."
  [params]
  (let [!params (atom nil)]
    (with-redefs [wkok/create-chat-completion (fn [p _opts]
                                                (reset! !params p)
                                                {:choices []})]
      (client/create-chat-completion params {:api-key "k"
                                             :api-endpoint "http://localhost"
                                             :impl :azure}))
    @!params))

(deftest client-normalizes-on-the-direct-branch
  (testing "GPT-5 request reaches the wire with max_completion_tokens, no max_tokens"
    (let [body (capture-direct-body {:model "gpt-5.5" :messages messages
                                     :max_tokens 30 :temperature 0.1})]
      (is (= 30 (:max_completion_tokens body)))
      (is (not (contains? body :max_tokens)))
      (is (not (contains? body :temperature)))))

  (testing "a non-GPT-5 model still sends max_tokens and temperature"
    (let [body (capture-direct-body {:model "gpt-4o" :messages messages
                                     :max_tokens 30 :temperature 0.1})]
      (is (contains? body :max_tokens))
      (is (contains? body :temperature))
      (is (not (contains? body :max_completion_tokens))))))

(deftest client-normalizes-on-the-azure-branch
  (testing "GPT-5 deployment gets max_completion_tokens before wkok delegation"
    (let [params (capture-azure-params {:model "gpt-5.5" :messages messages
                                        :max_tokens 30 :temperature 0.1})]
      (is (= 30 (:max_completion_tokens params)))
      (is (not (contains? params :max_tokens)))
      (is (not (contains? params :temperature)))))

  (testing "a GPT-4 deployment is delegated unchanged"
    (let [params {:model "gpt-4o" :messages messages :max_tokens 30 :temperature 0.1}]
      (is (= params (capture-azure-params params))))))

;; ---------------------------------------------------------------------------
;; Streaming branch — digdir.llm.openai/streaming-chat-completion
;; ---------------------------------------------------------------------------

(defn- chan-of
  "A closed channel carrying `events`, buffered so puts never block."
  [events]
  (let [ch (async/chan (max 1 (count events)))]
    (doseq [e events] (async/>!! ch e))
    (async/close! ch)
    ch))

(def ^:private sse-events
  "The minimum an OpenAI-compatible server sends: one delta, then a finish."
  [{:choices [{:delta {:content "ok"} :index 0}]}
   {:choices [{:delta {} :finish_reason "stop" :index 0}]}])

(defn- capture-streaming-params
  "Run `llm/streaming-chat-completion` and return the params handed to wkok.

   `opts` selects the branch, and the two wkok impls return DIFFERENT shapes
   (#305): Azure answers `{:body <channel>}`, the OpenAI-compatible one answers
   the bare channel. The stub honours both so a branch is never asserted
   against the other's shape."
  [params opts]
  (let [!params (atom nil)
        azure? (= :azure (:impl opts))]
    (with-redefs [wkok/create-chat-completion
                  (fn [p & _]
                    (reset! !params p)
                    (if azure?
                      {:body (chan-of sse-events)}
                      (chan-of sse-events)))]
      (llm/streaming-chat-completion params (assoc opts :on-content-delta (fn [_]))))
    @!params))

(deftest gpt-5-6-deployment-names-are-detected
  ;; The names actually present on the Azure resource. `gpt-5.6-sol` is the
  ;; reasoning deployment this coverage was added for; the regex must match it
  ;; on name alone, since on the Azure path the deployment name is the only
  ;; family signal available.
  (testing "gpt-5.6-* deployments are GPT-5 family"
    (doseq [model ["gpt-5.6-sol" "gpt-5.6-terra" "gpt-5.6" "GPT-5.6-SOL"]]
      (is (true? (model-params/gpt-5-family? model))
          (str model " should be detected as GPT-5 family")))))

(deftest streaming-normalizes-on-the-azure-branch
  (testing "gpt-5.6-sol: temperature dropped and max_tokens renamed before wkok"
    (let [params (capture-streaming-params
                   {:model "gpt-5.6-sol" :messages messages
                    :max_tokens 30 :temperature 0.3}
                   {:impl :azure :api-key "k" :api-endpoint "e"})]
      (is (not (contains? params :temperature))
          "a reasoning model hard-400s on any temperature but 1")
      (is (= 30 (:max_completion_tokens params)))
      (is (not (contains? params :max_tokens)))
      (is (true? (:stream params))
          "normalization must not cost us the streaming request")))

  (testing "temperature 1 is the one value a reasoning model accepts"
    (let [params (capture-streaming-params
                   {:model "gpt-5.6-sol" :messages messages :temperature 1}
                   {:impl :azure :api-key "k" :api-endpoint "e"})]
      (is (= 1 (:temperature params)))))

  (testing "a gpt-4o deployment keeps temperature on the same path"
    (let [params (capture-streaming-params
                   {:model "gpt-4o-2M-tps" :messages messages
                    :max_tokens 30 :temperature 0.3}
                   {:impl :azure :api-key "k" :api-endpoint "e"})]
      (is (= 0.3 (:temperature params)))
      (is (= 30 (:max_tokens params)))
      (is (not (contains? params :max_completion_tokens))))))

(deftest streaming-normalizes-on-the-openai-branch
  (testing "the non-Azure streaming branch normalizes too"
    (let [params (capture-streaming-params
                   {:model "gpt-5.6-sol" :messages messages
                    :max_tokens 30 :temperature 0.3}
                   {})]
      (is (not (contains? params :temperature)))
      (is (= 30 (:max_completion_tokens params)))))

  (testing "a local model is passed through untouched"
    (let [params (capture-streaming-params
                   {:model "qwen/qwen3.6-35b-a3b" :messages messages
                    :temperature 0.3}
                   {})]
      (is (= 0.3 (:temperature params))))))

(deftest azure-streaming-omits-the-wkok-control-key
  ;; `:stream/close?` is a documented wkok parameter (doc/03-streaming.md,
  ;; added 0.21.0 / PR 63) that wkok's Azure impl — added later, in 0.21.2 /
  ;; PR 73 — never learned about: `azure/patch-params` neither strips it from
  ;; `:martian.core/body` nor forwards it to `sse/sse-events`. So on Azure it
  ;; is serialized into the request JSON, where Azure answers 400
  ;; "Unrecognized request argument supplied: stream/close?", AND it never
  ;; closes the channel it exists to close. The drain loop terminates on the
  ;; `:done` event instead, so omitting it on Azure costs nothing.
  (testing "Azure gets no :stream/close?"
    (let [params (capture-streaming-params
                   {:model "gpt-4o-2M-tps" :messages messages}
                   {:impl :azure :api-key "k" :api-endpoint "e"})]
      (is (not (contains? params :stream/close?)))))

  (testing "the OpenAI-compatible branch still gets it, where it works"
    (let [params (capture-streaming-params
                   {:model "gpt-4o" :messages messages} {})]
      (is (true? (:stream/close? params))))))

(deftest azure-streaming-asks-for-usage
  ;; `stream_options {:include_usage true}` USED to be withheld from Azure, on
  ;; the grounds that it needs api-version >= 2024-06-01. wkok's Azure impl
  ;; hardcodes exactly `2024-06-01` (azure.clj `patch-params`), so that
  ;; condition was already satisfied and the exclusion only cost us the data.
  ;;
  ;; Verified on the wire against the live deployment at that api-version:
  ;;   stream only            -> 200, no prompt_tokens anywhere in the stream
  ;;   stream + include_usage -> 200, prompt_tokens present
  ;;
  ;; Without it every streamed call reported nil usage, so the #25 latency
  ;; decomposition classified Playground LLM calls as "missing usage" and every
  ;; token column read 0 there — Playground cost was unmeasurable, and not
  ;; comparable to the sweep runner, which is non-streaming and always had it.
  (testing "Azure streaming requests usage in the final chunk"
    (let [params (capture-streaming-params
                   {:model "gpt-4o-2M-tps" :messages messages}
                   {:impl :azure :api-key "k" :api-endpoint "e"})]
      (is (= {:include_usage true} (:stream_options params)))))

  (testing "the OpenAI-compatible branch still asks too"
    (let [params (capture-streaming-params {:model "gpt-4o" :messages messages} {})]
      (is (= {:include_usage true} (:stream_options params))))))

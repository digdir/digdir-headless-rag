(ns digdir.llm.call-provenance-test
  "Phase 0 of the provider-resolver change, wire half: every LLM call records what was ACTUALLY sent —
   branch, endpoint host, key presence (never the value), and the model /
   temperature / max-tokens trio both as the caller passed it and as it left
   after the `OPENAI_*` env merge and model-family normalisation.

   Three chokepoints, each stubbed at its TRANSPORT so the record is taken above
   the stub (a stub on `client/create-chat-completion` itself would sit above
   the record and see nothing):
   - blocking, Azure  → `wkok.openai-clojure.api/create-chat-completion`
   - blocking, direct → `clj-http.client/post`
   - streaming, both  → `wkok.openai-clojure.api/create-chat-completion`"
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-http.client :as http]
            [digdir.llm.client :as client]
            [digdir.llm.openai :as llm]
            [digdir.llm.provenance :as provenance]
            [digdir.secrets :as secrets]
            [wkok.openai-clojure.api :as wkok]))

(def ^:private messages [{:role "user" :content "hi"}])

(def ^:private secret-key "sk-THIS-MUST-NEVER-APPEAR-IN-A-RECORD")

(defn- no-env-overrides
  "Neutralise the numeric `OPENAI_*` override env so a test's result does not
   depend on whose shell ran it."
  [_k]
  nil)

(defn- calls
  "Run `f` inside a provenance sink and return only the :llm-call events."
  [f]
  (->> (provenance/capture f) :events (filterv #(= :llm-call (:event %)))))

(defn- direct-call [params opts]
  (calls #(with-redefs [http/post (fn [_url _opts] {:body {:choices []}})]
            (client/create-chat-completion params opts))))

(defn- azure-call [params opts]
  (calls #(with-redefs [wkok/create-chat-completion (fn [_p _o] {:choices []})]
            (client/create-chat-completion params (assoc opts :impl :azure)))))

(defn- chan-of [events]
  (let [ch (async/chan (max 1 (count events)))]
    (doseq [e events] (async/>!! ch e))
    (async/close! ch)
    ch))

(def ^:private sse-events
  [{:choices [{:delta {:content "ok"} :index 0}]}
   {:choices [{:delta {} :finish_reason "stop" :index 0}]}])

(defn- streaming-call [params opts]
  (let [azure? (= :azure (:impl opts))]
    (calls #(with-redefs [wkok/create-chat-completion
                          (fn [& _] (if azure? {:body (chan-of sse-events)} (chan-of sse-events)))]
              (llm/streaming-chat-completion params (assoc opts :on-content-delta (fn [_])))))))

;; =============================================================================
;; Blocking, direct (non-Azure) branch
;; =============================================================================

(deftest direct-branch-records-one-call-with-its-destination
  (with-redefs [client/env-num no-env-overrides]
    (let [[rec & more] (direct-call {:model "qwen3.6" :messages messages
                                     :temperature 0.3 :max_tokens 400}
                                    {:api-key secret-key :api-endpoint "http://localhost:1234/v1"})]
      (testing "absolute: exactly one record, of the expected shape"
        (is (some? rec))
        (is (empty? more)))
      (is (= :blocking (:path rec)))
      (is (= :openai (:branch rec)))
      (is (= "localhost:1234" (:endpoint-host rec)))
      (is (= :opts (:endpoint-from rec)))
      (is (true? (:key-present? rec)))
      (is (= :opts (:key-from rec)))
      (is (false? (:endpoint-rederived? rec)) "the direct branch OBSERVES its endpoint")
      (is (false? (:key-rederived? rec)) "… and its key")
      (is (= {:model "qwen3.6" :temperature 0.3 :max_tokens 400} (:caller rec)))
      (is (= {:model "qwen3.6" :temperature 0.3 :max_tokens 400} (:sent rec)))
      (is (false? (:normalized? rec))))))

(deftest direct-branch-records-the-env-override-layer
  ;; client.clj lets OPENAI_TEMPERATURE / OPENAI_MAX_TOKENS override the
  ;; caller on the direct branch only. Phase 3 moves subsystems onto this
  ;; branch, so temperature can change with no step-level source changing —
  ;; the record must say where the sent value came from.
  (with-redefs [client/env-num (fn [k] (get {"OPENAI_TEMPERATURE" 0.9} k))]
    (let [[rec] (direct-call {:model "qwen3.6" :messages messages :temperature 0.3}
                             {:api-key "k" :api-endpoint "http://localhost:1234/v1"})]
      (is (= 0.3 (get-in rec [:caller :temperature])) "the caller's value is kept …")
      (is (= 0.9 (get-in rec [:sent :temperature])) "… and the wire value is the env's")
      (is (contains? (:env-applied rec) :temperature))
      (is (not (contains? (:env-applied rec) :max_tokens))))))

(deftest direct-branch-records-normalisation
  (with-redefs [client/env-num no-env-overrides]
    (let [[rec] (direct-call {:model "gpt-5.5" :messages messages :temperature 0.3 :max_tokens 400}
                             {:api-key "k" :api-endpoint "http://localhost:1234/v1"})]
      (is (= {:model "gpt-5.5" :temperature 0.3 :max_tokens 400} (:caller rec)))
      (is (= {:model "gpt-5.5" :max_completion_tokens 400} (:sent rec)))
      (is (true? (:normalized? rec))))))

(deftest direct-branch-key-from-the-secret-store-is-recorded-by-presence-only
  (with-redefs [client/env-num no-env-overrides
                secrets/get! (fn [k] (when (= k :openai-api-key) secret-key))]
    (let [[rec] (direct-call {:model "m" :messages messages}
                             {:api-endpoint "http://localhost:1234/v1"})]
      (is (true? (:key-present? rec)))
      (is (= :secret (:key-from rec)))
      (is (not (str/includes? (pr-str rec) secret-key))))))

(deftest direct-branch-endpoint-provenance-without-opts
  (with-redefs [client/env-num no-env-overrides]
    (let [[rec] (direct-call {:model "m" :messages messages} {:api-key "k"})
          env-endpoint (System/getenv "OPENAI_API_ENDPOINT")]
      (if env-endpoint
        (is (= :env (:endpoint-from rec)))
        (do (is (= :default (:endpoint-from rec)))
            (is (= "api.openai.com" (:endpoint-host rec))))))))

;; =============================================================================
;; Blocking, Azure branch
;; =============================================================================

(deftest azure-branch-records-one-call-with-its-destination
  (let [[rec & more] (azure-call {:model "gpt-4o-deploy" :messages messages :temperature 0.1}
                                 {:api-key secret-key :api-endpoint "https://example.openai.azure.com"})]
    (is (some? rec))
    (is (empty? more))
    (is (= :blocking (:path rec)))
    (is (= :azure (:branch rec)))
    (is (= "example.openai.azure.com" (:endpoint-host rec)))
    (is (= :opts (:endpoint-from rec)))
    (is (true? (:key-present? rec)))
    (is (false? (:endpoint-rederived? rec)))
    (is (false? (:key-rederived? rec)))
    (is (= {:model "gpt-4o-deploy" :temperature 0.1} (:sent rec)))
    (is (= #{} (:env-applied rec)) "no env-override layer exists on the Azure branch")
    (is (not (str/includes? (pr-str rec) secret-key)))))

(deftest azure-branch-with-no-configured-credentials-shows-wkoks-env-door
  ;; A tenant with no Azure api-key/endpoint hands wkok nil opts, and wkok
  ;; then reads AZURE_OPENAI_API_KEY / AZURE_OPENAI_API_ENDPOINT from the
  ;; process env (wkok 0.23.0 azure.clj) — per-tenant config silently replaced
  ;; by process-global. The record must say so, as a re-derivation.
  (let [[rec] (azure-call {:model "m" :messages messages} {:api-key nil :api-endpoint nil})
        env-key (System/getenv "AZURE_OPENAI_API_KEY")
        env-endpoint (System/getenv "AZURE_OPENAI_API_ENDPOINT")]
    (is (true? (:key-rederived? rec)))
    (is (true? (:endpoint-rederived? rec)))
    (is (= (when env-key :env) (:key-from rec)))
    (is (= (when env-endpoint :env) (:endpoint-from rec)))
    ;; Compare booleans only: a failing assertion prints its evaluated
    ;; arguments, and the real process key must never reach a test log.
    (is (= (not (str/blank? env-key)) (:key-present? rec)))))

(deftest azure-branch-records-normalisation
  (let [[rec] (azure-call {:model "gpt-5.6-sol" :messages messages :temperature 0.3 :max_tokens 30}
                          {:api-key "k" :api-endpoint "https://example.openai.azure.com"})]
    (is (= {:model "gpt-5.6-sol" :max_completion_tokens 30} (:sent rec)))
    (is (true? (:normalized? rec)))))

;; =============================================================================
;; Streaming (openai.cljc) — the second chokepoint
;; =============================================================================

(deftest streaming-azure-records-one-call
  (let [[rec & more] (streaming-call {:model "gpt-5.6-sol" :messages messages :temperature 0.3}
                                     {:impl :azure :api-key secret-key
                                      :api-endpoint "https://example.openai.azure.com"})]
    (is (some? rec))
    (is (empty? more))
    (is (= :streaming (:path rec)))
    (is (= :azure (:branch rec)))
    (is (= "example.openai.azure.com" (:endpoint-host rec)))
    (is (= {:model "gpt-5.6-sol"} (:sent rec)) "temperature normalised away for a reasoning deployment")
    (is (not (str/includes? (pr-str rec) secret-key)))))

(deftest streaming-direct-records-one-call-and-no-env-layer
  ;; The streaming direct branch goes through wkok, not clj-http, so the
  ;; OPENAI_* override layer does not exist there. Today's asymmetry, recorded.
  (with-redefs [client/env-num (fn [k] (get {"OPENAI_TEMPERATURE" 0.9} k))]
    (let [[rec] (streaming-call {:model "qwen3.6" :messages messages :temperature 0.3}
                                {:api-key "k" :api-endpoint "http://localhost:1234/v1"})]
      (is (= :streaming (:path rec)))
      (is (= :openai (:branch rec)))
      (is (= "localhost:1234" (:endpoint-host rec)))
      (is (= 0.3 (get-in rec [:sent :temperature])))
      (is (= #{} (:env-applied rec))))))

;; =============================================================================
;; The record never gets in the way of the call
;; =============================================================================

(deftest no-sink-bound-the-call-is-unchanged
  (with-redefs [client/env-num no-env-overrides
                http/post (fn [_url _opts] {:body {:choices [{:message {:content "fine"}}]}})]
    (is (= "fine" (-> (client/create-chat-completion {:model "m" :messages messages}
                                                     {:api-key "k" :api-endpoint "http://localhost:1234/v1"})
                      :choices first :message :content)))))

(deftest an-unparseable-endpoint-does-not-fail-the-call
  (with-redefs [client/env-num no-env-overrides]
    (let [recs (direct-call {:model "m" :messages messages}
                            {:api-key "k" :api-endpoint "not a url at all"})]
      (is (= 1 (count recs)) "the call went ahead and was still recorded")
      (is (nil? (:endpoint-host (first recs)))))))

;; =============================================================================
;; The source tag (Phase 1 of the provider-resolver change; guard 4 from Phase 2 on)
;; =============================================================================
;;
;; Only the resolver knows whether a credential came from config (or, from
;; Phase 2, a sweep's run override): at the wire both arrive as plain opts. So
;; `provider/resolve` tags them under `:provider/source`, and the call record
;; carries that tag. A call site that drops the tag must read as `:untagged` —
;; an ABSENCE — never as a default like `:config`, or the guard would be green
;; exactly when the mechanism it guards has broken.

(def ^:private tagged-azure-opts
  {:impl :azure :api-key "k" :api-endpoint "https://example.openai.azure.com"
   :provider/source {:api-key {:from :config :path "services.azure-openai.api-key" :present? true}
                     :api-endpoint {:from :config :path "services.azure-openai.api-endpoint" :present? true}}})

(def ^:private tagged-openai-compatible-opts
  {:impl :openai :api-key nil :api-endpoint nil
   :provider/source {:api-key {:from :unresolved} :api-endpoint {:from :unresolved}}})

(deftest the-source-tag-reaches-the-record-on-every-transport
  (with-redefs [client/env-num no-env-overrides
                secrets/get! (constantly "s")]
    (testing "blocking Azure"
      (let [[rec] (azure-call {:model "m" :messages messages} tagged-azure-opts)]
        (is (= :config (:key-source rec)))
        (is (= :config (:endpoint-source rec)))))
    (testing "blocking direct"
      (let [[rec] (direct-call {:model "m" :messages messages} tagged-openai-compatible-opts)]
        (is (= :unresolved (:key-source rec)))
        (is (= :unresolved (:endpoint-source rec)))))
    (testing "streaming Azure"
      (let [[rec] (streaming-call {:model "m" :messages messages} tagged-azure-opts)]
        (is (= :config (:key-source rec)))))
    (testing "streaming direct"
      (let [[rec] (streaming-call {:model "m" :messages messages} tagged-openai-compatible-opts)]
        (is (= :unresolved (:key-source rec)))))))

(deftest a-dropped-tag-reads-as-untagged-never-as-a-default
  (with-redefs [client/env-num no-env-overrides
                secrets/get! (constantly "s")]
    (doseq [[label recs] [["blocking Azure" (azure-call {:model "m" :messages messages}
                                                        (dissoc tagged-azure-opts :provider/source))]
                          ["blocking direct" (direct-call {:model "m" :messages messages}
                                                          (dissoc tagged-openai-compatible-opts :provider/source))]
                          ["streaming Azure" (streaming-call {:model "m" :messages messages}
                                                             (dissoc tagged-azure-opts :provider/source))]
                          ["streaming, no opts at all" (streaming-call {:model "m" :messages messages} {})]]
            :let [[rec] recs]]
      (is (some? rec) (str label ": absolute — there is a record to read"))
      (is (= :untagged (:key-source rec)) label)
      (is (= :untagged (:endpoint-source rec)) label))))

(deftest the-tag-is-never-sent
  ;; The tag rides on the opts map, which the direct branch never serialises.
  (with-redefs [client/env-num no-env-overrides]
    (let [!body (atom nil)]
      (with-redefs [http/post (fn [_ opts] (reset! !body (:body opts)) {:body {:choices []}})]
        (client/create-chat-completion {:model "m" :messages messages}
                                       (assoc tagged-openai-compatible-opts :api-key "k")))
      (is (string? @!body) "absolute: a body was sent")
      (is (not (str/includes? @!body "provider/source")))
      (is (not (str/includes? @!body "unresolved"))))))

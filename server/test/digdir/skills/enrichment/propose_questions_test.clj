(ns digdir.skills.enrichment.propose-questions-test
  "Phase B.2 — unit coverage for :builtin/enrichment-propose-questions.

   We never call the real LLM here; the eval-delta gate in Phase B.5 is
   where we measure real generation quality. These tests pin the wiring
   only:

   1. Skill is registered on namespace load.
   2. Prompt rendering substitutes all four placeholders.
   3. Response parsing tolerates the three common shapes the LLM
      produces despite the prompt: clean lines, numbered, bulleted.
   4. The skill body assembles questions + provenance from a stubbed
      OpenAI response and caps at :question-count."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.enrichment.propose-questions :as pq]
            [digdir.config.accessor :as cfg]
            [digdir.llm.client :as client]
            [wkok.openai-clojure.api :as openai]))

(use-fixtures :once
  (fn [t]
    (pq/register!)
    (t)))

(deftest skill-registered
  (testing ":builtin/enrichment-propose-questions is in the skills registry"
    (is (some? (skills/get-skill :builtin/enrichment-propose-questions)))))

(deftest prompt-substitution
  (testing "render-prompt fills all four placeholders even when some are blank"
    (let [out (pq/render-prompt pq/default-prompt-template
                                {:question-count 3
                                 :doc-title "Lov om Altinn"
                                 :doc-url "https://example.no/altinn"
                                 :chunk-content "Altinn 3 ble lansert i juni 2020."})]
      (is (.contains out "3") "question-count substituted")
      (is (.contains out "Lov om Altinn") "doc-title substituted")
      (is (.contains out "https://example.no/altinn") "doc-url substituted")
      (is (.contains out "Altinn 3 ble lansert i juni 2020.") "chunk-content substituted")
      (is (not (.contains out "{{")) "no unsubstituted placeholders remain"))
    (testing "Blank optional inputs render as empty strings, not as the literal placeholder"
      (let [out (pq/render-prompt pq/default-prompt-template
                                  {:question-count 4
                                   :doc-title nil
                                   :doc-url nil
                                   :chunk-content "x"})]
        (is (not (.contains out "{{doc-title}}")))
        (is (not (.contains out "{{doc-url}}")))))))

(defn- stub-response
  "Build the minimal OpenAI response shape the parser walks."
  [content]
  {:choices [{:message {:content content}}]})

(defn- install
  "An accessor stub: `values` by path, nil for everything else. Since the provider-resolver change
   Phase 3 the skill resolves its call through `digdir.llm.provider`, so the
   stub has to answer the provider decision and the branch's credentials - a
   stub that answered every path with one string would be read as the provider
   and refused."
  [values]
  (fn [_opts & path] (get values (vec path))))

(def ^:private azure-install
  (install {[:services :llm :provider] :azure
            [:services :azure-openai :api-key] "az-key"
            [:services :azure-openai :api-endpoint] "https://azure.invalid"
            [:services :azure-openai :deployment-name] "stub-azure-deployment"}))

(def ^:private openai-compatible-install
  (install {[:services :llm :provider] :openai-compatible
            [:services :llm :api-key] "llm-key"
            [:services :llm :api-endpoint] "http://llm.invalid"
            [:services :azure-openai :model-name] "local-model"}))

(deftest response-parsing
  (testing "Clean one-per-line output passes through trimmed and deduped"
    (is (= ["Når ble Altinn 3 lansert?"
            "Hva er Altinn 3?"]
           (pq/parse-questions-response
            (stub-response "Når ble Altinn 3 lansert?\nHva er Altinn 3?\n")))))
  (testing "Numbered output has the numbers stripped"
    (is (= ["Når ble Altinn 3 lansert?"
            "Hva er Altinn 3?"]
           (pq/parse-questions-response
            (stub-response "1. Når ble Altinn 3 lansert?\n2) Hva er Altinn 3?")))))
  (testing "Bulleted output has the markers stripped"
    (is (= ["Når ble Altinn 3 lansert?"
            "Hva er Altinn 3?"]
           (pq/parse-questions-response
            (stub-response "- Når ble Altinn 3 lansert?\n• Hva er Altinn 3?")))))
  (testing "Duplicate lines collapse and blanks are removed"
    (is (= ["Når ble Altinn 3 lansert?" "Hva er Altinn 3?"]
           (pq/parse-questions-response
            (stub-response "Når ble Altinn 3 lansert?\n\nNår ble Altinn 3 lansert?\nHva er Altinn 3?\n"))))))

(deftest skill-body-happy-path
  (testing "Stubbed LLM response → :questions vector capped at :question-count + provenance"
    (with-redefs [cfg/get azure-install
                  openai/create-chat-completion
                  (fn [_convo _impl-opts]
                    (stub-response
                     "Når ble Altinn 3 lansert?\nHva er Altinn 3?\nHvem driver Altinn 3?\nHva koster Altinn 3?\nEr Altinn 3 åpen for alle?"))]
      (let [result (pq/execute-propose-questions
                    {:inputs {:chunk-id "abc123"
                              :chunk-content "Altinn 3 ble lansert i juni 2020."
                              :doc-title "Lov om Altinn"
                              :doc-url "https://example.no/altinn"}
                     :parameters {:question-count 3
                                  :temperature 0.4}
                     :skill-params {:tenant "digdir"}})
            outputs (skills/get-result-outputs result)]
        (is (skills/result-success? result))
        (is (= "abc123" (:chunk-id outputs)))
        (is (= 3 (count (:questions outputs)))
            "Capped at :question-count even when the model returns more")
        (is (every? string? (:questions outputs)))
        (let [{:keys [model prompt-hash generated-at-ms question-count]} (:provenance outputs)]
          (is (= "stub-azure-deployment" model))
          (is (string? prompt-hash))
          (is (pos-int? generated-at-ms))
          (is (= 3 question-count)))))))

(deftest skill-body-defaults
  (testing "Omitting :question-count defaults to 4"
    (with-redefs [cfg/get azure-install
                  openai/create-chat-completion
                  (fn [_convo _impl-opts]
                    (stub-response
                     "q1\nq2\nq3\nq4\nq5\nq6"))]
      (let [result (pq/execute-propose-questions
                    {:inputs {:chunk-id "c1"
                              :chunk-content "content"
                              :doc-title nil
                              :doc-url nil}
                     :parameters {}
                     :skill-params {:tenant "digdir"}})
            outputs (skills/get-result-outputs result)]
        (is (= 4 (count (:questions outputs))))))))

;; ---------------------------------------------------------------------------
;; enrichment goes through digdir.llm.client on BOTH branches
;; ---------------------------------------------------------------------------
;;
;; Its OpenAI-compatible path used to be a direct POST of its own. Through the
;; client it now gets what every other OpenAI-compatible call gets. That is the
;; collapse working - one path, one set of knobs - but it changes enrichment
;; OUTPUT on any machine that exports those knobs, which is every sweep machine,
;; so it is pinned here rather than left to be found from moved results.

(defn- sent-body
  "Run the skill on `accessor` with the direct POST captured; answers the JSON
   body the client sent, parsed."
  [accessor env]
  (let [!bodies (atom [])]
    (with-redefs [cfg/get accessor
                  client/env-num (fn [k] (get env k))
                  http/post (fn [_url opts]
                                         (swap! !bodies conj (json/parse-string (:body opts) true))
                                         {:body (stub-response "q1\nq2")})]
      (pq/execute-propose-questions {:inputs {:chunk-id "c" :chunk-content "content"}
                                     :parameters {}
                                     :skill-params {:tenant "digdir"}}))
    @!bodies))

(deftest openai-compatible-enrichment-takes-the-clients-inference-overrides
  (testing "OPENAI_TEMPERATURE replaces the skill's own 0.4, as it does for every
            OpenAI-compatible call. The direct POST never applied it."
    (let [[body :as bodies] (sent-body openai-compatible-install {"OPENAI_TEMPERATURE" 0.9})]
      (is (= 1 (count bodies)) "absolute: the call went through the client's direct POST")
      (is (= 0.9 (:temperature body)))
      (is (= "local-model" (:model body)))))
  (testing "with no override exported, the skill's own temperature is sent"
    (is (= 0.4 (:temperature (first (sent-body openai-compatible-install {})))))))

(deftest azure-enrichment-is-untouched-by-the-overrides
  (testing "the Azure branch delegates to wkok, which the client does not apply
            the process-wide overrides to - as before Phase 3 of the provider-resolver change"
    (let [!seen (atom nil)]
      (with-redefs [cfg/get azure-install
                    client/env-num (fn [k] (get {"OPENAI_TEMPERATURE" 0.9} k))
                    openai/create-chat-completion (fn [convo _opts] (reset! !seen convo) (stub-response "q1"))]
        (pq/execute-propose-questions {:inputs {:chunk-id "c" :chunk-content "content"}
                                       :parameters {}
                                       :skill-params {:tenant "digdir"}}))
      (is (= 0.4 (:temperature @!seen)))
      (is (= "stub-azure-deployment" (:model @!seen))))))

(deftest openai-compatible-enrichment-is-retried-on-429
  (testing "the client's 429 retry now covers enrichment's OpenAI-compatible calls"
    (let [!attempts (atom 0)]
      (with-redefs [cfg/get openai-compatible-install
                    client/env-num (constantly nil)
                    client/sleep-ms! (fn [_])
                    http/post (fn [_url _opts]
                                           (if (= 1 (swap! !attempts inc))
                                             (throw (ex-info "clj-http: status 429" {:status 429 :headers {}}))
                                             {:body (stub-response "q1\nq2")}))]
        (let [result (pq/execute-propose-questions {:inputs {:chunk-id "c" :chunk-content "content"}
                                                    :parameters {}
                                                    :skill-params {:tenant "digdir"}})]
          (is (skills/result-success? result))
          (is (= 2 @!attempts) "one 429, then the retry that succeeded"))))))

(deftest openai-compatible-enrichment-sends-one-think-prefill
  (testing "OPENAI_DISABLE_THINKING=true: the client appends the closed-<think> turn, and
            enrichment no longer adds its own - two would be sent if it did"
    (let [!bodies (atom [])]
      (with-redefs [cfg/get openai-compatible-install
                    client/env-num (constantly nil)
                    client/env-flag? (fn [k] (= "OPENAI_DISABLE_THINKING" k))
                    http/post (fn [_url opts]
                                (swap! !bodies conj (json/parse-string (:body opts) true))
                                {:body (stub-response "q1")})]
        (pq/execute-propose-questions {:inputs {:chunk-id "c" :chunk-content "content"}
                                       :parameters {}
                                       :skill-params {:tenant "digdir"}}))
      (is (= 1 (count @!bodies)) "absolute: one call, through the client")
      (is (= 1 (count (filter #(= {:role "assistant" :content "<think></think>"} %)
                              (:messages (first @!bodies)))))))))

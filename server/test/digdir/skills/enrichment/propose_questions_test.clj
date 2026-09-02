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
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.enrichment.propose-questions :as pq]
            [digdir.config.accessor :as cfg]
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
    (with-redefs [cfg/get (fn [_opts & _path] "stub-azure-deployment")
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
    (with-redefs [cfg/get (fn [_opts & _path] "stub-azure-deployment")
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

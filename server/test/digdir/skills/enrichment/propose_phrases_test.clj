(ns digdir.skills.enrichment.propose-phrases-test
  "Phase D1 — unit coverage for :builtin/enrichment-propose-phrases.

   No real LLM. Tests pin the wiring:
   1. Skill registered on namespace load.
   2. Prompt rendering substitutes all four placeholders.
   3. Response parsing tolerates clean/numbered/bulleted shapes.
   4. Skill body assembles phrases + provenance, caps at :phrase-count."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.config.accessor :as cfg]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.enrichment.propose-phrases :as pp]
            [wkok.openai-clojure.api :as openai]))

(use-fixtures :once
  (fn [t]
    (pp/register!)
    (t)))

(deftest skill-registered
  (testing ":builtin/enrichment-propose-phrases is in the skills registry"
    (is (some? (skills/get-skill :builtin/enrichment-propose-phrases)))))

(deftest prompt-substitution
  (testing "render-prompt fills all four placeholders even when some are blank"
    (let [out (pp/render-prompt pp/default-prompt-template
                                {:phrase-count 5
                                 :doc-title "Lov om Altinn"
                                 :doc-url "https://example.no/altinn"
                                 :chunk-content "Altinn 3 ble lansert i juni 2020."})]
      (is (.contains out "5") "phrase-count substituted")
      (is (.contains out "Lov om Altinn") "doc-title substituted")
      (is (.contains out "https://example.no/altinn") "doc-url substituted")
      (is (.contains out "Altinn 3 ble lansert i juni 2020.") "chunk-content substituted")
      (is (not (.contains out "{{")) "no unsubstituted placeholders remain"))
    (testing "Blank optional inputs render as empty strings, not as the literal placeholder"
      (let [out (pp/render-prompt pp/default-prompt-template
                                  {:phrase-count 5
                                   :doc-title nil
                                   :doc-url nil
                                   :chunk-content "x"})]
        (is (not (.contains out "{{doc-title}}")))
        (is (not (.contains out "{{doc-url}}")))))))

(defn- stub-response [content]
  {:choices [{:message {:content content}}]})

(deftest response-parsing
  (testing "Clean one-per-line output passes through trimmed and deduped"
    (is (= ["Altinn 3 lanseringsdato" "Altinn 3 juni 2020"]
           (pp/parse-phrases-response
            (stub-response "Altinn 3 lanseringsdato\nAltinn 3 juni 2020\n")))))
  (testing "Numbered output has the numbers stripped"
    (is (= ["Altinn 3 lanseringsdato" "Altinn 3 juni 2020"]
           (pp/parse-phrases-response
            (stub-response "1. Altinn 3 lanseringsdato\n2) Altinn 3 juni 2020")))))
  (testing "Bulleted output has the markers stripped"
    (is (= ["Altinn 3 lanseringsdato" "Altinn 3 juni 2020"]
           (pp/parse-phrases-response
            (stub-response "- Altinn 3 lanseringsdato\n• Altinn 3 juni 2020")))))
  (testing "Duplicate lines collapse and blanks are removed"
    (is (= ["Altinn 3 lanseringsdato" "Altinn 3 juni 2020"]
           (pp/parse-phrases-response
            (stub-response "Altinn 3 lanseringsdato\n\nAltinn 3 lanseringsdato\nAltinn 3 juni 2020\n"))))))

(deftest skill-body-happy-path
  (testing "Stubbed LLM → :phrases vec capped at :phrase-count + provenance"
    (with-redefs [cfg/get (fn [_opts & _path] "stub-azure-deployment")
                  openai/create-chat-completion
                  (fn [_convo _impl-opts]
                    (stub-response
                     "Altinn 3 lansering\nAltinn 3 oppstartsdato\nFørste versjon Altinn 3\nAltinn 3 juni 2020\nAltinn 3 produksjon\nAltinn 3 søknader 2020"))]
      (let [result (pp/execute-propose-phrases
                    {:inputs {:chunk-id "abc123"
                              :chunk-content "Altinn 3 ble lansert i juni 2020."
                              :doc-title "Lov om Altinn"
                              :doc-url "https://example.no/altinn"}
                     :parameters {:phrase-count 4
                                  :temperature 0.4}
                     :skill-params {:tenant "digdir"}})
            outputs (skills/get-result-outputs result)]
        (is (skills/result-success? result))
        (is (= "abc123" (:chunk-id outputs)))
        (is (= 4 (count (:phrases outputs)))
            "Capped at :phrase-count even when the model returns more")
        (is (every? string? (:phrases outputs)))
        (let [{:keys [model prompt-hash generated-at-ms phrase-count]} (:provenance outputs)]
          (is (= "stub-azure-deployment" model))
          (is (string? prompt-hash))
          (is (pos-int? generated-at-ms))
          (is (= 4 phrase-count)))))))

(deftest skill-body-defaults
  (testing "Omitting :phrase-count defaults to 5"
    (with-redefs [cfg/get (fn [_opts & _path] "stub-azure-deployment")
                  openai/create-chat-completion
                  (fn [_convo _impl-opts]
                    (stub-response "p1\np2\np3\np4\np5\np6\np7"))]
      (let [result (pp/execute-propose-phrases
                    {:inputs {:chunk-id "c1"
                              :chunk-content "content"
                              :doc-title nil
                              :doc-url nil}
                     :parameters {}
                     :skill-params {:tenant "digdir"}})
            outputs (skills/get-result-outputs result)]
        (is (= 5 (count (:phrases outputs))))))))

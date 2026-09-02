(ns digdir.skills.enrichment.propose-facts-test
  "Phase D2 — unit coverage for :builtin/enrichment-propose-facts.

   No real LLM. Tests pin the wiring:
   1. Skill registered on namespace load.
   2. Prompt rendering substitutes all four placeholders.
   3. Response parsing tolerates clean/numbered/bulleted lines and
      collapses semantic duplicates via triple-text.
   4. Skill body assembles facts + provenance, caps at :fact-count."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.config.accessor :as cfg]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.enrichment.propose-facts :as pf]
            [wkok.openai-clojure.api :as openai]))

(use-fixtures :once
  (fn [t]
    (pf/register!)
    (t)))

(deftest skill-registered
  (testing ":builtin/enrichment-propose-facts is in the skills registry"
    (is (some? (skills/get-skill :builtin/enrichment-propose-facts)))))

(deftest prompt-substitution
  (testing "render-prompt fills all four placeholders even when some are blank"
    (let [out (pf/render-prompt pf/default-prompt-template
                                {:fact-count 5
                                 :doc-title "Lov om Altinn"
                                 :doc-url "https://example.no/altinn"
                                 :chunk-content "Altinn 3 ble lansert i juni 2020."})]
      (is (.contains out "5") "fact-count substituted")
      (is (.contains out "Lov om Altinn") "doc-title substituted")
      (is (.contains out "https://example.no/altinn") "doc-url substituted")
      (is (.contains out "Altinn 3 ble lansert i juni 2020.") "chunk-content substituted")
      (is (not (.contains out "{{")) "no unsubstituted placeholders remain"))
    (testing "Blank optional inputs render as empty strings, not as the literal placeholder"
      (let [out (pf/render-prompt pf/default-prompt-template
                                  {:fact-count 5
                                   :doc-title nil
                                   :doc-url nil
                                   :chunk-content "x"})]
        (is (not (.contains out "{{doc-title}}")))
        (is (not (.contains out "{{doc-url}}")))))))

(defn- stub-response [content]
  {:choices [{:message {:content content}}]})

(deftest triple-text-shape
  (testing "triple-text joins the three cells with a single space and trims"
    (is (= "Altinn 3 ble lansert juni 2020"
           (pf/triple-text {:subject " Altinn 3 "
                            :predicate "ble lansert"
                            :object "juni 2020"})))
    (is (= "Altinn 3 ble lansert"
           (pf/triple-text {:subject "Altinn 3"
                            :predicate "ble lansert"
                            :object "   "}))
        "Blank cells drop out so two triples with whitespace-only object agree")))

(deftest response-parsing
  (testing "Clean pipe-delimited output parses to triple maps in order"
    (is (= [{:subject "Altinn 3" :predicate "ble lansert" :object "juni 2020"}
            {:subject "Altinn 2" :predicate "ble avviklet" :object "2025"}]
           (pf/parse-facts-response
            (stub-response "Altinn 3 | ble lansert | juni 2020\nAltinn 2 | ble avviklet | 2025\n")))))
  (testing "Numbered output has the numbers stripped"
    (is (= [{:subject "Altinn 3" :predicate "ble lansert" :object "juni 2020"}
            {:subject "Altinn 2" :predicate "ble avviklet" :object "2025"}]
           (pf/parse-facts-response
            (stub-response "1. Altinn 3 | ble lansert | juni 2020\n2) Altinn 2 | ble avviklet | 2025")))))
  (testing "Bulleted output has the markers stripped"
    (is (= [{:subject "Altinn 3" :predicate "ble lansert" :object "juni 2020"}]
           (pf/parse-facts-response
            (stub-response "- Altinn 3 | ble lansert | juni 2020")))))
  (testing "Whitespace-only duplicates collapse to a single triple"
    (is (= [{:subject "Altinn 3" :predicate "ble lansert" :object "juni 2020"}]
           (pf/parse-facts-response
            (stub-response "Altinn 3 | ble lansert | juni 2020\n  Altinn 3 |  ble lansert | juni 2020 \n")))))
  (testing "Lines with fewer than three cells are dropped, not coerced"
    (is (= [{:subject "Altinn 3" :predicate "ble lansert" :object "juni 2020"}]
           (pf/parse-facts-response
            (stub-response "Altinn 3 | ble lansert\nAltinn 3 | ble lansert | juni 2020\n|||\nAltinn 3 |  | juni 2020")))))
  (testing "Empty response yields an empty vector, not nil"
    (is (= [] (pf/parse-facts-response (stub-response ""))))))

(deftest skill-body-happy-path
  (testing "Stubbed LLM → :facts vec capped at :fact-count + provenance"
    (with-redefs [cfg/get (fn [_opts & _path] "stub-azure-deployment")
                  openai/create-chat-completion
                  (fn [_convo _impl-opts]
                    (stub-response
                     "Altinn 3 | ble lansert | juni 2020\nAltinn 3 | erstatter | Altinn 2\nAltinn 2 | ble avviklet | 2025\nDigdir | forvalter | Altinn\nAltinn 3 | bruker | digital signering\nAltinn 3 | støtter | offentlige skjemaer"))]
      (let [result (pf/execute-propose-facts
                    {:inputs {:chunk-id "abc123"
                              :chunk-content "Altinn 3 ble lansert i juni 2020."
                              :doc-title "Lov om Altinn"
                              :doc-url "https://example.no/altinn"}
                     :parameters {:fact-count 4
                                  :temperature 0.2}
                     :skill-params {:tenant "digdir"}})
            outputs (skills/get-result-outputs result)]
        (is (skills/result-success? result))
        (is (= "abc123" (:chunk-id outputs)))
        (is (= 4 (count (:facts outputs)))
            "Capped at :fact-count even when the model returns more")
        (is (every? map? (:facts outputs)))
        (is (every? (fn [t] (every? string? ((juxt :subject :predicate :object) t)))
                    (:facts outputs))
            "Every emitted triple has all three cells")
        (let [{:keys [model prompt-hash generated-at-ms fact-count]} (:provenance outputs)]
          (is (= "stub-azure-deployment" model))
          (is (string? prompt-hash))
          (is (pos-int? generated-at-ms))
          (is (= 4 fact-count)))))))

(deftest skill-body-defaults
  (testing "Omitting :fact-count defaults to 5"
    (with-redefs [cfg/get (fn [_opts & _path] "stub-azure-deployment")
                  openai/create-chat-completion
                  (fn [_convo _impl-opts]
                    (stub-response
                     (str/join
                      "\n"
                      (for [i (range 1 8)]
                        (str "s" i " | p" i " | o" i)))))]
      (let [result (pf/execute-propose-facts
                    {:inputs {:chunk-id "c1"
                              :chunk-content "content"
                              :doc-title nil
                              :doc-url nil}
                     :parameters {}
                     :skill-params {:tenant "digdir"}})
            outputs (skills/get-result-outputs result)]
        (is (= 5 (count (:facts outputs))))))))

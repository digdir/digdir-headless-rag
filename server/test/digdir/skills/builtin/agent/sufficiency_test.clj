(ns digdir.skills.builtin.agent.sufficiency-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [digdir.skills.builtin.agent.loop :as agent-loop]
            [digdir.skills.builtin.agent.sufficiency :as sufficiency]
            [digdir.skills.test-helpers :as th]))

(deftest evaluate-sufficiency-detects-conflicting-metric-values
  (testing "Two different values for the same year/metric are marked as conflicting"
    (let [decision (sufficiency/evaluate-sufficiency
                    "Hvor mange årsverk hadde Digdir i 2022?"
                    {:query-intent {:answer-type :numeric-fact
                                    :entity "Digdir"
                                    :year-or-date "2022"
                                    :metric "årsverk"}
                     :readable-chunks [{:chunk-id "c1"
                                        :title "Årsrapport Digdir 2022"
                                        :content-preview "Digdir hadde 326 utførte årsverk i 2022."}
                                       {:chunk-id "c2"
                                        :title "Bemanning Digdir 2022"
                                        :content-preview "Digdir hadde 356 utførte årsverk i 2022."}]
                     :latest-search-summaries []
                     :unread-chunk-ids []
                     :unread-range nil})]
      (is (= :conflicting (:status decision)))
      (is (true? (:contradiction-detected? decision)))
      (is (= :re-search (:suggested-strategy decision))))))

(deftest evaluate-sufficiency-detects-missing-year-specific-evidence
  (testing "Evidence for the wrong year is marked insufficient"
    (let [decision (sufficiency/evaluate-sufficiency
                    "Hvor mange årsverk hadde Digdir i 2022?"
                    {:query-intent {:answer-type :numeric-fact
                                    :entity "Digdir"
                                    :year-or-date "2022"
                                    :metric "årsverk"}
                     :readable-chunks [{:chunk-id "c1"
                                        :title "Årsrapport Digdir 2021"
                                        :content-preview "Digdir hadde 300 utførte årsverk i 2021."}]
                     :latest-search-summaries []
                     :unread-chunk-ids []
                     :unread-range nil})]
      (is (= :insufficient (:status decision)))
      (is (= :re-search (:suggested-strategy decision)))
      (is (some #(re-find #"2022" %) (:missing-info decision))))))

(deftest evaluate-sufficiency-marks-off-topic-evidence
  (testing "Evidence for the wrong entity is marked off-topic"
    (let [decision (sufficiency/evaluate-sufficiency
                    "Hvor mange årsverk hadde Altinn i 2022?"
                    {:query-intent {:answer-type :numeric-fact
                                    :entity "Altinn"
                                    :year-or-date "2022"
                                    :metric "årsverk"}
                     :readable-chunks [{:chunk-id "c1"
                                        :title "Årsrapport Digdir 2022"
                                        :content-preview "Digdir hadde 326 utførte årsverk i 2022."}]
                     :latest-search-summaries []
                     :unread-chunk-ids []
                     :unread-range nil})]
      (is (= :off-topic (:status decision)))
      (is (= :re-search (:suggested-strategy decision)))
      (is (some #(re-find #"scope" %) [(:reasoning decision)])))))

(deftest evaluate-sufficiency-rejects-on-entity-evidence-that-misses-query-topic
  (testing "Evidence that mentions the entity but covers a different topic is insufficient"
    (let [decision (sufficiency/evaluate-sufficiency
                    "Hvordan abonnerer jeg på hendelser i Altinn 3?"
                    {:query-intent {:answer-type :lookup
                                    :entity "Altinn"
                                    :year-or-date nil
                                    :metric nil}
                     ;; Chunks mention Altinn but describe organizational structure,
                     ;; not how to subscribe to events
                     :readable-chunks [{:chunk-id "c1"
                                        :title "Om Altinn"
                                        :content-preview "Altinn er en digital plattform utviklet av Digitaliseringsdirektoratet. Plattformen brukes av offentlige etater og næringsliv for rapportering og kommunikasjon."}
                                       {:chunk-id "c2"
                                        :title "Altinn organisering"
                                        :content-preview "Altinn driftes av Digdir og tilbyr tjenester for digital kommunikasjon mellom næringslivet og offentlig sektor."}]
                     :latest-search-summaries []
                     :unread-chunk-ids []
                     :unread-range nil})]
      (is (= :insufficient (:status decision))
          "Entity-only match should not be sufficient when topic terms are absent")
      (is (re-find #"does not.*address" (:reasoning decision)))))

  (testing "Evidence that addresses the query topic is sufficient"
    (let [decision (sufficiency/evaluate-sufficiency
                    "Hvordan abonnerer jeg på hendelser i Altinn 3?"
                    {:query-intent {:answer-type :lookup
                                    :entity "Altinn"
                                    :year-or-date nil
                                    :metric nil}
                     :readable-chunks [{:chunk-id "c1"
                                        :title "Events - Abonnement"
                                        :content-preview "For å abonnere på hendelser i Altinn 3, bruk Events API. POST til /subscriptions med webhook-URL og filter for å opprette et abonnement."}]
                     :latest-search-summaries []
                     :unread-chunk-ids []
                     :unread-range nil})]
      (is (= :sufficient (:status decision))
          "Evidence that contains query topic terms should be sufficient"))))

;; =============================================================================
;; :degraded? semantics (added with the shared structured-eval utility)
;; =============================================================================

(def ^:private evidence-fixture
  {:query-intent {:answer-type :lookup
                  :entity "Altinn"
                  :year-or-date nil
                  :metric nil}
   :readable-chunks [{:chunk-id "c1"
                      :title "Altinn 3"
                      :content-preview "Altinn 3 is the third generation of the Altinn platform."}]
   :latest-search-summaries []
   :unread-chunk-ids []
   :unread-range nil})

(deftest heuristic-path-marks-decision-as-degraded
  (testing "Arity-2 (no LLM) path always returns :degraded? true so traces can tell it apart"
    (let [decision (sufficiency/evaluate-sufficiency "What is Altinn 3?" evidence-fixture)]
      (is (true? (:degraded? decision)))
      (is (= :heuristic-fallback (:degraded-reason decision))))))

(deftest llm-path-returns-non-degraded-on-valid-output
  (testing "Successful LLM evaluation is marked :degraded? false"
    (let [llm-json (json/write-str {:status "sufficient"
                                    :reasoning "Looks good."
                                    :missing_info []
                                    :contradiction_detected false
                                    :suggested_strategy "finalize"})
          decision (sufficiency/evaluate-sufficiency
                    "What is Altinn 3?"
                    evidence-fixture
                    {:llm-fn (th/llm-returning-json llm-json)})]
      (is (= :sufficient (:status decision)))
      (is (false? (:degraded? decision))))))

(deftest llm-path-normalizes-case-variants
  (testing "Case/underscore variants like 'SUFFICIENT' and 'off_topic' no longer drop to degraded"
    (let [llm-json (json/write-str {:status "SUFFICIENT"
                                    :reasoning "ok"
                                    :missing_info []
                                    :contradiction_detected false
                                    :suggested_strategy "FINALIZE"})
          decision (sufficiency/evaluate-sufficiency
                    "q"
                    evidence-fixture
                    {:llm-fn (th/llm-returning-json llm-json)})]
      (is (= :sufficient (:status decision)))
      (is (= :finalize (:suggested-strategy decision)))
      (is (false? (:degraded? decision))
          "Casing variants must flow through, not force the heuristic fallback"))))

(deftest llm-path-tolerates-fenced-json
  (testing "```json fenced responses are parsed"
    (let [fenced (str "```json\n"
                      (json/write-str {:status "insufficient"
                                       :reasoning "need more"
                                       :missing_info ["year"]
                                       :contradiction_detected false
                                       :suggested_strategy "re-search"})
                      "\n```")
          decision (sufficiency/evaluate-sufficiency
                    "q"
                    evidence-fixture
                    {:llm-fn (th/llm-returning-json fenced)})]
      (is (= :insufficient (:status decision)))
      (is (false? (:degraded? decision))))))

(deftest llm-path-falls-back-to-heuristic-on-failure
  (testing "LLM errors drop to the heuristic path, marked :degraded?"
    (let [decision (sufficiency/evaluate-sufficiency
                    "What is Altinn 3?"
                    evidence-fixture
                    {:llm-fn (th/llm-throwing "LLM timeout")})]
      (is (true? (:degraded? decision)))
      (is (= :heuristic-fallback (:degraded-reason decision))))))

(deftest evaluator-system-prompt-includes-language-rule
  (testing "The sufficiency evaluator's system prompt tells the LLM to reason in the question's language"
    (let [!captured (atom [])
          _ (sufficiency/evaluate-sufficiency
             "Når ble Altinn 3 lansert?"
             evidence-fixture
             {:llm-fn (th/llm-capturing !captured
                                        (json/write-str {:status "sufficient"
                                                         :reasoning "ok"
                                                         :missing_info []
                                                         :contradiction_detected false
                                                         :suggested_strategy "finalize"}))})
          system-msg (some #(when (= "system" (:role %)) %)
                           (-> @!captured first :messages))]
      (is (some? system-msg))
      (is (re-find #"SAME LANGUAGE" (:content system-msg))
          "System prompt must embed the shared language-preservation rule"))))

(deftest grounding-decision-rejects-an-uncited-answer
  ;; A DETERMINISTIC floor under the LLM sufficiency gate. Measured behaviour
  ;; it exists to stop: asked "What is the capital of France?" against a
  ;; Norwegian public-sector corpus, the agent searched, retrieved 8-11 chunks,
  ;; found nothing relevant, and answered "Paris" from model knowledge with
  ;; zero citations — 5/5, on BOTH gpt-4o and gpt-5.6-sol, and on the pre-PR
  ;; code. Neither a model trait nor a regression: nothing in the pipeline
  ;; distinguished an uncited answer from a grounded one, because the only
  ;; signal available (`:all-valid?`) asks whether the emitted [N] markers are
  ;; real, and an answer with none has no invalid ones.
  ;;
  ;; Deterministic on purpose: this is a property of the produced text, so it
  ;; needs no LLM call, cannot itself hallucinate, and short-circuits the
  ;; response-validation call rather than adding one.
  (testing "no citation-validation yet — gate does not apply"
    (is (nil? (sufficiency/grounding-decision {})))
    (is (nil? (sufficiency/grounding-decision {:citation-validation nil}))))

  (testing "an answer with at least one valid citation passes"
    (is (nil? (sufficiency/grounding-decision
                {:citation-validation {:grounded? true :all-valid? true
                                       :total-references 2}}))))

  (testing "an answer citing nothing is rejected as insufficient"
    (let [d (sufficiency/grounding-decision
              {:citation-validation {:grounded? false :all-valid? true
                                     :total-references 0}})]
      (is (= :insufficient (:status d)))
      (is (= :grounding-gate (:source d))
          "must be attributable in the trace, distinct from the LLM gate")
      (is (= :grounding (:suggested-strategy d)))))

  (testing "an answer whose only citation is fabricated is also rejected"
    ;; :all-valid? already false here; groundedness must not treat a made-up
    ;; marker as evidence.
    (let [d (sufficiency/grounding-decision
              {:citation-validation {:grounded? false :all-valid? false
                                     :total-references 1}})]
      (is (= :insufficient (:status d))))))

(deftest grounding-hint-tells-the-model-to-cite-or-decline
  (testing "the hint names both permitted outcomes and forbids prior knowledge"
    (let [msg (agent-loop/gate-system-hint
                "What is the capital of France?"
                {:status :insufficient :source :grounding-gate
                 :suggested-strategy :grounding
                 :reasoning "no valid citations"}
                nil)]
      (is (string? msg))
      (is (re-find #"(?i)cite" msg))
      (is (re-find #"(?i)knowledge base|not covered|does not cover" msg)
          "declining must be an explicit option, or the model just re-asserts")
      (is (re-find #"(?i)prior knowledge|own knowledge|memory" msg)
          "the failure mode is answering from parametric knowledge; name it"))))

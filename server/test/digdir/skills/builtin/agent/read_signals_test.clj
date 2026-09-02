(ns digdir.skills.builtin.agent.read-signals-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [digdir.skills.builtin.agent.read-signals :as read-signals]
            [digdir.skills.test-helpers :as th]))

(deftest evaluate-read-normalizes-llm-json
  (testing "The local evaluator accepts plan-schema JSON and maps claim ids back to the evidence plan"
    (let [evidence-plan {:required-claims [{:claim-id :topic-match
                                            :text "Topic matches query"
                                            :critical? true}
                                           {:claim-id :answer-bearing-evidence
                                            :text "Contains answer-bearing details"
                                            :critical? true}]}
          response-json (json/write-str
                         {:status "support-found"
                          :scope_assessment "aligned"
                          :supported_claims [{:claim_id "topic-match"
                                              :support "explicit"
                                              :chunk_ids ["c1"]
                                              :notes "Directly addresses subscriptions."}
                                             {:claim_id "answer-bearing-evidence"
                                              :support "partial"
                                              :chunk_ids ["c1"]}]
                          :remaining_gaps []
                          :contradictions []
                          :next_action_hint "finalize"
                          :confidence 0.82})
          signal (read-signals/evaluate-read
                  "How do I subscribe to Altinn events?"
                  {:answer-type :lookup}
                  evidence-plan
                  (:required-claims evidence-plan)
                  [{:chunk_id "c1"
                    :doc_num "d1"
                    :chunk_index 0
                    :title "Events API"
                    :metadata {:header "Subscriptions"}
                    :content_markdown "Use POST /subscriptions to create an event subscription."}]
                  {:llm-fn (th/llm-returning-json response-json)})]
      (is (= :support-found (:status signal)))
      (is (= :aligned (:scope-assessment signal)))
      (is (= :finalize (:next-action-hint signal)))
      (is (= false (:degraded? signal)))
      (is (= :topic-match (get-in signal [:supported-claims 0 :claim-id])))
      (is (= :explicit (get-in signal [:supported-claims 0 :support-level])))
      (is (= :answer-bearing-evidence (get-in signal [:supported-claims 1 :claim-id])))
      (is (= :partial (get-in signal [:supported-claims 1 :support-level])))
      (is (= 0.82 (:confidence signal)))
      (is (= {:status "support-found"
              :scope_assessment "aligned"
              :supported_claims [{:claim_id "topic-match"
                                  :support "explicit"
                                  :chunk_ids ["c1"]
                                  :notes "Directly addresses subscriptions."}
                                 {:claim_id "answer-bearing-evidence"
                                  :support "partial"
                                  :chunk_ids ["c1"]}]
              :remaining_gaps []
              :contradictions []
              :next_action_hint "finalize"
              :confidence 0.82}
             (read-signals/read-signal->json-payload signal))))))

(deftest evaluate-read-falls-back-when-local-evaluator-fails
  (testing "Invalid local-evaluator output produces a conservative degraded fallback signal"
    (let [evidence-plan {:required-claims [{:claim-id :requested-value
                                            :text "Explicit value"
                                            :critical? true}]}
          signal (read-signals/evaluate-read
                  "How many employees?"
                  {:answer-type :numeric-fact}
                  evidence-plan
                  (:required-claims evidence-plan)
                  [{:chunk_id "c1"
                    :content_markdown "The document discusses staffing."}]
                  {:llm-fn (th/llm-returning-json "{not valid json}")})]
      (is (true? (:degraded? signal)))
      (is (= :degraded-fallback (:evaluation-mode signal)))
      (is (= :unclear (:status signal))
          "Degraded fallback must not claim semantic support — always :unclear")
      (is (= :ambiguous (:scope-assessment signal)))
      (is (empty? (:supported-claims signal))
          "Heuristic matching must not leak into the degraded path")
      (is (= :re-search (:next-action-hint signal)))
      (is (= :requested-value (-> signal :remaining-gaps first :claim-id))
          "Every required claim flows through as an open gap")
      (is (true? (-> signal :remaining-gaps first :critical?)))
      (is (= 0.0 (:confidence signal)))
      (is (= :local-evaluator-failed (:degraded-reason signal))))))

(deftest evaluate-read-normalizes-enum-variants
  (testing "Underscore and case variants from the LLM do not drop the signal to degraded"
    (let [evidence-plan {:required-claims [{:claim-id :topic-match
                                            :text "Topic"
                                            :critical? true}]}
          signal (read-signals/evaluate-read
                  "What is X?"
                  {:answer-type :lookup}
                  evidence-plan
                  (:required-claims evidence-plan)
                  [{:chunk_id "c1"
                    :content_markdown "X is defined as ..."}]
                  {:llm-fn (th/llm-returning-json
                             "{\"status\":\"Support_Found\",\"scope_assessment\":\"Aligned\",\"supported_claims\":[{\"claim_id\":\"topic-match\",\"support\":\"Explicit\",\"chunk_ids\":[\"c1\"]}],\"remaining_gaps\":[],\"contradictions\":[],\"next_action_hint\":\"Finalize\",\"confidence\":0.9}")})]
      (is (= false (:degraded? signal))
          "Underscore/case variants must not force degraded fallback")
      (is (= :support-found (:status signal)))
      (is (= :aligned (:scope-assessment signal)))
      (is (= :finalize (:next-action-hint signal)))
      (is (= :explicit (get-in signal [:supported-claims 0 :support-level]))))))

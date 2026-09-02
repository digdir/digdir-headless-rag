(ns digdir.skills.enrichment.mark-keep-test
  "Unit coverage for `:builtin/enrichment-mark-keep`. Trivial skill, so
   the tests are correspondingly small — they pin the output shape that
   the self-improve graph's `:foreach :collect-as :chunk-outcomes` and
   the downstream `:builtin/enrichment-compose-report` depend on."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.enrichment.mark-keep :as mk]))

(use-fixtures :once
  (fn [t]
    (mk/register!)
    (t)))

(deftest skill-registered
  (testing ":builtin/enrichment-mark-keep is in the skills registry"
    (is (some? (skills/get-skill :builtin/enrichment-mark-keep)))))

(deftest emits-keep-decision-and-passes-through
  (testing "Decision tag plus chunk-id, proposal, eval pass-through"
    (let [proposal {:chunk-id "c1"
                    :questions ["Når ble Altinn 3 lansert?"]
                    :provenance {:model "gpt-4o" :prompt-hash "abc"}}
          eval-summary {:gate-pass true :cases 1 :current-pass 1 :relaxed-pass 1}
          res (mk/execute-mark-keep
               {:inputs {:chunk-id "c1"
                         :proposal proposal
                         :eval eval-summary}})
          outputs (skills/get-result-outputs res)]
      (is (skills/result-success? res))
      (is (= :keep (:decision outputs)))
      (is (= "c1" (:chunk-id outputs)))
      (is (= proposal (:proposal outputs)) ":proposal echoed verbatim")
      (is (= eval-summary (:eval outputs)) ":eval echoed verbatim"))))

(deftest accepts-nil-proposal-and-eval
  (testing "Graph may not always have :proposal / :eval populated; skill must not error"
    (let [res (mk/execute-mark-keep
               {:inputs {:chunk-id "c1"}})
          outputs (skills/get-result-outputs res)]
      (is (skills/result-success? res))
      (is (= :keep (:decision outputs)))
      (is (= "c1" (:chunk-id outputs)))
      (is (nil? (:proposal outputs)))
      (is (nil? (:eval outputs))))))

(deftest missing-chunk-id-throws
  (testing "Refuses to tag without a chunk-id — caller bug, not a silent success"
    (is (thrown? clojure.lang.ExceptionInfo
                 (mk/execute-mark-keep {:inputs {:chunk-id nil}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (mk/execute-mark-keep {:inputs {:chunk-id ""}})))))

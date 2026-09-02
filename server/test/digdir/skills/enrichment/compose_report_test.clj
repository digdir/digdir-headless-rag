(ns digdir.skills.enrichment.compose-report-test
  "Unit coverage for `:builtin/enrichment-compose-report`. Pure-function
   tests of the Markdown shape and the structured stats. No Typesense /
   no LLM / no I/O — the skill itself doesn't touch anything."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.enrichment.compose-report :as cr]))

(use-fixtures :once
  (fn [t]
    (cr/register!)
    (t)))

(def ^:private sample-analysis
  {:chunk-ids ["c1" "c2" "c3"]
   :analysis "Three chunks were chosen because their retrieval failure rate exceeds the 0.4 threshold."})

(def ^:private kept-outcome
  {:decision :keep
   :chunk-id "c1"
   :proposal {:chunk-id "c1"
              :questions ["Når ble Altinn 3 lansert?"
                          "Hva er Altinn 3?"]
              :provenance {:model "gpt-4o" :prompt-hash "abc"}}
   :eval {:gate-pass true :cases 1 :current-pass 1 :relaxed-pass 1
          :improved 0 :regressed 0 :error-count 0}})

(def ^:private reverted-outcome
  {:decision :revert
   :chunk-id "c2"
   :reverted-count 4
   :eval {:gate-pass false :cases 1 :current-pass 0 :relaxed-pass 1
          :improved 0 :regressed 1 :error-count 0}})

(def ^:private errored-outcome
  {:chunk-id "c3"
   :error "Typesense timeout"})

;; =============================================================================
;; Registration
;; =============================================================================

(deftest skill-registered
  (testing ":builtin/enrichment-compose-report is in the skills registry"
    (is (some? (skills/get-skill :builtin/enrichment-compose-report)))))

;; =============================================================================
;; structured-stats
;; =============================================================================

(deftest structured-stats-counts-and-buckets
  (testing "Counts and chunk-id lists for a mixed-outcome run"
    (let [stats (cr/structured-stats [kept-outcome reverted-outcome errored-outcome])]
      (is (= 3 (:total stats)))
      (is (= 1 (:kept-count stats)))
      (is (= 1 (:reverted-count stats)))
      (is (= 1 (:errored-count stats)))
      (is (= 0 (:unknown-count stats)))
      (is (= ["c1"] (:kept-chunk-ids stats)))
      (is (= ["c2"] (:reverted-chunk-ids stats)))
      (is (= ["c3"] (:errored-chunk-ids stats))))))

(deftest structured-stats-empty-outcomes
  (testing "All counts zero, all id-lists empty"
    (let [stats (cr/structured-stats [])]
      (is (= 0 (:total stats)))
      (is (= 0 (:kept-count stats)))
      (is (= 0 (:reverted-count stats)))
      (is (= 0 (:errored-count stats)))
      (is (empty? (:kept-chunk-ids stats)))
      (is (empty? (:reverted-chunk-ids stats)))
      (is (empty? (:errored-chunk-ids stats))))))

(deftest structured-stats-aggregates-verify-signals
  (testing "When outcomes carry :verify, aggregate stats land under :verify"
    (let [stats (cr/structured-stats
                 [{:decision :keep :chunk-id "a"
                   :verify {:newly-findable? true
                            :improved? true
                            :keep? true
                            :position-delta nil}}
                  {:decision :keep :chunk-id "b"
                   :verify {:newly-findable? false
                            :improved? true
                            :position-delta 3}}
                  {:decision :revert :chunk-id "c"
                   :verify {:newly-findable? false
                            :improved? false
                            :position-delta -2}}])]
      (is (= 3 (-> stats :verify :verify-considered)))
      (is (= 1 (-> stats :verify :newly-findable-count)))
      (is (= 2 (-> stats :verify :improved-count)))
      (is (= 1 (-> stats :verify :regressed-count)))
      (is (= 0.5 (-> stats :verify :avg-position-delta))
          "(3 + -2) / 2 — nil position-deltas dropped"))))

(deftest structured-stats-omits-verify-when-absent
  (testing "Pre-D2.8 outcomes (no :verify map) → no :verify key in stats"
    (let [stats (cr/structured-stats [{:decision :keep :chunk-id "a"}])]
      (is (not (contains? stats :verify))))))

(deftest structured-stats-unknown-decision
  (testing "Outcomes with a missing or unrecognised :decision go to :unknown bucket"
    (let [stats (cr/structured-stats [{:chunk-id "c1" :decision :wat}
                                      {:chunk-id "c2"}])]
      (is (= 0 (:kept-count stats)))
      (is (= 0 (:reverted-count stats)))
      (is (= 2 (:unknown-count stats)))
      (is (= ["c1" "c2"] (:unknown-chunk-ids stats))))))

;; =============================================================================
;; compose-markdown
;; =============================================================================

(deftest markdown-contains-headers-and-analysis-prose
  (testing "Top-level structure: title + Corpus analysis + Per-chunk outcomes"
    (let [md (cr/compose-markdown sample-analysis [kept-outcome])]
      (is (str/includes? md "# Self-improvement run"))
      (is (str/includes? md "## Corpus analysis"))
      (is (str/includes? md "## Per-chunk outcomes (1 chunk(s) tried)"))
      (is (str/includes? md (:analysis sample-analysis))
          "Analysis prose verbatim"))))

(deftest markdown-omits-empty-sections
  (testing "No 'Kept' section when nothing was kept; no 'Reverted' when nothing reverted"
    (let [md-only-revert (cr/compose-markdown sample-analysis [reverted-outcome])]
      (is (not (str/includes? md-only-revert "### Chunks kept")))
      (is (str/includes? md-only-revert "### Chunks reverted")))
    (let [md-only-keep (cr/compose-markdown sample-analysis [kept-outcome])]
      (is (str/includes? md-only-keep "### Chunks kept"))
      (is (not (str/includes? md-only-keep "### Chunks reverted")))
      (is (not (str/includes? md-only-keep "### Chunks with errors"))))))

(deftest markdown-kept-block-lists-questions-and-gate-line
  (testing "A kept chunk shows the gate verdict and the applied questions (count in heading)"
    (let [md (cr/compose-markdown sample-analysis [kept-outcome])]
      (is (str/includes? md "`c1`"))
      (is (str/includes? md "gate-pass ✓"))
      (is (str/includes? md "Questions applied (2):"))
      (is (str/includes? md "Når ble Altinn 3 lansert?"))
      (is (str/includes? md "Hva er Altinn 3?")))))

(deftest markdown-kept-block-renders-phrases
  (testing "A kept chunk whose proposal carries :phrases shows them under 'Phrases applied'"
    (let [outcome {:decision :keep
                   :chunk-id "cp1"
                   :proposal {:phrases ["Altinn 3 lanseringsdato"
                                        "Altinn 3 juni 2020"]
                              :provenance {:model "gpt-4o" :prompt-hash "h"}}
                   :eval {:gate-pass true :cases 1 :current-pass 1
                          :relaxed-pass 1 :improved 0 :regressed 0}}
          md (cr/compose-markdown sample-analysis [outcome])]
      (is (str/includes? md "Phrases applied (2):"))
      (is (str/includes? md "Altinn 3 lanseringsdato"))
      (is (str/includes? md "Altinn 3 juni 2020")))))

(deftest markdown-kept-block-renders-facts
  (testing "A kept chunk whose proposal carries :facts shows them as 'subject | predicate | object'"
    (let [outcome {:decision :keep
                   :chunk-id "cf1"
                   :proposal {:facts [{:subject "Altinn 3"
                                       :predicate "ble lansert"
                                       :object "juni 2020"}
                                      {:subject "Altinn 3"
                                       :predicate "erstatter"
                                       :object "Altinn 2"}]
                              :provenance {:model "gpt-4o" :prompt-hash "h"}}
                   :eval {:gate-pass true :cases 1 :current-pass 1
                          :relaxed-pass 1 :improved 0 :regressed 0}}
          md (cr/compose-markdown sample-analysis [outcome])]
      (is (str/includes? md "Facts applied (2):"))
      (is (str/includes? md "Altinn 3 | ble lansert | juni 2020"))
      (is (str/includes? md "Altinn 3 | erstatter | Altinn 2")))))

(deftest markdown-kept-block-renders-verify-newly-findable
  (testing "Verify newly-findable signal surfaces in the kept block + matched-enrichment line"
    (let [outcome {:decision :keep
                   :chunk-id "c1"
                   :proposal {:phrases ["Altinn 3 lansering"]
                              :provenance {}}
                   :eval {:gate-pass true :cases 1 :current-pass 1 :relaxed-pass 1
                          :improved 0 :regressed 0}
                   :verify {:baseline-found? false
                            :enriched-found? true
                            :enriched-index 0
                            :enriched-rank 0.91
                            :newly-findable? true
                            :improved? true
                            :keep? true
                            :matched-enrichment {:phrase "Altinn 3 lanseringsdato"}}}
          md (cr/compose-markdown sample-analysis [outcome])]
      (is (str/includes? md "newly findable")
          "Verify summary fragment included on the chunk's line")
      (is (str/includes? md "rank 0.910")
          "Rank surfaced with three decimals")
      (is (str/includes? md "Matched on intent:"))
      (is (str/includes? md "Altinn 3 lanseringsdato")))))

(deftest markdown-kept-block-renders-verify-moved-up
  (testing "Verify position-delta (both found) renders as 'moved up N place(s)'"
    (let [outcome {:decision :keep
                   :chunk-id "c1"
                   :proposal {:questions ["q"]}
                   :eval {:gate-pass true :cases 1 :current-pass 1 :relaxed-pass 1}
                   :verify {:baseline-found? true
                            :baseline-index 5
                            :baseline-rank 0.4
                            :enriched-found? true
                            :enriched-index 0
                            :enriched-rank 0.92
                            :position-delta 5
                            :newly-findable? false
                            :improved? true
                            :keep? true
                            :matched-enrichment {:question "Når ble Altinn 3 lansert?"}}}
          md (cr/compose-markdown sample-analysis [outcome])]
      (is (str/includes? md "moved up 5 place(s)"))
      (is (str/includes? md "index 5 → 0")))))

(deftest markdown-reverted-block-renders-verify-regression
  (testing "Reverted chunk shows the verify regression that caused it"
    (let [outcome {:decision :revert
                   :chunk-id "c2"
                   :reverted-count 3
                   :eval {:gate-pass true :cases 1 :current-pass 1 :relaxed-pass 1}
                   :verify {:baseline-found? true
                            :baseline-index 0
                            :enriched-found? true
                            :enriched-index 2
                            :position-delta -2
                            :improved? false
                            :keep? false}}
          md (cr/compose-markdown sample-analysis [outcome])]
      (is (str/includes? md "regressed 2 place(s)"))
      (is (str/includes? md "3 row(s) removed")))))

(deftest markdown-reverted-block-renders-proposal-and-matched
  (testing "D2.13 — reverted block carries the proposal content + matched-enrichment so the user can validate the revert decision"
    (let [outcome {:decision :revert
                   :chunk-id "c2"
                   :reverted-count 5
                   :eval {:gate-pass true :cases 1 :current-pass 1 :relaxed-pass 1}
                   :proposal {:phrases ["Altinn Formidling løsningsarkitektur"
                                        "Altinn 3 arkitektur"
                                        "Altinn Formidling integrasjon"
                                        "Altinn Formidling avvikling"
                                        "Altinn løsningsarkitektur dokumentasjon"]
                              :provenance {:model "gpt-4o"}}
                   :verify {:baseline-found? true
                            :baseline-index 4
                            :enriched-found? true
                            :enriched-index 7
                            :position-delta -3
                            :improved? false
                            :keep? false
                            :matched-enrichment {:phrase "Altinn Formidling løsningsarkitektur"}}}
          md (cr/compose-markdown sample-analysis [outcome])]
      (is (str/includes? md "Phrases tried (5):")
          "Reverted block shows tried items count and label")
      (is (str/includes? md "Altinn Formidling løsningsarkitektur")
          "Each phrase is in the block")
      (is (str/includes? md "Altinn 3 arkitektur"))
      (is (str/includes? md "regressed 3 place(s)")
          "Verify summary fragment surfaces the why-reverted")
      (is (str/includes? md "Closest enrichment match for intent:")
          "Matched-enrichment shown so user sees what would have matched"))))

(deftest markdown-reverted-block-explains-rank-regression
  (testing "D2.15 — 'Why reverted' line names the position drop in plain English"
    (let [outcome {:decision :revert
                   :chunk-id "c1"
                   :reverted-count 3
                   :eval {:gate-pass true :cases 1 :current-pass 1 :relaxed-pass 1}
                   :proposal {:phrases ["p"]}
                   :verify {:baseline-found? true :baseline-index 0
                            :enriched-found? true :enriched-index 5
                            :position-delta -5
                            :improved? false :keep? false}}
          md (cr/compose-markdown sample-analysis [outcome])]
      (is (str/includes? md "_Why reverted:_"))
      (is (str/includes? md "pushed the chunk DOWN"))
      (is (str/includes? md "position 0"))
      (is (str/includes? md "to 5")))))

(deftest markdown-reverted-block-explains-enriched-miss
  (testing "Baseline finds chunk but enriched lookup doesn't → name the surface-form mismatch"
    (let [outcome {:decision :revert
                   :chunk-id "c2"
                   :reverted-count 4
                   :eval {:gate-pass true :cases 1 :current-pass 1 :relaxed-pass 1}
                   :proposal {:phrases ["p"]}
                   :verify {:baseline-found? true :baseline-index 3
                            :enriched-found? false
                            :improved? false :keep? false}}
          md (cr/compose-markdown sample-analysis [outcome])]
      (is (str/includes? md "Content search already finds this chunk at position 3"))
      (is (str/includes? md "topically adjacent")))))

(deftest markdown-reverted-block-explains-no-match
  (testing "Neither baseline nor enriched finds chunk → flag weak topical match"
    (let [outcome {:decision :revert
                   :chunk-id "c3"
                   :reverted-count 2
                   :eval {:gate-pass true :cases 1 :current-pass 1 :relaxed-pass 1}
                   :proposal {:questions ["q"]}
                   :verify {:baseline-found? false
                            :enriched-found? false
                            :improved? false :keep? false}}
          md (cr/compose-markdown sample-analysis [outcome])]
      (is (str/includes? md "Neither baseline content search nor the new enrichment"))
      (is (str/includes? md "weakly relevant")))))

(deftest markdown-reverted-block-explains-eval-regression
  (testing "Eval-fail dominates the reason regardless of verify"
    (let [outcome {:decision :revert
                   :chunk-id "c4"
                   :reverted-count 5
                   :eval {:gate-pass false :cases 3 :current-pass 1 :relaxed-pass 2}
                   :proposal {:phrases ["p"]}
                   :verify {:baseline-found? true :baseline-index 0
                            :enriched-found? true :enriched-index 0
                            :position-delta 0
                            :improved? true :keep? false}}
          md (cr/compose-markdown sample-analysis [outcome])]
      (is (str/includes? md "Eval suite regressed"))
      (is (str/includes? md "current 1/3"))
      (is (str/includes? md "relaxed 2/3")))))

(deftest markdown-kept-block-includes-chunk-context-details
  (testing "D2.16 — kept block carries a <details><summary>Chunk content</summary> block with the source text"
    (let [outcome {:decision :keep
                   :chunk-id "c1"
                   :proposal {:phrases ["p"]}
                   :eval {:gate-pass true}
                   :verify {:improved? true :keep? true}
                   :context {:chunk-content "Altinn 3 ble lansert i juni 2020."
                             :doc-title "Lov om Altinn"
                             :doc-url "https://example.no/lov"
                             :doc-num "42"}}
          md (cr/compose-markdown sample-analysis [outcome])]
      (is (str/includes? md "<details>"))
      (is (str/includes? md "<summary>Chunk content</summary>"))
      (is (str/includes? md "Altinn 3 ble lansert i juni 2020."))
      (is (str/includes? md "_from [Lov om Altinn](https://example.no/lov)_")))))

(deftest markdown-reverted-block-includes-chunk-context-details
  (testing "Reverted block also carries the <details> chunk content"
    (let [outcome {:decision :revert
                   :chunk-id "c2"
                   :reverted-count 3
                   :eval {:gate-pass true}
                   :proposal {:phrases ["p"]}
                   :verify {:improved? false :keep? false}
                   :context {:chunk-content "Some chunk text here."
                             :doc-title "Doc"
                             :doc-url "https://example.no/doc"}}
          md (cr/compose-markdown sample-analysis [outcome])]
      (is (str/includes? md "<details>"))
      (is (str/includes? md "Some chunk text here.")))))

(deftest markdown-context-omitted-when-missing
  (testing "No :context → no <details> block (graceful degradation for legacy outcomes)"
    (let [outcome {:decision :keep
                   :chunk-id "c1"
                   :proposal {:phrases ["p"]}
                   :eval {:gate-pass true}
                   :verify {:improved? true}}
          md (cr/compose-markdown sample-analysis [outcome])]
      (is (not (str/includes? md "<details>"))))))

(deftest markdown-reverted-block-renders-facts-tried
  (testing "Facts proposal in reverted block renders as 'subject | predicate | object'"
    (let [outcome {:decision :revert
                   :chunk-id "cf2"
                   :reverted-count 4
                   :eval {:gate-pass true :cases 1 :current-pass 1 :relaxed-pass 1}
                   :proposal {:facts [{:subject "Altinn Studio"
                                       :predicate "tilbyr"
                                       :object "skjemautvikling"}
                                      {:subject "Altinn"
                                       :predicate "støttes av"
                                       :object "Digdir"}]
                              :provenance {}}
                   :verify {:improved? false :keep? false :position-delta -1}}
          md (cr/compose-markdown sample-analysis [outcome])]
      (is (str/includes? md "Facts tried (2):"))
      (is (str/includes? md "Altinn Studio | tilbyr | skjemautvikling"))
      (is (str/includes? md "Altinn | støttes av | Digdir")))))

(deftest markdown-reverted-block-shows-counter-and-gate-fail
  (testing "A reverted chunk shows gate-fail and the reverted row count"
    (let [md (cr/compose-markdown sample-analysis [reverted-outcome])]
      (is (str/includes? md "`c2`"))
      (is (str/includes? md "gate-fail ✗"))
      (is (str/includes? md "4 row(s) removed")))))

(deftest markdown-error-block-surfaces-message
  (testing "An errored chunk shows its error message"
    (let [md (cr/compose-markdown sample-analysis [errored-outcome])]
      (is (str/includes? md "### Chunks with errors"))
      (is (str/includes? md "`c3`"))
      (is (str/includes? md "Typesense timeout")))))

(deftest markdown-handles-no-outcomes
  (testing "An analysis run that selected nothing renders the no-chunks placeholder"
    (let [md (cr/compose-markdown sample-analysis [])]
      (is (str/includes? md "## Per-chunk outcomes (0 chunk(s) tried)"))
      (is (str/includes? md "No chunks selected"))
      (is (not (str/includes? md "### Chunks kept")))
      (is (not (str/includes? md "### Chunks reverted"))))))

(deftest markdown-handles-missing-analysis
  (testing "Missing analysis prose renders the no-analysis placeholder"
    (let [md (cr/compose-markdown nil [kept-outcome])]
      (is (str/includes? md "_No corpus analysis available._"))
      (is (str/includes? md "### Chunks kept")
          "Outcomes still render even when analysis is missing"))
    (let [md (cr/compose-markdown {:chunk-ids ["c1"] :analysis ""} [kept-outcome])]
      (is (str/includes? md "_No corpus analysis available._")))))

(deftest markdown-mixed-run-has-all-sections
  (testing "Kept + reverted + errored all in one run; all three sections present"
    (let [md (cr/compose-markdown sample-analysis
                                  [kept-outcome reverted-outcome errored-outcome])]
      (is (str/includes? md "Kept: 1"))
      (is (str/includes? md "Reverted: 1"))
      (is (str/includes? md "Errored: 1"))
      (is (str/includes? md "### Chunks kept"))
      (is (str/includes? md "### Chunks reverted"))
      (is (str/includes? md "### Chunks with errors")))))

;; =============================================================================
;; Skill body
;; =============================================================================

(deftest execute-returns-both-outputs
  (testing "Skill returns both :report (Markdown string) and :report-structured (stats map)"
    (let [res (cr/execute-compose-report
               {:inputs {:analysis sample-analysis
                         :outcomes [kept-outcome reverted-outcome]}})
          outputs (skills/get-result-outputs res)]
      (is (skills/result-success? res))
      (is (string? (:report outputs)))
      (is (str/includes? (:report outputs) "# Self-improvement run"))
      (is (map? (:report-structured outputs)))
      (is (= 2 (-> outputs :report-structured :total)))
      (is (= 1 (-> outputs :report-structured :kept-count)))
      (is (= 1 (-> outputs :report-structured :reverted-count))))))

(deftest execute-handles-nil-inputs
  (testing "Skill accepts nil :analysis and nil :outcomes without throwing"
    (let [res (cr/execute-compose-report {:inputs {}})
          outputs (skills/get-result-outputs res)]
      (is (skills/result-success? res))
      (is (string? (:report outputs)))
      (is (= 0 (-> outputs :report-structured :total))))))

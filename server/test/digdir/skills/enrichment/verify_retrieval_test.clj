(ns digdir.skills.enrichment.verify-retrieval-test
  "D2.8 / D2.10 — unit coverage for :builtin/enrichment-verify-retrieval.

   No live Typesense — every test stubs the rag/search-chunks-by-content
   baseline call AND the rag/lookup-*-similar enriched call. The skill
   body is dispatch + delta arithmetic, so the assertions pin:
     - correct lookup picked by :enrichment-type
     - :newly-findable? when baseline misses but enriched hits
     - :position-delta and :rank-delta computed for the both-found case
     - :improved? false when baseline found higher than enriched
     - :keep? ANDs improved? with eval-suite's :gate-pass
     - missing inputs degrade to eval-only fallback (no throw)
     - missing :chunks-collection degrades to enriched-only (D2.8 shape)"
  (:require [digdir.test-utils :as tu]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.rag.retrieval :as rag]
            [digdir.rag.skills.core :as skills]
            [digdir.skills.enrichment.verify-retrieval :as vr]))

(use-fixtures :once
  (fn [t]
    (vr/register!)
    (t)))

(defn- with-stubs* [{:keys [baseline enriched-q enriched-p enriched-f]} thunk]
  (with-redefs [rag/search-chunks-by-content
                (fn [& _] (or baseline []))
                rag/lookup-hypothetical-questions-similar
                (fn [& _] (or enriched-q []))
                rag/lookup-verified-phrases-similar
                (fn [& _] (or enriched-p []))
                rag/lookup-fact-assertions-similar
                (fn [& _] (or enriched-f []))]
    (thunk)))

(defmacro with-stubs [stubs & body]
  `(with-stubs* ~stubs (fn [] ~@body)))

(deftest skill-registered
  (testing ":builtin/enrichment-verify-retrieval is in the skills registry"
    (is (some? (skills/get-skill :builtin/enrichment-verify-retrieval)))))

(deftest dispatch-questions-newly-findable
  (testing "Chunk absent from baseline + present in enriched → :newly-findable? true"
    (with-stubs {:baseline [{:chunk_id "OTHER" :rank 0.4 :index 0}]
                 :enriched-q [{:chunk_id "c1" :rank 0.91 :index 0
                               :matched-question "Når ble Altinn 3 lansert?"}]}
      (let [res (vr/execute-verify-retrieval
                 {:inputs {:chunk-id "c1"
                           :enrichment-collection-name "enrichment_hypothetical_questions_abc"
                           :docs-collection "website_documents_abc"
                           :chunks-collection "website_chunks_abc"
                           :user-query "Altinn 3 lansert"
                           :eval-summary {:gate-pass true}}
                  :parameters {:enrichment-type :hypothetical-questions}
                  :skill-params {:tenant "digdir"}})
            outputs (skills/get-result-outputs res)]
        (is (skills/result-success? res))
        (is (false? (:baseline-found? outputs)))
        (is (true? (:enriched-found? outputs)))
        (is (true? (:newly-findable? outputs)))
        (is (true? (:improved? outputs)))
        (is (true? (:keep? outputs)))
        (is (= {:question "Når ble Altinn 3 lansert?"}
               (:matched-enrichment outputs)))))))

(deftest dispatch-phrases-positive-position-delta
  (testing "Baseline finds the chunk lower, enriched finds it higher → :position-delta positive, :improved? true"
    (with-stubs {:baseline [{:chunk_id "A" :rank 0.5 :index 0}
                            {:chunk_id "B" :rank 0.4 :index 1}
                            {:chunk_id "c1" :rank 0.3 :index 2}]
                 :enriched-p [{:chunk_id "c1" :rank 0.88 :index 0
                               :matched-phrase "Altinn 3 lanseringsdato"}]}
      (let [res (vr/execute-verify-retrieval
                 {:inputs {:chunk-id "c1"
                           :enrichment-collection-name "enrichment_verified_phrases_abc"
                           :docs-collection "website_documents_abc"
                           :chunks-collection "website_chunks_abc"
                           :user-query "Altinn 3 lansert"
                           :eval-summary {:gate-pass true}}
                  :parameters {:enrichment-type :verified-phrases}
                  :skill-params {:tenant "digdir"}})
            outputs (skills/get-result-outputs res)]
        (is (true? (:baseline-found? outputs)))
        (is (= 2 (:baseline-index outputs)))
        (is (true? (:enriched-found? outputs)))
        (is (= 0 (:enriched-index outputs)))
        (is (= 2 (:position-delta outputs))
            "Moved up 2 places (baseline-index 2 → enriched-index 0)")
        (is (false? (:newly-findable? outputs))
            "Already in baseline, so not newly-findable; we measure rank instead")
        (is (true? (:improved? outputs)))
        (is (true? (:keep? outputs)))
        (is (= {:phrase "Altinn 3 lanseringsdato"} (:matched-enrichment outputs)))))))

(deftest dispatch-facts-with-matched-triple
  (testing "Facts variant returns matched-triple as the full {:subject :predicate :object} map"
    (with-stubs {:baseline []
                 :enriched-f [{:chunk_id "c1" :rank 0.95 :index 0
                               :matched-triple {:subject "Altinn 3"
                                                :predicate "ble lansert"
                                                :object "juni 2020"}}]}
      (let [res (vr/execute-verify-retrieval
                 {:inputs {:chunk-id "c1"
                           :enrichment-collection-name "enrichment_fact_assertions_abc"
                           :docs-collection "website_documents_abc"
                           :chunks-collection "website_chunks_abc"
                           :user-query "Altinn 3 lansert"
                           :eval-summary {:gate-pass true}}
                  :parameters {:enrichment-type :fact-assertions}
                  :skill-params {:tenant "digdir"}})
            outputs (skills/get-result-outputs res)]
        (is (true? (:newly-findable? outputs)))
        (is (true? (:improved? outputs)))
        (is (= {:subject "Altinn 3" :predicate "ble lansert" :object "juni 2020"}
               (:matched-enrichment outputs)))))))

(deftest enriched-miss-vetoes-keep
  (testing "Chunk-id absent from enriched hits → :enriched-found? false → :keep? false"
    (with-stubs {:baseline [{:chunk_id "c1" :rank 0.5 :index 0}]
                 :enriched-q [{:chunk_id "OTHER" :rank 0.91 :index 0
                               :matched-question "irrelevant"}]}
      (let [res (vr/execute-verify-retrieval
                 {:inputs {:chunk-id "c1"
                           :enrichment-collection-name "x"
                           :docs-collection "d"
                           :chunks-collection "ch"
                           :user-query "anything"
                           :eval-summary {:gate-pass true}}
                  :parameters {:enrichment-type :hypothetical-questions}
                  :skill-params {:tenant "digdir"}})
            outputs (skills/get-result-outputs res)]
        (is (false? (:enriched-found? outputs)))
        (is (false? (:newly-findable? outputs)))
        (is (false? (:improved? outputs)))
        (is (false? (:keep? outputs)))))))

(deftest baseline-better-than-enriched-flips-keep
  (testing "Chunk found in both but baseline was higher → :improved? false → :keep? false (enrichment didn't help)"
    (with-stubs {:baseline [{:chunk_id "c1" :rank 0.9 :index 0}]
                 :enriched-q [{:chunk_id "A" :rank 0.95 :index 0
                               :matched-question "noise"}
                              {:chunk_id "B" :rank 0.85 :index 1
                               :matched-question "more noise"}
                              {:chunk_id "c1" :rank 0.5 :index 2
                               :matched-question "weak match"}]}
      (let [res (vr/execute-verify-retrieval
                 {:inputs {:chunk-id "c1"
                           :enrichment-collection-name "x"
                           :docs-collection "d"
                           :chunks-collection "ch"
                           :user-query "q"
                           :eval-summary {:gate-pass true}}
                  :parameters {:enrichment-type :hypothetical-questions}
                  :skill-params {:tenant "digdir"}})
            outputs (skills/get-result-outputs res)]
        (is (true? (:baseline-found? outputs)))
        (is (true? (:enriched-found? outputs)))
        (is (= -2 (:position-delta outputs))
            "Regressed two places (0 → 2)")
        (is (false? (:improved? outputs)))
        (is (false? (:keep? outputs))
            "Even with eval-pass=true, a regressed position vetoes keep")))))

(deftest verify-found-but-eval-fails
  (testing "Verify finds the chunk + improved, but eval gate fails → :keep?=false (the AND)"
    (with-stubs {:baseline []
                 :enriched-p [{:chunk_id "c1" :rank 0.9 :index 0
                               :matched-phrase "Altinn 3"}]}
      (let [res (vr/execute-verify-retrieval
                 {:inputs {:chunk-id "c1"
                           :enrichment-collection-name "x"
                           :docs-collection "d"
                           :chunks-collection "ch"
                           :user-query "Altinn 3"
                           :eval-summary {:gate-pass false}}
                  :parameters {:enrichment-type :verified-phrases}
                  :skill-params {:tenant "digdir"}})
            outputs (skills/get-result-outputs res)]
        (is (true? (:newly-findable? outputs)))
        (is (true? (:improved? outputs)))
        (is (false? (:keep? outputs))
            "Eval regression vetoes a useful enrichment")))))

(deftest missing-chunks-collection-degrades-to-d28-shape
  (testing "No :chunks-collection → :baseline-found? nil, :improved? = enriched-found? (the pre-D2.10 behaviour)"
    (with-stubs {:enriched-q [{:chunk_id "c1" :rank 0.9 :index 0
                               :matched-question "q"}]}
      (let [res (vr/execute-verify-retrieval
                 {:inputs {:chunk-id "c1"
                           :enrichment-collection-name "x"
                           :docs-collection "d"
                           :chunks-collection nil
                           :user-query "q"
                           :eval-summary {:gate-pass true}}
                  :parameters {:enrichment-type :hypothetical-questions}
                  :skill-params {:tenant "digdir"}})
            outputs (skills/get-result-outputs res)]
        (is (nil? (:baseline-found? outputs))
            ":baseline-found? nil signals 'no baseline was run'")
        (is (true? (:enriched-found? outputs)))
        (is (true? (:improved? outputs))
            "Without baseline, any enriched hit counts as improvement")
        (is (true? (:keep? outputs)))))))

(deftest missing-user-query-degrades-to-eval-only
  (testing "No user-query → short-circuit to eval-only :keep? = :eval-gate-pass?"
    (let [called (atom false)]
      (with-redefs [rag/search-chunks-by-content (tu/recording-fn (reset! called true) [])
                    rag/lookup-hypothetical-questions-similar (tu/recording-fn (reset! called true) [])]
        (let [res (vr/execute-verify-retrieval
                   {:inputs {:chunk-id "c1"
                             :enrichment-collection-name "x"
                             :docs-collection "d"
                             :chunks-collection "ch"
                             :user-query nil
                             :eval-summary {:gate-pass true}}
                    :parameters {:enrichment-type :hypothetical-questions}
                    :skill-params {:tenant "digdir"}})
              outputs (skills/get-result-outputs res)]
          (is (false? @called) "Skill short-circuits before any Typesense call")
          (is (nil? (:improved? outputs)))
          (is (true? (:keep? outputs))
              "Eval-only fallback: keep iff eval-gate-pass?")
          (is (re-find #"verify-skip" (:summary outputs))))))))

(deftest unknown-enrichment-type-degrades
  (testing "Unknown :enrichment-type → eval-only fallback, no throw"
    (let [res (vr/execute-verify-retrieval
               {:inputs {:chunk-id "c1"
                         :enrichment-collection-name "x"
                         :docs-collection "d"
                         :chunks-collection "ch"
                         :user-query "q"
                         :eval-summary {:gate-pass false}}
                :parameters {:enrichment-type :something-new}
                :skill-params {:tenant "digdir"}})
          outputs (skills/get-result-outputs res)]
      (is (skills/result-success? res))
      (is (false? (:keep? outputs)))
      (is (re-find #"no lookup wired" (:summary outputs))))))

(deftest summary-is-human-readable
  (testing ":summary surfaces both verdicts and the position delta"
    (with-stubs {:baseline [{:chunk_id "OTHER" :index 0 :rank 0.5}]
                 :enriched-q [{:chunk_id "c1" :rank 0.91 :index 0
                               :matched-question "q"}]}
      (let [res (vr/execute-verify-retrieval
                 {:inputs {:chunk-id "c1"
                           :enrichment-collection-name "x"
                           :docs-collection "d"
                           :chunks-collection "ch"
                           :user-query "q"
                           :eval-summary {:gate-pass true}}
                  :parameters {:enrichment-type :hypothetical-questions}
                  :skill-params {:tenant "digdir"}})
            outputs (skills/get-result-outputs res)]
        (is (re-find #"newly-findable" (:summary outputs)))
        (is (re-find #"enriched index 0" (:summary outputs)))
        (is (re-find #"eval-pass" (:summary outputs)))
        (is (re-find #"keep" (:summary outputs)))))))

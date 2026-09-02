(ns digdir.skills.enrichment.analyze-corpus-test
  "Unit coverage for `:builtin/enrichment-analyze-corpus`.

   We split coverage into two halves:

   - Pure-function tests of `select-chunk-ids` and `build-analysis-prose`
     pin the heuristic and the prose shape without touching Typesense.
   - `with-redefs` stubs for `ts/search` exercise the end-to-end skill
     body: that we count the right collections, sample the chunks
     collection, query the enrichment collection for already-enriched
     ids, and select correctly."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.rag.skills.core :as skills]
            [digdir.rag.typesense :as ts-utils]
            [digdir.skills.enrichment.analyze-corpus :as ac]
            [digdir.skills.enrichment.collections :as enrich-coll]
            [typesense.client :as ts]))

(use-fixtures :once
  (fn [t]
    (ac/register!)
    (t)))

;; =============================================================================
;; Registration
;; =============================================================================

(deftest skill-registered
  (testing ":builtin/enrichment-analyze-corpus is in the skills registry"
    (is (some? (skills/get-skill :builtin/enrichment-analyze-corpus)))))

;; =============================================================================
;; select-chunk-ids (pure)
;; =============================================================================

(deftest select-chunk-ids-skips-already-enriched
  (testing "Already-enriched chunk-ids are removed before taking max-chunks"
    (let [sample [{:chunk_id "c1"} {:chunk_id "c2"} {:chunk_id "c3"} {:chunk_id "c4"}]]
      (is (= ["c1" "c3" "c4"]
             (ac/select-chunk-ids sample #{"c2"} 3))
          "c2 dropped, first 3 of the remainder taken")
      (is (= ["c3"]
             (ac/select-chunk-ids sample #{"c1" "c2" "c4"} 3))
          "Only one chunk left after skipping; result is shorter than max-chunks")
      (is (= []
             (ac/select-chunk-ids sample #{"c1" "c2" "c3" "c4"} 3))
          "All chunks already enriched → empty selection"))))

(deftest select-chunk-ids-respects-max
  (testing "max-chunks caps the result, even when nothing is skipped"
    (let [sample (mapv #(hash-map :chunk_id (str "c" %)) (range 10))]
      (is (= 3 (count (ac/select-chunk-ids sample #{} 3))))
      (is (= 0 (count (ac/select-chunk-ids sample #{} 0))))
      (is (= 10 (count (ac/select-chunk-ids sample #{} 50))) "max above sample is bounded by sample"))))

(deftest select-chunk-ids-drops-nils
  (testing "Nil :chunk_id values in the sample are dropped before selection"
    (let [sample [{:chunk_id nil} {:chunk_id "c1"} {:chunk_id nil} {:chunk_id "c2"}]]
      (is (= ["c1" "c2"] (ac/select-chunk-ids sample #{} 5))))))

;; =============================================================================
;; build-analysis-prose (pure)
;; =============================================================================

(deftest analysis-prose-is-single-line-with-counts
  (testing "D2.14 — prose collapses to one line that includes corpus + selection counts"
    (let [text (ac/build-analysis-prose
                {:total-docs 12
                 :total-chunks 87
                 :existing-enrichment-rows 0
                 :selected-count 3
                 :excluded-already-enriched? true
                 :max-chunks 3})]
      (is (str/includes? text "Selected 3/3 chunks from 87"))
      (is (str/includes? text "12 docs"))
      (is (not (str/includes? text "\n")) "Single line — no embedded newlines"))))

(deftest analysis-prose-leads-with-intent-topic
  (testing "When :user-query is provided, prose leads with «...» — the most important context"
    (let [text (ac/build-analysis-prose
                {:total-docs 12 :total-chunks 87
                 :existing-enrichment-rows 0
                 :selected-count 1
                 :excluded-already-enriched? true
                 :max-chunks 3
                 :user-query "signering i en Altinn-app"})]
      (is (str/starts-with? text "Intent: «signering i en Altinn-app»")))))

(deftest analysis-prose-omits-intent-when-empty
  (testing "Blank/nil :user-query → no Intent: prefix"
    (let [text (ac/build-analysis-prose
                {:total-docs 12 :total-chunks 87
                 :existing-enrichment-rows 0
                 :selected-count 3
                 :excluded-already-enriched? true
                 :max-chunks 3
                 :user-query "   "})]
      (is (not (str/includes? text "Intent:"))))))

(deftest analysis-prose-mentions-existing-rows-only-when-positive
  (testing "Existing enrichment row count surfaces only when > 0"
    (let [empty-text (ac/build-analysis-prose
                     {:total-docs 12 :total-chunks 87
                      :existing-enrichment-rows 0
                      :selected-count 2 :excluded-already-enriched? true :max-chunks 3})
          populated-text (ac/build-analysis-prose
                         {:total-docs 12 :total-chunks 87
                          :existing-enrichment-rows 17
                          :selected-count 2 :excluded-already-enriched? true :max-chunks 3})]
      (is (not (str/includes? empty-text "existing enrichment")))
      (is (str/includes? populated-text "17 existing enrichment row(s)")))))

(deftest analysis-prose-flags-no-filter
  (testing "When exclude flag is false, the prose surfaces it"
    (let [text (ac/build-analysis-prose
                {:total-docs 12 :total-chunks 87
                 :existing-enrichment-rows nil
                 :selected-count 3
                 :excluded-already-enriched? false
                 :max-chunks 3})]
      (is (str/includes? text "no enrichment-status filter")))))

;; =============================================================================
;; Skill body
;; =============================================================================

(defn- stub-ts-settings
  "Helper: stub a Typesense settings object so the skill body's
   `(when (nil? settings) (throw ...))` guard is satisfied."
  [_]
  {:uri "http://stub" :key "k"})

(defn- stub-search-fn
  "Helper: build a `ts/search` stub that dispatches on collection name
   to a per-collection response. Unrecognised collections return an
   empty response so the skill degrades cleanly."
  [responses]
  (fn [_settings coll _opts]
    (get responses coll {:found 0 :hits []})))

(deftest live-path-selects-from-sample-and-excludes-enriched
  (testing "End-to-end happy path: docs counted, chunks sampled, enrichment narrowed, selection made"
    (let [search-calls (atom [])
          responses {"docs" {:found 12 :hits [{:document {:doc_num "1"}}]}
                     "chunks" {:found 87
                               :hits [{:document {:chunk_id "c1"}}
                                      {:document {:chunk_id "c2"}}
                                      {:document {:chunk_id "c3"}}
                                      {:document {:chunk_id "c4"}}]}
                     "enrich" {:found 1
                               :hits [{:document {:chunk_id "c2"}}]}}]
      (with-redefs [ts-utils/make-ts-settings stub-ts-settings
                    ts/search (fn [_settings coll opts]
                                (swap! search-calls conj {:coll coll :opts opts})
                                ((stub-search-fn responses) _settings coll opts))]
        (let [res (ac/execute-analyze-corpus
                   {:inputs {:tenant "digdir"
                             :docs-collection "docs"
                             :chunks-collection "chunks"
                             :enrichment-collection "enrich"
                             :max-chunks 3}})
              outputs (skills/get-result-outputs res)]
          (is (skills/result-success? res))
          (is (= ["c1" "c3" "c4"] (:chunk-ids outputs))
              "c2 dropped because the enrichment search returned it as already-enriched")
          (is (= 12 (-> outputs :corpus-stats :total-docs)))
          (is (= 87 (-> outputs :corpus-stats :total-chunks)))
          (is (= 1 (-> outputs :corpus-stats :existing-enrichment-rows)))
          (is (string? (:analysis outputs)))
          (is (str/includes? (:analysis outputs) "Selected 3")))))))

(deftest live-path-skips-exclusion-when-disabled
  (testing "With :exclude-already-enriched? false, all sampled chunks are eligible"
    (with-redefs [ts-utils/make-ts-settings stub-ts-settings
                  ts/search (stub-search-fn
                             {"docs" {:found 12 :hits []}
                              "chunks" {:found 4
                                        :hits [{:document {:chunk_id "c1"}}
                                               {:document {:chunk_id "c2"}}
                                               {:document {:chunk_id "c3"}}]}
                              "enrich" {:found 99 :hits []}})]
      (let [res (ac/execute-analyze-corpus
                 {:inputs {:tenant "digdir"
                           :docs-collection "docs"
                           :chunks-collection "chunks"
                           :enrichment-collection "enrich"
                           :max-chunks 2
                           :exclude-already-enriched? false}})
            outputs (skills/get-result-outputs res)]
        (is (= ["c1" "c2"] (:chunk-ids outputs))
            "First two chunks taken regardless of enrichment status")
        (is (str/includes? (:analysis outputs) "no enrichment-status filter"))))))

(deftest live-path-handles-no-enrichment-collection
  (testing "With :enrichment-collection nil, skill still works; existing-rows is nil"
    (with-redefs [ts-utils/make-ts-settings stub-ts-settings
                  ts/search (stub-search-fn
                             {"docs" {:found 12 :hits []}
                              "chunks" {:found 4
                                        :hits [{:document {:chunk_id "c1"}}
                                               {:document {:chunk_id "c2"}}]}})]
      (let [res (ac/execute-analyze-corpus
                 {:inputs {:tenant "digdir"
                           :docs-collection "docs"
                           :chunks-collection "chunks"
                           :max-chunks 5}})
            outputs (skills/get-result-outputs res)]
        (is (skills/result-success? res))
        (is (= ["c1" "c2"] (:chunk-ids outputs)))
        (is (nil? (-> outputs :corpus-stats :existing-enrichment-rows)))))))

(deftest live-path-uses-default-max-chunks
  (testing "Default :max-chunks is 3 when not provided"
    (with-redefs [ts-utils/make-ts-settings stub-ts-settings
                  ts/search (stub-search-fn
                             {"chunks" {:found 50
                                        :hits (mapv #(hash-map :document {:chunk_id (str "c" %)})
                                                    (range 50))}})]
      (let [res (ac/execute-analyze-corpus
                 {:inputs {:tenant "digdir"
                           :chunks-collection "chunks"}})
            outputs (skills/get-result-outputs res)]
        (is (= 3 (count (:chunk-ids outputs))) ":max-chunks defaults to 3")))))

(deftest live-path-auto-ensures-enrichment-collection
  (testing "Analyze auto-calls ensure-collection-by-name! with the derived collection name"
    (let [ensure-calls (atom [])]
      (with-redefs [ts-utils/make-ts-settings stub-ts-settings
                    ts/search (stub-search-fn
                               {"chunks" {:found 1
                                          :hits [{:document {:chunk_id "c1"}}]}})
                    enrich-coll/ensure-collection-by-name!
                    (fn [docs enrich etype]
                      (swap! ensure-calls conj
                             {:docs docs :enrich enrich :etype etype})
                      :stub)]
        (ac/execute-analyze-corpus
          {:inputs {:tenant "digdir"
                    :docs-collection "website_documents_deadbeef"
                    :chunks-collection "chunks"
                    :max-chunks 1}
           :parameters {:enrichment-type :verified-phrases}})
        (is (= 1 (count @ensure-calls))
            "ensure-collection-by-name! called exactly once")
        (let [call (first @ensure-calls)]
          (is (= "website_documents_deadbeef" (:docs call)))
          (is (= "website_enrichment_verified_phrases_deadbeef" (:enrich call))
              "Enrich name derived from docs name by swapping the segment")
          (is (= :verified-phrases (:etype call))))))))

(deftest live-path-auto-ensures-fact-assertions-collection
  (testing "With :enrichment-type :fact-assertions the derived name uses the fact-assertions segment"
    (let [ensure-calls (atom [])]
      (with-redefs [ts-utils/make-ts-settings stub-ts-settings
                    ts/search (stub-search-fn
                               {"chunks" {:found 1
                                          :hits [{:document {:chunk_id "c1"}}]}})
                    enrich-coll/ensure-collection-by-name!
                    (fn [docs enrich etype]
                      (swap! ensure-calls conj
                             {:docs docs :enrich enrich :etype etype})
                      :stub)]
        (ac/execute-analyze-corpus
         {:inputs {:tenant "digdir"
                   :docs-collection "website_documents_deadbeef"
                   :chunks-collection "chunks"
                   :max-chunks 1}
          :parameters {:enrichment-type :fact-assertions}})
        (is (= 1 (count @ensure-calls)))
        (let [call (first @ensure-calls)]
          (is (= "website_documents_deadbeef" (:docs call)))
          (is (= "website_enrichment_fact_assertions_deadbeef" (:enrich call))
              "Enrich name derives from docs name with the fact-assertions segment")
          (is (= :fact-assertions (:etype call))))))))

(deftest live-path-ensure-collection-failure-is-non-fatal
  (testing "If ensure-collection-by-name! throws, analyze still returns success"
    (with-redefs [ts-utils/make-ts-settings stub-ts-settings
                  ts/search (stub-search-fn
                             {"chunks" {:found 1
                                        :hits [{:document {:chunk_id "c1"}}]}})
                  enrich-coll/ensure-collection-by-name!
                  (fn [_ _ _] (throw (ex-info "typesense down" {})))]
      (let [res (ac/execute-analyze-corpus
                  {:inputs {:tenant "digdir"
                            :docs-collection "website_documents_deadbeef"
                            :chunks-collection "chunks"
                            :max-chunks 1}})]
        (is (skills/result-success? res)
            "Ensure failure is logged-and-swallowed; downstream apply will surface the real error")))))

(deftest missing-chunks-collection-throws
  (testing "An empty/nil :chunks-collection surfaces a clear ex-info"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ac/execute-analyze-corpus
                  {:inputs {:tenant "digdir" :chunks-collection nil}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ac/execute-analyze-corpus
                  {:inputs {:tenant "digdir" :chunks-collection ""}})))))

;; =============================================================================
;; LLM-driven selection
;; =============================================================================

(deftest parse-llm-selection-strips-markers-and-filters-to-known
  (testing "Extracts chunk_ids from LLM response, drops unknowns + list markers"
    (let [resp {:choices [{:message {:content "c2\n1. c1\n- c-missing\nc3\n"}}]}]
      (is (= ["c2" "c1" "c3"]
             (ac/parse-llm-selection resp ["c1" "c2" "c3"]))))))

(deftest llm-selection-chooses-relevant-chunks
  (testing "LLM mode picks topically-relevant chunks and bypasses alphabetical heuristic"
    (let [sample [{:chunk_id "c-altinn" :title "Altinn 3 lansert"
                   :content_markdown "Altinn 3 was launched on date X."}
                  {:chunk_id "c-panel" :title "Panel component"
                   :content_markdown "The Panel component shows info."}
                  {:chunk_id "c-signing" :title "SigningActions"
                   :content_markdown "List of SigningActions properties."}]
          fake-llm (fn [_prompt]
                     {:choices [{:message {:content "c-altinn\nc-signing\n"}}]})
          out (ac/llm-select-chunk-ids "digdir" "When was Altinn 3 launched?" 2 sample #{}
                                       {:llm-call-fn fake-llm})]
      (is (= ["c-altinn" "c-signing"] out)
          "Top result is the topically-relevant chunk, NOT the alphabetically-first"))))

(deftest llm-selection-honours-max-chunks
  (testing "Even if LLM returns more, the result is capped"
    (let [sample (mapv #(hash-map :chunk_id (str "c" %)
                                  :title ""
                                  :content_markdown "")
                       (range 10))
          fake-llm (fn [_prompt]
                     {:choices [{:message {:content (str/join "\n" (map :chunk_id sample))}}]})
          out (ac/llm-select-chunk-ids "digdir" "q" 3 sample #{}
                                       {:llm-call-fn fake-llm})]
      (is (= 3 (count out))))))

(deftest llm-selection-skips-already-enriched
  (testing "Sample is pre-filtered by the already-enriched set before the LLM ever sees it"
    (let [sample [{:chunk_id "c-skip" :title "" :content_markdown ""}
                  {:chunk_id "c-pick" :title "" :content_markdown ""}]
          observed-prompt (atom nil)
          fake-llm (fn [p]
                     (reset! observed-prompt p)
                     {:choices [{:message {:content "c-pick\n"}}]})
          out (ac/llm-select-chunk-ids "digdir" "q" 5 sample #{"c-skip"}
                                       {:llm-call-fn fake-llm})]
      (is (= ["c-pick"] out))
      (is (not (re-find #"c-skip" @observed-prompt))
          "Filtered-out chunk shouldn't appear in the LLM prompt"))))

(deftest llm-selection-degrades-on-llm-error
  (testing "If the LLM call throws, returns nil so caller can fall back to heuristic"
    (let [sample [{:chunk_id "c1" :title "" :content_markdown ""}]
          out (ac/llm-select-chunk-ids "digdir" "q" 3 sample #{}
                                       {:llm-call-fn (fn [& _]
                                                       (throw (ex-info "boom" {})))})]
      (is (nil? out)))))

(deftest live-path-llm-mode-uses-llm-when-query-present
  (testing "Skill body picks LLM-selected ids when :selection-mode :llm + non-blank :user-query"
    (with-redefs [ts-utils/make-ts-settings stub-ts-settings
                  ts/search (stub-search-fn
                             {"chunks" {:found 3
                                        :hits [{:document {:chunk_id "c-altinn" :title "Altinn"
                                                           :content_markdown "Altinn 3 launched on X"}}
                                               {:document {:chunk_id "c-panel" :title "Panel"
                                                           :content_markdown "Panel info"}}
                                               {:document {:chunk_id "c-signing" :title "Signing"
                                                           :content_markdown "SigningActions"}}]}})
                  ac/llm-select-chunk-ids (fn [_ _ _ _ _ & _] ["c-altinn"])]
      (let [res (ac/execute-analyze-corpus
                 {:inputs {:tenant "digdir"
                           :chunks-collection "chunks"
                           :max-chunks 1
                           :user-query "When was Altinn 3 launched?"}
                  :parameters {:selection-mode :llm}})
            outputs (skills/get-result-outputs res)]
        (is (= ["c-altinn"] (:chunk-ids outputs))
            "LLM-selected chunk, not the alphabetically-first")
        (is (= :llm (-> outputs :corpus-stats :selection-mode)))))))

(deftest live-path-falls-back-when-llm-returns-nil
  (testing "If LLM mode is requested but returns nil (error/empty), the heuristic kicks in"
    (with-redefs [ts-utils/make-ts-settings stub-ts-settings
                  ts/search (stub-search-fn
                             {"chunks" {:found 3
                                        :hits [{:document {:chunk_id "c-alpha"}}
                                               {:document {:chunk_id "c-beta"}}]}})
                  ac/llm-select-chunk-ids (fn [& _] nil)]
      (let [res (ac/execute-analyze-corpus
                 {:inputs {:tenant "digdir"
                           :chunks-collection "chunks"
                           :max-chunks 2
                           :user-query "When was Altinn 3 launched?"}
                  :parameters {:selection-mode :llm}})
            outputs (skills/get-result-outputs res)]
        (is (= ["c-alpha" "c-beta"] (:chunk-ids outputs))
            "Heuristic alphabetical-first fallback")
        (is (= :heuristic-fallback (-> outputs :corpus-stats :selection-mode)))))))

(deftest live-path-multi-pass-sampling-with-queries-vec
  (testing "D2.19 — when :queries (a vec) is supplied, sample-chunks runs once per query and the merged pool is deduped"
    (let [observed-queries (atom [])
          ;; Per-query response: each query lands a different chunk plus
          ;; one shared chunk. Verifies that the merge deduplicates by
          ;; chunk_id and that the LLM ranker sees the UNION.
          per-query-chunks {"q1" [{:chunk_id "c-shared" :title "Shared" :content_markdown ""}
                                  {:chunk_id "c-a" :title "A" :content_markdown ""}]
                            "q2" [{:chunk_id "c-shared" :title "Shared" :content_markdown ""}
                                  {:chunk_id "c-b" :title "B" :content_markdown ""}]
                            "q3" [{:chunk_id "c-c" :title "C" :content_markdown ""}]}
          observed-sample-passed-to-llm (atom nil)]
      (with-redefs [ts-utils/make-ts-settings stub-ts-settings
                    ts/search (fn [_settings coll opts]
                                (if (= "chunks" coll)
                                  (let [q (:q opts)]
                                    ;; Skip the count-rows probe
                                    ;; (`:q "*"` with :per_page 1) so
                                    ;; the assertion stays focused on
                                    ;; the per-query sample calls.
                                    (when (not= "*" q)
                                      (swap! observed-queries conj q))
                                    {:found (count (get per-query-chunks q []))
                                     :hits (mapv (fn [d] {:document d})
                                                 (get per-query-chunks q []))})
                                  {:found 0 :hits []}))
                    ac/llm-select-chunk-ids
                    (fn [_ _ _ sample _ & _]
                      (reset! observed-sample-passed-to-llm sample)
                      (mapv :chunk_id sample))]
        (ac/execute-analyze-corpus
         {:inputs {:tenant "digdir"
                   :chunks-collection "chunks"
                   :max-chunks 10
                   :user-query "primary topic"
                   :queries ["q1" "q2" "q3"]}
          :parameters {:selection-mode :llm}})
        (is (= ["q1" "q2" "q3"] @observed-queries)
            "sample-chunks invoked once per supplied query, in order")
        (let [chunk-ids (->> @observed-sample-passed-to-llm (map :chunk_id) set)]
          (is (= #{"c-shared" "c-a" "c-b" "c-c"} chunk-ids)
              "LLM sees deduped union — c-shared appears once, not twice")
          (is (= 4 (count @observed-sample-passed-to-llm))
              "Total deduped pool is 4 (was 5 across the 3 queries before dedup)"))))))

(deftest live-path-multi-pass-falls-back-to-single-query-when-queries-empty
  (testing "Empty :queries vec or all-blank entries → fall back to single :user-query pass"
    (let [observed-queries (atom [])]
      (with-redefs [ts-utils/make-ts-settings stub-ts-settings
                    ts/search (fn [_settings coll opts]
                                (when (and (= "chunks" coll)
                                           (not= "*" (:q opts)))
                                  (swap! observed-queries conj (:q opts)))
                                {:found 0 :hits []})
                    ac/llm-select-chunk-ids (fn [& _] nil)]
        (ac/execute-analyze-corpus
         {:inputs {:tenant "digdir"
                   :chunks-collection "chunks"
                   :user-query "fallback topic"
                   :queries []}
          :parameters {:selection-mode :llm}})
        (is (= ["fallback topic"] @observed-queries)
            "Empty :queries → single sample-chunks call with :user-query"))
      (reset! observed-queries [])
      (with-redefs [ts-utils/make-ts-settings stub-ts-settings
                    ts/search (fn [_settings coll opts]
                                (when (and (= "chunks" coll)
                                           (not= "*" (:q opts)))
                                  (swap! observed-queries conj (:q opts)))
                                {:found 0 :hits []})
                    ac/llm-select-chunk-ids (fn [& _] nil)]
        (ac/execute-analyze-corpus
         {:inputs {:tenant "digdir"
                   :chunks-collection "chunks"
                   :user-query "fallback topic"
                   :queries ["   " nil ""]}
          :parameters {:selection-mode :llm}})
        (is (= ["fallback topic"] @observed-queries)
            "All-blank :queries → same single-query fallback")))))

(deftest live-path-honours-explicit-chunk-ids
  (testing "When :explicit-chunk-ids is non-empty, skip search/rank and use those ids directly"
    (with-redefs [ts-utils/make-ts-settings stub-ts-settings
                  ts/search (fn [_ coll _]
                              ;; Should never be called against chunks
                              ;; or enrich collections when explicit
                              ;; mode is active and exclude is off.
                              (case coll
                                "docs" {:found 12 :hits []}
                                {:found 0 :hits []}))
                  ac/llm-select-chunk-ids (fn [& _]
                                            (throw (ex-info "must not be called" {})))]
      (let [res (ac/execute-analyze-corpus
                 {:inputs {:tenant "digdir"
                           :docs-collection "docs"
                           :chunks-collection "chunks"
                           :max-chunks 3
                           :exclude-already-enriched? false
                           :explicit-chunk-ids ["8e22ae4b88b1"]}
                  :parameters {:selection-mode :llm}})
            outputs (skills/get-result-outputs res)]
        (is (= ["8e22ae4b88b1"] (:chunk-ids outputs))
            "User-named chunk threads through unmodified")
        (is (= :explicit (-> outputs :corpus-stats :selection-mode))
            "Mode reports as :explicit so the trace shows the path taken")))))

(deftest live-path-stays-heuristic-without-explicit-mode
  (testing "Without :selection-mode :llm, even a user-query input doesn't trigger LLM"
    (with-redefs [ts-utils/make-ts-settings stub-ts-settings
                  ts/search (stub-search-fn
                             {"chunks" {:found 1 :hits [{:document {:chunk_id "c1"}}]}})
                  ac/llm-select-chunk-ids (fn [& _]
                                            (throw (ex-info "must not be called" {})))]
      (let [res (ac/execute-analyze-corpus
                 {:inputs {:tenant "digdir"
                           :chunks-collection "chunks"
                           :user-query "anything"}})
            outputs (skills/get-result-outputs res)]
        (is (= :heuristic (-> outputs :corpus-stats :selection-mode)))))))

(deftest tenant-falls-back-to-skill-params
  (testing "When :tenant input is missing, falls back to skill-params"
    (let [observed-tenant (atom nil)]
      (with-redefs [ts-utils/make-ts-settings (fn [opts]
                                                (reset! observed-tenant (:tenant opts))
                                                {:uri "x" :key "k"})
                    ts/search (stub-search-fn
                               {"chunks" {:found 0 :hits []}})]
        (ac/execute-analyze-corpus
         {:inputs {:chunks-collection "chunks"}
          :skill-params {:tenant "from-skill-params"}})
        (is (= "from-skill-params" @observed-tenant))))))

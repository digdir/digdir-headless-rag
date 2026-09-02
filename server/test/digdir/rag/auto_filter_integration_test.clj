(ns digdir.rag.auto-filter-integration-test
  "Integration tests for query-aware automatic filtering.

   Tests that the auto-filter correctly detects org names in queries,
   fetches facet values from Typesense, and improves retrieval quality
   when run through the full retrieval skill pipeline.

   Requires a running config DB and Typesense.
   Uses the kudos dataset on public-sector-knowledge/dev environment.

   Run with: bb auto-filter-eval"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.string :as str]
            [digdir.config.db :as config-db]
            [digdir.config.core :as config-core]
            [digdir.rag.auto-filter :as auto-filter]
            [digdir.rag.core :as rag]
            [digdir.rag.live-context :as live-ctx]
            [digdir.skills.context :as ctx]
            [digdir.skills.builtin.retrieval :as retrieval]))

;; =============================================================================
;; Configuration & Fixture
;; =============================================================================

(def target-dataset-ref live-ctx/default-dataset-ref)
(def target-chunk-id "6a80d6499075")
(def target-query "Hvor mange årsverk hadde Digdir i 2022?")
(def search-queries ["årsverk Digdir 2022"
                     "antall ansatte Digdir 2022"
                     "bemanning Digdir 2022"
                     "ressursbruk Digitaliseringsdirektoratet 2022"
                     "ansatte Digitaliseringsdirektoratet 2022"])

(def ^:private !test-config (atom nil))

(defn- services-reachable? []
  (and (config-core/get-master-key)
       (try
         (some? (config-db/get-conn))
         (catch Exception _ false))))

(defn- resolve-test-config! []
  (let [{:keys [dataset-config
                collection-names
                docs-collection
                chunks-collection
                phrases-collection
                ts-opts]} (live-ctx/resolve-live-dataset-context! target-dataset-ref)]
    {:pipeline-config dataset-config
     :collection-names collection-names
     :docs-collection docs-collection
     :chunks-collection chunks-collection
     :phrases-collection phrases-collection
     :ts-opts ts-opts}))

(defn configuration-check-fixture [f]
  (if (services-reachable?)
    (do
      (println "\n=== Auto-Filter Integration Tests ===")
      (println (str "Target dataset-ref: " target-dataset-ref))
      (if-let [config (try
                        (resolve-test-config!)
                        (catch Exception _
                          nil))]
        (do
          (println (str "Collections: " (:collection-names config)))
          (if (live-ctx/typesense-reachable? (:ts-opts config))
            (do
              (println "Typesense: reachable")
              (reset! !test-config config)
              (auto-filter/clear-facet-cache!)
              (f)
              (auto-filter/clear-facet-cache!))
            (println "Skipping: Typesense not reachable")))
        (println "Skipping: live dataset config unavailable")))
    (println "Skipping auto-filter integration tests: CONFIG_MASTER_KEY not set or config DB not reachable")))

(use-fixtures :once configuration-check-fixture)

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- build-retrieval-context
  "Build an execution context for the retrieval skill."
  [{:keys [docs-collection chunks-collection phrases-collection ts-opts]}
   queries parameters]
  (ctx/build-execution-context
    :builtin/retrieval
    {:queries queries
     :docs-collection docs-collection
     :chunks-collection chunks-collection
     :phrases-collection phrases-collection}
    {:tenant (:tenant ts-opts)
     :dataset-config-key (:dataset-config-key ts-opts)
     :parameters parameters}))

(defn- chunk-from-target?
  "Check if a chunk matches the target chunk ID prefix."
  [chunk]
  (str/starts-with? (or (:chunk_id chunk) "") target-chunk-id))

(defn- chunk-title
  "Extract document title from a chunk's joined doc fields."
  [chunk docs-collection]
  (get-in chunk [(keyword docs-collection) :title]))

(defn- digdir-relevant?
  "Check if a chunk is from a Digdir document based on its title."
  [chunk docs-collection]
  (let [title (chunk-title chunk docs-collection)]
    (when title
      (or (str/includes? title "Digitaliseringsdirektoratet")
          (str/includes? title "Digdir")))))

(defn- count-relevant-chunks
  "Count chunks from Digdir documents based on title matching."
  [chunks docs-collection]
  (count (filter #(digdir-relevant? % docs-collection) chunks)))

;; =============================================================================
;; Test 1: Facet Value Fetching
;; =============================================================================

(deftest test-facet-value-fetching
  (testing "Fetches org facet values from live Typesense"
    (let [{:keys [docs-collection ts-opts]} @!test-config
          ;; Call detect-org-filters with a query we know should match,
          ;; which forces facet cache population
          result (auto-filter/detect-org-filters
                   ["Digitaliseringsdirektoratet"] docs-collection ts-opts)]

      (println "\n=== TEST 1: Facet Value Fetching ===")
      (println "Detected filter:" (pr-str result))

      (is (some? result) "Should detect Digitaliseringsdirektoratet as an org")
      (is (= "orgs_long" (get-in result [:fields 0 :field]))
          "Should match on orgs_long field")
      (is (contains? (get-in result [:fields 0 :selected-options])
                     "Digitaliseringsdirektoratet")
          "Should include Digitaliseringsdirektoratet in selected options"))))

;; =============================================================================
;; Test 2: Detection With Real Queries
;; =============================================================================

(deftest test-detection-with-real-queries
  (testing "Detects org names in realistic search queries"
    (let [{:keys [docs-collection ts-opts]} @!test-config]

      (println "\n=== TEST 2: Detection With Real Queries ===")

      (testing "Detects Digitaliseringsdirektoratet in query"
        (let [result (auto-filter/detect-org-filters
                       ["årsverk Digitaliseringsdirektoratet 2022"] docs-collection ts-opts)]
          (println "Query 'årsverk Digitaliseringsdirektoratet 2022' → " (pr-str result))
          (is (some? result))
          (is (contains? (get-in result [:fields 0 :selected-options])
                         "Digitaliseringsdirektoratet"))))

      (testing "Detects multiple orgs across multiple queries"
        (let [result (auto-filter/detect-org-filters
                       search-queries docs-collection ts-opts)]
          (println "All search queries → " (pr-str result))
          (is (some? result))
          (let [options (get-in result [:fields 0 :selected-options])]
            (is (or (contains? options "Digdir")
                    (contains? options "Digitaliseringsdirektoratet"))
                "Should detect at least one Digdir org name"))))

      (testing "No false positive on generic query"
        (let [result (auto-filter/detect-org-filters
                       ["årsrapport 2022"] docs-collection ts-opts)]
          (println "Query 'årsrapport 2022' → " (pr-str result))
          (is (nil? result) "Generic query should not trigger auto-filter"))))))

;; =============================================================================
;; Test 3: Retrieval Skill With Auto-Filter
;; =============================================================================

(deftest test-retrieval-with-auto-filter
  (testing "Retrieval skill applies auto-filter and improves results"
    (let [{:keys [_docs-collection] :as config} @!test-config

          ;; Run with auto-filter enabled (default)
          ctx-filtered (build-retrieval-context config search-queries {})
          result-filtered (retrieval/execute-retrieval ctx-filtered)
          chunks-filtered (get-in result-filtered [:outputs :chunks])
          attribution-filtered (get-in result-filtered [:outputs :search-attribution])

          ;; Run with auto-filter explicitly disabled
          ctx-unfiltered (build-retrieval-context config search-queries {:auto-filter false})
          result-unfiltered (retrieval/execute-retrieval ctx-unfiltered)
          chunks-unfiltered (get-in result-unfiltered [:outputs :chunks])
          attribution-unfiltered (get-in result-unfiltered [:outputs :search-attribution])]

      (println "\n=== TEST 3: Retrieval Skill With Auto-Filter ===")
      (println (str "Auto-filtered: " (count chunks-filtered) " chunks, attribution: "
                    (select-keys attribution-filtered [:phrase :metadata :content :merged
                                                       :auto-filter-applied :auto-filter-fallback])))
      (println (str "Unfiltered:    " (count chunks-unfiltered) " chunks, attribution: "
                    (select-keys attribution-unfiltered [:phrase :metadata :content :merged])))

      ;; Auto-filter should have been applied
      (is (some? (:auto-filter-applied attribution-filtered))
          "Auto-filter should report it was applied")

      ;; Both should return results
      (is (seq chunks-filtered) "Auto-filtered search should return results")
      (is (seq chunks-unfiltered) "Unfiltered search should return results")

      ;; Check target chunk presence
      (let [target-in-filtered (some chunk-from-target? chunks-filtered)
            target-in-unfiltered (some chunk-from-target? chunks-unfiltered)]
        (println (str "\nTarget chunk " target-chunk-id ":"))
        (println (str "  In auto-filtered results: " (boolean target-in-filtered)))
        (println (str "  In unfiltered results:    " (boolean target-in-unfiltered)))
        (is target-in-filtered
            "Target chunk should be present in auto-filtered results")))))

;; =============================================================================
;; Test 4: Org Distribution Comparison
;; =============================================================================

(deftest test-org-distribution-comparison
  (testing "Auto-filter concentrates results on relevant orgs"
    (let [{:keys [docs-collection] :as config} @!test-config

          ctx-filtered (build-retrieval-context config search-queries {})
          result-filtered (retrieval/execute-retrieval ctx-filtered)
          chunks-filtered (get-in result-filtered [:outputs :chunks])

          ctx-unfiltered (build-retrieval-context config search-queries {:auto-filter false})
          result-unfiltered (retrieval/execute-retrieval ctx-unfiltered)
          chunks-unfiltered (get-in result-unfiltered [:outputs :chunks])

          ;; Count unique document titles in each result set
          title-freq (fn [chunks]
                       (->> chunks
                            (map #(chunk-title % docs-collection))
                            (remove nil?)
                            frequencies
                            (sort-by val >)))
          titles-filtered (title-freq chunks-filtered)
          titles-unfiltered (title-freq chunks-unfiltered)

          digdir-count-filtered (count-relevant-chunks chunks-filtered docs-collection)
          digdir-count-unfiltered (count-relevant-chunks chunks-unfiltered docs-collection)]

      (println "\n=== TEST 4: Org Distribution Comparison ===")
      (println "\nAuto-filtered document titles:")
      (doseq [[title cnt] (take 10 titles-filtered)]
        (println (format "  %-55s %d chunks" (if (> (count title) 55) (str (subs title 0 52) "...") title) cnt)))
      (println (str "  Digdir-related: " digdir-count-filtered "/" (count chunks-filtered)))

      (println "\nUnfiltered document titles:")
      (doseq [[title cnt] (take 10 titles-unfiltered)]
        (println (format "  %-55s %d chunks" (if (> (count title) 55) (str (subs title 0 52) "...") title) cnt)))
      (println (str "  Digdir-related: " digdir-count-unfiltered "/" (count chunks-unfiltered)))

      ;; Auto-filtered should have higher concentration of relevant docs
      (let [pct-filtered (if (pos? (count chunks-filtered))
                           (/ (double digdir-count-filtered) (count chunks-filtered))
                           0.0)
            pct-unfiltered (if (pos? (count chunks-unfiltered))
                             (/ (double digdir-count-unfiltered) (count chunks-unfiltered))
                             0.0)]
        (println (format "\nDigdir relevance: %.0f%% (filtered) vs %.0f%% (unfiltered)"
                         (* 100 pct-filtered) (* 100 pct-unfiltered)))
        (is (>= pct-filtered pct-unfiltered)
            "Auto-filtered results should have equal or higher percentage of Digdir chunks")))))

;; =============================================================================
;; Test 5: Explicit Filter Is Merged With Auto-Filter
;; =============================================================================

(deftest test-explicit-filter-precedence
  (testing "Explicit filter-by is preserved when auto-filter is detected"
    (let [config @!test-config
          explicit-filter {:fields [{:type :multiselect
                                     :field "orgs_long"
                                     :selected-options #{"Petoro"}
                                     :value-type :string}]}
          ctx (build-retrieval-context config search-queries {:filter-by explicit-filter})
          result (retrieval/execute-retrieval ctx)
          attribution (get-in result [:outputs :search-attribution])]

      (println "\n=== TEST 5: Explicit + Auto Merge ===")
      (println "Explicit filter: orgs_long = Petoro")
      (println "Attribution:" (pr-str (select-keys attribution
                                                    [:merged :auto-filter-applied :auto-filter-fallback
                                                     :filter-source :filter-applied])))

      (is (= :merged (:filter-source attribution))
          "Merged mode should be reported when explicit and auto filters both exist")
      (is (contains? (set (get-in attribution [:filter-applied :fields 0 :selected-options]))
                     "Petoro")
          "Explicit filter values must be preserved"))))

;; =============================================================================
;; Test 6: Auto-Filter Fallback on Empty Results
;; =============================================================================

(deftest test-auto-filter-fallback
  (testing "Falls back to unfiltered search when auto-filter returns 0 results"
    (let [{:keys [docs-collection _ts-opts] :as config} @!test-config
          ;; Use a query that mentions an org name but is very specific so filtered
          ;; results might be empty. We'll inject a fake org via cache to guarantee this.
          _ (auto-filter/clear-facet-cache!)
          ;; Populate cache with a fake org that exists as facet value but has no matching chunks
          ;; for the specific query
          cache-atom @#'auto-filter/!facet-cache
          _ (swap! cache-atom assoc docs-collection
                   {:values {"orgs_long" [{:name "FakeOrgThatDoesNotExist" :count 1}]
                             "orgs_short" []}
                    :fetched-at (System/currentTimeMillis)})

          ctx (build-retrieval-context config ["FakeOrgThatDoesNotExist special query xyz"] {})
          result (retrieval/execute-retrieval ctx)
          attribution (get-in result [:outputs :search-attribution])
          chunks (get-in result [:outputs :chunks])]

      (println "\n=== TEST 6: Auto-Filter Fallback ===")
      (println "Attribution:" (pr-str (select-keys attribution
                                                    [:merged :auto-filter-applied :auto-filter-fallback])))
      (println "Chunks returned:" (count chunks))

      (is (some? (:auto-filter-applied attribution))
          "Auto-filter should have been applied initially")
      (is (true? (:auto-filter-fallback attribution))
          "Should have fallen back to unfiltered search")

      ;; Clean up fake cache
      (auto-filter/clear-facet-cache!))))

;; =============================================================================
;; Test 7: Cache Behavior
;; =============================================================================

(deftest test-facet-cache-behavior
  (testing "Facet values are cached and reused"
    (let [{:keys [docs-collection ts-opts]} @!test-config]
      (auto-filter/clear-facet-cache!)

      (println "\n=== TEST 7: Cache Behavior ===")

      ;; First call populates cache
      (let [t1 (System/nanoTime)
            _ (auto-filter/detect-org-filters ["Digdir"] docs-collection ts-opts)
            elapsed-1 (/ (- (System/nanoTime) t1) 1e6)

            ;; Second call should use cache (much faster)
            t2 (System/nanoTime)
            _ (auto-filter/detect-org-filters ["Digdir"] docs-collection ts-opts)
            elapsed-2 (/ (- (System/nanoTime) t2) 1e6)]

        (println (format "First call (cache miss):  %.1f ms" elapsed-1))
        (println (format "Second call (cache hit):  %.1f ms" elapsed-2))

        (is (< elapsed-2 (* elapsed-1 0.5))
            "Cached call should be significantly faster than initial call"))

      (auto-filter/clear-facet-cache!))))

;; =============================================================================
;; Test 8: Per-Strategy Target Chunk Rank
;; =============================================================================

(deftest test-per-strategy-target-chunk-rank
  (testing "Reports rank position of target chunk in each search strategy"
    (let [{:keys [docs-collection chunks-collection phrases-collection ts-opts]} @!test-config
          detected-filter (auto-filter/detect-query-filters search-queries docs-collection ts-opts)]

      (println "\n=== TEST 8: Per-Strategy Target Chunk Rank ===")
      (println (str "Detected filter: " (pr-str detected-filter)))

      (doseq [limit [20 30 40]]
        (println (str "\n--- Limit: " limit " ---"))
        (let [opts (assoc ts-opts :limit limit)

              phrase-hits (rag/lookup-search-phrases-similar
                            phrases-collection docs-collection
                            search-queries detected-filter opts)
              metadata-hits (rag/search-chunks-by-metadata
                              chunks-collection docs-collection
                              search-queries detected-filter opts)
              content-hits (rag/search-chunks-by-content
                             chunks-collection docs-collection
                             search-queries detected-filter opts)

              find-rank (fn [hits]
                          (let [idx (->> hits
                                         (map-indexed vector)
                                         (some (fn [[i h]]
                                                 (when (str/starts-with?
                                                         (or (:chunk_id h) "") target-chunk-id)
                                                   (inc i)))))]
                            idx))]

          (println (str "  phrase:   " (count phrase-hits) " hits, target rank: "
                        (or (find-rank phrase-hits) "absent")))
          (println (str "  metadata: " (count metadata-hits) " hits, target rank: "
                        (or (find-rank metadata-hits) "absent")))
          (println (str "  content:  " (count content-hits) " hits, target rank: "
                        (or (find-rank content-hits) "absent")))

          ;; At limit 30+, at least one strategy should find the target chunk
          (when (>= limit 30)
            (is (or (find-rank phrase-hits)
                    (find-rank metadata-hits)
                    (find-rank content-hits))
                (str "Target chunk should appear in at least one strategy at limit " limit))))))))

;; =============================================================================
;; Test 9: Retrieval Limit Sensitivity
;; =============================================================================

(deftest test-retrieval-limit-sensitivity
  (testing "Evaluates target chunk presence across different per-query limits"
    (let [config @!test-config]

      (println "\n=== TEST 9: Retrieval Limit Sensitivity ===")

      (doseq [limit [20 30 40]]
        (let [ctx (build-retrieval-context config search-queries {:limit limit})
              result (retrieval/execute-retrieval ctx)
              chunks (get-in result [:outputs :chunks])
              attribution (get-in result [:outputs :search-attribution])
              target-found (some chunk-from-target? chunks)]

          (println (str "\n--- Limit: " limit " ---"))
          (println (str "  Total unique chunks: " (count chunks)))
          (println (str "  Merged hits: " (:merged attribution)))
          (println (str "  Target chunk " target-chunk-id ": "
                        (if target-found "FOUND" "ABSENT")))

          ;; At the new default (30), target chunk should be present
          (when (>= limit 30)
            (is target-found
                (str "Target chunk should be present at limit " limit))))))))

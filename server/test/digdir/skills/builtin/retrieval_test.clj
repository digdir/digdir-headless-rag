(ns digdir.skills.builtin.retrieval-test
  (:require [digdir.test-utils :as tu]
            [clojure.test :refer [deftest testing is]]
            [digdir.skills.builtin.retrieval :as retrieval]
            [digdir.rag.auto-filter :as auto-filter]
            [digdir.rag.core :as rag]))

(defn- test-ctx
  [parameters]
  {:inputs {:queries ["Hvor mange årsverk hadde Digdir i 2022?"]
            :docs-collection "docs"
            :chunks-collection "chunks"
            :phrases-collection "phrases"}
   :parameters parameters
   :skill-params {:tenant "t" :environment "e"}})

(deftest execute-retrieval-forwards-merge-overrides
  (testing "Direct retrieval execution forwards merge override parameters into the merge helper"
    (let [captured-args (atom nil)]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    rag/lookup-search-phrases-similar (tu/recording-fn [{:chunk_id "p1" :rank 0.9 :index 0}])
                    rag/search-chunks-by-metadata (tu/recording-fn [])
                    rag/search-chunks-by-content (tu/recording-fn [])
                    rag/merge-chunk-search-results
                    (fn [& args]
                      (reset! captured-args args)
                      [{:chunk_id "p1"}])
                    rag/retrieve-chunks-by-id
                    (fn [& _] [{:chunk_id "p1" :doc_num "d1" :chunk_index 0 :content_length 50}])]
        (retrieval/execute-retrieval
         (test-ctx {:strategy-weights {:phrase 1.0 :content 0.5}
                    :strategy-contribution-caps {:phrase 2}}))
        (is (= {:strategy-weights {:phrase 1.0 :content 0.5}
                :strategy-contribution-caps {:phrase 2}}
               (first @captured-args)))
        (is (= :phrase (:search-type (first (second @captured-args)))))))))

(deftest boost-weights-override-affects-query-aware-prior
  (testing ":boost-weights parameter overrides individual boost coefficients without touching merge"
    (let [;; Two chunks: one has phrase search-type (boosted), one doesn't.
          phrase-chunk {:chunk_id "p" :doc_num "d1" :chunk_index 0
                        :hit-count 1 :search-types [:phrase]}
          plain-chunk {:chunk_id "plain" :doc_num "d2" :chunk_index 0
                       :hit-count 1 :search-types []}
          run-with (fn [parameters]
                     (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                                   rag/lookup-search-phrases-similar (tu/recording-fn [])
                                   rag/search-chunks-by-metadata (tu/recording-fn [])
                                   rag/search-chunks-by-content (tu/recording-fn [])
                                   rag/merge-chunk-search-results
                                   (fn [& _] [phrase-chunk plain-chunk])
                                   rag/retrieve-chunks-by-id
                                   (fn [& _] [phrase-chunk plain-chunk])]
                       (retrieval/execute-retrieval (test-ctx parameters))))
          default-result (run-with {})
          boosted-result (run-with {:boost-weights {:phrase-search-type 10.0}})
          prior-of (fn [result chunk-id]
                     (->> (get-in result [:outputs :chunks])
                          (some #(when (= chunk-id (:chunk_id %)) (:retrieval-prior %)))))]
      (is (some? (prior-of default-result "p")))
      (is (> (prior-of boosted-result "p") (prior-of default-result "p"))
          "Raising :phrase-search-type should raise the phrase chunk's :retrieval-prior")
      (is (= (prior-of default-result "plain") (prior-of boosted-result "plain"))
          "The plain chunk (no phrase search-type) is unaffected by the override"))))

(deftest diversity-config-override-changes-relax-thresholds
  (testing ":diversity-config lets tenants change when the default-cap is relaxed"
    (let [chunks (concat
                  ;; 4 chunks in doc d1, 1 chunk in doc d2 → top-doc-share 0.8
                  (for [i (range 4)] {:chunk_id (str "a" i) :doc_num "d1" :chunk_index i
                                      :hit-count 1})
                  [{:chunk_id "b0" :doc_num "d2" :chunk_index 0 :hit-count 1}])
          run-with (fn [parameters]
                     (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                                   rag/lookup-search-phrases-similar (tu/recording-fn [])
                                   rag/search-chunks-by-metadata (tu/recording-fn [])
                                   rag/search-chunks-by-content (tu/recording-fn [])
                                   rag/merge-chunk-search-results (fn [& _] (vec chunks))
                                   rag/retrieve-chunks-by-id (tu/recording-fn (vec chunks))]
                       (retrieval/execute-retrieval (test-ctx parameters))))
          ;; Default config: relax-min-total 30 → NOT met (we only have 5 chunks),
          ;; so the default cap of 10 applies → all 5 pass.
          default-result (run-with {})
          ;; Overridden config: relax-min-total 2 and default-max-per-document 2 →
          ;; relaxation IS met (5>=2 chunks, 2>=2 docs, 4>=2 top-doc-count, 0.8>=0.4 share),
          ;; so the cap jumps to relaxed-max-per-document. Set it to 1 so diversity
          ;; truncates doc d1 to 1 chunk → 2 chunks total.
          relaxed-result (run-with {:diversity-config {:relax-min-total 2
                                                       :relax-min-docs 2
                                                       :relax-min-top-doc-count 2
                                                       :relax-min-top-doc-share 0.4
                                                       :default-max-per-document 2
                                                       :relaxed-max-per-document 1}})]
      (is (= 5 (count (get-in default-result [:outputs :chunks]))))
      (is (= 2 (count (get-in relaxed-result [:outputs :chunks])))
          ":diversity-config must be honoured end-to-end"))))

(deftest execute-retrieval-merges-explicit-and-auto-filters
  (testing "Merges explicit filter with auto-detected filters"
    (let [captured-filter (atom nil)
          explicit {:fields [{:type :multiselect
                              :field "orgs_long"
                              :selected-options #{"Digdir"}
                              :value-type :string}]}
          auto {:fields [{:type :contains
                          :field "title"
                          :selected-options #{"2022"}
                          :value-type :string}]}]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] auto)
                    retrieval/run-search-strategies
                    (fn [_q _d _c _p filter-by _opts]
                      (reset! captured-filter filter-by)
                      {:phrase-hits []
                       :metadata-hits []
                       :content-hits []
                       :merged-hits [{:chunk_id "c1"}]})
                    rag/retrieve-chunks-by-id
                    (fn [& _] [{:chunk_id "c1" :doc_num "d1"}])]
        (let [result (retrieval/execute-retrieval (test-ctx {:filter-by explicit}))
              attr (get-in result [:outputs :search-attribution])]
          (is (= 2 (count (get-in @captured-filter [:fields]))))
          (is (= :merged (:filter-source attr)))
          (is (= #{"2022"} (get-in @captured-filter [:fields 1 :selected-options]))))))))

(deftest execute-retrieval-fallback-drops-auto-keeps-explicit
  (testing "When merged filter returns zero hits, fallback keeps explicit filters only"
    (let [calls (atom [])
          explicit {:fields [{:type :multiselect
                              :field "orgs_long"
                              :selected-options #{"Digdir"}
                              :value-type :string}]}
          auto {:fields [{:type :contains
                          :field "title"
                          :selected-options #{"2022"}
                          :value-type :string}]}]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] auto)
                    retrieval/run-search-strategies
                    (fn [_q _d _c _p filter-by _opts]
                      (swap! calls conj filter-by)
                      (if (= 1 (count @calls))
                        {:phrase-hits [] :metadata-hits [] :content-hits [] :merged-hits []}
                        {:phrase-hits [] :metadata-hits [] :content-hits [] :merged-hits [{:chunk_id "c1"}]}))
                    rag/retrieve-chunks-by-id
                    (fn [& _] [{:chunk_id "c1" :doc_num "d1"}])]
        (let [result (retrieval/execute-retrieval (test-ctx {:filter-by explicit}))
              attr (get-in result [:outputs :search-attribution])]
          (is (= 2 (count @calls)))
          (is (= explicit (second @calls)))
          (is (true? (:auto-filter-fallback attr))))))))

(deftest execute-retrieval-applies-prioritization-and-document-diversity-cap
  (testing "Applies query-aware prior and caps chunks per document"
    (let [chunks [{:chunk_id "c1"
                   :doc_num "d1"
                   :hit-count 10
                   :original-rank 0.9
                   :metadata {:orgs_long ["Digitaliseringsdirektoratet"]}
                   :docs {:title "Årsrapport Digitaliseringsdirektoratet 2022"}}
                  {:chunk_id "c2"
                   :doc_num "d1"
                   :hit-count 9
                   :original-rank 0.8
                   :metadata {:orgs_long ["Digitaliseringsdirektoratet"]}
                   :docs {:title "Årsrapport Digitaliseringsdirektoratet 2021"}}
                  {:chunk_id "c3"
                   :doc_num "d2"
                   :hit-count 8
                   :original-rank 0.7
                   :metadata {:orgs_long ["Digitaliseringsdirektoratet"]}
                   :docs {:title "Tildelingsbrev Digitaliseringsdirektoratet 2022"}}]]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    retrieval/run-search-strategies
                    (fn [& _]
                      {:phrase-hits [{:chunk_id "c1"}]
                       :metadata-hits [{:chunk_id "c1"}]
                       :content-hits [{:chunk_id "c1"}]
                       :merged-hits (mapv #(select-keys % [:chunk_id :hit-count :original-rank :search-types]) chunks)})
                    rag/retrieve-chunks-by-id
                    (fn [& _] chunks)]
        (let [result (retrieval/execute-retrieval
                      {:inputs {:queries ["Hvor mange årsverk hadde Digdir i 2022?"]
                                :docs-collection "docs"
                                :chunks-collection "chunks"
                                :phrases-collection "phrases"}
                       :parameters {:max-per-document 1
                                    :query-aware-boost true}
                       :skill-params {:tenant "t" :environment "e"}})
              out-chunks (get-in result [:outputs :chunks])
              attr (get-in result [:outputs :search-attribution])]
          (is (= 2 (count out-chunks)))
          (is (= 1 (:dropped-by-diversity attr)))
          (is (every? :retrieval-prior out-chunks))
          (is (= #{"d1" "d2"} (set (map :doc_num out-chunks)))))))))

(deftest execute-retrieval-relaxes-default-diversity-cap-for-dominant-document
  (testing "Default cap is relaxed when one document dominates a large candidate pool"
    (let [d1 (mapv (fn [i]
                     {:chunk_id (str "d1-" i)
                      :doc_num "d1"
                      :hit-count (- 100 i)
                      :original-rank 0.8
                      :metadata {:orgs_long ["Digitaliseringsdirektoratet"]}
                      :docs {:title "Årsrapport Digitaliseringsdirektoratet 2022"}})
                   (range 24))
          d2 (mapv (fn [i]
                     {:chunk_id (str "d2-" i)
                      :doc_num "d2"
                      :hit-count (- 50 i)
                      :original-rank 0.6
                      :metadata {:orgs_long ["Digitaliseringsdirektoratet"]}
                      :docs {:title "Vedlegg 2022"}})
                   (range 4))
          d3 (mapv (fn [i]
                     {:chunk_id (str "d3-" i)
                      :doc_num "d3"
                      :hit-count (- 30 i)
                      :original-rank 0.5
                      :metadata {:orgs_long ["Digitaliseringsdirektoratet"]}
                      :docs {:title "Notat 2022"}})
                   (range 4))
          chunks (vec (concat d1 d2 d3))]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    retrieval/run-search-strategies
                    (fn [& _]
                      {:phrase-hits []
                       :metadata-hits []
                       :content-hits []
                       :merged-hits (mapv #(select-keys % [:chunk_id]) chunks)})
                    rag/retrieve-chunks-by-id
                    (fn [& _] chunks)]
        (let [result (retrieval/execute-retrieval
                      {:inputs {:queries ["Hvor mange årsverk hadde Digdir i 2022?"]
                                :docs-collection "docs"
                                :chunks-collection "chunks"
                                :phrases-collection "phrases"}
                       :parameters {}
                       :skill-params {:tenant "t" :environment "e"}})
              out-chunks (get-in result [:outputs :chunks])
              attr (get-in result [:outputs :search-attribution])]
          (is (= 32 (count out-chunks)))
          (is (= 10 (:max-per-document attr)))
          (is (= 50 (:effective-max-per-document attr)))
          (is (= 0 (:dropped-by-diversity attr))))))))

(deftest execute-retrieval-prioritization-exposes-boost-components
  (testing "Prioritization includes title/year/org boosts with stable weights"
    (let [chunks [{:chunk_id "boosted"
                   :doc_num "d1"
                   :hit-count 5
                   :original-rank 0.5
                   :metadata "{}"
                   :docs {:title "Digdir report 2022" :orgs_long ["Digdir"]}}
                  {:chunk_id "plain"
                   :doc_num "d2"
                   :hit-count 5
                   :original-rank 0.5
                   :metadata "{}"
                   :docs {:title "Unrelated note" :orgs_long ["Other"]}}]
          explicit {:fields [{:type :multiselect
                              :field "orgs_long"
                              :selected-options #{"Digdir"}
                              :value-type :string}]}]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    retrieval/run-search-strategies
                    (fn [& _]
                      {:phrase-hits []
                       :metadata-hits []
                       :content-hits []
                       :merged-hits (mapv #(select-keys % [:chunk_id]) chunks)})
                    rag/retrieve-chunks-by-id
                    (fn [& _] chunks)]
        (let [result (retrieval/execute-retrieval
                      {:inputs {:queries ["Digdir report 2022"]
                                :docs-collection "docs"
                                :chunks-collection "chunks"
                                :phrases-collection "phrases"}
                       :parameters {:filter-by explicit
                                    :max-per-document 5
                                    :query-aware-boost true}
                       :skill-params {:tenant "t" :environment "e"}})
              out-chunks (get-in result [:outputs :chunks])
              boosted (first out-chunks)]
          (is (= "boosted" (:chunk_id boosted)))
          (is (= 0.09 (get-in boosted [:retrieval-boosts :title])))
          (is (= 0.15 (get-in boosted [:retrieval-boosts :year])))
          (is (= 0.40 (get-in boosted [:retrieval-boosts :org])))
          (is (> (:retrieval-prior boosted)
                 (:retrieval-prior (second out-chunks)))))))))

(deftest execute-retrieval-prioritizes-answer-bearing-content-for-numeric-facts
  (testing "Numeric fact queries boost chunks with direct body-text evidence and content hits"
    (let [chunks [{:chunk_id "content-answer"
                   :doc_num "d1"
                   :hit-count 4
                   :original-rank 0.4
                   :search-types [:content]
                   :metadata "{}"
                   :content_markdown "Digdir besto 31.12.2022 av 326 utførte årsverk fordelt på 345 faste stillinger."
                   :docs {:title "HR-notat" :orgs_long ["Digdir"]}}
                  {:chunk_id "title-only"
                   :doc_num "d2"
                   :hit-count 4
                   :original-rank 0.4
                   :search-types [:metadata]
                   :metadata "{}"
                   :content_markdown "Generell omtale av virksomheten og bemanning."
                   :docs {:title "Årsrapport Digdir 2022 ansatte" :orgs_long ["Digdir"]}}]]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    retrieval/run-search-strategies
                    (fn [& _]
                      {:phrase-hits []
                       :metadata-hits []
                       :content-hits [{:chunk_id "content-answer"}]
                       :merged-hits (mapv #(select-keys % [:chunk_id :search-types]) chunks)})
                    rag/retrieve-chunks-by-id
                    (fn [& _] chunks)]
        (let [result (retrieval/execute-retrieval
                      {:inputs {:queries ["Hvor mange årsverk hadde Digdir i 2022?"]
                                :docs-collection "docs"
                                :chunks-collection "chunks"
                                :phrases-collection "phrases"}
                       :parameters {:max-per-document 5
                                    :query-aware-boost true}
                       :skill-params {:tenant "t" :environment "e"}})
          out-chunks (get-in result [:outputs :chunks])
          top-chunk (first out-chunks)]
          (is (= "content-answer" (:chunk_id top-chunk)))
          (is (= 0.35 (get-in top-chunk [:retrieval-boosts :search-type])))
          (is (> (get-in top-chunk [:retrieval-boosts :content-overlap]) 0.0))
          (is (= 0.45 (get-in top-chunk [:retrieval-boosts :numeric-evidence])))
          (is (> (:retrieval-prior top-chunk)
                 (:retrieval-prior (second out-chunks)))))))))

(deftest execute-retrieval-metadata-only-still-rewards-content-search-hits
  (testing "Metadata-only retrieval can still lift content-hit chunks via late fusion"
    (let [chunks [{:chunk_id "content-hit"
                   :doc_num "d1"
                   :hit-count 3
                   :original-rank 0.4
                   :search-types [:content]
                   :metadata "{:headers [\"Hovudtal\"]}"
                   :docs {:title "Bemanning notat 2022 Digdir"}}
                  {:chunk_id "metadata-hit"
                   :doc_num "d2"
                   :hit-count 3
                   :original-rank 0.4
                   :search-types [:metadata]
                   :metadata "{:headers [\"Generelt\"]}"
                   :docs {:title "Bemanning notat 2022 Digdir"}}]]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    retrieval/run-search-strategies
                    (fn [& _]
                      {:phrase-hits []
                       :metadata-hits [{:chunk_id "metadata-hit"}]
                       :content-hits [{:chunk_id "content-hit"}]
                       :merged-hits (mapv #(select-keys % [:chunk_id :search-types]) chunks)})
                    rag/retrieve-chunk-metadata-by-id
                    (fn [& _] chunks)]
        (let [result (retrieval/execute-retrieval
                      {:inputs {:queries ["Hvor mange årsverk hadde Digdir i 2022?"]
                                :docs-collection "docs"
                                :chunks-collection "chunks"
                                :phrases-collection "phrases"}
                       :parameters {:metadata-only true
                                    :max-per-document 5
                                    :query-aware-boost true}
                       :skill-params {:tenant "t" :environment "e"}})
              out-chunks (get-in result [:outputs :chunks])]
          (is (= "content-hit" (:chunk_id (first out-chunks))))
          (is (= 0.35 (get-in (first out-chunks) [:retrieval-boosts :search-type])))
          (is (= 0.0 (get-in (first out-chunks) [:retrieval-boosts :numeric-evidence]))))))))

(deftest execute-retrieval-keeps-default-diversity-cap-when-share-is-below-threshold
  (testing "Default diversity cap does not relax when top document share is below threshold"
    (let [d1 (mapv (fn [i]
                     {:chunk_id (str "d1-" i)
                      :doc_num "d1"
                      :hit-count (- 100 i)
                      :original-rank 0.8
                      :metadata {:orgs_long ["Digdir"]}
                      :docs {:title "Digdir report 2022"}})
                   (range 13))
          d2 (mapv (fn [i]
                     {:chunk_id (str "d2-" i)
                      :doc_num "d2"
                      :hit-count (- 60 i)
                      :original-rank 0.7
                      :metadata {:orgs_long ["Digdir"]}
                      :docs {:title "Supporting note 2022"}})
                   (range 9))
          d3 (mapv (fn [i]
                     {:chunk_id (str "d3-" i)
                      :doc_num "d3"
                      :hit-count (- 40 i)
                      :original-rank 0.6
                      :metadata {:orgs_long ["Digdir"]}
                      :docs {:title "Appendix 2022"}})
                   (range 8))
          chunks (vec (concat d1 d2 d3))]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    retrieval/run-search-strategies
                    (fn [& _]
                      {:phrase-hits []
                       :metadata-hits []
                       :content-hits []
                       :merged-hits (mapv #(select-keys % [:chunk_id]) chunks)})
                    rag/retrieve-chunks-by-id
                    (fn [& _] chunks)]
        (let [result (retrieval/execute-retrieval
                      {:inputs {:queries ["Digdir report 2022"]
                                :docs-collection "docs"
                                :chunks-collection "chunks"
                                :phrases-collection "phrases"}
                       :parameters {}
                       :skill-params {:tenant "t" :environment "e"}})
              out-chunks (get-in result [:outputs :chunks])
              attr (get-in result [:outputs :search-attribution])]
          (is (= 27 (count out-chunks)))
          (is (= 10 (:effective-max-per-document attr)))
          (is (= 3 (:dropped-by-diversity attr))))))))

(deftest execute-retrieval-metadata-only-skips-content-fetch
  (testing "When :metadata-only is true, uses retrieve-chunk-metadata-by-id instead of retrieve-chunks-by-id"
    (let [metadata-fn-called (atom false)
          content-fn-called (atom false)]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    rag/lookup-search-phrases-similar (tu/recording-fn [{:chunk_id "c1"}])
                    rag/search-chunks-by-metadata (tu/recording-fn [])
                    rag/search-chunks-by-content (tu/recording-fn [])
                    rag/merge-chunk-search-results (fn [& _] [{:chunk_id "c1"}])
                    rag/retrieve-chunk-metadata-by-id
                    (fn [& _]
                      (reset! metadata-fn-called true)
                      [{:chunk_id "c1" :doc_num "d1" :chunk_index 0 :content_length 50}])
                    rag/retrieve-chunks-by-id
                    (fn [& _]
                      (reset! content-fn-called true)
                      [{:chunk_id "c1" :doc_num "d1" :chunk_index 0
                        :content_markdown "Full content" :content_length 12}])]
        (retrieval/execute-retrieval
          (test-ctx {:metadata-only true}))
        (is (true? @metadata-fn-called)
            "metadata-only should call retrieve-chunk-metadata-by-id")
        (is (false? @content-fn-called)
            "metadata-only should NOT call retrieve-chunks-by-id")))))

;; ============================================================================
;; B.4 — :enrichment-search-targets opt-in (sibling-strategy retrieval)
;; ============================================================================
;;
;; These tests verify the additive-only contract: when the parameter is
;; absent or empty the retrieval skill behaves identically to before;
;; when it is set, a fourth strategy runs against the enrichment
;; collection and its hits flow into the merge alongside the base three.

(deftest enrichment-search-targets-absent-runs-three-strategies-only
  (testing "No :enrichment-search-targets → lookup-hypothetical-questions-similar is never called"
    (let [enrichment-fn-called (atom false)]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    rag/lookup-search-phrases-similar (tu/recording-fn [])
                    rag/search-chunks-by-metadata (tu/recording-fn [])
                    rag/search-chunks-by-content (tu/recording-fn [])
                    rag/lookup-hypothetical-questions-similar
                    (fn [& _]
                      (reset! enrichment-fn-called true)
                      [])
                    rag/merge-chunk-search-results (fn [& _] [{:chunk_id "c1"}])
                    rag/retrieve-chunks-by-id
                    (fn [& _] [{:chunk_id "c1" :doc_num "d1"}])]
        (let [result (retrieval/execute-retrieval (test-ctx {}))]
          (is (false? @enrichment-fn-called))
          (is (= 3 (get-in result [:metadata :search-strategies-used]))))))))

(deftest enrichment-search-targets-empty-map-runs-three-strategies-only
  (testing "Empty :enrichment-search-targets map is treated as absent"
    (let [enrichment-fn-called (atom false)]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    rag/lookup-search-phrases-similar (tu/recording-fn [])
                    rag/search-chunks-by-metadata (tu/recording-fn [])
                    rag/search-chunks-by-content (tu/recording-fn [])
                    rag/lookup-hypothetical-questions-similar
                    (fn [& _]
                      (reset! enrichment-fn-called true)
                      [])
                    rag/merge-chunk-search-results (fn [& _] [{:chunk_id "c1"}])
                    rag/retrieve-chunks-by-id
                    (fn [& _] [{:chunk_id "c1" :doc_num "d1"}])]
        (let [result (retrieval/execute-retrieval
                      (test-ctx {:enrichment-search-targets {}}))]
          (is (false? @enrichment-fn-called))
          (is (= 3 (get-in result [:metadata :search-strategies-used]))))))))

(deftest enrichment-search-targets-with-hypothetical-questions-runs-fourth-strategy
  (testing "With :hypothetical-questions target → the enrichment lookup is called and merged"
    (let [enrichment-call-args (atom nil)
          merge-call-lists (atom nil)]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    rag/lookup-search-phrases-similar (tu/recording-fn [])
                    rag/search-chunks-by-metadata (tu/recording-fn [])
                    rag/search-chunks-by-content (tu/recording-fn [])
                    rag/lookup-hypothetical-questions-similar
                    (fn [coll-name & _]
                      (reset! enrichment-call-args coll-name)
                      [{:chunk_id "c1" :rank 0.9 :index 0}
                       {:chunk_id "c2" :rank 0.7 :index 1}])
                    rag/merge-chunk-search-results
                    (fn [& args]
                      (reset! merge-call-lists args)
                      [{:chunk_id "c1"} {:chunk_id "c2"}])
                    rag/retrieve-chunks-by-id
                    (fn [& _] [{:chunk_id "c1" :doc_num "d1"}
                               {:chunk_id "c2" :doc_num "d2"}])]
        (let [result (retrieval/execute-retrieval
                      (test-ctx {:enrichment-search-targets
                                 {:hypothetical-questions "enrich_hq_abc"}}))
              attr (get-in result [:outputs :search-attribution])]
          (is (= "enrich_hq_abc" @enrichment-call-args))
          (is (= 4 (get-in result [:metadata :search-strategies-used])))
          ;; Attribution map exposes the enrichment hit count
          (is (= {:hypothetical-questions 2} (:enrichment-hits-by-type attr)))
          ;; merge-chunk-search-results received an extra strategy list
          (let [arg-lists @merge-call-lists
                ;; First arg may be a merge-opts map (when strategy-weights are set);
                ;; the rest are strategy lists.
                lists (if (and (map? (first arg-lists))
                               (or (contains? (first arg-lists) :strategy-weights)
                                   (contains? (first arg-lists) :strategy-contribution-caps)))
                        (rest arg-lists)
                        arg-lists)]
            (is (= 4 (count lists))
                "Merge received phrase + metadata + content + hypothetical-questions")
            (is (every? #(= :hypothetical-questions (:search-type %)) (last lists))
                "The fourth list carries :hypothetical-questions search-type")))))))

(deftest enrichment-search-targets-injects-default-weight
  (testing "When :strategy-weights does not pin :hypothetical-questions, the skill defaults it to 0.7"
    (let [captured-merge-opts (atom nil)]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    rag/lookup-search-phrases-similar (tu/recording-fn [])
                    rag/search-chunks-by-metadata (tu/recording-fn [])
                    rag/search-chunks-by-content (tu/recording-fn [])
                    rag/lookup-hypothetical-questions-similar
                    (fn [& _] [{:chunk_id "c1" :rank 0.9 :index 0}])
                    rag/merge-chunk-search-results
                    (fn [& args]
                      (when (map? (first args))
                        (reset! captured-merge-opts (first args)))
                      [{:chunk_id "c1"}])
                    rag/retrieve-chunks-by-id
                    (fn [& _] [{:chunk_id "c1" :doc_num "d1"}])]
        (retrieval/execute-retrieval
         (test-ctx {:enrichment-search-targets {:hypothetical-questions "enrich_hq_abc"}}))
        (is (= 0.7 (get-in @captured-merge-opts [:strategy-weights :hypothetical-questions])))))))

(deftest enrichment-search-targets-with-fact-assertions-runs-fourth-strategy
  (testing "With :fact-assertions target → the enrichment lookup is called and merged"
    (let [enrichment-call-args (atom nil)
          merge-call-lists (atom nil)]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    rag/lookup-search-phrases-similar (tu/recording-fn [])
                    rag/search-chunks-by-metadata (tu/recording-fn [])
                    rag/search-chunks-by-content (tu/recording-fn [])
                    rag/lookup-hypothetical-questions-similar (tu/recording-fn [])
                    rag/lookup-verified-phrases-similar (tu/recording-fn [])
                    rag/lookup-fact-assertions-similar
                    (fn [coll-name & _]
                      (reset! enrichment-call-args coll-name)
                      [{:chunk_id "c1" :rank 0.9 :index 0
                        :matched-triple {:subject "Altinn 3"
                                         :predicate "ble lansert"
                                         :object "juni 2020"}}
                       {:chunk_id "c2" :rank 0.7 :index 1
                        :matched-triple {:subject "Altinn 3"
                                         :predicate "erstatter"
                                         :object "Altinn 2"}}])
                    rag/merge-chunk-search-results
                    (fn [& args]
                      (reset! merge-call-lists args)
                      [{:chunk_id "c1"} {:chunk_id "c2"}])
                    rag/retrieve-chunks-by-id
                    (fn [& _] [{:chunk_id "c1" :doc_num "d1"}
                               {:chunk_id "c2" :doc_num "d2"}])]
        (let [result (retrieval/execute-retrieval
                      (test-ctx {:enrichment-search-targets
                                 {:fact-assertions "enrich_fa_abc"}}))
              attr (get-in result [:outputs :search-attribution])]
          (is (= "enrich_fa_abc" @enrichment-call-args))
          (is (= 4 (get-in result [:metadata :search-strategies-used])))
          (is (= {:fact-assertions 2} (:enrichment-hits-by-type attr)))
          (let [arg-lists @merge-call-lists
                lists (if (and (map? (first arg-lists))
                               (or (contains? (first arg-lists) :strategy-weights)
                                   (contains? (first arg-lists) :strategy-contribution-caps)))
                        (rest arg-lists)
                        arg-lists)]
            (is (= 4 (count lists))
                "Merge received phrase + metadata + content + fact-assertions")
            (is (every? #(= :fact-assertions (:search-type %)) (last lists))
                "The fourth list carries :fact-assertions search-type")))))))

(deftest enrichment-search-targets-injects-default-weight-fact-assertions
  (testing "When :strategy-weights does not pin :fact-assertions, the skill defaults it to 0.7"
    (let [captured-merge-opts (atom nil)]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    rag/lookup-search-phrases-similar (tu/recording-fn [])
                    rag/search-chunks-by-metadata (tu/recording-fn [])
                    rag/search-chunks-by-content (tu/recording-fn [])
                    rag/lookup-hypothetical-questions-similar (tu/recording-fn [])
                    rag/lookup-verified-phrases-similar (tu/recording-fn [])
                    rag/lookup-fact-assertions-similar
                    (fn [& _] [{:chunk_id "c1" :rank 0.9 :index 0}])
                    rag/merge-chunk-search-results
                    (fn [& args]
                      (when (map? (first args))
                        (reset! captured-merge-opts (first args)))
                      [{:chunk_id "c1"}])
                    rag/retrieve-chunks-by-id
                    (fn [& _] [{:chunk_id "c1" :doc_num "d1"}])]
        (retrieval/execute-retrieval
         (test-ctx {:enrichment-search-targets {:fact-assertions "enrich_fa_abc"}}))
        (is (= 0.7 (get-in @captured-merge-opts [:strategy-weights :fact-assertions])))))))

(deftest enrichment-search-targets-respects-caller-weight-override
  (testing "Explicit :strategy-weights :hypothetical-questions value beats the 0.7 default"
    (let [captured-merge-opts (atom nil)]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    rag/lookup-search-phrases-similar (tu/recording-fn [])
                    rag/search-chunks-by-metadata (tu/recording-fn [])
                    rag/search-chunks-by-content (tu/recording-fn [])
                    rag/lookup-hypothetical-questions-similar
                    (fn [& _] [{:chunk_id "c1" :rank 0.9 :index 0}])
                    rag/merge-chunk-search-results
                    (fn [& args]
                      (when (map? (first args))
                        (reset! captured-merge-opts (first args)))
                      [{:chunk_id "c1"}])
                    rag/retrieve-chunks-by-id
                    (fn [& _] [{:chunk_id "c1" :doc_num "d1"}])]
        (retrieval/execute-retrieval
         (test-ctx {:enrichment-search-targets {:hypothetical-questions "enrich_hq_abc"}
                    :strategy-weights {:hypothetical-questions 1.5}}))
        (is (= 1.5 (get-in @captured-merge-opts [:strategy-weights :hypothetical-questions]))
            "Caller-supplied weight wins")))))

;; =============================================================================
;; Slice 23 — user-intent first-pass union
;; =============================================================================

(defn- ctx-with-intent
  [parameters user-intent queries]
  {:inputs {:queries queries
            :user-intent user-intent
            :docs-collection "docs"
            :chunks-collection "chunks"
            :phrases-collection "phrases"}
   :parameters parameters
   :skill-params {:tenant "t" :environment "e"}})

(deftest user-intent-union-runs-two-passes-and-cap-merges
  (testing "Two-pass union with cap N=2 puts intent's top-2 in front of expansion's chunks"
    (let [pass-call-queries (atom [])
          ;; Distinct chunk sets per pass; the union should preserve order
          ;; deterministically. hit-count drives the per-pass prior.
          intent-chunks [{:chunk_id "intent-A" :doc_num "dA" :hit-count 3}
                         {:chunk_id "intent-B" :doc_num "dB" :hit-count 2}
                         {:chunk_id "intent-C" :doc_num "dC" :hit-count 1}]
          expansion-chunks [{:chunk_id "exp-X" :doc_num "dX" :hit-count 3}
                            {:chunk_id "exp-Y" :doc_num "dY" :hit-count 2}
                            {:chunk_id "exp-Z" :doc_num "dZ" :hit-count 1}]
          all-by-id (into {} (map (juxt :chunk_id identity))
                          (concat intent-chunks expansion-chunks))]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    retrieval/run-search-strategies
                    (fn [queries _d _c _p _f _opts]
                      (swap! pass-call-queries conj (vec queries))
                      (let [src (if (= 1 (count queries))
                                  intent-chunks
                                  expansion-chunks)]
                        {:phrase-hits [] :metadata-hits [] :content-hits []
                         :merged-hits (mapv #(select-keys % [:chunk_id :doc_num]) src)}))
                    rag/retrieve-chunks-by-id
                    (fn [_docs _chunks hits _opts]
                      (mapv #(get all-by-id (:chunk_id %)) hits))]
        (let [result (retrieval/execute-retrieval
                       (ctx-with-intent
                         {:user-intent-union-enabled true
                          :user-intent-union-mode :cap
                          :user-intent-union-cap 2}
                         "what is dialogporten"
                         ["dialogporten api specification"
                          "dialogporten meldingsformidlingen"]))
              out-chunks (get-in result [:outputs :chunks])
              attr (get-in result [:outputs :search-attribution])
              ids (mapv :chunk_id out-chunks)]
          (is (= 2 (count @pass-call-queries)) "Both expansion and intent passes ran")
          (is (= ["intent-A" "intent-B" "exp-X" "exp-Y" "exp-Z"] ids)
              "Cap N=2 places intent's first 2 in front, then full expansion (no overlap → no dedup)")
          (is (= :cap (get-in attr [:user-intent-pass :union-mode])))
          (is (= 2 (get-in attr [:user-intent-pass :union-cap]))))))))

(deftest user-intent-union-dedups-overlapping-chunk
  (testing "Cap-merge with overlap: expansion entries already in intent prefix are skipped"
    (let [intent-chunks [{:chunk_id "A" :doc_num "dA" :hit-count 3}
                         {:chunk_id "B" :doc_num "dB" :hit-count 2}]
          expansion-chunks [{:chunk_id "A" :doc_num "dA" :hit-count 3}  ; overlaps with intent-A
                            {:chunk_id "Y" :doc_num "dY" :hit-count 2}
                            {:chunk_id "Z" :doc_num "dZ" :hit-count 1}]
          all-by-id (into {} (map (juxt :chunk_id identity))
                          (concat intent-chunks expansion-chunks))]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    retrieval/run-search-strategies
                    (fn [queries _d _c _p _f _opts]
                      (let [src (if (= 1 (count queries))
                                  intent-chunks
                                  expansion-chunks)]
                        {:phrase-hits [] :metadata-hits [] :content-hits []
                         :merged-hits (mapv #(select-keys % [:chunk_id :doc_num]) src)}))
                    rag/retrieve-chunks-by-id
                    (fn [_docs _chunks hits _opts]
                      (mapv #(get all-by-id (:chunk_id %)) hits))]
        (let [result (retrieval/execute-retrieval
                       (ctx-with-intent
                         {:user-intent-union-enabled true
                          :user-intent-union-mode :cap
                          :user-intent-union-cap 2}
                         "the intent"
                         ["q1" "q2"]))
              ids (mapv :chunk_id (get-in result [:outputs :chunks]))]
          (is (= ["A" "B" "Y" "Z"] ids)
              "A is in intent prefix; expansion's A is skipped"))))))

(deftest user-intent-absent-runs-one-pass
  (testing "No :user-intent → single expansion pass; no :user-intent-pass attribution"
    (let [call-count (atom 0)]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    retrieval/run-search-strategies
                    (fn [& _]
                      (swap! call-count inc)
                      {:phrase-hits [] :metadata-hits [] :content-hits []
                       :merged-hits [{:chunk_id "c1"}]})
                    rag/retrieve-chunks-by-id
                    (fn [& _] [{:chunk_id "c1" :doc_num "d1"}])]
        (let [result (retrieval/execute-retrieval (test-ctx {:user-intent-union-enabled true}))
              attr (get-in result [:outputs :search-attribution])]
          (is (= 1 @call-count) "Only the expansion pass ran")
          (is (nil? (:user-intent-pass attr)) "No union attribution"))))))

(deftest user-intent-union-disabled-runs-one-pass
  (testing "When :user-intent-union-enabled is false, no second pass even with :user-intent"
    (let [call-count (atom 0)]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    retrieval/run-search-strategies
                    (fn [& _]
                      (swap! call-count inc)
                      {:phrase-hits [] :metadata-hits [] :content-hits []
                       :merged-hits [{:chunk_id "c1"}]})
                    rag/retrieve-chunks-by-id
                    (fn [& _] [{:chunk_id "c1" :doc_num "d1"}])]
        (let [result (retrieval/execute-retrieval
                       (ctx-with-intent {:user-intent-union-enabled false}
                                         "intent-string"
                                         ["expansion query"]))
              attr (get-in result [:outputs :search-attribution])]
          (is (= 1 @call-count))
          (is (nil? (:user-intent-pass attr))))))))

(deftest user-intent-matching-single-query-runs-one-pass
  (testing "If :user-intent == the single :queries entry, skip the second pass"
    (let [call-count (atom 0)]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    retrieval/run-search-strategies
                    (fn [& _]
                      (swap! call-count inc)
                      {:phrase-hits [] :metadata-hits [] :content-hits []
                       :merged-hits [{:chunk_id "c1"}]})
                    rag/retrieve-chunks-by-id
                    (fn [& _] [{:chunk_id "c1" :doc_num "d1"}])]
        (let [result (retrieval/execute-retrieval
                       (ctx-with-intent {:user-intent-union-enabled true}
                                         "what is X"
                                         ["what is X"]))
              attr (get-in result [:outputs :search-attribution])]
          (is (= 1 @call-count) "Identical intent and query → no second pass")
          (is (nil? (:user-intent-pass attr))))))))

;; ---------------------------------------------------------------------------
;; merge-per-strategy-rerank — pure logic
;; ---------------------------------------------------------------------------

(deftest merge-per-strategy-rerank-orders-by-score-then-interleave-position
  (testing "Final order is rerank-score desc; interleave position is the tiebreak"
    (let [a [{:chunk_id "a1" :rerank-score 0.9}
             {:chunk_id "a2" :rerank-score 0.5}]
          b [{:chunk_id "b1" :rerank-score 0.9}  ; ties with a1
             {:chunk_id "b2" :rerank-score 0.4}]
          merged (#'retrieval/merge-per-strategy-rerank [a b] 10)
          ids (mapv :chunk_id merged)]
      (is (= ["a1" "b1" "a2" "b2"] ids)
          "a1 before b1 because a1 is at interleave position 0, b1 at 1; score tie broken by position"))))

(deftest merge-per-strategy-rerank-dedups-keeping-highest-score
  (testing "Same chunk_id in two strategies — variant with higher rerank-score wins"
    (let [a [{:chunk_id "shared" :rerank-score 0.3}
             {:chunk_id "a-only" :rerank-score 0.2}]
          b [{:chunk_id "shared" :rerank-score 0.8}  ; higher score
             {:chunk_id "b-only" :rerank-score 0.1}]
          merged (#'retrieval/merge-per-strategy-rerank [a b] 10)
          shared (first (filter #(= "shared" (:chunk_id %)) merged))]
      (is (= 3 (count merged)) "3 distinct chunk_ids: shared, a-only, b-only")
      (is (= 0.8 (:rerank-score shared)) "Higher score variant kept")
      (is (= "shared" (:chunk_id (first merged))) "shared (0.8) sorts to front by score"))))

(deftest merge-per-strategy-rerank-respects-final-cap
  (testing ":final-cap truncates the merged list"
    (let [a [{:chunk_id "a1" :rerank-score 0.9}
             {:chunk_id "a2" :rerank-score 0.7}
             {:chunk_id "a3" :rerank-score 0.5}]
          b [{:chunk_id "b1" :rerank-score 0.8}
             {:chunk_id "b2" :rerank-score 0.6}
             {:chunk_id "b3" :rerank-score 0.4}]
          merged (#'retrieval/merge-per-strategy-rerank [a b] 3)
          ids (mapv :chunk_id merged)]
      (is (= 3 (count merged)))
      (is (= ["a1" "b1" "a2"] ids) "Top 3 by score"))))

(deftest merge-per-strategy-rerank-degrades-on-empty-or-single-strategy
  (testing "Empty strategy lists are skipped; single-strategy returns its sorted contents"
    (let [a [{:chunk_id "a1" :rerank-score 0.9}
             {:chunk_id "a2" :rerank-score 0.5}]
          ;; Mix in empty strategies
          merged (#'retrieval/merge-per-strategy-rerank [[] a []] 10)
          ids (mapv :chunk_id merged)]
      (is (= ["a1" "a2"] ids))
      (is (= [] (#'retrieval/merge-per-strategy-rerank [] 10))
          "No strategies → empty")
      (is (= [] (#'retrieval/merge-per-strategy-rerank [[] [] []] 10))
          "All-empty strategies → empty"))))

;; ---------------------------------------------------------------------------
;; 4-branch rerank matrix at execute-retrieval
;; (union? × per-strategy-rerank?) = 4 combinations
;; ---------------------------------------------------------------------------

(defn- ctx-for-rerank-matrix
  "Build an execute-retrieval ctx for the 4-branch matrix tests. The retrieval
   pipeline is stubbed so each pass returns 1 phrase + 1 metadata + 1 content
   hit (3 distinct strategies with seq hit lists)."
  [parameters user-intent queries]
  {:inputs (cond-> {:queries queries
                    :docs-collection "docs"
                    :chunks-collection "chunks"
                    :phrases-collection "phrases"}
             user-intent (assoc :user-intent user-intent))
   :parameters parameters
   :skill-params {:tenant "t" :environment "e"}})

(defn- stub-multi-strategy-search
  "Stub `run-search-strategies` so phrase/metadata/content each return one
   distinct hit per call. Returns a fn suitable for with-redefs."
  []
  (let [pass-counter (atom 0)]
    (fn [_queries _docs _chunks _phrases _filter _opts]
      (let [n (swap! pass-counter inc)
            base (str "p" n)]
        {:phrase-hits   [{:chunk_id (str base "-ph") :doc_num "d1"}]
         :metadata-hits [{:chunk_id (str base "-md") :doc_num "d2"}]
         :content-hits  [{:chunk_id (str base "-co") :doc_num "d3"}]
         :merged-hits   [{:chunk_id (str base "-ph") :doc_num "d1"}
                         {:chunk_id (str base "-md") :doc_num "d2"}
                         {:chunk_id (str base "-co") :doc_num "d3"}]}))))

(deftest rerank-matrix-no-union-no-per-strategy-runs-one-colbert-call
  (testing "Baseline regression guard: union=off + per-strategy=off → 1 ColBERT invocation"
    (let [colbert-calls (atom 0)]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    retrieval/run-search-strategies (stub-multi-strategy-search)
                    rag/retrieve-chunks-by-id
                    (fn [_d _c hits _o]
                      (mapv (fn [h] {:chunk_id (:chunk_id h) :doc_num (:doc_num h)}) hits))
                    retrieval/apply-colbert-rerank
                    (fn [chunks _queries _docs _chunks-coll _opts _opts2]
                      (swap! colbert-calls inc)
                      {:chunks chunks :rerank-ms 0 :rerank-candidate-count (count chunks)})]
        (retrieval/execute-retrieval
          (ctx-for-rerank-matrix {:rerank-with-colbert true} nil ["q1"]))
        (is (= 1 @colbert-calls) "Single-pass, single-rerank")))))

(deftest rerank-matrix-no-union-per-strategy-runs-three-colbert-calls
  (testing "union=off + per-strategy=on → 1 call per strategy = 3 ColBERT invocations"
    (let [colbert-calls (atom 0)]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    retrieval/run-search-strategies (stub-multi-strategy-search)
                    rag/retrieve-chunks-by-id
                    (fn [_d _c hits _o]
                      (mapv (fn [h] {:chunk_id (:chunk_id h) :doc_num (:doc_num h)}) hits))
                    retrieval/apply-colbert-rerank
                    (fn [chunks _queries _docs _chunks-coll _opts _opts2]
                      (swap! colbert-calls inc)
                      {:chunks chunks :rerank-ms 0 :rerank-candidate-count (count chunks)})]
        (retrieval/execute-retrieval
          (ctx-for-rerank-matrix {:rerank-with-colbert true
                                  :per-strategy-rerank? true}
                                 nil ["q1"]))
        (is (= 3 @colbert-calls) "Single pass × 3 strategies")))))

(deftest rerank-matrix-union-no-per-strategy-runs-two-colbert-calls
  (testing "union=on + per-strategy=off → 1 call per pass = 2 ColBERT invocations"
    (let [colbert-calls (atom 0)]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    retrieval/run-search-strategies (stub-multi-strategy-search)
                    rag/retrieve-chunks-by-id
                    (fn [_d _c hits _o]
                      (mapv (fn [h] {:chunk_id (:chunk_id h) :doc_num (:doc_num h)}) hits))
                    retrieval/apply-colbert-rerank
                    (fn [chunks _queries _docs _chunks-coll _opts _opts2]
                      (swap! colbert-calls inc)
                      {:chunks chunks :rerank-ms 0 :rerank-candidate-count (count chunks)})]
        (retrieval/execute-retrieval
          (ctx-for-rerank-matrix {:rerank-with-colbert true
                                  :user-intent-union-enabled true}
                                 "intent string"
                                 ["expansion query"]))
        (is (= 2 @colbert-calls) "Expansion + intent passes, each one ColBERT")))))

(deftest rerank-matrix-union-per-strategy-runs-six-colbert-calls
  (testing "union=on + per-strategy=on → 3 strategies × 2 passes = 6 ColBERT invocations"
    (let [colbert-calls (atom 0)]
      (with-redefs [auto-filter/detect-query-filters (fn [& _] nil)
                    retrieval/run-search-strategies (stub-multi-strategy-search)
                    rag/retrieve-chunks-by-id
                    (fn [_d _c hits _o]
                      (mapv (fn [h] {:chunk_id (:chunk_id h) :doc_num (:doc_num h)}) hits))
                    retrieval/apply-colbert-rerank
                    (fn [chunks _queries _docs _chunks-coll _opts _opts2]
                      (swap! colbert-calls inc)
                      {:chunks chunks :rerank-ms 0 :rerank-candidate-count (count chunks)})]
        (retrieval/execute-retrieval
          (ctx-for-rerank-matrix {:rerank-with-colbert true
                                  :per-strategy-rerank? true
                                  :user-intent-union-enabled true}
                                 "intent string"
                                 ["expansion query"]))
        (is (= 6 @colbert-calls)
            "Each of expansion+intent passes runs ColBERT on each of phrase/metadata/content")))))

;; ---------------------------------------------------------------------------
;; Duplicate-file suppression (#101)
;;
;; 222 digests in the deployed corpus appear under more than one doc_num.
;; Seeing the same FILE twice in one answer is a wrong answer; seeing two
;; VERSIONS of a report is arguably right - so this keys on the digest, never
;; on title or year. Each of the three constraints from the policy is a test
;; here, because each is a way an obvious implementation gets it wrong.
;; ---------------------------------------------------------------------------

(defn- chunk-with [doc-num digests]
  {:chunk_id (str "c-" doc-num) :doc_num doc-num :file-digests digests})

(deftest suppress-duplicate-files-keeps-the-highest-ranked-registration
  (testing "a repeat of the same single file is dropped, the first is kept"
    (let [in [(chunk-with "1" ["aaa"])
              (chunk-with "2" ["aaa"])
              (chunk-with "3" ["bbb"])]
          out (#'retrieval/suppress-duplicate-files in)]
      (is (= ["1" "3"] (mapv :doc_num out))
          "input order is rank order, so doc 1 outranks doc 2")))

  (testing "the surviving chunk is returned UNCHANGED"
    ;; "Invents nothing" - no merged orgs, no synthesised title, no marker
    ;; field. A record that was never registered is worse than a duplicated one.
    (let [a (assoc (chunk-with "1" ["aaa"]) :title "As registered" :orgs_long ["A"])
          b (assoc (chunk-with "2" ["aaa"]) :title "Registered differently" :orgs_long ["B"])
          out (#'retrieval/suppress-duplicate-files [a b])]
      (is (= [a] out) "identical map, not a merged or annotated one"))))

(deftest suppress-duplicate-files-does-not-force-a-winner
  ;; Suppression is scoped to ONE RESPONSE. Keeping the top-ranked result is a
  ;; ranking decision, not a claim the other registration is false - 44% of
  ;; these groups differ in a field a user reads, and for the less-complete-org
  ;; cases there may be no fact of the matter.
  (testing "the suppressed registration is still returned in its own right"
    (let [a (chunk-with "1" ["aaa"])
          b (chunk-with "2" ["aaa"])]
      (is (= ["1"] (mapv :doc_num (#'retrieval/suppress-duplicate-files [a b]))))
      (is (= ["2"] (mapv :doc_num (#'retrieval/suppress-duplicate-files [b])))
          "doc 2 is not deleted - it is simply not shown twice alongside doc 1")
      (is (= ["2"] (mapv :doc_num (#'retrieval/suppress-duplicate-files [b a])))
          "and if doc 2 outranks doc 1, doc 2 is the one that survives")))

  (testing "nothing is mutated"
    (let [in [(chunk-with "1" ["aaa"]) (chunk-with "2" ["aaa"])]]
      (#'retrieval/suppress-duplicate-files in)
      (is (= ["1" "2"] (mapv :doc_num in)) "the caller's list is untouched"))))

(deftest suppress-duplicate-files-leaves-the-annex-case-alone
  ;; #228 separated the two populations by FILE COUNT: 216 groups where every
  ;; document has exactly one file are duplicate registrations; 6 groups where
  ;; some document carries MORE are an annex attached to two different
  ;; documents, and BOTH RECORDS ARE CORRECT.
  (testing "a document with several files is never suppressed"
    (let [single (chunk-with "1" ["aaa"])
          annex  (chunk-with "2" ["aaa" "bbb"])
          out (#'retrieval/suppress-duplicate-files [single annex])]
      (is (= ["1" "2"] (mapv :doc_num out))
          "sharing a file with a multi-file document is legitimate, not duplication")))

  (testing "and it never suppresses others either"
    (let [annex  (chunk-with "1" ["aaa" "bbb"])
          single (chunk-with "2" ["aaa"])
          out (#'retrieval/suppress-duplicate-files [annex single])]
      (is (= ["1" "2"] (mapv :doc_num out))
          "the annex does not claim the digest on the way past"))))

(deftest suppress-duplicate-files-passes-through-what-it-cannot-judge
  (testing "chunks with no digest are untouched"
    (let [in [(chunk-with "1" []) (chunk-with "2" nil) (chunk-with "3" [])]]
      (is (= ["1" "2" "3"] (mapv :doc_num (#'retrieval/suppress-duplicate-files in)))
          "absent digests must not collapse into each other")))

  (testing "several chunks from the SAME document are not this function's job"
    ;; cap-per-document handles per-document limits; conflating the two would
    ;; silently change diversity behaviour.
    (let [in [(chunk-with "1" ["aaa"]) (assoc (chunk-with "1" ["aaa"]) :chunk_id "c-1b")]]
      (is (= 2 (count (#'retrieval/suppress-duplicate-files in)))
          "same doc_num, same file - a per-document cap decides this, not a digest")))

  (testing "an empty list is an empty list"
    (is (= [] (#'retrieval/suppress-duplicate-files [])))))


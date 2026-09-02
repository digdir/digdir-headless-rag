(ns digdir.rag.retrieval-doc-title-test
  (:require [digdir.test-utils :as tu]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.rag.core :as rag]
            [digdir.rag.typesense :as ts-utils]
            [typesense.client :as ts-client]))

(defn- two-pass-mocker
  "Stub `ts-client/multi-search` with sequential responses: first call returns
   `docs-resp`, second returns `chunks-resp`. Captures each invocation's
   search-args into `!calls` for assertion."
  [!calls docs-resp chunks-resp]
  (fn [_settings search-args _opts]
    (swap! !calls conj search-args)
    (case (count @!calls)
      1 docs-resp
      2 chunks-resp)))

(deftest doc-title-noop-cases
  (testing "Empty title-fields → strategy short-circuits without calling Typesense"
    (let [called? (atom false)]
      (with-redefs [ts-client/multi-search (fn [& _] (reset! called? true) {})]
        (is (= [] (rag/search-docs-by-title "docs-coll" "chunks-coll"
                                            [] 3
                                            ["query"] nil)))
        (is (false? @called?)))))

  (testing "Zero chunk-fanout → no-op"
    (let [called? (atom false)]
      (with-redefs [ts-client/multi-search (fn [& _] (reset! called? true) {})]
        (is (= [] (rag/search-docs-by-title "docs-coll" "chunks-coll"
                                            ["linktitle"] 0
                                            ["query"] nil)))
        (is (false? @called?)))))

  (testing "Empty relaxed-queries → no-op"
    (let [called? (atom false)]
      (with-redefs [ts-client/multi-search (fn [& _] (reset! called? true) {})]
        (is (= [] (rag/search-docs-by-title "docs-coll" "chunks-coll"
                                            ["linktitle"] 3
                                            [] nil)))
        (is (false? @called?))))))

(deftest doc-title-pass-1-shape
  (testing "Pass-1 multi-search uses title-fields joined as query_by and direct filter"
    (let [!calls (atom [])
          docs-resp {:results [{:hits []}]}
          chunks-resp {:results []}]
      (with-redefs [ts-client/multi-search (two-pass-mocker !calls docs-resp chunks-resp)
                    ts-utils/make-ts-settings (tu/recording-fn {})]
        (rag/search-docs-by-title "docs-coll" "chunks-coll"
                                  ["linktitle" "frontmatter_title"]
                                  3
                                  ["create dialog"]
                                  {:fields [{:field "language"
                                             :selected-options #{"en"}
                                             :value-type :string}]}))
      (let [docs-call (first @!calls)
            searches (:searches docs-call)
            search (first searches)]
        (is (= "docs-coll" (:collection search)))
        (is (= "create dialog" (:q search)))
        ;; Per-field fan-out: each title-field gets its own search branch with a
        ;; single `query_by`, rather than one branch with the fields comma-joined.
        ;; Typesense's combined-field BM25 scoring silently drops matches when a
        ;; doc populates only one of the fields — see the same treatment of
        ;; metadata-fields in search-chunks-by-metadata.
        (is (= 2 (count searches))
            "One branch per (query, title-field) pair — 1 query x 2 fields")
        (is (= ["linktitle" "frontmatter_title"] (mapv :query_by searches))
            "Each branch scores a single field")
        (is (every? #(= "create dialog" (:q %)) searches))
        (is (= "language:=[`en`]" (:filter_by search))
            "Filter is direct (no $<coll>(...) reference wrapper) because query targets docs directly"))))

  (testing "Multiple relaxed queries → one search-branch each"
    (let [!calls (atom [])]
      (with-redefs [ts-client/multi-search (two-pass-mocker !calls {:results [{:hits []}]} {:results []})
                    ts-utils/make-ts-settings (tu/recording-fn {})]
        (rag/search-docs-by-title "docs-coll" "chunks-coll"
                                  ["linktitle"] 3
                                  ["q1" "q2" "q3"] nil))
      (is (= 3 (count (:searches (first @!calls)))))))

  ;; With a single title-field the branch count is query-count either way, so
  ;; the case above cannot tell fan-out from a comma-joined query_by. This one
  ;; pins the product.
  (testing "Queries x title-fields → one search-branch per pair"
    (let [!calls (atom [])]
      (with-redefs [ts-client/multi-search (two-pass-mocker !calls {:results [{:hits []}]} {:results []})
                    ts-utils/make-ts-settings (tu/recording-fn {})]
        (rag/search-docs-by-title "docs-coll" "chunks-coll"
                                  ["linktitle" "frontmatter_title"] 3
                                  ["q1" "q2" "q3"] nil))
      (let [searches (:searches (first @!calls))]
        (is (= 6 (count searches)) "3 queries x 2 fields")
        (is (= #{"linktitle" "frontmatter_title"} (set (map :query_by searches))))
        (is (every? #(= 1 (count (str/split (:query_by %) #","))) searches)
            "No branch comma-joins fields")))))

(deftest doc-title-pass-2-fanout
  (testing "Each matched doc gets one search-branch in pass-2 with K-limit and sort"
    (let [!calls (atom [])
          docs-resp {:results
                     [{:hits [{:document {:doc_num "doc-a" :total_chunks 5}
                               :text_match_info {:score 100}}
                              {:document {:doc_num "doc-b" :total_chunks 2}
                               :text_match_info {:score 80}}]}]}
          chunks-resp {:results
                       [{:hits [{:document {:chunk_id "a-0" :chunk_index 0 :doc_num "doc-a"}}
                                {:document {:chunk_id "a-1" :chunk_index 1 :doc_num "doc-a"}}
                                {:document {:chunk_id "a-2" :chunk_index 2 :doc_num "doc-a"}}]}
                        {:hits [{:document {:chunk_id "b-0" :chunk_index 0 :doc_num "doc-b"}}
                                {:document {:chunk_id "b-1" :chunk_index 1 :doc_num "doc-b"}}]}]}]
      (with-redefs [ts-client/multi-search (two-pass-mocker !calls docs-resp chunks-resp)
                    ts-utils/make-ts-settings (tu/recording-fn {})]
        (let [hits (rag/search-docs-by-title "docs-coll" "chunks-coll"
                                             ["linktitle"] 3
                                             ["q"] nil)]
          ;; pass-2 sent one search per matched doc
          (let [chunks-call (second @!calls)
                searches (:searches chunks-call)]
            (is (= 2 (count searches)))
            (is (every? #(= "*" (:q %)) searches))
            (is (every? #(= 3 (:limit %)) searches))
            (is (every? #(= "chunk_index:asc" (:sort_by %)) searches))
            (is (= #{"doc_num:=doc-a" "doc_num:=doc-b"}
                   (set (map :filter_by searches)))))
          ;; All 5 chunks returned, each tagged :doc-title
          (is (= 5 (count hits)))
          (is (every? #(= :doc-title (:search-type %)) hits))
          ;; Parent-doc rank propagates to all chunks
          (let [by-id (into {} (map (juxt :chunk_id identity) hits))]
            (is (= 100 (get-in by-id ["a-0" :rank])))
            (is (= 100 (get-in by-id ["a-1" :rank])))
            (is (= 80 (get-in by-id ["b-0" :rank])))
            (is (= 80 (get-in by-id ["b-1" :rank]))))
          ;; chunk_index becomes :index — used by merge for tie-breaking
          (let [by-id (into {} (map (juxt :chunk_id identity) hits))]
            (is (= 0 (get-in by-id ["a-0" :index])))
            (is (= 1 (get-in by-id ["a-1" :index])))
            (is (= 2 (get-in by-id ["a-2" :index]))))))))

  (testing "Cross-query dedupe: same doc matched twice → one search-branch in pass-2 with max rank"
    (let [!calls (atom [])
          docs-resp {:results
                     [{:hits [{:document {:doc_num "doc-x"}
                               :text_match_info {:score 60}}]}
                      {:hits [{:document {:doc_num "doc-x"}
                               :text_match_info {:score 95}}]}]}
          chunks-resp {:results
                       [{:hits [{:document {:chunk_id "x-0" :chunk_index 0 :doc_num "doc-x"}}]}]}]
      (with-redefs [ts-client/multi-search (two-pass-mocker !calls docs-resp chunks-resp)
                    ts-utils/make-ts-settings (tu/recording-fn {})]
        (let [hits (rag/search-docs-by-title "docs-coll" "chunks-coll"
                                             ["linktitle"] 3
                                             ["q-a" "q-b"] nil)]
          (is (= 1 (count (:searches (second @!calls))))
              "Doc-x deduped despite being matched in both queries")
          (is (= 95 (-> hits first :rank))
              "Best rank across queries wins after dedupe"))))))

(deftest doc-title-empty-matches
  (testing "Pass-1 returns no docs → skip pass-2 entirely"
    (let [!calls (atom [])]
      (with-redefs [ts-client/multi-search (two-pass-mocker !calls {:results [{:hits []}]} {:results []})
                    ts-utils/make-ts-settings (tu/recording-fn {})]
        (let [hits (rag/search-docs-by-title "docs-coll" "chunks-coll"
                                             ["linktitle"] 3
                                             ["query-with-no-matches"] nil)]
          (is (= [] hits))
          (is (= 1 (count @!calls))
              "Pass-2 not called when no docs matched"))))))

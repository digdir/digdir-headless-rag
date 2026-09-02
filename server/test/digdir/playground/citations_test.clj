(ns digdir.playground.citations-test
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.playground.citations :as citations]))

(deftest metadata-headings-extracts-and-orders-header-values
  (testing "Metadata heading extraction handles map and EDN-string payloads"
    (is (= ["Top level" "Sub level"]
           (citations/metadata-headings {"Header 2" "Sub level"
                                         "Header 1" "Top level"})))
    (is (= ["Main heading"]
           (citations/metadata-headings "{\"Header 1\" \"Main heading\"}")))))

(deftest source-display-data-prefers-title-and-falls-back-to-headings
  (testing "Source label uses title when present, then heading fallback"
    (let [with-title (citations/source-display-data {:title "Annual Report"
                                                     :metadata {"Header 1" "Governance"
                                                                "Header 2" "Board"}})
          no-title (citations/source-display-data {:chunk_id "chunk-123"
                                                   :metadata {"Header 1" "Fallback heading"
                                                              "Header 2" "Details"}})
          no-metadata (citations/source-display-data {:chunk_id "chunk-456"})]
      (is (= "Annual Report" (:title with-title)))
      (is (= "Governance > Board" (:heading-line with-title)))
      (is (= "Fallback heading" (:title no-title)))
      (is (= "Details" (:heading-line no-title)))
      (is (= "Untitled source" (:title no-metadata))))))

(deftest citation-source-display-data-fallback-paths
  (testing "Citation source lookup falls back to fetched chunk, then citation title/chunk id"
    (let [from-fetched (citations/citation-source-display-data
                        {:index 1 :chunk-id "c-missing"}
                        []
                        nil
                        {:chunk_id "c-missing"
                         :title "Fetched Source"
                         :metadata {"Header 1" "Fetched"}})
          from-citation-title (citations/citation-source-display-data
                               {:index 2 :chunk-id "c-2" :title "Citation Title"}
                               [])
          from-chunk-id (citations/citation-source-display-data
                         {:index 3 :chunk-id "c-3"}
                         [])]
      (is (= "Fetched Source" (:title from-fetched)))
      (is (= "Citation Title" (:title from-citation-title)))
      (is (= "c-3" (:title from-chunk-id))))))

(deftest bind-citations-sorts-by-index
  (testing "Bound citations are returned in citation index order for UI rendering"
    (let [result (citations/bind-citations
                  [{:index 3 :chunk-id "c3"}
                   {:index 1 :chunk-id "c1"}
                   {:index 2 :chunk-id "c2"}]
                  [{:chunk_id "c1" :title "One"}
                   {:chunk_id "c2" :title "Two"}
                   {:chunk_id "c3" :title "Three"}])]
      (is (= [1 2 3] (mapv :index result)))
      (is (= ["One" "Two" "Three"] (mapv #(get-in % [:source :title]) result))))))

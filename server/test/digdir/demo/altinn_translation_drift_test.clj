(ns digdir.demo.altinn-translation-drift-test
  "Unit tests for the page-pairer bridge skill."
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.demo.altinn-translation-drift :as drift]))

(defn- mk-chunk
  "Build a chunk matching the shape multi-retrieval emits (collection-named
   key holds the doc metadata; :url lives inside that nested map)."
  [coll-key url & {:as extra}]
  (merge {coll-key {:title "Page"
                    :total_chunks 1
                    :url url}
          :content_length 100
          :hit-count 1.0
          :type-ranks {:content 1.0}
          :content_markdown "stub"
          :original-rank 1.0
          :chunk_index 0
          :doc_num "doc1"
          :id "1"
          :metadata "{}"
          :chunk_id "c1"
          :original-index 0
          :search-types #{:content}}
         extra))

(deftest pairer-finds-canonical-pair
  (testing "When NB and EN chunks share a canonical path, they pair"
    (let [chunks [(mk-chunk :website_documents_ab897fbdedfa "/nb/community/about/index.md")
                  (mk-chunk :website_documents_ab897fbdedfa "/en/community/about/index.md")]
          result (drift/execute-translation-page-pairer
                  {:inputs {:chunks chunks}
                   :parameters {:max-pairs 5}})
          pairs (-> result :outputs :pairs)]
      (is (= 1 (count pairs)))
      (is (= "/community/about/index.md" (:canonical-path (first pairs))))
      (is (= "/nb/community/about/index.md" (:nb-url (first pairs))))
      (is (= "/en/community/about/index.md" (:en-url (first pairs)))))))

(deftest pairer-skips-unpaired-paths
  (testing "Paths present in only one language land in :unpaired"
    (let [chunks [(mk-chunk :website_documents_ab897fbdedfa "/nb/community/about/index.md")
                  (mk-chunk :website_documents_ab897fbdedfa "/nb/broker/about/index.md")]
          result (drift/execute-translation-page-pairer
                  {:inputs {:chunks chunks}
                   :parameters {}})
          {:keys [pairs unpaired]} (:outputs result)]
      (is (empty? pairs))
      (is (= 2 (count unpaired))))))

(deftest pairer-handles-multiple-chunks-per-side
  (testing "Multiple chunks per language collapse into the same pair"
    (let [chunks [(mk-chunk :website_documents_ab897fbdedfa "/nb/community/about/index.md" :chunk_id "a")
                  (mk-chunk :website_documents_ab897fbdedfa "/nb/community/about/index.md" :chunk_id "b")
                  (mk-chunk :website_documents_ab897fbdedfa "/en/community/about/index.md" :chunk_id "c")]
          result (drift/execute-translation-page-pairer
                  {:inputs {:chunks chunks}
                   :parameters {}})
          pair (-> result :outputs :pairs first)]
      (is (some? pair))
      (is (= 2 (count (:nb-chunks pair))))
      (is (= 1 (count (:en-chunks pair)))))))

(deftest pairer-respects-max-pairs
  (testing "max-pairs caps the output, keeping pairs with the most chunks"
    (let [coll :website_documents_ab897fbdedfa
          chunks (concat
                  ;; big pair
                  [(mk-chunk coll "/nb/a/index.md" :chunk_id "1")
                   (mk-chunk coll "/nb/a/index.md" :chunk_id "2")
                   (mk-chunk coll "/en/a/index.md" :chunk_id "3")
                   (mk-chunk coll "/en/a/index.md" :chunk_id "4")]
                  ;; small pair
                  [(mk-chunk coll "/nb/b/index.md" :chunk_id "5")
                   (mk-chunk coll "/en/b/index.md" :chunk_id "6")])
          result (drift/execute-translation-page-pairer
                  {:inputs {:chunks chunks}
                   :parameters {:max-pairs 1}})
          pairs (-> result :outputs :pairs)]
      (is (= 1 (count pairs)))
      (is (= "/a/index.md" (:canonical-path (first pairs)))))))

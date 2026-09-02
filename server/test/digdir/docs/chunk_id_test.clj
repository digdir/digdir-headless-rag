(ns digdir.docs.chunk-id-test
  "Chunk ids must separate documents.

   `chunk_id` is the Typesense primary key for chunks, and phrase ids derive
   from it, so two chunks sharing an id means the second upsert deletes the
   first. Hashing content alone did exactly that whenever two documents held
   the same text — measured at 9% of rows across 36% of documents (#72).

   The headline case is the first test: same content, two documents, two ids."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest testing is]]
            [digdir.docs.pipeline.core :as core]
            [digdir.docs.pipeline.protocol :as proto]
            [digdir.docs.pipeline.search-phrases :as search-phrases]))

(def ^:private boilerplate
  "Beholdninger omfatter varer for salg og driftsmateriell.")

(defn- mk-chunk [content] {:content_markdown content})

;; ============================================================================
;; The collision this exists to prevent
;; ============================================================================

(deftest same-content-in-two-documents-gets-two-ids
  (testing "identical text in different documents must not share an id"
    (let [a (core/assign-chunk-ids "doc-151" [(mk-chunk boilerplate)])
          b (core/assign-chunk-ids "doc-147126" [(mk-chunk boilerplate)])]
      (is (not= (:chunk_id (first a)) (:chunk_id (first b)))
          "content-only hashing made these equal, and the second upsert
           deleted the first"))))

(deftest same-content-at-the-same-position-in-two-documents-gets-two-ids
  (testing "the measured example: one report under two doc_nums, sharing its
            title page at chunk_index 0 in both"
    ;; This is why the derivation is document+content and not content+position:
    ;; position is identical here, so content+position would still collide.
    (let [title-page "Bibliometrisk analyse, Rapport 2021:1"
          a (core/assign-chunk-ids "151" [(mk-chunk title-page) (mk-chunk "body a")])
          b (core/assign-chunk-ids "147126" [(mk-chunk title-page) (mk-chunk "body b")])]
      (is (not= (:chunk_id (first a)) (:chunk_id (first b)))))))

(deftest whole-documents-of-shared-boilerplate-stay-separate
  (testing "114 reports sharing one paragraph produce 114 distinct ids"
    (let [ids (->> (range 114)
                   (map #(core/assign-chunk-ids (str "doc-" %) [(mk-chunk boilerplate)]))
                   (map (comp :chunk_id first)))]
      (is (= 114 (count (distinct ids)))))))

;; ============================================================================
;; Within one document
;; ============================================================================

(deftest repeated-content-within-one-document-gets-distinct-ids
  (testing "the same text twice in one document is two chunks, not one"
    ;; document+content alone would collide here; the occurrence counter is
    ;; what closes it.
    (let [chunks (core/assign-chunk-ids "doc-1" [(mk-chunk "same") (mk-chunk "other") (mk-chunk "same")])]
      (is (= 3 (count (distinct (map :chunk_id chunks))))))))

(deftest ids-are-stable-for-an-unchanged-document
  (testing "re-ingesting an unchanged document upserts in place"
    (let [chunks [(mk-chunk "a") (mk-chunk "b") (mk-chunk "a")]]
      (is (= (map :chunk_id (core/assign-chunk-ids "doc-1" chunks))
             (map :chunk_id (core/assign-chunk-ids "doc-1" chunks)))))))

(deftest inserting-a-chunk-does-not-change-the-others
  (testing "an edit near the top must not renumber every id below it"
    ;; The property content+position would lose: with position in the hash,
    ;; every chunk after an insertion gets a new id, so every row and every
    ;; phrase row is rewritten and the phrase cache — keyed on chunk_id —
    ;; misses for the whole tail.
    (let [before (core/assign-chunk-ids "doc-1" [(mk-chunk "a") (mk-chunk "b") (mk-chunk "c")])
          after (core/assign-chunk-ids "doc-1" [(mk-chunk "NEW") (mk-chunk "a") (mk-chunk "b") (mk-chunk "c")])]
      (is (= (map :chunk_id before)
             (rest (map :chunk_id after)))))))

(deftest editing-one-chunk-changes-only-that-id
  (testing "a changed chunk gets a new id; its neighbours keep theirs"
    (let [before (core/assign-chunk-ids "doc-1" [(mk-chunk "a") (mk-chunk "b") (mk-chunk "c")])
          after (core/assign-chunk-ids "doc-1" [(mk-chunk "a") (mk-chunk "b EDITED") (mk-chunk "c")])]
      (is (= (nth (map :chunk_id before) 0) (nth (map :chunk_id after) 0)))
      (is (not= (nth (map :chunk_id before) 1) (nth (map :chunk_id after) 1)))
      (is (= (nth (map :chunk_id before) 2) (nth (map :chunk_id after) 2))))))

;; ============================================================================
;; Shape and failure modes
;; ============================================================================

(deftest ids-keep-their-shape
  (testing "still a 12-character short hash"
    (let [chunks (core/assign-chunk-ids "doc-1" [(mk-chunk "a") (mk-chunk "b")])]
      (is (every? #(= 12 (count (:chunk_id %))) chunks))
      (is (every? #(re-matches #"[0-9a-f]{12}" (:chunk_id %)) chunks)))))

(deftest a-missing-document-id-fails-loudly
  (testing "blank doc-num throws rather than collapsing back to content-only"
    ;; Silently degrading here would reintroduce the exact data loss this
    ;; derivation exists to prevent, and it would do it invisibly.
    (is (thrown? clojure.lang.ExceptionInfo (core/chunk-id nil 0 "text")))
    (is (thrown? clojure.lang.ExceptionInfo (core/chunk-id "" 0 "text")))
    (is (thrown? clojure.lang.ExceptionInfo (core/chunk-id "   " 0 "text")))
    (is (thrown? clojure.lang.ExceptionInfo
                 (doall (core/assign-chunk-ids nil [(mk-chunk "text")]))))))

(deftest content-still-drives-the-id
  (testing "different text in the same document gives different ids"
    (let [chunks (core/assign-chunk-ids "doc-1" [(mk-chunk "one") (mk-chunk "two")])]
      (is (not= (:chunk_id (first chunks)) (:chunk_id (second chunks)))))))

;; ============================================================================
;; It propagates: phrase ids derive from chunk_id
;; ============================================================================

(deftest phrase-ids-inherit-the-separation
  (testing "same chunk text in two documents yields distinct phrase ids"
    ;; Phrase rows are keyed on sha256(chunk_id|phrase) in both
    ;; docs/loader.clj and docs/pipeline/storage.clj, so fixing chunk_id
    ;; fixes phrases with it — worth pinning, since a phrase collision
    ;; deletes rows the same way.
    (let [phrase "hva er beholdninger"
          id-for (fn [doc-num]
                   (let [c (first (core/assign-chunk-ids doc-num [(mk-chunk boilerplate)]))]
                     (core/sha256-short-hash (str (:chunk_id c) "|" phrase))))]
      (is (not= (id-for "doc-151") (id-for "doc-147126"))))))

;; ============================================================================
;; End to end through the pipeline entry point
;; ============================================================================

(deftest chunk-document-separates-documents
  (testing "two documents with byte-identical content chunk to distinct ids"
    (let [config {:chunks/strategy :header-based
                  :chunks/minimum-length 1
                  :chunks/maximum-length 100000}
          markdown "# Heading\n\nShared body text that appears in both documents.\n"
          a (proto/chunk-document config {:doc_num "151" :content_markdown markdown :url "/a"} :url)
          b (proto/chunk-document config {:doc_num "147126" :content_markdown markdown :url "/b"} :url)
          ids-a (map :chunk_id (:chunks a))
          ids-b (map :chunk_id (:chunks b))]
      (is (seq ids-a))
      (is (= (count ids-a) (count ids-b)))
      (is (every? some? ids-a))
      (is (empty? (set/intersection (set ids-a) (set ids-b)))
          "no id may be shared between the two documents"))))

;; ============================================================================
;; The phrase cache must NOT follow the id
;; ============================================================================

(deftest phrase-cache-is-shared-across-documents-with-identical-text
  (testing "duplicated content is generated once, not once per document"
    ;; Document-scoped ids gave every copy of a duplicated chunk its own id.
    ;; If the search-phrase cache still keyed on chunk_id, each copy would
    ;; miss and pay an LLM call — 9% of this corpus. The phrases depend only
    ;; on the chunk text, so the key does too.
    (let [a (first (core/assign-chunk-ids "doc-151" [(mk-chunk boilerplate)]))
          b (first (core/assign-chunk-ids "doc-147126" [(mk-chunk boilerplate)]))]
      (is (not= (:chunk_id a) (:chunk_id b))
          "precondition: the ids differ")
      (is (= (search-phrases/cache-key a "gpt-5.5" "prompt")
             (search-phrases/cache-key b "gpt-5.5" "prompt"))
          "same text, same cache entry"))))

(deftest phrase-cache-still-separates-different-text
  (testing "different content still gets its own cache entry"
    (let [[a b] (core/assign-chunk-ids "doc-1" [(mk-chunk "one") (mk-chunk "two")])]
      (is (not= (search-phrases/cache-key a "gpt-5.5" "prompt")
                (search-phrases/cache-key b "gpt-5.5" "prompt"))))))

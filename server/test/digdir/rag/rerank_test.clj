(ns digdir.rag.rerank-test
  "Unit tests for rerank-chunks internals: dedup, context accumulation,
   threshold/backfill, and prompt assembly. ColBERT HTTP is stubbed."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [digdir.config.accessor :as cfg]
            [digdir.rag.rerank :as rerank]
            [clj-http.client :as http]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-chunk
  "Build a minimal retrieved chunk map.
   `idx` is used to generate a unique chunk_id unless overridden."
  [idx & {:keys [chunk-id content title metadata collection]
          :or   {collection "docs"}}]
  (let [cid (or chunk-id (str "chunk-" idx))]
    (cond-> {:chunk_id          cid
             :content_markdown  (or content (str "Content of chunk " idx))}
      title    (assoc-in [(keyword collection) :title] title)
      metadata (assoc :metadata metadata))))

(defn- make-params
  "Build a params map with sensible defaults that can be overridden."
  [overrides]
  (merge {:translated_user_query "test query"
          :docsCollectionName    "docs"
          :rerankTopkChunks      10
          :rerankMaxChunkLength  1000
          :rerankMaxLength       100000
          :contextTopkChunks     10
          :maxContextLength      100000
          :promptRagGenerate     "Context:\n{context}\n\nQuestion:\n{question}"}
         overrides))

(defn- stub-colbert-identity
  "Returns a ColBERT stub that echoes candidates back in original order with
   descending scores. Optionally accepts a reorder vector of indices."
  ([] (stub-colbert-identity nil))
  ([reorder]
   (fn [_url {:keys [body] :as _req}]
     (let [parsed  (json/read-str body :key-fn keyword)
           n       (count (:documents parsed))
           indices (or reorder (range n))]
       {:body (json/write-str
               (map-indexed (fn [rank idx]
                              {:index idx
                               :score (double (- 1.0 (* 0.05 rank)))
                               :rank  (inc rank)})
                            indices))}))))

(defn- stub-cfg [_opts & _path]
  (case (vec _path)
    [:services :colbert :api-url] "https://colbert.test/rerank"
    [:services :colbert :api-key] "test-key"
    nil))

(defmacro with-colbert
  "Run body with cfg/get and http/post stubbed. `colbert-stub` is the http/post
   replacement (use stub-colbert-identity or a custom fn)."
  [colbert-stub & body]
  `(with-redefs [cfg/get   stub-cfg
                 http/post ~colbert-stub]
     ~@body))

;; ---------------------------------------------------------------------------
;; 1. Basic rerank flow — correct reordering
;; ---------------------------------------------------------------------------

(deftest rerank-reorders-by-colbert-response
  (testing "Chunks are reordered according to ColBERT scores"
    (let [chunks (mapv #(make-chunk % :title (str "Doc " %)) (range 5))
          ;; ColBERT returns them reversed: index 4 first, 0 last
          params (make-params {})
          result (with-colbert (stub-colbert-identity [4 3 2 1 0])
                   (rerank/rerank-chunks chunks params))
          reranked-ids (mapv :chunk_id (:reranked-chunks result))]
      (is (= ["chunk-4" "chunk-3" "chunk-2" "chunk-1" "chunk-0"]
             reranked-ids))
      (is (> (:rerank-score (first (:reranked-chunks result)))
             (:rerank-score (last (:reranked-chunks result))))))))

;; ---------------------------------------------------------------------------
;; 2. Context accumulation — maxContextLength budget
;; ---------------------------------------------------------------------------

(deftest context-accumulation-respects-max-context-length
  (testing "used-docs stops accumulating when maxContextLength is exceeded"
    (let [;; Each chunk has ~30 chars of content, but the wrapper adds title/fences
          ;; so each doc is roughly 60-80 chars. Set a tight budget.
          chunks (mapv #(make-chunk % :content (apply str (repeat 100 "x"))
                                      :title (str "T" %))
                       (range 10))
          params (make-params {:maxContextLength  250
                               :contextTopkChunks 10})
          result (with-colbert (stub-colbert-identity)
                   (rerank/rerank-chunks chunks params))
          used (count (:used-docs result))]
      (is (pos? used) "Should include at least one doc")
      (is (< used 10) "Should stop before loading all 10 docs")
      ;; Verify total length is near the budget
      (let [total-len (reduce + (map #(count (:page_content %)) (:used-docs result)))]
        (is (<= (- total-len 250) 250)
            "Total used-doc length should be in the neighbourhood of maxContextLength")))))

;; ---------------------------------------------------------------------------
;; 3. Context accumulation — contextTopkChunks limit
;; ---------------------------------------------------------------------------

(deftest context-accumulation-respects-context-topk
  (testing "used-docs never exceeds contextTopkChunks even with large budget"
    (let [chunks (mapv #(make-chunk % :content "short") (range 20))
          params (make-params {:maxContextLength  1000000
                               :contextTopkChunks 3})
          result (with-colbert (stub-colbert-identity)
                   (rerank/rerank-chunks chunks params))]
      (is (= 3 (count (:used-docs result)))))))

;; ---------------------------------------------------------------------------
;; 4. Context accumulation — duplicate chunk_id dedup
;; ---------------------------------------------------------------------------

(deftest context-accumulation-deduplicates-chunk-ids
  (testing "Duplicate chunk_ids in context candidates produce only one used-doc"
    (let [chunks [(make-chunk 0 :chunk-id "dup" :content "AAA")
                  (make-chunk 1 :chunk-id "dup" :content "BBB")
                  (make-chunk 2 :chunk-id "unique" :content "CCC")]
          ;; ColBERT returns all three; the function should dedup by chunk_id
          params (make-params {:contextTopkChunks 10})
          result (with-colbert (stub-colbert-identity)
                   (rerank/rerank-chunks chunks params))
          used-sources (mapv #(get-in % [:metadata :source]) (:used-docs result))]
      (is (= 1 (count (filter #(= "dup" %) used-sources)))
          "Only one doc per chunk_id should appear in used-docs"))))

;; ---------------------------------------------------------------------------
;; 5. Nil content_markdown is skipped in context accumulation
;; ---------------------------------------------------------------------------

(deftest context-accumulation-skips-nil-content
  (testing "Chunks with nil content_markdown are excluded from used-docs"
    (let [chunks [(assoc (make-chunk 0) :content_markdown nil)
                  (make-chunk 1 :content "real content")]
          params (make-params {:contextTopkChunks 10})
          result (with-colbert (stub-colbert-identity)
                   (rerank/rerank-chunks chunks params))
          used-sources (mapv #(get-in % [:metadata :source]) (:used-docs result))]
      (is (= ["chunk-1"] used-sources)))))

;; ---------------------------------------------------------------------------
;; 6. Threshold filtering — chunks below threshold are excluded
;; ---------------------------------------------------------------------------

(deftest threshold-excludes-below-threshold-chunks
  (testing "Chunks scoring below contextRelativeScoreThreshold are excluded from context"
    (let [chunks (mapv #(make-chunk % :content (str "c" %)) (range 5))
          ;; ColBERT scores: 1.0, 0.8, 0.6, 0.4, 0.2
          ;; threshold at 0.5 → cutoff = 0.5 → chunks 0,1,2 above, 3,4 below
          colbert-stub (fn [_url {:keys [body]}]
                         (let [parsed (json/read-str body :key-fn keyword)
                               n (count (:documents parsed))]
                           {:body (json/write-str
                                  (map (fn [i] {:index i
                                                :score (- 1.0 (* 0.2 i))
                                                :rank (inc i)})
                                       (range n)))}))
          params (make-params {:contextTopkChunks             10
                               :contextMinChunks              1
                               :contextRelativeScoreThreshold 0.5
                               :maxContextLength              1000000})
          result (with-colbert colbert-stub
                   (rerank/rerank-chunks chunks params))
          used-ids (set (map #(get-in % [:metadata :source]) (:used-docs result)))]
      (is (= 3 (count used-ids))
          "Only above-threshold chunks should enter context")
      (is (contains? used-ids "chunk-0"))
      (is (contains? used-ids "chunk-1"))
      (is (contains? used-ids "chunk-2"))
      (is (not (contains? used-ids "chunk-3")))
      (is (not (contains? used-ids "chunk-4"))))))

;; ---------------------------------------------------------------------------
;; 7. Threshold with backfill — contextMinChunks floor
;; ---------------------------------------------------------------------------

(deftest threshold-backfills-to-context-min-chunks
  (testing "When fewer chunks pass threshold than contextMinChunks, backfill from top reranked"
    (let [chunks (mapv #(make-chunk % :content (str "c" %)) (range 5))
          ;; Scores: 1.0, 0.3, 0.2, 0.1, 0.05
          ;; threshold at 0.5 → cutoff = 0.5 → only chunk-0 passes
          ;; contextMinChunks = 3 → should backfill to 3
          colbert-stub (fn [_url _req]
                         {:body (json/write-str
                                [{:index 0 :score 1.0  :rank 1}
                                 {:index 1 :score 0.3  :rank 2}
                                 {:index 2 :score 0.2  :rank 3}
                                 {:index 3 :score 0.1  :rank 4}
                                 {:index 4 :score 0.05 :rank 5}])})
          params (make-params {:contextTopkChunks             10
                               :contextMinChunks              3
                               :contextRelativeScoreThreshold 0.5
                               :maxContextLength              1000000})
          result (with-colbert colbert-stub
                   (rerank/rerank-chunks chunks params))
          used-ids (mapv #(get-in % [:metadata :source]) (:used-docs result))]
      (is (>= (count used-ids) 3)
          "Should have at least contextMinChunks docs via backfill")
      (is (= "chunk-0" (first used-ids))
          "Highest-scored chunk should come first"))))

;; ---------------------------------------------------------------------------
;; 8. Prompt assembly — {context} and {question} substitution
;; ---------------------------------------------------------------------------

(deftest prompt-assembly-substitutes-context-and-question
  (testing "full-prompt has {context} and {question} replaced"
    (let [chunks [(make-chunk 0 :content "important fact" :title "Doc A")]
          params (make-params {:promptRagGenerate "C:{context}\nQ:{question}"
                               :contextTopkChunks 5})
          result (with-colbert (stub-colbert-identity)
                   (rerank/rerank-chunks chunks params))
          prompt (:full-prompt result)]
      (is (string? prompt))
      (is (not (str/includes? prompt "{context}"))
          "Template variable {context} should be replaced")
      (is (not (str/includes? prompt "{question}"))
          "Template variable {question} should be replaced")
      (is (str/includes? prompt "important fact")
          "Context should contain chunk content")
      (is (str/includes? prompt "test query")
          "Question should contain the translated_user_query"))))

;; ---------------------------------------------------------------------------
;; 9. Nil promptRagGenerate → nil full-prompt
;; ---------------------------------------------------------------------------

(deftest nil-prompt-template-yields-nil-full-prompt
  (testing "When promptRagGenerate is nil, full-prompt is nil"
    (let [chunks [(make-chunk 0)]
          params (make-params {:promptRagGenerate nil})
          result (with-colbert (stub-colbert-identity)
                   (rerank/rerank-chunks chunks params))]
      (is (nil? (:full-prompt result))))))

;; ---------------------------------------------------------------------------
;; 10. used-chunks aligns with used-docs (chunk ordering)
;; ---------------------------------------------------------------------------

(deftest used-chunks-match-used-docs
  (testing "used-chunks contains the reranked entries that correspond to used-docs"
    (let [chunks (mapv #(make-chunk % :content (str "c" %) :title (str "T" %))
                       (range 5))
          params (make-params {:contextTopkChunks 3})
          result (with-colbert (stub-colbert-identity)
                   (rerank/rerank-chunks chunks params))
          used-doc-ids  (set (map #(get-in % [:metadata :source]) (:used-docs result)))
          used-chunk-ids (set (map :chunk_id (:used-chunks result)))]
      (is (= used-doc-ids used-chunk-ids)
          "used-chunks and used-docs should reference the same chunk_ids"))))

;; ---------------------------------------------------------------------------
;; content-snippet — the bounded read-decision preview
;; ---------------------------------------------------------------------------

(deftest content-snippet-empty-content-is-empty-string
  (is (= "" (rerank/content-snippet nil "filstørrelse")))
  (is (= "" (rerank/content-snippet "" "filstørrelse"))))

(deftest content-snippet-short-content-returned-whole-collapsed
  (testing "Content shorter than the budget is returned whole, whitespace collapsed"
    (let [snip (rerank/content-snippet "Maks   filstørrelse\n\ner 250 MB." "filstørrelse" 220)]
      (is (= "Maks filstørrelse er 250 MB." snip)
          "Newlines and runs of spaces collapse to single spaces; no truncation"))))

(deftest content-snippet-is-bounded-and-single-line
  (testing "Long content yields a single-line snippet no longer than the budget"
    (let [content (str/join " " (repeat 400 "lorem ipsum dolor"))
          budget 120
          snip (rerank/content-snippet content "lorem" budget)]
      (is (<= (count snip) budget) "Snippet respects the budget cap")
      (is (not (re-find #"\n" snip)) "Snippet is single-line")
      (is (seq snip) "Non-empty for non-empty content"))))

(deftest content-snippet-windows-to-the-query-region
  (testing "The snippet covers the query-relevant passage, not just the head"
    (let [filler (str/join " " (repeat 200 "alpha beta gamma"))
          content (str filler " VIRUSSKANN skjer automatisk ved opplasting. " filler)
          snip (rerank/content-snippet content "virusskann opplasting" 120)]
      (is (re-find #"(?i)virusskann" snip)
          "Best window includes the query term buried in the middle"))))

(deftest rerank-chunks-attaches-snippet-to-reranked-chunks
  (testing "Every reranked chunk carries a :snippet derived from its content"
    (let [chunks [(make-chunk 0 :content "Maks filstørrelse for opplasting er 250 MB per fil.")
                  (make-chunk 1 :content "Virusskann kjøres automatisk på alle opplastede filer.")]
          params (make-params {:translated_user_query "filstørrelse opplasting"})
          result (with-colbert (stub-colbert-identity)
                   (rerank/rerank-chunks chunks params))
          reranked (:reranked-chunks result)]
      (is (every? #(contains? % :snippet) reranked)
          "Each reranked chunk has a :snippet key")
      (is (every? #(and (string? (:snippet %)) (seq (:snippet %))) reranked)
          "Snippets are non-empty strings")
      (is (some #(re-find #"filstørrelse" (:snippet %)) reranked)
          "Snippet reflects the chunk's actual content"))))

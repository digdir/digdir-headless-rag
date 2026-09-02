(ns digdir.rag.core-merge-test
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.rag.core :as rag]))

(defn- approx=
  ([expected actual] (approx= expected actual 1e-6))
  ([expected actual tol]
   (< (Math/abs (- (double expected) (double actual))) tol)))

(deftest merge-aggregates-matched-questions-per-chunk
  (testing "Hits carrying :matched-question collect into a deduped :matched-questions vector on the merged row"
    (let [hq-hits [{:chunk_id "c1" :rank 0.9 :index 0
                    :search-type :hypothetical-questions
                    :matched-question "Når kom første versjon av Altinn 3?"}
                   {:chunk_id "c2" :rank 0.8 :index 1
                    :search-type :hypothetical-questions
                    :matched-question "Hva er Altinn Studio?"}]
          ;; Mix in a content hit on c1 — its lack of :matched-question
          ;; must not erase the c1 enrichment evidence.
          content-hits [{:chunk_id "c1" :rank 0.5 :index 0 :search-type :content}]
          merged (rag/merge-chunk-search-results hq-hits content-hits [])
          by-id (into {} (map (juxt :chunk_id identity)) merged)]
      (is (= ["Når kom første versjon av Altinn 3?"]
             (:matched-questions (get by-id "c1"))))
      (is (= ["Hva er Altinn Studio?"]
             (:matched-questions (get by-id "c2"))))))

  (testing "Chunks with no matched-question hits don't get the field at all"
    (let [content-hits [{:chunk_id "plain" :rank 0.5 :index 0 :search-type :content}]
          merged (rag/merge-chunk-search-results [] content-hits [])]
      (is (= 1 (count merged)))
      (is (false? (contains? (first merged) :matched-questions))
          "Missing field is the contract; empty vector would noise downstream code"))))

(deftest merge-prefers-strong-content-signal
  (testing "Content rank is primary when phrase and content disagree"
    (let [phrase-hits [{:chunk_id "distractor" :rank 0.99 :index 0 :search-type :phrase}
                       {:chunk_id "golden" :rank 0.20 :index 1 :search-type :phrase}]
          metadata-hits []
          content-hits [{:chunk_id "golden" :rank 0.90 :index 0 :search-type :content}
                        {:chunk_id "distractor" :rank 0.10 :index 1 :search-type :content}]
          merged (rag/merge-chunk-search-results phrase-hits metadata-hits content-hits)]
      (is (= "golden" (:chunk_id (first merged))))
      (is (> (:rank (first merged))
             (:rank (second merged)))))))

(deftest merge-caps-duplicate-strategy-contribution
  (testing "Repeated phrase hits for one chunk are capped at one contribution by default"
    (let [phrase-hits [{:chunk_id "dup" :rank 0.99 :index 0 :search-type :phrase}
                       {:chunk_id "dup" :rank 0.98 :index 1 :search-type :phrase}
                       {:chunk_id "dup" :rank 0.97 :index 2 :search-type :phrase}]
          merged (rag/merge-chunk-search-results phrase-hits [] [])
          dup-row (first merged)]
      (is (= "dup" (:chunk_id dup-row)))
      (is (= 1.0 (:hit-count dup-row)))
      (is (= 3 (:raw-hit-count dup-row))))))

(deftest merge-allows-configurable-strategy-cap
  (testing "Per-strategy cap can be raised via options"
    (let [phrase-hits [{:chunk_id "dup" :rank 0.99 :index 0 :search-type :phrase}
                       {:chunk_id "dup" :rank 0.98 :index 1 :search-type :phrase}
                       {:chunk_id "dup" :rank 0.97 :index 2 :search-type :phrase}]
          merged (rag/merge-chunk-search-results
                  {:strategy-contribution-caps {:phrase 2}}
                  phrase-hits
                  []
                  [])
          dup-row (first merged)]
      (is (= 2.0 (:hit-count dup-row)))
      (is (= 3 (:raw-hit-count dup-row))))))

(defn- chunk-rank-by-id
  [merged chunk-id]
  (some (fn [row] (when (= chunk-id (:chunk_id row)) (:rank row))) merged))

(deftest default-phrase-weight-rewards-phrase-heavy-chunks
  (testing "The default merge weights give a strong phrase-only chunk a materially higher rank than it
           received under the pre-fix weight (phrase=0.35).

           Regression context: in the Altinn 3 launch-date trace, the answer chunk had the highest
           single-query phrase-rank but matched only phrase, so it ranked below 40 out of 88 with the
           old weights. The fix raises phrase weight to 0.7 so such chunks climb into the visible
           top-read window."
    (let [phrase-hits [{:chunk_id "answer" :rank 0.90 :index 0 :search-type :phrase}
                       {:chunk_id "distractor" :rank 0.50 :index 1 :search-type :phrase}
                       {:chunk_id "filler" :rank 0.10 :index 2 :search-type :phrase}]
          content-hits [{:chunk_id "distractor" :rank 0.70 :index 0 :search-type :content}
                        {:chunk_id "filler" :rank 0.30 :index 1 :search-type :content}]
          ;; Default weights (post-fix: :phrase 0.7)
          merged-default (rag/merge-chunk-search-results phrase-hits [] content-hits)
          ;; Pre-fix weights for comparison
          merged-pre-fix (rag/merge-chunk-search-results
                          {:strategy-weights {:content 1.0 :phrase 0.35 :metadata 0.2 :unknown 0.15}}
                          phrase-hits
                          []
                          content-hits)
          answer-rank-default (chunk-rank-by-id merged-default "answer")
          answer-rank-pre-fix (chunk-rank-by-id merged-pre-fix "answer")]
      (is (some? answer-rank-default))
      (is (some? answer-rank-pre-fix))
      (is (> answer-rank-default answer-rank-pre-fix)
          (str "Answer chunk should rank higher under the new default phrase weight. "
               "default=" answer-rank-default " pre-fix=" answer-rank-pre-fix))
      ;; Concrete bound: 0.7 * 1.0 (normalized phrase-rank) = 0.70 under new weights,
      ;; vs 0.35 * 1.0 = 0.35 under pre-fix. Phrase-only chunks should roughly double.
      (is (>= answer-rank-default 0.65))
      (is (<= answer-rank-pre-fix 0.40)))))

(deftest default-doc-title-weight-sits-between-content-and-phrase
  (testing "A doc-title-only chunk and a phrase-only chunk with equal normalized rank
           rank the doc-title chunk higher (0.8 > 0.7); a content-only chunk still beats both
           (1.0 > 0.8). This is the intended weighting for the new strategy."
    (let [doc-title-hits [{:chunk_id "doc-title-only" :rank 0.9 :index 0 :search-type :doc-title}]
          phrase-hits [{:chunk_id "phrase-only" :rank 0.9 :index 0 :search-type :phrase}]
          content-hits [{:chunk_id "content-only" :rank 0.9 :index 0 :search-type :content}]
          merged (rag/merge-chunk-search-results phrase-hits doc-title-hits content-hits [])]
      (is (= "content-only" (:chunk_id (first merged))))
      (is (= "doc-title-only" (:chunk_id (second merged))))
      (is (= "phrase-only" (:chunk_id (nth merged 2)))))))

;; ---------------------------------------------------------------------------
;; RRF (Reciprocal Rank Fusion)
;; ---------------------------------------------------------------------------

(deftest rrf-default-is-opt-in
  (testing "Without :merge-mode :rrf, behavior is unchanged (weighted-sum default)"
    (let [phrase-hits [{:chunk_id "a" :rank 0.9 :index 0 :search-type :phrase}]
          content-hits [{:chunk_id "b" :rank 0.5 :index 0 :search-type :content}]
          weighted-sum (rag/merge-chunk-search-results phrase-hits [] content-hits)
          explicit-weighted-sum (rag/merge-chunk-search-results
                                 {:merge-mode :weighted-sum}
                                 phrase-hits [] content-hits)]
      (is (= (map :chunk_id weighted-sum)
             (map :chunk_id explicit-weighted-sum))
          "Default and explicit :weighted-sum produce same ordering"))))

(deftest rrf-rank-1-in-two-strategies-beats-rank-1-in-one
  (testing "Multi-strategy agreement raises RRF score above single-strategy hits"
    (let [;; "winner" is rank-1 in both phrase and content
          phrase-hits [{:chunk_id "winner" :rank 0.5 :index 0 :search-type :phrase}
                       {:chunk_id "phrase-only" :rank 0.9 :index 1 :search-type :phrase}]
          content-hits [{:chunk_id "winner" :rank 0.5 :index 0 :search-type :content}
                        {:chunk_id "content-only" :rank 0.9 :index 1 :search-type :content}]
          merged (rag/merge-chunk-search-results
                  {:merge-mode :rrf}
                  phrase-hits [] content-hits)]
      (is (= "winner" (:chunk_id (first merged)))
          "Chunk found by 2 strategies outranks chunks found by 1, regardless of score"))))

(deftest rrf-lower-rank-beats-higher-rank
  (testing "Within a single strategy, rank-1 beats rank-10"
    (let [content-hits [{:chunk_id "top" :rank 1.0 :index 0 :search-type :content}
                        {:chunk_id "bottom" :rank 1.0 :index 9 :search-type :content}]
          merged (rag/merge-chunk-search-results
                  {:merge-mode :rrf}
                  [] [] content-hits)]
      (is (= "top" (:chunk_id (first merged)))))))

(deftest rrf-uses-best-rank-per-strategy
  (testing "When a chunk appears multiple times in one strategy, RRF uses its best (lowest) :index"
    (let [phrase-hits [{:chunk_id "dup" :rank 0.5 :index 0 :search-type :phrase}
                       {:chunk_id "dup" :rank 0.5 :index 5 :search-type :phrase}
                       {:chunk_id "dup" :rank 0.5 :index 10 :search-type :phrase}]
          merged (rag/merge-chunk-search-results
                  {:merge-mode :rrf
                   :strategy-weights {:phrase 1.0}}
                  phrase-hits [] [])
          {:keys [rank]} (first merged)]
      ;; RRF with weight 1.0, k=60, best index=0 (rank-1) → 1/(60+1) ≈ 0.0164
      (is (approx= (/ 1.0 (+ 60 1)) rank))
      (is (= 1 (count merged))))))

(deftest rrf-k-constant-affects-spread
  (testing "Smaller k = more spread between top and bottom"
    (let [hits [{:chunk_id "top" :rank 1.0 :index 0 :search-type :content}
                {:chunk_id "bot" :rank 1.0 :index 9 :search-type :content}]
          k-60 (rag/merge-chunk-search-results {:merge-mode :rrf :rrf-k 60} [] [] hits)
          k-10 (rag/merge-chunk-search-results {:merge-mode :rrf :rrf-k 10} [] [] hits)
          ratio-60 (/ (:rank (first k-60)) (:rank (second k-60)))
          ratio-10 (/ (:rank (first k-10)) (:rank (second k-10)))]
      ;; With k=60: ratio = (1/61) / (1/70) = 70/61 ≈ 1.15
      ;; With k=10: ratio = (1/11) / (1/20) = 20/11 ≈ 1.82
      (is (> ratio-10 ratio-60)
          (str "k=10 should give more spread; got " ratio-10 " vs " ratio-60)))))

(deftest rrf-weights-still-bias
  (testing ":strategy-weights multiply each strategy's RRF term (preserves tuning)"
    (let [phrase-hits [{:chunk_id "phrase-1" :rank 1.0 :index 0 :search-type :phrase}]
          content-hits [{:chunk_id "content-1" :rank 1.0 :index 0 :search-type :content}]
          ;; Default-ish weights: content 1.0, phrase 0.7 → content wins
          default-weighted (rag/merge-chunk-search-results
                            {:merge-mode :rrf
                             :strategy-weights {:content 1.0 :phrase 0.7}}
                            phrase-hits [] content-hits)
          ;; Inverted: phrase 1.0, content 0.5 → phrase wins
          phrase-priority (rag/merge-chunk-search-results
                           {:merge-mode :rrf
                            :strategy-weights {:content 0.5 :phrase 1.0}}
                           phrase-hits [] content-hits)]
      (is (= "content-1" (:chunk_id (first default-weighted))))
      (is (= "phrase-1" (:chunk_id (first phrase-priority)))))))

(deftest rrf-pool-size-invariance-vs-weighted-sum
  (testing "Adding a sparse strategy doesn't redistribute scores under RRF;
            under weighted-sum, min-max normalization re-spreads the sparse
            strategy's hits."
    ;; Build a case where one strategy has one strong hit and one weak hit
    ;; (broad pool), and another strategy has just one hit (narrow pool).
    ;; Under weighted-sum, the lone-hit strategy's normalization gives the hit
    ;; rank 1.0; under RRF, it gets rank-1 contribution.
    (let [content-hits [{:chunk_id "shared" :rank 0.8 :index 0 :search-type :content}
                        {:chunk_id "content-only" :rank 0.2 :index 1 :search-type :content}]
          ;; phrase has only the shared chunk
          phrase-hits [{:chunk_id "shared" :rank 0.4 :index 0 :search-type :phrase}]
          rrf (rag/merge-chunk-search-results
               {:merge-mode :rrf}
               phrase-hits [] content-hits)
          weighted (rag/merge-chunk-search-results phrase-hits [] content-hits)]
      ;; Under both modes, "shared" should win (multi-strategy confirmation).
      ;; The point isn't WHICH wins; it's that the formulas have different
      ;; sensitivities to per-strategy pool size — and RRF's formula is
      ;; bounded by 1/(k+1) per strategy regardless.
      (is (= "shared" (:chunk_id (first rrf))))
      (is (= "shared" (:chunk_id (first weighted))))
      ;; Sanity: RRF rank for "shared" is bounded as designed:
      ;;   w_phrase / (60+1) + w_content / (60+1)
      ;;   = 0.7/61 + 1.0/61 ≈ 0.0279
      (is (approx= (+ (/ 0.7 61.0) (/ 1.0 61.0))
                   (:rank (first rrf))
                   1e-4)))))

(deftest rrf-preserves-search-types-set
  (testing "Each chunk's :search-types reflects which strategies hit it"
    (let [phrase-hits [{:chunk_id "x" :rank 0.5 :index 0 :search-type :phrase}]
          content-hits [{:chunk_id "x" :rank 0.5 :index 0 :search-type :content}
                        {:chunk_id "y" :rank 0.5 :index 1 :search-type :content}]
          merged (rag/merge-chunk-search-results
                  {:merge-mode :rrf}
                  phrase-hits [] content-hits)
          by-id (into {} (map (juxt :chunk_id identity) merged))]
      (is (= #{:phrase :content} (:search-types (get by-id "x"))))
      (is (= #{:content} (:search-types (get by-id "y")))))))

(deftest rrf-preserves-matched-questions
  (testing "Enrichment-style :matched-question round-trips through RRF merge"
    (let [hq-hits [{:chunk_id "c1" :rank 0.9 :index 0
                    :search-type :hypothetical-questions
                    :matched-question "Test Q?"}]
          merged (rag/merge-chunk-search-results
                  {:merge-mode :rrf}
                  [] hq-hits [])]
      (is (= ["Test Q?"] (:matched-questions (first merged)))))))

;; ---------------------------------------------------------------------------
;; :rrf-with-score (score-weighted RRF)
;; ---------------------------------------------------------------------------

(deftest rrf-with-score-suppresses-low-rank-hits
  "Score-weighted RRF multiplies each strategy's RRF term by the
   chunk's min-max-normalized score. The TOP hit in any strategy gets
   the full RRF term (score=1.0); lower hits get progressively less
   (down to 0 at the worst-ranked); pure RRF gives all hits a positive
   1/(k+rank) regardless. This is the design contrast — score-weighted
   RRF more aggressively drops borderline hits than pure RRF."
  (testing "Lowest-rank hits contribute ~0 under score-weighted RRF"
    (let [content-hits [{:chunk_id "top" :rank 100.0 :index 0 :search-type :content}
                        {:chunk_id "mid" :rank 50.0 :index 5 :search-type :content}
                        {:chunk_id "bottom" :rank 1.0 :index 19 :search-type :content}]
          ;; Pure RRF: all 3 chunks get positive contribution
          rrf (rag/merge-chunk-search-results
                {:merge-mode :rrf :strategy-weights {:content 1.0}}
                content-hits)
          sw  (rag/merge-chunk-search-results
                {:merge-mode :rrf-with-score :strategy-weights {:content 1.0}}
                content-hits)
          rank-by-id (fn [merged id]
                       (->> merged (filter #(= id (:chunk_id %))) first :rank))]
      ;; Under pure RRF, bottom > 0 (gets 1/(60+20) ≈ 0.0125)
      (is (pos? (rank-by-id rrf "bottom")))
      ;; Under score-weighted RRF, bottom = 0 (normalized score = 0 at worst-rank)
      (is (zero? (rank-by-id sw "bottom"))
          "Worst-ranked hit (normalized score = 0) gets 0 contribution"))))

(deftest rrf-with-score-multi-strategy-still-rewarded
  (testing "Two-strategy agreement beats single-strategy under :rrf-with-score
            — as long as the multi-strategy chunk isn't at the min-normalized
            position in every strategy (which would zero out its score)."
    (let [;; In each strategy: winner is the top hit (max score → normalized 1.0).
          ;; Min hits are the fillers (normalized 0.0).
          phrase-hits [{:chunk_id "winner" :rank 0.9 :index 0 :search-type :phrase}
                       {:chunk_id "phrase-filler" :rank 0.2 :index 1 :search-type :phrase}]
          content-hits [{:chunk_id "winner" :rank 0.9 :index 0 :search-type :content}
                        {:chunk_id "content-only-rank-2" :rank 0.5 :index 1 :search-type :content}]
          merged (rag/merge-chunk-search-results
                  {:merge-mode :rrf-with-score}
                  phrase-hits [] content-hits)]
      (is (= "winner" (:chunk_id (first merged)))
          "Chunk found by 2 strategies as their top hit still wins under :rrf-with-score"))))

(deftest rrf-with-score-min-normalized-zeros-out
  "Documents the edge case: a chunk that happens to be at the MIN
   normalized score in every strategy gets 0 contribution under
   :rrf-with-score, even with multi-strategy agreement. This is
   the intended design — bottom-of-pool hits get aggressively
   suppressed. Surprising in degenerate test inputs; rare in practice
   where strategies return 20+ hits each."
  (testing "Min-normalized-score in every strategy → 0 fused score"
    (let [;; "edge" is the lowest-scoring hit in both strategies.
          phrase-hits [{:chunk_id "edge" :rank 0.1 :index 0 :search-type :phrase}
                       {:chunk_id "p" :rank 0.9 :index 1 :search-type :phrase}]
          content-hits [{:chunk_id "edge" :rank 0.1 :index 0 :search-type :content}
                        {:chunk_id "c" :rank 0.9 :index 1 :search-type :content}]
          merged (rag/merge-chunk-search-results
                  {:merge-mode :rrf-with-score}
                  phrase-hits [] content-hits)
          edge-rank (->> merged (filter #(= "edge" (:chunk_id %))) first :rank)]
      (is (zero? edge-rank)
          "Chunk at min-normalized position in every strategy gets 0 under :rrf-with-score"))))

;; ---------------------------------------------------------------------------
;; :combmnz
;; ---------------------------------------------------------------------------

(deftest combmnz-multi-strategy-multiplicative-boost
  (testing "CombMNZ multiplies weighted-sum by count-of-strategies-finding-d"
    ;; "shared" appears in BOTH strategies; "lone" appears in only one with
    ;; equal-or-better score. Under weighted-sum: lone wins on raw score.
    ;; Under CombMNZ: shared wins because of the ×N multiplier.
    (let [phrase-hits [{:chunk_id "shared" :rank 0.5 :index 0 :search-type :phrase}
                       {:chunk_id "lone" :rank 1.0 :index 1 :search-type :phrase}]
          content-hits [{:chunk_id "shared" :rank 0.5 :index 0 :search-type :content}]
          weighted-sum (rag/merge-chunk-search-results phrase-hits [] content-hits)
          combmnz (rag/merge-chunk-search-results
                   {:merge-mode :combmnz}
                   phrase-hits [] content-hits)
          shared-mnz (->> combmnz (filter #(= "shared" (:chunk_id %))) first :rank)
          shared-ws (->> weighted-sum (filter #(= "shared" (:chunk_id %))) first :rank)]
      ;; Under default weighted-sum (content=1.0, phrase=0.7), "lone" wins
      ;; (single strategy with score 1.0 × 0.7 = 0.7 > shared's 0.5 × 0.7 + 0.5 × 1.0 = 0.85)
      ;; Wait that's not right. shared scores 0.85, lone scores 0.7. shared wins anyway.
      ;; Let me just verify CombMNZ result: shared has N=2 multiplier
      (is (approx= (* shared-ws 2) shared-mnz)
          (str "CombMNZ should be weighted-sum × 2 for a 2-strategy hit. "
               "weighted-sum=" shared-ws " combmnz=" shared-mnz)))))

(deftest combmnz-single-strategy-multiplier-is-1
  (testing "A chunk found by only one strategy has CombMNZ = weighted-sum (N=1)"
    (let [hits [{:chunk_id "x" :rank 0.5 :index 0 :search-type :content}]
          weighted-sum (->> (rag/merge-chunk-search-results hits [] [])
                            first :rank)
          combmnz (->> (rag/merge-chunk-search-results
                        {:merge-mode :combmnz} hits [] [])
                       first :rank)]
      (is (approx= weighted-sum combmnz)))))

(deftest merge-opts-can-override-default-weights
  (testing ":strategy-weights override lets callers tune the merge formula without touching code"
    (let [phrase-hits [{:chunk_id "phrase-only" :rank 0.8 :index 0 :search-type :phrase}]
          content-hits [{:chunk_id "content-heavy" :rank 0.8 :index 0 :search-type :content}]
          ;; Default weights: :content 1.0, :phrase 0.7  →  content-heavy wins
          merged-default (rag/merge-chunk-search-results phrase-hits [] content-hits)
          ;; Override: raise phrase weight above content weight  →  phrase-only wins
          merged-override (rag/merge-chunk-search-results
                           {:strategy-weights {:content 0.5 :phrase 1.0}}
                           phrase-hits
                           []
                           content-hits)]
      (is (= "content-heavy" (:chunk_id (first merged-default))))
      (is (= "phrase-only" (:chunk_id (first merged-override)))))))

(ns digdir.sweep.v3-cites-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.sweep.v3-cites :as v3]))

;; The v3-cites loader is tested against the real, version-controlled
;; markdown files. If those files drift, these tests catch the
;; mismatch — that's the intended contract (the scoring is anchored to
;; the same ground truth as the slice-1/slice-2 validation docs).

(def ^:private v3-dir "../plans/in-progress/target-optimal-baseline-v3")

(defn- load-v3
  []
  (v3/load-v3-cites v3-dir))

(deftest loads-all-seven-question-files
  (testing "Exactly 7 v3 question files (01..07) are parsed"
    (let [entries (load-v3)]
      (is (= 7 (count entries)))
      (is (= ["01" "02" "03" "04" "05" "06" "07"]
             (mapv :question-num entries))))))

(deftest cite-counts-match-the-baseline
  (testing "Per-question cite counts match the slice-2 validation table"
    ;; Per plans/in-progress/target-optimal-baseline-v3/10-auto-filter-rules-validation.md:
    ;; Q1=3, Q2=2, Q3=2, Q4=3, Q5=5, Q6=3, Q7=5 → 23 total
    (let [entries (load-v3)
          counts (mapv (comp count :cites) entries)]
      (is (= [3 2 2 3 5 3 5] counts))
      (is (= 23 (reduce + counts))))))

(deftest known-chunk-ids-present-per-question
  (testing "Spot-check: each question file contains its v3-cited chunk_ids"
    (let [by-num (into {} (map (juxt :question-num :cites) (load-v3)))]
      ;; Q1 — About-dialogporten chunks 0/1/2
      (is (contains? (get by-num "01") "ea7de904e1aa"))
      (is (contains? (get by-num "01") "a233d1c22ebe"))
      (is (contains? (get by-num "01") "b8ddca7bace0"))
      ;; Q2 — Creating-dialogs chunks 0/1
      (is (contains? (get by-num "02") "c4e1cd6328b8"))
      (is (contains? (get by-num "02") "378b160a17a5"))
      ;; Q4 — NB migration chunks 0/1 + EN sibling chunk 2
      (is (contains? (get by-num "04") "bdd5427ec8d5"))
      (is (contains? (get by-num "04") "688781d672e2"))
      (is (contains? (get by-num "04") "8e71d9016de9"))
      ;; Q7 — setup-subscription + the NEW webhook-secret cites
      (is (contains? (get by-num "07") "4aa2b0740446"))
      (is (contains? (get by-num "07") "09eeadadc174"))
      (is (contains? (get by-num "07") "9b4017645a43")))))

(deftest question-text-extracted-correctly
  (testing "H1 question text is parsed from each file"
    (let [by-num (into {} (map (juxt :question-num :question) (load-v3)))]
      (is (str/starts-with? (get by-num "01") "What is Dialogporten"))
      (is (str/starts-with? (get by-num "02") "How do I create a new dialog"))
      (is (str/starts-with? (get by-num "03") "Hvordan setter jeg opp autentisering"))
      (is (str/starts-with? (get by-num "07") "Does Altinn support webhook signatures")))))

(defn- approx=
  ([expected actual] (approx= expected actual 1e-6))
  ([expected actual tol]
   (< (Math/abs (- (double expected) (double actual))) tol)))

(deftest score-retrieval-math
  (testing "Hit count, misses, hit-rate, and hit-positions"
    (let [cites #{"a" "b" "c"}
          retrieved ["x" "a" "y" "c" "z"]
          result (v3/score-retrieval cites retrieved 30)]
      (is (= 2 (:hit-count result)))
      (is (= 3 (:total result)))
      (is (= #{"a" "c"} (:hits result)))
      (is (= #{"b"} (:misses result)))
      (is (approx= 2/3 (:hit-rate result)))
      ;; 1-based ranks in retrieved order
      (is (= {"a" 2 "c" 4} (:hit-positions result)))))

  (testing "Top-k cap excludes later hits"
    (let [cites #{"a" "b"}
          retrieved ["x" "x" "x" "a" "b"]
          result (v3/score-retrieval cites retrieved 3)]
      (is (= 0 (:hit-count result))
          "Both cites are at positions 4 and 5; top-3 cap excludes them")))

  (testing "Empty cite set → hit-rate 0.0 without divide-by-zero"
    (let [result (v3/score-retrieval #{} ["x" "y"] 30)]
      (is (= 0 (:hit-count result)))
      (is (= 0.0 (:hit-rate result)))))

  (testing "Empty retrieved → zero hits"
    (let [result (v3/score-retrieval #{"a" "b"} [] 30)]
      (is (= 0 (:hit-count result)))
      (is (= 0.0 (:hit-rate result))))))

(deftest aggregate-scores-rolls-up
  (testing "Sum of hits and cites, overall hit-rate"
    (let [per-q [{:hit-count 2 :total 3 :hit-rate 2/3 :hit-positions {"a" 1} :misses #{}}
                 {:hit-count 0 :total 2 :hit-rate 0.0 :hit-positions {} :misses #{"x" "y"}}
                 {:hit-count 5 :total 5 :hit-rate 1.0 :hit-positions {} :misses #{}}]
          agg (v3/aggregate-scores per-q)]
      (is (= 10 (:total-cites agg)))
      (is (= 7 (:total-hits agg)))
      (is (approx= 7/10 (:overall-hit-rate agg)))
      (is (= 3 (count (:per-question agg)))))))

(deftest slice-1-baseline-reproduction
  (testing "Score the slice-1 retrieval result against v3 cites.

           The slice-1 validation doc records 6/23 hits across the 7
           questions. This test reconstructs the per-question
           retrieved sets from that doc (using the chunk_ids visible
           in the doc-title-strategy-validation table) and confirms
           our scoring matches the documented 26% number."
    (let [entries (load-v3)
          by-num (into {} (map (juxt :question-num :cites) entries))
          ;; Reconstructed from
          ;; plans/in-progress/target-optimal-baseline-v3/09-doc-title-strategy-validation.md
          slice1-hits-per-q
          [;; Q1: 2 hits — a233d1c22ebe (#9), ea7de904e1aa (#11)
           {:qnum "01" :hits ["a233d1c22ebe" "ea7de904e1aa"]}
           ;; Q2: 0 hits
           {:qnum "02" :hits []}
           ;; Q3: 0 hits
           {:qnum "03" :hits []}
           ;; Q4: 3 hits — 8e71d9016de9 (#3), 688781d672e2 (#21), bdd5427ec8d5 (#30)
           {:qnum "04" :hits ["8e71d9016de9" "688781d672e2" "bdd5427ec8d5"]}
           ;; Q5: 0 hits
           {:qnum "05" :hits []}
           ;; Q6: 0 hits (slice-1 doc was 1/3, but counts a doc-level match
           ;; for 837753b0cd68; the chunk_id wasn't in the v3 cited set —
           ;; cited chunk 0 of that doc is `d871d2d193b8`, and slice-1
           ;; surfaced chunk 2 instead. So 0 by strict chunk-id match.)
           {:qnum "06" :hits []}
           ;; Q7: 1 hit — 4aa2b0740446 (#3 by content)
           {:qnum "07" :hits ["4aa2b0740446"]}]
          scored (for [{:keys [qnum hits]} slice1-hits-per-q
                       :let [cites (get by-num qnum)]]
                   ;; Build a retrieved list that's exactly the hits in
                   ;; order. The scorer just checks set intersection
                   ;; capped at top-k=30; the actual ranks don't change
                   ;; pass/fail at top-30 since each q has < 30 hits.
                   (v3/score-retrieval cites hits 30))
          agg (v3/aggregate-scores scored)]
      ;; Slice 1 reports 6/23 by chunk-id. Strict chunk-id matching
      ;; agrees on Q1=2, Q4=3, Q7=1 = 6.
      (is (= 6 (:total-hits agg)))
      (is (= 23 (:total-cites agg)))
      ;; 6/23 = 26.087%; assert the documented "26%" rounds correctly.
      (let [pct (* 100 (:overall-hit-rate agg))]
        (is (and (>= pct 25.0) (<= pct 27.0))
            (str "Expected ~26% overall hit-rate; got " pct "%"))))))

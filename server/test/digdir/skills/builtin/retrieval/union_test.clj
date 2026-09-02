(ns digdir.skills.builtin.retrieval.union-test
  (:require [clojure.test :refer [deftest is testing]]
            [digdir.skills.builtin.retrieval.union :as u]))

(defn- c [id]
  {:chunk_id id :tag id})

(defn- ids [chunks]
  (mapv :chunk_id chunks))

;; -----------------------------------------------------------------------------
;; rank-interleave
;; -----------------------------------------------------------------------------

(deftest interleave-empty-inputs
  (testing "Both empty → empty"
    (is (= [] (u/rank-interleave [] []))))
  (testing "Empty a → all of b in order"
    (is (= ["b1" "b2" "b3"] (ids (u/rank-interleave [] [(c "b1") (c "b2") (c "b3")])))))
  (testing "Empty b → all of a in order"
    (is (= ["a1" "a2"] (ids (u/rank-interleave [(c "a1") (c "a2")] []))))))

(deftest interleave-no-overlap
  (testing "Alternates a,b,a,b... when disjoint"
    (is (= ["a1" "b1" "a2" "b2" "a3"]
           (ids (u/rank-interleave [(c "a1") (c "a2") (c "a3")]
                                   [(c "b1") (c "b2")]))))))

(deftest interleave-heavy-overlap
  (testing "Dedup keeps first occurrence (a wins when chosen first)"
    (is (= ["x1" "b1" "x2" "b2"]
           (ids (u/rank-interleave [(c "x1") (c "x2") (c "b1")]
                                   [(c "b1") (c "x1") (c "b2")]))))))

;; -----------------------------------------------------------------------------
;; cap-merge
;; -----------------------------------------------------------------------------

(deftest cap-merge-basic
  (testing "Takes first N from a then concatenates b (dedup'd)"
    (is (= ["a1" "a2" "a3" "b1" "b2"]
           (ids (u/cap-merge [(c "a1") (c "a2") (c "a3") (c "a4")]
                             [(c "b1") (c "b2")]
                             3))))))

(deftest cap-merge-overlap
  (testing "Expansion entries already in prefix are skipped"
    (is (= ["a1" "a2" "a3" "b1" "b2"]
           (ids (u/cap-merge [(c "a1") (c "a2") (c "a3")]
                             [(c "b1") (c "a2") (c "b2") (c "a1")]
                             3))))))

(deftest cap-merge-n-exceeds-a
  (testing "Cap > len(a) → all of a then dedup'd b"
    (is (= ["a1" "a2" "b1"]
           (ids (u/cap-merge [(c "a1") (c "a2")]
                             [(c "b1") (c "a1")]
                             5))))))

(deftest cap-merge-empty-inputs
  (is (= [] (u/cap-merge [] [] 3)))
  (is (= ["b1"] (ids (u/cap-merge [] [(c "b1")] 3))))
  (is (= ["a1" "a2"] (ids (u/cap-merge [(c "a1") (c "a2")] [] 3)))))

;; -----------------------------------------------------------------------------
;; rrf-merge
;; -----------------------------------------------------------------------------

(deftest rrf-merge-disjoint
  (testing "Disjoint lists with equal length → interleaved by rank"
    (let [a [(c "a1") (c "a2") (c "a3")]
          b [(c "b1") (c "b2") (c "b3")]
          out (ids (u/rrf-merge a b 60))]
      (is (= 6 (count out)))
      (is (= "a1" (first out)) "a's #1 ties with b's #1 but a comes first in distinct-ids order"))))

(deftest rrf-merge-heavy-overlap
  (testing "Chunks present in both passes outrank single-pass chunks"
    (let [a [(c "shared") (c "a-only")]
          b [(c "b-only") (c "shared")]
          out (ids (u/rrf-merge a b 60))]
      (is (= "shared" (first out)) "shared gets sum of two reciprocals")
      (is (= 3 (count out))))))

(deftest rrf-merge-symmetry
  (testing "(rrf a b) and (rrf b a) produce identical score sums per id"
    (let [a [(c "x") (c "y") (c "z")]
          b [(c "z") (c "x") (c "y")]
          out-ab (u/rrf-merge a b 60)
          out-ba (u/rrf-merge b a 60)
          scores-of (fn [out]
                      (let [a-scores (into {} (map-indexed
                                                (fn [i ch] [(:chunk_id ch) (/ 1.0 (+ 60 (inc i)))])
                                                a))
                            b-scores (into {} (map-indexed
                                                (fn [i ch] [(:chunk_id ch) (/ 1.0 (+ 60 (inc i)))])
                                                b))]
                        (into {} (map (fn [ch]
                                        [(:chunk_id ch)
                                         (+ (get a-scores (:chunk_id ch) 0.0)
                                            (get b-scores (:chunk_id ch) 0.0))]))
                              out)))]
      (is (= (scores-of out-ab) (scores-of out-ba))
          "score totals are commutative; ordering between equal scores may differ"))))

(deftest rrf-merge-empty-inputs
  (is (= [] (u/rrf-merge [] [] 60)))
  (is (= ["b1"] (ids (u/rrf-merge [] [(c "b1")] 60))))
  (is (= ["a1"] (ids (u/rrf-merge [(c "a1")] [] 60)))))

;; -----------------------------------------------------------------------------
;; union-merge dispatch
;; -----------------------------------------------------------------------------

(deftest union-merge-default-is-cap
  (testing "Default mode is :cap N=3"
    (is (= ["a1" "a2" "a3" "b1"]
           (ids (u/union-merge [(c "a1") (c "a2") (c "a3") (c "a4")]
                               [(c "b1")] {}))))))

(deftest union-merge-honors-mode-keyword-or-string
  (let [a [(c "a1") (c "a2")] b [(c "b1") (c "b2")]]
    (is (= (u/union-merge a b {:mode :interleave})
           (u/union-merge a b {:mode "interleave"}))
        "Both keyword and string mode select :interleave")
    (is (= (u/union-merge a b {:mode :interleave})
           (u/union-merge a b {:mode ":interleave"}))
        "Leading colon tolerated")))

(deftest union-merge-cap-knob
  (is (= ["a1" "b1" "b2"]
         (ids (u/union-merge [(c "a1") (c "a2")] [(c "b1") (c "b2")]
                             {:mode :cap :cap 1})))))

(deftest union-merge-rrf-k-knob
  (testing "Different k values produce same ranking on simple disjoint case"
    ;; RRF with disjoint lists of equal length always preserves a-first order
    ;; regardless of k, so this just exercises the knob without crashing.
    (is (= 4 (count (u/union-merge [(c "a1") (c "a2")]
                                    [(c "b1") (c "b2")]
                                    {:mode :rrf :rrf-k 10}))))
    (is (= 4 (count (u/union-merge [(c "a1") (c "a2")]
                                    [(c "b1") (c "b2")]
                                    {:mode :rrf :rrf-k 1000}))))))

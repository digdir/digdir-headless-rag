(ns digdir.skills.builtin.retrieval.union
  "Union-merge two ranked chunk lists produced by separate retrieval passes.

   Slice 23 carries the slice-21/22 first-pass union from the bb v3-score
   harness into the production retrieval skill. The harness was the spec:
   see bb.edn `v3-score` (search for `union-merge`).

   Three modes:
   - :interleave — alternating zip with chunk-id dedup, intent first.
   - :cap        — intent's first N chunks lock into the first N slots,
                   expansion fills the remainder (dedup'd). Slice-22
                   measurement says cap N=3 dominates the other modes.
   - :rrf        — Reciprocal Rank Fusion (Cormack et al. SIGIR 2009).
                   Score(c) = Σ 1/(k+rank). Default k=60.

   All functions operate on full chunk maps and dedup on `:chunk_id`
   (server-side underscore key)."
  (:require [clojure.string :as str]))

(defn- chunk-id [c]
  (or (:chunk_id c) (:chunk-id c)))

(defn rank-interleave
  "Alternate one chunk from a, then b, ..., skipping any chunk-id already
   in the output."
  [a b]
  (loop [out [] seen #{} ai 0 bi 0]
    (cond
      (and (>= ai (count a)) (>= bi (count b))) out
      :else
      (let [from-a? (and (< ai (count a))
                         (or (>= bi (count b))
                             (zero? (mod (+ ai bi) 2))))]
        (if from-a?
          (let [x (nth a ai) id (chunk-id x)]
            (if (or (nil? id) (contains? seen id))
              (recur out seen (inc ai) bi)
              (recur (conj out x) (conj seen id) (inc ai) bi)))
          (let [x (nth b bi) id (chunk-id x)]
            (if (or (nil? id) (contains? seen id))
              (recur out seen ai (inc bi))
              (recur (conj out x) (conj seen id) ai (inc bi)))))))))

(defn cap-merge
  "Take a's first N chunks (the intent prefix), then concatenate b
   skipping chunks already in the prefix. Cap > (count a) collapses
   to (concat a (remove ... b))."
  [a b n]
  (let [a-prefix (vec (take n a))
        a-ids (into #{} (keep chunk-id) a-prefix)
        b-rest (vec (remove (fn [c] (contains? a-ids (chunk-id c))) b))]
    (vec (concat a-prefix b-rest))))

(defn rrf-merge
  "Reciprocal Rank Fusion. For each chunk-id, score = Σ 1/(k+rank) across
   passes. Sort descending by score. Ties broken by first-occurrence order
   in (concat b a) — same convention as the harness."
  [a b k]
  (let [score-list (fn [lst]
                     (->> lst
                          (map-indexed (fn [i c]
                                         [(chunk-id c)
                                          (/ 1.0 (+ k (inc i)))]))
                          (into {})))
        a-scores (score-list a)
        b-scores (score-list b)
        id->chunk (into {} (map (juxt chunk-id identity)) (concat b a))
        all-ids (distinct (concat (map chunk-id a) (map chunk-id b)))]
    (->> all-ids
         (map (fn [id]
                {:chunk (get id->chunk id)
                 :score (+ (get a-scores id 0.0)
                           (get b-scores id 0.0))}))
         (sort-by (fn [x] (- (:score x))))
         (map :chunk)
         vec)))

(defn- normalize-mode
  [mode]
  (cond
    (keyword? mode) mode
    (string? mode) (keyword (str/replace mode #"^:" ""))
    :else :cap))

(defn union-merge
  "Merge two reranked chunk lists per the configured mode.

   `intent-chunks` is the result of the intent-only pass; `expansion-chunks`
   is the result of the full expansion pass. Mode-specific knobs:
     :cap → :cap (default 3)
     :rrf → :rrf-k (default 60)
   `:interleave` takes no extra knobs.

   Returns a vector of full chunk maps (dedup'd by :chunk_id)."
  [intent-chunks expansion-chunks {:keys [mode cap rrf-k]
                                   :or {mode :cap cap 3 rrf-k 60}}]
  (case (normalize-mode mode)
    :interleave (rank-interleave intent-chunks expansion-chunks)
    :cap (cap-merge intent-chunks expansion-chunks cap)
    :rrf (rrf-merge intent-chunks expansion-chunks rrf-k)
    (cap-merge intent-chunks expansion-chunks cap)))

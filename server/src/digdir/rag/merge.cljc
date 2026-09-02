(ns digdir.rag.merge
  "Pure search-result merge helpers for RAG retrieval.")

(defn normalize-ranks
  "Normalizes ranks within a list using min-max normalization to 0-1 range.
   This ensures scores from different search types (phrase, metadata, content)
   are comparable when merged."
  [results]
  (if (empty? results)
    results
    (let [ranks (map :rank results)
          min-rank (apply min ranks)
          max-rank (apply max ranks)
          range-val (- max-rank min-rank)]
      (if (zero? range-val)
        (map #(assoc % :rank 1.0) results)
        (map #(assoc % :rank (double (/ (- (:rank %) min-rank) range-val))) results)))))

(def ^:private default-merge-strategy-weights
  "Relative contribution weights when combining per-search-type ranks into a merged
   rank. Phrase-weight was historically 0.35, which systematically demoted chunks
   that were found only via phrase match (typical for factual-lookup answers where
   exact-token-sequence hits matter more than bag-of-words content density). Raised
   to 0.7 so a strong single-type phrase hit can compete with multi-type mediocre
   hits. `:doc-title` sits between content and phrase: a strong doc-title match
   means the parent doc is on-topic, but the chunk's actual answer-bearing power
   is decided by content. Tenants can override via the retrieval skill's
   :strategy-weights parameter."
  {:content 1.0
   :doc-title 0.8
   :phrase 0.7
   :metadata 0.2
   :unknown 0.15})

(def ^:private default-merge-contribution-caps
  {:content 1
   :doc-title 1
   :phrase 1
   :metadata 1
   :unknown 1})

(defn- to-num
  [r]
  (cond
    (nil? r) 0.0
    (number? r) (double r)
    (string? r) (try #?(:clj (java.lang.Double/parseDouble r) :cljs (js/parseFloat r)) (catch #?(:clj Exception :cljs js/Error) _ 0.0))
    :else 0.0))

(defn- normalize-search-type
  [search-type]
  (if (keyword? search-type)
    search-type
    (if (nil? search-type)
      :unknown
      (keyword (str search-type)))))

(def ^:private default-rrf-k
  "Smoothing constant for Reciprocal Rank Fusion. Cormack, Clarke, Buettcher
   (SIGIR 2009) used 60; Elasticsearch defaults to 60. Smaller k spreads more
   between high/low ranks (more selectivity); larger k smooths more.
   Tunable per call via `:rrf-k` in merge-opts."
  60)

(defn- merge-opts?
  [m]
  (and (map? m)
       (or (contains? m :strategy-weights)
           (contains? m :strategy-contribution-caps)
           (contains? m :merge-mode)
           (contains? m :rrf-k))))

(defn- parse-merge-args
  [args]
  (if (merge-opts? (first args))
    [(or (first args) {}) (rest args)]
    [{} args]))

(defn- collect-matched-questions
  "Read-tool integration: collect any `:matched-question` strings carried by
   enrichment-style strategies. Currently only `:hypothetical-questions`
   populates this. Order-stable and deduped so the agent's preview line shows
   a clean list."
  [hits]
  (->> hits
       (keep :matched-question)
       (remove (fn [q] (or (nil? q) (= "" q))))
       distinct
       vec))

(defn- summarize-chunk-hits-weighted-sum
  "Original min-max-normalized weighted-sum fusion.

   Per-strategy ranks have already been normalized to [0, 1] by
   `normalize-ranks` before this is called. We take the *max* normalized
   rank within each strategy (a chunk hit multiple times by the same
   strategy keeps its best score), then sum `weight_s * rank_s` across
   strategies."
  [hits strategy-weights strategy-caps]
  (let [hits-by-type (group-by (comp normalize-search-type :search-type) hits)
        type-ranks (into {}
                         (map (fn [[search-type shits]]
                                [search-type (apply max (map #(to-num (:rank %)) shits))]))
                         hits-by-type)
        search-types (set (keys type-ranks))
        best-index (->> hits
                        (map #(long (or (:index %) 0)))
                        (apply min))
        best-rank (if (seq type-ranks) (apply max (vals type-ranks)) 0.0)
        weighted-rank (reduce-kv (fn [acc search-type rank]
                                   (+ acc (* (double (get strategy-weights search-type 0.0))
                                             (double rank))))
                                 0.0
                                 type-ranks)
        capped-hit-score (reduce-kv (fn [acc search-type shits]
                                      (+ acc (double (min (long (count shits))
                                                          (long (get strategy-caps search-type 1))))))
                                    0.0
                                    hits-by-type)
        matched-questions (collect-matched-questions hits)
        rank-for-sort (if (pos? weighted-rank) weighted-rank best-rank)]
    (cond-> {:rank rank-for-sort
             :index best-index
             :search-types search-types
             :hit-count capped-hit-score
             :raw-hit-count (count hits)
             :type-ranks type-ranks}
      (seq matched-questions)
      (assoc :matched-questions matched-questions))))

(defn- summarize-chunk-hits-rrf-with-score
  "Score-weighted RRF: each strategy's RRF term is multiplied by the chunk's
   normalized score within that strategy.

       score(d) = Σ_s  weight_s · normalized_score_s(d) · 1/(k + rank_s(d))

   Addresses the lost-score-nuance failure mode of pure RRF: when one
   strategy's top hit has a strong absolute score and another's has a
   weak one, this mode preserves the contrast. Equivalent to pure RRF
   when all scores are uniform; equivalent to a rank-discounted
   weighted-sum when ranks aren't a factor.

   Assumes :rank has already been min-max-normalized to [0, 1] by
   `normalize-ranks` (same prep as :weighted-sum)."
  [hits strategy-weights strategy-caps rrf-k]
  (let [hits-by-type (group-by (comp normalize-search-type :search-type) hits)
        per-type-contrib
        (into {}
              (map (fn [[search-type shits]]
                     ;; Best hit in this strategy = lowest :index. Use its
                     ;; normalized :rank as the score multiplier.
                     (let [best-hit (apply min-key #(long (or (:index %) 0)) shits)
                           best-index (long (or (:index best-hit) 0))
                           best-score (to-num (:rank best-hit))
                           rank-1based (inc best-index)
                           w (double (get strategy-weights search-type 0.0))]
                       [search-type (* w best-score (/ 1.0 (+ (double rrf-k) (double rank-1based))))])))
              hits-by-type)
        score (reduce + 0.0 (vals per-type-contrib))
        search-types (set (keys hits-by-type))
        best-index (->> hits (map #(long (or (:index %) 0))) (apply min))
        capped-hit-score (reduce-kv (fn [acc search-type shits]
                                      (+ acc (double (min (long (count shits))
                                                          (long (get strategy-caps search-type 1))))))
                                    0.0
                                    hits-by-type)
        matched-questions (collect-matched-questions hits)]
    (cond-> {:rank score
             :index best-index
             :search-types search-types
             :hit-count capped-hit-score
             :raw-hit-count (count hits)
             :type-ranks per-type-contrib}
      (seq matched-questions)
      (assoc :matched-questions matched-questions))))

(defn- summarize-chunk-hits-combmnz
  "CombMNZ (Fox & Shaw, TREC-2 1994):

       score(d) = (Σ_s  weight_s · normalized_score_s(d)) × N(d)

   where N(d) is the number of strategies that found d. Multi-strategy
   agreement gets an explicit multiplicative reward on top of the
   weighted-sum.

   Assumes :rank has already been min-max-normalized to [0, 1] by
   `normalize-ranks` (same prep as :weighted-sum)."
  [hits strategy-weights strategy-caps]
  (let [hits-by-type (group-by (comp normalize-search-type :search-type) hits)
        ;; Per-strategy: take the max normalized score (best agreement of
        ;; multi-hits within one strategy).
        per-type-score
        (into {}
              (map (fn [[search-type shits]]
                     [search-type (apply max (map #(to-num (:rank %)) shits))]))
              hits-by-type)
        weighted-sum (reduce-kv (fn [acc search-type s]
                                  (+ acc (* (double (get strategy-weights search-type 0.0))
                                            (double s))))
                                0.0
                                per-type-score)
        ;; N(d) = number of distinct strategies that found d.
        n-strategies (count hits-by-type)
        score (* weighted-sum (double n-strategies))
        search-types (set (keys hits-by-type))
        best-index (->> hits (map #(long (or (:index %) 0))) (apply min))
        capped-hit-score (reduce-kv (fn [acc search-type shits]
                                      (+ acc (double (min (long (count shits))
                                                          (long (get strategy-caps search-type 1))))))
                                    0.0
                                    hits-by-type)
        matched-questions (collect-matched-questions hits)]
    (cond-> {:rank score
             :index best-index
             :search-types search-types
             :hit-count capped-hit-score
             :raw-hit-count (count hits)
             :type-ranks per-type-score}
      (seq matched-questions)
      (assoc :matched-questions matched-questions))))

(defn- summarize-chunk-hits-rrf
  "Reciprocal Rank Fusion (Cormack, Clarke, Buettcher SIGIR 2009).

   For each strategy that hit this chunk, take its best (lowest) `:index`
   as the 0-based rank, add 1 for the 1-based position, and contribute
   `weight_s / (k + rank_s)` to the merged score. Pool-size invariant:
   each strategy's contribution is bounded by `weight_s / (k + 1)`, so
   no strategy can dominate by virtue of its score distribution.

   `:strategy-weights` is preserved as a multiplier on each strategy's
   RRF term (standard RRF uses uniform weights = 1; we keep weights so
   caller-supplied biases survive). The `:hit-count` and per-strategy
   contribution caps still apply as a tiebreaker / observability
   signal."
  [hits strategy-weights strategy-caps rrf-k]
  (let [hits-by-type (group-by (comp normalize-search-type :search-type) hits)
        ;; Per-strategy best rank (0-based; +1 to make 1-based for RRF).
        per-type-best-rank (into {}
                                 (map (fn [[search-type shits]]
                                        [search-type
                                         (inc (apply min (map #(long (or (:index %) 0)) shits)))]))
                                 hits-by-type)
        ;; Per-strategy RRF contribution: w_s / (k + rank_s)
        per-type-rrf (into {}
                           (map (fn [[search-type rank-1based]]
                                  [search-type
                                   (* (double (get strategy-weights search-type 0.0))
                                      (/ 1.0 (+ (double rrf-k) (double rank-1based))))]))
                           per-type-best-rank)
        rrf-score (reduce + 0.0 (vals per-type-rrf))
        search-types (set (keys hits-by-type))
        best-index (->> hits
                        (map #(long (or (:index %) 0)))
                        (apply min))
        capped-hit-score (reduce-kv (fn [acc search-type shits]
                                      (+ acc (double (min (long (count shits))
                                                          (long (get strategy-caps search-type 1))))))
                                    0.0
                                    hits-by-type)
        matched-questions (collect-matched-questions hits)]
    (cond-> {:rank rrf-score
             :index best-index
             :search-types search-types
             :hit-count capped-hit-score
             :raw-hit-count (count hits)
             ;; Keep the existing :type-ranks field shape but populate it
             ;; with the RRF contribution per strategy, so observability
             ;; downstream can still see "this chunk's content-strategy
             ;; component was X, phrase was Y."
             :type-ranks per-type-rrf}
      (seq matched-questions)
      (assoc :matched-questions matched-questions))))

(defn merge-chunk-search-results
  "Merges chunk IDs from multiple search results, preserving search-type information
   and combining ranks when chunks appear in multiple searches.

   Four fusion modes (selected via `:merge-mode` in the optional first arg):

   - `:weighted-sum` (default, backwards-compatible) — min-max normalize each
     strategy's ranks to [0, 1], then sum `weight_s * rank_s` across strategies.
     Sensitive to per-strategy score distributions; can be destabilized by
     pool-size changes.
   - `:rrf` — Reciprocal Rank Fusion (Cormack, Clarke, Buettcher SIGIR 2009):
     `Σ_s weight_s / (k + rank_s)`. Rank-based, pool-size invariant, naturally
     bounds each strategy's contribution. The `:rrf-k` constant defaults to 60.
     Pure rank fusion; discards score-strength signal.
   - `:rrf-with-score` — Score-weighted RRF:
     `Σ_s weight_s · normalized_score_s · 1/(k + rank_s)`. Preserves both
     rank-fusion stability AND per-strategy score-strength contrast.
     A response to corpora where rank-based fusion alone loses load-bearing
     information.
   - `:combmnz` — CombMNZ (Fox & Shaw, TREC-2 1994):
     `(Σ_s weight_s · normalized_score_s) × N(d)`, where N is the count of
     strategies that found d. Multi-strategy agreement gets an explicit
     multiplicative reward on top of the weighted-sum.

   Optional first argument (any subset):
   {:merge-mode                  :weighted-sum | :rrf | :rrf-with-score | :combmnz
    :strategy-weights            {:content 1.0 :phrase 0.7 :metadata 0.2 :doc-title 0.8}
    :strategy-contribution-caps  {:content 1 :phrase 1 :metadata 1 :doc-title 1}
    :rrf-k                       60}"
  [& args]
  (let [[opts search-results] (parse-merge-args args)
        merge-mode (or (:merge-mode opts) :weighted-sum)
        strategy-weights (merge default-merge-strategy-weights
                                (:strategy-weights opts))
        strategy-caps (merge default-merge-contribution-caps
                             (:strategy-contribution-caps opts))
        rrf-k (or (:rrf-k opts) default-rrf-k)
        ;; Pure RRF uses raw rank position; all other modes need min-max
        ;; normalization to put scores on a common [0, 1] scale.
        prepared-results (case merge-mode
                           :rrf search-results
                           (map normalize-ranks search-results))
        all-results (apply concat prepared-results)
        grouped-by-chunk-id (group-by :chunk_id all-results)
        summarize (case merge-mode
                    :rrf
                    (fn [hits] (summarize-chunk-hits-rrf hits strategy-weights strategy-caps rrf-k))
                    :rrf-with-score
                    (fn [hits] (summarize-chunk-hits-rrf-with-score hits strategy-weights strategy-caps rrf-k))
                    :combmnz
                    (fn [hits] (summarize-chunk-hits-combmnz hits strategy-weights strategy-caps))
                    (fn [hits] (summarize-chunk-hits-weighted-sum hits strategy-weights strategy-caps)))]
    (->> grouped-by-chunk-id
         (map (fn [[chunk-id hits]]
                (assoc (summarize hits) :chunk_id chunk-id)))
         (sort-by (juxt (comp - :rank) (comp - :hit-count) :index))
         vec)))

(ns digdir.skills.enrichment.batch-verdict
  "Keep/revert verdict computation for a batch of enrichments.

   Promoted out of `digdir.skills.enrichment.eval-sweep` in slice 2a of #82
   (#94). It is pure: it reads scored run rows as data and decides, per
   chunk, whether an enrichment earned its place. It runs nothing and knows
   nothing about how those rows were produced, so it carries no dependency
   on the research harness.

   That separation is why this could come across on its own. The admin
   dashboard already called `compute-batch-verdict` WITHOUT `run-matrix` —
   the verdict was always separable from the sweep that feeds it; it just
   happened to live in the same file.

   ROW CONTRACT — a scored run row needs only five keys:

     :question-id           the question this run answered
     :config-id             \"enrichment-off\" or \"enrichment-on\"
     :recall-at-20          double
     :answer-substring-hit? boolean   ;; note the trailing ?
     :retrieved-chunk-ids   \";\"-joined chunk ids, in rank order

   Anything that can produce those five keys can drive this verdict."
  (:require [clojure.string :as str]))

(defn- mean
  [rows k]
  (let [vs (keep k rows)]
    (if (seq vs) (/ (reduce + 0.0 (map double vs)) (count vs)) 0.0)))

(defn- frac-true
  [rows k]
  (if (seq rows)
    (/ (double (count (filter #(true? (k %)) rows))) (count rows))
    0.0))

(defn- top20-set
  "The top-20 reranked chunk-ids of one scored run row (the :recall-at-20 window).
   `:retrieved-chunk-ids` is a `;`-joined string in rank order."
  [row]
  (->> (str/split (str (:retrieved-chunk-ids row)) #";")
       (remove str/blank?)
       (take 20)
       set))

(defn- chunk-frac-top20
  "Fraction of `rows` whose top-20 contains `chunk-id`."
  [rows chunk-id]
  (if (seq rows)
    (/ (double (count (filter #(contains? (top20-set %) chunk-id) rows))) (count rows))
    0.0))

(defn compute-batch-verdict
  "Per-chunk keep/revert from the off/on sweep rows.

   For each chunk: improved? = enrichment-on raises its top-20 frequency over off.
   A shared regression check (any regression-set row dropping recall@20 / answer-hit
   under enrichment-on) vetoes ALL keeps for the batch."
  [rows target-id chunk-ids regression-ids]
  (let [by (group-by (juxt :question-id :config-id) rows)
        t-off (get by [target-id "enrichment-off"] [])
        t-on  (get by [target-id "enrichment-on"] [])
        regressions (for [qid regression-ids
                          :let [r-off (get by [qid "enrichment-off"] [])
                                r-on  (get by [qid "enrichment-on"] [])
                                rec-drop? (< (mean r-on :recall-at-20)
                                             (mean r-off :recall-at-20))
                                ans-drop? (< (frac-true r-on :answer-substring-hit?)
                                             (frac-true r-off :answer-substring-hit?))]
                          :when (or rec-drop? ans-drop?)]
                      {:question-id qid :recall-drop? rec-drop? :answer-drop? ans-drop?})
        regressed? (boolean (seq regressions))
        verdicts (into {}
                       (for [c chunk-ids
                             :let [f-off (chunk-frac-top20 t-off c)
                                   f-on  (chunk-frac-top20 t-on c)
                                   improved? (> f-on f-off)
                                   keep? (and improved? (not regressed?))]]
                         [c {:keep? keep?
                             :improved? improved?
                             :top20-off f-off
                             :top20-on f-on
                             :regressed? regressed?
                             :reason (str (format "top-20 %.2f→%.2f" f-off f-on)
                                          (when regressed?
                                            (str " · REGRESSED " (count regressions)))
                                          " → " (if keep? "keep" "revert"))}]))
        kept (vec (for [[c v] verdicts :when (:keep? v)] c))
        reverted (vec (for [[c v] verdicts :when (not (:keep? v))] c))]
    {:verdicts verdicts
     :kept kept
     :reverted reverted
     :regressed? regressed?
     :regressions (vec regressions)
     :batch-summary (str (count kept) "/" (count chunk-ids) " kept"
                         (when regressed?
                           (str " · " (count regressions) " regression(s) vetoed keeps")))}))

;; =============================================================================
;; Skill body
;; =============================================================================

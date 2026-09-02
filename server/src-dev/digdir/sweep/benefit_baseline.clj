(ns digdir.sweep.benefit-baseline
  "Establish the LLM-judge benefit baseline over the 190-row set (the 10 enriched
   chunks) and measure how well the cheap semantic proxy + the token-IDF filter
   track it. Three local 'worth removing?' signals compared head to head:
     - token-IDF (propose-prune): lexical broadness
     - semantic-proxy (verify-prune-benefit): embedding discriminativeness
     - LLM-judge (verify-prune-benefit): gold
   Read-only. ~one LLM call per phrase."
  (:require [digdir.skills.enrichment.propose-prune :as pp]
            [digdir.skills.enrichment.verify-prune-benefit :as vpb]
            [digdir.sweep.runner :as runner]
            [digdir.rag.typesense :as tsu]
            [typesense.client :as ts]))

(defn- enriched-chunk-ids [settings enr-coll]
  (->> (ts/multi-search settings
                        {:searches [{:collection enr-coll :q "*" :per_page 250
                                     :include_fields "chunk_id"}]}
                        {:query_by "phrase"})
       :results first :hits (map (comp :chunk_id :document)) distinct vec))

(defn run [{:keys [enrichment-collection threshold tenant]
            :or {tenant "digdir" threshold 4.0}}]
  (let [{:keys [collections]} (#'runner/resolve-dataset-config!
                               {:tenant tenant :dataset-config-key "default"})
        pc (:phrases-collection collections)
        cc (:chunks-collection collections)
        settings (tsu/make-ts-settings {:tenant tenant})
        cids (enriched-chunk-ids settings enrichment-collection)
        rows
        (for [cid cids
              :let [pr (:outputs (pp/execute-propose-prune
                                  {:inputs {:chunk-id cid :phrases-collection-name pc
                                            :enrichment-collection-name enrichment-collection}
                                   :parameters {:specificity-threshold threshold}
                                   :skill-params {:tenant tenant}}))
                    flagged (set (map :phrase (:candidates pr)))]
              ph (concat (:candidates pr) (:kept pr))]
          (let [b (:outputs (vpb/execute-verify-prune-benefit
                             {:inputs {:chunk-id cid :candidate-phrase (:phrase ph)
                                       :phrases-collection-name pc :chunks-collection cc}
                              :parameters {:semantic-k 10} :skill-params {:tenant tenant}}))]
            {:chunk-id cid :phrase (:phrase ph) :spec (:specificity ph)
             :idf-remove? (contains? flagged (:phrase ph))
             :llm-remove? (:llm-remove? b)
             :sem-remove? (:semantic-remove? b)
             :rank-remove? (:content-rank-remove? b)
             :c-rank (:content-rank b)
             :dist (:semantic-nearest-other-distance b)}))
        rows (vec rows)
        n (count rows)
        cnt (fn [pred] (count (filter pred rows)))
        rate (fn [pred] (if (zero? n) 0.0 (* 100.0 (/ (cnt pred) n))))]
    (println (format "phrases evaluated: %d  (over %d enriched chunks)\n" n (count cids)))
    (println (format "REMOVE rates:  token-IDF %.0f%%   semantic %.0f%%   content-rank %.0f%%   LLM %.0f%%"
                     (rate :idf-remove?) (rate :sem-remove?) (rate :rank-remove?) (rate :llm-remove?)))
    (let [pr-re (fn [k] ; precision/recall of signal k vs LLM truth
                  (let [tp (cnt #(and (k %) (:llm-remove? %)))
                        fp (cnt #(and (k %) (not (:llm-remove? %))))
                        fn* (cnt #(and (not (k %)) (:llm-remove? %)))]
                    [(if (zero? (+ tp fp)) 0.0 (* 100.0 (/ tp (+ tp fp))))
                     (if (zero? (+ tp fn*)) 0.0 (* 100.0 (/ tp (+ tp fn*))))]))]
      (let [[p r] (pr-re :sem-remove?)]
        (println (format "\nLLM vs SEMANTIC:      agreement %.0f%%   precision %.0f%%  recall %.0f%%"
                         (rate #(= (:llm-remove? %) (:sem-remove? %))) p r)))
      (let [[p r] (pr-re :rank-remove?)]
        (println (format "LLM vs CONTENT-RANK:  agreement %.0f%%   precision %.0f%%  recall %.0f%%"
                         (rate #(= (:llm-remove? %) (:rank-remove? %))) p r))))
    (println (format "LLM vs token-IDF agreement: %.0f%%" (rate #(= (:llm-remove? %) (:idf-remove? %)))))
    ;; Pre-filter tuning: content-rank feeds the LLM, so we want RECALL (don't miss
    ;; LLM-removes) at an acceptable flag-rate (= LLM-call cost). Swept in-memory off
    ;; the stored :c-rank (LLM verdict is threshold-independent).
    (println "\ncontent-rank threshold sweep (pre-filter; remove? = c-rank > t OR absent):")
    (let [llm-removes (cnt :llm-remove?)]
      (doseq [t [0 1 2 3]]
        (let [flag? (fn [r] (or (nil? (:c-rank r)) (> (:c-rank r) t)))
              flagged (cnt flag?)
              tp (cnt #(and (flag? %) (:llm-remove? %)))]
          (println (format "  t=%d  flagged %.0f%% (LLM-call rate)  recall %.0f%%  precision %.0f%%"
                           t (* 100.0 (/ flagged n))
                           (if (zero? llm-removes) 0.0 (* 100.0 (/ tp llm-removes)))
                           (if (zero? flagged) 0.0 (* 100.0 (/ tp flagged))))))))
    (println "\nLLM=REMOVE but token-IDF KEPT (semantic-generic that IDF misses):")
    (doseq [r rows :when (and (:llm-remove? r) (not (:idf-remove? r)))]
      (println (format "  spec=%.2f dist=%s  %s" (:spec r)
                       (if (:dist r) (format "%.3f" (double (:dist r))) "-") (:phrase r))))
    (println "\nLLM=KEEP but token-IDF flagged (IDF false-positives):")
    (doseq [r rows :when (and (not (:llm-remove? r)) (:idf-remove? r))]
      (println (format "  spec=%.2f dist=%s  %s" (:spec r)
                       (if (:dist r) (format "%.3f" (double (:dist r))) "-") (:phrase r))))
    rows))

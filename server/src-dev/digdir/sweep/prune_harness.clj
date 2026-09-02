(ns digdir.sweep.prune-harness
  "~190-row gate-discrimination confirmation for the prune half. Runs
   propose-prune -> verify-prune over the chunks that already carry enrichment
   (verified-phrases) rows — testing both their primary AND enrichment phrases on
   real, varied chunks — and reports how the gate discriminates at scale BEFORE we
   wire the production graph. No deletions (read-only probe)."
  (:require [digdir.skills.enrichment.propose-prune :as pp]
            [digdir.skills.enrichment.verify-prune :as vp]
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
        dc (:docs-collection collections)
        settings (tsu/make-ts-settings {:tenant tenant})
        cids (enriched-chunk-ids settings enrichment-collection)
        results
        (for [cid cids]
          (let [pr (:outputs (pp/execute-propose-prune
                              {:inputs {:chunk-id cid :phrases-collection-name pc
                                        :enrichment-collection-name enrichment-collection}
                               :parameters {:specificity-threshold threshold}
                               :skill-params {:tenant tenant}}))
                cands (:candidates pr)
                verds (for [c cands]
                        (let [v (:outputs (vp/execute-verify-prune
                                           {:inputs {:chunk-id cid :candidate-phrase (:phrase c)
                                                     :phrases-collection-name pc
                                                     :chunks-collection cc :docs-collection dc}
                                            :parameters {} :skill-params {:tenant tenant}}))]
                          (assoc c :prune? (:prune? v) :stranded? (:stranded? v))))]
            {:chunk-id cid
             :evaluated (-> pr :provenance :evaluated)
             :kept (count (:kept pr))
             :flagged (count cands)
             :pruned (count (filter :prune? verds))
             :vetoed (count (remove :prune? verds))
             :verds verds}))]
    (println (format "enriched chunks: %d | threshold: %.1f | primary: %s\n" (count cids) (double threshold) pc))
    (println (format "%-14s eval flag prune veto kept" "chunk"))
    (doseq [r results]
      (println (format "%-14s %4d %4d %5d %4d %4d" (:chunk-id r)
                       (:evaluated r) (:flagged r) (:pruned r) (:vetoed r) (:kept r))))
    (let [tot (fn [k] (reduce + (map k results)))]
      (println (format "\nTOTALS: evaluated=%d flagged-broad=%d -> PRUNED=%d vetoed-stranded=%d kept-specific=%d"
                       (tot :evaluated) (tot :flagged) (tot :pruned) (tot :vetoed) (tot :kept))))
    (println "\nsample PRUNED (broad + safe):")
    (doseq [r results, v (:verds r) :when (:prune? v)]
      (println (format "  spec=%.2f [%s] %s" (:specificity v) (name (:collection v)) (:phrase v))))
    (println "\nsample VETOED (broad but stranded -> kept):")
    (doseq [r results, v (:verds r) :when (and (not (:prune? v)) (:stranded? v))]
      (println (format "  spec=%.2f [%s] %s" (:specificity v) (name (:collection v)) (:phrase v))))
    results))

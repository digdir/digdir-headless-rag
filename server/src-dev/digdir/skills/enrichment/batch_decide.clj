(ns digdir.skills.enrichment.batch-decide
  "P2 (batch) — the decide/act step that pairs with :builtin/enrichment-eval-sweep.

   The self-improve graph applies all proposed enrichments in the foreach, then
   ONE batch eval-sweep scores them (per-chunk :verdicts). This skill consumes
   those verdicts + the foreach :chunk-outcomes (which carry each chunk's
   propose-provenance :prompt-hash) and REVERTS the chunks the verdict failed —
   leaving the kept enrichments written. One pass after the foreach, not per
   chunk.

   The revert is scoped by prompt-hash (same as the old per-chunk :decide), so it
   only removes the rows THIS run added. src-dev only."
  (:require [digdir.rag.skills.core :as skills]
            [digdir.skills.enrichment.revert-chunk :as revert]))

(def batch-decide-metadata
  {:skill-id :builtin/enrichment-batch-decide
   :name "Enrichment batch decide"
   :description "Revert the chunks a batch eval-sweep verdict failed (keep the rest), in one pass after the foreach. Consumes :verdicts (per-chunk) + :chunk-outcomes (for prompt-hash)."
   :category :orchestration
   :inputs [:verdicts :chunk-outcomes :enrichment-collection-name]
   :outputs [:outcomes :kept :reverted :decisions :summary]
   :parameters {:dry-run? :boolean}
   :version "1.0.0"
   :tags #{:enrichment :self-improve :sweep}})

(defn execute-batch-decide
  [{:keys [inputs parameters skill-params]}]
  (let [{:keys [verdicts chunk-outcomes enrichment-collection-name]} inputs
        dry-run? (boolean (:dry-run? parameters))
        outcomes (vec (filter map? (or chunk-outcomes [])))
        ;; Decide per outcome and MERGE the decision back into the outcome map, so
        ;; the existing compose-report (which partitions by :decision and reads
        ;; each outcome's :verify shadow) consumes `:outcomes` unchanged.
        merged
        (vec (for [o outcomes
                   :let [cid (:chunk-id o)
                         prompt-hash (get-in o [:proposal :provenance :prompt-hash])
                         v (get verdicts cid)
                         keep? (boolean (:keep? v))]
                   :when cid]
               (if keep?
                 (assoc o :decision :keep :reason (:reason v) :batch-verdict v)
                 (let [rv (revert/execute-revert-chunk
                           {:inputs (cond-> {:chunk-id cid
                                             :collection-name enrichment-collection-name}
                                      prompt-hash (assoc :prompt-hash prompt-hash))
                            :parameters {:dry-run? dry-run?}
                            :skill-params skill-params})]
                   (assoc o :decision :revert :reason (:reason v) :batch-verdict v
                          :reverted-count (get-in rv [:outputs :reverted-count])
                          :dry-run? dry-run?)))))
        kept (vec (keep #(when (= :keep (:decision %)) (:chunk-id %)) merged))
        reverted (vec (keep #(when (= :revert (:decision %)) (:chunk-id %)) merged))]
    (skills/success-result
     {:outcomes merged
      :kept kept
      :reverted reverted
      :decisions (mapv #(select-keys % [:chunk-id :decision :reason :reverted-count]) merged)
      :summary (str (count kept) " kept, " (count reverted) " reverted"
                    (when dry-run? " (dry-run)"))}
     {})))

(def batch-decide-skill
  {:metadata batch-decide-metadata
   :execute execute-batch-decide})

(defn register! [] (skills/register-skill! batch-decide-skill))
(register!)

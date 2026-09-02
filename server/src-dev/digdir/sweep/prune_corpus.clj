(ns digdir.sweep.prune-corpus
  "Run the eval-gated prune (prune-chunk, LLM-confirmed) over a set of chunks
   against a PRIMARY CLONE — the apply step of the net-benefit test. Enrichment is
   passed nil (real enrichment untouched; the A/B runs enrichment-off anyway).
   Idempotent (apply-prune deletes by id), so re-running after an interruption is
   safe. Logs progress; writes a per-chunk prune summary."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [digdir.skills.enrichment.prune-chunk :as pc]
            [digdir.sweep.runner :as runner]))

(defn run [{:keys [chunk-ids-file primary-clone content-rank-threshold tenant out-file]
            :or {tenant "digdir" content-rank-threshold 0
                 out-file "results/prune-corpus-summary.edn"}}]
  (let [{:keys [collections]} (#'runner/resolve-dataset-config!
                               {:tenant tenant :dataset-config-key "default"})
        cc (:chunks-collection collections)
        dc (:docs-collection collections)
        cids (edn/read-string (slurp chunk-ids-file))
        total (count cids)]
    (println (format "prune-corpus: %d chunks -> clone %s (content-rank t=%d, LLM-confirmed)"
                     total primary-clone content-rank-threshold))
    (let [summary
          (loop [[cid & more] cids i 1 pruned 0 acc []]
            (if-not cid
              (do (println (format "DONE: %d chunks, %d phrases pruned" total pruned)) acc)
              (let [r (try
                        (:outputs (pc/execute-prune-chunk
                                   {:inputs {:chunk-id cid
                                             :phrases-collection-name primary-clone
                                             :enrichment-collection-name nil
                                             :chunks-collection cc :docs-collection dc}
                                    :parameters {:content-rank-threshold content-rank-threshold
                                                 :use-llm? true :dry-run? false}
                                    :skill-params {:tenant tenant}}))
                        (catch Throwable t {:pruned-count 0 :error (.getMessage t)}))
                    n (or (:pruned-count r) 0)]
                (when (or (zero? (mod i 25)) (pos? n) (:error r))
                  (println (format "[%d/%d] %s pruned=%d cum=%d%s"
                                   i total cid n (+ pruned n)
                                   (if (:error r) (str " ERR " (:error r)) ""))))
                (recur more (inc i) (+ pruned n)
                       (conj acc {:chunk-id cid :pruned n
                                  :phrases (mapv (fn [d] (select-keys d [:phrase :decision :reason]))
                                                 (:decisions r))})))))]
      (io/make-parents out-file)
      (spit out-file (pr-str summary))
      (println "wrote" out-file)
      {:total total :pruned (reduce + (map :pruned summary))})))

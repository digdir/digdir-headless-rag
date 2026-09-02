(ns digdir.skills.enrichment.prune-chunk
  "`:builtin/enrichment-prune-chunk` — the per-chunk PRUNE branch, composed.

   One step the unified self-improve graph can call per chunk, orchestrating the
   three validated prune skills + the benefit gate so the graph stays linear (no
   fragile nested foreach over candidates):

     enumerate a chunk's combined phrases (propose-prune)
       → CONTENT-RANK candidate generation, cheap/no-LLM, over ALL phrases
           └ per candidate → verify-prune (safety: not stranded)
                           → LLM confirm (verify-prune-benefit, the precision step)
       → apply-prune (delete approved, by id, on the CLONE collections)

   Candidate generation by content-rank (not IDF) is the recall-lifting fix: IDF
   (~9% flagged) under-generated; content-rank (~37%) reaches the ~58% recall ceiling.
   Approval = content-rank says crowding ∧ SAFE (not stranded) ∧ (LLM confirms |
   :use-llm? false). The LLM judges only the content-rank crowders — ~3× cheaper, no
   benchmark. Honors :dry-run? (gate, don't delete). propose-prune's IDF specificity
   is kept as a secondary signal on each phrase, not the candidate gate.

   Lives in `src-dev/` with its siblings; calls their execute fns directly."
  (:require [digdir.rag.skills.core :as skills]
            [digdir.skills.enrichment.propose-prune :as propose]
            [digdir.skills.enrichment.verify-prune :as safety]
            [digdir.skills.enrichment.verify-prune-benefit :as benefit]
            [digdir.skills.enrichment.apply-prune :as apply-p]))

(def prune-chunk-metadata
  {:skill-id :builtin/enrichment-prune-chunk
   :name "Prune a chunk's generic phrases"
   :description "Per-chunk prune branch: propose-prune (IDF) -> verify-prune (safety) -> verify-prune-benefit (content-rank + LLM) -> apply-prune. Operates on clone collections; honors :dry-run?."
   :category :augmentation
   :inputs [:chunk-id :phrases-collection-name :enrichment-collection-name
            :chunks-collection :docs-collection]
   :outputs [:chunk-id :pruned-count :decisions]
   :parameters {:specificity-threshold :number :content-rank-threshold :number
                :use-llm? :boolean :dry-run? :boolean}
   :required-services #{:typesense :azure-openai}
   :version "1.0.0"
   :tags #{:enrichment :self-improve :prune :graph}})

(defn execute-prune-chunk
  [{:keys [inputs parameters skill-params]}]
  (let [{:keys [chunk-id phrases-collection-name enrichment-collection-name
                chunks-collection docs-collection]} inputs
        {:keys [specificity-threshold content-rank-threshold use-llm? dry-run?]
         :or {use-llm? true}} parameters
        sp {:tenant (:tenant skill-params)}
        out (fn [r] (:outputs r))
        bench (fn [phrase llm?]
                (out (benefit/execute-verify-prune-benefit
                      {:inputs {:chunk-id chunk-id :candidate-phrase phrase
                                :phrases-collection-name phrases-collection-name
                                :chunks-collection chunks-collection}
                       :parameters {:content-rank-threshold content-rank-threshold :use-llm? llm?}
                       :skill-params sp})))
        ;; 1. enumerate ALL the chunk's phrases (propose-prune returns candidates ∪
        ;;    kept; its IDF specificity is carried as a secondary signal only).
        pp (out (propose/execute-propose-prune
                 {:inputs {:chunk-id chunk-id
                           :phrases-collection-name phrases-collection-name
                           :enrichment-collection-name enrichment-collection-name}
                  :parameters {:specificity-threshold specificity-threshold}
                  :skill-params sp}))
        all-phrases (vec (concat (:candidates pp) (:kept pp)))
        ;; 2. candidate generation by CONTENT-RANK (cheap, no LLM) over ALL phrases —
        ;;    the recall-lifting fix: IDF (~9%) under-generated; content-rank (~37%)
        ;;    reaches the ~58% recall ceiling.
        candidates (filterv #(:content-rank-remove? (bench (:phrase %) false)) all-phrases)
        ;; 3. per candidate: safety gate, then LLM confirm (the precision step).
        decisions
        (for [c candidates]
          (let [safe (out (safety/execute-verify-prune
                           {:inputs {:chunk-id chunk-id :candidate-phrase (:phrase c)
                                     :phrases-collection-name phrases-collection-name
                                     :chunks-collection chunks-collection
                                     :docs-collection docs-collection}
                            :parameters {} :skill-params sp}))]
            (if-not (:prune? safe)
              (assoc c :decision :keep :reason :stranded)
              (let [ben (when use-llm? (bench (:phrase c) true))
                    confirmed? (if use-llm? (:llm-remove? ben) true)]
                (assoc c :decision (if confirmed? :prune :keep)
                       :reason (if confirmed? :crowding-confirmed :llm-kept)
                       :llm-remove? (when ben (:llm-remove? ben)))))))
        approved (filterv #(= :prune (:decision %)) decisions)
        applied (out (apply-p/execute-apply-prune
                      {:inputs {:candidates approved
                                :primary-collection-name phrases-collection-name
                                :enrichment-collection-name enrichment-collection-name}
                       :parameters {:dry-run? (boolean dry-run?)}
                       :skill-params sp}))]
    (skills/success-result
     {:chunk-id chunk-id
      :pruned-count (:pruned-count applied)
      :decisions (vec decisions)}
     {:candidates (count candidates)
      :approved (count approved)
      :dry-run? (boolean dry-run?)
      :pruned-by-collection (:pruned-by-collection applied)})))

(def prune-chunk-skill
  {:metadata prune-chunk-metadata
   :execute execute-prune-chunk})

(defn register! [] (skills/register-skill! prune-chunk-skill))

(register!)

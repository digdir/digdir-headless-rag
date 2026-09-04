(ns digdir.sweep.add-corpus
  "ADD half of the self-improvement loop, as a corpus runner: for each chunk in a
   set, fetch context -> propose enrichment (verified phrases OR hypothetical
   questions) -> apply to a GROWN enrichment collection. Mirror of `prune_corpus.clj`.

   Oracle ADD tests: grow enrichment on the 43 benchmark GOLDEN chunks, then A/B
   enrichment-off vs on — the upper bound on whether that enrichment type lifts
   recall. Ungated (apply all proposed); the propose prompts are the discriminative
   D2.21 ones, and the A/B is the judge. Idempotent (apply deletes prior rows for a
   chunk-id before upsert). Parameterized by `:enrichment-type`."
  (:require [clojure.edn :as edn]
            [digdir.skills.enrichment.collections :as colls]
            [digdir.skills.enrichment.fetch-chunk-context :as fetch]
            [digdir.skills.enrichment.propose-phrases :as propose-p]
            [digdir.skills.enrichment.propose-questions :as propose-q]
            [digdir.skills.enrichment.apply-phrases :as apply-p]
            [digdir.skills.enrichment.apply-questions :as apply-q]
            [digdir.sweep.runner :as runner]))

(defn- propose+apply
  "Dispatch propose+apply by enrichment-type; returns applied-count."
  [enrichment-type sp coll cnt ctx cid]
  (let [out (fn [r] (:outputs r))
        base {:chunk-id cid :chunk-content (:chunk-content ctx)
              :doc-title (:doc-title ctx) :doc-url (:doc-url ctx)}]
    (case enrichment-type
      :verified-phrases
      (let [prop (out (propose-p/execute-propose-phrases
                       {:inputs base :parameters {:phrase-count cnt} :skill-params sp}))]
        (:applied-count (out (apply-p/execute-apply-phrases
                              {:inputs {:proposal (assoc prop :doc-num (:doc-num ctx))
                                        :collection-name coll} :parameters {} :skill-params sp}))))
      :hypothetical-questions
      (let [prop (out (propose-q/execute-propose-questions
                       {:inputs base :parameters {:question-count cnt} :skill-params sp}))]
        (:applied-count (out (apply-q/execute-apply-questions
                              {:inputs {:proposal (assoc prop :doc-num (:doc-num ctx))
                                        :collection-name coll} :parameters {} :skill-params sp})))))))

(defn run [{:keys [chunk-ids-file enrichment-collection enrichment-type n-items tenant create?]
            :or {tenant "digdir" enrichment-type :verified-phrases n-items 6 create? true}}]
  (let [{:keys [collections]} (#'runner/resolve-dataset-config!
                               {:tenant tenant :dataset-config-key "default"})
        cc (:chunks-collection collections)
        dc (:docs-collection collections)
        cids (edn/read-string (slurp chunk-ids-file))
        out (fn [r] (:outputs r))
        sp {:tenant tenant}
        total (count cids)]
    (when create?
      (println "ensure collection:" enrichment-collection
               (colls/ensure-collection-by-name! sp dc enrichment-collection enrichment-type)))
    (println (format "add-corpus: %d chunks -> %s (type %s, count %d)"
                     total enrichment-collection (name enrichment-type) n-items))
    (let [added
          (loop [[cid & more] cids i 1 added 0]
            (if-not cid
              (do (println (format "DONE: %d chunks, %d items added" total added)) added)
              (let [n (try
                        (let [ctx (out (fetch/execute-fetch-chunk-context
                                        {:inputs {:chunk-id cid :chunks-collection cc
                                                  :docs-collection dc} :skill-params sp}))]
                          (or (propose+apply enrichment-type sp enrichment-collection n-items ctx cid) 0))
                        (catch Throwable t (println "  ERR" cid (.getMessage t)) 0))]
                (when (or (zero? (mod i 10)) (pos? n))
                  (println (format "[%d/%d] %s added=%d cum=%d" i total cid n (+ added n))))
                (recur more (inc i) (+ added n)))))]
      {:total total :added added})))

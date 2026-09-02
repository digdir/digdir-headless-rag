(ns digdir.sweep.inspect-enrichment
  "Generate enrichment (hypothetical-questions or verified-phrases) for a set of
   chunks and WRITE the generated content to disk for human review — no Typesense
   collection is created or touched. The dump-to-disk step the bulk add_corpus
   runner should have had all along, so generated bridges are inspectable before
   shipping."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [digdir.skills.enrichment.fetch-chunk-context :as fetch]
            [digdir.skills.enrichment.propose-phrases :as propose-p]
            [digdir.skills.enrichment.propose-questions :as propose-q]
            [digdir.sweep.runner :as runner]))

(defn run [{:keys [chunk-ids-file enrichment-type n-items out-file tenant limit]
            :or {tenant "digdir" enrichment-type :hypothetical-questions n-items 5
                 out-file "results/enrichment-inspect.edn"}}]
  (let [{:keys [collections]} (#'runner/resolve-dataset-config!
                               {:tenant tenant :dataset-config-key "default"})
        cc (:chunks-collection collections) dc (:docs-collection collections)
        sp {:tenant tenant}
        out (fn [r] (:outputs r))
        cids (cond->> (edn/read-string (slurp chunk-ids-file)) limit (take limit))
        results
        (doall
         (for [cid cids]
           (let [ctx (out (fetch/execute-fetch-chunk-context
                           {:inputs {:chunk-id cid :chunks-collection cc :docs-collection dc}
                            :skill-params sp}))
                 base {:chunk-id cid :chunk-content (:chunk-content ctx)
                       :doc-title (:doc-title ctx) :doc-url (:doc-url ctx)}
                 prop (out (case enrichment-type
                             :hypothetical-questions
                             (propose-q/execute-propose-questions
                              {:inputs base :parameters {:question-count n-items} :skill-params sp})
                             :verified-phrases
                             (propose-p/execute-propose-phrases
                              {:inputs base :parameters {:phrase-count n-items} :skill-params sp})))
                 items (or (:questions prop) (:phrases prop))]
             {:chunk-id cid
              :doc-title (:doc-title ctx)
              :snippet (let [s (str (:chunk-content ctx))] (subs s 0 (min 240 (count s))))
              :items (vec items)})))]
    (io/make-parents out-file)
    (spit out-file (with-out-str (pp/pprint results)))
    (println (format "wrote %s (%d chunks, type %s)\n" out-file (count results) (name enrichment-type)))
    ;; readable sample
    (doseq [r (take 8 results)]
      (println (str "── " (:chunk-id r) "  «" (:doc-title r) "»"))
      (println (str "   chunk: " (:snippet r) "…"))
      (doseq [it (:items r)] (println (str "   • " it)))
      (println))
    {:count (count results) :out-file out-file}))

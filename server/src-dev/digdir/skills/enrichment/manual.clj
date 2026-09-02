(ns digdir.skills.enrichment.manual
  "Reusable CLI for manual enrichment apply/revert (offline tooling).

   The enrichment skills live in src-dev (not on the production classpath),
   so they can't be driven from a production debug endpoint. This namespace
   gives a `-main` that reads an EDN spec file and applies or reverts
   enrichments, invoked from the `bb enrich-apply` / `bb enrich-revert` tasks
   (which shell out to `clojure -M:dev -m digdir.skills.enrichment.manual`).

   Apply spec EDN:
     {:tenant \"digdir\"
      :dataset-config-key \"public-docs\"
      :enrichment-type :hypothetical-questions | :verified-phrases | :fact-assertions
      :prompt-hash \"some-batch-tag\"            ;; for selective revert
      :proposals [{:chunk-id \"...\" :doc-num \"...\"
                   :items [\"question/phrase string\" ...]}]}   ;; facts: items = [{:subject :predicate :object} ...]

   Revert spec EDN:
     {:tenant \"digdir\" :dataset-config-key \"public-docs\"
      :enrichment-type :hypothetical-questions
      :chunk-ids [\"...\"] :prompt-hash \"some-batch-tag\"}      ;; prompt-hash optional"
  (:require [clojure.edn :as edn]
            [digdir.api.context :as api-ctx]
            [digdir.skills.enrichment.collections :as enrich-coll]
            [digdir.skills.enrichment.apply-questions]
            [digdir.skills.enrichment.apply-phrases]
            [digdir.skills.enrichment.apply-facts]
            [digdir.skills.enrichment.revert-chunk]
            [digdir.rag.skills.core :as skills]
            [digdir.rag.typesense :as ts-utils]))

(def ^:private type->apply
  {:hypothetical-questions {:skill :builtin/enrichment-apply-questions :items-key :questions}
   :verified-phrases       {:skill :builtin/enrichment-apply-phrases   :items-key :phrases}
   :fact-assertions        {:skill :builtin/enrichment-apply-facts     :items-key :facts}})

(defn- resolve-ctx [{:keys [tenant dataset-config-key enrichment-type]}]
  (let [{:keys [config dataset-config]}
        (api-ctx/resolve-dataset-context-by-ref! {:tenant tenant
                                                   :dataset-config-key dataset-config-key})
        pcfg (or config dataset-config)]
    {:pcfg pcfg
     :coll (enrich-coll/enrichment-collection-name pcfg enrichment-type)
     :settings (ts-utils/make-ts-settings {:tenant tenant})}))

(defn apply!
  "Apply enrichments from a spec map. Returns the apply skill outputs."
  [{:keys [tenant enrichment-type prompt-hash proposals] :as spec}]
  (let [{:keys [pcfg coll settings]} (resolve-ctx spec)
        {:keys [skill items-key]} (type->apply enrichment-type)
        _ (when-not skill (throw (ex-info "Unknown enrichment-type" {:enrichment-type enrichment-type})))
        prov {:model "manual-cli" :prompt-hash prompt-hash :generated-at-ms 0}
        prepared (mapv (fn [{:keys [chunk-id doc-num items]}]
                         {:chunk-id chunk-id :doc-num doc-num
                          items-key (vec items) :provenance prov})
                       proposals)]
    (enrich-coll/ensure-collection! pcfg enrichment-type)
    (let [r (skills/execute-skill skill
                                  {:inputs {:proposals prepared :collection-name coll}
                                   :parameters {:dry-run? false}
                                   :services {:typesense settings}
                                   :skill-params {:tenant tenant}})]
      (if (skills/result-success? r)
        (assoc (skills/get-result-outputs r) :collection coll)
        (throw (ex-info "apply failed" {:error (:error r)}))))))

(defn revert!
  "Revert enrichments for the given chunk-ids (optionally scoped to prompt-hash)."
  [{:keys [tenant _enrichment-type chunk-ids prompt-hash] :as spec}]
  (let [{:keys [coll settings]} (resolve-ctx spec)]
    (mapv (fn [chunk-id]
            (let [r (skills/execute-skill :builtin/enrichment-revert-chunk
                                          (cond-> {:inputs {:chunk-id chunk-id :collection-name coll}
                                                   :parameters {:dry-run? false}
                                                   :services {:typesense settings}
                                                   :skill-params {:tenant tenant}}
                                            prompt-hash (assoc-in [:inputs :prompt-hash] prompt-hash)))]
              {:chunk-id chunk-id
               :reverted (get-in (skills/get-result-outputs r) [:reverted-count])}))
          chunk-ids)))

(defn -main
  "Usage: clojure -M:dev -m digdir.skills.enrichment.manual <apply|revert> <spec.edn>"
  [& args]
  (let [[op spec-path] args
        spec (edn/read-string (slurp spec-path))]
    (case op
      "apply"  (println :applied (apply! spec))
      "revert" (println :reverted (revert! spec))
      (do (println "Usage: <apply|revert> <spec.edn>") (System/exit 1)))
    (flush)
    (System/exit 0)))

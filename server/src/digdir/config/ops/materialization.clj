(ns digdir.config.ops.materialization
  (:require [digdir.config.db :as config-db]
            [digdir.pipeline.materialization :as materialization]))

(defn- pipeline-node-values
  [property-values]
  (reduce-kv
   (fn [acc property value]
     (if-let [path (config-db/pipeline-property-path property)]
       (assoc acc path value)
       acc))
   {}
   property-values))

(defn- target-materialization-node-values
  [pipeline-config]
  (pipeline-node-values
   (materialization/drifted-target-contract-properties pipeline-config)))

(defn seed-pipeline-materialization-defaults!
  "Reconcile a resolved pipeline to the explicit Dataset-root materialization contract.

   Writes contract values onto the shared dataset base when the effective
   Dataset-root value is missing or drifted."
  [conn {:keys [tenant tenant-config-key pipeline-id master-key]}]
  (let [pipeline-config (or (config-db/get-dataset @conn tenant tenant-config-key pipeline-id master-key)
                            (throw (ex-info "Pipeline config not found"
                                            {:tenant tenant
                                             :tenant-config-key tenant-config-key
                                             :pipeline-id pipeline-id})))
        effective-pipeline-config (assoc pipeline-config
                                         :tenant tenant
                                         :tenant-config-key tenant-config-key
                                         :pipeline-id pipeline-id)
        target-contract (or (materialization/target-materialization-contract effective-pipeline-config)
                            (materialization/target-materialization-contract
                             {:tenant tenant
                              :dataset-id (:dataset-id pipeline-config)}))
        _ (when-not target-contract
            (throw (ex-info "No explicit materialization contract defined for pipeline"
                            {:tenant tenant
                             :tenant-config-key tenant-config-key
                             :pipeline-id pipeline-id
                             :dataset-id (:dataset-id pipeline-config)})))
        node-values (target-materialization-node-values effective-pipeline-config)
        node-id (or (when-let [dataset-id (:dataset-id pipeline-config)]
                      (config-db/dataset-base-node-id tenant dataset-id))
                    (:dataset-node-id pipeline-config))
        actions (into {}
                      (map (fn [[path value]]
                             [path (config-db/set-node-value! conn
                                                              {:root :dataset
                                                               :tenant tenant
                                                               :node-id node-id
                                                               :path path
                                                               :value value
                                                               :master-key master-key})]))
                      node-values)]
    {:tenant tenant
     :tenant-config-key tenant-config-key
     :pipeline-id pipeline-id
     :dataset-id (:dataset-id pipeline-config)
     :node-id node-id
     :seed-source :target-contract
     :seeded-paths (-> actions keys sort vec)
     :actions actions}))

(defn seed-target-materialization-defaults!
  "Seed missing Dataset-root materialization defaults for the deployment target tenants."
  [conn {:keys [master-key tenant-config-key]
         :or {tenant-config-key "default"}}]
  {:digdir (mapv (fn [pipeline-id]
                   (seed-pipeline-materialization-defaults! conn
                                                            {:tenant "digdir"
                                                             :tenant-config-key tenant-config-key
                                                             :pipeline-id pipeline-id
                                                             :master-key master-key}))
                 ["altinn-docs" "digdir-docs"])
   :public-sector-knowledge (mapv (fn [pipeline-id]
                                    (seed-pipeline-materialization-defaults! conn
                                                                             {:tenant "public-sector-knowledge"
                                                                              :tenant-config-key tenant-config-key
                                                                              :pipeline-id pipeline-id
                                                                              :master-key master-key}))
                                  ["kudos"])})

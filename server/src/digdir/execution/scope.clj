(ns digdir.execution.scope
  "Lightweight shared helpers for canonical dataset and execution scope."
  (:require [clojure.string :as str]
            [digdir.config.accessor :as cfg]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]))

(defn normalize-dataset-ref
  [dataset-ref]
  (when (map? dataset-ref)
    (let [tenant (or (:tenant dataset-ref) (get dataset-ref "tenant"))
          dataset-config-key (or (:dataset-config-key dataset-ref)
                                 (get dataset-ref "dataset-config-key")
                                 (get dataset-ref "dataset_config_key")
                                 (:tenant-config-key dataset-ref)
                                 (get dataset-ref "tenant-config-key")
                                 (get dataset-ref "tenant_config_key")
                                 (:config-key dataset-ref)
                                 (get dataset-ref "config-key")
                                 (get dataset-ref "config_key"))]
      (when (every? #(and (string? %) (not (str/blank? %)))
                    [tenant dataset-config-key])
        {:tenant tenant
         :dataset-config-key dataset-config-key}))))

(defn dataset-ref-key
  [{:keys [tenant dataset-config-key]}]
  [tenant dataset-config-key])

(defn distinct-dataset-refs
  [dataset-refs]
  (let [seen (volatile! #{})]
    (reduce (fn [acc dataset-ref]
              (let [ref-key (dataset-ref-key dataset-ref)]
                (if (contains? @seen ref-key)
                  acc
                  (do
                    (vswap! seen conj ref-key)
                    (conj acc dataset-ref)))))
            []
            dataset-refs)))

(defn assoc-execution-scope
  "Attach canonical tenant/dataset/agent scope to execution opts and skill params."
  [opts {:keys [tenant dataset-config-key dataset-ref agent-id entity]}]
  (let [skill-params (cond-> (or (:skill-params opts) {})
                       tenant (assoc :tenant tenant)
                       dataset-config-key (assoc :dataset-config-key dataset-config-key)
                       entity (assoc :entity entity)
                       dataset-ref (assoc :dataset-ref dataset-ref)
                       agent-id (assoc :agent-id agent-id))]
    (cond-> (assoc opts
                   :tenant tenant
                   :dataset-config-key dataset-config-key
                   :skill-params skill-params)
      dataset-ref (assoc :dataset-ref dataset-ref)
      agent-id (assoc :agent-id agent-id))))

(defn resolve-dataset-context-by-ref!
  [dataset-ref]
  (when-let [dataset-ref (normalize-dataset-ref dataset-ref)]
    (let [conn (config-db/get-conn)]
      (when-not conn
        (throw (ex-info "Config DB connection is not available"
                        {:status 500
                         :dataset-ref dataset-ref})))
      (let [master-key (config-core/get-master-key)
            dataset-config (config-db/get-dataset-by-ref @conn dataset-ref master-key)
            _ (when-not dataset-config
                (throw (ex-info "Dataset not found"
                                {:status 404
                                 :dataset-ref dataset-ref})))
            canonical-dataset-ref {:tenant (:tenant dataset-config)
                                   :dataset-config-key (:dataset-config-key dataset-config)}
            dataset-trace-result (try
                                   (cfg/load-dataset-config-v2-with-trace
                                    {:tenant (:tenant dataset-config)
                                     :dataset-config-key (:dataset-config-key dataset-config)
                                     :dataset-id (:dataset-id dataset-config)
                                     :paths config-db/dataset-runtime-property-paths})
                                   (catch Exception _
                                     nil))]
        {:dataset-ref canonical-dataset-ref
         :tenant (:tenant dataset-ref)
         :dataset-config-key (:dataset-config-key dataset-config)
         :dataset-id (:dataset-id dataset-config)
         :dataset-node-id (:dataset-node-id dataset-config)
         :dataset-config dataset-config
         :config dataset-config
         :dataset-inputs {:docs-collection (:docs-collection dataset-config)
                          :chunks-collection (:chunks-collection dataset-config)
                          :phrases-collection (:phrases-collection dataset-config)}
         :dataset-node (:node dataset-trace-result)
         :traces (:traces dataset-trace-result)}))))

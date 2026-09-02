(ns digdir.import-export.entities.config
  "Config import/export helpers."
  (:require [digdir.config.db :as config-db]
            [digdir.config.ops.sync :as config-sync]
            [digdir.import-export.model :as model]))

(defn export-config-data
  [config-conn {:keys [master-key export-password include-audit?]
                :or {include-audit? true}}]
  (let [config-export (config-sync/export-full config-conn {:master-key master-key
                                                           :export-password export-password
                                                           :include-audit? include-audit?})]
    {:definitions (vec (get-in config-export [:data :definitions]))
     :nodes (vec (get-in config-export [:data :nodes]))
     :bindings (vec (get-in config-export [:data :bindings]))
     :compatibilities (vec (get-in config-export [:data :compatibilities]))
     :datasets (vec (get-in config-export [:data :datasets]))
     :dataset-pipelines (vec (get-in config-export [:data :dataset-pipelines]))
     :node-values (vec (get-in config-export [:data :node-values]))
     :audit (vec (or (get-in config-export [:data :audit]) []))}))

(defn import-config-data!
  [config-conn normalized {:keys [master-key export-password on-conflict]}]
  (config-db/init-config-db! config-conn)
  (config-sync/import-data config-conn
                           (model/config-import-envelope normalized)
                           {:master-key master-key
                            :export-password export-password
                            :on-conflict on-conflict}))

(defn preview-import-config-data
  [config-conn normalized {:keys [master-key export-password on-conflict]}]
  (config-sync/import-data config-conn
                           (model/config-import-envelope normalized)
                           {:master-key master-key
                            :export-password export-password
                            :on-conflict on-conflict
                            :dry-run? true}))

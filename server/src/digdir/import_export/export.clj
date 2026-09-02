(ns digdir.import-export.export
  "System export coordination."
  (:require [digdir.import-export.canonical.system :as canonical-system]
            [digdir.import-export.files :as files]
            [digdir.import-export.model :as model]
            [digdir.import-export.registry :as registry]
            [digdir.import-export.report :as report]))

(defn- export-system-data
  [config-conn main-conn opts]
  (reduce (fn [acc {:keys [export-fn]}]
            (merge acc (export-fn config-conn main-conn opts)))
          {}
          registry/ordered-system-entities))

(defn export-system
  "Export the full config DB and main DB state needed for the migration cutover."
  [config-conn main-conn opts]
  (let [data (-> (model/system-envelope (export-system-data config-conn main-conn opts))
                 canonical-system/canonicalize-system-export
                 model/assert-system-envelope!)]
    (assoc data :report (report/build-export-report data))))

(defn export-to-file
  [config-conn main-conn file-path opts]
  (let [data (export-system config-conn main-conn opts)]
    (files/write-json-file! file-path data)
    {:file-path file-path
     :report (:report data)}))

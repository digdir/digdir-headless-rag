(ns digdir.import-export.report
  "Shared import/export reporting helpers."
  (:require [digdir.import-export.model :as model]))

(defn build-export-report
  [data]
  (reduce (fn [acc k]
            (assoc acc k (count (get-in data [:data k]))))
          {}
          model/system-data-keys))

(defn preview-existing-items
  [items exists? on-conflict]
  (reduce (fn [acc item]
            (if (exists? item)
              (update acc (if (= on-conflict :overwrite) :would-overwrite :would-skip) inc)
              (update acc :would-create inc)))
          {:would-create 0
           :would-overwrite 0
           :would-skip 0
           :total (count items)}
          items))

(defn build-system-import-result
  [mode on-conflict entity-results]
  (letfn [(sum-key [data k]
            (reduce (fn [acc node]
                      (+ acc (or (when (and (map? node) (contains? node k))
                                   (get node k))
                                 0)))
                    0
                    (tree-seq coll? seq data)))
          (summary-keys []
            (case mode
              :preview [:would-create :would-overwrite :would-skip :total :count :would-import]
              :apply [:created :updated :overwritten :skipped :imported]
              []))]
    (let [summary-keys (summary-keys)
          summary (reduce (fn [acc k]
                            (assoc acc k (sum-key entity-results k)))
                          {}
                          summary-keys)]
      {:mode mode
       :on-conflict on-conflict
       :summary summary
       :entities entity-results})))

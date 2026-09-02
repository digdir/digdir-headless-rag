(ns digdir.import-export.entities.folders
  "Folder import/export helpers."
  (:require [datahike.api :as d]
            [digdir.import-export.report :as report]))

(defn export-folders
  [db]
  (->> (d/q '[:find [?folder-id ...]
              :where
              [?e :folder/id ?folder-id]]
            db)
       sort
       (mapv (fn [folder-id]
               (d/pull db [:folder/id :folder/name :folder/created] [:folder/id folder-id])))))

(defn- folder-eid
  [db folder-id]
  (d/q '[:find ?e .
         :in $ ?folder-id
         :where
         [?e :folder/id ?folder-id]]
       db folder-id))

(defn import-folders!
  [conn folders on-conflict]
  (reduce (fn [acc folder]
            (let [folder-id (:folder/id folder)
                  exists? (some? (folder-eid @conn folder-id))]
              (cond
                (and exists? (= on-conflict :skip))
                (update acc :skipped inc)

                :else
                (do
                  (d/transact conn {:tx-data [{:folder/id folder-id
                                              :folder/name (:folder/name folder)
                                              :folder/created (:folder/created folder)}]})
                  (update acc (if exists? :overwritten :created) inc)))))
          {:created 0 :skipped 0 :overwritten 0}
          folders))

(defn preview-import-folders
  [db folders on-conflict]
  (report/preview-existing-items folders
                                 #(some? (folder-eid db (:folder/id %)))
                                 on-conflict))

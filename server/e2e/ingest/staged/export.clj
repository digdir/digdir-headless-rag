;; The REAL default backup, as `bb migration-export` takes it: the system
;; export, WITH audit, through the product's own connections. Run with the
;; server stopped. Writes /exchange/backup.json and /exchange/exported.edn.
(require '[digdir.config.core :as config-core]
         '[digdir.config.db :as config-db]
         '[digdir.data.db :as db]
         '[digdir.import-export.system :as migration])

(let [result (migration/export-to-file (config-db/get-conn) (db/get-conn) "/exchange/backup.json"
                                       {:master-key (config-core/get-master-key) :include-audit? true})]
  (spit "/exchange/exported.edn" (pr-str {:file-path (:file-path result) :report (:report result)}))
  (System/exit 0))

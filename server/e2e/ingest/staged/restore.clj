;; Restore the default backup into a FRESH INSTALL, as `bb migration-import`
;; does (on-conflict :skip), through the product's own connections. Then judge
;; the audit trail with `digdir.config.round-trip`, mounted
;; from the test tree at /round-trip: every row present, no field lost, and a
;; reference exactly when THIS store defines the row's path. Run with the
;; server stopped. Writes /exchange/restored.edn.
(require '[clojure.data.json :as json]
         '[digdir.config.core :as config-core]
         '[digdir.config.db :as config-db]
         '[digdir.data.db :as db]
         '[digdir.import-export.system :as migration])
(load-file "/round-trip/round_trip.clj")

(let [result (migration/import-from-file (config-db/get-conn) (db/get-conn) "/exchange/backup.json"
                                         {:master-key (config-core/get-master-key) :on-conflict :skip})
      ;; String keys, as the export writes each row: the shape round-trip reads.
      raw (json/read-str (slurp "/exchange/backup.json"))
      backup {:data {:audit (get-in raw ["data" "audit"])
                     :definitions (get-in raw ["data" "definitions"])}}
      restored @(config-db/get-conn)]
  (spit "/exchange/restored.edn"
        (pr-str {:import-summary (:summary result)
                 ;; The config phase's own audit report: :without-definition
                 ;; counts the rows that arrived naming a path THIS store does
                 ;; not define.
                 :import-audit (get-in result [:entities :config :audit])
                 :backup-audit-rows (count (get-in backup [:data :audit]))
                 :infidelities ((resolve 'digdir.config.round-trip/audit-infidelities) backup restored)}))
  (System/exit 0))

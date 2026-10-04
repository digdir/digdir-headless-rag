;; What the store holds NOW, read directly (never through `get-conn`, which
;; would run boot on it). Run with the server stopped:
;;
;;   java -cp app.jar clojure.main /staged/inspect.clj <audit-ids-edn> <out-file>
;;
;; For every staged path: is it still defined, and how many values reference
;; it; for every staged audit row: is it there, what path it names, and what it
;; REFERENCES. Plus the migration markers. Writes one EDN map to
;; /exchange/<out-file> (a file: Datahike's writer thread logs to stdout).
(require '[clojure.edn :as edn]
         '[datahike.api :as d]
         '[digdir.config.core :as config-core]
         '[digdir.config.db :as config-db])

(let [audit-ids (edn/read-string (first *command-line-args*))
      bootstrap (config-core/load-bootstrap-config)
      cfg (get bootstrap (:db-env bootstrap))
      conn (d/connect cfg)
      db @conn
      values-at (fn [path]
                  (count (d/q '[:find [?v ...] :in $ ?p
                                :where [?def :config-def/path ?p] [?v :config.value/definition ?def]]
                              db path)))
      row (fn [id]
            (when-let [e (d/q '[:find ?e . :in $ ?id :where [?e :audit/id ?id]] db id)]
              (d/pull db [:audit/config-path {:audit/config-def [:config-def/path]}] e)))
      report {:migration-markers (vec (sort (d/q '[:find [?id ...] :where [_ :digdir.migration/id ?id]] db)))
              :paths (into (sorted-map)
                           (for [p (keys audit-ids)]
                             [p {:defined? (some? (config-db/get-definition db p))
                                 :values (values-at p)}]))
              :audit (into (sorted-map)
                           (for [[p id] audit-ids
                                 :let [r (row id)]]
                             [p {:present? (some? r)
                                 :config-path (:audit/config-path r)
                                 :references (get-in r [:audit/config-def :config-def/path])}]))}]
  (d/release conn)
  (spit (str "/exchange/" (second *command-line-args*)) (pr-str report))
  (System/exit 0))

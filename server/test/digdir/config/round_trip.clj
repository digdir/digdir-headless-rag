(ns digdir.config.round-trip
  "Export -> import round trips of a config store, and what a faithful one
   must keep. Built for the retracted-definition backup issue, and meant to be driven again by the staged-store
   test: any store, however it came by its history, must survive its
   own default backup.

   The default backup is what `bb migration-export` writes: `export-full`
   WITH audit. Dumps exclude audit, so they never exercised this path."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [digdir.config.db :as config-db]
            [digdir.config.ops.sync :as ops-sync]
            [digdir.config.schema :as schema]))

(defn fresh-config-conn
  "An empty config store: the config schema and nothing else, so no path is
   defined until an import brings its definition."
  []
  (let [cfg {:store {:backend :mem :id (str "round-trip-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data schema/config-migration-schema})
      conn)))

(defn release!
  [conn]
  (let [cfg (:config @(:wrapped-atom conn))]
    (d/release conn)
    (d/delete-database cfg)))

(defn default-backup
  "What `bb migration-export` writes by default: a full export WITH audit."
  [conn]
  (ops-sync/export-full conn {:include-audit? true}))

(defn restore!
  "Import `backup` into `conn` as a restore does. Throws what it throws."
  [conn backup]
  (ops-sync/import-data conn backup {:on-conflict :skip}))

(defn backup-audit-rows
  "The backup's audit rows as {id config-path}; config-path is nil for rows
   that name no path (API-key events)."
  [backup]
  (into {}
        (map (fn [row] [(get row "audit/id") (get row "audit/config-path")]))
        (get-in backup [:data :audit])))

(defn- audit-row
  "`db`'s audit row `id` with its config path and referenced path, or nil when
   `db` holds no such row (a lookup ref would throw instead)."
  [db id]
  (when-let [e (d/q '[:find ?e . :in $ ?id :where [?e :audit/id ?id]] db id)]
    (d/pull db [:audit/id :audit/config-path {:audit/config-def [:config-def/path]}] e)))

(defn audit-reference
  "The config path `db`'s audit row `id` REFERENCES, or nil when it carries no
   reference (or `db` holds no such row)."
  [db id]
  (get-in (audit-row db id) [:audit/config-def :config-def/path]))

(defn exported-audit-rows
  "`db`'s audit rows exactly as the export writes them, keyed by id: the same
   serializer the backup went through, so a field compares like with like."
  [db]
  (into {} (map (juxt #(get % "audit/id") identity)) (#'ops-sync/export-audit db nil)))

(defn- field-differences
  "The fields on which two exported audit rows differ, sorted."
  [a b]
  (sort (filter #(not= (get a %) (get b %)) (distinct (concat (keys a) (keys b))))))

(defn audit-infidelities
  "Every way `db`'s audit rows fail to be a faithful copy of `backup`'s:
   - a row is missing, or its config path differs;
   - a FIELD is lost or changed: the restored row, re-exported, must equal the
     backup's row (the references aside, which the export never carries);
   - the reference does not follow the TARGET: it must be present exactly when
     `db` defines the row's path. A row naming a path `db` defines but carrying
     no reference is a dropped reference, the regression an atomic import
     could introduce silently.
   Empty means faithful. The field comparison uses the export on both sides,
   so it sees what an IMPORT loses, not what the export itself never writes."
  [backup db]
  (let [exported (exported-audit-rows db)
        backup-rows (into {} (map (juxt #(get % "audit/id") identity)) (get-in backup [:data :audit]))]
    (vec (for [[id path] (sort (backup-audit-rows backup))
             :let [row (audit-row db id)
                   referenced (get-in row [:audit/config-def :config-def/path])
                   defined? (and path (some? (config-db/get-definition db path)))
                   lost (when (:audit/id row) (field-differences (backup-rows id) (exported id)))]
             problem [(when-not (:audit/id row)
                        (str id ": missing after the restore"))
                      (when (and (:audit/id row) (not= path (:audit/config-path row)))
                        (str id ": config-path " (pr-str (:audit/config-path row)) ", backup has " (pr-str path)))
                      (when (and (:audit/id row) defined? (not= path referenced))
                        (str id ": " path " is defined here, but the row "
                             (if referenced (str "references " referenced) "carries NO reference")))
                      (when (and (:audit/id row) (not defined?) referenced)
                        (str id ": references " referenced " although " (pr-str path) " is not defined here"))
                      (when (seq lost)
                        (str id ": fields lost or changed by the restore: " (str/join ", " lost)))]
             :when problem]
         problem))))

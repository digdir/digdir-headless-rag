(ns digdir.config.audit-round-trip-test
  "a store that ran a definition-retraction migration must survive its
   own default backup (`export-full` WITH audit), into a fresh store AND back
   into itself, and every audit reference must round-trip faithfully: present
   exactly when the target defines the row's path.

   The retraction migrations keep audit rows on purpose and drop only their
   `:audit/config-def` reference (`config-path` is the denormalized history).
   A restore that re-creates the reference unconditionally looks up a
   definition the backup does not carry, and the audit phase, which is LAST,
   throws after everything else has committed.

   THE SAME-BACKUP ARM IS MANDATORY. \"Will the definition exist?\" must
   be asked of the database the audit rows land in: today @conn after the
   definitions phase, under an atomic import the speculated db. Asked
   of the wrong value, the predicate drops the reference of every definition
   that arrives in the same backup, silently. Only that arm sees it."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [digdir.config.audit :as audit]
            [digdir.config.db :as config-db]
            [digdir.config.ops.bootstrap :as ops-bootstrap]
            [digdir.config.round-trip :as rt]))

(def ^:private retracted-path
  "One of config.db's env-migrated paths, so the REAL migration retracts it."
  "services.auth.use-db")

(def ^:private arriving-path
  "A path no catalogue defines: a target holds it only if the backup brings it."
  "services.round-trip.arrives-with-the-backup")

(defn- define! [conn path value-type]
  (config-db/upsert-definition! conn {:path path
                                      :root :platform
                                      :value-type value-type
                                      :encrypted? false
                                      :category :services
                                      :service :other
                                      :sensitivity :internal
                                      :function :settings}))

(defn- audited-edit!
  "An audit row as the real global-edit path writes it: a config path, and a
   reference to its definition. Returns the audit id."
  [conn path value]
  (audit/log-global-change! conn {:action :global-edit
                                  :path path
                                  :tenant "__global__"
                                  :global-version 1
                                  :changelog "round-trip fixture"
                                  :new-value value
                                  :user-email "fixture@example.test"}))

(defn- retracting-deployment!
  "A deployment that defined `retracted-path`, held a value for it, audited an
   edit of it, then ran the REAL `remove-env-migrated-paths!`. Returns the
   audit id and the migration's report."
  [conn]
  (define! conn retracted-path :boolean)
  (ops-bootstrap/bootstrap-config-tree! conn {:root :platform
                                              :tenant "rt-tenant"
                                              :base-values {retracted-path true}})
  (let [id (audited-edit! conn retracted-path true)]
    {:id id
     :migration (#'config-db/remove-env-migrated-paths! conn)}))

(defn- restore
  "{:result r} when `backup` restores into `conn`, else {:error what-it-threw}:
   a refusal reads as a named failure, not a bare error."
  [conn backup]
  (try {:result (rt/restore! conn backup)}
       (catch Exception e
         {:error (str (.getMessage e) " " (pr-str (ex-data e)))})))

(defn- backup-defines? [backup path]
  (boolean (some #(= path (get % "config-def/path")) (get-in backup [:data :definitions]))))

(deftest a-retracted-definition-no-longer-breaks-the-default-backup
  (let [src (rt/fresh-config-conn)]
    (try
      (let [{:keys [id migration]} (retracting-deployment! src)
            backup (rt/default-backup src)]
        (testing "PREMISE: the fixture really produced the shape that broke restores"
          (is (= {:definitions-removed 1 :values-removed 1} migration))
          (is (nil? (config-db/get-definition @src retracted-path))
              "the migration left the definition in place")
          (is (= retracted-path (get (rt/backup-audit-rows backup) id))
              "the backup does not carry the audit row that names the retracted path")
          (is (not (backup-defines? backup retracted-path))
              "the backup carries the retracted definition"))
        (let [tgt (rt/fresh-config-conn)]
          (try
            (testing "it restores into a fresh store"
              (let [{:keys [error result]} (restore tgt backup)]
                (is (nil? error))
                (is (= 1 (get-in result [:audit :without-definition]))
                    "the restore reports the one row that arrived without a definition"))
              (is (empty? (rt/audit-infidelities backup @tgt)))
              (is (nil? (rt/audit-reference @tgt id))
                  "the row must carry no reference, exactly as in its source"))
            (finally
              (rt/release! tgt))))
        (testing "it restores back into its own source"
          (is (nil? (:error (restore src backup))))
          (is (empty? (rt/audit-infidelities backup @src)))))
      (finally
        (rt/release! src)))))

(deftest a-definition-arriving-in-the-same-backup-keeps-its-audit-reference
  (let [src (rt/fresh-config-conn)
        tgt (rt/fresh-config-conn)]
    (try
      (define! src arriving-path :string)
      (ops-bootstrap/bootstrap-config-tree! src {:root :platform
                                                 :tenant "rt-tenant"
                                                 :base-values {arriving-path "v"}})
      (let [id (audited-edit! src arriving-path "v")
            backup (rt/default-backup src)]
        (testing "PREMISE: a fresh target, which defines the path only once the backup brings it"
          (is (nil? (config-db/get-definition @tgt arriving-path)))
          (is (backup-defines? backup arriving-path)))
        (let [{:keys [error result]} (restore tgt backup)]
          (is (nil? error))
          (is (= 0 (get-in result [:audit :without-definition]))))
        (testing "MANDATORY: a definition from the SAME backup keeps its reference"
          (is (= arriving-path (rt/audit-reference @tgt id))
              (str "the reference was dropped although the backup itself brought the definition: "
                   "\"will the definition exist?\" was asked of a database from BEFORE the "
                   "definitions phase")))
        (is (empty? (rt/audit-infidelities backup @tgt))))
      (finally
        (rt/release! src)
        (rt/release! tgt)))))

(deftest the-reference-follows-the-target-not-the-backup
  (let [src (rt/fresh-config-conn)
        tgt (rt/fresh-config-conn)]
    (try
      (let [{:keys [id]} (retracting-deployment! src)
            backup (rt/default-backup src)]
        (define! tgt retracted-path :boolean)
        (testing "PREMISE: the backup lacks the definition; the target already holds it"
          (is (not (backup-defines? backup retracted-path)))
          (is (some? (config-db/get-definition @tgt retracted-path))))
        (is (nil? (:error (restore tgt backup))))
        (testing "the target defines the path, so the row references the target's definition"
          (is (= retracted-path (rt/audit-reference @tgt id))))
        (is (empty? (rt/audit-infidelities backup @tgt))))
      (finally
        (rt/release! src)
        (rt/release! tgt)))))

(def ^:private fields-a-restore-dropped
  "Per audit row type, the fields `import-audit!`'s hand-kept whitelist dropped
   although the export carries them. The first two row types are written
   by production code today; `change-tx-data` has no production caller, but its
   fields are schema attributes and must survive a restore too.

   `:undeclared` is the NEXT attribute: one a producer writes that no schema
   declares yet (stores run `:schema-flexibility :read`). Any LIST of what to
   import, hand-kept or derived from the schema, drops it the same way; that
   list is the mechanism of the dropped audit-fields issue, so the restore must keep every exported
   `:audit/*` except the references."
  {:api-key     ["audit/api-key-id" "audit/api-key-name" "audit/api-key-pipeline-id"]
   :global-edit ["audit/changelog" "audit/global-version"]
   :change      ["audit/client" "audit/skill-graph" "audit/pipeline"]
   :undeclared  ["audit/not-yet-declared"]})

(deftest every-exported-audit-field-survives-the-restore
  (let [src (rt/fresh-config-conn)
        tgt (rt/fresh-config-conn)]
    (try
      (define! src arriving-path :string)
      (let [change (audit/change-tx-data {:path arriving-path
                                          :action :update
                                          :new-value "v"
                                          :client "a-client"
                                          :skill-graph "a-skill-graph"
                                          :pipeline "a-pipeline"
                                          :user-email "fixture@example.test"})
            undeclared {:audit/id "undeclared-attribute-row"
                        :audit/timestamp 1
                        :audit/action :global-edit
                        :audit/not-yet-declared "the next attribute"}
            _ (d/transact src {:tx-data [change undeclared]})
            ids {:undeclared (:audit/id undeclared)
                 :api-key (audit/log-api-key-change! src {:action :created
                                                          :api-key-id "key-1"
                                                          :api-key-name "round-trip key"
                                                          :pipeline-id "pipeline-1"
                                                          :user-email "fixture@example.test"})
                 :global-edit (audited-edit! src arriving-path "v")
                 :change (:audit/id change)}
            backup (rt/default-backup src)
            backup-row (fn [id] (some #(when (= id (get % "audit/id")) %) (get-in backup [:data :audit])))]
        (testing "PREMISE: the backup carries every field a restore used to drop"
          (doseq [[kind fields] fields-a-restore-dropped
                  field fields]
            (is (some? (get (backup-row (ids kind)) field))
                (str "the export no longer writes " field " on " (name kind) " rows"))))
        (is (nil? (:error (restore tgt backup))))
        (let [restored (rt/exported-audit-rows @tgt)]
          (doseq [[kind fields] fields-a-restore-dropped
                  field fields]
            (testing (str "the restore keeps " field " on " (name kind) " rows")
              (is (= (get (backup-row (ids kind)) field)
                     (get (restored (ids kind)) field))
                  (str field " was dropped (or changed) by the restore")))))
        (is (empty? (rt/audit-infidelities backup @tgt))))
      (finally
        (rt/release! src)
        (rt/release! tgt)))))

(deftest a-reference-in-the-backup-is-never-copied
  (let [src (rt/fresh-config-conn)
        tgt (rt/fresh-config-conn)]
    (try
      (let [{:keys [id]} (retracting-deployment! src)
            ;; the export never writes a reference today; a backup that does
            ;; (an export changed, or one edited by hand) must not smuggle it in
            backup (update-in (rt/default-backup src) [:data :audit]
                              (fn [rows] (mapv #(assoc % "audit/config-def" {"config-def/path" retracted-path}) rows)))]
        (testing "PREMISE: the backup's row carries a reference, and the target does not define its path"
          (is (some #(get % "audit/config-def") (get-in backup [:data :audit])))
          (is (nil? (config-db/get-definition @tgt retracted-path))))
        (is (nil? (:error (restore tgt backup)))
            "a reference in the backup was copied instead of being decided by the target")
        (is (nil? (rt/audit-reference @tgt id))))
      (finally
        (rt/release! src)
        (rt/release! tgt)))))

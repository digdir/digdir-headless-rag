(ns digdir.import-export.all-tenants-carry-test
  "a restore CARRIES the all-tenant
   marker, and REPORTS every marked key it brings in.

   Both restore doors end in the one importer: the JSON system export
   (`export-to-file` → `import-from-file`) and the YAML dump (`dump/export-system`
   → `dump/import-system`). The parity is over the three values a key can hold:
   TRUE (marked), FALSE (marked, then cleared), and ABSENT (never marked; a
   policyless legacy key too). Absent must stay absent: it is the fail-closed
   reading.

   Vars that commit 4 adds are not referenced, so this namespace loads at the
   base; each arm there fails by assertion."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [datahike.api :as d]
            [digdir.config.api-keys :as api-keys]
            [digdir.config.schema :as config-schema]
            [digdir.data.db :as data-db]
            [digdir.import-export.dump :as dump]
            [digdir.import-export.entities.api-keys :as api-key-entities]
            [digdir.import-export.system :as migration]
            [digdir.skills.api :as skills-api])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(use-fixtures :each (fn [f] (skills-api/reset-skills!) (skills-api/initialize!) (f) (skills-api/reset-skills!)))

(def ^:private master-key "test-master-key-32-chars-padding!")

(defn- mem-conn [label schema]
  (let [cfg {:store {:backend :mem :id (str "all-tenants-carry-" label "-" (random-uuid))} :schema-flexibility :read}]
    (d/create-database cfg)
    (doto (d/connect cfg) (d/transact {:tx-data schema}))))

(defn- stores []
  {:config (mem-conn "config" config-schema/config-migration-schema)
   :main (mem-conn "main" data-db/dh-schema)})

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "all-tenants-carry-" (into-array FileAttribute []))))

(defn- make-key! [conn key-name]
  (let [plaintext (:api-key (api-keys/create-api-key! conn key-name "creator"
                                                      {:scopes #{:query}
                                                       :dataset-scopes [{:tenant "kt" :dataset-config-key "ds-kt"}]}))]
    {:plaintext plaintext :id (:api-key-id (api-keys/validate-api-key conn plaintext)) :name key-name}))

(defn- seed-source!
  "Four keys: T (marked), F (marked, then cleared), A (a policy, never marked),
   L (a legacy key with NO policy)."
  [main]
  (let [t (make-key! main "T") f (make-key! main "F") a (make-key! main "A")
        l {:plaintext "SYNTHSECRET-legacy-708" :id "legacy-708" :name "L"}]
    (api-keys/set-all-tenants! main (:id t) true {:user-id "admin"})
    (api-keys/set-all-tenants! main (:id f) true {:user-id "admin"})
    (api-keys/set-all-tenants! main (:id f) false {:user-id "admin"})
    (d/transact main {:tx-data [{:api-key/id (:id l) :api-key/name "L" :api-key/revoked false :api-key/scopes [:query]
                                 :api-key/created 1 :api-key/created-by "legacy"
                                 :api-key/key-digest (api-keys/api-key-digest (:plaintext l))}]})
    {:T t :F f :A a :L l}))

(defn- stored-marker
  "The marker as STORED on the key's policy: true, false, or nil (absent)."
  [conn id]
  (d/q '[:find ?v . :in $ ?id :where [?k :api-key/id ?id] [?k :api-key/policy ?p] [?p :access-policy/all-tenants? ?v]]
       @conn id))

(defn- policy-id [conn id]
  (d/q '[:find ?pid . :in $ ?id :where [?k :api-key/id ?id] [?k :api-key/policy ?p] [?p :access-policy/id ?pid]]
       @conn id))

(defn- assert-parity! [dst keys]
  (is (= {:T true :F false :A nil :L nil}
         (update-vals keys #(stored-marker dst (:id %))))
      "stored marker: true, false and absent each arrive as they left")
  (is (= {:T true :F false :A false :L false}
         (update-vals keys #(:all-tenants? (api-keys/validate-api-key dst (:plaintext %)))))
      "what the door sees: only T reaches every tenant")
  (is (= (str "imported-policy/" (get-in keys [:T :id])) (policy-id dst (get-in keys [:T :id])))
      "the marker rides on the minted, reserved policy"))

(deftest the-marker-round-trips-through-the-json-system-door
  (let [{src-config :config src :main} (stores)
        {dst-config :config dst :main} (stores)
        keys (seed-source! src)
        file (io/file (temp-dir) "system.json")]
    (migration/export-to-file src-config src (str file) {:include-audit? false})
    (let [result (migration/import-from-file dst-config dst (str file) {:on-conflict :skip})]
      (assert-parity! dst keys)
      (is (= [{:id (get-in keys [:T :id]) :name "T"}] (:all-tenant-keys result))
          "the restore LISTS the all-tenant key it brought in, and only it"))))

(deftest the-marker-round-trips-through-the-yaml-dump-door
  (let [{src-config :config src :main} (stores)
        {dst-config :config dst :main} (stores)
        keys (seed-source! src)
        dir (temp-dir)]
    (dump/export-system src-config src dir {:master-key master-key})
    (let [result (dump/import-system dst-config dst dir {:master-key master-key})]
      (assert-parity! dst keys)
      (is (= [{:id (get-in keys [:T :id]) :name "T"}] (:all-tenant-keys result))))))

(deftest a-preview-lists-what-it-would-write-and-writes-nothing
  (let [{src-config :config src :main} (stores)
        {dst-config :config dst :main} (stores)
        keys (seed-source! src)
        exported (migration/export-system src-config src {:include-audit? false})
        preview (migration/preview-import-system dst-config dst exported {:on-conflict :skip})]
    (is (= [{:id (get-in keys [:T :id]) :name "T"}] (:all-tenant-keys preview)))
    (is (empty? (d/q '[:find ?k :where [?k :api-key/id]] @dst)) "the preview wrote a key")))

(deftest a-key-skipped-by-the-import-is-not-listed
  (let [{src-config :config src :main} (stores)
        {dst-config :config dst :main} (stores)
        _ (seed-source! src)
        exported (migration/export-system src-config src {:include-audit? false})]
    (is (= 1 (count (:all-tenant-keys (migration/import-system dst-config dst exported {:on-conflict :skip})))))
    (is (= [] (:all-tenant-keys (migration/import-system dst-config dst exported {:on-conflict :skip})))
        "the second import skipped every key, so it brought no all-tenant key in")
    (is (= 1 (count (:all-tenant-keys (migration/import-system dst-config dst exported {:on-conflict :overwrite})))))))

(defn- record [id marker-policy]
  (cond-> {:api-key/id id :api-key/name id :api-key/created 1 :api-key/created-by "import"
           :api-key/revoked false :api-key/scopes [:query]
           :api-key/key-digest (api-keys/api-key-digest (str "SYNTHSECRET-" id))
           :api-key/dataset-scopes [{:tenant "kt" :dataset-config-key "ds-kt"}]}
    marker-policy (assoc :api-key/policy marker-policy)))

(deftest an-exported-policy-id-never-links-a-policy-of-the-target
  (let [{config :config main :main} (stores)]
    (d/transact main {:tx-data [{:access-policy/id "p1" :access-policy/name "target's own"
                                 :access-policy/tenants ["ku"] :access-policy/all-tenants? true}]})
    (api-key-entities/import-api-keys! config main
                                       [(record "k1" {:access-policy/id "p1" :access-policy/all-tenants? false})]
                                       :skip)
    (let [info (api-keys/validate-api-key main "SYNTHSECRET-k1")]
      (is (= "imported-policy/k1" (policy-id main "k1")) "the key was linked to the TARGET's policy p1")
      (is (false? (:all-tenants? info)) "p1's marker reached the imported key")
      (is (= ["kt"] (:tenants info)) "p1's tenant grant reached the imported key"))
    (is (true? (d/q '[:find ?v . :where [?p :access-policy/id "p1"] [?p :access-policy/all-tenants? ?v]] @main))
        "the target's own policy was changed by the import")))

(deftest only-a-boolean-is-a-marker-and-absence-mints-nothing
  (let [{config :config main :main} (stores)
        result (api-key-entities/import-api-keys! config main
                                                  [(record "k-string" {:access-policy/all-tenants? "true"})
                                                   (record "k-absent" nil)
                                                   (record "k-true-string-keys" {"access-policy/all-tenants?" true})]
                                                  :skip)]
    (is (nil? (policy-id main "k-string")) "a non-boolean minted a policy")
    (is (false? (:all-tenants? (api-keys/validate-api-key main "SYNTHSECRET-k-string"))) "a string marked a key")
    (is (nil? (policy-id main "k-absent")) "absence minted a policy")
    (is (true? (:all-tenants? (api-keys/validate-api-key main "SYNTHSECRET-k-true-string-keys")))
        "a string-keyed record (as a reader may produce) lost its marker")
    (is (= [{:id "k-true-string-keys" :name "k-true-string-keys"}] (:all-tenant-keys result)))))

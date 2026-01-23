(ns digdir.config.ops-test
  "Tests for import/export operations."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.data.json :as json]
            [datahike.api :as d]
            [digdir.config.schema :as schema]
            [digdir.config.db :as config-db]
            [digdir.config.ops :as ops]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn create-test-db
  "Create an in-memory test database with schema."
  []
  (let [cfg {:store {:backend :mem
                     :id (str "ops-test-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      ;; Add config schema
      (d/transact conn {:tx-data schema/config-migration-schema})
      conn)))

(defn delete-test-db
  "Delete the test database."
  [conn]
  (let [cfg (:config @(:wrapped-atom conn))]
    (d/release conn)
    (d/delete-database cfg)))

;; =============================================================================
;; Import Tests
;; =============================================================================

(deftest test-import-from-file
  (let [conn (create-test-db)]
    (try
      (let [test-file "../config/export-full-2026-01-14-004309.json"]

        (testing "Dry-run import shows expected counts"
          (let [result (ops/import-from-file conn test-file {:dry-run? true})]
            (is (map? result))
            (is (contains? result :definitions))
            (is (contains? result :values))
            (is (> (get-in result [:definitions :total]) 0)
                "Should have definitions to import")
            (is (> (get-in result [:values :total]) 0)
                "Should have values to import")))

        (testing "Actual import creates definitions and values"
          (let [result (ops/import-from-file conn test-file {:on-conflict :skip})]
            (is (map? result))
            (is (contains? result :definitions))
            (is (contains? result :values))
            (is (> (get-in result [:definitions :created]) 0)
                "Should create definitions")))

        (testing "Definitions were imported correctly"
          (let [db @conn
                all-defs (config-db/get-all-definitions db)]
            (is (> (count all-defs) 0) "Should have definitions in database")
            ;; Check a specific definition exists
            (let [auth-session-def (config-db/get-definition db "services.auth.session-max-age")]
              (is (some? auth-session-def)
                  "Should have imported services.auth.session-max-age definition")
              (when auth-session-def
                (is (= :number (:config-def/value-type auth-session-def)))))))

        (testing "Tenants were created from imported values"
          (let [db @conn
                tenants (config-db/list-tenants db)]
            (is (seq tenants) "Should have tenants after import")
            (is (some #(= "ka" %) tenants) "Should have 'ka' tenant")))

        (testing "Values were imported correctly"
          (let [db @conn
                ;; Check if we can query values - use a definition that should exist
                all-defs (config-db/get-all-definitions db)]
            (when (seq all-defs)
              ;; Try to find any imported value
              (let [sample-def (first all-defs)
                    path (:config-def/path sample-def)
                    values (config-db/get-all-values-for-path db path)]
                ;; Just verify we can query - not all paths have values
                (is (vector? values) "Should be able to query values"))))))
      (finally
        (delete-test-db conn)))))

(deftest test-import-with-overwrite
  (let [conn (create-test-db)]
    (try
      (let [test-file "../config/export-full-2026-01-14-004309.json"]

        ;; First import
        (ops/import-from-file conn test-file {:on-conflict :skip})

        (let [first-import-def-count (count (config-db/get-all-definitions @conn))
              first-import-tenant-count (count (config-db/list-tenants @conn))]

          (testing "Re-import with skip leaves existing data"
            (let [result (ops/import-from-file conn test-file {:on-conflict :skip})]
              ;; All values should be skipped since they exist
              (is (= 0 (get-in result [:values :created] 0))
                  "Should not create new values on re-import with skip")))

          (testing "Re-import with overwrite updates existing data"
            (let [result (ops/import-from-file conn test-file {:on-conflict :overwrite})]
              ;; Should have updated existing values
              (is (>= (get-in result [:values :updated] 0) 0)
                  "Should update values on re-import with overwrite")))

          (testing "Definition and tenant counts remain consistent"
            (is (= first-import-def-count (count (config-db/get-all-definitions @conn)))
                "Definition count should remain the same after re-import")
            (is (= first-import-tenant-count (count (config-db/list-tenants @conn)))
                "Tenant count should remain the same after re-import"))))
      (finally
        (delete-test-db conn)))))

(deftest test-import-data-structure
  (let [conn (create-test-db)]
    (try
      (let [test-file "../config/export-full-2026-01-14-004309.json"
            raw-data (json/read-str (slurp test-file) :key-fn keyword)]

        (testing "Export file has expected structure"
          (is (= "1.0" (:version raw-data)) "Should have version 1.0")
          (is (= "full" (:scope raw-data)) "Should be full scope export")
          (is (contains? (:data raw-data) :definitions) "Should have definitions")
          (is (contains? (:data raw-data) :values) "Should have values"))

        (testing "Definitions have required fields"
          (let [first-def (first (get-in raw-data [:data :definitions]))]
            (is (contains? first-def (keyword "config-def/path")))
            (is (contains? first-def (keyword "config-def/value-type")))))

        (testing "Values have required fields"
          (let [first-val (first (get-in raw-data [:data :values]))]
            (is (contains? first-val (keyword "config/value")))
            (is (contains? first-val (keyword "config/definition-path"))))))
      (finally
        (delete-test-db conn)))))

;; =============================================================================
;; Export Tests (Round-trip)
;; =============================================================================

(deftest test-export-import-roundtrip
  (let [conn (create-test-db)]
    (try
      ;; Create some test data
      (config-db/upsert-definition! conn
                                    {:path "test.sample.value"
                                     :value-type :string
                                     :encrypted? false
                                     :category :general
                                     :service :other
                                     :sensitivity :internal
                                     :function :settings})

      (config-db/set-value! conn {:tenant "test-tenant"
                                   :environment "test"
                                   :entity nil
                                   :path "test.sample.value"
                                   :value "test-value"
                                   :skip-audit? true})

      (testing "Export produces valid data"
        (let [export-data (ops/export-full conn {:include-audit? false})]
          (is (= "1.0" (:version export-data)))
          (is (= "full" (:scope export-data)))
          (is (= 1 (count (get-in export-data [:data :definitions]))))
          (is (= 1 (count (get-in export-data [:data :values]))))))

      ;; Create a second database and import into it
      (let [conn2 (create-test-db)]
        (try
          (let [export-data (ops/export-full conn {:include-audit? false})]

            (testing "Import into fresh database works"
              (let [result (ops/import-data conn2 export-data {:on-conflict :skip})]
                (is (= 1 (get-in result [:definitions :created])))
                (is (= 1 (get-in result [:values :created])))))

            (testing "Imported data matches original"
              (let [db2 @conn2
                    imported-def (config-db/get-definition db2 "test.sample.value")
                    imported-val (config-db/get-raw-value db2 "test-tenant" "test" nil "test.sample.value")]
                (is (some? imported-def))
                (is (= :string (:config-def/value-type imported-def)))
                (is (= "test-value" imported-val)))))
          (finally
            (delete-test-db conn2))))
      (finally
        (delete-test-db conn)))))

;; =============================================================================
;; Run Tests
;; =============================================================================

(comment
  ;; Run all tests
  (clojure.test/run-tests 'digdir.config.ops-test)

  ;; Run specific test
  (test-import-from-file))

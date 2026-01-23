(ns digdir.config.db-test
  "Tests for multi-dimensional config resolution."
  (:require [clojure.test :refer [deftest testing is]]
            [datahike.api :as d]
            [digdir.config.schema :as schema]
            [digdir.config.db :as config-db]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn create-test-db
  "Create an in-memory test database with schema."
  []
  (let [cfg {:store {:backend :mem
                     :id (str "db-test-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data schema/config-migration-schema})
      conn)))

(defn delete-test-db
  "Delete the test database."
  [conn]
  (let [cfg (:config @(:wrapped-atom conn))]
    (d/release conn)
    (d/delete-database cfg)))

(defn seed-test-definition!
  "Create a test config definition."
  [conn path]
  (config-db/upsert-definition! conn
                                {:path path
                                 :value-type :string
                                 :encrypted? false
                                 :description "Test config"
                                 :category :general}))

(defn set-test-value!
  "Set a test config value at a specific level."
  [conn {:keys [tenant environment entity path value]}]
  (config-db/set-value! conn
                        {:tenant tenant
                         :environment environment
                         :entity entity
                         :path path
                         :value value
                         :master-key nil
                         :skip-audit? true}))

;; =============================================================================
;; Resolution Order Tests
;; =============================================================================

(deftest test-resolve-value-global
  (testing "Global value resolves when no other values exist"
    (let [conn (create-test-db)
          path "test.global-only"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :value "global-value"})

        (let [result (config-db/resolve-value @conn "tenant1" "prod" "entity1" path)]
          (is (= "global-value" (:config/value result))))
        (finally
          (delete-test-db conn))))))

(deftest test-resolve-value-environment-only
  (testing "Environment-only value resolves correctly"
    (let [conn (create-test-db)
          path "test.env-only"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :value "global"})
        (set-test-value! conn {:path path :environment "prod" :value "prod-value"})

        (let [result (config-db/resolve-value @conn "tenant1" "prod" "entity1" path)]
          (is (= "prod-value" (:config/value result))))
        (finally
          (delete-test-db conn))))))

(deftest test-resolve-value-tenant-only
  (testing "Tenant-only value takes priority over environment-only"
    (let [conn (create-test-db)
          path "test.tenant-only"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :value "global"})
        (set-test-value! conn {:path path :environment "prod" :value "prod-value"})
        (set-test-value! conn {:path path :tenant "ka" :value "ka-value"})

        (let [result (config-db/resolve-value @conn "ka" "prod" "entity1" path)]
          (is (= "ka-value" (:config/value result))))
        (finally
          (delete-test-db conn))))))

(deftest test-resolve-value-entity-only
  (testing "Entity-only value takes highest priority at 1 dimension"
    (let [conn (create-test-db)
          path "test.entity-only"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :value "global"})
        (set-test-value! conn {:path path :environment "prod" :value "prod-value"})
        (set-test-value! conn {:path path :tenant "ka" :value "ka-value"})
        (set-test-value! conn {:path path :entity "bot1" :value "bot1-value"})

        (let [result (config-db/resolve-value @conn "ka" "prod" "bot1" path)]
          (is (= "bot1-value" (:config/value result))))
        (finally
          (delete-test-db conn))))))

(deftest test-resolve-value-tenant-env
  (testing "Tenant+Environment takes priority over single dimensions"
    (let [conn (create-test-db)
          path "test.tenant-env"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :value "global"})
        (set-test-value! conn {:path path :tenant "ka" :value "ka-value"})
        (set-test-value! conn {:path path :entity "bot1" :value "bot1-value"})
        (set-test-value! conn {:path path :tenant "ka" :environment "prod" :value "ka-prod-value"})

        (let [result (config-db/resolve-value @conn "ka" "prod" "bot1" path)]
          (is (= "ka-prod-value" (:config/value result))))
        (finally
          (delete-test-db conn))))))

(deftest test-resolve-value-entity-env
  (testing "Entity+Environment takes priority over tenant+env"
    (let [conn (create-test-db)
          path "test.entity-env"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :value "global"})
        (set-test-value! conn {:path path :tenant "ka" :environment "prod" :value "ka-prod-value"})
        (set-test-value! conn {:path path :entity "bot1" :environment "prod" :value "bot1-prod-value"})

        (let [result (config-db/resolve-value @conn "ka" "prod" "bot1" path)]
          (is (= "bot1-prod-value" (:config/value result))))
        (finally
          (delete-test-db conn))))))

(deftest test-resolve-value-entity-tenant
  (testing "Entity+Tenant takes highest priority at 2 dimensions"
    (let [conn (create-test-db)
          path "test.entity-tenant"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :value "global"})
        (set-test-value! conn {:path path :tenant "ka" :environment "prod" :value "ka-prod-value"})
        (set-test-value! conn {:path path :entity "bot1" :environment "prod" :value "bot1-prod-value"})
        (set-test-value! conn {:path path :entity "bot1" :tenant "ka" :value "bot1-ka-value"})

        (let [result (config-db/resolve-value @conn "ka" "prod" "bot1" path)]
          (is (= "bot1-ka-value" (:config/value result))))
        (finally
          (delete-test-db conn))))))

(deftest test-resolve-value-entity-tenant-env
  (testing "Entity+Tenant+Environment takes highest priority (3 dimensions)"
    (let [conn (create-test-db)
          path "test.entity-tenant-env"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :value "global"})
        (set-test-value! conn {:path path :entity "bot1" :tenant "ka" :value "bot1-ka-value"})
        (set-test-value! conn {:path path :entity "bot1" :tenant "ka" :environment "prod" :value "full-specific"})

        (let [result (config-db/resolve-value @conn "ka" "prod" "bot1" path)]
          (is (= "full-specific" (:config/value result))))
        (finally
          (delete-test-db conn))))))

;; =============================================================================
;; Resolution Level Tests
;; =============================================================================

(deftest test-resolve-value-with-level-returns-correct-level
  (testing "resolve-value-with-level returns the correct level keyword"
    (let [conn (create-test-db)
          path "test.levels"]
      (try
        (seed-test-definition! conn path)

        ;; Set values at all levels
        (set-test-value! conn {:path path :value "global"})
        (set-test-value! conn {:path path :environment "prod" :value "env"})
        (set-test-value! conn {:path path :tenant "ka" :value "tenant"})
        (set-test-value! conn {:path path :entity "bot1" :value "entity"})
        (set-test-value! conn {:path path :tenant "ka" :environment "prod" :value "tenant-env"})
        (set-test-value! conn {:path path :entity "bot1" :environment "prod" :value "entity-env"})
        (set-test-value! conn {:path path :entity "bot1" :tenant "ka" :value "entity-tenant"})
        (set-test-value! conn {:path path :entity "bot1" :tenant "ka" :environment "prod" :value "full"})

        ;; Test that the most specific level is returned
        (let [result (config-db/resolve-value-with-level @conn "ka" "prod" "bot1" path)]
          (is (= :entity-tenant-env (:level result)))
          (is (= "full" (:config/value (:value result)))))

        (finally
          (delete-test-db conn))))))

(deftest test-resolve-value-with-level-each-level-in-isolation
  (testing "Each level resolves correctly when it's the most specific"
    (let [conn (create-test-db)]
      (try
        ;; Test global level
        (let [path "test.level.global"]
          (seed-test-definition! conn path)
          (set-test-value! conn {:path path :value "global"})
          (let [result (config-db/resolve-value-with-level @conn "ka" "prod" "bot1" path)]
            (is (= :global (:level result)))))

        ;; Test environment level
        (let [path "test.level.env"]
          (seed-test-definition! conn path)
          (set-test-value! conn {:path path :environment "prod" :value "env"})
          (let [result (config-db/resolve-value-with-level @conn "ka" "prod" "bot1" path)]
            (is (= :environment (:level result)))))

        ;; Test tenant level
        (let [path "test.level.tenant"]
          (seed-test-definition! conn path)
          (set-test-value! conn {:path path :tenant "ka" :value "tenant"})
          (let [result (config-db/resolve-value-with-level @conn "ka" "prod" "bot1" path)]
            (is (= :tenant (:level result)))))

        ;; Test entity level
        (let [path "test.level.entity"]
          (seed-test-definition! conn path)
          (set-test-value! conn {:path path :entity "bot1" :value "entity"})
          (let [result (config-db/resolve-value-with-level @conn "ka" "prod" "bot1" path)]
            (is (= :entity (:level result)))))

        ;; Test tenant-env level
        (let [path "test.level.tenant-env"]
          (seed-test-definition! conn path)
          (set-test-value! conn {:path path :tenant "ka" :environment "prod" :value "tenant-env"})
          (let [result (config-db/resolve-value-with-level @conn "ka" "prod" "bot1" path)]
            (is (= :tenant-env (:level result)))))

        ;; Test entity-env level
        (let [path "test.level.entity-env"]
          (seed-test-definition! conn path)
          (set-test-value! conn {:path path :entity "bot1" :environment "prod" :value "entity-env"})
          (let [result (config-db/resolve-value-with-level @conn "ka" "prod" "bot1" path)]
            (is (= :entity-env (:level result)))))

        ;; Test entity-tenant level
        (let [path "test.level.entity-tenant"]
          (seed-test-definition! conn path)
          (set-test-value! conn {:path path :entity "bot1" :tenant "ka" :value "entity-tenant"})
          (let [result (config-db/resolve-value-with-level @conn "ka" "prod" "bot1" path)]
            (is (= :entity-tenant (:level result)))))

        ;; Test entity-tenant-env level
        (let [path "test.level.entity-tenant-env"]
          (seed-test-definition! conn path)
          (set-test-value! conn {:path path :entity "bot1" :tenant "ka" :environment "prod" :value "full"})
          (let [result (config-db/resolve-value-with-level @conn "ka" "prod" "bot1" path)]
            (is (= :entity-tenant-env (:level result)))))

        (finally
          (delete-test-db conn))))))

;; =============================================================================
;; Tiebreaker Tests
;; =============================================================================

(deftest test-tiebreaker-entity-over-tenant-at-1-dim
  (testing "Entity takes priority over tenant at 1 dimension"
    (let [conn (create-test-db)
          path "test.tiebreaker.1dim"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :tenant "ka" :value "tenant"})
        (set-test-value! conn {:path path :entity "bot1" :value "entity"})

        (let [result (config-db/resolve-value @conn "ka" "prod" "bot1" path)]
          (is (= "entity" (:config/value result))))
        (finally
          (delete-test-db conn))))))

(deftest test-tiebreaker-tenant-over-environment-at-1-dim
  (testing "Tenant takes priority over environment at 1 dimension"
    (let [conn (create-test-db)
          path "test.tiebreaker.tenant-env"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :environment "prod" :value "env"})
        (set-test-value! conn {:path path :tenant "ka" :value "tenant"})

        (let [result (config-db/resolve-value @conn "ka" "prod" "bot1" path)]
          (is (= "tenant" (:config/value result))))
        (finally
          (delete-test-db conn))))))

(deftest test-tiebreaker-entity-tenant-over-entity-env-at-2-dim
  (testing "Entity+Tenant takes priority over Entity+Environment at 2 dimensions"
    (let [conn (create-test-db)
          path "test.tiebreaker.2dim"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :entity "bot1" :environment "prod" :value "entity-env"})
        (set-test-value! conn {:path path :entity "bot1" :tenant "ka" :value "entity-tenant"})

        (let [result (config-db/resolve-value @conn "ka" "prod" "bot1" path)]
          (is (= "entity-tenant" (:config/value result))))
        (finally
          (delete-test-db conn))))))

(deftest test-tiebreaker-entity-env-over-tenant-env-at-2-dim
  (testing "Entity+Environment takes priority over Tenant+Environment at 2 dimensions"
    (let [conn (create-test-db)
          path "test.tiebreaker.2dim-env"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :tenant "ka" :environment "prod" :value "tenant-env"})
        (set-test-value! conn {:path path :entity "bot1" :environment "prod" :value "entity-env"})

        (let [result (config-db/resolve-value @conn "ka" "prod" "bot1" path)]
          (is (= "entity-env" (:config/value result))))
        (finally
          (delete-test-db conn))))))

;; =============================================================================
;; Cascading Resolution Tests
;; =============================================================================

(deftest test-cascading-resolution
  (testing "Values cascade correctly through the hierarchy"
    (let [conn (create-test-db)
          path "test.cascade"]
      (try
        (seed-test-definition! conn path)

        ;; Only global
        (set-test-value! conn {:path path :value "global"})
        (is (= "global" (:config/value (config-db/resolve-value @conn "ka" "prod" "bot1" path))))

        ;; Add tenant - should override
        (set-test-value! conn {:path path :tenant "ka" :value "tenant"})
        (is (= "tenant" (:config/value (config-db/resolve-value @conn "ka" "prod" "bot1" path))))

        ;; Add entity - should override tenant
        (set-test-value! conn {:path path :entity "bot1" :value "entity"})
        (is (= "entity" (:config/value (config-db/resolve-value @conn "ka" "prod" "bot1" path))))

        ;; Add entity+tenant - should override entity
        (set-test-value! conn {:path path :entity "bot1" :tenant "ka" :value "entity-tenant"})
        (is (= "entity-tenant" (:config/value (config-db/resolve-value @conn "ka" "prod" "bot1" path))))

        ;; Add full specification - should override all
        (set-test-value! conn {:path path :entity "bot1" :tenant "ka" :environment "prod" :value "full"})
        (is (= "full" (:config/value (config-db/resolve-value @conn "ka" "prod" "bot1" path))))

        (finally
          (delete-test-db conn))))))

;; =============================================================================
;; get-values-at-all-levels Tests
;; =============================================================================

(deftest test-get-values-at-all-levels-returns-all-levels
  (testing "get-values-at-all-levels returns values keyed by level descriptor"
    (let [conn (create-test-db)
          path "test.all-levels"]
      (try
        (seed-test-definition! conn path)

        ;; Set values at various levels
        (set-test-value! conn {:path path :value "global"})
        (set-test-value! conn {:path path :environment "prod" :value "env"})
        (set-test-value! conn {:path path :tenant "ka" :value "tenant"})
        (set-test-value! conn {:path path :entity "bot1" :value "entity"})
        (set-test-value! conn {:path path :tenant "ka" :environment "prod" :value "tenant-env"})
        (set-test-value! conn {:path path :entity "bot1" :environment "prod" :value "entity-env"})
        (set-test-value! conn {:path path :entity "bot1" :tenant "ka" :value "entity-tenant"})
        (set-test-value! conn {:path path :entity "bot1" :tenant "ka" :environment "prod" :value "full"})

        ;; selected-entities is now a flat set
        (let [result (config-db/get-values-at-all-levels @conn path #{"ka"} #{"prod"} #{"bot1"})]
          ;; Check that all expected keys are present
          (is (contains? result :global))
          (is (contains? result [:env "prod"]))
          (is (contains? result [:tenant "ka"]))
          (is (contains? result [:entity "bot1"]))
          (is (contains? result [:tenant-env "ka" "prod"]))
          (is (contains? result [:entity-env "bot1" "prod"]))
          (is (contains? result [:entity-tenant "bot1" "ka"]))
          (is (contains? result [:entity-tenant-env "bot1" "ka" "prod"]))

          ;; Verify values
          (is (= "global" (:config/value (get result :global))))
          (is (= "env" (:config/value (get result [:env "prod"]))))
          (is (= "tenant" (:config/value (get result [:tenant "ka"]))))
          (is (= "entity" (:config/value (get result [:entity "bot1"]))))
          (is (= "tenant-env" (:config/value (get result [:tenant-env "ka" "prod"]))))
          (is (= "entity-env" (:config/value (get result [:entity-env "bot1" "prod"]))))
          (is (= "entity-tenant" (:config/value (get result [:entity-tenant "bot1" "ka"]))))
          (is (= "full" (:config/value (get result [:entity-tenant-env "bot1" "ka" "prod"])))))

        (finally
          (delete-test-db conn))))))

(deftest test-get-values-at-all-levels-filtering
  (testing "get-values-at-all-levels filters by selected tenants/environments/entities"
    (let [conn (create-test-db)
          path "test.filtering"]
      (try
        (seed-test-definition! conn path)

        ;; Set values for multiple tenants/envs/entities
        (set-test-value! conn {:path path :value "global"})
        (set-test-value! conn {:path path :tenant "ka" :value "ka"})
        (set-test-value! conn {:path path :tenant "altinn" :value "altinn"})
        (set-test-value! conn {:path path :environment "prod" :value "prod"})
        (set-test-value! conn {:path path :environment "staging" :value "staging"})

        ;; Filter to only ka tenant and prod environment
        ;; selected-entities is now a flat set (empty)
        (let [result (config-db/get-values-at-all-levels @conn path #{"ka"} #{"prod"} #{})]
          (is (contains? result :global))
          (is (contains? result [:tenant "ka"]))
          (is (not (contains? result [:tenant "altinn"])))  ; filtered out
          (is (contains? result [:env "prod"]))
          (is (not (contains? result [:env "staging"]))))   ; filtered out

        (finally
          (delete-test-db conn))))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest test-nil-entity-returns-lower-levels
  (testing "When entity is nil, only non-entity levels are checked"
    (let [conn (create-test-db)
          path "test.nil-entity"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :value "global"})
        (set-test-value! conn {:path path :tenant "ka" :value "tenant"})
        (set-test-value! conn {:path path :tenant "ka" :environment "prod" :value "tenant-env"})
        (set-test-value! conn {:path path :entity "bot1" :tenant "ka" :value "entity-tenant"})

        ;; With nil entity, should get tenant-env (highest non-entity level)
        (let [result (config-db/resolve-value @conn "ka" "prod" nil path)]
          (is (= "tenant-env" (:config/value result))))

        (finally
          (delete-test-db conn))))))

(deftest test-not-found-returns-nil
  (testing "When no value exists at any level, returns nil"
    (let [conn (create-test-db)
          path "test.not-found"]
      (try
        (seed-test-definition! conn path)
        ;; Don't set any values

        (let [result (config-db/resolve-value @conn "ka" "prod" "bot1" path)]
          (is (nil? result)))

        (finally
          (delete-test-db conn))))))

;; =============================================================================
;; Cross-Tenant/Cross-Entity Isolation Tests
;; =============================================================================

(deftest test-entity-value-does-not-match-different-entity
  (testing "Entity-specific value only resolves for that specific entity"
    (let [conn (create-test-db)
          path "test.entity-isolation"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :value "global"})
        (set-test-value! conn {:path path :entity "bot1" :value "bot1-value"})

        ;; Different entity should get global, not bot1's value
        (let [result (config-db/resolve-value @conn "ka" "prod" "bot2" path)]
          (is (= "global" (:config/value result))))

        ;; bot1 should get its specific value
        (let [result (config-db/resolve-value @conn "ka" "prod" "bot1" path)]
          (is (= "bot1-value" (:config/value result))))

        (finally
          (delete-test-db conn))))))

(deftest test-tenant-value-does-not-match-different-tenant
  (testing "Tenant-specific value only resolves for that specific tenant"
    (let [conn (create-test-db)
          path "test.tenant-isolation"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :value "global"})
        (set-test-value! conn {:path path :tenant "ka" :value "ka-value"})

        ;; Different tenant should get global
        (let [result (config-db/resolve-value @conn "altinn" "prod" "bot1" path)]
          (is (= "global" (:config/value result))))

        ;; ka should get its specific value
        (let [result (config-db/resolve-value @conn "ka" "prod" "bot1" path)]
          (is (= "ka-value" (:config/value result))))

        (finally
          (delete-test-db conn))))))

(deftest test-entity-tenant-combination-must-match
  (testing "Entity+Tenant value requires both entity AND tenant to match"
    (let [conn (create-test-db)
          path "test.entity-tenant-isolation"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :value "global"})
        (set-test-value! conn {:path path :entity "bot1" :tenant "ka" :value "bot1-ka"})

        ;; Same entity, different tenant - should get global
        (let [result (config-db/resolve-value @conn "altinn" "prod" "bot1" path)]
          (is (= "global" (:config/value result))))

        ;; Different entity, same tenant - should get global
        (let [result (config-db/resolve-value @conn "ka" "prod" "bot2" path)]
          (is (= "global" (:config/value result))))

        ;; Matching entity and tenant - should get specific value
        (let [result (config-db/resolve-value @conn "ka" "prod" "bot1" path)]
          (is (= "bot1-ka" (:config/value result))))

        (finally
          (delete-test-db conn))))))

(deftest test-environment-only-overridden-by-tenant
  (testing "Environment value is overridden when tenant value exists"
    (let [conn (create-test-db)
          path "test.env-tenant-override"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :environment "prod" :value "prod-default"})
        (set-test-value! conn {:path path :tenant "ka" :value "ka-value"})

        ;; ka tenant in prod env should get ka-value (tenant > env)
        (let [result (config-db/resolve-value @conn "ka" "prod" nil path)]
          (is (= "ka-value" (:config/value result))))

        ;; Different tenant in prod env should get prod-default
        (let [result (config-db/resolve-value @conn "altinn" "prod" nil path)]
          (is (= "prod-default" (:config/value result))))

        (finally
          (delete-test-db conn))))))

;; =============================================================================
;; Multi-Entity Scenarios
;; =============================================================================

(deftest test-multiple-entities-different-values
  (testing "Different entities can have different values at same path"
    (let [conn (create-test-db)
          path "test.multi-entity"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :value "global"})
        (set-test-value! conn {:path path :entity "bot1" :value "bot1-value"})
        (set-test-value! conn {:path path :entity "bot2" :value "bot2-value"})
        (set-test-value! conn {:path path :entity "bot3" :value "bot3-value"})

        (is (= "bot1-value" (:config/value (config-db/resolve-value @conn "ka" "prod" "bot1" path))))
        (is (= "bot2-value" (:config/value (config-db/resolve-value @conn "ka" "prod" "bot2" path))))
        (is (= "bot3-value" (:config/value (config-db/resolve-value @conn "ka" "prod" "bot3" path))))
        ;; Entity without specific value gets global
        (is (= "global" (:config/value (config-db/resolve-value @conn "ka" "prod" "bot4" path))))

        (finally
          (delete-test-db conn))))))

(deftest test-entity-inherits-from-tenant-when-no-entity-value
  (testing "Entity inherits tenant value when no entity-specific value exists"
    (let [conn (create-test-db)
          path "test.entity-inherits-tenant"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :value "global"})
        (set-test-value! conn {:path path :tenant "ka" :value "ka-default"})
        (set-test-value! conn {:path path :entity "bot1" :tenant "ka" :value "bot1-ka-specific"})

        ;; bot1 in ka gets its specific value
        (is (= "bot1-ka-specific" (:config/value (config-db/resolve-value @conn "ka" "prod" "bot1" path))))

        ;; bot2 in ka inherits from ka tenant (not global) because tenant > global
        ;; but entity-only for bot2 doesn't exist, so it falls through to tenant
        (is (= "ka-default" (:config/value (config-db/resolve-value @conn "ka" "prod" "bot2" path))))

        (finally
          (delete-test-db conn))))))

;; =============================================================================
;; Config ID Format Tests
;; =============================================================================

(deftest test-make-config-id-format
  (testing "make-config-id generates correct format"
    (is (= "ka:prod:bot1:services.api-key"
           (config-db/make-config-id "ka" "prod" "bot1" "services.api-key")))
    (is (= "_:prod:bot1:services.api-key"
           (config-db/make-config-id nil "prod" "bot1" "services.api-key")))
    (is (= "ka:_:bot1:services.api-key"
           (config-db/make-config-id "ka" nil "bot1" "services.api-key")))
    (is (= "ka:prod:_:services.api-key"
           (config-db/make-config-id "ka" "prod" nil "services.api-key")))
    (is (= "_:_:_:services.api-key"
           (config-db/make-config-id nil nil nil "services.api-key")))))

(deftest test-parse-config-id-roundtrip
  (testing "parse-config-id correctly parses all formats"
    ;; Full specification
    (is (= {:tenant "ka" :environment "prod" :entity "bot1" :path "services.api-key"}
           (config-db/parse-config-id "ka:prod:bot1:services.api-key")))
    ;; Nil tenant
    (is (= {:tenant nil :environment "prod" :entity "bot1" :path "services.api-key"}
           (config-db/parse-config-id "_:prod:bot1:services.api-key")))
    ;; Nil environment
    (is (= {:tenant "ka" :environment nil :entity "bot1" :path "services.api-key"}
           (config-db/parse-config-id "ka:_:bot1:services.api-key")))
    ;; Nil entity
    (is (= {:tenant "ka" :environment "prod" :entity nil :path "services.api-key"}
           (config-db/parse-config-id "ka:prod:_:services.api-key")))
    ;; All nil (global)
    (is (= {:tenant nil :environment nil :entity nil :path "services.api-key"}
           (config-db/parse-config-id "_:_:_:services.api-key")))
    ;; Path with dots
    (is (= {:tenant "ka" :environment "prod" :entity "bot1" :path "services.azure.openai.api-key"}
           (config-db/parse-config-id "ka:prod:bot1:services.azure.openai.api-key")))))

;; =============================================================================
;; Nil Dimension Handling
;; =============================================================================

(deftest test-nil-tenant-resolution
  (testing "Resolution works correctly when tenant is nil"
    (let [conn (create-test-db)
          path "test.nil-tenant"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :value "global"})
        (set-test-value! conn {:path path :environment "prod" :value "prod-value"})
        (set-test-value! conn {:path path :entity "bot1" :value "entity-value"})

        ;; With nil tenant, entity-only should still win over environment
        (let [result (config-db/resolve-value @conn nil "prod" "bot1" path)]
          (is (= "entity-value" (:config/value result))))

        (finally
          (delete-test-db conn))))))

(deftest test-nil-environment-resolution
  (testing "Resolution works correctly when environment is nil"
    (let [conn (create-test-db)
          path "test.nil-environment"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :value "global"})
        (set-test-value! conn {:path path :tenant "ka" :value "tenant-value"})
        (set-test-value! conn {:path path :entity "bot1" :tenant "ka" :value "entity-tenant-value"})

        ;; With nil environment, entity+tenant should work
        (let [result (config-db/resolve-value @conn "ka" nil "bot1" path)]
          (is (= "entity-tenant-value" (:config/value result))))

        (finally
          (delete-test-db conn))))))

(deftest test-all-dimensions-nil
  (testing "Resolution with all dimensions nil returns global"
    (let [conn (create-test-db)
          path "test.all-nil"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :value "global"})
        (set-test-value! conn {:path path :tenant "ka" :value "tenant-value"})

        ;; All nil should get global
        (let [result (config-db/resolve-value @conn nil nil nil path)]
          (is (= "global" (:config/value result))))

        (finally
          (delete-test-db conn))))))

;; =============================================================================
;; Complete Resolution Order Verification
;; =============================================================================

(deftest test-complete-8-level-precedence
  (testing "Verifies complete 8-level precedence order with all levels set"
    (let [conn (create-test-db)
          path "test.8-level-precedence"]
      (try
        (seed-test-definition! conn path)

        ;; Set ALL 8 levels with unique values
        (set-test-value! conn {:path path :value "L8-global"})
        (set-test-value! conn {:path path :environment "prod" :value "L7-env"})
        (set-test-value! conn {:path path :tenant "ka" :value "L6-tenant"})
        (set-test-value! conn {:path path :entity "bot1" :value "L5-entity"})
        (set-test-value! conn {:path path :tenant "ka" :environment "prod" :value "L4-tenant-env"})
        (set-test-value! conn {:path path :entity "bot1" :environment "prod" :value "L3-entity-env"})
        (set-test-value! conn {:path path :entity "bot1" :tenant "ka" :value "L2-entity-tenant"})
        (set-test-value! conn {:path path :entity "bot1" :tenant "ka" :environment "prod" :value "L1-full"})

        ;; Test that highest priority wins
        (is (= "L1-full" (:config/value (config-db/resolve-value @conn "ka" "prod" "bot1" path))))

        ;; Now delete L1 and verify L2 takes over
        (config-db/delete-value! conn "ka" "prod" "bot1" path)
        (is (= "L2-entity-tenant" (:config/value (config-db/resolve-value @conn "ka" "prod" "bot1" path))))

        ;; Delete L2 and verify L3 takes over
        (config-db/delete-value! conn "ka" nil "bot1" path)
        (is (= "L3-entity-env" (:config/value (config-db/resolve-value @conn "ka" "prod" "bot1" path))))

        ;; Delete L3 and verify L4 takes over
        (config-db/delete-value! conn nil "prod" "bot1" path)
        (is (= "L4-tenant-env" (:config/value (config-db/resolve-value @conn "ka" "prod" "bot1" path))))

        ;; Delete L4 and verify L5 takes over
        (config-db/delete-value! conn "ka" "prod" nil path)
        (is (= "L5-entity" (:config/value (config-db/resolve-value @conn "ka" "prod" "bot1" path))))

        ;; Delete L5 and verify L6 takes over
        (config-db/delete-value! conn nil nil "bot1" path)
        (is (= "L6-tenant" (:config/value (config-db/resolve-value @conn "ka" "prod" "bot1" path))))

        ;; Delete L6 and verify L7 takes over
        (config-db/delete-value! conn "ka" nil nil path)
        (is (= "L7-env" (:config/value (config-db/resolve-value @conn "ka" "prod" "bot1" path))))

        ;; Delete L7 and verify L8 (global) is returned
        (config-db/delete-value! conn nil "prod" nil path)
        (is (= "L8-global" (:config/value (config-db/resolve-value @conn "ka" "prod" "bot1" path))))

        (finally
          (delete-test-db conn))))))

;; =============================================================================
;; Entity-Environment Level Tests (New in 8-level model)
;; =============================================================================

(deftest test-entity-env-cross-tenant
  (testing "Entity+Environment value works across different tenants"
    (let [conn (create-test-db)
          path "test.entity-env-cross-tenant"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :value "global"})
        (set-test-value! conn {:path path :entity "bot1" :environment "prod" :value "bot1-prod"})

        ;; Should work for any tenant since entity+env doesn't include tenant
        (is (= "bot1-prod" (:config/value (config-db/resolve-value @conn "ka" "prod" "bot1" path))))
        (is (= "bot1-prod" (:config/value (config-db/resolve-value @conn "altinn" "prod" "bot1" path))))
        (is (= "bot1-prod" (:config/value (config-db/resolve-value @conn nil "prod" "bot1" path))))

        ;; Different entity or env should not match
        (is (= "global" (:config/value (config-db/resolve-value @conn "ka" "staging" "bot1" path))))
        (is (= "global" (:config/value (config-db/resolve-value @conn "ka" "prod" "bot2" path))))

        (finally
          (delete-test-db conn))))))

(deftest test-entity-only-cross-tenant-and-env
  (testing "Entity-only value works across all tenants and environments"
    (let [conn (create-test-db)
          path "test.entity-only-cross"]
      (try
        (seed-test-definition! conn path)
        (set-test-value! conn {:path path :value "global"})
        (set-test-value! conn {:path path :entity "shared-bot" :value "shared-value"})

        ;; Should work for any tenant/env combination
        (is (= "shared-value" (:config/value (config-db/resolve-value @conn "ka" "prod" "shared-bot" path))))
        (is (= "shared-value" (:config/value (config-db/resolve-value @conn "altinn" "staging" "shared-bot" path))))
        (is (= "shared-value" (:config/value (config-db/resolve-value @conn nil nil "shared-bot" path))))

        (finally
          (delete-test-db conn))))))

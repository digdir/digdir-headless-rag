(ns digdir.config.ui-test
  "Tests for config UI functions, specifically column key parsing."
  (:require [clojure.test :refer [deftest testing is]]
            [digdir.config.ui :as ui]))

;; =============================================================================
;; parse-column-key Tests
;; =============================================================================

(deftest test-parse-column-key-global
  (testing "Global key parses to all nils"
    (is (= [nil nil nil] (ui/parse-column-key :global)))))

(deftest test-parse-column-key-environment-only
  (testing "Environment-only key parses correctly"
    (is (= [nil "prod" nil] (ui/parse-column-key [:env "prod"])))
    (is (= [nil "staging" nil] (ui/parse-column-key [:env "staging"])))))

(deftest test-parse-column-key-tenant-only
  (testing "Tenant-only key parses correctly"
    (is (= ["ka" nil nil] (ui/parse-column-key [:tenant "ka"])))
    (is (= ["altinn" nil nil] (ui/parse-column-key [:tenant "altinn"])))))

(deftest test-parse-column-key-entity-only
  (testing "Entity-only key parses correctly (NEW)"
    (is (= [nil nil "bot1"] (ui/parse-column-key [:entity "bot1"])))
    (is (= [nil nil "shared-assistant"] (ui/parse-column-key [:entity "shared-assistant"])))))

(deftest test-parse-column-key-tenant-env
  (testing "Tenant+Environment key parses correctly"
    (is (= ["ka" "prod" nil] (ui/parse-column-key [:tenant-env "ka" "prod"])))
    (is (= ["altinn" "staging" nil] (ui/parse-column-key [:tenant-env "altinn" "staging"])))))

(deftest test-parse-column-key-entity-env
  (testing "Entity+Environment key parses correctly (NEW)"
    (is (= [nil "prod" "bot1"] (ui/parse-column-key [:entity-env "bot1" "prod"])))
    (is (= [nil "staging" "assistant"] (ui/parse-column-key [:entity-env "assistant" "staging"])))))

(deftest test-parse-column-key-entity-tenant
  (testing "Entity+Tenant key parses correctly (renamed from tenant-entity)"
    (is (= ["ka" nil "bot1"] (ui/parse-column-key [:entity-tenant "bot1" "ka"])))
    (is (= ["altinn" nil "assistant"] (ui/parse-column-key [:entity-tenant "assistant" "altinn"])))))

(deftest test-parse-column-key-entity-tenant-env
  (testing "Entity+Tenant+Environment key parses correctly (NEW naming)"
    (is (= ["ka" "prod" "bot1"] (ui/parse-column-key [:entity-tenant-env "bot1" "ka" "prod"])))
    (is (= ["altinn" "staging" "assistant"] (ui/parse-column-key [:entity-tenant-env "assistant" "altinn" "staging"])))))

;; =============================================================================
;; Legacy Key Format Tests (Backwards Compatibility)
;; =============================================================================

(deftest test-parse-column-key-legacy-tenant-entity
  (testing "Legacy tenant-entity key still works"
    (is (= ["ka" nil "bot1"] (ui/parse-column-key [:tenant-entity "ka" "bot1"])))))

(deftest test-parse-column-key-legacy-tenant-env-entity
  (testing "Legacy tenant-env-entity key still works"
    (is (= ["ka" "prod" "bot1"] (ui/parse-column-key [:tenant-env-entity "ka" "prod" "bot1"])))))

;; =============================================================================
;; generate-columns Tests
;; =============================================================================

(deftest test-generate-columns-basic
  (testing "generate-columns produces correct column structure"
    ;; selected-entities is now a flat set
    (let [columns (ui/generate-columns #{"ka"} #{"prod"} #{"bot1"})]
      ;; Should have: global, entity:bot1, tenant:ka, env:prod, entity-tenant, entity-env, tenant-env, entity-tenant-env
      ;; Within each dimension count, priority is: entity > tenant > environment
      (is (= 8 (count columns)))

      ;; Check ordering (left to right = least to most specific)
      (is (= :global (:key (first columns))))
      (is (= [:entity "bot1"] (:key (second columns))))
      (is (= [:tenant "ka"] (:key (nth columns 2))))
      (is (= [:env "prod"] (:key (nth columns 3))))
      (is (= [:entity-tenant "bot1" "ka"] (:key (nth columns 4))))
      (is (= [:entity-env "bot1" "prod"] (:key (nth columns 5))))
      (is (= [:tenant-env "ka" "prod"] (:key (nth columns 6))))
      (is (= [:entity-tenant-env "bot1" "ka" "prod"] (:key (nth columns 7)))))))

(deftest test-generate-columns-multiple-tenants
  (testing "generate-columns handles multiple tenants"
    ;; selected-entities is now a flat set (empty in this test)
    (let [columns (ui/generate-columns #{"ka" "altinn"} #{"prod"} #{})]
      ;; Should have: global, env:prod, tenant:altinn, tenant:ka, tenant-env:altinn:prod, tenant-env:ka:prod
      (is (= 6 (count columns)))

      ;; Verify tenant columns are sorted
      (let [tenant-cols (filter #(= :tenant (:level %)) columns)]
        (is (= 2 (count tenant-cols)))
        (is (= "altinn" (second (:key (first tenant-cols)))))
        (is (= "ka" (second (:key (second tenant-cols)))))))))

(deftest test-generate-columns-multiple-environments
  (testing "generate-columns handles multiple environments"
    ;; selected-entities is now a flat set (empty in this test)
    (let [columns (ui/generate-columns #{"ka"} #{"prod" "staging"} #{})]
      ;; Should have: global, env:prod, env:staging, tenant:ka, tenant-env:ka:prod, tenant-env:ka:staging
      (is (= 6 (count columns)))

      ;; Verify env columns are sorted
      (let [env-cols (filter #(= :environment (:level %)) columns)]
        (is (= 2 (count env-cols)))
        (is (= "prod" (second (:key (first env-cols)))))
        (is (= "staging" (second (:key (second env-cols)))))))))

(deftest test-generate-columns-multiple-entities
  (testing "generate-columns handles multiple entities"
    ;; selected-entities is now a flat set
    (let [columns (ui/generate-columns #{"ka"} #{"prod"} #{"bot1" "bot2"})]
      ;; Check entity-only columns
      (let [entity-cols (filter #(= :entity (:level %)) columns)]
        (is (= 2 (count entity-cols))))

      ;; Check entity-tenant columns (each entity x each tenant)
      (let [entity-tenant-cols (filter #(= :entity-tenant (:level %)) columns)]
        (is (= 2 (count entity-tenant-cols))))

      ;; Check entity-tenant-env columns (each entity x each tenant x each env)
      (let [full-cols (filter #(= :entity-tenant-env (:level %)) columns)]
        (is (= 2 (count full-cols)))))))

(deftest test-generate-columns-empty-selections
  (testing "generate-columns with no selections produces minimal columns"
    ;; selected-entities is now a flat set (empty)
    (let [columns (ui/generate-columns #{} #{} #{})]
      ;; Should only have global
      (is (= 1 (count columns)))
      (is (= :global (:key (first columns)))))))

(deftest test-generate-columns-level-labels
  (testing "generate-columns assigns correct level keywords"
    ;; selected-entities is now a flat set
    (let [columns (ui/generate-columns #{"ka"} #{"prod"} #{"bot1"})]
      (is (every? #(contains? #{:global :environment :tenant :entity
                                :tenant-env :entity-env :entity-tenant :entity-tenant-env}
                              (:level %))
                  columns)))))

;; =============================================================================
;; Column Key Roundtrip Tests
;; =============================================================================

(deftest test-column-key-roundtrip
  (testing "Column keys generated by generate-columns can be parsed correctly"
    ;; selected-entities is now a flat set
    (let [columns (ui/generate-columns #{"ka"} #{"prod"} #{"bot1"})]
      (doseq [col columns]
        (let [[tenant env entity] (ui/parse-column-key (:key col))]
          ;; Verify the parsed values match the column's level
          (case (:level col)
            :global (do (is (nil? tenant)) (is (nil? env)) (is (nil? entity)))
            :environment (do (is (nil? tenant)) (is (some? env)) (is (nil? entity)))
            :tenant (do (is (some? tenant)) (is (nil? env)) (is (nil? entity)))
            :entity (do (is (nil? tenant)) (is (nil? env)) (is (some? entity)))
            :tenant-env (do (is (some? tenant)) (is (some? env)) (is (nil? entity)))
            :entity-env (do (is (nil? tenant)) (is (some? env)) (is (some? entity)))
            :entity-tenant (do (is (some? tenant)) (is (nil? env)) (is (some? entity)))
            :entity-tenant-env (do (is (some? tenant)) (is (some? env)) (is (some? entity)))))))))

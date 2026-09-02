(ns digdir.pipeline.integration-test
  "Integration tests for pipeline end-to-end workflows."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.pipeline.core :as pipeline]
            [digdir.pipeline.collections :as collections]
            [digdir.config.db :as config-db]
            [digdir.data.db :as db]
            [datahike.api :as d]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def test-master-key "test-key-for-encryption")

(defn setup-test-db []
  (let [cfg {:store {:backend :mem
                     :id (str "pipeline-integration-test-" (random-uuid))}}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (config-db/ensure-schema! conn)
      conn)))

(defn cleanup-test-db [conn]
  (d/release conn))

(defn with-test-db [f]
  (let [conn (setup-test-db)]
    (try
      (with-redefs [db/get-conn (constantly conn)]
        (f))
      (finally
        (cleanup-test-db conn)))))

(use-fixtures :each with-test-db)

;; =============================================================================
;; End-to-End Tests
;; =============================================================================

(deftest test-create-pipeline-with-collection-names
  (testing "Create pipeline and generate collection names"
    (let [conn (db/get-conn)]
      ;; Create pipeline with source config
      (pipeline/create-pipeline! conn
                                {:tenant "ka"
                                 :environment "prod"
                                 :pipeline-name "e2e-test"
                                 :properties {:name "E2E Test Pipeline"
                                             :source-type :kudos
                                             :chunk-strategy :semantic
                                             :chunk-minimum-length 100
                                             :chunk-maximum-length 2000
                                             :search-phrases-model "gpt-4o"
                                             :collection-prefix "e2e_"}
                                 :master-key test-master-key})

      ;; Get pipeline and generate collection names
      (let [db @conn
            p (pipeline/get-pipeline db "ka" "prod" "e2e-test" test-master-key)
            coll-names (collections/get-or-generate-collection-names p)]

        ;; Verify pipeline properties
        (is (= "E2E Test Pipeline" (:name p)))
        (is (= :kudos (:source-type p)))
        (is (= :semantic (:chunk-strategy p)))

        ;; Verify collection names were generated
        (is (clojure.string/starts-with? (:docs-collection coll-names) "e2e_documents_"))
        (is (clojure.string/starts-with? (:chunks-collection coll-names) "e2e_chunks_"))
        (is (clojure.string/starts-with? (:phrases-collection coll-names) "e2e_phrases_"))

        ;; Store collection names back to pipeline
        (collections/track-pipeline-collections! conn
                                                "ka"
                                                "prod"
                                                "e2e-test"
                                                coll-names
                                                test-master-key)

        ;; Verify they were stored
        (let [db @conn
              p-updated (pipeline/get-pipeline db "ka" "prod" "e2e-test" test-master-key)]
          (is (= (:docs-collection coll-names) (:docs-collection p-updated)))
          (is (= (:chunks-collection coll-names) (:chunks-collection p-updated)))
          (is (= (:phrases-collection coll-names) (:phrases-collection p-updated))))))))

(deftest test-pipeline-lifecycle
  (testing "Complete pipeline lifecycle"
    (let [conn (db/get-conn)]
      ;; 1. Create pipeline
      (let [pipeline-id (pipeline/create-pipeline! conn
                                                  {:tenant "ka"
                                                   :environment "prod"
                                                   :pipeline-name "lifecycle-test"
                                                   :properties {:name "Lifecycle Test"
                                                               :description "Testing full lifecycle"
                                                               :source-type :kudos
                                                               :chunk-strategy :semantic}
                                                   :master-key test-master-key})]
        (is (= "ka:prod:lifecycle-test" pipeline-id)))

      ;; 2. Verify it exists
      (is (= 1 (count (pipeline/list-pipelines @conn "ka" "prod"))))

      ;; 3. Update pipeline
      (pipeline/update-pipeline! conn
                                {:tenant "ka"
                                 :environment "prod"
                                 :pipeline-name "lifecycle-test"
                                 :properties {:description "Updated description"
                                             :chunk-minimum-length 200}
                                 :master-key test-master-key})

      ;; 4. Verify update
      (let [db @conn
            p (pipeline/get-pipeline db "ka" "prod" "lifecycle-test" test-master-key)]
        (is (= "Updated description" (:description p)))
        (is (= 200 (:chunk-minimum-length p)))
        ;; Original properties should still be there
        (is (= "Lifecycle Test" (:name p)))
        (is (= :kudos (:source-type p))))

      ;; 5. Duplicate pipeline
      (let [copy-id (pipeline/duplicate-pipeline! conn
                                                 {:tenant "ka"
                                                  :environment "prod"
                                                  :source-pipeline-name "lifecycle-test"
                                                  :new-pipeline-name "lifecycle-copy"
                                                  :master-key test-master-key})]
        (is (= "ka:prod:lifecycle-copy" copy-id)))

      ;; 6. Verify both exist
      (is (= 2 (count (pipeline/list-pipelines @conn "ka" "prod"))))

      ;; 7. Delete original
      (pipeline/soft-delete-pipeline! conn "ka" "prod" "lifecycle-test")

      ;; 8. Verify only copy remains
      (is (= 1 (count (pipeline/list-pipelines @conn "ka" "prod"))))

      ;; 9. Verify copy has correct properties
      (let [db @conn
            copy (pipeline/get-pipeline db "ka" "prod" "lifecycle-copy" test-master-key)]
        (is (= "Lifecycle Test" (:name copy)))
        (is (= "Updated description" (:description copy)))
        (is (= 200 (:chunk-minimum-length copy)))))))

(deftest test-multi-tenant-pipelines
  (testing "Pipelines across multiple tenants and environments"
    (let [conn (db/get-conn)]
      ;; Create pipelines in different tenants/environments
      (pipeline/create-pipeline! conn
                                {:tenant "ka"
                                 :environment "prod"
                                 :pipeline-name "pipeline-1"
                                 :properties {:name "KA Prod Pipeline"}
                                 :master-key test-master-key})

      (pipeline/create-pipeline! conn
                                {:tenant "ka"
                                 :environment "test"
                                 :pipeline-name "pipeline-1"
                                 :properties {:name "KA Test Pipeline"}
                                 :master-key test-master-key})

      (pipeline/create-pipeline! conn
                                {:tenant "altinn"
                                 :environment "prod"
                                 :pipeline-name "pipeline-1"
                                 :properties {:name "Altinn Prod Pipeline"}
                                 :master-key test-master-key})

      (let [db @conn]
        ;; Verify all three exist
        (is (= 3 (count (pipeline/list-pipelines db nil nil))))

        ;; Verify isolation by tenant
        (is (= 2 (count (pipeline/list-pipelines db "ka" nil))))
        (is (= 1 (count (pipeline/list-pipelines db "altinn" nil))))

        ;; Verify isolation by environment
        (is (= 1 (count (pipeline/list-pipelines db "ka" "prod"))))
        (is (= 1 (count (pipeline/list-pipelines db "ka" "test"))))

        ;; Verify each has correct properties
        (let [ka-prod (pipeline/get-pipeline db "ka" "prod" "pipeline-1" test-master-key)
              ka-test (pipeline/get-pipeline db "ka" "test" "pipeline-1" test-master-key)
              altinn-prod (pipeline/get-pipeline db "altinn" "prod" "pipeline-1" test-master-key)]

          (is (= "KA Prod Pipeline" (:name ka-prod)))
          (is (= "KA Test Pipeline" (:name ka-test)))
          (is (= "Altinn Prod Pipeline" (:name altinn-prod)))

          (is (= "ka:prod:pipeline-1" (:id ka-prod)))
          (is (= "ka:test:pipeline-1" (:id ka-test)))
          (is (= "altinn:prod:pipeline-1" (:id altinn-prod))))))))

(deftest test-config-inheritance-across-levels
  (testing "Config inheritance from global to tenant to environment to pipeline"
    (let [conn (db/get-conn)]
      ;; Set up definitions
      (config-db/upsert-definition! conn
                                   {:path "pipeline.chunks.minimum-length"
                                    :value-type :number})

      ;; Set global default
      (config-db/set-value! conn
                           {:tenant nil
                            :environment nil
                            :entity nil
                            :path "pipeline.chunks.minimum-length"
                            :value 100
                            :master-key test-master-key})

      ;; Set tenant-level override
      (config-db/set-value! conn
                           {:tenant "ka"
                            :environment nil
                            :entity nil
                            :path "pipeline.chunks.minimum-length"
                            :value 200
                            :master-key test-master-key})

      ;; Set environment-level override
      (config-db/set-value! conn
                           {:tenant "ka"
                            :environment "prod"
                            :entity nil
                            :path "pipeline.chunks.minimum-length"
                            :value 300
                            :master-key test-master-key})

      ;; Create pipeline without setting the value
      (pipeline/create-pipeline! conn
                                {:tenant "ka"
                                 :environment "prod"
                                 :pipeline-name "inherit-test"
                                 :properties {:name "Inheritance Test"}
                                 :master-key test-master-key})

      ;; Should inherit from most specific level (environment)
      (let [db @conn
            p (pipeline/get-pipeline db "ka" "prod" "inherit-test" test-master-key)]
        (is (= 300 (:chunk-minimum-length p))))

      ;; Create pipeline with explicit value
      (pipeline/create-pipeline! conn
                                {:tenant "ka"
                                 :environment "prod"
                                 :pipeline-name "explicit-test"
                                 :properties {:name "Explicit Test"
                                             :chunk-minimum-length 400}
                                 :master-key test-master-key})

      ;; Should use pipeline-specific value
      (let [db @conn
            p (pipeline/get-pipeline db "ka" "prod" "explicit-test" test-master-key)]
        (is (= 400 (:chunk-minimum-length p)))))))

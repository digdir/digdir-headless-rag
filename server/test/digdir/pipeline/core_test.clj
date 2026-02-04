(ns digdir.pipeline.core-test
  "Tests for pipeline CRUD operations."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.pipeline.core :as pipeline]
            [digdir.config.db :as config-db]
            [digdir.data.db :as db]
            [datahike.api :as d]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(def test-master-key "test-key-for-encryption")

(defn setup-test-db []
  (let [cfg {:store {:backend :mem
                     :id (str "pipeline-test-" (random-uuid))}}]
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
;; Pipeline ID Tests
;; =============================================================================

(deftest test-make-pipeline-id
  (testing "Pipeline ID generation"
    (is (= "ka:prod:main-pipeline"
           (pipeline/make-pipeline-id "ka" "prod" "main-pipeline")))

    (is (= "_:_:default-pipeline"
           (pipeline/make-pipeline-id nil nil "default-pipeline")))

    (is (= "ka:_:tenant-pipeline"
           (pipeline/make-pipeline-id "ka" nil "tenant-pipeline")))))

(deftest test-parse-pipeline-id
  (testing "Pipeline ID parsing"
    (is (= {:tenant "ka" :environment "prod" :pipeline-name "main-pipeline"}
           (pipeline/parse-pipeline-id "ka:prod:main-pipeline")))

    (is (= {:tenant nil :environment nil :pipeline-name "default-pipeline"}
           (pipeline/parse-pipeline-id "_:_:default-pipeline")))

    (is (= {:tenant "ka" :environment nil :pipeline-name "tenant-pipeline"}
           (pipeline/parse-pipeline-id "ka:_:tenant-pipeline")))))

;; =============================================================================
;; Pipeline CRUD Tests
;; =============================================================================

(deftest test-create-pipeline
  (testing "Create a new pipeline"
    (let [conn (db/get-conn)
          pipeline-id (pipeline/create-pipeline! conn
                                                {:tenant "ka"
                                                 :environment "prod"
                                                 :pipeline-name "test-pipeline"
                                                 :properties {:name "Test Pipeline"
                                                             :source-type :kudos
                                                             :chunk-strategy :semantic}
                                                 :master-key test-master-key})]

      (is (= "ka:prod:test-pipeline" pipeline-id))

      ;; Verify pipeline was created
      (let [db @conn
            p (pipeline/get-pipeline db "ka" "prod" "test-pipeline" test-master-key)]
        (is (not (nil? p)))
        (is (= "Test Pipeline" (:name p)))
        (is (= :kudos (:source-type p)))
        (is (= :semantic (:chunk-strategy p)))))))

(deftest test-get-pipeline
  (testing "Get pipeline by ID"
    (let [conn (db/get-conn)]
      ;; Create a pipeline
      (pipeline/create-pipeline! conn
                                {:tenant "ka"
                                 :environment "prod"
                                 :pipeline-name "get-test"
                                 :properties {:name "Get Test"
                                             :description "Test description"}
                                 :master-key test-master-key})

      ;; Get it back
      (let [db @conn
            p (pipeline/get-pipeline db "ka" "prod" "get-test" test-master-key)]
        (is (not (nil? p)))
        (is (= "Get Test" (:name p)))
        (is (= "Test description" (:description p)))
        (is (= "ka:prod:get-test" (:id p)))))))

(deftest test-update-pipeline
  (testing "Update pipeline properties"
    (let [conn (db/get-conn)]
      ;; Create a pipeline
      (pipeline/create-pipeline! conn
                                {:tenant "ka"
                                 :environment "prod"
                                 :pipeline-name "update-test"
                                 :properties {:name "Original Name"
                                             :source-type :kudos}
                                 :master-key test-master-key})

      ;; Update it
      (pipeline/update-pipeline! conn
                                {:tenant "ka"
                                 :environment "prod"
                                 :pipeline-name "update-test"
                                 :properties {:name "Updated Name"
                                             :description "New description"}
                                 :master-key test-master-key})

      ;; Verify update
      (let [db @conn
            p (pipeline/get-pipeline db "ka" "prod" "update-test" test-master-key)]
        (is (= "Updated Name" (:name p)))
        (is (= "New description" (:description p)))
        ;; Original properties should still be there
        (is (= :kudos (:source-type p)))))))

(deftest test-list-pipelines
  (testing "List pipelines with filters"
    (let [conn (db/get-conn)]
      ;; Create multiple pipelines
      (pipeline/create-pipeline! conn
                                {:tenant "ka"
                                 :environment "prod"
                                 :pipeline-name "pipeline-1"
                                 :properties {:name "Pipeline 1"}
                                 :master-key test-master-key})

      (pipeline/create-pipeline! conn
                                {:tenant "ka"
                                 :environment "test"
                                 :pipeline-name "pipeline-2"
                                 :properties {:name "Pipeline 2"}
                                 :master-key test-master-key})

      (pipeline/create-pipeline! conn
                                {:tenant "altinn"
                                 :environment "prod"
                                 :pipeline-name "pipeline-3"
                                 :properties {:name "Pipeline 3"}
                                 :master-key test-master-key})

      (let [db @conn]
        ;; List all pipelines
        (is (= 3 (count (pipeline/list-pipelines db nil nil))))

        ;; List by tenant
        (is (= 2 (count (pipeline/list-pipelines db "ka" nil))))
        (is (= 1 (count (pipeline/list-pipelines db "altinn" nil))))

        ;; List by tenant and environment
        (is (= 1 (count (pipeline/list-pipelines db "ka" "prod"))))
        (is (= 1 (count (pipeline/list-pipelines db "ka" "test"))))))))

(deftest test-soft-delete-pipeline
  (testing "Soft delete pipeline"
    (let [conn (db/get-conn)]
      ;; Create a pipeline
      (pipeline/create-pipeline! conn
                                {:tenant "ka"
                                 :environment "prod"
                                 :pipeline-name "delete-test"
                                 :properties {:name "Delete Test"}
                                 :master-key test-master-key})

      ;; Verify it exists
      (is (= 1 (count (pipeline/list-pipelines @conn "ka" "prod"))))

      ;; Soft delete it
      (let [deleted-count (pipeline/soft-delete-pipeline! conn "ka" "prod" "delete-test")]
        (is (> deleted-count 0)))

      ;; Verify it's no longer listed
      (is (= 0 (count (pipeline/list-pipelines @conn "ka" "prod")))))))

(deftest test-duplicate-pipeline
  (testing "Duplicate a pipeline"
    (let [conn (db/get-conn)]
      ;; Create original pipeline
      (pipeline/create-pipeline! conn
                                {:tenant "ka"
                                 :environment "prod"
                                 :pipeline-name "original"
                                 :properties {:name "Original Pipeline"
                                             :description "Original description"
                                             :source-type :kudos
                                             :chunk-strategy :semantic}
                                 :master-key test-master-key})

      ;; Duplicate it
      (let [new-id (pipeline/duplicate-pipeline! conn
                                                {:tenant "ka"
                                                 :environment "prod"
                                                 :source-pipeline-name "original"
                                                 :new-pipeline-name "copy"
                                                 :master-key test-master-key})]

        (is (= "ka:prod:copy" new-id))

        ;; Verify the copy
        (let [db @conn
              copy (pipeline/get-pipeline db "ka" "prod" "copy" test-master-key)]
          (is (not (nil? copy)))
          (is (= "Original Pipeline" (:name copy)))
          (is (= "Original description" (:description copy)))
          (is (= :kudos (:source-type copy)))
          (is (= :semantic (:chunk-strategy copy))))))))

;; =============================================================================
;; Property Inheritance Tests
;; =============================================================================

(deftest test-pipeline-inheritance
  (testing "Pipeline property inheritance"
    (let [conn (db/get-conn)]
      ;; Set global default
      (config-db/upsert-definition! conn
                                   {:path "pipeline.chunks.strategy"
                                    :value-type :edn})
      (config-db/set-value! conn
                           {:tenant nil
                            :environment nil
                            :entity nil
                            :path "pipeline.chunks.strategy"
                            :value :header-based
                            :master-key test-master-key})

      ;; Set tenant-level override
      (config-db/set-value! conn
                           {:tenant "ka"
                            :environment nil
                            :entity nil
                            :path "pipeline.chunks.strategy"
                            :value :semantic
                            :master-key test-master-key})

      ;; Create pipeline without setting chunk-strategy
      (pipeline/create-pipeline! conn
                                {:tenant "ka"
                                 :environment "prod"
                                 :pipeline-name "inherit-test"
                                 :properties {:name "Inherit Test"}
                                 :master-key test-master-key})

      ;; Should inherit tenant-level value
      (let [db @conn
            p (pipeline/get-pipeline db "ka" "prod" "inherit-test" test-master-key)]
        (is (= :semantic (:chunk-strategy p)))))))

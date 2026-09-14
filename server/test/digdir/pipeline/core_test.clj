(ns digdir.pipeline.core-test
  "Tests for pipeline CRUD operations."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.pipeline.core :as pipeline]
            [digdir.config.db :as config-db]
            [digdir.setup.config :as setup-config]
            [digdir.config.ops.bootstrap :as config-bootstrap]
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
        ;; #513: the API now requires what materialization requires, so the
        ;; test DB must know those config definitions. It previously got away
        ;; with a subset because the API asked for less than execution did.
        ;;
        ;; ⚠️ AND THE REDEF ABOVE IS NOT ENOUGH ON ITS OWN (yardarm-544errors).
        ;; `config-db/get-conn` returns `@!config-conn` FIRST and only falls
        ;; through to `data.db/get-conn` when that override is unset. Several
        ;; config test namespaces call `config-db/set-conn!`, and one that does
        ;; not restore it leaves the override set for the rest of the JVM — so
        ;; the seeding below silently writes to THAT connection and this test's
        ;; own conn never gets the definitions. The symptom is
        ;; "Config definition not found: pipeline.source.kudos.use-preprod" at
        ;; `set-node-value!`, and it appears ONLY in a full-suite run, which is
        ;; exactly why running these namespaces alone did not catch it.
        ;;
        ;; Setting it explicitly makes this fixture independent of what ran
        ;; before it, rather than of what it happens to run after.
        (let [previous (config-db/get-conn)]
          (try
            (config-db/set-conn! conn)
            (setup-config/ensure-pipeline-config-definitions!)
            (f)
            (finally
              (config-db/set-conn! (when-not (identical? previous conn) previous))))))
      (finally
        (cleanup-test-db conn)))))

(use-fixtures :each with-test-db)

(defn create-test-dataset!
  [conn dataset-id name]
  (pipeline/create-dataset! conn {:dataset-id dataset-id
                                  :name name}))

(defn seed-dataset-tree!
  [conn {:keys [tenant tenant-config-key dataset-id pipeline-name base-values leaf-values]}]
  (config-bootstrap/bootstrap-dataset-tree!
   conn
   {:tenant tenant
    :tenant-name tenant
    :dataset-id (or dataset-id pipeline-name)
    :pipeline-id pipeline-name
    :base-tenant-config-key (or tenant-config-key "default")
    :base-node-id (str "dataset/" tenant "/default")
    :materialization-node-id (str "dataset/" tenant "/" (or dataset-id pipeline-name) "/" (or tenant-config-key "_") "/" pipeline-name "/materialization")
    :materialization-label (str pipeline-name " Materialization")
    :materialization-tenant-config-key (config-db/default-dataset-tenant-config-key tenant-config-key pipeline-name)
    :base-values base-values
    :dataset-values leaf-values
    :master-key test-master-key}))

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
    (is (= {:tenant "ka" :tenant-config-key "prod" :pipeline-name "main-pipeline"}
           (pipeline/parse-pipeline-id "ka:prod:main-pipeline")))

    (is (= {:tenant nil :tenant-config-key nil :pipeline-name "default-pipeline"}
           (pipeline/parse-pipeline-id "_:_:default-pipeline")))

    (is (= {:tenant "ka" :tenant-config-key nil :pipeline-name "tenant-pipeline"}
           (pipeline/parse-pipeline-id "ka:_:tenant-pipeline")))))

;; =============================================================================
;; Pipeline CRUD Tests
;; =============================================================================

(deftest test-create-pipeline
  (testing "Create a new pipeline"
    (let [conn (db/get-conn)
          _ (create-test-dataset! conn "test-dataset" "Test Dataset")
          pipeline-id (pipeline/create-pipeline! conn
                                                {:tenant "ka"
                                                 :tenant-config-key "prod"
                                                 :dataset-id "test-dataset"
                                                 :pipeline-name "test-pipeline"
                                                 :properties {:name "Test Pipeline"
                                                             :source-type :kudos
                                                             :kudos-use-preprod false
                                                             :kudos-starting-page 1
                                                             :kudos-document-types ["rapport"]
                                                             :kudos-transducer :identity
                                                             :chunk-strategy :semantic}
                                                 :master-key test-master-key})]

      (is (= "ka:prod:test-pipeline" pipeline-id))

      ;; Verify pipeline was created
      (let [db @conn
            p (pipeline/get-dataset db "ka" "prod" "test-pipeline" test-master-key)]
        (is (not (nil? p)))
        (is (= "Test Pipeline" (:name p)))
        (is (= :kudos (:source-type p)))
        (is (= :semantic (:chunk-strategy p)))))))

(deftest test-get-pipeline
  (testing "Get pipeline by ID"
    (let [conn (db/get-conn)]
      ;; Create a pipeline
      (create-test-dataset! conn "get-test-dataset" "Get Test Dataset")
      (pipeline/create-pipeline! conn
                                {:tenant "ka"
                                 :tenant-config-key "prod"
                                 :dataset-id "get-test-dataset"
                                 :pipeline-name "get-test"
                                 :properties {:name "Get Test"
                                             :description "Test description"}
                                 :master-key test-master-key})

      ;; Get it back
      (let [db @conn
            p (pipeline/get-dataset db "ka" "prod" "get-test" test-master-key)]
        (is (not (nil? p)))
        (is (= "Get Test" (:name p)))
        (is (= "Test description" (:description p)))
        (is (= "ka:prod:get-test" (:id p)))))))

(deftest test-update-pipeline
  (testing "Update pipeline properties"
    (let [conn (db/get-conn)]
      ;; Create a pipeline
      (create-test-dataset! conn "update-test-dataset" "Update Test Dataset")
      (pipeline/create-pipeline! conn
                                {:tenant "ka"
                                 :tenant-config-key "prod"
                                 :dataset-id "update-test-dataset"
                                 :pipeline-name "update-test"
                                 :properties {:name "Original Name"
                                             :source-type :kudos}
                                 :master-key test-master-key})

      ;; Update it
      (pipeline/update-pipeline! conn
                                {:tenant "ka"
                                 :tenant-config-key "prod"
                                 :pipeline-name "update-test"
                                 :properties {:name "Updated Name"
                                             :description "New description"}
                                 :master-key test-master-key})

      ;; Verify update
      (let [db @conn
            p (pipeline/get-dataset db "ka" "prod" "update-test" test-master-key)]
        (is (= "Updated Name" (:name p)))
        (is (= "New description" (:description p)))
        ;; Original properties should still be there
        (is (= :kudos (:source-type p)))))))

(deftest test-list-pipelines
  (testing "List pipelines with filters"
    (let [conn (db/get-conn)]
      ;; Create multiple pipelines
      (create-test-dataset! conn "dataset-1" "Dataset 1")
      (create-test-dataset! conn "dataset-2" "Dataset 2")
      (create-test-dataset! conn "dataset-3" "Dataset 3")
      (pipeline/create-pipeline! conn
                                {:tenant "ka"
                                 :tenant-config-key "prod"
                                 :dataset-id "dataset-1"
                                 :pipeline-name "pipeline-1"
                                 :properties {:name "Pipeline 1"}
                                 :master-key test-master-key})

      (pipeline/create-pipeline! conn
                                {:tenant "ka"
                                 :tenant-config-key "test"
                                 :dataset-id "dataset-2"
                                 :pipeline-name "pipeline-2"
                                 :properties {:name "Pipeline 2"}
                                 :master-key test-master-key})

      (pipeline/create-pipeline! conn
                                {:tenant "altinn"
                                 :tenant-config-key "prod"
                                 :dataset-id "dataset-3"
                                 :pipeline-name "pipeline-3"
                                 :properties {:name "Pipeline 3"}
                                 :master-key test-master-key})

      (let [db @conn]
        ;; List all pipelines
        (is (= 3 (count (pipeline/list-datasets db nil nil))))

        ;; List by tenant
        (is (= 2 (count (pipeline/list-datasets db "ka" nil))))
        (is (= 1 (count (pipeline/list-datasets db "altinn" nil))))

        ;; List by tenant and environment
        (is (= 1 (count (pipeline/list-datasets db "ka" "prod"))))
        (is (= 1 (count (pipeline/list-datasets db "ka" "test"))))))))

(deftest test-soft-delete-pipeline
  (testing "Soft delete pipeline"
    (let [conn (db/get-conn)]
      ;; Create a pipeline
      (create-test-dataset! conn "delete-test-dataset" "Delete Test Dataset")
      (pipeline/create-pipeline! conn
                                {:tenant "ka"
                                 :tenant-config-key "prod"
                                 :dataset-id "delete-test-dataset"
                                 :pipeline-name "delete-test"
                                 :properties {:name "Delete Test"}
                                 :master-key test-master-key})

      ;; Verify it exists
      (is (= 1 (count (pipeline/list-datasets @conn "ka" "prod"))))

      ;; Soft delete it
      (let [deleted-count (pipeline/soft-delete-pipeline! conn "ka" "prod" "delete-test")]
        (is (> deleted-count 0)))

      ;; Verify it's no longer listed
      (is (= 0 (count (pipeline/list-datasets @conn "ka" "prod")))))))

(deftest test-duplicate-pipeline
  (testing "Duplicate a pipeline"
    (let [conn (db/get-conn)]
      ;; Create original pipeline
      (create-test-dataset! conn "original-dataset" "Original Dataset")
      (pipeline/create-pipeline! conn
                                {:tenant "ka"
                                 :tenant-config-key "prod"
                                 :dataset-id "original-dataset"
                                 :pipeline-name "original"
                                 :properties {:name "Original Pipeline"
                                             :description "Original description"
                                             :source-type :kudos
                                             :kudos-use-preprod false
                                             :kudos-starting-page 1
                                             :kudos-document-types ["rapport"]
                                             :kudos-transducer :identity
                                             :chunk-strategy :semantic}
                                 :master-key test-master-key})

      ;; Duplicate it
      (let [new-id (pipeline/duplicate-pipeline! conn
                                                {:tenant "ka"
                                                 :tenant-config-key "prod"
                                                 :source-pipeline-name "original"
                                                 :new-pipeline-name "copy"
                                                 :master-key test-master-key})]

        (is (= "ka:prod:copy" new-id))

        ;; Verify the copy
        (let [db @conn
              copy (pipeline/get-dataset db "ka" "prod" "copy" test-master-key)]
          (is (not (nil? copy)))
          (is (= "Original Pipeline" (:name copy)))
          (is (= "Original description" (:description copy)))
          (is (= :kudos (:source-type copy)))
          (is (= :semantic (:chunk-strategy copy))))))))

;; =============================================================================
;; Property Inheritance Tests
;; =============================================================================

(deftest test-pipeline-inheritance
  (testing "Pipeline property inheritance resolves through the Dataset V2 tree"
    (let [conn (db/get-conn)]
      (config-db/upsert-definition! conn
                                   {:path "pipeline.chunks.strategy"
                                    :root :dataset
                                    :value-type :edn})

      (create-test-dataset! conn "inherit-test-dataset" "Inherit Test Dataset")
      (pipeline/create-pipeline! conn
                                 {:tenant "ka"
                                  :tenant-config-key "prod"
                                  :dataset-id "inherit-test-dataset"
                                  :pipeline-name "inherit-test"
                                  :properties {:name "Inherit Test"}
                                  :master-key test-master-key})

      (seed-dataset-tree! conn
                          {:tenant "ka"
                           :tenant-config-key "prod"
                           :dataset-id "inherit-test-dataset"
                           :pipeline-name "inherit-test"
                           :base-values {"pipeline.chunks.strategy" :semantic}})

      (let [db @conn
            p (pipeline/get-dataset db "ka" "prod" "inherit-test" test-master-key)]
        (is (= :semantic (:chunk-strategy p)))))))

(deftest test-list-pipelines-includes-inherited-pipeline-scope
  (testing "Pipelines are discoverable through explicit materialization node tenant-config-keys"
    (let [conn (db/get-conn)]
      (create-test-dataset! conn "assistant-dataset" "Assistant Dataset")
      (pipeline/create-pipeline! conn
                                 {:tenant "altinn-docs"
                                  :tenant-config-key "dev"
                                  :dataset-id "assistant-dataset"
                                  :pipeline-name "assistant"
                                  :properties {:name "Assistant"
                                               :docs-collection "altinn_docs_collection"}
                                  :master-key test-master-key})

      (let [db @conn]
        (is (= ["assistant"]
               (pipeline/list-datasets db "altinn-docs" "dev")))
        (is (= ["assistant"]
               (pipeline/list-datasets db "altinn-docs" nil)))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"dataset config node not found"
             (pipeline/get-dataset db "altinn-docs" "prod" "assistant" test-master-key)))))))

(deftest test-create-pipeline-can-target-explicit-shared-dataset
  (testing "Multiple pipelines can materialize into one explicit dataset"
    (let [conn (db/get-conn)]
      (create-test-dataset! conn "public-docs" "Public Docs")
      (pipeline/create-pipeline! conn
                                 {:tenant "digdir"
                                  :tenant-config-key "dev"
                                  :dataset-id "public-docs"
                                  :pipeline-name "altinn-docs"
                                  :properties {:name "Altinn Docs"
                                               :source-type :website
                                               :website-sitemap-url "https://docs.altinn.studio/sitemap.xml"
                                                 :website-base-url "https://docs.altinn.studio"}
                                  :master-key test-master-key})
      (pipeline/create-pipeline! conn
                                 {:tenant "digdir"
                                  :tenant-config-key "dev"
                                  :dataset-id "public-docs"
                                  :pipeline-name "digdir-docs"
                                  :properties {:name "Digdir Docs"
                                               :source-type :website
                                               :website-sitemap-url "https://docs.digdir.no/sitemap.xml"
                                                 :website-base-url "https://docs.digdir.no"}
                                  :master-key test-master-key})

      (let [db @conn
            altinn-docs (pipeline/get-dataset db "digdir" "dev" "altinn-docs" test-master-key)
            digdir-docs (pipeline/get-dataset db "digdir" "dev" "digdir-docs" test-master-key)
            altinn-leaf (config-db/get-config-node db (config-db/dataset-materialization-node-id "digdir" "dev" "public-docs" "altinn-docs"))
            digdir-leaf (config-db/get-config-node db (config-db/dataset-materialization-node-id "digdir" "dev" "public-docs" "digdir-docs"))
            dataset-base (config-db/get-config-node db (config-db/dataset-base-node-id "digdir" "public-docs"))]
        (is (= "public-docs" (:dataset-id altinn-docs)))
        (is (= "public-docs" (:dataset-id digdir-docs)))
        (is (= "Altinn Docs" (:name altinn-docs)))
        (is (= "Digdir Docs" (:name digdir-docs)))
        (is (= "https://docs.altinn.studio/sitemap.xml" (:website-sitemap-url altinn-docs)))
        (is (= "https://docs.digdir.no/sitemap.xml" (:website-sitemap-url digdir-docs)))
        (is (= "dataset/digdir/public-docs/default" (:config.node/id dataset-base)))
        (is (= "dataset/digdir/public-docs/default"
               (get-in altinn-leaf [:config.node/parent :config.node/id])))
        (is (= "dataset/digdir/public-docs/default"
               (get-in digdir-leaf [:config.node/parent :config.node/id])))
        (is (not= (:config.node/id altinn-leaf) (:config.node/id digdir-leaf)))))))

(deftest test-create-pipeline-requires-existing-parent-dataset
  (testing "Creating a pipeline without a durable parent dataset fails"
    (let [conn (db/get-conn)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"dataset-id is required when creating a pipeline"
           (pipeline/create-pipeline! conn
                                      {:tenant "ka"
                                       :tenant-config-key "prod"
                                       :pipeline-name "missing-parent"
                                       :properties {:name "Missing Parent"}
                                       :master-key test-master-key})))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Dataset not found"
           (pipeline/create-pipeline! conn
                                      {:tenant "ka"
                                       :tenant-config-key "prod"
                                       :dataset-id "missing-dataset"
                                       :pipeline-name "missing-parent"
                                       :properties {:name "Missing Parent"}
                                       :master-key test-master-key}))))))

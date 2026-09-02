(ns digdir.pipeline.integration-test
  "Integration tests for pipeline end-to-end workflows."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.pipeline.core :as pipeline]
            [digdir.pipeline.collections :as collections]
            [digdir.config.db :as config-db]
            [digdir.config.ops.bootstrap :as config-bootstrap]
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
;; End-to-End Tests
;; =============================================================================

(deftest test-create-pipeline-with-collection-names
  (testing "Create pipeline and generate collection names"
    (let [conn (db/get-conn)]
      ;; Create pipeline with source config
      (create-test-dataset! conn "e2e-dataset" "E2E Dataset")
      (pipeline/create-pipeline! conn
                                {:tenant "ka"
                                 :tenant-config-key "prod"
                                 :dataset-id "e2e-dataset"
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
            p (pipeline/get-dataset db "ka" "prod" "e2e-test" test-master-key)
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
                                                "e2e-dataset"
                                                "e2e-test"
                                                coll-names
                                                test-master-key)

        ;; Verify they were stored
        (let [db @conn
              p-updated (pipeline/get-dataset db "ka" "prod" "e2e-test" test-master-key)]
          (is (= (:docs-collection coll-names) (:docs-collection p-updated)))
          (is (= (:chunks-collection coll-names) (:chunks-collection p-updated)))
          (is (= (:phrases-collection coll-names) (:phrases-collection p-updated))))))))

(deftest test-pipeline-lifecycle
  (testing "Complete pipeline lifecycle"
    (let [conn (db/get-conn)]
      ;; 1. Create pipeline
      (create-test-dataset! conn "lifecycle-dataset" "Lifecycle Dataset")
      (let [pipeline-id (pipeline/create-pipeline! conn
                                                  {:tenant "ka"
                                                   :tenant-config-key "prod"
                                                   :dataset-id "lifecycle-dataset"
                                                   :pipeline-name "lifecycle-test"
                                                   :properties {:name "Lifecycle Test"
                                                               :description "Testing full lifecycle"
                                                               :source-type :kudos
                                                               :chunk-strategy :semantic}
                                                   :master-key test-master-key})]
        (is (= "ka:prod:lifecycle-test" pipeline-id)))

      ;; 2. Verify it exists
      (is (= 1 (count (pipeline/list-datasets @conn "ka" "prod"))))

      ;; 3. Update pipeline
      (pipeline/update-pipeline! conn
                                {:tenant "ka"
                                 :tenant-config-key "prod"
                                 :pipeline-name "lifecycle-test"
                                 :properties {:description "Updated description"
                                             :chunk-minimum-length 200}
                                 :master-key test-master-key})

      ;; 4. Verify update
      (let [db @conn
            p (pipeline/get-dataset db "ka" "prod" "lifecycle-test" test-master-key)]
        (is (= "Updated description" (:description p)))
        (is (= 200 (:chunk-minimum-length p)))
        ;; Original properties should still be there
        (is (= "Lifecycle Test" (:name p)))
        (is (= :kudos (:source-type p))))

      ;; 5. Duplicate pipeline
      (let [copy-id (pipeline/duplicate-pipeline! conn
                                                 {:tenant "ka"
                                                  :tenant-config-key "prod"
                                                  :source-pipeline-name "lifecycle-test"
                                                  :new-pipeline-name "lifecycle-copy"
                                                  :master-key test-master-key})]
        (is (= "ka:prod:lifecycle-copy" copy-id)))

      ;; 6. Verify both exist
      (is (= 2 (count (pipeline/list-datasets @conn "ka" "prod"))))

      ;; 7. Delete original
      (pipeline/soft-delete-pipeline! conn "ka" "prod" "lifecycle-test")

      ;; 8. Verify only copy remains
      (is (= 1 (count (pipeline/list-datasets @conn "ka" "prod"))))

      ;; 9. Verify copy has correct properties
      (let [db @conn
            copy (pipeline/get-dataset db "ka" "prod" "lifecycle-copy" test-master-key)]
        (is (= "Lifecycle Test" (:name copy)))
        (is (= "Updated description" (:description copy)))
        (is (= 200 (:chunk-minimum-length copy)))))))

(deftest test-multi-tenant-pipelines
  (testing "Pipelines use globally unique IDs with tenant-local dataset trees"
    (let [conn (db/get-conn)]
      (create-test-dataset! conn "ka-prod-dataset" "KA Prod Dataset")
      (create-test-dataset! conn "ka-test-dataset" "KA Test Dataset")
      (create-test-dataset! conn "altinn-prod-dataset" "Altinn Prod Dataset")
      (pipeline/create-pipeline! conn
                                 {:tenant "ka"
                                  :tenant-config-key "prod"
                                  :dataset-id "ka-prod-dataset"
                                  :pipeline-name "ka-prod-pipeline"
                                  :properties {:name "KA Prod Pipeline"}
                                  :master-key test-master-key})

      (pipeline/create-pipeline! conn
                                 {:tenant "ka"
                                  :tenant-config-key "test"
                                  :dataset-id "ka-test-dataset"
                                  :pipeline-name "ka-test-pipeline"
                                  :properties {:name "KA Test Pipeline"}
                                  :master-key test-master-key})

      (pipeline/create-pipeline! conn
                                 {:tenant "altinn"
                                  :tenant-config-key "prod"
                                  :dataset-id "altinn-prod-dataset"
                                  :pipeline-name "altinn-prod-pipeline"
                                  :properties {:name "Altinn Prod Pipeline"}
                                  :master-key test-master-key})

      (let [db @conn]
        (is (= 3 (count (pipeline/list-datasets db nil nil))))

        (is (= 2 (count (pipeline/list-datasets db "ka" nil))))
        (is (= 1 (count (pipeline/list-datasets db "altinn" nil))))

        (is (= 1 (count (pipeline/list-datasets db "ka" "prod"))))
        (is (= 1 (count (pipeline/list-datasets db "ka" "test"))))

        (let [ka-prod (pipeline/get-dataset db "ka" "prod" "ka-prod-pipeline" test-master-key)
              ka-test (pipeline/get-dataset db "ka" "test" "ka-test-pipeline" test-master-key)
              altinn-prod (pipeline/get-dataset db "altinn" "prod" "altinn-prod-pipeline" test-master-key)]

          (is (= "KA Prod Pipeline" (:name ka-prod)))
          (is (= "KA Test Pipeline" (:name ka-test)))
          (is (= "Altinn Prod Pipeline" (:name altinn-prod)))

          (is (= "ka:prod:ka-prod-pipeline" (:id ka-prod)))
          (is (= "ka:test:ka-test-pipeline" (:id ka-test)))
          (is (= "altinn:prod:altinn-prod-pipeline" (:id altinn-prod))))))))

(deftest test-config-inheritance-across-levels
  (testing "Dataset config inheritance resolves from base node to materialization leaf"
    (let [conn (db/get-conn)]
      (config-db/upsert-definition! conn
                                   {:path "pipeline.chunks.minimum-length"
                                    :root :dataset
                                    :value-type :number})

      (create-test-dataset! conn "inherit-test-dataset" "Inheritance Dataset")
      (pipeline/create-pipeline! conn
                                 {:tenant "ka"
                                  :tenant-config-key "prod"
                                  :dataset-id "inherit-test-dataset"
                                  :pipeline-name "inherit-test"
                                  :properties {:name "Inheritance Test"}
                                  :master-key test-master-key})

      (seed-dataset-tree! conn
                          {:tenant "ka"
                           :tenant-config-key "prod"
                           :dataset-id "inherit-test-dataset"
                           :pipeline-name "inherit-test"
                           :base-values {"pipeline.chunks.minimum-length" 200}
                           :leaf-values {"pipeline.chunks.minimum-length" 300}})

      (let [db @conn
            p (pipeline/get-dataset db "ka" "prod" "inherit-test" test-master-key)]
        (is (= 300 (:chunk-minimum-length p))))

      (create-test-dataset! conn "explicit-test-dataset" "Explicit Dataset")
      (pipeline/create-pipeline! conn
                                 {:tenant "ka"
                                  :tenant-config-key "prod"
                                  :dataset-id "explicit-test-dataset"
                                  :pipeline-name "explicit-test"
                                  :properties {:name "Explicit Test"
                                               :chunk-minimum-length 400}
                                  :master-key test-master-key})

      (let [db @conn
            p (pipeline/get-dataset db "ka" "prod" "explicit-test" test-master-key)]
        (is (= 400 (:chunk-minimum-length p)))))))

(deftest test-track-pipeline-collections-uses-explicit-dataset-id
  (testing "Collection tracking writes back under the shared dataset base"
    (let [conn (db/get-conn)]
      (create-test-dataset! conn "public-docs" "Public Docs")
      (pipeline/create-pipeline! conn
                                 {:tenant "digdir"
                                  :tenant-config-key "dev"
                                  :dataset-id "public-docs"
                                  :pipeline-name "altinn-docs"
                                  :properties {:name "Altinn Docs"
                                               :source-type :website
                                               :website-sitemap-url "https://docs.altinn.studio/sitemap.xml"}
                                  :master-key test-master-key})
      (pipeline/create-pipeline! conn
                                 {:tenant "digdir"
                                  :tenant-config-key "dev"
                                  :dataset-id "public-docs"
                                  :pipeline-name "digdir-docs"
                                  :properties {:name "Digdir Docs"
                                               :source-type :website
                                               :website-sitemap-url "https://docs.digdir.no/sitemap.xml"}
                                  :master-key test-master-key})

      (collections/track-pipeline-collections! conn
                                               "digdir"
                                               "dev"
                                               "public-docs"
                                               "altinn-docs"
                                               {:docs-collection "public_docs_documents_hash"
                                                :chunks-collection "public_docs_chunks_hash"
                                               :phrases-collection "public_docs_phrases_hash"}
                                               test-master-key)

      (let [db @conn
            altinn-pipeline (pipeline/get-dataset db "digdir" "dev" "altinn-docs" test-master-key)
            digdir-pipeline (pipeline/get-dataset db "digdir" "dev" "digdir-docs" test-master-key)
            dataset-base (config-db/get-config-node db (config-db/dataset-base-node-id "digdir" "public-docs"))
            pipeline-leaf (config-db/get-config-node db (config-db/dataset-materialization-node-id "digdir" "dev" "public-docs" "altinn-docs"))]
        (is (= "public_docs_documents_hash" (:docs-collection altinn-pipeline)))
        (is (= "public_docs_documents_hash" (:docs-collection digdir-pipeline)))
        (is (= "dataset/digdir/public-docs/default" (:config.node/id dataset-base)))
        (is (= "dataset/digdir/public-docs/default"
               (get-in pipeline-leaf [:config.node/parent :config.node/id])))))))

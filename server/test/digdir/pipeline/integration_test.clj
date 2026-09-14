(ns digdir.pipeline.integration-test
  "Integration tests for pipeline end-to-end workflows."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [digdir.pipeline.core :as pipeline]
            [digdir.pipeline.collections :as collections]
            [digdir.pipeline.executor :as executor]
            [digdir.config.db :as config-db]
            [digdir.setup.config :as setup-config]
            [digdir.config.ops.bootstrap :as config-bootstrap]
            [digdir.data.db :as db]
            [missionary.core :as m]
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
                                             :kudos-use-preprod false
                                             :kudos-starting-page 1
                                             :kudos-document-types ["rapport"]
                                             :kudos-transducer :identity
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
                                                               :kudos-use-preprod false
                                                               :kudos-starting-page 1
                                                               :kudos-document-types ["rapport"]
                                                               :kudos-transducer :identity
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

(def folder-materialization-properties
  "A complete materialization contract for a `:folder` dataset.

   EVERY key is required: `required-execution-properties` derives the set from
   the loader key maps and materialization throws on any that is missing. Shaped
   after `digdir.setup.demo-dataset/dataset-values`, so this is the config a
   seeded tenant actually gets rather than a minimal stub."
  {:name "Altinn Docs"
   :source-type :folder
   :folder-path "./demo-corpus"
   :document-limit 5000
   :document-offset 0
   :chunk-strategy :header-based
   :chunk-minimum-length 333
   :chunk-maximum-length 256000
   :search-phrases-model "gpt-4o"
   :search-phrases-fallback :google/gemma-3-27b-it
   :search-phrases-prompt "REPLACE_ME"
   :collection-prefix "altinn_"
   :parallelism-documents 3
   :parallelism-store 1
   :max-document-failures 10})

(defn- node-id-holding
  "The config node a value at `path` was written to, or nil."
  [db path]
  (d/q '[:find ?nid .
         :in $ ?path
         :where
         [?v :config.value/definition ?d]
         [?d :config-def/path ?path]
         [?v :config.value/node ?n]
         [?n :config.node/id ?nid]]
       db path))

(deftest test-execute-pipeline-persists-collections-under-the-durable-dataset-id
  (testing "a materialization that succeeds records its collection names on the dataset base node"
    (let [conn (db/get-conn)]
      (create-test-dataset! conn "public-docs" "Public Docs")
      (pipeline/create-pipeline! conn
                                 {:tenant "digdir"
                                  :tenant-config-key "default"
                                  :dataset-id "public-docs"
                                  :pipeline-name "altinn-docs"
                                  :properties folder-materialization-properties
                                  :master-key test-master-key})

      ;; ⚠️ THE DISTINCTION THIS TEST EXISTS FOR. The resolved config carries TWO
      ;; ids and only one of them keys the dataset tree. `pipeline/get-dataset`
      ;; stamps `:id` with `make-pipeline-id`, a COMPOSITE of tenant, config key
      ;; and pipeline name; the durable dataset id is a separate key. Reading
      ;; `:id` as a dataset id builds a base-node id that names nothing, which is
      ;; asserted here rather than described, because it is the whole defect.
      (let [dataset-config (pipeline/get-dataset @conn "digdir" "default" "altinn-docs" test-master-key)]
        (is (= "digdir:default:altinn-docs" (:id dataset-config)))
        (is (= "public-docs" (:dataset-id dataset-config)))
        (is (nil? (config-db/get-config-node
                   @conn (config-db/dataset-base-node-id "digdir" (:id dataset-config)))))
        (is (some? (config-db/get-config-node
                    @conn (config-db/dataset-base-node-id "digdir" (:dataset-id dataset-config))))))

      ;; Only the loader is stubbed. What is under test is the identity the
      ;; executor hands to collection tracking AFTER a run succeeds — not the
      ;; ingest, which is exactly the half that was already working when this
      ;; failed on a real corpus.
      (with-redefs [executor/dispatch-to-loader (fn [_ _] (m/sp nil))]
        (m/? (executor/execute-pipeline! conn "digdir" "default" "altinn-docs"
                                         test-master-key "test-user")))

      (let [db @conn
            resolved (pipeline/get-dataset db "digdir" "default" "altinn-docs" test-master-key)
            execution (first (executor/list-executions db "digdir:default:altinn-docs"))]
        (is (= :completed (:pipeline-execution/status execution))
            "the run was marked failed by the persist step, on an ingest that had succeeded")
        (is (str/starts-with? (str (:docs-collection resolved)) "altinn_documents_"))
        (is (str/starts-with? (str (:chunks-collection resolved)) "altinn_chunks_"))
        (is (str/starts-with? (str (:phrases-collection resolved)) "altinn_phrases_"))
        (is (= "dataset/digdir/public-docs/default"
               (node-id-holding db "pipeline.storage.chunks-collection")))))))

(deftest test-collection-names-are-durable-before-the-run-is-marked-completed
  (testing "a run is only :completed once its collection names are recorded"
    (let [conn (db/get-conn)]
      (create-test-dataset! conn "public-docs" "Public Docs")
      (pipeline/create-pipeline! conn
                                 {:tenant "digdir"
                                  :tenant-config-key "default"
                                  :dataset-id "public-docs"
                                  :pipeline-name "altinn-docs"
                                  :properties folder-materialization-properties
                                  :master-key test-master-key})

      ;; ⚠️ WHY ORDER IS TESTED AND NOT JUST THE END STATE. Marking :completed
      ;; before persisting publishes "this run succeeded" while the config still
      ;; names whatever a previous computation guessed. A process that dies in
      ;; that window leaves it there FOREVER, because nothing revisits a run
      ;; already recorded as completed — observed on a real corpus, where a run
      ;; killed between the two statements kept three collection names that no
      ;; collection has ever answered to. The end state alone cannot see this;
      ;; only the order can.
      (let [names-when-marked (atom ::never-marked)
            real-update! executor/update-execution-status!]
        (with-redefs [executor/dispatch-to-loader (fn [_ _] (m/sp nil))
                      executor/update-execution-status!
                      (fn [c eid status opts]
                        (when (= :completed status)
                          (reset! names-when-marked
                                  (:chunks-collection
                                   (pipeline/get-dataset @c "digdir" "default"
                                                         "altinn-docs" test-master-key))))
                        (real-update! c eid status opts))]
          (m/? (executor/execute-pipeline! conn "digdir" "default" "altinn-docs"
                                           test-master-key "test-user")))

        (is (not= ::never-marked @names-when-marked)
            "the run never reached :completed")
        (is (some? @names-when-marked)
            "the run was marked :completed while no collection name was recorded")
        (is (str/starts-with? (str @names-when-marked) "altinn_chunks_")
            "the name recorded at :completed must be the one this run created")
        (is (= @names-when-marked
               (:chunks-collection (pipeline/get-dataset @conn "digdir" "default"
                                                         "altinn-docs" test-master-key)))
            "the name visible at :completed must be the final one")))))

;; =============================================================================
;; #509 — two conflated identities, reachable only with a SECOND dataset
;; =============================================================================
;;
;; ⚠️ THESE ARE THE TESTS THAT MAKE THE DEFECTS REACHABLE. Both hide completely
;; while a tenant has exactly one dataset, which is every tenant we ship — so
;; the fixture's whole job is to be the second dataset. A latent defect fixed
;; without ever seeing it fire is a claim, not a result.

(deftest test-509-five-arity-refuses-instead-of-inventing-a-dataset-id
  (testing "the 5-arity must not supply pipeline-name where dataset-id belongs"
    (let [conn (db/get-conn)]
      (create-test-dataset! conn "public-docs" "Public Docs")
      (pipeline/create-pipeline! conn
                                 {:tenant "digdir" :tenant-config-key "default"
                                  :dataset-id "public-docs" :pipeline-name "altinn-docs"
                                  :properties {:name "Altinn Docs" :source-type :website
                                               :website-sitemap-url "https://docs.altinn.studio/sitemap.xml"}
                                  :master-key test-master-key})
      ;; BEFORE: resolved a base node from a PIPELINE name and threw the
      ;; misleading "Dataset base node not found". AFTER: it names the mistake.
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"needs an explicit dataset-id"
           (collections/track-pipeline-collections!
            conn "digdir" "default" "altinn-docs"
            {:docs-collection "d" :chunks-collection "c" :phrases-collection "p"}
            test-master-key))))))

(defn- two-dataset-tenant!
  "A tenant with TWO datasets, each with its own pipeline — the configuration
   #509's silent defect needs, and the one `test-multi-tenant-pipelines` shows
   is legitimate (tenant `ka` runs prod and test)."
  [conn]
  (create-test-dataset! conn "public-docs" "Public Docs")
  (create-test-dataset! conn "other-docs" "Other Docs")
  (pipeline/create-pipeline! conn
                             {:tenant "digdir" :tenant-config-key "default"
                              :dataset-id "public-docs" :pipeline-name "altinn-docs"
                              :properties {:name "Altinn Docs" :source-type :website
                                           :website-sitemap-url "https://docs.altinn.studio/sitemap.xml"}
                              :master-key test-master-key})
  (pipeline/create-pipeline! conn
                             {:tenant "digdir" :tenant-config-key "default"
                              :dataset-id "other-docs" :pipeline-name "other-pipeline"
                              :properties {:name "Other Docs" :source-type :website
                                           :website-sitemap-url "https://other.example/sitemap.xml"}
                              :master-key test-master-key}))

(deftest test-509-second-dataset-does-not-read-the-first-datasets-corpus
  (testing "collection names recorded for one dataset are invisible from the other"
    (let [conn (db/get-conn)]
      (two-dataset-tenant! conn)
      (collections/track-pipeline-collections!
       conn "digdir" "default" "public-docs" "altinn-docs"
       {:docs-collection "public_documents" :chunks-collection "public_chunks"
        :phrases-collection "public_phrases"}
       test-master-key)
      (let [db @conn
            other (pipeline/get-dataset db "digdir" "default" "other-pipeline" test-master-key)]
        ;; ⚠️ THE ASSERTION WORTH KEEPING. This fails by returning the WRONG
        ;; CORPUS, not by throwing — which is exactly why nothing else would
        ;; notice a regression here. Before the fix this read "public_chunks".
        (is (not= "public_chunks" (:chunks-collection other))
            "second dataset resolves the FIRST dataset's chunks collection")))))

(deftest test-509-each-dataset-gets-its-own-base-node
  (testing "each dataset's materialization tree hangs off its own base node"
    (let [conn (db/get-conn)]
      (two-dataset-tenant! conn)
      (let [db @conn
            first-base  (config-db/dataset-base-node-id "digdir" "public-docs")
            second-base (config-db/dataset-base-node-id "digdir" "other-docs")]
        (is (some? (config-db/get-config-node db first-base)))
        (is (some? (config-db/get-config-node db second-base)))
        (doseq [[base dataset-id pipeline] [[first-base "public-docs" "altinn-docs"]
                                            [second-base "other-docs" "other-pipeline"]]]
          (is (= base (get-in (config-db/get-config-node
                               db (config-db/dataset-materialization-node-id
                                   "digdir" "default" dataset-id pipeline))
                              [:config.node/parent :config.node/id]))
              (str pipeline " is parented under the wrong dataset's base node")))))))

(deftest test-509-tenant-still-has-a-dataset-root-under-default
  (testing "the tenant's dataset ROOT still resolves by (tenant, :dataset, \"default\")"
    (let [conn (db/get-conn)]
      (two-dataset-tenant! conn)
      ;; ⚠️ WHY THIS GUARD EXISTS. A sweep for `:dataset` + "default" found four
      ;; paths that resolve a tenant's dataset root by that exact key — including
      ;; `authorize-dataset-materialization-request!`, which 404s without it. If
      ;; a later change keys EVERY dataset by its id, those break for every
      ;; tenant created afterwards, starting with its first dataset. This is the
      ;; test that says so out loud.
      (is (some? (config-db/get-config-node-by-tenant-config-key
                  @conn "digdir" :dataset "default"))
          "tenant has no dataset root under \"default\" — four call sites 404 on this"))))

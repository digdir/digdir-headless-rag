(ns digdir.config.ops-test
  "Tests for V2 config export/import and bootstrap operations."
  (:require [clojure.test :refer [deftest testing is]]
            [datahike.api :as d]
            [digdir.agents.db :as agents-db]
            [digdir.data.db :as data-db]
            [digdir.config.schema :as schema]
            [digdir.config.db :as config-db]
            [digdir.config.ops.bootstrap :as ops-bootstrap]
            [digdir.config.ops.clone :as ops-clone]
            [digdir.config.ops.materialization :as ops-materialization]
            [digdir.config.ops.retirement :as ops-retirement]
            [digdir.config.ops.sync :as ops-sync]
            [digdir.config.ops.topology :as ops-topology]
            [digdir.config.structure :as structure]))

(defn create-test-db
  []
  (let [cfg {:store {:backend :mem
                     :id (str "ops-test-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data schema/config-migration-schema})
      conn)))

(defn delete-test-db
  [conn]
  (let [cfg (:config @(:wrapped-atom conn))]
    (d/release conn)
    (d/delete-database cfg)))

(defn seed-runtime-definitions!
  [conn]
  (config-db/upsert-definition! conn
                                {:path "skills.rerank.top-k"
                                 :root :runtime
                                 :value-type :number
                                 :encrypted? false
                                 :category :skills
                                 :service :search
                                 :sensitivity :internal
                                 :function :settings})
  (config-db/upsert-definition! conn
                                {:path "skills.query-planner.prompt"
                                 :root :runtime
                                 :value-type :string
                                 :encrypted? false
                                 :category :skills
                                 :service :llm
                                 :sensitivity :internal
                                 :function :prompts}))

(defn seed-platform-definitions!
  [conn]
  (config-db/upsert-definition! conn
                                {:path "services.auth.session-max-age"
                                 :root :platform
                                 :value-type :number
                                 :encrypted? false
                                 :category :services
                                 :service :auth
                                 :sensitivity :internal
                                 :function :settings})
  (config-db/upsert-definition! conn
                                {:path "features.playground.enabled"
                                 :root :platform
                                 :value-type :boolean
                                 :encrypted? false
                                 :category :features
                                 :service :other
                                 :sensitivity :internal
                                 :function :features}))

(defn seed-dataset-definitions!
  [conn]
  (config-db/upsert-definition! conn
                                {:path "pipeline.chunking.size"
                                 :root :dataset
                                 :value-type :number
                                 :encrypted? false
                                 :category :pipelines
                                 :service :search
                                 :sensitivity :internal
                                 :function :settings})
  (config-db/upsert-definition! conn
                                {:path "pipeline.indexing.enabled"
                                 :root :dataset
                                 :value-type :boolean
                                 :encrypted? false
                                 :category :pipelines
                                 :service :search
                                 :sensitivity :internal
                                 :function :settings}))

(defn seed-deployment-polish-definitions!
  [conn]
  (seed-platform-definitions! conn)
  (seed-runtime-definitions! conn)
  (doseq [[path value-type] [["pipeline.ui.name" :string]
                             ["pipeline.source.type" :edn]
                             ["pipeline.source.website.sitemap-url" :string]
                             ["pipeline.source.website.base-url" :string]
                             ["pipeline.source.kudos.use-preprod" :boolean]
                             ["pipeline.source.kudos.starting-page" :number]
                             ["pipeline.documents.limit" :number]
                             ["pipeline.documents.offset" :number]
                             ["pipeline.chunks.strategy" :edn]
                             ["pipeline.chunks.minimum-length" :number]
                             ["pipeline.chunks.maximum-length" :number]
                             ["pipeline.search-phrases.model" :string]
                             ["pipeline.search-phrases.fallback-model" :edn]
                             ["pipeline.search-phrases.prompt" :string]
                             ["pipeline.storage.collection-prefix" :string]
                             ["pipeline.storage.docs-collection" :string]
                             ["pipeline.storage.chunks-collection" :string]
                             ["pipeline.storage.phrases-collection" :string]
                             ["pipeline.operations.parallelism-documents" :number]
                             ["pipeline.operations.parallelism-store" :number]
                             ["pipeline.operations.max-document-failures" :number]]]
    (config-db/upsert-definition! conn
                                  {:path path
                                   :root :dataset
                                   :value-type value-type
                                   :encrypted? false
                                   :category :pipelines
                                   :service :search
                                   :sensitivity :internal
                                   :function :settings})))

(defn seed-platform-tree-for-export!
  [conn]
  (seed-platform-definitions! conn)
  (ops-bootstrap/bootstrap-config-tree! conn
                              {:root :platform
                               :tenant "test-tenant"
                               :base-values {"services.auth.session-max-age" 3600}
                               :leaf-values {"features.playground.enabled" true}}))

(deftest test-bootstrap-runtime-tree-creates-runtime-nodes-bindings-and-values
  (let [conn (create-test-db)]
    (try
      (seed-runtime-definitions! conn)
      (let [{:keys [base-node runtime-node bindings value-actions]}
            (ops-bootstrap/bootstrap-runtime-tree! conn
                                         {:tenant "ka"
                                          :agent-id "research-assistant"
                                          :dataset-id "kudos"
                                          :base-values {:rerank-top-k 24}
                                          :runtime-values {:query-planner-prompt "Please answer precisely."}})
            runtime-node-id (:config.node/id runtime-node)
            rerank-resolution (config-db/resolve-node-value-with-trace @conn
                                                                      :runtime
                                                                      "ka"
                                                                      runtime-node-id
                                                                      "skills.rerank.top-k")
            prompt-resolution (config-db/resolve-node-value-with-trace @conn
                                                                      :runtime
                                                                      "ka"
                                                                      runtime-node-id
                                                                      "skills.query-planner.prompt")]
        (is (= "runtime/ka/default" (:config.node/id base-node)))
        (is (= "runtime/ka/default-runtime" runtime-node-id))
        (is (empty? bindings))
        (is (= :created (get-in value-actions [:base "skills.rerank.top-k"])))
        (is (= :created (get-in value-actions [:runtime "skills.query-planner.prompt"])))
        (is (= "24" (get-in rerank-resolution [:value :config.value/raw])))
        (is (= "Please answer precisely."
               (get-in prompt-resolution [:value :config.value/raw]))))
      (finally
        (delete-test-db conn)))))

(deftest test-bootstrap-runtime-tree-supports_explicit_single_node_mode
  (let [conn (create-test-db)]
    (try
      (seed-runtime-definitions! conn)
      (let [{:keys [base-node runtime-node bindings compatibilities value-actions]}
            (ops-bootstrap/bootstrap-runtime-tree! conn
                                         {:tenant "ka"
                                          :single-node? true
                                          :agent-id "research-assistant"
                                          :dataset-id "kudos"
                                          :runtime-values {:rerank-top-k 24}})
            selected-node (config-db/resolve-runtime-node! @conn
                                                           {:tenant "ka"
                                                            :tenant-config-key "default"
                                                            :agent-id "research-assistant"
                                                            :dataset-id "kudos"})
            rerank-resolution (config-db/resolve-node-value-with-trace @conn
                                                                      :runtime
                                                                      "ka"
                                                                      (:config.node/id selected-node)
                                                                      "skills.rerank.top-k")]
        (is (= "runtime/ka/default" (:config.node/id base-node)))
        (is (= "runtime/ka/default" (:config.node/id runtime-node)))
        (is (empty? bindings))
        (is (empty? compatibilities))
        (is (= :created (get-in value-actions [:runtime "skills.rerank.top-k"])))
        (is (= "24" (get-in rerank-resolution [:value :config.value/raw]))))
      (finally
        (delete-test-db conn)))))

(deftest test-bootstrap-config-tree-supports-platform-root-through-generic-api
  (let [conn (create-test-db)]
    (try
      (seed-platform-definitions! conn)
      (let [{:keys [base-node leaf-node bindings value-actions]}
            (ops-bootstrap/bootstrap-config-tree! conn
                                        {:root :platform
                                         :tenant "ka"
                                         :base-values {"services.auth.session-max-age" 86400}
                                         :leaf-values {"features.playground.enabled" true}})            leaf-node-id (:config.node/id leaf-node)
            session-resolution (config-db/resolve-node-value-with-trace @conn
                                                                       :platform
                                                                       "ka"
                                                                       leaf-node-id
                                                                       "services.auth.session-max-age")]
        (is (= "platform/ka/default" (:config.node/id base-node)))
        (is (= "platform/ka/prod" leaf-node-id))
        (is (= [] bindings))
        (is (= :created (get-in value-actions [:base "services.auth.session-max-age"])))
        (is (= "86400" (get-in session-resolution [:value :config.value/raw]))))
      (finally
        (delete-test-db conn)))))

(deftest test-bootstrap-config-tree-supports_explicit_single_node_platform_mode
  (let [conn (create-test-db)]
    (try
      (seed-platform-definitions! conn)
      (let [{:keys [base-node leaf-node bindings value-actions]}
            (ops-bootstrap/bootstrap-config-tree! conn
                                        {:root :platform
                                         :tenant "ka"
                                         :single-node? true
                                         :base-values {"services.auth.session-max-age" 86400
                                                       "features.playground.enabled" true}})
            selected-node (config-db/resolve-platform-node! @conn
                                                            {:tenant "ka"
                                                             :tenant-config-key "default"})
            session-resolution (config-db/resolve-node-value-with-trace @conn
                                                                       :platform
                                                                       "ka"
                                                                       (:config.node/id selected-node)
                                                                       "services.auth.session-max-age")]
        (is (= "platform/ka/default" (:config.node/id base-node)))
        (is (= "platform/ka/default" (:config.node/id leaf-node)))
        (is (= [] bindings))
        (is (= :created (get-in value-actions [:base "services.auth.session-max-age"])))
        (is (= "86400" (get-in session-resolution [:value :config.value/raw]))))
      (finally
        (delete-test-db conn)))))

(deftest test-bootstrap-config-tree-supports-dataset-root-through-generic-api
  (let [conn (create-test-db)]
    (try
      (seed-dataset-definitions! conn)
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Legacy binding bootstrap is no longer supported"
           (ops-bootstrap/bootstrap-config-tree! conn
                                       {:root :dataset
                                        :tenant "ka"
                                        :base-values {"pipeline.chunking.size" 500}
                                        :leaf-values {"pipeline.indexing.enabled" true}
                                        :bindings [{:type :dataset :value "kudos"}
                                                   {:type :pipeline :value "kudos-loader"}]})))
      (finally
        (delete-test-db conn)))))

(deftest test-clone-tenant-copies_tenant_local_nodes_and_values
  (let [conn (create-test-db)]
    (try
      (seed-platform-definitions! conn)
      (seed-runtime-definitions! conn)
      (seed-dataset-definitions! conn)
      (config-db/create-dataset! conn {:dataset-id "kudos" :name "Kudos"})
      (config-db/create-dataset-pipeline! conn
                                          {:pipeline-id "kudos-loader"
                                           :dataset-id "kudos"
                                           :name "Kudos Loader"
                                           :source-type :sharepoint})
      (ops-bootstrap/bootstrap-config-tree! conn
                                            {:root :platform
                                             :tenant "ka"
                                             :base-values {"services.auth.session-max-age" 3600}
                                             :leaf-values {"features.playground.enabled" true}})
      (ops-bootstrap/bootstrap-runtime-tree! conn
                                             {:tenant "ka"
                                              :agent-id "research-assistant"
                                              :dataset-id "kudos"
                                              :base-values {:rerank-top-k 24}
                                              :runtime-values {:query-planner-prompt "Please answer precisely."}})
      (ops-bootstrap/bootstrap-dataset-tree! conn
                                             {:tenant "ka"
                                              :dataset-id "kudos"
                                              :pipeline-id "kudos-loader"
                                              :base-values {"pipeline.chunking.size" 500}
                                              :dataset-values {"pipeline.indexing.enabled" true}})
      (let [preview (ops-clone/preview-clone-tenant! conn "ka" "ka-copy" {:exclude-dataset-pipelines? false})]
        (is (= 6 (get-in preview [:preview :nodes :total])))
        (is (= 6 (get-in preview [:summary :clone :nodes :count])))
        (is (= #{"kudos"} (set (get-in preview [:summary :clone :linked-datasets]))))
        (is (= #{"kudos-loader"} (set (get-in preview [:summary :clone :linked-dataset-pipelines])))))
      (let [result (ops-clone/clone-tenant! conn "ka" "ka-copy" {:exclude-dataset-pipelines? false})
            db @conn]
        (is (= 6 (get-in result [:result :nodes :created])))
        (is (= 6 (get-in result [:result :node-values :created])))
        (is (some? (config-db/get-tenant db "ka-copy")))
        (is (some? (config-db/get-config-node db "platform/ka-copy/default")))
        (is (some? (config-db/get-config-node db "runtime/ka-copy/default-runtime")))
        (is (some? (config-db/get-config-node db "dataset/ka-copy/kudos/default")))
        (is (some? (config-db/get-config-node db "dataset/ka-copy/kudos/default/kudos-loader/materialization")))
        (is (= "500"
               (:config.value/raw
                (config-db/get-node-value db
                                          :dataset
                                          "ka-copy"
                                          "dataset/ka-copy/kudos/default"
                                          "pipeline.chunking.size"))))
        (is (= "true"
               (:config.value/raw
                (config-db/get-node-value db
                                          :dataset
                                          "ka-copy"
                                          "dataset/ka-copy/kudos/default/kudos-loader/materialization"
                                          "pipeline.indexing.enabled")))))
      (finally
        (delete-test-db conn)))))

(deftest test-clone-tenant-can_exclude_dataset_pipeline_nodes
  (let [conn (create-test-db)]
    (try
      (seed-dataset-definitions! conn)
      (config-db/create-dataset! conn {:dataset-id "kudos" :name "Kudos"})
      (config-db/create-dataset-pipeline! conn
                                          {:pipeline-id "kudos-loader"
                                           :dataset-id "kudos"
                                           :name "Kudos Loader"
                                           :source-type :sharepoint})
      (ops-bootstrap/bootstrap-dataset-tree! conn
                                             {:tenant "ka"
                                              :dataset-id "kudos"
                                              :pipeline-id "kudos-loader"
                                              :base-values {"pipeline.chunking.size" 500}
                                              :dataset-values {"pipeline.indexing.enabled" true}})
      (let [preview (ops-clone/preview-clone-tenant! conn "ka" "ka-lite" {:exclude-dataset-pipelines? true})]
        (is (= 1 (get-in preview [:summary :clone :nodes :count])))
        (is (= 1 (get-in preview [:preview :nodes :total]))))
      (let [result (ops-clone/clone-tenant! conn "ka" "ka-lite" {:exclude-dataset-pipelines? true})
            db @conn]
        (is (= 1 (get-in result [:result :nodes :created])))
        (is (= 1 (get-in result [:result :node-values :created])))
        (is (some? (config-db/get-config-node db "dataset/ka-lite/kudos/default")))
        (is (nil? (config-db/get-config-node db "dataset/ka-lite/kudos/default/kudos-loader/materialization")))
        (is (= "500"
               (:config.value/raw
                (config-db/get-node-value db
                                          :dataset
                                          "ka-lite"
                                          "dataset/ka-lite/kudos/default"
                                          "pipeline.chunking.size")))))
      (finally
        (delete-test-db conn)))))

(deftest test-import-from-file-restores-v2-export
  (let [conn (create-test-db)]
    (try
      (seed-platform-tree-for-export! conn)
      (let [file (.getAbsolutePath (java.io.File/createTempFile "config-export-" ".json"))]
        (try
          (ops-sync/export-to-file conn file {:include-audit? false})

          (testing "Dry-run import previews only V2 entities"
            (let [preview-conn (create-test-db)]
              (try
                (let [preview (ops-sync/import-from-file preview-conn file {:dry-run? true})]
                  (is (= 2 (get-in preview [:definitions :total])))
                  (is (= 2 (get-in preview [:nodes :total])))
                  (is (= 0 (get-in preview [:bindings :total])))
                  (is (= 2 (get-in preview [:node-values :total]))))
                (finally
                  (delete-test-db preview-conn)))))

          (testing "Actual import restores node-based config"
            (let [fresh (create-test-db)]
              (try
                (let [result (ops-sync/import-from-file fresh file {:on-conflict :skip})
                      db @fresh]
                  (is (= 2 (get-in result [:definitions :created])))
                  (is (= 2 (get-in result [:nodes :created])))
                  (is (= 0 (get-in result [:bindings :created])))
                  (is (= 0 (get-in result [:dataset-pipelines :created])))
                  (is (= 2 (get-in result [:node-values :created])))
                  (is (= "3600"
                         (:config.value/raw
                          (config-db/get-node-value db
                                                    :platform
                                                    "test-tenant"
                                                    "platform/test-tenant/default"
                                                    "services.auth.session-max-age")))))
                (finally
                  (delete-test-db fresh)))))
          (finally
            (.delete (java.io.File. file)))))
      (finally
        (delete-test-db conn)))))

(deftest test-import-data-structure-is-v2-only
  (let [conn (create-test-db)]
    (try
      (seed-platform-tree-for-export! conn)
      (let [raw-data (ops-sync/export-full conn {:include-audit? false})]
        (is (= "1.0" (:version raw-data)))
        (is (= "full" (:scope raw-data)))
        (is (contains? (:data raw-data) :definitions))
        (is (contains? (:data raw-data) :nodes))
        (is (contains? (:data raw-data) :bindings))
        (is (contains? (:data raw-data) :node-values))
        (is (not (contains? (:data raw-data) :values))))
      (finally
        (delete-test-db conn)))))

(deftest test-export-preview-uses-node-based-shape
  (let [conn (create-test-db)]
    (try
      (seed-platform-tree-for-export! conn)
      (let [preview (ops-sync/export-full conn {:include-audit? false
                                                :dry-run? true})]
        (is (= 2 (get-in preview [:definitions :count])))
        (is (= 2 (get-in preview [:nodes :count])))
        (is (= 2 (get-in preview [:node-values :count])))
        (is (= 0 (get-in preview [:datasets :count])))
        (is (= 0 (get-in preview [:dataset-pipelines :count]))))
      (finally
        (delete-test-db conn)))))

(deftest test-export-import-roundtrip
  (let [conn (create-test-db)]
    (try
      (seed-platform-tree-for-export! conn)
      (let [export-data (ops-sync/export-full conn {:include-audit? false})]
        (is (= 2 (count (get-in export-data [:data :definitions]))))
        (is (= 2 (count (get-in export-data [:data :nodes]))))
        (is (= 0 (count (get-in export-data [:data :bindings]))))
        (is (= 2 (count (get-in export-data [:data :node-values]))))
        (let [conn2 (create-test-db)]
          (try
            (let [result (ops-sync/import-data conn2 export-data {:on-conflict :skip})
                  db2 @conn2]
              (is (= 2 (get-in result [:definitions :created])))
              (is (= 2 (get-in result [:nodes :created])))
              (is (= 0 (get-in result [:bindings :created])))
              (is (= 0 (get-in result [:dataset-pipelines :created])))
              (is (= 2 (get-in result [:node-values :created])))
              (is (= :number (:config-def/value-type
                              (config-db/get-definition db2 "services.auth.session-max-age"))))
              (is (= "3600"
                     (:config.value/raw
                      (config-db/get-node-value db2
                                                :platform
                                                "test-tenant"
                                                "platform/test-tenant/default"
                                                "services.auth.session-max-age")))))
            (finally
              (delete-test-db conn2)))))
      (finally
        (delete-test-db conn)))))

(deftest test-import-data-backfills-legacy-dataset-pipeline-projection-fields
  (let [conn (create-test-db)]
    (try
      (let [import-data {:version "1.0"
                         :scope "full"
                         :data {:definitions []
                                :nodes [{:config.node/id "dataset/ka/public-docs/default"
                                         :config.node/root :dataset
                                         :config.node/tenant "ka"
                                         :config.node/label "Default"
                                         :config.node/tenant-config-key "default"
                                         :config.node/enabled? true}
                                        {:config.node/id "dataset/ka/public-docs/prod/assistant/materialization"
                                         :config.node/root :dataset
                                         :config.node/tenant "ka"
                                         :config.node/label "Assistant Materialization"
                                         :config.node/tenant-config-key "prod-assistant"
                                         :config.node/enabled? true}]
                                :bindings []
                                :compatibilities []
                                :datasets [{:dataset/id "public-docs"
                                            :dataset/name "Public Docs"
                                            :dataset/enabled? true}]
                                :dataset-pipelines [{"dataset.pipeline/id" "assistant"
                                                     "dataset.pipeline/dataset-id" "public-docs"
                                                     "dataset.pipeline/name" "Imported Legacy"
                                                     "dataset.pipeline/source-type" "website"
                                                     "dataset.pipeline/enabled?" true}]
                                :node-values []}}
            result (ops-sync/import-data conn import-data {:on-conflict :skip})
            db @conn
            pipeline-record (config-db/get-dataset-pipeline db "assistant")
            dataset-config (config-db/get-dataset db "ka" "prod" "assistant" nil)]
        (is (= 1 (get-in result [:dataset-pipelines :created])))
        (is (= "Imported Legacy" (:name dataset-config)))
        (is (= :website (:source-type dataset-config)))
        (is (not (contains? pipeline-record :dataset.pipeline/name)))
        (is (not (contains? pipeline-record :dataset.pipeline/source-type))))
      (finally
        (delete-test-db conn)))))

(deftest test-import-data-node-values-win-over-legacy-dataset-pipeline-projections
  (testing "Envelope node-values for pipeline.ui.name win over legacy dataset.pipeline/name in the same import"
    (let [conn (create-test-db)]
      (try
        (let [materialization-node-id "dataset/ka/public-docs/prod/assistant/materialization"
              import-data {:version "1.0"
                           :scope "full"
                           :data {:definitions [{:config-def/path "pipeline.ui.name"
                                                 :config-def/root :dataset
                                                 :config-def/value-type :string
                                                 :config-def/encrypted? false
                                                 :config-def/category :pipelines
                                                 :config-def/service :other
                                                 :config-def/sensitivity :internal
                                                 :config-def/function :settings}]
                                  :nodes [{:config.node/id "dataset/ka/public-docs/default"
                                           :config.node/root :dataset
                                           :config.node/tenant "ka"
                                           :config.node/label "Default"
                                           :config.node/tenant-config-key "default"
                                           :config.node/enabled? true}
                                          {:config.node/id materialization-node-id
                                           :config.node/root :dataset
                                           :config.node/tenant "ka"
                                           :config.node/label "Assistant Materialization"
                                           :config.node/tenant-config-key "prod-assistant"
                                           :config.node/enabled? true}]
                                  :bindings []
                                  :compatibilities []
                                  :datasets [{:dataset/id "public-docs"
                                              :dataset/name "Public Docs"
                                              :dataset/enabled? true}]
                                  :dataset-pipelines [{"dataset.pipeline/id" "assistant"
                                                       "dataset.pipeline/dataset-id" "public-docs"
                                                       "dataset.pipeline/name" "Legacy Loses"
                                                       "dataset.pipeline/enabled?" true}]
                                  :node-values [{:config.value/root :dataset
                                                 :config.value/tenant "ka"
                                                 :config.value/node-id materialization-node-id
                                                 :config.value/definition-path "pipeline.ui.name"
                                                 :config.value/raw "Envelope Wins"}]}}
              result (ops-sync/import-data conn import-data {:on-conflict :overwrite})
              db @conn
              dataset-config (config-db/get-dataset db "ka" "prod" "assistant" nil)
              pipeline-record (config-db/get-dataset-pipeline db "assistant")]
          (is (= 1 (get-in result [:dataset-pipelines :created])))
          (is (= "Envelope Wins" (:name dataset-config))
              "envelope node-value should overwrite the backfilled legacy projection")
          (is (not (contains? pipeline-record :dataset.pipeline/name))
              "legacy attr is rejected by the new schema and stays absent"))
        (finally
          (delete-test-db conn))))))

(deftest test-export-tenant-prunes-unrelated-global-datasets-and-pipelines
  (let [conn (create-test-db)]
    (try
      (config-db/register-tenant! conn "ka" {:name "KA"})
      (config-db/register-tenant! conn "altinn" {:name "Altinn"})
      (config-db/create-dataset! conn {:dataset-id "kudos" :name "Kudos"})
      (config-db/create-dataset! conn {:dataset-id "altinn-docs" :name "Altinn Docs"})
      (config-db/create-dataset-pipeline! conn
                                          {:pipeline-id "kudos-loader"
                                           :dataset-id "kudos"
                                           :name "Kudos Loader"
                                           :source-type :sharepoint})
      (config-db/create-dataset-pipeline! conn
                                          {:pipeline-id "altinn-loader"
                                           :dataset-id "altinn-docs"
                                           :name "Altinn Loader"
                                           :source-type :sharepoint})
      (ops-bootstrap/bootstrap-dataset-tree! conn
                                   {:tenant "ka"
                                    :dataset-id "kudos"
                                    :pipeline-id "kudos-loader"
                                    :base-tenant-config-key "default"})
      (ops-bootstrap/bootstrap-dataset-tree! conn
                                   {:tenant "altinn"
                                    :dataset-id "altinn-docs"
                                    :pipeline-id "altinn-loader"
                                    :base-tenant-config-key "default"})

      (let [preview (ops-sync/export-tenant conn "ka" {:include-audit? false
                                                  :dry-run? true})
            export-data (ops-sync/export-tenant conn "ka" {:include-audit? false})]
        (is (= 1 (get-in preview [:datasets :count])))
        (is (= 1 (get-in preview [:dataset-pipelines :count])))
        (is (= #{"kudos"}
               (set (map #(get % "dataset/id")
                         (get-in export-data [:data :datasets])))))
        (is (= #{"kudos-loader"}
               (set (map #(get % "dataset.pipeline/id")
                         (get-in export-data [:data :dataset-pipelines]))))))
      (finally
        (delete-test-db conn)))))

(def legacy-dataset-pipeline-projection-schema
  "Schema fixture that re-introduces the legacy durable pipeline projection attrs.
   Production no longer ships these in `schema/config-migration-schema`; tests transact
   them locally to exercise migration / drift code paths against simulated stale data."
  [{:db/ident :dataset.pipeline/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :dataset.pipeline/source-type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}])

(deftest test-export-tenant-prefers-effective-pipeline-projection-fields
  (let [conn (create-test-db)]
    (try
      (d/transact conn {:tx-data legacy-dataset-pipeline-projection-schema})
      (config-db/upsert-definition! conn
                                    {:path "pipeline.ui.name"
                                     :root :dataset
                                     :value-type :string
                                     :encrypted? false
                                     :category :pipelines
                                     :service :other
                                     :sensitivity :internal
                                     :function :settings})
      (config-db/upsert-definition! conn
                                    {:path "pipeline.source.type"
                                     :root :dataset
                                     :value-type :edn
                                     :encrypted? false
                                     :category :pipelines
                                     :service :storage
                                     :sensitivity :internal
                                     :function :settings})
      (config-db/register-tenant! conn "ka" {:name "KA"})
      (config-db/create-dataset! conn {:dataset-id "public-docs" :name "Public Docs"})
      (config-db/create-dataset-pipeline! conn
                                          {:pipeline-id "assistant"
                                           :dataset-id "public-docs"})
      (d/transact conn {:tx-data [{:db/id [:dataset.pipeline/id "assistant"]
                                   :dataset.pipeline/name "Stale Projection"
                                   :dataset.pipeline/source-type :folder}]})
      (ops-bootstrap/bootstrap-dataset-tree! conn
                                             {:tenant "ka"
                                              :dataset-id "public-docs"
                                              :pipeline-id "assistant"
                                              :base-tenant-config-key "prod"
                                              :dataset-values {"pipeline.ui.name" "Config Canonical"
                                                               "pipeline.source.type" :website}})

      (let [pre-export-record (config-db/get-dataset-pipeline @conn "assistant")
            export-data (ops-sync/export-tenant conn "ka" {:include-audit? false})
            exported-pipeline (first (get-in export-data [:data :dataset-pipelines]))
            exported-name-value (->> (get-in export-data [:data :node-values])
                                     (some (fn [v]
                                             (when (= "pipeline.ui.name" (get v "config.value/definition-path"))
                                               v))))]
        (is (= "Stale Projection" (:dataset.pipeline/name pre-export-record))
            "fixture should leave a stale durable projection in place before export")
        (is (= :folder (:dataset.pipeline/source-type pre-export-record)))
        (is (= "assistant" (get exported-pipeline "dataset.pipeline/id")))
        (is (= "public-docs" (get exported-pipeline "dataset.pipeline/dataset-id")))
        (is (nil? (get exported-pipeline "dataset.pipeline/name"))
            "export should drop the stale durable projection")
        (is (nil? (get exported-pipeline "dataset.pipeline/source-type")))
        (is (= "Config Canonical" (get exported-name-value "config.value/raw"))
            "export should carry the config-canonical name through node-values"))
      (finally
        (delete-test-db conn)))))

(deftest test-bootstrap-deployment-target-topology-recovers-public-docs-and-kudos-lineages
  (let [conn (create-test-db)]
    (try
      (seed-deployment-polish-definitions! conn)

      (ops-bootstrap/bootstrap-config-tree! conn
                                   {:root :platform
                                    :tenant "altinn"
                                    :tenant-name "Altinn"
                                    :leaf-values {"services.auth.session-max-age" 3600}})      (ops-bootstrap/bootstrap-config-tree! conn
                                  {:root :platform
                                   :tenant "ka"
                                   :tenant-name "KA"
                                   :leaf-values {"services.auth.session-max-age" 7200}})

      (config-db/create-dataset! conn {:dataset-id "assistant" :name "Assistant"})
      (config-db/create-dataset-pipeline! conn
                                          {:pipeline-id "assistant"
                                           :dataset-id "assistant"
                                           :name "Assistant"})
      (ops-bootstrap/bootstrap-dataset-tree! conn
                                   {:tenant "altinn"
                                    :dataset-id "assistant"
                                    :pipeline-id "assistant"
                                    :base-tenant-config-key "dev"
                                    :base-node-id "dataset/altinn/assistant/default"
                                    :materialization-node-id "dataset/altinn/dev/assistant/materialization"
                                    :materialization-label "Assistant Materialization"
                                    :materialization-tenant-config-key "dev-assistant"
                                    :dataset-values {"pipeline.ui.name" "[DEV] Altinn Assistant"
                                                     "pipeline.source.type" :website
                                                     "pipeline.storage.docs-collection" "website_documents_ab897fbdedfa"
                                                     "pipeline.storage.chunks-collection" "website_chunks_ab897fbdedfa"
                                                     "pipeline.storage.phrases-collection" "website_phrases_ab897fbdedfa"}})

      (config-db/create-dataset! conn {:dataset-id "kudos" :name "Kudos"})
      (config-db/create-dataset-pipeline! conn
                                          {:pipeline-id "kudos"
                                           :dataset-id "kudos"
                                           :name "Kunnskapsassistent"
                                           :source-type :kudos})
      (ops-bootstrap/bootstrap-dataset-tree! conn
                                   {:tenant "ka"
                                    :dataset-id "kudos"
                                    :pipeline-id "kudos"
                                    :base-tenant-config-key "dev"
                                    :base-node-id "dataset/ka/kudos/default"
                                    :materialization-node-id "dataset/ka/dev/kudos/materialization"
                                    :materialization-label "Kudos Materialization"
                                    :materialization-tenant-config-key "dev-kudos"
                                    :dataset-values {"pipeline.ui.name" "Kunnskapsassistent"
                                                     "pipeline.source.type" :kudos
                                                     "pipeline.storage.docs-collection" "KUDOS_preprod_v4_documents_ab897fbdedfa"
                                                     "pipeline.storage.chunks-collection" "KUDOS_preprod_v4_chunks_ab897fbdedfa"
                                                     "pipeline.storage.phrases-collection" "KUDOS_preprod_v4_phrases_ab897fbdedfa"}})

      (ops-bootstrap/bootstrap-runtime-tree! conn
                                   {:tenant "ka"
                                    :agent-id "builtin/agent-rag-agent"
                                    :dataset-id "kudos"
                                    :runtime-values {:rerank-top-k 42}})

      (let [result (ops-topology/bootstrap-deployment-target-topology! conn {:master-key nil})
            db @conn
            altinn-docs-pipeline (config-db/get-dataset-pipeline db "altinn-docs")
            digdir-docs-pipeline (config-db/get-dataset-pipeline db "digdir-docs")
            kudos-pipeline (config-db/get-dataset-pipeline db "kudos")
            altinn-docs-config (config-db/get-dataset db "digdir" "default" "altinn-docs" nil)
            digdir-docs-config (config-db/get-dataset db "digdir" "default" "digdir-docs" nil)
            kudos-config (config-db/get-dataset db "public-sector-knowledge" "default" "kudos" nil)
            altinn-node (:dataset-node-id altinn-docs-config)
            digdir-node (:dataset-node-id digdir-docs-config)
            altinn-defaults (get-in result [:materialization-defaults :digdir 0 :actions])
            digdir-defaults (get-in result [:materialization-defaults :digdir 1 :actions])
            kudos-defaults (get-in result [:materialization-defaults :public-sector-knowledge 0 :actions])
            interactive-agent (agents-db/get-agent db "interactive-doc-improve")
            ;; "qualtiy" is deliberate - see the id's comment in topology.clj.
            ;; If you renamed it there and landed here, do NOT update this
            ;; string: the id is a foreign key into persisted conversation rows
            ;; and runtime config nodes (#52).
            plain-language-agent (agents-db/get-agent db "plain-language-qualtiy-check")
            cross-sector-agent (agents-db/get-agent db "cross-sector-researcher")
            digdir-runtime-node (config-db/resolve-runtime-node! db
                                                                 {:tenant "digdir"
                                                                  :tenant-config-key "default-runtime"
                                                                  :agent-id "interactive-doc-improve"
                                                                  :dataset-id "public-docs"})
            public-sector-runtime-node (config-db/resolve-runtime-node! db
                                                                        {:tenant "public-sector-knowledge"
                                                                         :tenant-config-key "default-runtime"
                                                                         :agent-id "cross-sector-researcher"
                                                                         :dataset-id "kudos"})]
        (is (= #{"digdir" "public-sector-knowledge"}
               (set (map :tenant-id (:tenants result)))))
        (is (= "public-docs" (get-in altinn-docs-pipeline [:dataset.pipeline/dataset :dataset/id])))
        (is (= "public-docs" (get-in digdir-docs-pipeline [:dataset.pipeline/dataset :dataset/id])))
        (is (nil? (:dataset.pipeline/name altinn-docs-pipeline)))
        (is (nil? (:dataset.pipeline/name digdir-docs-pipeline)))
        (is (nil? (:dataset.pipeline/source-type altinn-docs-pipeline)))
        (is (nil? (:dataset.pipeline/source-type digdir-docs-pipeline)))
        (is (nil? (:dataset.pipeline/name kudos-pipeline)))
        (is (not= altinn-node digdir-node))
        (is (= "public-docs" (:dataset-id altinn-docs-config)))
        (is (= "website_documents_ab897fbdedfa" (:docs-collection altinn-docs-config)))
        (is (= "website_chunks_ab897fbdedfa" (:chunks-collection digdir-docs-config)))
        (is (= "Altinn Docs" (:name altinn-docs-config)))
        (is (= "Digdir Docs" (:name digdir-docs-config)))
        (is (= :website (:source-type altinn-docs-config)))
        (is (= :website (:source-type digdir-docs-config)))
        (is (= 30000 (:document-limit altinn-docs-config)))
        (is (= :header-based (:chunk-strategy digdir-docs-config)))
        (is (= "website_" (:collection-prefix digdir-docs-config)))
        (is (= "https://docs.altinn.studio/sitemap.xml" (:website-sitemap-url altinn-docs-config)))
        (is (= "https://docs.altinn.studio" (:website-base-url altinn-docs-config)))
        (is (= "https://docs.digdir.no/sitemap.xml" (:website-sitemap-url digdir-docs-config)))
        (is (= "https://docs.digdir.no" (:website-base-url digdir-docs-config)))
        (is (= :target-contract (get-in result [:materialization-defaults :digdir 0 :seed-source])))
        (is (= :target-contract (get-in result [:materialization-defaults :digdir 1 :seed-source])))
        (is (= {} altinn-defaults))
        (is (= {} digdir-defaults))
        (is (= "kudos" (:dataset-id kudos-config)))
        (is (= "Kunnskapsassistent" (:name kudos-config)))
        (is (= "KUDOS_preprod_v4_documents_ab897fbdedfa" (:docs-collection kudos-config)))
        (is (= "KUDOS_preprod_v4_" (:collection-prefix kudos-config)))
        (is (= false (:kudos-use-preprod kudos-config)))
        (is (= 1 (:kudos-starting-page kudos-config)))
        (is (= :target-contract (get-in result [:materialization-defaults :public-sector-knowledge 0 :seed-source])))
        (is (= {} kudos-defaults))
        (is (= "runtime/digdir/default-runtime" (:config.node/id digdir-runtime-node)))
        (is (= "runtime/public-sector-knowledge/default-runtime" (:config.node/id public-sector-runtime-node)))
        (is (= [{:tenant "digdir" :dataset-config-key "public-docs"}]
               (:allowed-dataset-scopes interactive-agent)))
        ;; Asserted before the scopes, because a renamed id makes get-agent
        ;; return nil and the scopes assertion then fails with a bare "actual
        ;; nil" that does not say WHY. The natural repair to that message is to
        ;; correct the string above, which completes the break instead of
        ;; reverting it. This one names the hazard at the moment it fires.
        (is (some? plain-language-agent)
            (str "agent \"plain-language-qualtiy-check\" did not resolve. That id "
                 "is a FOREIGN KEY (#52) - it keys persisted conversation rows "
                 "and runtime config nodes. If you just corrected the spelling "
                 "in topology.clj, revert it; fixing it needs a migration."))
        (is (= [{:tenant "digdir" :dataset-config-key "public-docs"}]
               (:allowed-dataset-scopes plain-language-agent)))
        (is (= [{:tenant "public-sector-knowledge" :dataset-config-key "kudos"}]
               (:allowed-dataset-scopes cross-sector-agent))))
      (finally
        (delete-test-db conn)))))

(deftest test-seed-pipeline-materialization-defaults-requires-explicit-contract
  (let [conn (create-test-db)]
    (try
      (seed-deployment-polish-definitions! conn)
      (config-db/create-dataset! conn {:dataset-id "legacy" :name "Legacy"})
      (config-db/create-dataset-pipeline! conn
                                          {:pipeline-id "legacy"
                                           :dataset-id "legacy"
                                           :name "Legacy"
                                           :source-type :website})
      (ops-bootstrap/bootstrap-dataset-tree! conn
                                   {:tenant "ka"
                                    :dataset-id "legacy"
                                    :pipeline-id "legacy"
                                    :base-tenant-config-key "dev"
                                    :base-node-id "dataset/ka/legacy/default"
                                    :materialization-node-id "dataset/ka/dev/legacy/materialization"
                                    :materialization-label "Legacy Materialization"
                                    :materialization-tenant-config-key "dev-legacy"
                                    :dataset-values {"pipeline.ui.name" "Legacy"
                                                     "pipeline.source.type" :website
                                                     "pipeline.source.website.base-url" "https://legacy.example.com"}
                                    :master-key nil})
      (let [err (try
                  (ops-materialization/seed-pipeline-materialization-defaults! conn
                                                               {:tenant "ka"
                                                                :tenant-config-key "dev"
                                                                :pipeline-id "legacy"
                                                                :master-key nil})
                  nil
                  (catch Exception ex
                    ex))]
        (is (some? err))
        (is (= "No explicit materialization contract defined for pipeline"
               (ex-message err))))
      (finally
        (delete-test-db conn)))))

(deftest test-seed-pipeline-materialization-defaults-reconciles-drifted-target-contract
  (let [conn (create-test-db)]
    (try
      (seed-deployment-polish-definitions! conn)
      (config-db/create-dataset! conn {:dataset-id "public-docs" :name "Public Docs"})
      (config-db/create-dataset-pipeline! conn
                                          {:pipeline-id "altinn-docs"
                                           :dataset-id "public-docs"
                                           :name "Altinn Docs"
                                           :source-type :website})
      (ops-bootstrap/bootstrap-dataset-tree! conn
                                   {:tenant "digdir"
                                    :dataset-id "public-docs"
                                    :pipeline-id "altinn-docs"
                                    :base-tenant-config-key "dev"
                                    :base-node-id "dataset/digdir/public-docs/default"
                                    :materialization-node-id "dataset/digdir/public-docs/altinn-docs/materialization"
                                    :materialization-label "Altinn Docs Materialization"
                                    :materialization-tenant-config-key "dev-altinn-docs"
                                    :base-values {"pipeline.storage.collection-prefix" "pipeline_"}
                                    :dataset-values {"pipeline.ui.name" "Altinn Docs"
                                                     "pipeline.source.type" :website
                                                     "pipeline.source.website.sitemap-url" "https://docs.altinn.studio/sitemap.xml"
                                                     "pipeline.source.website.base-url" "https://docs.altinn.studio"}
                                    :master-key nil})
      (let [result (ops-materialization/seed-pipeline-materialization-defaults! conn
                                                                {:tenant "digdir"
                                                                 :tenant-config-key "dev"
                                                                 :pipeline-id "altinn-docs"
                                                                 :master-key nil})
            pipeline-config (config-db/get-dataset @conn "digdir" "dev" "altinn-docs" nil)]
        (is (= :updated (get-in result [:actions "pipeline.storage.collection-prefix"])))
        (is (= "website_" (:collection-prefix pipeline-config))))
      (finally
        (delete-test-db conn)))))

(deftest test-preview-tenant-retirement-summarizes-source-tenants
  (let [conn (create-test-db)]
    (try
      (seed-deployment-polish-definitions! conn)
      (ops-bootstrap/bootstrap-config-tree! conn
                                  {:root :platform
                                   :tenant "altinn"
                                   :leaf-values {"services.auth.session-max-age" 3600}})      (ops-bootstrap/bootstrap-config-tree! conn
                                  {:root :platform
                                   :tenant "altinn-docs"
                                   :leaf-values {"services.auth.session-max-age" 3600}})      (ops-bootstrap/bootstrap-runtime-tree! conn
                                   {:tenant "ka"
                                    :agent-id "builtin/agent-rag-agent"
                                    :dataset-id "kudos"
                                    :runtime-values {:rerank-top-k 42}})
      (config-db/create-dataset! conn {:dataset-id "assistant" :name "Assistant"})
      (config-db/create-dataset-pipeline! conn
                                          {:pipeline-id "assistant"
                                           :dataset-id "assistant"
                                           :name "Assistant"
                                           :source-type :website})
      (ops-bootstrap/bootstrap-dataset-tree! conn
                                   {:tenant "ka"
                                    :dataset-id "assistant"
                                    :pipeline-id "assistant"
                                    :base-tenant-config-key "dev"
                                    :base-node-id "dataset/ka/assistant/default"
                                    :materialization-node-id "dataset/ka/dev/assistant/materialization"
                                    :materialization-tenant-config-key "dev-assistant"
                                    :dataset-values {"pipeline.ui.name" "Assistant"
                                                     "pipeline.source.type" :website}})
      (let [preview (ops-retirement/preview-tenant-retirement! conn {:tenants ["altinn" "altinn-docs" "ka"]
                                                          :tenant-config-key "dev"})
            by-tenant (into {} (map (juxt :tenant identity)) (:tenants preview))]
        (is (= "dev" (:tenant-config-key preview)))
        (is (= ["altinn" "altinn-docs" "ka"] (:removable-tenants preview)))
        (is (= structure/config-roots-ordered
               (mapv :root (get-in by-tenant ["ka" :root-summaries]))))
        (is (= 2 (get-in by-tenant ["altinn" :roots :platform :nodes]))
            "the existing root-keyed preview shape remains compatible")
        (is (= 2 (->> (get-in by-tenant ["altinn" :root-summaries])
                      (some #(when (= :platform (:root %)) (:nodes %))))))
        (is (= 2 (->> (get-in by-tenant ["ka" :root-summaries])
                      (some #(when (= :runtime (:root %)) (:nodes %))))))
        (is (= ["assistant"] (get-in by-tenant ["ka" :datasets])))
        (is (= ["assistant"] (get-in by-tenant ["ka" :pipelines])))
        (is (= ["assistant"] (get-in by-tenant ["ka" :tenant-config-key-pipelines])))
        (is (= [] (get-in by-tenant ["altinn-docs" :pipelines])))
        (is (= 0 (get-in by-tenant ["altinn-docs" :conversations]))))
      (finally
        (delete-test-db conn)))))

(deftest test-retire-source-tenants-dry-run-counts-entities
  (let [conn (create-test-db)]
    (try
      (seed-deployment-polish-definitions! conn)
      (ops-bootstrap/bootstrap-config-tree! conn
                                  {:root :platform
                                   :tenant "altinn"
                                   :leaf-values {"services.auth.session-max-age" 3600}})      (ops-bootstrap/bootstrap-runtime-tree! conn
                                   {:tenant "ka"
                                    :agent-id "builtin/agent-rag-agent"
                                    :dataset-id "kudos"
                                    :runtime-values {:rerank-top-k 42}})
      (let [result (ops-retirement/retire-source-tenants! conn {:tenants ["altinn" "ka"]
                                                     :dry-run? true})
            by-tenant (into {} (map (juxt :tenant identity)) (:effects result))]
        (is (:dry-run? result))
        (is (= 2 (get-in by-tenant ["altinn" :node-count])))
        (is (= 0 (get-in by-tenant ["altinn" :binding-count])))
        (is (= 2 (get-in by-tenant ["ka" :node-count])))
        (is (= 0 (get-in by-tenant ["ka" :binding-count]))))
      (finally
        (delete-test-db conn)))))

(deftest test-retire-source-tenants-removes_config_and_tenant_records
  (let [conn (create-test-db)]
    (try
      (seed-deployment-polish-definitions! conn)
      (ops-bootstrap/bootstrap-config-tree! conn
                                  {:root :platform
                                   :tenant "altinn"
                                   :leaf-values {"services.auth.session-max-age" 3600}})      (ops-bootstrap/bootstrap-runtime-tree! conn
                                   {:tenant "ka"
                                    :agent-id "builtin/agent-rag-agent"
                                    :dataset-id "kudos"
                                    :runtime-values {:rerank-top-k 42}})
      (config-db/register-tenant! conn "digdir")
      (config-db/register-tenant! conn "public-sector-knowledge")
      (let [result (ops-retirement/retire-source-tenants! conn {:tenants ["altinn" "ka"]
                                                     :dry-run? false
                                                     :conversation-strategy :retain})]
        (is (= ["altinn" "ka"] (:retired-tenants result)))
        (is (= ["digdir" "public-sector-knowledge"]
               (:remaining-tenants result)))
        (is (nil? (config-db/get-tenant @conn "altinn")))
        (is (nil? (config-db/get-tenant @conn "ka")))
        (is (= [] (config-db/list-config-nodes @conn "altinn" :platform)))
        (is (= [] (config-db/list-config-nodes @conn "ka" :runtime))))
      (finally
        (delete-test-db conn)))))

(deftest test-retire-source-tenants-delete-strategy-removes_playground_conversations
  (let [conn (create-test-db)]
    (try
      (seed-deployment-polish-definitions! conn)
      (ops-bootstrap/bootstrap-config-tree! conn
                                  {:root :platform
                                   :tenant "altinn-docs"
                                   :leaf-values {"services.auth.session-max-age" 3600}})      (ops-bootstrap/bootstrap-runtime-tree! conn
                                   {:tenant "ka"
                                    :agent-id "builtin/agent-rag-agent"
                                    :dataset-id "kudos"
                                    :runtime-values {:rerank-top-k 42}})
      (config-db/register-tenant! conn "digdir")
      (config-db/register-tenant! conn "public-sector-knowledge")
      (data-db/create-playground-conversation conn "interactive-doc-improve"
                                              {:tenant "altinn-docs"
                                               :dataset-config-key "dev"})
      (data-db/create-playground-conversation conn "cross-sector-researcher"
                                              {:tenant "ka"
                                               :dataset-config-key "dev"})
      (let [result (ops-retirement/retire-source-tenants! conn {:tenants ["altinn-docs" "ka"]
                                                     :dry-run? false
                                                     :conversation-strategy :delete})]
        (is (= [{:strategy :delete :conversation-count 1}
                {:strategy :delete :conversation-count 1}]
               (:conversation-results result)))
        (is (nil? (d/q '[:find (count ?c) .
                         :where [?c :conversation/id _]]
                       @conn)))
        (is (nil? (config-db/get-tenant @conn "altinn-docs")))
        (is (nil? (config-db/get-tenant @conn "ka"))))
      (finally
        (delete-test-db conn)))))

(ns digdir.config.accessor-test
  (:require [clojure.test :refer [deftest testing is]]
            [datahike.api :as d]
            [digdir.config.accessor :as accessor]
            [digdir.config.core :as core]
            [digdir.config.db :as config-db]
            [digdir.config.permissions :as permissions]
            [digdir.config.schema :as schema]))

(defn create-test-db
  []
  (let [cfg {:store {:backend :mem
                     :id (str "accessor-test-" (random-uuid))}
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

(defn seed-compatibility!
  [conn {:keys [compatibility-id root tenant node-id type value]}]
  (d/transact conn
              {:tx-data [{:config.compatibility/id compatibility-id
                          :config.compatibility/root root
                          :config.compatibility/tenant tenant
                          :config.compatibility/node [:config.node/id node-id]
                          :config.compatibility/type type
                          :config.compatibility/value value
                          :config.compatibility/created-at 1}]}))

(defn seed-runtime-tree!
  [conn]
  (config-db/upsert-definition! conn
                                {:path "skills.retrieval.top-k"
                                 :root :runtime
                                 :value-type :number
                                 :encrypted? false
                                 :category :skills
                                 :service :search
                                 :sensitivity :internal
                                 :function :settings})
  (config-db/upsert-definition! conn
                                {:path "skills.retrieval.enabled"
                                 :root :runtime
                                 :value-type :boolean
                                 :encrypted? false
                                 :category :skills
                                 :service :search
                                 :sensitivity :internal
                                 :function :settings})
  (config-db/create-dataset! conn
                             {:dataset-id "kudos"
                              :name "Kudos"})
  (config-db/create-config-node! conn
                                 {:root :runtime
                                  :tenant "ka"
                                  :node-id "runtime-base"
                                  :label "Base"
                                  :tenant-config-key "default"})
  (config-db/create-config-node! conn
                                 {:root :runtime
                                  :tenant "ka"
                                  :node-id "runtime-frontpage"
                                  :label "Frontpage"
                                  :tenant-config-key "frontpage"
                                  :parent-id "runtime-base"})
  (seed-compatibility! conn
                       {:compatibility-id "compat-runtime-agent-default"
                        :root :runtime
                        :tenant "ka"
                        :node-id "runtime-base"
                        :type :agent
                        :value "research-assistant"})
  (seed-compatibility! conn
                       {:compatibility-id "compat-runtime-dataset-default"
                        :root :runtime
                        :tenant "ka"
                        :node-id "runtime-base"
                        :type :dataset
                        :value "kudos"}))

(defn seed-platform-tree!
  [conn]
  (config-db/upsert-definition! conn
                                {:path "services.azure-openai.api-key"
                                 :root :platform
                                 :value-type :string
                                 :encrypted? false
                                 :category :services
                                 :service :llm
                                 :sensitivity :internal
                                 :function :settings})
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
                                {:path "services.typesense.collection-prefix"
                                 :root :platform
                                 :value-type :string
                                 :encrypted? false
                                 :category :services
                                 :service :search
                                 :sensitivity :internal
                                 :function :settings})
  (config-db/create-config-node! conn
                                 {:root :platform
                                  :tenant "ka"
                                  :node-id "platform-base"
                                  :label "Base"
                                  :tenant-config-key "default"})
  (config-db/create-config-node! conn
                                 {:root :platform
                                  :tenant "ka"
                                  :node-id "platform-prod"
                                  :label "Prod"
                                  :tenant-config-key "prod"
                                  :parent-id "platform-base"}))

(defn seed-dataset-tree!
  [conn]
  (doseq [[path value-type] [["pipeline.ui.name" :string]
                             ["pipeline.source.type" :edn]
                             ["pipeline.chunks.minimum-length" :number]
                             ["pipeline.storage.docs-collection" :string]]]
    (config-db/upsert-definition! conn
                                  {:path path
                                   :root :dataset
                                   :value-type value-type
                                   :encrypted? false
                                   :category :pipelines
                                   :service :other
                                   :sensitivity :internal
                                   :function :settings}))
  (config-db/create-dataset! conn
                             {:dataset-id "kudos"
                              :name "Kudos"})
  (config-db/create-dataset-pipeline! conn
                                      {:pipeline-id "kudos"
                                       :dataset-id "kudos"
                                       :name "Kudos"})
  (config-db/create-config-node! conn
                                 {:root :dataset
                                  :tenant "ka"
                                  :node-id "dataset/ka/kudos/default"
                                  :label "Base"
                                  :tenant-config-key "default"})
  (config-db/create-config-node! conn
                                 {:root :dataset
                                  :tenant "ka"
                                  :node-id "dataset/ka/kudos/prod/kudos/materialization"
                                  :label "Kudos Materialization"
                                  :tenant-config-key (config-db/default-dataset-tenant-config-key "prod" "kudos")
                                  :parent-id "dataset/ka/kudos/default"})
  (seed-compatibility! conn
                       {:compatibility-id "compat-dataset-kudos"
                        :root :dataset
                        :tenant "ka"
                        :node-id "dataset/ka/kudos/default"
                        :type :dataset
                        :value "kudos"})
  (seed-compatibility! conn
                       {:compatibility-id "compat-pipeline-kudos"
                        :root :dataset
                        :tenant "ka"
                        :node-id "dataset/ka/kudos/default"
                        :type :pipeline
                        :value "kudos"}))

(defmacro with-runtime-accessor-context
  [conn & body]
  `(do
     (config-db/set-conn! ~conn)
     (try
       (with-redefs [core/use-db-config? (constantly true)
                     core/get-master-key (constantly nil)]
         ~@body)
       (finally
         (config-db/set-conn! nil)))))

(defmacro with-platform-accessor-context
  [conn & body]
  `(do
     (config-db/set-conn! ~conn)
     (try
      (with-redefs [core/use-db-config? (constantly true)
                     core/get-master-key (constantly nil)]
         ~@body)
       (finally
         (config-db/set-conn! nil)))))

(defmacro with-dataset-accessor-context
  [conn & body]
  `(do
     (config-db/set-conn! ~conn)
     (try
      (with-redefs [core/use-db-config? (constantly true)
                     core/get-master-key (constantly nil)]
         ~@body)
       (finally
         (config-db/set-conn! nil)))))

(deftest test-get-runtime-value-with-trace-decodes-and-reports-ancestry
  (testing "Runtime V2 accessor decodes values and exposes trace metadata"
    (let [conn (create-test-db)]
      (try
        (seed-runtime-tree! conn)
        (config-db/set-node-value! conn
                                   {:root :runtime
                                    :tenant "ka"
                                    :node-id "runtime-base"
                                    :path "skills.retrieval.top-k"
                                    :value 42
                                    :master-key nil})
        (config-db/set-node-value! conn
                                   {:root :runtime
                                    :tenant "ka"
                                    :node-id "runtime-frontpage"
                                    :path "skills.retrieval.top-k"
                                    :value 84
                                    :master-key nil})

        (with-runtime-accessor-context conn
          (let [{:keys [value trace node]} (accessor/get-runtime-value-with-trace
                                            "skills.retrieval.top-k"
                                            {:tenant "ka"
                                             :tenant-config-key "frontpage"
                                             :agent-id "research-assistant"
                                             :dataset-id "kudos"})]
            (is (= 84 value))
            (is (= 84 (:decoded-value trace)))
            (is (= ["runtime-frontpage"] (:traversal-path trace)))
            (is (= "runtime-frontpage" (:winning-node trace)))
            (is (= "runtime-frontpage" (:config.node/id node)))))
        (finally
          (delete-test-db conn))))))

(deftest test-get-runtime-value-with-trace-supports-explicit-tenant-config-key
  (testing "Runtime accessor resolves explicit node tenant-config-keys against root compatibility"
    (let [conn (create-test-db)]
      (try
        (seed-runtime-tree! conn)
        (config-db/set-node-value! conn
                                   {:root :runtime
                                    :tenant "ka"
                                    :node-id "runtime-base"
                                    :path "skills.retrieval.top-k"
                                    :value 21
                                    :master-key nil})
        (config-db/set-node-value! conn
                                   {:root :runtime
                                    :tenant "ka"
                                    :node-id "runtime-frontpage"
                                    :path "skills.retrieval.top-k"
                                    :value 63
                                    :master-key nil})
        (with-runtime-accessor-context conn
          (let [{:keys [value trace node]} (accessor/get-runtime-value-with-trace
                                            "skills.retrieval.top-k"
                                            {:tenant "ka"
                                             :tenant-config-key "frontpage"
                                             :agent-id "research-assistant"
                                             :dataset-id "kudos"})]
            (is (= 63 value))
            (is (= "runtime-frontpage" (:config.node/id node)))
            (is (= ["runtime-frontpage"] (:traversal-path trace)))
            (is (= "runtime-frontpage" (:winning-node trace)))))
        (finally
          (delete-test-db conn))))))

(deftest test-get-dataset-value-with-trace-decodes-and-reports-ancestry
  (testing "Dataset V2 accessor decodes values and exposes trace metadata"
    (let [conn (create-test-db)]
      (try
        (seed-dataset-tree! conn)
        (config-db/set-node-value! conn
                                   {:root :dataset
                                    :tenant "ka"
                                    :node-id "dataset/ka/kudos/default"
                                    :path "pipeline.chunks.minimum-length"
                                    :value 128
                                    :master-key nil})
        (config-db/set-node-value! conn
                                   {:root :dataset
                                    :tenant "ka"
                                    :node-id "dataset/ka/kudos/prod/kudos/materialization"
                                    :path "pipeline.chunks.minimum-length"
                                    :value 256
                                    :master-key nil})
        (with-dataset-accessor-context conn
          (let [{:keys [value trace node]} (accessor/get-dataset-value-with-trace
                                            "pipeline.chunks.minimum-length"
                                            {:tenant "ka"
                                             :tenant-config-key "prod"
                                             :pipeline-id "kudos"})]
            (is (= 256 value))
            (is (= 256 (:decoded-value trace)))
            (is (= ["dataset/ka/kudos/prod/kudos/materialization"] (:traversal-path trace)))
            (is (= "dataset/ka/kudos/prod/kudos/materialization" (:winning-node trace)))
            (is (= "dataset/ka/kudos/prod/kudos/materialization" (:config.node/id node)))))
        (finally
          (delete-test-db conn))))))

(deftest test-get-dataset-pipeline-config-v2-with-trace-loads_pipeline_properties
  (testing "Dataset V2 pipeline config loader builds flat canonical config"
    (let [conn (create-test-db)]
      (try
        (seed-dataset-tree! conn)
        (config-db/set-node-value! conn
                                   {:root :dataset
                                    :tenant "ka"
                                    :node-id "dataset/ka/kudos/default"
                                    :path "pipeline.source.type"
                                    :value :kudos
                                    :master-key nil})
        (config-db/set-node-value! conn
                                   {:root :dataset
                                    :tenant "ka"
                                    :node-id "dataset/ka/kudos/prod/kudos/materialization"
                                    :path "pipeline.ui.name"
                                    :value "Kudos"
                                    :master-key nil})
        (config-db/set-node-value! conn
                                   {:root :dataset
                                    :tenant "ka"
                                    :node-id "dataset/ka/kudos/prod/kudos/materialization"
                                    :path "pipeline.storage.docs-collection"
                                    :value "kudos_docs"
                                    :master-key nil})
        (with-dataset-accessor-context conn
          (let [{:keys [config traces node]} (accessor/get-dataset-pipeline-config-v2-with-trace
                                              {:tenant "ka"
                                               :tenant-config-key "prod"
                                               :pipeline-id "kudos"})]
            (is (= "kudos" (:id config)))
            (is (= "kudos" (:dataset-id config)))
            (is (= "Kudos" (:name config)))
            (is (= :kudos (:source-type config)))
            (is (= "kudos_docs" (:docs-collection config)))
            (is (= "prod" (:dataset-tenant-config-key config)))
            (is (= "dataset/ka/kudos/prod/kudos/materialization" (:config.node/id node)))
            (is (= "dataset/ka/kudos/prod/kudos/materialization"
                   (get-in traces ["pipeline.ui.name" :winning-node])))))
        (finally
          (delete-test-db conn))))))

(deftest test-get-runtime-value-with-trace-returns-default-for-missing-definition
  (testing "Missing runtime definitions return the provided default and synthetic trace"
    (let [conn (create-test-db)]
      (try
        (seed-runtime-tree! conn)
        (with-runtime-accessor-context conn
          (let [{:keys [value trace]} (accessor/get-runtime-value-with-trace
                                       "skills.retrieval.unknown"
                                       {:tenant "ka"
                                        :tenant-config-key "frontpage"
                                        :agent-id "research-assistant"
                                        :dataset-id "kudos"
                                        :default :missing})]
            (is (= :missing value))
            (is (= :definition-not-found (:stop-reason trace)))
            (is (= "runtime-frontpage" (:selected-node trace)))))
        (finally
          (delete-test-db conn))))))

(deftest test-load-runtime-config-v2-with-trace-loads_requested_paths
  (testing "Runtime V2 config loader builds nested config and per-path traces"
    (let [conn (create-test-db)]
      (try
        (seed-runtime-tree! conn)
        (config-db/set-node-value! conn
                                   {:root :runtime
                                    :tenant "ka"
                                    :node-id "runtime-base"
                                    :path "skills.retrieval.enabled"
                                    :value true
                                    :master-key nil})
        (config-db/set-node-value! conn
                                   {:root :runtime
                                    :tenant "ka"
                                    :node-id "runtime-base"
                                    :path "skills.retrieval.top-k"
                                    :value 42
                                    :master-key nil})

        (with-runtime-accessor-context conn
          (let [{:keys [config traces node]} (accessor/load-runtime-config-v2-with-trace
                                              {:tenant "ka"
                                               :node-id "runtime-frontpage"
                                               :agent-id "research-assistant"
                                               :dataset-id "kudos"
                                               :paths ["skills.retrieval.top-k"
                                                       "skills.retrieval.enabled"]})]
            (is (= {:skills {:retrieval {:top-k 42
                                         :enabled true}}}
                   config))
            (is (= 42 (get-in traces ["skills.retrieval.top-k" :decoded-value])))
            (is (= ["runtime-frontpage" "runtime-base"]
                   (get-in traces ["skills.retrieval.top-k" :traversal-path])))
            (is (= true (get-in traces ["skills.retrieval.enabled" :decoded-value])))
            (is (= "runtime-frontpage" (:config.node/id node)))))
        (finally
          (delete-test-db conn))))))

(deftest test-runtime-v2-accessor-rejects-non-runtime-definitions
  (testing "Runtime V2 accessor refuses definitions without a runtime root"
    (let [conn (create-test-db)]
      (try
        (seed-runtime-tree! conn)
        (config-db/upsert-definition! conn
                                      {:path "services.azure-openai.api-key"
                                       :root :platform
                                       :value-type :string
                                       :encrypted? false
                                       :category :services
                                       :service :llm
                                       :sensitivity :internal
                                       :function :settings})

        (with-runtime-accessor-context conn
          (is (thrown? clojure.lang.ExceptionInfo
                       (accessor/get-runtime-value
                        "services.azure-openai.api-key"
                        {:tenant "ka"
                         :tenant-config-key "frontpage"
                         :agent-id "research-assistant"
                         :dataset-id "kudos"}))))
        (finally
          (delete-test-db conn))))))

(deftest test-get-platform-value-with-trace-decodes-and_reports_ancestry
  (testing "Platform V2 accessor decodes values and exposes trace metadata"
    (let [conn (create-test-db)]
      (try
        (seed-platform-tree! conn)
        (config-db/set-node-value! conn
                                   {:root :platform
                                    :tenant "ka"
                                    :node-id "platform-base"
                                    :path "services.auth.session-max-age"
                                    :value 86400
                                    :master-key nil})

        (with-platform-accessor-context conn
          (let [{:keys [value trace node]} (accessor/get-platform-value-with-trace
                                            "services.auth.session-max-age"
                                            {:tenant "ka"
                                             :tenant-config-key "prod"})]
            (is (= 86400 value))
            (is (= 86400 (:decoded-value trace)))
            (is (= ["platform-prod" "platform-base"] (:traversal-path trace)))
            (is (= "platform-base" (:winning-node trace)))
            (is (= "platform-prod" (:config.node/id node)))))
        (finally
          (delete-test-db conn))))))

(deftest test-get-reads-platform-v2-for-platform-rooted-definitions
  (testing "Primary accessor resolves platform-rooted definitions through Platform V2"
    (let [conn (create-test-db)]
      (try
        (seed-platform-tree! conn)
        (config-db/set-node-value! conn
                                   {:root :platform
                                    :tenant "ka"
                                    :node-id "platform-base"
                                    :path "services.azure-openai.api-key"
                                    :value "v2-key"
                                    :master-key nil})

        (with-platform-accessor-context conn
          (is (= "v2-key"
                 (accessor/get {:tenant "ka"} :services :azure-openai :api-key))))
        (finally
          (delete-test-db conn))))))

(deftest test-get-rejects-nil-tenant
  (testing "Nil tenant throws a helpful error — the __platform-defaults__ fallback was retired"
    (let [conn (create-test-db)]
      (try
        (seed-platform-tree! conn)
        (config-db/upsert-definition! conn
                                      {:path "services.demo.some-value"
                                       :root :platform
                                       :value-type :string})
        (with-platform-accessor-context conn
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"requires an explicit :tenant"
               (accessor/get {:tenant nil} :services :demo :some-value))
              "nil tenant must fail loudly so callers surface their missing plumbing")
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"requires an explicit :tenant"
               (accessor/get {:tenant ""} :services :demo :some-value))
              "blank-string tenant also rejected"))
        (finally
          (delete-test-db conn))))))

(deftest test-get-if-allowed-uses-explicit-opts
  (testing "Permission-checked get requires cfg/get opts and passes them through"
    (let [conn (create-test-db)
          checked-opts (atom nil)]
      (try
        (seed-platform-tree! conn)
        (config-db/set-node-value! conn
                                   {:root :platform
                                    :tenant "ka"
                                    :node-id "platform-base"
                                    :path "services.azure-openai.api-key"
                                    :value "v2-key"
                                    :master-key nil})
        (with-platform-accessor-context conn
          (with-redefs [permissions/can-access? (fn [_db _user-id _path _action opts]
                                                  (reset! checked-opts opts)
                                                  true)]
            (is (= "v2-key"
                   (accessor/get-if-allowed "user-123"
                                            {:tenant "ka"}
                                            :services :azure-openai :api-key)))
            (is (= {:tenant "ka"} @checked-opts))
            (is (thrown? clojure.lang.ExceptionInfo
                         (accessor/get-if-allowed "user-123"
                                                  :services :azure-openai :api-key)))))
        (finally
          (delete-test-db conn))))))

(deftest test-get-requires-platform-tree-for-platform-rooted-definitions
  (testing "Rooted platform definitions require Platform V2 even before a tenant has a Platform tree"
    (let [conn (create-test-db)]
      (try
        (config-db/upsert-definition! conn
                                      {:path "services.azure-openai.api-key"
                                       :root :platform
                                       :value-type :string
                                       :encrypted? false
                                       :category :services
                                       :service :llm
                                       :sensitivity :internal
                                       :function :settings})

        (with-platform-accessor-context conn
          (is (thrown? clojure.lang.ExceptionInfo
                       (accessor/get {:tenant "ka"} :services :azure-openai :api-key))))
        (finally
          (delete-test-db conn))))))

(deftest test-get-fails-closed-when-no-default-root-node
  (testing "Primary accessor fails when tenant has no canonical default platform node"
    (let [conn (create-test-db)]
      (try
        (config-db/upsert-definition! conn
                                      {:path "services.azure-openai.api-key"
                                       :root :platform
                                       :value-type :string
                                       :encrypted? true})
        (config-db/register-tenant! conn "empty" {:name "Empty"})

        (config-db/set-conn! conn)
        (try
          (with-redefs [core/use-db-config? (constantly true)
                        core/get-master-key (constantly nil)]
            (is (thrown? clojure.lang.ExceptionInfo
                         (accessor/get {:tenant "empty"} :services :azure-openai :api-key))))
          (finally
            (config-db/set-conn! nil)))
        (finally
          (delete-test-db conn))))))

(deftest test-get-requires-platform-v2-for-typesense-family
  (testing "Typesense Platform paths require V2"
    (let [conn (create-test-db)]
      (try
        (config-db/upsert-definition! conn
                                      {:path "services.typesense.collection-prefix"
                                       :root :platform
                                       :value-type :string
                                       :encrypted? false
                                       :category :services
                                       :service :search
                                       :sensitivity :internal
                                       :function :settings})

        (config-db/set-conn! conn)
        (try
          (with-redefs [core/use-db-config? (constantly true)
                        core/get-master-key (constantly nil)]
            (is (thrown? clojure.lang.ExceptionInfo
                         (accessor/get {:tenant "ka"} :services :typesense :collection-prefix))))
          (finally
            (config-db/set-conn! nil)))
        (finally
          (delete-test-db conn))))))

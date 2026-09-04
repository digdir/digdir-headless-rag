(ns digdir.config.db-test
  "Tests for the V2 config tree model."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [digdir.config.schema :as schema]
            [digdir.config.db :as config-db]
            [digdir.data.db :as data-db]))

(def legacy-dataset-pipeline-projection-schema
  [{:db/ident :dataset.pipeline/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :dataset.pipeline/source-type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}])

(defn create-test-db
  []
  (let [cfg {:store {:backend :mem
                     :id (str "db-test-" (random-uuid))}
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

(defn seed-definition!
  [conn path root value-type]
  (config-db/upsert-definition! conn
                                {:path path
                                 :root root
                                 :value-type value-type
                                 :encrypted? false
                                 :description "Test config"
                                 :category :general
                                 :service :other
                                 :sensitivity :internal
                                 :function :settings}))

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

(deftest test-make-config-id-roundtrip-supports-legacy-export-helpers
  (is (= {:tenant "ka"
          :tenant-config-key "prod"
          :client nil
          :skill-graph nil
          :pipeline "assistant"
          :path "skills.rerank.top-k"}
         (config-db/parse-config-id
          (config-db/make-config-id "ka" "prod" "assistant" "skills.rerank.top-k"))))
  (is (= {:tenant "ka"
          :tenant-config-key "prod"
          :client "web"
          :skill-graph "default"
          :pipeline "assistant"
          :path "skills.rerank.top-k"}
         (config-db/parse-config-id
          (config-db/make-config-id "ka" "prod" "web" "default" "assistant" "skills.rerank.top-k")))))

;; =============================================================================
;; Uniqueness guarantees for config-def entities
;;
;; These tests pin down the invariant that corrupted the live DB in the
;; 2026-04-23 incident: :config-def/path must enforce :db.unique/identity, and
;; upsert-definition! must refuse to write when the DB already has duplicates
;; for a given path.
;; =============================================================================

(defn- create-non-unique-test-db
  "Test DB whose :config-def/path schema omits :db/unique. Used to reproduce
   the pre-schema state where duplicates could be transacted."
  []
  (let [cfg {:store {:backend :mem :id (str "db-test-nonuniq-" (random-uuid))}
             :schema-flexibility :read}
        weakened (mapv (fn [m]
                         (if (= :config-def/path (:db/ident m))
                           (dissoc m :db/unique)
                           m))
                       schema/config-migration-schema)]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data weakened})
      conn)))

(deftest test-upsert-definition-is-idempotent-under-correct-schema
  (let [conn (create-test-db)]
    (try
      (config-db/upsert-definition! conn {:path "x.invariant" :root :platform
                                          :value-type :string})
      (config-db/upsert-definition! conn {:path "x.invariant" :root :platform
                                          :value-type :string
                                          :description "second call"})
      (let [eids (d/q '[:find [?e ...]
                        :where [?e :config-def/path "x.invariant"]]
                      @conn)]
        (is (= 1 (count eids))
            "repeated upsert must update the existing entity, not insert a new one"))
      (let [def (config-db/get-definition @conn "x.invariant")]
        (is (= "second call" (:config-def/description def))
            "latest upsert values should be present"))
      (finally (delete-test-db conn)))))

(deftest test-upsert-definition-throws-when-duplicates-already-exist
  (let [conn (create-non-unique-test-db)]
    (try
      ;; Force two entities at the same path by bypassing uniqueness.
      (d/transact conn
                  {:tx-data [{:db/id -1
                              :config-def/path "y.duplicated"
                              :config-def/root :platform
                              :config-def/value-type :string
                              :config-def/encrypted? false
                              :config-def/multiline? false
                              :config-def/created-at 1}
                             {:db/id -2
                              :config-def/path "y.duplicated"
                              :config-def/root :platform
                              :config-def/value-type :string
                              :config-def/encrypted? false
                              :config-def/multiline? false
                              :config-def/created-at 2}]})
      (let [eids (d/q '[:find [?e ...]
                        :where [?e :config-def/path "y.duplicated"]]
                      @conn)]
        (is (= 2 (count eids))
            "preflight: the weakened schema permits duplicates as expected"))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Duplicate config-def"
           (config-db/upsert-definition! conn {:path "y.duplicated"
                                               :root :platform
                                               :value-type :string}))
          "upsert must refuse to silently write to one of two duplicates")
      (finally (delete-test-db conn)))))

(deftest test-audit-uniqueness-invariants-reports-violations
  (let [conn (create-non-unique-test-db)]
    (try
      (d/transact conn
                  {:tx-data [{:db/id -1
                              :config-def/path "z.audit"
                              :config-def/root :platform
                              :config-def/value-type :string
                              :config-def/encrypted? false
                              :config-def/multiline? false
                              :config-def/created-at 1}
                             {:db/id -2
                              :config-def/path "z.audit"
                              :config-def/root :platform
                              :config-def/value-type :string
                              :config-def/encrypted? false
                              :config-def/multiline? false
                              :config-def/created-at 2}]})
      (let [{:keys [checked violations]}
            (data-db/audit-uniqueness-invariants!
             conn
             [{:label :config :tx schema/config-migration-schema}])
            config-def-violation (first (filter #(= :config-def/path (:attr %))
                                                violations))]
        (is (pos? checked)
            "audit must scan at least one unique attribute")
        (is (some? config-def-violation)
            "audit must report a violation for the duplicated :config-def/path")
        (is (= "z.audit" (:value config-def-violation)))
        (is (= 2 (count (:eids config-def-violation)))))
      (finally (delete-test-db conn)))))

(deftest test-audit-uniqueness-invariants-clean-db-has-no-violations
  (let [conn (create-test-db)]
    (try
      (seed-definition! conn "a.clean" :platform :string)
      (seed-definition! conn "b.clean" :runtime :number)
      (let [{:keys [checked violations]}
            (data-db/audit-uniqueness-invariants!
             conn
             [{:label :config :tx schema/config-migration-schema}])]
        (is (pos? checked))
        (is (empty? violations)
            "a well-formed DB must produce zero violations"))
      (finally (delete-test-db conn)))))

(deftest test-config-schema-supports-config-tree-entities
  (let [conn (create-test-db)]
    (try
      (d/transact conn
                  {:tx-data [{:config-def/path "skills.retrieval.top-k"
                              :config-def/root :runtime
                              :config-def/value-type :number
                              :config-def/encrypted? false
                              :config-def/category :skills
                              :config-def/service :search
                              :config-def/sensitivity :internal
                              :config-def/function :settings
                              :config-def/created-at 1}
                             {:db/id -10
                              :dataset/id "kudos"
                              :dataset/name "Kudos"
                              :dataset/description "Kudos dataset"
                              :dataset/enabled? true
                              :dataset/created-at 1
                              :dataset/updated-at 2}
                             {:dataset.pipeline/id "kudos-loader-v2"
                              :dataset.pipeline/dataset -10
                              :dataset.pipeline/name "Kudos Loader V2"
                              :dataset.pipeline/source-type :kudos
                              :dataset.pipeline/enabled? true
                              :dataset.pipeline/created-at 1
                              :dataset.pipeline/updated-at 2}
                             {:db/id -20
                              :config.node/id "cfg-node-runtime-default"
                             :config.node/root :runtime
                             :config.node/tenant "ka"
                             :config.node/label "Default Runtime"
                             :config.node/tenant-config-key "default-runtime"
                             :config.node/system-managed? true
                             :config.node/enabled? true
                             :config.node/created-at 1
                             :config.node/updated-at 2}
                             {:config.compatibility/id "compat-runtime-agent"
                              :config.compatibility/root :runtime
                              :config.compatibility/tenant "ka"
                              :config.compatibility/node [:config.node/id "cfg-node-runtime-default"]
                              :config.compatibility/type :agent
                              :config.compatibility/value "agent/research"
                              :config.compatibility/created-at 1}
                             {:config.value/id "runtime:ka:cfg-node-runtime-default:skills.retrieval.top-k"
                              :config.value/root :runtime
                              :config.value/tenant "ka"
                              :config.value/node -20
                              :config.value/definition [:config-def/path "skills.retrieval.top-k"]
                              :config.value/raw "42"
                              :config.value/created-at 1
                              :config.value/updated-at 2}]})
      (let [node (d/pull @conn '[* {:config.node/parent [:config.node/id]}]
                         [:config.node/id "cfg-node-runtime-default"])
            compatibility-node-id (d/q '[:find ?node-id .
                                         :in $ ?compatibility-id
                                         :where
                                         [?e :config.compatibility/id ?compatibility-id]
                                         [?e :config.compatibility/node ?node]
                                         [?node :config.node/id ?node-id]]
                                       @conn
                                       "compat-runtime-agent")
            value (d/pull @conn '[* {:config.value/definition [:config-def/path :config-def/root]}
                                    {:config.value/node [:config.node/id]}]
                          [:config.value/id "runtime:ka:cfg-node-runtime-default:skills.retrieval.top-k"])
            dataset (d/pull @conn '[*] [:dataset/id "kudos"])
            pipeline (d/pull @conn '[* {:dataset.pipeline/dataset [:dataset/id]}]
                             [:dataset.pipeline/id "kudos-loader-v2"])]
        (is (= :runtime (:config.node/root node)))
        (is (true? (:config.node/system-managed? node)))
        (is (= "cfg-node-runtime-default" compatibility-node-id))
        (is (= "skills.retrieval.top-k" (get-in value [:config.value/definition :config-def/path])))
        (is (= "Kudos" (:dataset/name dataset)))
        (is (= "kudos" (get-in pipeline [:dataset.pipeline/dataset :dataset/id]))))
      (finally
        (delete-test-db conn)))))

(deftest test-main-db-schema-supports-api-key-allowed-config-keys-with-config-node-refs
  (let [conn (create-test-db)]
    (try
      (d/transact conn {:tx-data data-db/api-key-schema})
      (d/transact conn
                  {:tx-data [{:db/id -1
                              :config.node/id "cfg-node-platform-prod"
                              :config.node/root :platform
                              :config.node/tenant "ka"
                              :config.node/label "Prod"
                              :config.node/tenant-config-key "default"
                              :config.node/enabled? true
                              :config.node/created-at 1
                              :config.node/updated-at 2}
                             {:db/id -2
                              :api-key.allowed-config-key/id "cfg-ceiling-1"
                              :api-key.allowed-config-key/root :platform
                              :api-key.allowed-config-key/tenant "ka"
                              :api-key.allowed-config-key/node -1
                              :api-key.allowed-config-key/node-id "cfg-node-platform-prod"
                              :api-key.allowed-config-key/tenant-config-key "default"
                              :api-key.allowed-config-key/created-at 1}
                             {:api-key/id "api-key-1"
                              :api-key/key "rag_test"
                              :api-key/name "Test Key"
                              :api-key/created 1
                              :api-key/created-by "user-1"
                              :api-key/revoked false
                              :api-key/allowed-config-keys [-2]}]})
      (let [api-key (d/pull @conn '[* {:api-key/allowed-config-keys [* {:api-key.allowed-config-key/node [:config.node/id :config.node/tenant-config-key]}]}]
                           [:api-key/id "api-key-1"])]
        (is (= "cfg-ceiling-1" (get-in api-key [:api-key/allowed-config-keys 0 :api-key.allowed-config-key/id])))
        (is (= "default" (get-in api-key [:api-key/allowed-config-keys 0 :api-key.allowed-config-key/node :config.node/tenant-config-key]))))
      (finally
        (delete-test-db conn)))))

(deftest test-definition-upsert-and-root-filtering
  (let [conn (create-test-db)]
    (try
      (seed-definition! conn "services.auth.session-max-age" :platform :number)
      (seed-definition! conn "skills.rerank.top-k" :runtime :number)
      (seed-definition! conn "pipeline.ui.name" :dataset :string)
      (is (= #{"services.auth.session-max-age"
               "skills.rerank.top-k"
               "pipeline.ui.name"}
             (set (map :config-def/path (config-db/get-all-definitions @conn)))))
      (is (= #{"services.auth.session-max-age"}
             (set (map :config-def/path (config-db/get-definitions-by-root @conn :platform)))))
      (is (= :runtime (:config-def/root (config-db/get-definition @conn "skills.rerank.top-k"))))
      (finally
        (delete-test-db conn)))))

(deftest test-runtime-node-resolution-and-batch-values
  (let [conn (create-test-db)]
    (try
      (seed-definition! conn "skills.retrieval.top-k" :runtime :number)
      (seed-definition! conn "skills.retrieval.enabled" :runtime :boolean)
      (config-db/create-dataset! conn {:dataset-id "kudos" :name "Kudos"})
      (config-db/create-config-node! conn {:root :runtime :tenant "ka" :node-id "runtime-base" :label "Base" :tenant-config-key "default"})
      (config-db/create-config-node! conn {:root :runtime :tenant "ka" :node-id "runtime-frontpage" :label "Frontpage" :tenant-config-key "frontpage" :parent-id "runtime-base"})
      (seed-compatibility! conn {:compatibility-id "compat-runtime-agent-research"
                                 :root :runtime
                                 :tenant "ka"
                                 :node-id "runtime-base"
                                 :type :agent
                                 :value "research-assistant"})
      (seed-compatibility! conn {:compatibility-id "compat-runtime-dataset-kudos"
                                 :root :runtime
                                 :tenant "ka"
                                 :node-id "runtime-base"
                                 :type :dataset
                                 :value "kudos"})
      (config-db/set-node-value! conn {:root :runtime
                                       :tenant "ka"
                                       :node-id "runtime-base"
                                       :path "skills.retrieval.top-k"
                                       :value 42
                                       :master-key nil})
      (config-db/set-node-value! conn {:root :runtime
                                       :tenant "ka"
                                       :node-id "runtime-frontpage"
                                       :path "skills.retrieval.enabled"
                                       :value true
                                       :master-key nil})
      (let [selected-node (config-db/resolve-runtime-node! @conn {:tenant "ka"
                                                                  :tenant-config-key "frontpage"
                                                                  :agent-id "research-assistant"
                                                                  :dataset-id "kudos"})
            {:keys [results]} (config-db/resolve-node-values-batch @conn
                                                                   :runtime
                                                                   "ka"
                                                                   (:config.node/id selected-node)
                                                                   ["skills.retrieval.top-k"
                                                                    "skills.retrieval.enabled"])]
        (is (= "runtime-frontpage" (:config.node/id selected-node)))
        (is (= "42" (get-in results ["skills.retrieval.top-k" :value :config.value/raw])))
        (is (= "runtime-base" (get-in results ["skills.retrieval.top-k" :trace :winning-node])))
        (is (= "true" (get-in results ["skills.retrieval.enabled" :value :config.value/raw])))
        (is (= "runtime-frontpage" (get-in results ["skills.retrieval.enabled" :trace :winning-node]))))
      (finally
        (delete-test-db conn)))))

(deftest test-dataset-ref-resolution-supports-legacy-materialization-node-ids
  (let [conn (create-test-db)]
    (try
      (seed-definition! conn "pipeline.ui.name" :dataset :string)
      (config-db/create-dataset! conn {:dataset-id "public-docs" :name "Public Docs"})
      (config-db/create-dataset-pipeline! conn {:pipeline-id "altinn-docs"
                                                :dataset-id "public-docs"
                                                :name "Altinn Docs"
                                                :source-type :website})
      (config-db/create-config-node! conn {:root :dataset
                                           :tenant "digdir"
                                           :node-id "dataset/digdir/public-docs/default"
                                           :label "Default"
                                           :tenant-config-key "default"})
      (config-db/create-config-node! conn {:root :dataset
                                           :tenant "digdir"
                                           :node-id "dataset/digdir/public-docs/altinn-docs/materialization"
                                           :label "Altinn Docs Materialization"
                                           :tenant-config-key "altinn-docs-materialization"
                                           :parent-id "dataset/digdir/public-docs/default"})
      (config-db/set-node-value! conn {:root :dataset
                                       :tenant "digdir"
                                       :node-id "dataset/digdir/public-docs/altinn-docs/materialization"
                                       :path "pipeline.ui.name"
                                       :value "Altinn Docs"
                                       :master-key nil})
      (let [parsed-node (config-db/parse-dataset-node-id "dataset/digdir/public-docs/altinn-docs/materialization")
            dataset (config-db/get-dataset-by-ref @conn
                                                  {:tenant "digdir"
                                                   :dataset-config-key "altinn-docs-materialization"}
                                                  nil)]
        (is (= {:kind :materialization
                :tenant "digdir"
                :dataset-id "public-docs"
                :tenant-config-key nil
                :pipeline-id "altinn-docs"
                :legacy? true}
               parsed-node))
        (is (= "public-docs" (:dataset-id dataset)))
        (is (= "public-docs" (:dataset-config-key dataset)))
        (is (= "Public Docs" (:name dataset)))
        (is (nil? (:materialization dataset))))
      (finally
        (delete-test-db conn)))))

(deftest test-config-backed-pipeline-fields-override-durable-projections
  (let [conn (create-test-db)]
    (try
      (seed-definition! conn "pipeline.ui.name" :dataset :string)
      (seed-definition! conn "pipeline.source.type" :dataset :edn)
      (config-db/create-dataset! conn {:dataset-id "public-docs" :name "Public Docs"})
      (config-db/create-dataset-pipeline! conn {:pipeline-id "assistant"
                                                :dataset-id "public-docs"})
      (let [base-node-id (config-db/dataset-base-node-id "ka" "public-docs")
            materialization-node-id (config-db/dataset-materialization-node-id "ka" "prod" "public-docs" "assistant")]
        (config-db/create-config-node! conn {:root :dataset
                                             :tenant "ka"
                                             :node-id base-node-id
                                             :label "Default"
                                             :tenant-config-key "default"})
        (config-db/create-config-node! conn {:root :dataset
                                             :tenant "ka"
                                             :node-id materialization-node-id
                                             :label "Assistant Materialization"
                                             :tenant-config-key (config-db/default-dataset-tenant-config-key "prod" "assistant")
                                             :parent-id base-node-id})
        (config-db/set-node-value! conn {:root :dataset
                                         :tenant "ka"
                                         :node-id materialization-node-id
                                         :path "pipeline.ui.name"
                                         :value "Config Canonical"
                                         :master-key nil})
        (config-db/set-node-value! conn {:root :dataset
                                         :tenant "ka"
                                         :node-id materialization-node-id
                                         :path "pipeline.source.type"
                                         :value :website
                                         :master-key nil})
        (let [dataset (config-db/get-dataset @conn "ka" "prod" "assistant" nil)
              effective-record (config-db/effective-dataset-pipeline-record
                                @conn
                                (config-db/get-dataset-pipeline @conn "assistant")
                                {:tenant "ka"
                                 :tenant-config-key "prod"
                                 :master-key nil})]
          (is (= "Config Canonical" (:name dataset)))
          (is (= :website (:source-type dataset)))
          (is (nil? (:projection-drift dataset)))
          (is (= "Config Canonical" (:dataset.pipeline/effective-name effective-record)))
          (is (= :website (:dataset.pipeline/effective-source-type effective-record)))
          (is (nil? (:dataset.pipeline/projection-drift effective-record)))))
      (finally
        (delete-test-db conn)))))

(deftest test-migrate-legacy-dataset-pipeline-projections-backfills-and-retracts
  (let [conn (create-test-db)]
    (try
      (d/transact conn {:tx-data legacy-dataset-pipeline-projection-schema})
      (config-db/register-tenant! conn "ka" {:name "KA"})
      (config-db/create-dataset! conn {:dataset-id "public-docs" :name "Public Docs"})
      (config-db/create-dataset-pipeline! conn {:pipeline-id "assistant"
                                                :dataset-id "public-docs"})
      (d/transact conn {:tx-data [{:db/id [:dataset.pipeline/id "assistant"]
                                   :dataset.pipeline/name "Legacy Assistant"
                                   :dataset.pipeline/source-type :website}]})
      (let [base-node-id (config-db/dataset-base-node-id "ka" "public-docs")
            materialization-node-id (config-db/dataset-materialization-node-id "ka" "prod" "public-docs" "assistant")]
        (config-db/create-config-node! conn {:root :dataset
                                             :tenant "ka"
                                             :node-id base-node-id
                                             :label "Default"
                                             :tenant-config-key "default"})
        (config-db/create-config-node! conn {:root :dataset
                                             :tenant "ka"
                                             :node-id materialization-node-id
                                             :label "Assistant Materialization"
                                             :tenant-config-key (config-db/default-dataset-tenant-config-key "prod" "assistant")
                                             :parent-id base-node-id})
        (let [result (config-db/migrate-legacy-dataset-pipeline-projections! conn)
              migrated-record (config-db/get-dataset-pipeline @conn "assistant")
              dataset-config (config-db/get-dataset @conn "ka" "prod" "assistant" nil)]
          (is (= {:pipelines-with-legacy-projections 1
                  :pipelines-migrated 1
                  :pipelines-without-materialization-nodes 0
                  :values-backfilled 2
                  :values-conflicting-config 0
                  :legacy-attrs-retracted 2}
                 result))
          (is (= "Legacy Assistant" (:name dataset-config)))
          (is (= :website (:source-type dataset-config)))
          (is (not (contains? migrated-record :dataset.pipeline/name)))
          (is (not (contains? migrated-record :dataset.pipeline/source-type)))))
      (finally
        (delete-test-db conn)))))

(deftest test-backfill-warns-on-config-conflict
  (let [conn (create-test-db)]
    (try
      (d/transact conn {:tx-data legacy-dataset-pipeline-projection-schema})
      (seed-definition! conn "pipeline.ui.name" :dataset :string)
      (config-db/register-tenant! conn "ka" {:name "KA"})
      (config-db/create-dataset! conn {:dataset-id "public-docs" :name "Public Docs"})
      (config-db/create-dataset-pipeline! conn {:pipeline-id "assistant"
                                                :dataset-id "public-docs"})
      (d/transact conn {:tx-data [{:db/id [:dataset.pipeline/id "assistant"]
                                   :dataset.pipeline/name "Legacy Name"}]})
      (let [base-node-id (config-db/dataset-base-node-id "ka" "public-docs")
            materialization-node-id (config-db/dataset-materialization-node-id "ka" "prod" "public-docs" "assistant")]
        (config-db/create-config-node! conn {:root :dataset
                                             :tenant "ka"
                                             :node-id base-node-id
                                             :label "Default"
                                             :tenant-config-key "default"})
        (config-db/create-config-node! conn {:root :dataset
                                             :tenant "ka"
                                             :node-id materialization-node-id
                                             :label "Assistant Materialization"
                                             :tenant-config-key (config-db/default-dataset-tenant-config-key "prod" "assistant")
                                             :parent-id base-node-id})
        (config-db/set-node-value! conn {:root :dataset
                                         :tenant "ka"
                                         :node-id materialization-node-id
                                         :path "pipeline.ui.name"
                                         :value "Config Wins"
                                         :master-key nil})
        (let [result (config-db/backfill-dataset-pipeline-config-projections!
                      conn
                      "assistant"
                      {:name "Legacy Name"
                       :retract-legacy-attrs? true})
              post-record (config-db/get-dataset-pipeline @conn "assistant")
              post-config-name (-> (config-db/get-node-value @conn :dataset "ka" materialization-node-id "pipeline.ui.name")
                                   :config.value/raw)]
          (is (= 0 (:values-backfilled result)))
          (is (= 1 (:values-conflicting-config result)))
          (is (= 1 (:legacy-attrs-retracted result)))
          (is (= "Config Wins" post-config-name)
              "config value should remain canonical after backfill skips conflicting legacy")
          (is (not (contains? post-record :dataset.pipeline/name))
              "legacy attr should still be retracted even when backfill skipped writing")))
      (finally
        (delete-test-db conn)))))

(deftest test-effective-record-multi-tenant-ambiguity
  (let [conn (create-test-db)]
    (try
      (seed-definition! conn "pipeline.ui.name" :dataset :string)
      (config-db/register-tenant! conn "ka" {:name "KA"})
      (config-db/register-tenant! conn "nav" {:name "NAV"})
      (config-db/create-dataset! conn {:dataset-id "public-docs" :name "Public Docs"})
      (config-db/create-dataset-pipeline! conn {:pipeline-id "assistant"
                                                :dataset-id "public-docs"})
      (doseq [tenant ["ka" "nav"]]
        (let [base-node-id (config-db/dataset-base-node-id tenant "public-docs")
              materialization-node-id (config-db/dataset-materialization-node-id tenant "prod" "public-docs" "assistant")]
          (config-db/create-config-node! conn {:root :dataset
                                               :tenant tenant
                                               :node-id base-node-id
                                               :label "Default"
                                               :tenant-config-key "default"})
          (config-db/create-config-node! conn {:root :dataset
                                               :tenant tenant
                                               :node-id materialization-node-id
                                               :label "Assistant Materialization"
                                               :tenant-config-key (config-db/default-dataset-tenant-config-key "prod" "assistant")
                                               :parent-id base-node-id})
          (config-db/set-node-value! conn {:root :dataset
                                           :tenant tenant
                                           :node-id materialization-node-id
                                           :path "pipeline.ui.name"
                                           :value (str "Assistant " (clojure.string/upper-case tenant))
                                           :master-key nil})))
      (let [pipeline-record (config-db/get-dataset-pipeline @conn "assistant")
            no-tenant-record (config-db/effective-dataset-pipeline-record @conn pipeline-record)
            ka-record (config-db/effective-dataset-pipeline-record @conn pipeline-record
                                                                   {:tenant "ka"
                                                                    :tenant-config-key "prod"
                                                                    :master-key nil})
            nav-record (config-db/effective-dataset-pipeline-record @conn pipeline-record
                                                                    {:tenant "nav"
                                                                     :tenant-config-key "prod"
                                                                     :master-key nil})]
        (is (nil? (:dataset.pipeline/effective-name no-tenant-record))
            "ambiguous resolution must not pick a winner")
        (is (true? (:dataset.pipeline/effective-name-ambiguous? no-tenant-record)))
        (is (= "Assistant KA" (:dataset.pipeline/effective-name ka-record)))
        (is (not (contains? ka-record :dataset.pipeline/effective-name-ambiguous?)))
        (is (= "Assistant NAV" (:dataset.pipeline/effective-name nav-record))))
      (finally
        (delete-test-db conn)))))

(deftest test-materialization-contexts-by-pipeline-id-batches-resolution
  (let [conn (create-test-db)]
    (try
      (config-db/register-tenant! conn "ka" {:name "KA"})
      (config-db/register-tenant! conn "nav" {:name "NAV"})
      (config-db/create-dataset! conn {:dataset-id "public-docs" :name "Public Docs"})
      (config-db/create-dataset-pipeline! conn {:pipeline-id "assistant"
                                                :dataset-id "public-docs"})
      (config-db/create-dataset-pipeline! conn {:pipeline-id "kudos"
                                                :dataset-id "public-docs"})
      (doseq [[tenant pipeline-id] [["ka" "assistant"] ["nav" "assistant"] ["ka" "kudos"]]]
        (let [base-node-id (config-db/dataset-base-node-id tenant "public-docs")
              materialization-node-id (config-db/dataset-materialization-node-id tenant "prod" "public-docs" pipeline-id)]
          (when-not (config-db/get-config-node @conn base-node-id)
            (config-db/create-config-node! conn {:root :dataset
                                                 :tenant tenant
                                                 :node-id base-node-id
                                                 :label "Default"
                                                 :tenant-config-key "default"}))
          (config-db/create-config-node! conn {:root :dataset
                                               :tenant tenant
                                               :node-id materialization-node-id
                                               :label (str pipeline-id " Materialization")
                                               :tenant-config-key (config-db/default-dataset-tenant-config-key "prod" pipeline-id)
                                               :parent-id base-node-id})))
      (let [contexts (config-db/materialization-contexts-by-pipeline-id @conn)]
        (is (= 2 (count (get contexts "assistant"))))
        (is (= 1 (count (get contexts "kudos"))))
        (is (= #{"ka" "nav"} (set (map :tenant (get contexts "assistant")))))
        (is (= #{"ka"} (set (map :tenant (get contexts "kudos"))))))
      (finally
        (delete-test-db conn)))))

(deftest test-platform-node-resolution-error-for-missing-tenant-root
  (let [conn (create-test-db)]
    (try
      (let [error (try
                    (config-db/resolve-platform-node! @conn {:tenant "ka"})
                    nil
                    (catch clojure.lang.ExceptionInfo e
                      e))
            data (ex-data error)]
        (is error)
        (is (str/includes? (.getMessage error) "Canonical tenant root node not found"))
        (is (str/includes? (.getMessage error) "tenant 'ka'"))
        (is (str/includes? (.getMessage error) "root :platform"))
        (is (= :platform (:root data)))
        (is (= "ka" (:tenant data)))
        (is (= "default" (:required-tenant-config-key data)))
        (is (= 0 (:available-node-count data)))
        (is (= [] (:available-root-nodes data))))
      (finally
        (delete-test-db conn)))))

(deftest test-platform-node-resolution-falls-back-to-tenant-root
  (let [conn (create-test-db)]
    (try
      (config-db/register-tenant! conn "digdir" {:name "Digdir"})
      (config-db/create-config-node! conn {:root :platform
                                           :tenant "digdir"
                                           :node-id "platform-default"
                                           :label "Default"
                                           :tenant-config-key "default"})
      ;; Without explicit node-id or tenant-config-key, falls back to tenant root
      (let [node (config-db/resolve-platform-node! @conn {:tenant "digdir"})]
        (is (= "platform-default" (:config.node/id node)))
        (is (= "default" (:config.node/tenant-config-key node))))
      ;; With explicit tenant-config-key, resolves directly
      (let [node (config-db/resolve-platform-node! @conn {:tenant "digdir"
                                                           :tenant-config-key "default"})]
        (is (= "platform-default" (:config.node/id node))))
      ;; Missing tenant root fails with clear error
      (let [error (try
                    (config-db/resolve-platform-node! @conn {:tenant "nonexistent"})
                    nil
                    (catch clojure.lang.ExceptionInfo e
                      e))]
        (is error)
        (is (str/includes? (.getMessage error) "Canonical tenant root node not found")))
      (finally
        (delete-test-db conn)))))

(deftest test-platform-node-resolution-error-includes-available-root-nodes
  (let [conn (create-test-db)]
    (try
      (config-db/register-tenant! conn "digdir" {:name "Digdir"})
      (config-db/create-config-node! conn {:root :platform
                                           :tenant "digdir"
                                           :node-id "platform-prod"
                                           :label "Prod"
                                           :tenant-config-key "prod"})
      (let [error (try
                    (config-db/resolve-platform-node! @conn {:tenant "digdir"})
                    nil
                    (catch clojure.lang.ExceptionInfo e
                      e))
            data (ex-data error)]
        (is error)
        (is (str/includes? (.getMessage error) "tenant-config-key 'default'"))
        (is (str/includes? (.getMessage error) "platform-prod"))
        (is (= 1 (:available-node-count data)))
        (is (= [{:config.node/id "platform-prod"
                 :config.node/label "Prod"
                 :config.node/tenant-config-key "prod"
                 :config.node/enabled? true
                 :config.node/system-managed? false}]
               (:available-root-nodes data))))
      (finally
        (delete-test-db conn)))))

(deftest test-preview-node-parent-change-diffs-before-and-after
  (let [conn (create-test-db)]
    (try
      (seed-definition! conn "skills.retrieval.top-k" :runtime :number)
      (config-db/create-config-node! conn {:root :runtime :tenant "ka" :node-id "root-a" :label "Root A" :tenant-config-key "root-a"})
      (config-db/create-config-node! conn {:root :runtime :tenant "ka" :node-id "root-b" :label "Root B" :tenant-config-key "root-b"})
      (config-db/create-config-node! conn {:root :runtime :tenant "ka" :node-id "leaf" :label "Leaf" :parent-id "root-a" :tenant-config-key "leaf"})
      (config-db/set-node-value! conn {:root :runtime :tenant "ka" :node-id "root-a" :path "skills.retrieval.top-k" :value 10 :master-key nil})
      (config-db/set-node-value! conn {:root :runtime :tenant "ka" :node-id "root-b" :path "skills.retrieval.top-k" :value 20 :master-key nil})
      (let [{:keys [before after current-parent-id preview-parent-id]}
            (config-db/preview-node-parent-change @conn
                                                  :runtime
                                                  "ka"
                                                  "leaf"
                                                  "root-b"
                                                  ["skills.retrieval.top-k"])]
        (is (= "root-a" current-parent-id))
        (is (= "root-b" preview-parent-id))
        (is (= "10" (get-in before [:results "skills.retrieval.top-k" :value :config.value/raw])))
        (is (= "20" (get-in after [:results "skills.retrieval.top-k" :value :config.value/raw]))))
      (finally
        (delete-test-db conn)))))

(deftest test-dataset-pipelines-are-listed-through-dataset-v2
  (let [conn (create-test-db)]
    (try
      (seed-definition! conn "pipeline.ui.name" :dataset :string)
      (config-db/register-tenant! conn "ka" {:name "KA"})
      (config-db/create-dataset! conn {:dataset-id "kudos" :name "Kudos"})
      (config-db/create-dataset-pipeline! conn {:pipeline-id "kudos-loader"
                                                :dataset-id "kudos"})
      (config-db/create-config-node! conn {:root :dataset
                                           :tenant "ka"
                                           :node-id "dataset/ka/kudos/default"
                                           :label "Default"
                                           :tenant-config-key "default"})
      (config-db/create-config-node! conn {:root :dataset
                                           :tenant "ka"
                                           :node-id "dataset/ka/kudos/default/kudos-loader/materialization"
                                           :label "Default Materialization"
                                           :parent-id "dataset/ka/kudos/default"
                                           :tenant-config-key (config-db/default-dataset-tenant-config-key "default" "kudos-loader")})
      (config-db/set-node-value! conn {:root :dataset
                                       :tenant "ka"
                                       :node-id "dataset/ka/kudos/default/kudos-loader/materialization"
                                       :path "pipeline.ui.name"
                                       :value "Kudos Loader"
                                       :master-key nil})
      (is (= ["kudos-loader"] (config-db/list-datasets @conn "ka")))
      (is (= {"kudos-loader" "Kudos Loader"}
             (config-db/get-dataset-names @conn nil ["kudos-loader"])))
      (finally
        (delete-test-db conn)))))

(deftest test-dataset-records-support-generated-global-ids
  (let [conn (create-test-db)]
    (try
      (let [created (config-db/create-dataset! conn {:name "Generated Dataset"})
            dataset-id (:dataset/id created)]
        (is (string? dataset-id))
        (is (str/starts-with? dataset-id "ds_"))
        (is (= "Generated Dataset" (:dataset/name created)))
        (is (= created
               (config-db/get-dataset-record @conn dataset-id)))
        (is (= [dataset-id]
               (mapv :dataset/id (config-db/list-dataset-records @conn)))))
      (finally
        (delete-test-db conn)))))

(deftest resolve-dataset-runtime-node-classifies-a-failed-ref-instead-of-crashing
  "A failed dataset resolution must produce a classified client error, not a cast.

   THE DEFECT. #434 added a branch that distinguishes `no datasets at all` from
   `a ref matching none`, and asked the question with
   `(get-datasets-by-tenant db tenant)` — passing a tenant STRING to a function
   that reduces over a COLLECTION. `reduce` walked the characters of \"demo\" and
   handed `\\d` to `clojure.string/blank?`, so every `tools/call` and every `/v1`
   chat returned a 500 ClassCastException.

   WHY NOTHING CAUGHT IT. The code runs ONLY after resolution has already
   failed — its whole job is to pick a nicer message. Anything exercising
   successful resolution never reaches this line, so no test did.

   ⚠️ WHY BOTH BRANCHES ARE ASSERTED, AND WHY ONE WOULD NOT DO. The obvious
   one-line fix is `(get-datasets-by-tenant db [tenant])`, which returns
   `{\"demo\" []}` — and `(seq {\"demo\" []})` is TRUTHY with zero datasets. That
   fix makes `any-datasets?` permanently true, so the `No datasets are
   configured` branch can never fire again: a crash swapped for a message that
   is always wrong. A test covering only the has-datasets branch passes against
   BOTH the correct fix and that broken one. Exercising both is the only thing
   that tells them apart."
  (testing "a tenant with NO datasets is told exactly that"
    (let [conn (create-test-db)]
      (try
        (config-db/register-tenant! conn "demo" {:name "Demo"})
        (let [e (is (thrown? clojure.lang.ExceptionInfo
                             (config-db/resolve-dataset-runtime-node!
                              @conn {:tenant "demo" :dataset-config-key "public-docs"})))]
          (is (= "No datasets are configured for this tenant" (ex-message e)))
          (is (= :no-datasets-configured (:digdir/client-error (ex-data e)))
              "the error boundary reads this key to choose a status"))
        (finally (delete-test-db conn)))))

  (testing "a tenant WITH datasets but a ref matching none is told that instead"
    (let [conn (create-test-db)]
      (try
        (seed-definition! conn "pipeline.ui.name" :dataset :string)
        (config-db/register-tenant! conn "demo" {:name "Demo"})
        (config-db/create-dataset! conn {:dataset-id "norquad" :name "NorQuAD"})
        (config-db/create-dataset-pipeline! conn {:pipeline-id "norquad-docs"
                                                 :dataset-id "norquad"})
        (config-db/create-config-node! conn {:root :dataset
                                             :tenant "demo"
                                             :node-id "dataset/demo/norquad/default"
                                             :label "Default"
                                             :tenant-config-key "default"})
        (config-db/create-config-node! conn {:root :dataset
                                             :tenant "demo"
                                             :node-id "dataset/demo/norquad/default/norquad-docs/materialization"
                                             :label "Default Materialization"
                                             :parent-id "dataset/demo/norquad/default"
                                             :tenant-config-key (config-db/default-dataset-tenant-config-key "default" "norquad-docs")})
        (testing "control: this tenant really does have a dataset, so the branch above
                  is reachable and this one is not passing by having nothing either"
          (is (seq (config-db/list-datasets @conn "demo"))))
        (let [e (is (thrown? clojure.lang.ExceptionInfo
                             (config-db/resolve-dataset-runtime-node!
                              @conn {:tenant "demo" :dataset-config-key "no-such-key"})))]
          (is (= "Dataset ref does not match any configured dataset" (ex-message e)))
          (is (= :dataset-ref-unknown (:digdir/client-error (ex-data e)))))
        (finally (delete-test-db conn))))))

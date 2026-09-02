(ns digdir.data.db-test
  (:require [clojure.test :refer [deftest testing is]]
            [datahike.api :as d]
            [digdir.data.db :as db]))

(defn create-test-db
  []
  (let [cfg {:store {:backend :mem
                     :id (str "data-db-test-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data db/dh-schema})
      conn)))

(defn delete-test-db
  [conn]
  (let [cfg (:config @(:wrapped-atom conn))]
    (d/release conn)
    (d/delete-database cfg)))

(deftest test-main-db-schema-supports-api-key-grants-and-conversation-agent-id
  (testing "Main DB schema accepts structured API key grants and agent-scoped conversations"
    (let [conn (create-test-db)]
      (try
        (d/transact conn
                    {:tx-data [{:db/id -1
                                :api-key.dataset-scope/id "dataset-scope-1"
                                :api-key.dataset-scope/tenant "altinn-docs"
                                :api-key.dataset-scope/dataset-config-key "dev"}
                               {:db/id -2
                                :api-key.agent-ref/id "agent-ref-1"
                                :api-key.agent-ref/agent-id "altinn-docs-assistant"}
                               {:api-key/id "key-1"
                                :api-key/key "rag_test_key"
                                :api-key/name "Phase 1 Test Key"
                                :api-key/created 1
                                :api-key/created-by "user-1"
                                :api-key/revoked false
                                :api-key/scopes [:query]
                                :api-key/usage-count 0
                                :api-key/dataset-scopes [-1]
                                :api-key/agent-refs [-2]}
                               {:conversation/id "conversation-1"
                                :conversation/topic "Test conversation"
                                :conversation/agent-id "altinn-docs-assistant"
                                :conversation/tags ["alpha" "beta"]}
                               {:message/id "message-1"
                                :message/text "Hello"
                                :message/tags ["note"]}]})

        (let [api-key (d/pull @conn '[* {:api-key/dataset-scopes [*]}
                                     {:api-key/agent-refs [*]}]
                              [:api-key/id "key-1"])
              conversation (d/pull @conn '[*] [:conversation/id "conversation-1"])
              message (d/pull @conn '[*] [:message/id "message-1"])]
          (is (= "Phase 1 Test Key" (:api-key/name api-key)))
          (is (= [{:api-key.dataset-scope/id "dataset-scope-1"
                   :api-key.dataset-scope/tenant "altinn-docs"
                   :api-key.dataset-scope/dataset-config-key "dev"}]
                 (mapv #(select-keys % [:api-key.dataset-scope/id
                                        :api-key.dataset-scope/tenant
                                        :api-key.dataset-scope/dataset-config-key])
                       (:api-key/dataset-scopes api-key))))
          (is (= [{:api-key.agent-ref/id "agent-ref-1"
                   :api-key.agent-ref/agent-id "altinn-docs-assistant"}]
                 (mapv #(select-keys % [:api-key.agent-ref/id
                                        :api-key.agent-ref/agent-id])
                          (:api-key/agent-refs api-key))))
          (is (= "altinn-docs-assistant" (:conversation/agent-id conversation)))
          (is (= ["alpha" "beta"] (:conversation/tags conversation)))
          (is (= ["note"] (:message/tags message))))
        (finally
          (delete-test-db conn))))))

(deftest ensure-api-key-grant-schema-adds-missing-structural-attrs
  (testing "Startup schema repair adds missing API key grant schema attrs"
    (let [cfg {:store {:backend :mem
                       :id (str "data-db-schema-repair-test-" (random-uuid))}
               :schema-flexibility :read}]
      (d/create-database cfg)
      (let [conn (d/connect cfg)]
        (try
          (d/transact conn
                      {:tx-data [{:db/ident :api-key.agent-ref/id
                                  :db/valueType :db.type/string
                                  :db/cardinality :db.cardinality/one
                                  :db/unique :db.unique/identity}]})
          (when-let [tx (#'digdir.data.db/ensure-api-key-grant-schema-tx @conn)]
            (d/transact conn {:tx-data tx}))
          (doseq [[ident expected] [[:api-key/dataset-scopes {:db/valueType :db.type/ref
                                                              :db/cardinality :db.cardinality/many}]
                                    [:api-key.dataset-scope/id {:db/valueType :db.type/string
                                                                :db/cardinality :db.cardinality/one
                                                                :db/unique :db.unique/identity}]
                                    [:api-key/allowed-config-keys {:db/valueType :db.type/ref
                                                                   :db/cardinality :db.cardinality/many}]
                                    [:api-key.allowed-config-key/id {:db/valueType :db.type/string
                                                                     :db/cardinality :db.cardinality/one
                                                                     :db/unique :db.unique/identity}]
                                    [:api-key.agent-ref/id {:db/valueType :db.type/string
                                                            :db/cardinality :db.cardinality/one
                                                            :db/unique :db.unique/identity}]]]
            (let [schema-entity (d/q '[:find (pull ?e [:db/ident :db/valueType :db/cardinality :db/unique]) .
                                       :in $ ?ident
                                       :where [?e :db/ident ?ident]]
                                     @conn
                                     ident)]
              (is schema-entity)
              (is (= (:db/valueType expected) (:db/valueType schema-entity)))
              (is (= (:db/cardinality expected) (:db/cardinality schema-entity)))
              (when (contains? expected :db/unique)
                (is (= (:db/unique expected) (:db/unique schema-entity))))))
          (doseq [ident [:api-key.dataset-scope/id
                         :api-key.agent-ref/id
                         :api-key.allowed-config-key/id]]
            (is (= :db.unique/identity
                   (:db/unique
                    (d/q '[:find (pull ?e [:db/ident :db/unique]) .
                           :in $ ?ident
                           :where [?e :db/ident ?ident]]
                         @conn
                         ident)))))
          (finally
            (d/release conn)
            (d/delete-database cfg)))))))

(deftest test-conversation-writes-persist-agent-id
  (testing "Conversation creation helpers store agent-scoped identity"
    (let [conn (create-test-db)]
      (try
        (let [{api-conversation-id :conversation-id}
              (db/transact-new-msg-thread conn "builtin/agent-rag-agent" "user-1" {:source "api"})
              conversation (d/pull @conn '[*] [:conversation/id api-conversation-id])
              {playground-conversation-id :conversation-id}
              (db/create-playground-conversation conn "builtin/retrieve-only-agent"
                                                {:user-id "user-1"
                                                 :tenant "ka"
                                                 :dataset-config-key "dev"})
              playground-conversation (d/pull @conn '[*] [:conversation/id playground-conversation-id])]
          (is (= "builtin/agent-rag-agent" (:conversation/agent-id conversation)))
          (is (= :playground (:conversation/type playground-conversation)))
          (is (= "builtin/retrieve-only-agent" (:conversation/agent-id playground-conversation)))
          (is (= "dev" (:conversation/dataset-config-key playground-conversation)))
          (is (nil? (:conversation/tenant-config-key playground-conversation)))
          (is (nil? (:conversation/pipeline playground-conversation))))
        (finally
          (delete-test-db conn))))))

(deftest playground-conversations-by-user-returns-user-scoped-pulls
  (testing "Playground conversation listing does not hit the Datahike pull parser path"
    (let [conn (create-test-db)]
      (try
        (let [{convo-id-1 :conversation-id}
              (db/create-playground-conversation conn "agent-1"
                                                {:user-id "user-1"
                                                 :tenant "ka"
                                                 :dataset-config-key "dev"})
              {_convo-id-2 :conversation-id}
              (db/create-playground-conversation conn "agent-2"
                                                {:user-id "user-2"
                                                 :tenant "kb"
                                                 :dataset-config-key "prod"})
              conversations (db/playground-conversations-by-user @conn "user-1")]
          (is (= 1 (count conversations)))
          (is (= convo-id-1 (:conversation/id (first conversations))))
          (is (= :playground (:conversation/type (first conversations))))
          (is (= "agent-1" (:conversation/agent-id (first conversations))))
          (is (= "dev" (:conversation/dataset-config-key (first conversations))))
          (is (nil? (:conversation/pipeline (first conversations)))))
        (finally
          (delete-test-db conn))))))

(deftest playground-sidebar-searches-the-users-full-conversation-set
  (let [conn (create-test-db)]
    (try
      (let [{first-id :conversation-id}
            (db/create-playground-conversation conn "agent/docs"
                                               {:user-id "user-1"
                                                :tenant "digdir"
                                                :dataset-config-key "public-docs"})
            {second-id :conversation-id}
            (db/create-playground-conversation conn "agent/research"
                                               {:user-id "user-1"
                                                :tenant "nav"
                                                :dataset-config-key "regulations"})]
        (d/transact conn {:tx-data [{:db/id [:conversation/id first-id]
                                     :conversation/topic "Altinn onboarding"}
                                    {:db/id [:conversation/id second-id]
                                     :conversation/topic "Policy review"}]})
        (testing "Search is case-insensitive and runs before pagination"
          (let [page (db/playground-conversation-sidebar-page
                      @conn "user-1" {:limit 1 :query "ALTINN"})]
            (is (= [first-id] (mapv :conversation/id (:items page))))
            (is (= 1 (:total-count page)))
            (is (= 2 (:user-total-count page)))))
        (testing "Scope and agent identifiers are searchable too"
          (is (= [second-id]
                 (mapv :conversation/id
                       (:items (db/playground-conversation-sidebar-page
                                @conn "user-1" {:query "regulations"})))))))
      (finally
        (delete-test-db conn)))))

(deftest clear-user-playground-conversations-is-complete-and-user-scoped
  (let [conn (create-test-db)]
    (try
      (let [{user-convo-1 :conversation-id}
            (db/create-playground-conversation conn "agent-1" {:user-id "user-1"})
            {user-convo-2 :conversation-id}
            (db/create-playground-conversation conn "agent-2" {:user-id "user-1"})
            {other-convo :conversation-id}
            (db/create-playground-conversation conn "agent-3" {:user-id "user-2"})]
        (d/transact conn
                    {:tx-data [{:db/id [:conversation/id user-convo-1]
                                :conversation/messages [{:message/id "user-message-1"
                                                         :message/text "one"}]}
                               {:db/id [:conversation/id user-convo-2]
                                :conversation/messages [{:message/id "user-message-2"
                                                         :message/text "two"}]}
                               {:db/id [:conversation/id other-convo]
                                :conversation/messages [{:message/id "other-message"
                                                         :message/text "keep"}]}]})
        (is (= 2 (db/clear-user-playground-conversations conn "user-1")))
        (is (empty? (db/playground-conversations-by-user @conn "user-1")))
        (is (= [other-convo]
               (mapv :conversation/id
                     (db/playground-conversations-by-user @conn "user-2"))))
        (is (nil? (d/q '[:find ?e .
                         :in $ ?message-id
                         :where [?e :message/id ?message-id]]
                       @conn "user-message-1")))
        (is (nil? (d/q '[:find ?e .
                         :in $ ?message-id
                         :where [?e :message/id ?message-id]]
                       @conn "user-message-2")))
        (is (= "keep" (:message/text (d/pull @conn '[*] [:message/id "other-message"])))))
      (finally
        (delete-test-db conn)))))

(deftest conversation-listing-helpers-pull-by-id
  (testing "Conversation listing helpers query ids first and then pull entities"
    (let [conn (create-test-db)]
      (try
        (d/transact conn
                    {:tx-data [{:conversation/id "conversation-1"
                                :conversation/topic "Newest"
                                :conversation/created 20
                                :conversation/agent-id "agent-1"
                                :conversation/user-id "user-1"
                                :conversation/tags ["alpha" "beta"]}
                               {:conversation/id "conversation-2"
                                :conversation/topic "Older"
                                :conversation/created 10
                                :conversation/agent-id "agent-2"
                                :conversation/user-id "user-2"}
                               {:conversation/id "conversation-3"
                                :conversation/topic "Folder child"
                                :conversation/created 30
                                :conversation/agent-id "agent-3"
                                :conversation/user-id "user-3"
                                :conversation/folder "folder-1"}]})
        (let [all-conversations (db/conversations @conn)
              alpha-conversations (db/conversations @conn ["alpha"])
              user-conversations (db/conversations-by-user @conn "user-1")
              alpha-user-conversations (db/conversations-by-user @conn "user-1" ["alpha"])
              orphaned-conversations (db/orphan-conversations @conn)]
          (is (= ["conversation-1" "conversation-2"]
                 (mapv :conversation/id all-conversations)))
          (is (= ["conversation-1"]
                 (mapv :conversation/id alpha-conversations)))
          (is (= ["conversation-1"]
                 (mapv :conversation/id user-conversations)))
          (is (= ["conversation-1"]
                 (mapv :conversation/id alpha-user-conversations)))
          (is (empty? orphaned-conversations)))
        (finally
          (delete-test-db conn))))))

(deftest conversation-by-id-reads-back-api-conversation
  (testing "Conversation lookup by id avoids inline pull query parsing failures"
    (let [conn (create-test-db)]
      (try
        (let [{convo-id :conversation-id}
              (db/transact-new-msg-thread conn "builtin/agent-rag-agent" "user-1" {:source "api"})
              _ (db/set-conversation-tags conn convo-id ["alpha" "beta"])
              conversation (db/conversation-by-id @conn convo-id)]
          (is (= convo-id (:conversation/id conversation)))
          (is (= "builtin/agent-rag-agent" (:conversation/agent-id conversation)))
          (is (= "user-1" (:conversation/user-id conversation)))
          (is (= "Ny tråd" (:conversation/topic conversation)))
          (is (= ["alpha" "beta"] (:conversation/tags conversation))))
        (finally
          (delete-test-db conn))))))

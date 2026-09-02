(ns digdir.import-export.system-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [digdir.agents.db :as agents-db]
            [digdir.config.api-keys :as api-keys]
            [digdir.config.db :as config-db]
            [digdir.config.env-bridge :as env-bridge]
            [digdir.config.schema :as config-schema]
            [digdir.config.verify :as config-verify]
            [digdir.data.db :as data-db]
            [digdir.import-export.canonical.system :as canonical-system]
            [digdir.import-export.import :as import-impl]
            [digdir.import-export.system :as migration]
            [digdir.skills.api :as skills-api]))

(defn with-initialized-skills
  [f]
  (skills-api/reset-skills!)
  (skills-api/initialize!)
  (f)
  (skills-api/reset-skills!))

(use-fixtures :each with-initialized-skills)

(defn- create-config-test-db
  []
  (let [cfg {:store {:backend :mem
                     :id (str "migration-config-test-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data config-schema/config-migration-schema})
      conn)))

(defn- create-main-test-db
  []
  (let [cfg {:store {:backend :mem
                     :id (str "migration-main-test-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data data-db/dh-schema})
      conn)))

(defn- delete-test-db
  [conn]
  (let [cfg (:config @(:wrapped-atom conn))]
    (d/release conn)
    (d/delete-database cfg)))

(def ^:private sample-dataset-scope
  {:tenant "altinn-docs"
   :dataset-config-key "dev"})

(def ^:private sample-pipeline-id
  "altinn-docs:dev:assistant")

(defn- seed-system-fixture!
  [config-conn main-conn]
  (d/transact config-conn
              {:tx-data [{:permission/id "admin-full"
                          :permission/name "Full Admin Access"
                          :permission/description "Full read/write access"
                          :permission/attributes (pr-str {:service :*
                                                          :sensitivity :*
                                                          :function :*})
                          :permission/tenants (pr-str :*)
                          :permission/environments (pr-str :*)
                          :permission/actions (pr-str #{:read :write})}
                         {:user/id "user-1"
                          :user/email "bdb@itonomi.com"
                          :user/created 1
                          :user/permissions [[:permission/id "admin-full"]]}]})
  (config-db/upsert-definition! config-conn
                                {:path "skills.rerank.top-k"
                                 :root :runtime
                                 :value-type :number
                                 :encrypted? false
                                 :category :skills
                                 :service :search
                                 :sensitivity :internal
                                 :function :settings})
  (config-db/create-dataset! config-conn
                             {:dataset-id "dataset/altinn-docs-assistant"
                              :name "Altinn Docs Assistant"})
  (config-db/create-dataset-pipeline! config-conn
                                      {:pipeline-id "pipeline/altinn-docs-assistant"
                                       :dataset-id "dataset/altinn-docs-assistant"
                                       :name "Altinn Docs Assistant Pipeline"
                                       :source-type :sharepoint})
  (config-db/create-config-node! config-conn
                                 {:root :dataset
                                  :tenant "altinn-docs"
                                  :node-id "dataset/altinn-docs/dev"
                                  :label "Dev Dataset"
                                  :tenant-config-key "dev"})
  (config-db/create-config-node! config-conn
                                 {:root :runtime
                                  :tenant "altinn-docs"
                                  :node-id "runtime/altinn-docs/default"
                                  :label "Default"
                                  :tenant-config-key "default"})
  (config-db/create-config-node! config-conn
                                 {:root :runtime
                                  :tenant "altinn-docs"
                                  :node-id "runtime/altinn-docs/default-runtime"
                                  :label "Default Runtime"
                                  :tenant-config-key "default-runtime"
                                  :parent-id "runtime/altinn-docs/default"})
  (config-db/set-node-value! config-conn
                             {:root :runtime
                              :tenant "altinn-docs"
                              :node-id "runtime/altinn-docs/default"
                              :path "skills.rerank.top-k"
                              :value 25})
  (agents-db/upsert-agent! config-conn
                           {:id "agent/altinn-docs"
                            :name "Altinn Docs Agent"
                            :description "Answers over Altinn docs"
                            :instructions "Use grounded retrieval."
                            :default-skill-graph "builtin/agent-rag-graph-bundled"
                            :allowed-skill-graphs ["builtin/agent-rag-graph-bundled"]
                            :allowed-dataset-scopes [sample-dataset-scope]
                            :guardrails {:citations-required true}
                            :enabled? true})
  (let [{:keys [api-key-id]} (api-keys/store-api-key main-conn
                                                     "rag_roundtrip"
                                                     "Roundtrip Key"
                                                     "user-1"
                                                     {:dataset-scopes [sample-dataset-scope]
                                                      :agent-refs ["agent/altinn-docs"]
                                                      :skill-graphs ["builtin/agent-rag-graph-bundled"]
                                                      :scopes #{:query :admin}})]
    (d/transact main-conn
                {:tx-data [{:db/id -1
                            :api-key.allowed-config-key/id "cfg-ceiling-runtime-altinn-docs"
                            :api-key.allowed-config-key/root :runtime
                            :api-key.allowed-config-key/tenant "altinn-docs"
                            :api-key.allowed-config-key/node-id "runtime/altinn-docs/default-runtime"
                            :api-key.allowed-config-key/tenant-config-key "default-runtime"}
                           {:db/id -2
                            :api-key.allowed-config-key/id "cfg-ceiling-dataset-altinn-docs"
                            :api-key.allowed-config-key/root :dataset
                            :api-key.allowed-config-key/tenant "altinn-docs"
                            :api-key.allowed-config-key/node-id "dataset/altinn-docs/dev"
                            :api-key.allowed-config-key/tenant-config-key "dev"}
                           {:db/id [:api-key/id api-key-id]
                            :api-key/allowed-config-keys #{-1 -2}}]}))
  (d/transact main-conn
              {:tx-data [{:folder/id "folder-1"
                          :folder/name "Exports"
                          :folder/created 10}
                         {:conversation/id "conversation-1"
                          :conversation/topic "Roundtrip conversation"
                          :conversation/created 11
                          :conversation/agent-id "agent/altinn-docs"
                          :conversation/pipeline "assistant"
                          :conversation/user-id "user-1"
                          :conversation/type :playground
                          :conversation/tenant "altinn-docs"
                          :conversation/environment "dev"
                          :conversation/view-mode :focused
                          :conversation/folder [:folder/id "folder-1"]
                          :conversation/messages [{:message/id "message-1"
                                                   :message/text "What changed?"
                                                   :message/role :user
                                                   :message/voice :user
                                                   :message/completion true
                                                   :message/kind :kind/markdown
                                                   :message/created 11}
                                                  {:message/id "message-2"
                                                   :message/text "Here is the grounded answer."
                                                   :message/role :assistant
                                                   :message/voice :assistant
                                                   :message/completion true
                                                   :message/kind :kind/markdown
                                                   :message/created 12
                                                   :message/config (pr-str {:agent-id "agent/altinn-docs"})
                                                   :message/diagnostics (pr-str {:trace ["retrieve" "synthesize"]})
                                                   :message/execution-id "exec-1"
                                                   :message/branch-index 0
                                                   :message/parent-message [:message/id "message-1"]
                                                   :message/chunks [{:chunk/doc-num "1"
                                                                     :chunk/doc-title "Altinn Docs"
                                                                     :chunk/chunk-id "chunk-1"
                                                                     :chunk/content-markdown "Chunk content"
                                                                     :chunk/url "https://example.com/docs"
                                                                     :chunk/metadata (pr-str {:source "test"})
                                                                     :chunk/sha "abc123"}]}]}]})
  {:api-key "rag_roundtrip"
   :agent-id "agent/altinn-docs"})

(deftest test-export-import-system-roundtrip
  (let [config-conn (create-config-test-db)
        main-conn (create-main-test-db)
        fresh-config-conn (create-config-test-db)
        fresh-main-conn (create-main-test-db)]
    (try
      (seed-system-fixture! config-conn main-conn)
      (let [exported (migration/export-system config-conn main-conn {:include-audit? false})
            imported (migration/import-system fresh-config-conn fresh-main-conn exported {:on-conflict :skip})]
        (testing "export contains the expected full-system sections"
          (is (= "2.0" (:version exported)))
          (is (= 1 (get-in exported [:report :users])))
          (is (= 1 (get-in exported [:report :agents])))
          (is (= 1 (get-in exported [:report :api-keys])))
          (is (= 3 (get-in exported [:report :nodes])))
          (is (= 0 (get-in exported [:report :bindings])))
          (is (= 0 (get-in exported [:report :compatibilities])))
          (is (= 1 (get-in exported [:report :datasets])))
          (is (= 1 (get-in exported [:report :dataset-pipelines])))
          (is (= 1 (get-in exported [:report :node-values])))
          (is (= 1 (get-in exported [:report :folders])))
          (is (= 1 (get-in exported [:report :conversations]))))
        (testing "import reports the migrated entities"
          (is (nil? (:config imported)))
          (is (nil? (:api-keys imported)))
          (is (= 1 (get-in imported [:entities :config :definitions :created])))
          (is (= 3 (get-in imported [:entities :config :nodes :created])))
          (is (= 1 (get-in imported [:entities :config :datasets :created])))
          (is (= 1 (get-in imported [:entities :config :dataset-pipeline-result :created])))
          (is (= 1 (get-in imported [:entities :config :node-values :created])))
          (is (= 1 (get-in imported [:entities :users :created])))
          (is (= 1 (get-in imported [:entities :agents :imported])))
          (is (= 1 (get-in imported [:entities :folders :created])))
          (is (= 1 (get-in imported [:entities :api-keys :created])))
          (is (= 1 (get-in imported [:entities :conversations :created]))))
        (testing "agents roundtrip into the config domain on the fresh target table"
          (is (= {:tenant "altinn-docs"
                  :dataset-config-key "dev"}
                 (first (:allowed-dataset-scopes
                         (agents-db/get-agent @fresh-config-conn "agent/altinn-docs"))))))
        (testing "config-domain runtime entities roundtrip into the fresh target table"
          (is (= #{"runtime/altinn-docs/default" "runtime/altinn-docs/default-runtime"}
                 (set (map :config.node/id
                           (config-db/list-config-nodes @fresh-config-conn "altinn-docs" :runtime)))))
          (is (= #{"dataset/altinn-docs-assistant"}
                 (set (map :dataset/id (config-db/list-dataset-records @fresh-config-conn)))))
          (is (= #{"pipeline/altinn-docs-assistant"}
                 (set (map :dataset.pipeline/id (config-db/list-dataset-pipelines @fresh-config-conn)))))
          (is (= "25"
                 (get-in (config-db/get-node-value @fresh-config-conn
                                                  :runtime
                                                  "altinn-docs"
                                                  "runtime/altinn-docs/default"
                                                  "skills.rerank.top-k")
                         [:config.value/raw]))))
        (testing "config-domain users roundtrip into the fresh target table"
          (is (= {:user/id "user-1"
                  :user/email "bdb@itonomi.com"}
                 (select-keys
                  (d/pull @fresh-config-conn '[:user/id :user/email {:user/permissions [:permission/id]}]
                          [:user/id "user-1"])
                  [:user/id :user/email])))
          (is (= ["admin-full"]
                 (mapv :permission/id
                       (:user/permissions
                        (d/pull @fresh-config-conn '[:user/id :user/email {:user/permissions [:permission/id]}]
                                [:user/id "user-1"]))))))
        (testing "app-domain API key ceilings roundtrip into the fresh target table"
          (let [api-key-id (d/q '[:find ?api-key-id .
                                  :in $ ?name
                                  :where
                                  [?e :api-key/name ?name]
                                  [?e :api-key/id ?api-key-id]]
                                @fresh-main-conn
                                "Roundtrip Key")
                api-key (d/pull @fresh-main-conn
                                '[:api-key/name
                                  {:api-key/dataset-scopes [:api-key.dataset-scope/tenant
                                                          :api-key.dataset-scope/dataset-config-key]}
                                  {:api-key/agent-refs [:api-key.agent-ref/agent-id]}
                                  {:api-key/allowed-config-keys [:api-key.allowed-config-key/root
                                                             :api-key.allowed-config-key/tenant
                                                             :api-key.allowed-config-key/node-id
                                                             :api-key.allowed-config-key/tenant-config-key]}]
                                [:api-key/id api-key-id])]
            (is (= "Roundtrip Key" (:api-key/name api-key)))
            (is (= [{:api-key.dataset-scope/tenant "altinn-docs"
                     :api-key.dataset-scope/dataset-config-key "dev"}]
                   (:api-key/dataset-scopes api-key)))
            (is (= [{:api-key.agent-ref/agent-id "agent/altinn-docs"}]
                   (:api-key/agent-refs api-key)))
            (is (= [{:api-key.allowed-config-key/root :dataset
                     :api-key.allowed-config-key/tenant "altinn-docs"
                     :api-key.allowed-config-key/node-id "dataset/altinn-docs/dev"
                     :api-key.allowed-config-key/tenant-config-key "default"}
                    {:api-key.allowed-config-key/root :runtime
                     :api-key.allowed-config-key/tenant "altinn-docs"
                     :api-key.allowed-config-key/node-id "runtime/altinn-docs/default-runtime"
                     :api-key.allowed-config-key/tenant-config-key "default-runtime"}]
                   (:api-key/allowed-config-keys api-key))))
        (testing "app-domain conversations and nested messages roundtrip into the fresh target table"
          (let [conversation (d/pull @fresh-main-conn
                                     '[:conversation/id
                                       :conversation/topic
                                       :conversation/agent-id
                                       :conversation/pipeline
                                       :conversation/type
                                       :conversation/tenant
                                       :conversation/environment
                                       :conversation/view-mode
                                       {:conversation/folder [:folder/id]}
                                       {:conversation/messages
                                        [:message/id
                                         :message/execution-id
                                         :message/branch-index
                                         {:message/parent-message [:message/id]}
                                         {:message/chunks [:chunk/chunk-id :chunk/doc-title]}]}]
                                     [:conversation/id "conversation-1"])
                messages-by-id (->> (:conversation/messages conversation)
                                    (sort-by :message/id)
                                    vec)
                assistant-message (some #(when (= "message-2" (:message/id %)) %) messages-by-id)]
            (is (= "agent/altinn-docs" (:conversation/agent-id conversation)))
            (is (= "assistant" (:conversation/pipeline conversation)))
            (is (= "folder-1" (get-in conversation [:conversation/folder :folder/id])))
            (is (= 2 (count messages-by-id)))
            (is (= "message-2" (:message/id assistant-message)))
            (is (= "exec-1" (:message/execution-id assistant-message)))
            (is (= [{:chunk/chunk-id "chunk-1"
                     :chunk/doc-title "Altinn Docs"}]
                   (get-in assistant-message [:message/chunks])))))))
      (finally
        (delete-test-db fresh-main-conn)
        (delete-test-db fresh-config-conn)
        (delete-test-db main-conn)
        (delete-test-db config-conn)))))

(deftest test-export-system-uses-canonical-export-module
  (let [config-conn (create-config-test-db)
        main-conn (create-main-test-db)]
    (try
      (seed-system-fixture! config-conn main-conn)
      (with-redefs [canonical-system/canonicalize-system-export
                    (fn [data]
                      (assoc-in data [:data :folders] [{:folder/id "sentinel"
                                                       :folder/name "Sentinel"}]))]
        (let [exported (migration/export-system config-conn main-conn {:include-audit? false})]
          (is (= [{:folder/id "sentinel"
                   :folder/name "Sentinel"}]
                 (get-in exported [:data :folders])))))
      (finally
        (delete-test-db main-conn)
        (delete-test-db config-conn)))))

(deftest test-preview-import-system-reports-creates-for-fresh-target
  (let [config-conn (create-config-test-db)
        main-conn (create-main-test-db)
        fresh-config-conn (create-config-test-db)
        fresh-main-conn (create-main-test-db)]
    (try
      (seed-system-fixture! config-conn main-conn)
      (let [exported (migration/export-system config-conn main-conn {:include-audit? false})
            preview (migration/preview-import-system fresh-config-conn fresh-main-conn exported {:on-conflict :skip})]
        (is (= :preview (:mode preview)))
        (is (= :skip (:on-conflict preview)))
        (is (nil? (:config preview)))
        (is (nil? (:api-keys preview)))
        (is (= 1 (get-in preview [:entities :config :definitions :would-create])))
        (is (= 3 (get-in preview [:entities :config :nodes :would-create])))
        (is (= 1 (get-in preview [:entities :config :datasets :would-create])))
        (is (= 1 (get-in preview [:entities :config :dataset-pipelines :would-create])))
        (is (= 1 (get-in preview [:entities :config :node-values :would-create])))
        (is (= 1 (get-in preview [:entities :users :would-create])))
        (is (= 1 (get-in preview [:entities :agents :would-create])))
        (is (= 1 (get-in preview [:entities :folders :would-create])))
        (is (= 1 (get-in preview [:entities :api-keys :would-create])))
        (is (= 1 (get-in preview [:entities :conversations :would-create])))
        (is (= 12 (get-in preview [:summary :would-create]))))
      (finally
        (delete-test-db fresh-main-conn)
        (delete-test-db fresh-config-conn)
        (delete-test-db main-conn)
        (delete-test-db config-conn)))))

(deftest test-import-system-overwrite-replaces-conflicting-data
  (let [config-conn (create-config-test-db)
        main-conn (create-main-test-db)
        fresh-config-conn (create-config-test-db)
        fresh-main-conn (create-main-test-db)]
    (try
      (seed-system-fixture! config-conn main-conn)
      (let [exported (migration/export-system config-conn main-conn {:include-audit? false})
            _ (migration/import-system fresh-config-conn fresh-main-conn exported {:on-conflict :skip})
            api-key-id (get-in exported [:data :api-keys 0 :api-key/id])
            overwritten-export (-> exported
                                   (assoc-in [:data :node-values 0 "config.value/raw"] "30")
                                   (assoc-in [:data :agents 0 :name] "Altinn Docs Agent v2")
                                   (assoc-in [:data :api-keys 0 :api-key/name] "Roundtrip Key v2")
                                   (assoc-in [:data :folders 0 :folder/name] "Exports v2")
                                   (assoc-in [:data :conversations 0 :conversation/topic] "Roundtrip conversation v2"))
            imported (migration/import-system fresh-config-conn fresh-main-conn overwritten-export {:on-conflict :overwrite})]
        (testing "overwrite counts are reported"
          (is (= 1 (get-in imported [:entities :config :node-values :updated])))
          (is (= 1 (get-in imported [:entities :folders :overwritten])))
          (is (= 1 (get-in imported [:entities :api-keys :overwritten])))
          (is (= 1 (get-in imported [:entities :conversations :overwritten]))))

        (testing "overwritten entities reflect the new export contents"
          (is (= "30"
                 (get-in (config-db/get-node-value @fresh-config-conn
                                                   :runtime
                                                   "altinn-docs"
                                                   "runtime/altinn-docs/default"
                                                   "skills.rerank.top-k")
                         [:config.value/raw])))
          (is (= "Altinn Docs Agent v2"
                 (:name (agents-db/get-agent @fresh-config-conn "agent/altinn-docs"))))
          (is (= "Roundtrip Key v2"
                 (:api-key/name (api-keys/get-api-key-info fresh-main-conn api-key-id))))
          (is (= "Exports v2"
                 (:folder/name (d/pull @fresh-main-conn [:folder/name] [:folder/id "folder-1"]))))
          (is (= "Roundtrip conversation v2"
                 (:conversation/topic (d/pull @fresh-main-conn [:conversation/topic]
                                              [:conversation/id "conversation-1"]))))))
      (finally
        (delete-test-db fresh-main-conn)
        (delete-test-db fresh-config-conn)
        (delete-test-db main-conn)
        (delete-test-db config-conn)))))

(deftest test-file-based-import-export-helpers
  (let [config-conn (create-config-test-db)
        main-conn (create-main-test-db)
        fresh-config-conn (create-config-test-db)
        fresh-main-conn (create-main-test-db)
        export-file (.getAbsolutePath (java.io.File/createTempFile "system-export-" ".json"))]
    (try
      (seed-system-fixture! config-conn main-conn)
      (let [export-result (migration/export-to-file config-conn main-conn export-file {:include-audit? false})
            exported-json (json/parse-string (slurp export-file) true)
            import-result (migration/import-from-file fresh-config-conn fresh-main-conn export-file {:on-conflict :skip})]
        (testing "export-to-file writes the 2.0 system envelope"
          (is (= export-file (:file-path export-result)))
          (is (= "2.0" (:version exported-json)))
          (is (= 1 (get-in export-result [:report :api-keys])))
          (is (= 0 (count (get-in exported-json [:data :compatibilities])))))

        (testing "import-from-file loads the canonical system export into fresh databases"
          (is (= 1 (get-in import-result [:entities :api-keys :created])))
          (is (= 1 (count (api-keys/list-all-api-keys @fresh-main-conn))))
          (let [agent-ids (set (map :id (agents-db/list-agents @fresh-config-conn)))]
            (is (contains? agent-ids "agent/altinn-docs"))
            (is (contains? agent-ids "builtin/agent-rag-agent"))
            ;; All four builtin definitions seed here, plus the custom
            ;; agent/altinn-docs from the fixture. digdir/altinn-docs-tuned is
            ;; the opt-in Round-5 retrieval agent added purely additively; it
            ;; seeds because its skill graphs are the always-registered
            ;; builtin/agent-rag-graph-* ones.
            ;;
            ;; builtin/docs-agent seeds too, as of #91. It did not before:
            ;; seed-builtin-agents! filters an agent's graphs against the live
            ;; registry, this namespace's fixture calls reset-skills!, and the
            ;; initialiser that ran afterwards re-registered only the builtins —
            ;; so docs-agent was left with none of its docs/* graphs and was
            ;; skipped. Now that both initialisers are one door, a reset is
            ;; followed by a full re-registration, the docs/* graphs are present,
            ;; and the filter correctly keeps the agent. This is the availability
            ;; filter working, not weakening: a production build has no src-dev
            ;; on the classpath, so those graphs are absent and docs-agent is
            ;; still skipped there. It also ships :enabled? false (#89), so
            ;; seeding it does not turn anything on.
            ;;
            ;; The three #240 agents seed here too, as of 2026-08-24. They are
            ;; the agents #81 dropped, redefined rather than restored:
            ;; research-assistant now names builtin/agent-rag-graph-faithful,
            ;; retrieve-only names the rewired builtin/retrieve-only, and
            ;; ai-overview replaces the retired simple-qa. All three name graphs
            ;; registered from src/, so they seed on every classpath — unlike
            ;; builtin/docs-agent above, which is src-dev-only.
            ;;
            ;; Asserted as a set rather than a count so a future unexpected
            ;; agent names itself instead of just moving a number — which is
            ;; exactly how this one announced itself, twice now.
            (is (= #{"agent/altinn-docs"
                     "builtin/agent-rag-agent"
                     "builtin/ai-overview-agent"
                     "builtin/docs-agent"
                     "builtin/fact-checker-agent"
                     "builtin/research-assistant-agent"
                     "builtin/retrieve-only-agent"
                     "digdir/altinn-docs-tuned"}
                   agent-ids)))))
      (finally
        (doseq [file-path [export-file]]
          (io/delete-file file-path true))
        (delete-test-db fresh-main-conn)
        (delete-test-db fresh-config-conn)
        (delete-test-db main-conn)
        (delete-test-db config-conn)))))

(deftest test-import-system-rejects-precanonical-export
  (let [fresh-config-conn (create-config-test-db)
        fresh-main-conn (create-main-test-db)
        legacy-export {:version "1.0"
                       :main {:api-keys [{:api-key/id "legacy-key"
                                          :api-key/key "rag_legacy"
                                          :api-key/name "Legacy Key"
                                          :api-key/pipelines [sample-pipeline-id]
                                          :api-key/scopes [:query]}]
                              :conversations [{:conversation/id "legacy-convo"
                                               :conversation/topic "Legacy"
                                               :conversation/created 1
                                               :conversation/pipeline-id sample-pipeline-id
                                               :conversation/messages []}]}}]
    (try
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Unsupported system export version"
           (migration/import-system fresh-config-conn fresh-main-conn legacy-export {:on-conflict :skip})))
      (finally
        (delete-test-db fresh-main-conn)
        (delete-test-db fresh-config-conn)))))

;; ---------------------------------------------------------------------------
;; #275 - the post-import verification must actually find the tenants it checks
;; ---------------------------------------------------------------------------

(deftest imported-platform-tenants-finds-the-tenants-it-must-verify
  ;; This function silently returned NOTHING on my first attempt: I guessed
  ;; [:config :nodes] when nodes live at [:data :nodes], and matched the root
  ;; against the keyword :platform when JSON delivers the string "platform".
  ;; Either mistake disables the verification completely while the import still
  ;; reports success - the exact shape of the defect being guarded against, so
  ;; the guard needs a guard.
  (let [extract #'import-impl/imported-platform-tenants]
    (testing "the real envelope shape: [:data :nodes] with string roots"
      (is (= ["digdir" "public-sector-knowledge"]
             (extract {:data {:nodes [{:config.node/root "platform"
                                       :config.node/tenant "digdir"}
                                      {:config.node/root "platform"
                                       :config.node/tenant "digdir"}
                                      {:config.node/root "runtime"
                                       :config.node/tenant "ignored"}
                                      {:config.node/root "platform"
                                       :config.node/tenant "public-sector-knowledge"}]}}))))

    (testing "keyword roots are accepted too, so an in-memory envelope works"
      (is (= ["digdir"]
             (extract {:data {:nodes [{:config.node/root :platform
                                       :config.node/tenant "digdir"}]}}))))

    (testing "the global pseudo-tenant is not a tenant to verify"
      (is (= [] (extract {:data {:nodes [{:config.node/root "platform"
                                          :config.node/tenant "__global__"}]}}))))

    (testing "an envelope with no platform nodes yields nothing to check"
      (is (= [] (extract {:data {:nodes []}})))
      (is (= [] (extract {}))))))

;; ---------------------------------------------------------------------------
;; The bridge and the verification are ordered, and the order is load-bearing
;; ---------------------------------------------------------------------------

(defn- import-with-recorded-calls
  "Run a real import with the bridge and the verification stubbed, returning
   [result call-log]. `bridge-result` is what the stubbed bridge reports."
  [bridge-result]
  (let [config-conn (create-config-test-db)
        main-conn (create-main-test-db)
        fresh-config-conn (create-config-test-db)
        fresh-main-conn (create-main-test-db)
        export-file (.getAbsolutePath (java.io.File/createTempFile "bridge-order-" ".json"))
        calls (atom [])]
    (try
      (seed-system-fixture! config-conn main-conn)
      ;; The shared fixture seeds :dataset and :runtime nodes only, so
      ;; `imported-platform-tenants` finds nothing and NEITHER of the two
      ;; functions under test would run - the first version of this test passed
      ;; its ordering assertion against an empty call log. Added here rather
      ;; than to the fixture: other tests assert exact node counts on it.
      (config-db/create-config-node! config-conn
                                     {:root :platform
                                      :tenant "altinn-docs"
                                      :node-id "platform/altinn-docs/default"
                                      :label "Default Platform"
                                      :tenant-config-key "default"})
      ;; Goes through the FILE, because that is what the real caller does and
      ;; the shape differs: `export-system` returns node maps with STRING keys,
      ;; and `imported-platform-tenants` looks them up with keywords, so an
      ;; in-memory envelope finds no tenants and silently runs neither function.
      ;; `read-json-file` keywordizes, which is why `bb migration-import` works.
      ;; Hand-keywordizing here instead would be a claim about the producer
      ;; rather than a use of it.
      (migration/export-to-file config-conn main-conn export-file {:include-audit? false})
      (let [result (with-redefs [env-bridge/seed-config-from-env!
                                 (fn [_conn tenant & _]
                                   (swap! calls conj [:bridge tenant])
                                   (assoc bridge-result :tenant tenant))
                                 config-verify/report-unresolved-service-config!
                                 (fn [_db tenant]
                                   (swap! calls conj [:verify tenant])
                                   {:unreachable [] :undecryptable [] :unsupplied []})]
                     (migration/import-from-file fresh-config-conn fresh-main-conn
                                                 export-file {:on-conflict :skip}))]
        [result @calls])
      (finally
        (doseq [c [config-conn main-conn fresh-config-conn fresh-main-conn]]
          (d/release c))))))

(deftest the-bridge-runs-before-the-verification-that-reports-on-it
  ;; Run the bridge afterwards and the report describes a system that no longer
  ;; exists - listing values the operator has in fact already supplied. Neither
  ;; function's own tests can see this; it only exists in the wiring.
  (let [[_ calls] (import-with-recorded-calls
                    {:paths-written [] :actions {} :skipped []})
        kinds (mapv first calls)]
    (is (seq calls)
        "the fixture must actually import a platform tenant, or this test is vacuous")
    (is (some #{:bridge} kinds) "the bridge ran")
    (is (some #{:verify} kinds) "the verification ran")
    (is (< (.lastIndexOf kinds :bridge) (.indexOf kinds :verify))
        "every bridge call precedes every verification call")))

(deftest a-write-the-bridge-could-not-perform-is-reported
  ;; The variable is set, the operator believes it took, and the value is not
  ;; there. Silence here is worse than not bridging at all.
  (let [[result _] (import-with-recorded-calls
                     {:paths-written []
                      :actions {}
                      :skipped [{:path "services.typesense.api-key-admin"
                                 :env-var "TYPESENSE_API_KEY_ADMIN"
                                 :reason "Config definition not found"}]})]
    (is (= ["services.typesense.api-key-admin"]
           (distinct (map :path (:env-supply-skipped result)))))
    (is (= ["TYPESENSE_API_KEY_ADMIN"]
           (distinct (map :env-var (:env-supply-skipped result)))))))

(deftest a-clean-bridge-adds-no-noise-to-the-import-result
  ;; A key that always appears is a key nobody reads.
  (let [[result _] (import-with-recorded-calls
                     {:paths-written [] :actions {} :skipped []})]
    (is (not (contains? result :env-supply-skipped)))
    (is (not (contains? result :env-supplied-service-config)))))

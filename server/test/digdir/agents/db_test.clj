(ns digdir.agents.db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [datahike.api :as d]
            [digdir.agents.db :as agents-db]
            [digdir.config.db :as config-db]
            [digdir.skills.api :as skills-api]))

(defn with-initialized-skills
  [f]
  (skills-api/reset-skills!)
  (skills-api/initialize!)
  (f)
  (skills-api/reset-skills!))

(use-fixtures :each with-initialized-skills)

(defn create-test-db
  []
  (let [cfg {:store {:backend :mem
                     :id (str "agents-db-test-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (d/connect cfg)))

(defn delete-test-db
  [conn]
  (let [cfg (:config @(:wrapped-atom conn))]
    (d/release conn)
    (d/delete-database cfg)))

(deftest test-upsert-and-get-agent
  (testing "Persists and reloads normalized agent definitions"
    (let [conn (create-test-db)]
      (try
        (config-db/ensure-schema! conn)
        (let [saved (agents-db/upsert-agent!
                     conn
                     {:id "agent/altinn-docs"
                      :name "Altinn Docs Agent"
                      :description "Answers over Altinn docs"
                      :instructions "Use grounded retrieval."
                      :default-skill-graph "builtin/agent-rag-graph-bundled"
                      :allowed-skill-graphs ["builtin/agent-rag-graph-bundled" "builtin/agent-rag-graph-faithful"]
                      :allowed-dataset-scopes [{:tenant "altinn-docs"
                                                :dataset-config-key "dev"}]
                      :guardrails {:citations-required true}
                      :enabled? true})
              loaded (agents-db/get-agent @conn "agent/altinn-docs")]
          (is (= saved loaded))
          (is (= "Altinn Docs Agent" (:name loaded)))
          (is (= #{"builtin/agent-rag-graph-bundled" "builtin/agent-rag-graph-faithful"}
                 (set (:allowed-skill-graphs loaded))))
          (is (= [{:tenant "altinn-docs"
                   :dataset-config-key "dev"}]
                 (:allowed-dataset-scopes loaded))))
        (finally
          (delete-test-db conn))))))

(deftest test-skill-params-round-trip
  (testing "An agent persisted without :skill-params reads back as {}"
    (let [conn (create-test-db)]
      (try
        (config-db/ensure-schema! conn)
        (agents-db/upsert-agent!
         conn
         {:id "agent/no-params"
          :name "No Params"
          :description "Has no overrides"
          :instructions "Use defaults."
          :default-skill-graph "builtin/agent-rag-graph-bundled"
          :allowed-skill-graphs ["builtin/agent-rag-graph-bundled"]
          :guardrails {}
          :enabled? true})
        (let [loaded (agents-db/get-agent @conn "agent/no-params")]
          (is (= {} (:skill-params loaded))
              "Missing :skill-params decodes to empty map, not nil."))
        (finally (delete-test-db conn)))))

  (testing "An agent's :skill-params round-trips through EDN encoding"
    (let [conn (create-test-db)]
      (try
        (config-db/ensure-schema! conn)
        (let [original-params {:builtin/retrieval {:strategy-weights {:content 0.0
                                                                      :phrase 1.0
                                                                      :metadata 0.0}
                                                   :strategy-contribution-caps {:phrase 5
                                                                                :content 0
                                                                                :metadata 0}
                                                   :retrieve-top-k 100}
                               :builtin/rerank {:top-k 20}
                               :builtin/agent {:search-strategy-quota 5}}
              saved (agents-db/upsert-agent!
                     conn
                     {:id "agent/tuned"
                      :name "Tuned"
                      :description "Production-winner config"
                      :instructions "Use production settings."
                      :default-skill-graph "builtin/agent-rag-graph-faithful"
                      :allowed-skill-graphs ["builtin/agent-rag-graph-faithful"]
                      :guardrails {}
                      :skill-params original-params
                      :enabled? true})
              loaded (agents-db/get-agent @conn "agent/tuned")]
          (is (= original-params (:skill-params saved))
              "upsert-agent! return value carries the normalized skill-params.")
          (is (= original-params (:skill-params loaded))
              "get-agent decodes the EDN string back to the original map."))
        (finally (delete-test-db conn)))))

  (testing "Malformed EDN in :agent/skill-params falls back to {}"
    ;; Defensive read: a single corrupted agent shouldn't crash agent lookup.
    ;; We don't have a public path that writes invalid EDN, so we drop a
    ;; junk string in directly via d/transact and confirm get-agent still
    ;; returns a usable record.
    (let [conn (create-test-db)]
      (try
        (config-db/ensure-schema! conn)
        (agents-db/upsert-agent!
         conn
         {:id "agent/will-corrupt"
          :name "Will Corrupt"
          :description "About to have junk EDN injected"
          :instructions "Test fixture."
          :default-skill-graph "builtin/agent-rag-graph-bundled"
          :allowed-skill-graphs ["builtin/agent-rag-graph-bundled"]
          :guardrails {}
          :skill-params {:builtin/retrieval {:strategy-weights {:phrase 1.0}}}
          :enabled? true})
        (d/transact conn {:tx-data [{:agent/id "agent/will-corrupt"
                                     :agent/skill-params "{not-an-edn-map }"}]})
        (let [loaded (agents-db/get-agent @conn "agent/will-corrupt")]
          (is (= {} (:skill-params loaded))
              "Malformed EDN decodes to {} instead of throwing."))
        (finally (delete-test-db conn))))))

(deftest test-list-delete-and-seed-builtin-agents
  (testing "Lists, deletes, and seeds builtin agents"
    (let [conn (create-test-db)]
      (try
        (config-db/init-config-db! conn :sync-admins? false)
        (let [seeded-ids (set (map :id (agents-db/list-agents @conn)))]
          (is (contains? seeded-ids "builtin/agent-rag-agent"))
          (is (contains? seeded-ids "builtin/fact-checker-agent"))
          ;; :builtin/docs-agent names the :docs/* graphs from the demo and
          ;; src-dev namespaces. This assertion used to read `not contains?`,
          ;; with a comment explaining that seeding went through
          ;; digdir.skills.api/initialize! and so never saw those graphs —
          ;; i.e. it pinned the #85 defect rather than an invariant. Seeding
          ;; now initialises through digdir.skills.init, which registers
          ;; them, and the :test alias puts src-dev on the classpath, so the
          ;; agent seeds here exactly as it does at server boot. A production
          ;; build without src-dev still filters it out — that is what the
          ;; filtering in seed-builtin-agents! is for.
          (is (contains? seeded-ids "builtin/docs-agent")))

        (agents-db/upsert-agent!
         conn
         {:id "agent/custom"
          :name "Custom Agent"
          :description "Custom description"
          :instructions "Do custom work."
          :default-skill-graph "builtin/agent-rag-graph-bundled"
          :allowed-skill-graphs ["builtin/agent-rag-graph-bundled"]
          :guardrails {}
          :enabled? false})

        (let [agent-ids (mapv :id (agents-db/list-agents @conn))]
          (is (some #{"agent/custom"} agent-ids)))

        (is (true? (agents-db/delete-agent! conn "agent/custom")))
        (is (nil? (agents-db/get-agent @conn "agent/custom")))
        (finally
          (delete-test-db conn))))))

;; =============================================================================
;; Availability filtering: which degradations are honest (#92)
;;
;; Three behaviours, and they are not the same kind of thing:
;;
;;   drop unavailable allowed graphs  -> a truthful SUBSET
;;   skip an agent left with none     -> a truthful ABSENCE (load-bearing: a
;;                                       production build has no src-dev graphs)
;;   substitute the default           -> a record asserting something the
;;                                       definition never said. Removed.
;; =============================================================================

(def ^:private filter-available
  #'digdir.agents.db/filter-to-available-skill-graphs)

(deftest available-filtering-keeps-a-truthful-subset
  (testing "unavailable allowed graphs are dropped, the rest survive"
    (let [agent {:id "builtin/example"
                 :default-skill-graph "builtin/a"
                 :allowed-skill-graphs ["builtin/a" "docs/absent"]}
          filtered (filter-available agent #{"builtin/a"})]
      (is (= ["builtin/a"] (:allowed-skill-graphs filtered)))
      (is (= "builtin/a" (:default-skill-graph filtered))
          "an available default is left alone"))))

(deftest unavailable-default-degrades-to-no-default-not-someone-elses-graph
  (testing "the agent keeps its surviving graphs and loses only the false claim"
    (let [agent {:id "builtin/example"
                 :default-skill-graph "docs/absent"
                 :allowed-skill-graphs ["docs/absent" "builtin/b" "builtin/c"]}
          filtered (filter-available agent #{"builtin/b" "builtin/c"})]
      (is (nil? (:default-skill-graph filtered))
          "an unavailable default must not be replaced by another graph")
      (is (= ["builtin/b" "builtin/c"] (:allowed-skill-graphs filtered))
          "capability is not discarded to make a strictness point - the
           surviving graphs stay selectable explicitly")
      (is (not= "builtin/b" (:default-skill-graph filtered))
          "specifically NOT (first filtered-allowed), which is what it used to be"))))

(deftest empty-case-is-unchanged-and-still-load-bearing
  (testing "an agent with no available graphs filters to empty, and the caller skips it"
    ;; This is the case a production build actually hits, and the reason
    ;; filtering exists at all. It must keep working: seeding the other agents
    ;; depends on this one degrading rather than throwing.
    (let [agent {:id "builtin/docs-agent"
                 :default-skill-graph "docs/self-improve-graph"
                 :allowed-skill-graphs ["docs/self-improve-graph" "docs/outline-graph"]}
          filtered (filter-available agent #{"builtin/agent-rag-graph-bundled"})]
      (is (empty? (:allowed-skill-graphs filtered))
          "nothing survives, which is what seed-builtin-agents! keys its skip on")
      (is (nil? (:default-skill-graph filtered)))
      (is (some? filtered) "filtering degrades; it does not throw"))))

(deftest test-drift-report
  (testing "Covers agents that exist only in the database"
    (let [conn (create-test-db)]
      (try
        (config-db/ensure-schema! conn)
        (agents-db/upsert-agent!
         conn
         {:id "e2e/hand-rolled"
          :name "Hand Rolled"
          :description "Stored without a code definition"
          :instructions "Answer."
          :default-skill-graph "builtin/agent-rag-graph-bundled"
          :allowed-skill-graphs ["builtin/agent-rag-graph-bundled"]})
        (let [by-id (into {} (map (juxt :id identity)) (agents-db/drift-report @conn))]
          (is (= :custom (:status (get by-id "e2e/hand-rolled"))))
          (is (some? (:stored (get by-id "e2e/hand-rolled")))))
        (finally (delete-test-db conn)))))

  (testing "Covers declared agents that were never seeded"
    (let [conn (create-test-db)]
      (try
        (config-db/ensure-schema! conn)
        (let [by-id (into {} (map (juxt :id identity)) (agents-db/drift-report @conn))]
          (is (seq by-id) "Declared agents appear even against an empty database")
          (is (every? #(= :unseeded (:status %)) (vals by-id))))
        (finally (delete-test-db conn)))))

  (testing "A freshly seeded row matches the code definition"
    (let [conn (create-test-db)]
      (try
        (config-db/ensure-schema! conn)
        (agents-db/seed-builtin-agents! conn)
        (let [seeded (->> (agents-db/drift-report @conn)
                          (filter #(= "builtin/agent-rag-agent" (:id %)))
                          first)]
          (is (= :matches-code (:status seeded))))
        (finally (delete-test-db conn)))))

  (testing "A row holding fewer graphs than the code declares is stale, and names the recoverable one"
    (let [conn (create-test-db)]
      (try
        (config-db/ensure-schema! conn)
        (agents-db/upsert-agent!
         conn
         {:id "builtin/agent-rag-agent"
          :name "Agentic RAG Agent"
          :description "General-purpose agentic retrieval assistant."
          :instructions "Use the available retrieval and reasoning tools."
          :default-skill-graph "builtin/agent-rag-graph-bundled"
          :allowed-skill-graphs ["builtin/agent-rag-graph-bundled"]})
        (let [narrowed (->> (agents-db/drift-report @conn)
                            (filter #(= "builtin/agent-rag-agent" (:id %)))
                            first)]
          (is (= :stale (:status narrowed)))
          (is (contains? (set (get-in narrowed [:allowed-skill-graphs :recoverable]))
                         "builtin/agent-rag-graph-faithful")))
        (finally (delete-test-db conn))))))

(deftest test-upsert-removes-skill-graphs
  (testing "An upsert that drops a graph actually drops it"
    (let [conn (create-test-db)]
      (try
        (config-db/ensure-schema! conn)
        (let [base {:id "t/narrowing"
                    :name "Narrowing"
                    :description "d"
                    :instructions "i"
                    :default-skill-graph "builtin/agent-rag-graph-bundled"}]
          (agents-db/upsert-agent!
           conn (assoc base :allowed-skill-graphs ["builtin/agent-rag-graph-bundled"
                                                   "builtin/agent-rag-graph-faithful"]))
          (is (= #{"builtin/agent-rag-graph-bundled" "builtin/agent-rag-graph-faithful"}
                 (set (:allowed-skill-graphs (agents-db/get-agent @conn "t/narrowing")))))
          (agents-db/upsert-agent!
           conn (assoc base :allowed-skill-graphs ["builtin/agent-rag-graph-bundled"]))
          (is (= ["builtin/agent-rag-graph-bundled"]
                 (:allowed-skill-graphs (agents-db/get-agent @conn "t/narrowing")))))
        (finally (delete-test-db conn))))))

(deftest test-seed-agent
  (testing "Reseeding one agent repairs it and leaves the others alone"
    (let [conn (create-test-db)]
      (try
        (config-db/ensure-schema! conn)
        (agents-db/seed-builtin-agents! conn)
        (let [before (agents-db/get-agent @conn "builtin/fact-checker-agent")]
          (agents-db/upsert-agent!
           conn (assoc (agents-db/get-agent @conn "builtin/agent-rag-agent")
                       :allowed-skill-graphs ["builtin/agent-rag-graph-bundled"]
                       :default-skill-graph "builtin/agent-rag-graph-bundled"
                       :name "Hand edited"))
          (is (= :stale (->> (agents-db/drift-report @conn)
                             (filter #(= "builtin/agent-rag-agent" (:id %)))
                             first :status)))
          (agents-db/seed-agent! conn "builtin/agent-rag-agent")
          (is (= :matches-code (->> (agents-db/drift-report @conn)
                                    (filter #(= "builtin/agent-rag-agent" (:id %)))
                                    first :status)))
          (is (= before (agents-db/get-agent @conn "builtin/fact-checker-agent"))))
        (finally (delete-test-db conn)))))

  (testing "An id with no code definition seeds nothing"
    (let [conn (create-test-db)]
      (try
        (config-db/ensure-schema! conn)
        (is (nil? (agents-db/seed-agent! conn "e2e/not-in-code")))
        (finally (delete-test-db conn))))))

(deftest test-reconcile-skill-graphs
  (testing "Restores a narrowed graph list without touching other fields"
    (let [conn (create-test-db)]
      (try
        (config-db/ensure-schema! conn)
        (agents-db/seed-builtin-agents! conn)
        (agents-db/upsert-agent!
         conn (assoc (agents-db/get-agent @conn "builtin/agent-rag-agent")
                     :allowed-skill-graphs ["builtin/agent-rag-graph-bundled"]
                     :default-skill-graph "builtin/agent-rag-graph-bundled"
                     :instructions "Operator tuned."
                     :name "Operator named"))
        (agents-db/reconcile-skill-graphs! conn)
        (let [after (agents-db/get-agent @conn "builtin/agent-rag-agent")]
          (is (= #{"builtin/agent-rag-graph-bundled" "builtin/agent-rag-graph-faithful"}
                 (set (:allowed-skill-graphs after))))
          (is (= "Operator tuned." (:instructions after)))
          (is (= "Operator named" (:name after))))
        (finally (delete-test-db conn)))))

  (testing "Creates a builtin that has never been stored"
    (let [conn (create-test-db)]
      (try
        (config-db/ensure-schema! conn)
        (is (nil? (agents-db/get-agent @conn "builtin/agent-rag-agent")))
        (agents-db/reconcile-skill-graphs! conn)
        (is (some? (agents-db/get-agent @conn "builtin/agent-rag-agent")))
        (finally (delete-test-db conn)))))

  (testing "Changes nothing when the graphs already agree"
    (let [conn (create-test-db)]
      (try
        (config-db/ensure-schema! conn)
        (agents-db/seed-builtin-agents! conn)
        (is (empty? (agents-db/reconcile-skill-graphs! conn)))
        (finally (delete-test-db conn))))))

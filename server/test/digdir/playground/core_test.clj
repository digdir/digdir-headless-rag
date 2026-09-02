(ns digdir.playground.core-test
  (:require [digdir.test-utils :as tu]
            [clojure.set]
            [clojure.test :refer [deftest is testing]]
            [digdir.agents.db :as agents-db]
            [digdir.config.accessor :as cfg]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.data.db :as db]
            [digdir.llm.openai :as llm]
            [digdir.playground.core :as core]
            [digdir.rag.typesense :as ts-utils]
            [digdir.skills.api :as skills-api]
            [typesense.client :as ts-client]))

(deftest fetch-chunk-by-id-returns-document-hit
  (testing "Chunk fetch returns the first matching document"
    (with-redefs [ts-utils/make-ts-settings (fn [_opts] {:uri "http://typesense"})
                  ts-client/multi-search (fn [_settings _search-args _opts]
                                           {:results [{:hits [{:document {:chunk_id "chunk-1"
                                                                          :content_markdown "Hello"}}]}]})]
      (is (= {:chunk_id "chunk-1"
              :content_markdown "Hello"}
             (core/fetch-chunk-by-id "chunks" "docs" "chunk-1"))))))

(deftest fetch-chunk-by-id-swallows-backend-connectivity-errors
  (testing "Chunk fetch returns nil when Typesense is unreachable"
    (with-redefs [ts-utils/make-ts-settings (fn [_opts] {:uri "http://typesense"})
                  ts-client/multi-search (fn [_settings _search-args _opts]
                                           (throw (java.net.ConnectException. "Connection refused")))]
      (is (nil? (core/fetch-chunk-by-id "chunks" "docs" "chunk-1"))))))

(deftest fetch-dataset-stats-returns-lightweight-collection-counts
  (let [seen (atom [])]
    (with-redefs [ts-utils/make-ts-settings (fn [opts]
                                              (is (= {:tenant "digdir"
                                                      :dataset-config-key "docs"}
                                                     opts))
                                              {:uri "http://typesense"})
                  ts-client/retrieve-collection
                  (fn [_settings collection]
                    (swap! seen conj collection)
                    {:num_documents ({"docs" 12 "chunks" 48 "phrases" 96}
                                     collection)})]
      (is (= {:documents 12 :chunks 48 :phrases 96}
             (core/fetch-dataset-stats
              {:docs-collection "docs"
               :chunks-collection "chunks"
               :phrases-collection "phrases"}
              {:tenant "digdir" :dataset-config-key "docs"})))
      (is (= ["docs" "chunks" "phrases"] @seen)))))

(deftest fetch-dataset-stats-degrades-when-a-collection-is-unavailable
  (with-redefs [ts-utils/make-ts-settings (constantly {:uri "http://typesense"})
                ts-client/retrieve-collection
                (fn [_settings collection]
                  (if (= collection "missing")
                    (throw (java.net.ConnectException. "Connection refused"))
                    {:num_documents 7}))]
    (is (= {:documents 7 :chunks nil :phrases nil}
           (core/fetch-dataset-stats
            {:docs-collection "docs"
             :chunks-collection "missing"}
            nil)))))

(deftest execute-skill-graph-preserves-backend-issues-in-diagnostics
  (testing "Agentic and startup Typesense issues are carried into playground diagnostics"
    (let [execution-id "exec-backend-issues"
          agent-backend-issues [{:source :typesense
                                 :tool "inspect_filters"
                                 :issue-type :inspect-filters-failed
                                 :message "Collection not found"
                                 :details {:collection "digdir_rag_assistant_docs"}}]
          startup-diag {:connected true
                        :expected-collections ["digdir_rag_assistant_docs"
                                               "digdir_rag_assistant_chunks"
                                               "digdir_rag_assistant_phrases"]
                        :available-collections ["digdir_rag_assistant_chunks"
                                                "digdir_rag_assistant_phrases"]
                        :missing-collections ["digdir_rag_assistant_docs"]}
          combined-issues [{:source :typesense
                            :tool "startup-check"
                            :issue-type :missing-collections
                            :message "Expected Typesense collections are missing"
                            :details {:missing-collections ["digdir_rag_assistant_docs"]
                                      :expected-collections ["digdir_rag_assistant_docs"
                                                             "digdir_rag_assistant_chunks"
                                                             "digdir_rag_assistant_phrases"]
                                      :available-collections ["digdir_rag_assistant_chunks"
                                                              "digdir_rag_assistant_phrases"]}}
                           {:source :typesense
                            :tool "inspect_filters"
                            :issue-type :inspect-filters-failed
                            :message "Collection not found"
                            :details {:collection "digdir_rag_assistant_docs"}}]]
      (swap! core/!playground-executions assoc execution-id {:events [] :results {}})
      (try
        (with-redefs [skills-api/initialize! (fn [] nil)
                      core/get-typesense-diagnostics (fn [_expected-collections _opts] startup-diag)
                      skills-api/run-skill-graph
                      (fn [_skill-graph-id _inputs _opts]
                        {:outputs {:response "Fallback response"
                                   :trace [{:iteration 0 :reasoning "No evidence found"}]
                                   :search-history []
                                   :search-errors []
                                   :backend-issues agent-backend-issues
                                   :search-attributions []
                                   :query-intent {:answer-type :lookup}
                                   :budget-state {}
                                   :read-history []
                                   :sufficiency-decisions []}
                         :step-results {:agent {:outputs {:trace [{:iteration 0 :reasoning "No evidence found"}]}
                                                :metadata {:stage-timings [{:stage :agent-llm
                                                                            :iteration 0
                                                                            :duration-ms 12
                                                                            :status :ok}
                                                                           {:stage :search
                                                                            :iteration 0
                                                                            :tool "search_documents"
                                                                            :sub-skill :builtin/retrieval
                                                                            :duration-ms 34
                                                                            :status :ok}]}}}
                         :execution-metadata {:duration-ms 10
                                              :total-duration-ms 10
                                              :steps-executed 1
                                              :step-timings {:summarize {:duration-ms 10
                                                                         :skill-id :test/summarization
                                                                         :stage :summarization}}
                                              :stage-timings [{:step-id :summarize
                                                               :duration-ms 10
                                                               :skill-id :test/summarization
                                                               :stage :summarization}]}})]
          (let [result (core/execute-skill-graph
                        execution-id
                        "hva kan jeg lage med Altinn?"
                        [{:message/role :user :message/text "hva kan jeg lage med Altinn?"}]
                        {:docs-collection "digdir_rag_assistant_docs"
                         :chunks-collection "digdir_rag_assistant_chunks"
                         :phrases-collection "digdir_rag_assistant_phrases"}
                        {:skill-graph "builtin/agent-rag"}
                        nil)]
            (is (= combined-issues (get-in result [:diagnostics :backend-issues])))
            (is (= startup-diag (get-in result [:diagnostics :typesense-startup-diagnostics])))
            (is (= [{:step-id :summarize
                     :duration-ms 10
                     :skill-id :test/summarization
                     :stage :summarization}]
                   (get-in result [:diagnostics :execution-stage-timings])))
            (is (= [{:stage :agent-llm
                     :iteration 0
                     :duration-ms 12
                     :status :ok}
                    {:stage :search
                     :iteration 0
                     :tool "search_documents"
                     :sub-skill :builtin/retrieval
                     :duration-ms 34
                     :status :ok}]
                   (get-in result [:diagnostics :agent-stage-timings])))
            (is (= combined-issues (get-in @core/!playground-executions [execution-id :results :backend-issues])))))
        (finally
          (swap! core/!playground-executions dissoc execution-id))))))

(deftest execute-skill-graph-retains-live-agent-progress-metadata
  (testing "Live execution state keeps rich agent trace and stage timings as progress arrives"
    (let [execution-id "exec-live-agent-progress"
          stage-timings [{:stage :agent-llm
                          :iteration 0
                          :duration-ms 12
                          :status :ok}
                         {:stage :search
                          :iteration 0
                          :tool "search_documents"
                          :sub-skill :builtin/retrieval
                          :duration-ms 34
                          :status :ok}]
          tool-calls [{:tool "search_documents"
                       :args {:queries ["q"]}
                       :args-summary "{:queries [\"q\"]}"
                       :effective-parameters {:sub-skill :builtin/retrieval}
                       :stage :search
                       :sub-skill :builtin/retrieval
                       :duration-ms 34
                       :ok? true
                       :result-summary "Found 12 chunks"}]]
      (swap! core/!playground-executions assoc execution-id {:events [] :results {}})
      (try
        (with-redefs [skills-api/initialize! (fn [] nil)
                      core/get-typesense-diagnostics (fn [_expected-collections _opts]
                                                      {:connected true
                                                       :expected-collections []})
                      skills-api/run-skill-graph
                      (fn [_skill-graph-id _inputs opts]
                        ((:progress-fn opts) {:event :agent/turn-completed
                                              :iteration 0
                                              :reasoning "Searching"
                                              :tool-calls tool-calls
                                              :stage-timings stage-timings})
                        {:outputs {:response "ok"
                                   :trace []}
                         :step-results {}
                         :execution-metadata {:total-duration-ms 5
                                              :steps-executed 1
                                              :stage-timings []}})]
          (core/execute-skill-graph
           execution-id
           "hva kan jeg lage med Altinn?"
           [{:message/role :user :message/text "hva kan jeg lage med Altinn?"}]
           {:docs-collection "digdir_rag_assistant_docs"
            :chunks-collection "digdir_rag_assistant_chunks"
            :phrases-collection "digdir_rag_assistant_phrases"}
           {:skill-graph "builtin/agent-rag"}
           nil)
          (is (= [{:iteration 0
                   :reasoning "Searching"
                   :tool-calls tool-calls}]
                 (get-in @core/!playground-executions [execution-id :live-agent-trace])))
          (is (= stage-timings
                 (get-in @core/!playground-executions [execution-id :live-agent-stage-timings]))))
        (finally
          (swap! core/!playground-executions dissoc execution-id))))))

(deftest execute-playground-chat-pipeline-always-uses-skills
  (testing "Playground chat prefers V2 runtime config and records the selected runtime node"
    (let [skills-called (promise)
          assistant-saved (promise)
          created-conversation-opts (atom nil)
          captured-rag-params (atom nil)]
      (with-redefs [db/get-conn (fn [] (atom nil))
                    config-db/get-conn (fn [] (atom nil))
                    config-db/get-dataset-by-ref (fn [& _]
                                                   {:dataset-id "dataset-1"
                                              :docs-collection "digdir_rag_assistant_docs"
                                              :chunks-collection "digdir_rag_assistant_chunks"
                                              :phrases-collection "digdir_rag_assistant_phrases"})
                    cfg/get-runtime-skill-config-v2-with-trace (fn [_]
                                                                 {:config {:rerank-top-k 55
                                                                           :query-planner-prompt "v2-relax"
                                                                           :synthesis-generation-prompt "v2-generate"}
                                                                  :traces {"skills.rerank.top-k" {:decoded-value 55}
                                                                           "skills.query-planner.prompt" {:decoded-value "v2-relax"}
                                                                           "skills.synthesis.generation.prompt" {:decoded-value "v2-generate"}}
                                                                  :node {:config.node/id "runtime/frontpage"}})
                    config-core/get-master-key (fn [] "master-key")
                    db/create-playground-conversation (fn [_conn agent-id opts]
                                                       (is (= "agent/custom-agent" agent-id))
                                                       (reset! created-conversation-opts opts)
                                                       {:conversation-id "convo-1"})
                    db/transact-playground-user-msg (fn [& _] {:message/id "user-msg-1"})
                    db/get-message-lineage (fn [& _] nil)
                    db/queue-playground-assistant-msg! (tu/recording-fn
                                                        (deliver assistant-saved true)
                                                        {:message/id "assistant-msg-1"
                                                         :queued? true})
                    llm/use-azure-openai (fn [_] false)
                    core/execute-skill-graph (fn [_execution-id _query _all-messages rag-params _config _ts-opts]
                                                   (reset! captured-rag-params rag-params)
                                                   (deliver skills-called true)
                                                   {:response "skills response"
                                                    :diagnostics {}})]
        (let [result (core/execute-playground-chat-pipeline
                      {:query "hva kan jeg bruke Altinn til?"
                       :agent-id "agent/custom-agent"
                       :user-id "internal-user-123"
                       :tenant "ka"
                       :dataset-config-key "dev"
                       :config {:model "gpt-4o"}})]
          (is (string? (:execution-id result)))
          (is (true? (deref skills-called 1000 false)))
          (is (true? (deref assistant-saved 1000 false)))
          (is (= {:user-id "internal-user-123"
                  :tenant "ka"
                  :dataset-config-key "dev"
                  :skill-graph-id "builtin/agent-rag-graph-bundled"}
                 @created-conversation-opts))
          (is (= 55 (:rerankTopkChunks @captured-rag-params)))
          (is (= "v2-relax" (:promptRagQueryRelax @captured-rag-params)))
          (is (= "v2-generate" (:promptRagGenerate @captured-rag-params)))
          (is (= {:execution-id (:execution-id result)
                  :tenant "ka"
                  :dataset-config-key "dev"
                  :runtime-config-key "default"
                  :agent-id "agent/custom-agent"
                  :runtime-config-source :v2
                  :runtime-node-id "runtime/frontpage"
                  :runtime-config-traces {"skills.rerank.top-k" {:decoded-value 55}
                                          "skills.query-planner.prompt" {:decoded-value "v2-relax"}
                                          "skills.synthesis.generation.prompt" {:decoded-value "v2-generate"}}
                  :runtime-config-error nil
                  :collections {:docs-collection "digdir_rag_assistant_docs"
                                :chunks-collection "digdir_rag_assistant_chunks"
                                :phrases-collection "digdir_rag_assistant_phrases"}}
                 (get-in @core/!playground-executions
                         [(:execution-id result) :results :resolved-runtime-context])))
          (is (= "internal-user-123"
                 (get-in @core/!playground-executions
                         [(:execution-id result) :user-id])))))))
  (testing "Playground chat fails closed when V2 runtime resolution is unavailable"
    (with-redefs [db/get-conn (fn [] (atom nil))
                  config-db/get-conn (fn [] (atom nil))
                  config-db/get-dataset-by-ref (fn [& _]
                                                 {:dataset-id "dataset-1"
                                            :docs-collection "digdir_rag_assistant_docs"
                                            :chunks-collection "digdir_rag_assistant_chunks"
                                            :phrases-collection "digdir_rag_assistant_phrases"})
                  cfg/get-runtime-skill-config-v2-with-trace (fn [_]
                                                               (throw (ex-info "No runtime binding for tenant"
                                                                               {:tenant "ka"})))
                  config-core/get-master-key (fn [] "master-key")
                  db/create-playground-conversation (tu/recording-fn {:conversation-id "convo-1"})
                  db/transact-playground-user-msg (fn [& _] {:message/id "user-msg-1"})
                  db/get-message-lineage (fn [& _] nil)
                  llm/use-azure-openai (fn [_] false)
                  core/execute-skill-graph (fn [_execution-id _query _all-messages rag-params _config _ts-opts]
                                                 (throw (ex-info "skills pipeline should not execute"
                                                                 {:rag-params rag-params})))]
        (let [error (try
                      (core/execute-playground-chat-pipeline
                       {:query "hva kan jeg bruke Altinn til?"
                        :agent-id "agent/custom-agent"
                        :tenant "ka"
                        :dataset-config-key "dev"
                        :config {:model "gpt-4o"}})
                      nil
                      (catch clojure.lang.ExceptionInfo e
                        e))]
          (is error)
          (is (= "Runtime config resolution failed" (.getMessage error)))
          (is (= {:tenant "ka"
                  :dataset-config-key "dev"
                  :runtime-config-key "default"
                  :agent-id "agent/custom-agent"}
                 (select-keys (ex-data error) [:tenant :dataset-config-key :runtime-config-key :agent-id])))
          (is (= "No runtime binding for tenant"
                 (get-in (ex-data error) [:runtime-config-error :message])))))))

;; =============================================================================
;; Playground skill-params contract
;;
;; The playground delegates skill-params construction to
;; api.util/build-rag-skill-params (via core/build-playground-skill-params,
;; which is now a thin adapter). These tests lock down the playground-side
;; contract: dataset-config values from runtime/pipeline config reach the
;; nested :builtin/<skill> shape that skills consume.
;; =============================================================================

(def ^:private ^{:doc "Every :builtin/retrieval key that both code paths must forward when its source value is present in config/params. Update this set when adding a new path — the parity test will then fail on whichever side forgot to wire it."} retrieval-skill-param-contract
  #{:retrieve-top-k
    :max-per-document
    :query-aware-boost
    :strategy-weights
    :strategy-contribution-caps})

(deftest build-playground-skill-params-forwards-strategy-overrides
  (testing "Strategy-weights/caps from dataset-config flow into :builtin/retrieval"
    (let [dataset-config {:retrieval-strategy-weights {:phrase 1.0 :content 0.5 :metadata 0.5}
                          :retrieval-strategy-contribution-caps {:phrase 2 :content 1}}
          result (core/build-playground-skill-params dataset-config {} nil nil)]
      (is (= {:phrase 1.0 :content 0.5 :metadata 0.5}
             (get-in result [:builtin/retrieval :strategy-weights])))
      (is (= {:phrase 2 :content 1}
             (get-in result [:builtin/retrieval :strategy-contribution-caps]))))))

(deftest build-playground-skill-params-covers-retrieval-contract
  (testing "Every key in the retrieval contract appears in :builtin/retrieval — new contract entries must be wired in api.util/build-rag-skill-params"
    (let [dataset-config {:retrieval-top-k 42
                          :retrieval-max-per-document 7
                          :retrieval-query-aware-boost true
                          :retrieval-strategy-weights {:phrase 0.5}
                          :retrieval-strategy-contribution-caps {:phrase 1}}
          retrieval-params (:builtin/retrieval (core/build-playground-skill-params dataset-config {} nil nil))
          missing (clojure.set/difference retrieval-skill-param-contract
                                          (set (keys retrieval-params)))]
      (is (empty? missing)
          (str "Playground skill-params is missing contracted retrieval keys: " missing)))))

;; -----------------------------------------------------------------------------
;; Step 4 (Layer B): agent-scoped skill-params surface through the Playground
;; -----------------------------------------------------------------------------
;;
;; These tests are the proof that picking an agent in the Playground dropdown
;; changes which knobs reach the skills. The 5-arity build helper test is the
;; merge-math check; the execute-playground-chat-pipeline tests are the
;; end-to-end check that the resolved agent's :skill-params actually rides
;; through to the rag-params handed to execute-skill-graph.
;;
;; The mocked-agent shape mirrors what digdir.agents.db/get-agent returns —
;; one production-winner agent and one default-no-override agent — so a future
;; reader can see exactly what shape `:skill-params` carries on a real agent.

(def ^:private test-production-winner-agent
  {:id "test/agent-phrase-only"
   :name "Phrase-Only Production Winner"
   :default-skill-graph "builtin/agent-rag-graph-faithful"
   :skill-params {:builtin/retrieval {:strategy-weights {:content 0.0
                                                          :phrase 1.0
                                                          :metadata 0.0}
                                       :strategy-contribution-caps {:phrase 5
                                                                    :content 0
                                                                    :metadata 0}
                                       :retrieve-top-k 100}
                  :builtin/rerank {:top-k 20}}})

(def ^:private test-default-agent
  {:id "test/agent-default"
   :name "Default Agent"
   :default-skill-graph "builtin/agent-rag-graph-bundled"
   :skill-params {}})

(deftest build-playground-skill-params-honours-agent-layer
  (testing "5-arity build-playground-skill-params merges agent skill-params between dataset and UI"
    (let [dataset-config {:retrieval-top-k 42
                          :retrieval-strategy-weights {:phrase 0.5 :content 1.0}}
          agent-skill-params {:builtin/retrieval {:strategy-weights {:content 0.0
                                                                     :phrase 1.0
                                                                     :metadata 0.0}
                                                  :strategy-contribution-caps {:phrase 5}}}
          ;; UI-config (4th arg) is empty so we isolate the agent effect.
          result (core/build-playground-skill-params dataset-config
                                                     {}
                                                     nil
                                                     nil
                                                     agent-skill-params)]
      (is (= {:content 0.0 :phrase 1.0 :metadata 0.0}
             (get-in result [:builtin/retrieval :strategy-weights]))
          "Agent's strategy-weights override the dataset value")
      (is (= {:phrase 5}
             (get-in result [:builtin/retrieval :strategy-contribution-caps]))
          "Agent's caps appear even though the dataset didn't set them")
      (is (= 42 (get-in result [:builtin/retrieval :retrieve-top-k]))
          "Dataset's retrieve-top-k survives where agent didn't override")))

  (testing "4-arity build-playground-skill-params is the no-agent path (backward compat)"
    (let [dataset-config {:retrieval-top-k 42}
          result (core/build-playground-skill-params dataset-config {} nil nil)]
      (is (= 42 (get-in result [:builtin/retrieval :retrieve-top-k]))
          "Existing call sites with no agent see no behaviour change"))))

(deftest execute-playground-chat-pipeline-applies-agent-skill-params
  (testing "When the resolved agent carries :skill-params, those reach the rag-params handed to execute-skill-graph"
    (let [skills-called (promise)
          captured-rag-params (atom nil)]
      (with-redefs [db/get-conn (fn [] (atom nil))
                    config-db/get-conn (fn [] (atom nil))
                    config-db/get-dataset-by-ref (fn [& _]
                                                   {:dataset-id "dataset-1"
                                                    :docs-collection "docs"
                                                    :chunks-collection "chunks"
                                                    :phrases-collection "phrases"})
                    cfg/get-runtime-skill-config-v2-with-trace (fn [_]
                                                                 {:config {} :traces {}
                                                                  :node {:config.node/id "runtime/test"}})
                    config-core/get-master-key (fn [] "master-key")
                    ;; THE KEY STUB: agents-db/get-agent returns the
                    ;; production-winner agent. The pipeline should
                    ;; pick up its :skill-params and layer them in.
                    agents-db/get-agent (fn [_db agent-id]
                                          (case agent-id
                                            "test/agent-phrase-only" test-production-winner-agent
                                            "test/agent-default"     test-default-agent
                                            nil))
                    db/create-playground-conversation (fn [_conn _agent-id _opts]
                                                        {:conversation-id "convo-1"})
                    db/transact-playground-user-msg (fn [& _] {:message/id "user-msg-1"})
                    db/get-message-lineage (fn [& _] nil)
                    db/queue-playground-assistant-msg! (tu/recording-fn {:message/id "assistant-msg-1" :queued? true})
                    llm/use-azure-openai (fn [_] false)
                    core/execute-skill-graph (fn [_execution-id _query _all-messages rag-params _config _ts-opts]
                                                (reset! captured-rag-params rag-params)
                                                (deliver skills-called true)
                                                {:response "ok" :diagnostics {}})]
        (core/execute-playground-chat-pipeline
          {:query "test"
           :agent-id "test/agent-phrase-only"
           :user-id "u1"
           :tenant "ka"
           :dataset-config-key "dev"
           :config {}})
        (is (true? (deref skills-called 1000 false))
            "execute-skill-graph was called")
        (let [skill-params (:skill-params @captured-rag-params)
              retrieval (:builtin/retrieval skill-params)
              rerank (:builtin/rerank skill-params)]
          (is (= {:content 0.0 :phrase 1.0 :metadata 0.0}
                 (:strategy-weights retrieval))
              "Agent's strategy-weights flow into rag-params.skill-params")
          (is (= {:phrase 5 :content 0 :metadata 0}
                 (:strategy-contribution-caps retrieval))
              "Agent's caps flow into rag-params.skill-params")
          (is (= 100 (:retrieve-top-k retrieval))
              "Agent's retrieve-top-k flows into rag-params.skill-params")
          (is (= 20 (:top-k rerank))
              "Agent's rerank :top-k flows into rag-params.skill-params"))))))

(deftest execute-playground-chat-pipeline-default-agent-is-noop
  (testing "An agent with empty :skill-params leaves the merged skill-params untouched (regression-guard)"
    ;; execute-playground-chat-pipeline runs the skill graph on a worker
    ;; thread; without the promise the test would race the assertion
    ;; against an empty `captured-rag-params` atom.
    (let [skills-called (promise)
          captured-rag-params (atom nil)]
      (with-redefs [db/get-conn (fn [] (atom nil))
                    config-db/get-conn (fn [] (atom nil))
                    config-db/get-dataset-by-ref (fn [& _]
                                                   {:retrieval-top-k 75   ;; dataset says 75
                                                    :docs-collection "docs"
                                                    :chunks-collection "chunks"
                                                    :phrases-collection "phrases"})
                    cfg/get-runtime-skill-config-v2-with-trace (fn [_]
                                                                 {:config {} :traces {}
                                                                  :node {:config.node/id "runtime/test"}})
                    config-core/get-master-key (fn [] "master-key")
                    agents-db/get-agent (fn [_db _id] test-default-agent)
                    db/create-playground-conversation (tu/recording-fn {:conversation-id "convo-1"})
                    db/transact-playground-user-msg (fn [& _] {:message/id "user-msg-1"})
                    db/get-message-lineage (fn [& _] nil)
                    db/queue-playground-assistant-msg! (tu/recording-fn {:message/id "assistant-msg-1" :queued? true})
                    llm/use-azure-openai (fn [_] false)
                    core/execute-skill-graph (fn [_eid _q _msgs rag-params _cfg _ts]
                                                (reset! captured-rag-params rag-params)
                                                (deliver skills-called true)
                                                {:response "ok" :diagnostics {}})]
        (core/execute-playground-chat-pipeline
          {:query "test"
           :agent-id "test/agent-default"
           :user-id "u1"
           :tenant "ka"
           :dataset-config-key "dev"
           :config {}})
        (is (true? (deref skills-called 1000 false)))
        (let [retrieval (get-in @captured-rag-params [:skill-params :builtin/retrieval])]
          (is (= 75 (:retrieve-top-k retrieval))
              "Dataset's 75 wins because the agent's empty skill-params don't override")
          (is (nil? (:strategy-contribution-caps retrieval))
              "Empty agent layer doesn't fabricate keys the dataset didn't set"))))))

(deftest api-build-rag-skill-params-covers-retrieval-contract
  (testing "The public API path must also emit every contracted key — keeps the two paths in lockstep"
    (let [config {:retrieval-top-k 42
                  :retrieval-max-per-document 7
                  :retrieval-query-aware-boost true
                  :retrieval-strategy-weights {:phrase 0.5}
                  :retrieval-strategy-contribution-caps {:phrase 1}}
          retrieval-params (:builtin/retrieval ((requiring-resolve 'digdir.api.util/build-rag-skill-params) config {}))
          missing (clojure.set/difference retrieval-skill-param-contract
                                          (set (keys retrieval-params)))]
      (is (empty? missing)
          (str "build-rag-skill-params is missing contracted retrieval keys: " missing)))))

(deftest execute-skill-graph-propagates-retrieval-overrides-to-run-skill-graph
  (testing "End-to-end: strategy-weights/caps in dataset-config reach the opts passed into run-skill-graph"
    (let [execution-id "exec-retrieval-overrides"
          captured-opts (atom nil)]
      (swap! core/!playground-executions assoc execution-id {:events [] :results {}})
      (try
        (with-redefs [skills-api/initialize! (fn [] nil)
                      core/get-typesense-diagnostics (fn [_expected-collections _opts]
                                                      {:connected true :expected-collections []})
                      skills-api/run-skill-graph
                      (fn [_skill-graph-id _inputs opts]
                        (reset! captured-opts opts)
                        {:outputs {:response "ok" :trace []}
                         :step-results {}
                         :execution-metadata {:total-duration-ms 0 :steps-executed 0 :stage-timings []}})]
          (core/execute-skill-graph
            execution-id
            "query"
            [{:message/role :user :message/text "query"}]
            {:docs-collection "d" :chunks-collection "c" :phrases-collection "p"
             :skill-params (core/build-playground-skill-params
                             {:retrieval-strategy-weights {:phrase 1.0 :content 0.5 :metadata 0.5}
                              :retrieval-strategy-contribution-caps {:phrase 2 :content 1}}
                             {} nil nil)}
            {:skill-graph "builtin/agent-rag"}
            nil)
          (is (= {:phrase 1.0 :content 0.5 :metadata 0.5}
                 (get-in @captured-opts [:skill-params :builtin/retrieval :strategy-weights])))
          (is (= {:phrase 2 :content 1}
                 (get-in @captured-opts [:skill-params :builtin/retrieval :strategy-contribution-caps]))))
        (finally
          (swap! core/!playground-executions dissoc execution-id))))))

(deftest execute-skill-graph-omits-model-when-user-did-not-pick
  (testing "opts passed to run-skill-graph excludes :model unless rag-params has :user-model — preserves :synthesis-model runtime config flow"
    (let [execution-id "exec-no-user-model"
          captured-opts (atom nil)]
      (swap! core/!playground-executions assoc execution-id {:events [] :results {}})
      (try
        (with-redefs [skills-api/initialize! (fn [] nil)
                      core/get-typesense-diagnostics (fn [_expected-collections _opts]
                                                      {:connected true :expected-collections []})
                      skills-api/run-skill-graph
                      (fn [_skill-graph-id _inputs opts]
                        (reset! captured-opts opts)
                        {:outputs {:response "ok" :trace []}
                         :step-results {}
                         :execution-metadata {:total-duration-ms 0 :steps-executed 0 :stage-timings []}})]
          (core/execute-skill-graph
            execution-id
            "query"
            [{:message/role :user :message/text "query"}]
            ;; No :user-model in rag-params → user is on "Default" picker.
            {:docs-collection "d" :chunks-collection "c" :phrases-collection "p"
             :selected-model "gpt-4o"
             :skill-params {}}
            {:skill-graph "builtin/agent-rag"}
            nil)
          (is (not (contains? @captured-opts :model))
              "opts.model must be absent when no user override — runtime :synthesis-model would otherwise be shadowed"))
        (finally
          (swap! core/!playground-executions dissoc execution-id))))))

(def ^:private expected-diagnostics-keys
  "Snapshot of the keyset the Playground UI reads from
   `execute-skill-graph`'s `:diagnostics` map. Phase 1 of the MCP server
   migration extracted the core RAG invocation into
   `digdir.skills.invoke/invoke-rag`; the Playground keeps its own
   diagnostics shape on top of `invoke-rag`'s output so the existing UI
   doesn't have to know about the refactor.

   The keys below are everything the UI's diagnostics panels render or
   compare against (see `playground/core.cljc:595-646`). Adding a new
   key here is fine — the assertion uses `set/subset?`. Removing one
   means the UI stops getting data it previously rendered: add the key
   back, or remove the corresponding UI consumer in the same change."
  #{:status :clarification-request
    :query-relaxation :query-intent :budget-state
    :typesense-startup-diagnostics
    :search-history :read-history :search-errors :backend-issues
    :sufficiency-decisions :last-insufficiency
    :last-response-validation-insufficiency
    :phrase-search-count :metadata-search-count :content-search-count
    :merged-count
    :phrase-search :metadata-search :content-search :merged-results
    :used-chunks-count :used-chunks
    :skill-execution-metadata :execution-timing
    :execution-stage-timings :agent-stage-timings :total-duration-ms
    :agent-trace :agent-trace-count :action-trace
    :report-structured
    :retrieval-filters
    :citations :citation-index
    :auto-filter-applied :auto-filter-fallback})

(deftest execute-skill-graph-diagnostics-shape-snapshot
  (testing "Diagnostics map exposes the full keyset the UI panels render.
            Regression net for refactors of invoke-rag /
            execute-skill-graph that might silently drop a key."
    (let [execution-id "exec-diag-snapshot"
          stub-outputs {:response "answer"
                        :chunks [{:chunk_id "c1" :doc_num 1 :search-types #{:phrase}}]
                        :queries ["q1" "q2"]
                        :trace [{:iteration 0 :reasoning "..."}]
                        :search-history [{:phase :search :chunk-summaries []}]
                        :read-history [{:tool "read_chunks"}]
                        :search-errors []
                        :backend-issues [{:source :typesense :issue-type :other
                                          :message "x" :tool "t" :details {}}]
                        :search-attribution {:phrase 1 :metadata 0 :content 0 :merged 1
                                             :auto-filter-applied {:filter "x"}
                                             :auto-filter-fallback false}
                        :search-attributions [{:phrase 1 :metadata 0 :content 0 :merged 1}]
                        :query-intent {:answer-type :lookup}
                        :budget-state {:max-search-passes 4}
                        :sufficiency-decisions [{:status :sufficient}]
                        :last-insufficiency nil
                        :last-response-validation-insufficiency nil
                        :report-structured {:kept 3 :reverted 1}
                        :citations [{:chunk_id "c1"}]
                        :citation-index {:chunk_id "c1" :ordinal 1}}]
      (swap! core/!playground-executions assoc execution-id {:events [] :results {}})
      (try
        (with-redefs [skills-api/initialize! (fn [] nil)
                      core/get-typesense-diagnostics
                      (fn [_expected-collections _opts]
                        {:connected true :expected-collections [] :available-collections []
                         :missing-collections [] :all-collections-exist true})
                      skills-api/run-skill-graph
                      (fn [_id _inputs _opts]
                        {:outputs stub-outputs
                         :step-results {:agent {:outputs {:trace (:trace stub-outputs)}
                                                :metadata {:stage-timings [{:stage :agent-llm
                                                                            :iteration 0
                                                                            :duration-ms 5
                                                                            :status :ok}]}}}
                         :execution-metadata {:total-duration-ms 7
                                              :step-timings {:agent {:duration-ms 7}}
                                              :stage-timings [{:step-id :agent :duration-ms 7}]}})]
          (let [result (core/execute-skill-graph
                         execution-id
                         "q"
                         [{:message/role :user :message/text "q"}]
                         {:docs-collection "d" :chunks-collection "c" :phrases-collection "p"
                          :skill-params {}}
                         {:skill-graph "builtin/agent-rag"}
                         nil)
                actual (set (keys (:diagnostics result)))
                missing (clojure.set/difference expected-diagnostics-keys actual)]
            (is (empty? missing)
                (str "Diagnostics map dropped UI-visible keys: " (vec missing)))
            ;; Spot-check that values flow through (not just keys present).
            (is (= [{:iteration 0 :reasoning "..."}]
                   (get-in result [:diagnostics :agent-trace])))
            (is (= 1 (get-in result [:diagnostics :agent-trace-count])))
            (is (= {:kept 3 :reverted 1}
                   (get-in result [:diagnostics :report-structured])))
            (is (= [] (get-in result [:diagnostics :action-trace])))
            (is (= [{:chunk_id "c1"}]
                   (get-in result [:diagnostics :citations])))
            (is (= 7 (get-in result [:diagnostics :total-duration-ms])))))
        (finally
          (swap! core/!playground-executions dissoc execution-id))))))

(deftest execute-skill-graph-shares-canonical-actions-live-and-completed
  (let [execution-id "exec-canonical-actions"]
    (swap! core/!playground-executions assoc execution-id {:events [] :results {}})
    (try
      (with-redefs [skills-api/initialize! (fn [] nil)
                    core/get-typesense-diagnostics
                    (fn [_expected-collections _opts]
                      {:connected true :expected-collections []})
                    skills-api/run-skill-graph
                    (fn [_id _inputs opts]
                      (let [progress (:progress-fn opts)]
                        (progress {:event :step/started
                                   :step-id :plan
                                   :skill-id :builtin/query-planner})
                        (progress {:event :step/completed
                                   :step-id :plan
                                   :skill-id :builtin/query-planner
                                   :duration-ms 12
                                   :outputs {:queries ["canonical query"]
                                             :user-intent "intent"}})
                        (progress {:event :step/started
                                   :step-id :retrieve
                                   :skill-id :builtin/retrieval})
                        (progress {:event :step/completed
                                   :step-id :retrieve
                                   :skill-id :builtin/retrieval
                                   :duration-ms 20
                                   :outputs {:chunks [{:chunk_id "c1"}]
                                             :search-attribution {:merged 1
                                                                  :phrase 1}}})
                        {:outputs {:response "answer"
                                   :chunks [{:chunk_id "c1"}]
                                   :queries ["canonical query"]}
                         :step-results {}
                         :execution-metadata {:total-duration-ms 32
                                              :steps-executed 2
                                              :stage-timings []}}))]
        (let [result (core/execute-skill-graph
                      execution-id
                      "query"
                      [{:message/role :user :message/text "query"}]
                      {:docs-collection "d" :chunks-collection "c"
                       :phrases-collection "p" :skill-params {}}
                      {:skill-graph "builtin/ai-overview"}
                      nil)
              live-trace (get-in @core/!playground-executions
                                 [execution-id :action-trace])
              persisted-trace (get-in result [:diagnostics :action-trace])]
          (is (= live-trace persisted-trace))
          (is (= [:plan :search] (mapv :kind persisted-trace)))
          (is (= ["canonical query"]
                 (get-in persisted-trace [0 :result :queries])))
          (is (= 1 (get-in persisted-trace [1 :result :candidate-count])))
          (is (nil? (get-in @core/!playground-executions
                            [execution-id :live-agent-trace])))))
      (finally
        (swap! core/!playground-executions dissoc execution-id)))))

(deftest execute-skill-graph-passes-model-when-user-picks
  (testing "opts.model is set when rag-params has :user-model — explicit user choice wins over runtime config"
    (let [execution-id "exec-user-model-picked"
          captured-opts (atom nil)]
      (swap! core/!playground-executions assoc execution-id {:events [] :results {}})
      (try
        (with-redefs [skills-api/initialize! (fn [] nil)
                      core/get-typesense-diagnostics (fn [_expected-collections _opts]
                                                      {:connected true :expected-collections []})
                      skills-api/run-skill-graph
                      (fn [_skill-graph-id _inputs opts]
                        (reset! captured-opts opts)
                        {:outputs {:response "ok" :trace []}
                         :step-results {}
                         :execution-metadata {:total-duration-ms 0 :steps-executed 0 :stage-timings []}})]
          (core/execute-skill-graph
            execution-id
            "query"
            [{:message/role :user :message/text "query"}]
            {:docs-collection "d" :chunks-collection "c" :phrases-collection "p"
             :user-model "gpt-4o-mini"
             :selected-model "gpt-4o-mini"
             :skill-params {}}
            {:skill-graph "builtin/agent-rag"}
            nil)
          (is (= "gpt-4o-mini" (:model @captured-opts))))
        (finally
          (swap! core/!playground-executions dissoc execution-id))))))

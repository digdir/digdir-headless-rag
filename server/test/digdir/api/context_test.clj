(ns digdir.api.context-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.tools.logging.test :refer [logged? with-log]]
            [digdir.api.context :as api-ctx]
            [digdir.agents.db :as agents-db]
            [digdir.config.accessor :as cfg]
            [digdir.config.api-keys :as api-keys]
            [digdir.config.db :as config-db]
            [digdir.config.structure :as structure]))

(deftest root-config-key-params-derive-from-the-server-structure
  (testing "every canonical root follows the root-scoped request-key convention"
    (doseq [root structure/config-roots-ordered]
      (is (= (keyword (str (name root) "-config-key"))
             (api-ctx/root-config-key-param root)))))
  (testing "unknown roots remain a client error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown config root"
                          (api-ctx/root-config-key-param :not-a-config-root)))))

(defn example-agent
  ([] (example-agent {}))
  ([overrides]
   (merge {:id "builtin/agent-rag-agent"
           :name "Agentic RAG Agent"
           :default-skill-graph "builtin/agent-rag"
           :allowed-skill-graphs ["builtin/agent-rag"]
           :allowed-dataset-scopes []
           :guardrails {}
           :enabled? true}
          overrides)))

;; =============================================================================
;; #349 — select-request-agent! counts CANDIDATES, and validates the selection.
;;
;; One row per row of the measured table. Before this change the code counted
;; GRANTS while every other surface counted REACHABLE agents, which produced two
;; opposite-looking symptoms from one mismatch, and nothing validated the
;; selection at all: `POST /api/conversations` returned 201 and PERSISTED a
;; conversation against `no/such-agent-xyz`.
;; =============================================================================

(defn- with-agents
  "Run `f` with the registry stubbed to `agents` (maps with :id and :enabled?)."
  [agents f]
  (with-redefs [config-db/get-conn (fn [] (atom :fake-conn))
                agents-db/list-enabled-agents (fn [_] (filterv :enabled? agents))
                agents-db/get-agent (fn [_ id] (some #(when (= id (:id %)) %) agents))]
    (f)))

(def ^:private a1 (example-agent {:id "a/one"}))
(def ^:private a2 (example-agent {:id "a/two"}))
(def ^:private a3-off (example-agent {:id "a/three" :enabled? false}))

(defn- select
  "The `_agents` arg is unused — `with-agents` supplies the world through
   redefs — but each call site names it, so the row under test reads in one
   line instead of two."
  [_agents agent-refs params]
  (api-ctx/select-request-agent! {:api-key/agent-refs agent-refs} params))

(defn- status-of [f]
  (try (f) ::no-throw
       (catch clojure.lang.ExceptionInfo e (:status (ex-data e)))))

(deftest select-agent-candidates-is-grants-intersected-with-reachable
  (testing "the pure rule: empty grants mean every reachable agent (#349)"
    (is (= #{"a/one" "a/two"} (api-ctx/candidate-agent-ids [] #{"a/one" "a/two"})))
    (is (= #{"a/one"} (api-ctx/candidate-agent-ids ["a/one"] #{"a/one" "a/two"})))
    (is (= #{"a/one"} (api-ctx/candidate-agent-ids ["a/one" "a/gone"] #{"a/one" "a/two"}))
        "a granted agent that is not reachable is not a candidate")
    (is (= #{} (api-ctx/candidate-agent-ids ["a/gone"] #{"a/one"})))
    (is (= #{} (api-ctx/candidate-agent-ids [] #{})))))

(deftest select-agent-zero-grants-many-candidates-asks-which
  (testing "was 401 'missing agent grants'; the key is authorized, it is the
            CHOICE that is ambiguous — same answer as the multi-grant case"
    (with-agents [a1 a2]
      (fn [] (is (= 400 (status-of #(select [a1 a2] [] {}))))))))

(deftest select-agent-zero-grants-one-candidate-defaults-to-it
  (testing "was 401 even though exactly one agent existed to pick (#349)"
    (with-agents [a1]
      (fn [] (is (= "a/one" (select [a1] [] {})))))))

(deftest select-agent-zero-candidates-is-its-own-error
  (testing "nothing reachable at all — not a 400 telling the caller to name one"
    (with-agents []
      (fn [] (is (= 503 (status-of #(select [] [] {}))))))))

(deftest select-agent-one-grant-that-is-disabled-is-unavailable
  (testing "was 201, defaulting to a DISABLED agent and persisting it (#349)"
    (with-agents [a3-off]
      (fn [] (is (= 503 (status-of #(select [a3-off] ["a/three"] {}))))))))

(deftest select-agent-multi-grant-with-one-reachable-defaults
  (testing "was 400 'specify agent-id', offering a set containing a disabled
            agent; only one is actually a candidate, so it defaults"
    (with-agents [a1 a3-off]
      (fn [] (is (= "a/one" (select [a1 a3-off] ["a/one" "a/three"] {})))))))

(deftest select-agent-multi-grant-all-reachable-asks-which
  (with-agents [a1 a2]
    (fn [] (is (= 400 (status-of #(select [a1 a2] ["a/one" "a/two"] {})))))))

(deftest select-agent-honours-an-explicit-agent-id
  (with-agents [a1 a2]
    (fn [] (is (= "a/two" (select [a1 a2] ["a/one" "a/two"] {:agent-id "a/two"}))))))

(deftest select-agent-rejects-an-agent-the-key-does-not-grant
  (testing "reachable, but outside the grant list"
    (with-agents [a1 a2]
      (fn [] (is (= 403 (status-of #(select [a1 a2] ["a/one"] {:agent-id "a/two"}))))))))

(deftest select-agent-rejects-a-nonexistent-agent
  (testing "THE DATA-INTEGRITY ROW. This returned 201 and persisted a
            conversation with agentId 'no/such-agent-xyz' (#349)"
    (with-agents [a1]
      (fn [] (is (= 404 (status-of #(select [a1] [] {:agent-id "no/such-agent-xyz"}))))))))

(deftest select-agent-rejects-a-disabled-agent-by-name
  (testing "exists but is not reachable — 403, the status load-agent! already used"
    (with-agents [a1 a3-off]
      (fn [] (is (= 403 (status-of #(select [a1 a3-off] [] {:agent-id "a/three"}))))))))

(deftest test-resolve-request-config-node-enforces-node-validity-and-ceilings
  (testing "Config node resolution rejects invalid nodes and checks allowed config keys"
    (let [ceiling-call (atom nil)]
      (with-redefs [config-db/get-config-node-by-tenant-config-key
                    (fn [_ tenant root slug]
                      {:config.node/id (str (name root) "/" tenant "/" slug)
                       :config.node/tenant-config-key slug
                       :config.node/enabled? true})
                    api-keys/require-allowed-config-key!
                    (fn [_ allowed-config-keys opts]
                      (reset! ceiling-call {:allowed-config-keys allowed-config-keys
                                            :opts opts})
                      {:matched true})]
        (let [conn (atom :config-db)
              request {:api-key/allowed-config-keys [{:api-key.allowed-config-key/id "dataset-default"}]}
              {:keys [node matched-allowed-config-key]}
              (api-ctx/resolve-request-config-node! request
                                                    conn
                                                    {:tenant "ka"
                                                     :root :dataset
                                                     :tenant-config-key "prod"})]
          (is (= "dataset/ka/prod" (:config.node/id node)))
          (is (= {:matched true} matched-allowed-config-key))
          (is (= {:allowed-config-keys [{:api-key.allowed-config-key/id "dataset-default"}]
                  :opts {:root :dataset
                         :tenant "ka"
                         :node-id "dataset/ka/prod"}}
                 @ceiling-call)))))
    (with-redefs [config-db/get-config-node-by-tenant-config-key
                  (fn [_ tenant root slug]
                    {:config.node/id (str (name root) "/" tenant "/" slug)
                     :config.node/tenant-config-key slug
                     :config.node/enabled? false})]
      (let [conn (atom :config-db)
            error (try
                    (api-ctx/resolve-request-config-node! {} conn {:tenant "ka"
                                                                   :root :dataset
                                                                   :tenant-config-key "prod"})
                    nil
                    (catch clojure.lang.ExceptionInfo e
                      e))]
        (is error)
        (is (= 403 (:status (ex-data error))))))
    (with-redefs [config-db/get-config-node-by-tenant-config-key
                  (fn [_ tenant root slug]
                    {:config.node/id (str (name root) "/" tenant "/" slug)
                     :config.node/tenant-config-key slug
                     :config.node/enabled? true
                     :config.node/system-managed? true})]
      (let [conn (atom :config-db)
            error (try
                    (api-ctx/resolve-request-config-node! {} conn {:tenant "ka"
                                                                   :root :dataset
                                                                   :tenant-config-key "prod"})
                    nil
                    (catch clojure.lang.ExceptionInfo e
                      e))]
        (is error)
        (is (= 400 (:status (ex-data error))))))))

(deftest test-select-request-dataset-ref-requires-explicit-selection-when-multiple-grants
  (testing "Dataset selection must be explicit when multiple API key grants exist"
    (let [request {:api-key/dataset-scopes [{:tenant "ka" :dataset-config-key "prod"}
                                          {:tenant "altinn-docs" :dataset-config-key "dev"}]}
          error (atom nil)]
      (with-log
        (reset! error (try
                        (api-ctx/select-request-dataset-ref! request {:query "hello"})
                        nil
                        (catch clojure.lang.ExceptionInfo e
                          e)))
        (is @error)
        (is (= 400 (:status (ex-data @error))))
        (is (= [{:tenant "ka" :dataset-config-key "prod"}
                {:tenant "altinn-docs" :dataset-config-key "dev"}]
               (:available (ex-data @error))))
        (is (logged? 'digdir.api.context
                     :debug
                     #"Rejecting API request because dataset selection is required for this API key"))))))

(deftest test-resolve-request-execution-context-merges-config-and-traces
  (testing "Agent-scoped request resolution returns merged config, policy, and traces"
    (let [runtime-call (atom nil)
          ceiling-call (atom nil)]
      (with-redefs [config-db/get-conn (fn [] (atom :config-db))
                    ;; #349: selection resolves candidates against the enabled
                    ;; registry — the agent has to be reachable, not just found.
                    agents-db/list-enabled-agents (fn [_] [(example-agent)])
                    agents-db/get-agent (fn [_ _]
                                          (example-agent
                                           {:allowed-dataset-scopes [{:tenant "ka"
                                                                    :dataset-config-key "prod"}]}))
                    config-db/get-dataset-by-ref (fn [_ dataset-ref _]
                                                   (is (= {:tenant "ka"
                                                           :dataset-config-key "prod"}
                                                          dataset-ref))
                                                   {:dataset-id "ds-123"
                                                    :tenant "ka"
                                                    :dataset-config-key "prod"
                                                    :docs-collection "docs"
                                                    :chunks-collection "chunks"
                                                    :phrases-collection "phrases"})
                    cfg/load-dataset-config-v2-with-trace (fn [opts]
                                                            (is (= {:tenant "ka"
                                                                    :dataset-config-key "prod"
                                                                    :dataset-id "ds-123"
                                                                    :paths #{"pipeline.storage.collection-prefix"
                                                                             "pipeline.storage.docs-collection"
                                                                             "pipeline.storage.chunks-collection"
                                                                             "pipeline.storage.phrases-collection"}}
                                                                           opts))
                                                            {:node {:config.node/id "dataset/ka/prod"}
                                                             :traces {"pipeline.storage.docs-collection"
                                                                      {:resolved-from "dataset/ka/prod"}}})
                    cfg/get-runtime-skill-config-v2-with-trace (fn [opts]
                                                                 (reset! runtime-call opts)
                                                                 {:config {:rerank-top-k 77
                                                                           :query-planner-prompt "v2 prompt"}
                                                                  :traces {"skills.rerank.top-k"
                                                                           {:resolved-from "runtime/ka/default"}}
                                                                  :node {:config.node/id "runtime/ka/default"}})
                    config-db/get-config-node-by-tenant-config-key (fn [_ tenant root slug]
                                                                     {:config.node/id (str (name root) "/" tenant "/" slug)
                                                                      :config.node/tenant-config-key slug
                                                                      :config.node/enabled? true})
                    api-keys/require-allowed-config-key! (fn [_ allowed-config-keys opts]
                                                       (reset! ceiling-call {:allowed-config-keys allowed-config-keys
                                                                             :opts opts})
                                                       {:matched true})]
        (let [request {:api-key/agent-refs ["builtin/agent-rag-agent"]
                       :api-key/dataset-scopes [{:tenant "ka" :dataset-config-key "prod"}]
                       :api-key/allowed-config-keys [{:api-key.allowed-config-key/id "dataset-default"}]}
              params {:query "hello"
                      :runtime-config-key "default"
                      :dataset-ref {:tenant "ka"
                                    :dataset-config-key "prod"}}
              resolved (api-ctx/resolve-request-execution-context! request params {:require-runtime-config? true})]
          (is (= "builtin/agent-rag-agent" (:agent-id resolved)))
          (is (= "builtin/agent-rag" (:skill-graph-id resolved)))
          (is (= {:tenant "ka" :dataset-config-key "prod"} (:dataset-ref resolved)))
          (is (= {:tenant "ka"
                  :tenant-config-key "default"
                  :agent-id "builtin/agent-rag-agent"
                  :dataset-id "ds-123"}
                 @runtime-call))
          (is (= 77 (get-in resolved [:config :rerank-top-k])))
          (is (= "v2 prompt" (get-in resolved [:config :query-planner-prompt])))
          (is (= "docs" (get-in resolved [:dataset-inputs :docs-collection])))
          (is (= {:dataset {"pipeline.storage.docs-collection" {:resolved-from "dataset/ka/prod"}}
                  :runtime {"skills.rerank.top-k" {:resolved-from "runtime/ka/default"}}}
                 (:traces resolved)))
          (is (= {:allowed-config-keys [{:api-key.allowed-config-key/id "dataset-default"}]
                  :opts {:root :dataset
                         :tenant "ka"
                         :node-id "dataset/ka/prod"}}
                 @ceiling-call)))))))

(deftest test-resolve-request-execution-context-requires-runtime-config-key-for-agent-flow
  (testing "Agent-scoped requests fail fast when runtime-config-key is required"
    (with-redefs [config-db/get-conn (fn [] (atom :config-db))
                  ;; #349: select-request-agent! now resolves candidates against
                  ;; the enabled registry, so the agent must be reachable here.
                  agents-db/list-enabled-agents (fn [_] [(example-agent)])
                  agents-db/get-agent (fn [_ _] (example-agent))]
      (let [request {:api-key/agent-refs ["builtin/agent-rag-agent"]
                     :api-key/dataset-scopes [{:tenant "ka" :dataset-config-key "prod"}]}
            error (try
                    (api-ctx/resolve-request-execution-context! request
                                                                {:query "hello"
                                                                 :dataset-ref {:tenant "ka"
                                                                               :dataset-config-key "prod"}}
                                                                {:require-runtime-config? true})
                    nil
                    (catch clojure.lang.ExceptionInfo e
                      e))]
        (is error)
        (is (= 400 (:status (ex-data error))))
        (is (= "Missing required field: runtime-config-key" (.getMessage error)))))))

(deftest test-resolve-request-execution-context-skip-agent-preserves-dataset-only-flow
  (testing "Dataset-only flows bypass agent loading and still resolve dataset config"
    (with-redefs [config-db/get-conn (fn [] (atom :config-db))
                  agents-db/get-agent (fn [& _]
                                        (throw (ex-info "skip-agent path should not load agents" {})))
                  config-db/get-dataset-by-ref (fn [_ dataset-ref _]
                                                 {:dataset-id "ds-456"
                                                  :tenant (:tenant dataset-ref)
                                                  :dataset-config-key (:dataset-config-key dataset-ref)
                                                  :docs-collection "docs"
                                                  :chunks-collection "chunks"
                                                  :phrases-collection "phrases"})
                  config-db/get-config-node-by-tenant-config-key (fn [_ tenant root slug]
                                                                   {:config.node/id (str (name root) "/" tenant "/" slug)
                                                                    :config.node/tenant-config-key slug
                                                                    :config.node/enabled? true})]
      (let [request {:api-key/dataset-scopes [{:tenant "altinn-docs"
                                             :dataset-config-key "dev"}]}
            resolved (api-ctx/resolve-request-execution-context! request
                                                                 {:query "hello"
                                                                  :dataset-ref {:tenant "altinn-docs"
                                                                                :dataset-config-key "dev"}}
                                                                 {:skip-agent? true})]
        (is (nil? (:agent-id resolved)))
        (is (= {:tenant "altinn-docs" :dataset-config-key "dev"} (:dataset-ref resolved)))
        (is (= "docs" (get-in resolved [:config :docs-collection])))))))

(ns digdir.mcp.tools-test
  (:require [digdir.test-utils :as tu]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [digdir.agents.db :as agents-db]
            [digdir.api.util :as api-util]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.data.db :as data-db]
            [digdir.mcp.tools :as mcp-tools]
            [digdir.skills.api :as skills-api]
            [digdir.skills.invoke :as invoke]))

(def ^:private docs-agent
  {:id "builtin/docs-agent"
   :name "Docs Agent"
   :description "Curates docs."
   :default-skill-graph :docs/self-improve-graph
   :allowed-skill-graphs [:docs/self-improve-graph :docs/outline-graph]
   :allowed-dataset-scopes [{:tenant "altinn-docs"
                             :dataset-config-key "dev"}]
   :enabled? true})

(def ^:private rag-agent
  {:id "builtin/rag-agent"
   :name "RAG Agent"
   :description "Answers questions."
   :default-skill-graph :builtin/agent-rag-graph-bundled
   :allowed-skill-graphs [:builtin/agent-rag-graph-bundled]
   :allowed-dataset-scopes [{:tenant "altinn-docs"
                             :dataset-config-key "dev"}]
   :enabled? true})

(def ^:private disabled-agent
  (assoc rag-agent
         :id "builtin/disabled-agent"
         :enabled? false))

(defn- stub-skill-graph
  [graph-id]
  {:id graph-id
   :name (name graph-id)
   :description "stub"
   :input-schema [:map
                  [:user-query [:string {:min 1}]]
                  [:claim {:optional true} :string]]})

(defn- with-stubs
  "Stub the registries the tools namespace reads from."
  [agents f]
  (with-redefs [config-db/get-conn (fn [] (atom :fake-config-conn))
                agents-db/list-enabled-agents (fn [_] (filter :enabled? agents))
                agents-db/get-agent (fn [_ agent-id]
                                      (some #(when (= agent-id (:id %)) %)
                                            agents))
                skills-api/initialize! (fn [] nil)
                skills-api/get-skill-graph-info stub-skill-graph]
    (f)))

(deftest list-tools-filters-by-agent-refs
  (testing "Returns the cross-product of agents × allowed-skill-graphs,
            scoped to API key :agent-refs"
    (with-stubs [docs-agent rag-agent disabled-agent]
      (fn []
        (let [tools (mcp-tools/list-tools
                      {:api-key/agent-refs ["builtin/docs-agent"]
                       :api-key/skill-graphs []})]
          (is (= 2 (count tools)))
          (let [names (set (map :name tools))]
            (is (contains? names "builtin.docs-agent__self-improve-graph"))
            (is (contains? names "builtin.docs-agent__outline-graph"))))))))

(deftest list-tools-hides-disabled
  (testing "Disabled agents do not surface tools"
    (with-stubs [docs-agent rag-agent disabled-agent]
      (fn []
        (let [tools (mcp-tools/list-tools
                      {:api-key/agent-refs ["builtin/disabled-agent"]
                       :api-key/skill-graphs []})]
          (is (empty? tools)))))))

(deftest list-tools-without-agent-refs-returns-all-enabled
  (testing "Empty :agent-refs means no key-level filter"
    (with-stubs [docs-agent rag-agent disabled-agent]
      (fn []
        (let [tools (mcp-tools/list-tools
                      {:api-key/agent-refs []
                       :api-key/skill-graphs []})
              names (set (map :name tools))]
          (is (contains? names "builtin.rag-agent__agent-rag-graph-bundled"))
          (is (contains? names "builtin.docs-agent__self-improve-graph"))
          (is (contains? names "builtin.docs-agent__outline-graph")))))))

(deftest list-tools-marks-default
  (testing "_meta carries :default for the agent's default-skill-graph"
    (with-stubs [docs-agent]
      (fn []
        (let [tools (mcp-tools/list-tools
                      {:api-key/agent-refs ["builtin/docs-agent"]
                       :api-key/skill-graphs []})
              default-tool (some #(when (true? (get-in % [:_meta :default])) %)
                                 tools)]
          (is (= "builtin.docs-agent__self-improve-graph" (:name default-tool))))))))

(deftest list-tools-handles-string-skill-graph-ids
  (testing "Agents DB stores :allowed-skill-graphs and :default-skill-graph
            as strings (e.g. \"builtin/agent-rag-graph-bundled\"). The MCP
            server must normalize these to keywords so it can look the
            graph up in the skills-api registry and emit the short-name
            half of the tool id correctly."
    (let [string-agent (-> rag-agent
                            (assoc :allowed-skill-graphs ["builtin/agent-rag-graph-bundled"]
                                   :default-skill-graph "builtin/agent-rag-graph-bundled"))]
      (with-stubs [string-agent]
        (fn []
          (let [tools (mcp-tools/list-tools
                        {:api-key/agent-refs ["builtin/rag-agent"]
                         :api-key/skill-graphs []})]
            (is (= 1 (count tools)))
            (is (= "builtin.rag-agent__agent-rag-graph-bundled"
                   (:name (first tools))))
            (is (true? (get-in (first tools) [:_meta :default])))))))))

(deftest invoke-tool-rejects-bad-name
  (with-stubs [docs-agent]
    (fn []
      (let [{:keys [error]} (mcp-tools/invoke-tool
                              {:api-key/agent-refs ["builtin/docs-agent"]}
                              "no-double-underscore"
                              {"query" "hi"}
                              nil)]
        (is (= "invalid_tool_name" (:code error)))))))

(deftest invoke-tool-rejects-unauthorized-agent
  (with-stubs [docs-agent]
    (fn []
      (let [{:keys [error]} (mcp-tools/invoke-tool
                              {:api-key/agent-refs ["other-agent"]}
                              "builtin.docs-agent__self-improve-graph"
                              {"query" "hi"}
                              nil)]
        (is (= "agent_not_authorized" (:code error)))))))

;; =============================================================================
;; Error channels (#117) — the real code paths, not stubbed shapes
;;
;; The transport tests assert the wire envelope per channel. These assert that
;; the codes the real invoke-tool emits are the codes that classification knows
;; about, so the two halves cannot drift into agreeing about nothing.
;; =============================================================================

(deftest real-error-paths-land-on-the-intended-channel
  (with-stubs [docs-agent]
    (fn []
      (testing "a tool name that cannot exist -> invalid-params"
        (let [{:keys [error]} (mcp-tools/invoke-tool
                                {:api-key/agent-refs ["builtin/docs-agent"]}
                                "no-double-underscore" {"query" "hi"} nil)]
          (is (= "invalid_tool_name" (:code error)))
          (is (= :invalid-params (mcp-tools/error-channel error)))))

      (testing "an agent that does not exist -> invalid-params"
        (let [{:keys [error]} (mcp-tools/invoke-tool
                                {} "no-such-agent__some-graph" {"query" "hi"} nil)]
          (is (= "agent_not_found" (:code error)))
          (is (= :invalid-params (mcp-tools/error-channel error)))))

      (testing "an authorization failure -> back to the model"
        (let [{:keys [error]} (mcp-tools/invoke-tool
                                {:api-key/agent-refs ["other-agent"]}
                                "builtin.docs-agent__self-improve-graph" {"query" "hi"} nil)]
          (is (= "agent_not_authorized" (:code error)))
          (is (= :tool-error (mcp-tools/error-channel error)))
          (is (true? (:isError (mcp-tools/error->tool-result error))))
          (is (= (:message error)
                 (-> (mcp-tools/error->tool-result error) :content first :text))
              "the text a model needs must survive the rendering"))))))

(deftest every-emitted-error-code-is-classified
  (testing "each code invoke-tool can return has an intended channel"
    ;; An unclassified code degrades to :tool-error - it tells the model rather
    ;; than claiming the server broke - so this asserts intent, not safety.
    (is (= {"invalid_tool_name"          :invalid-params
            "agent_not_found"            :invalid-params
            "mode_not_allowed"    :invalid-params
            "agent_disabled"             :tool-error
            "agent_not_authorized"       :tool-error
            "mode_not_authorized" :tool-error
            "no_dataset_scope"           :tool-error
            "dataset_not_authorized"     :tool-error
            "missing_query"              :tool-error
            "invalid_overrides"          :invalid-params}
           (into {} (map (fn [c] [c (mcp-tools/error-channel {:code c})]))
                 ["invalid_tool_name" "agent_not_found" "mode_not_allowed"
                  "agent_disabled" "agent_not_authorized" "mode_not_authorized"
                  "no_dataset_scope" "dataset_not_authorized" "missing_query"
                  "invalid_overrides"])))))

(deftest invoke-tool-falls-back-when-agent-has-empty-scopes
  (testing "Built-in agents seeded with empty :allowed-dataset-scopes are
            invocable when the API key carries dataset-scopes (or the env
            defaults are set, or the caller passes tenant/dataset_config_key
            in args). Regression for the conformance-check finding
            2026-05-21 — empty agent scopes previously meant 'no scope matches'."
    (let [unscoped-agent (assoc rag-agent :allowed-dataset-scopes [])
          captured-invoke (atom nil)]
      (with-stubs [unscoped-agent]
        (fn []
          (with-redefs [config-core/get-master-key (fn [] "k")
                        config-db/get-dataset-by-ref
                        (fn [_ _ _] {:docs-collection "d" :chunks-collection "c"
                                     :phrases-collection "p"})
                        api-util/build-rag-skill-params (tu/recording-fn {})
                        data-db/get-conn (fn [] (atom :fake))
                        data-db/create-playground-conversation
                        (fn [_ _ _] {:conversation-id "convo-1"})
                        data-db/fetch-conversation-tree (fn [_ _] [])
                        data-db/transact-playground-user-msg (fn [& _] nil)
                        data-db/transact-assistant-msg (tu/recording-fn nil)
                        invoke/invoke-rag
                        (fn [args]
                          (reset! captured-invoke args)
                          {:status :complete :response "ok" :chunks []
                           :queries [] :search-attribution {}
                           :diagnostics {} :raw-result {} :error nil})]
            ;; API key carries a scope — should be picked when agent has none.
            (let [{:keys [result error]}
                  (mcp-tools/invoke-tool
                    {:api-key/agent-refs ["builtin/rag-agent"]
                     :api-key/dataset-scopes [{:tenant "altinn-docs"
                                               :dataset-config-key "dev"}]
                     :api-key/skill-graphs []
                     :api-key/client-id "x"}
                    "builtin.rag-agent__agent-rag-graph-bundled"
                    {"query" "Hello"}
                    nil)]
              (is (nil? error) "Should not error when API key has a scope")
              (is (= "altinn-docs" (get-in @captured-invoke [:execution-scope :tenant])))
              (is (= "dev" (get-in @captured-invoke [:execution-scope :dataset-config-key])))
              (is (false? (:isError result))))))))))

(deftest invoke-tool-accepts-explicit-scope-args-within-the-keys-grant
  ;; ⚠️ THIS TEST ASSERTED THE OPPOSITE UNTIL #464, AND THE CHANGE IS THE POINT
  ;; OF THAT ISSUE — flagged here rather than quietly rewritten.
  ;;
  ;; It was named `…-on-unscoped-agent` and asserted that explicit tool
  ;; arguments "override ANYTHING ELSE when the agent has no scope
  ;; restriction". Its fixture granted the key `key-tenant/key-dck` and passed
  ;; `explicit-tenant/explicit-dck`, then asserted no error — i.e. it pinned a
  ;; caller-supplied argument overriding the caller's OWN authorization grant.
  ;;
  ;; That is the defect, not a requirement: the REST path refuses the same
  ;; request (403, `select-request-dataset-ref!`), and `pick-dataset-scope`'s
  ;; own agent-declared branch already filtered by the key's grant — so the
  ;; unrestricted branch disagreed with its own sibling. All three shipped
  ;; agents declare no scopes, so it was the default path.
  ;;
  ;; What is KEPT is the legitimate half: a key granted several datasets still
  ;; selects among them per call. Only naming a dataset the key was never
  ;; granted is refused.
  (testing "explicit tenant/dataset_config_key select WITHIN the key's grant"
    (let [unscoped-agent (assoc rag-agent :allowed-dataset-scopes [])
          captured-invoke (atom nil)]
      (with-stubs [unscoped-agent]
        (fn []
          (with-redefs [config-core/get-master-key (fn [] "k")
                        config-db/get-dataset-by-ref
                        (fn [_ _ _] {:docs-collection "d" :chunks-collection "c"
                                     :phrases-collection "p"})
                        api-util/build-rag-skill-params (tu/recording-fn {})
                        data-db/get-conn (fn [] (atom :fake))
                        data-db/create-playground-conversation
                        (fn [_ _ _] {:conversation-id "convo-2"})
                        data-db/fetch-conversation-tree (fn [_ _] [])
                        data-db/transact-playground-user-msg (fn [& _] nil)
                        data-db/transact-assistant-msg (tu/recording-fn nil)
                        invoke/invoke-rag
                        (fn [args]
                          (reset! captured-invoke args)
                          {:status :complete :response "ok" :chunks []
                           :queries [] :search-attribution {}
                           :diagnostics {} :raw-result {} :error nil})]
            (let [r (mcp-tools/invoke-tool
                      {:api-key/agent-refs ["builtin/rag-agent"]
                       :api-key/dataset-scopes [{:tenant "key-tenant"
                                                 :dataset-config-key "key-dck"}
                                                {:tenant "key-tenant"
                                                 :dataset-config-key "second-dck"}]
                       :api-key/skill-graphs []
                       :api-key/client-id "x"}
                      "builtin.rag-agent__agent-rag-graph-bundled"
                      {"query" "Hello"
                       "tenant" "key-tenant"
                       "dataset_config_key" "second-dck"}
                      nil)]
              (is (nil? (:error r))
                  "a dataset the key WAS granted must still be selectable per call")
              (is (= "key-tenant" (get-in @captured-invoke [:execution-scope :tenant])))
              (is (= "second-dck" (get-in @captured-invoke [:execution-scope :dataset-config-key]))))

            (testing "and a dataset the key was NOT granted is refused"
              (reset! captured-invoke nil)
              (let [r (mcp-tools/invoke-tool
                        {:api-key/agent-refs ["builtin/rag-agent"]
                         :api-key/dataset-scopes [{:tenant "key-tenant"
                                                   :dataset-config-key "key-dck"}]
                         :api-key/skill-graphs []
                         :api-key/client-id "x"}
                        "builtin.rag-agent__agent-rag-graph-bundled"
                        {"query" "Hello"
                         "tenant" "explicit-tenant"
                         "dataset_config_key" "explicit-dck"}
                        nil)]
                (is (= "dataset_not_authorized" (get-in r [:error :code]))
                    "the key's grant is the floor; a tool argument cannot widen it")
                (is (nil? @captured-invoke)
                    "and the agent must not run at all against an unauthorized dataset")))))))))

(deftest invoke-tool-reports-agent-failure-as-iserror
  (testing "An agent that could not reach its model returns isError:true with
            the failure text as content (#303)"
    ;; The direction that matters: a failure reported as a success is the one
    ;; that makes a caller trust a broken install, and the one that makes any
    ;; harness scoring us count a dead call as a pass. The message still has
    ;; to reach the model — that is the isError channel's whole point (#117) —
    ;; so content carries it rather than being blanked.
    (with-stubs [docs-agent]
      (fn []
        (with-redefs [config-core/get-master-key (fn [] "master-key")
                      config-db/get-dataset-by-ref (fn [_ _ _]
                                                     {:docs-collection "docs"
                                                      :chunks-collection "chunks"
                                                      :phrases-collection "phrases"})
                      api-util/build-rag-skill-params (fn [& _] {:skill-params :stub})
                      data-db/get-conn (fn [] (atom :fake-data-conn))
                      data-db/create-playground-conversation
                      (fn [_ _ _] {:conversation-id "convo-err"})
                      data-db/fetch-conversation-tree (fn [_ _] [])
                      data-db/transact-playground-user-msg (fn [_ _ _ _ _ _] nil)
                      data-db/transact-assistant-msg (fn [_ _ _ _] nil)
                      invoke/invoke-rag
                      (fn [_]
                        {:status :error
                         :response "LLM request failed at iteration 0: Tag mismatch"
                         :insufficient? false
                         :clarification nil
                         :chunks []
                         :queries []
                         :search-attribution {}
                         :diagnostics {}
                         :raw-result {}
                         :error {:error-type :agent-terminal-error
                                 :error-message "LLM request failed at iteration 0: Tag mismatch"}})]
          (let [{:keys [result error]}
                (mcp-tools/invoke-tool
                  {:api-key/agent-refs ["builtin/docs-agent"]
                   :api-key/dataset-scopes [{:tenant "altinn-docs"
                                             :dataset-config-key "dev"}]
                   :api-key/skill-graphs []
                   :api-key/client-id "test-client"}
                  "builtin.docs-agent__self-improve-graph"
                  {"query" "Hva er Digdir?"}
                  nil)]
            ;; A tool result, not a JSON-RPC error: the call was dispatched
            ;; fine, the agent is what failed.
            (is (nil? error))
            (is (true? (:isError result)))
            (is (= [{:type "text" :text "LLM request failed at iteration 0: Tag mismatch"}]
                   (:content result)))
            (is (= "error" (get-in result [:_meta :status])))))))))

(deftest invoke-tool-runs-invoke-rag
  (testing "End-to-end resolution + invocation, with persistence stubbed"
    (let [conversation-ids (atom [])
          user-msgs (atom [])
          assistant-msgs (atom [])
          captured-invoke (atom nil)
          ;; Recording rather than variadic: which arity MCP picks here is a
          ;; semantic choice — the 2-arity drops the agent's :skill-params —
          ;; and a `(fn [& _] …)` stub cannot see the difference. That is
          ;; exactly how #119 shipped on /v1 behind this green test.
          skill-params-stub (tu/recording-fn {:skill-params :stub})]
      (with-stubs [docs-agent]
        (fn []
          (with-redefs [config-core/get-master-key (fn [] "master-key")
                        config-db/get-dataset-by-ref (fn [_ _ _]
                                                       {:docs-collection "docs"
                                                        :chunks-collection "chunks"
                                                        :phrases-collection "phrases"})
                        api-util/build-rag-skill-params skill-params-stub
                        data-db/get-conn (fn [] (atom :fake-data-conn))
                        data-db/create-playground-conversation
                        (fn [_ agent-id _opts]
                          (let [id (str "convo-" (count @conversation-ids))]
                            (swap! conversation-ids conj {:agent agent-id :id id})
                            {:conversation-id id}))
                        data-db/fetch-conversation-tree (fn [_ _] [])
                        data-db/transact-playground-user-msg
                        (fn [_ convo-id text _ _ _]
                          (swap! user-msgs conj {:convo convo-id :text text}))
                        data-db/transact-assistant-msg
                        (fn [_ convo-id text _]
                          (swap! assistant-msgs conj {:convo convo-id :text text}))
                        invoke/invoke-rag
                        (fn [args]
                          (reset! captured-invoke args)
                          {:status :complete
                           :response "An answer."
                           :insufficient? false
                           :clarification nil
                           :chunks [{:chunk_id "c1"}]
                           :queries ["q1"]
                           :search-attribution {:phrase 1}
                           :diagnostics {:execution-metadata {:total-duration-ms 7}}
                           :raw-result {}
                           :error nil})]
            (let [{:keys [result error]}
                  (mcp-tools/invoke-tool
                    {:api-key/agent-refs ["builtin/docs-agent"]
                     :api-key/dataset-scopes [{:tenant "altinn-docs"
                                               :dataset-config-key "dev"}]
                     :api-key/skill-graphs []
                     :api-key/client-id "test-client"}
                    "builtin.docs-agent__self-improve-graph"
                    {"query" "What changed?"}
                    nil)]
              (is (nil? error))
              (is (false? (:isError result)))
              (is (= [{:type "text" :text "An answer."}] (:content result)))
              (is (= "complete" (get-in result [:_meta :status])))
              (is (= "builtin/docs-agent" (get-in result [:_meta :agent_id])))
              (is (= "convo-0" (get-in result [:_meta :conversation_id])))
              ;; Conversation created, user + assistant turns persisted.
              (is (= 1 (count @conversation-ids)))
              (is (= 1 (count @user-msgs)))
              (is (= 1 (count @assistant-msgs)))
              ;; invoke-rag got the right scope and skill graph.
              (is (= :docs/self-improve-graph (:skill-graph-id @captured-invoke)))
              (is (= {:tenant "altinn-docs"
                      :dataset-config-key "dev"
                      :agent-id "builtin/docs-agent"}
                     (:execution-scope @captured-invoke)))
              ;; Arity, not just result: MCP must thread the agent's
              ;; :skill-params, and this fails if a caller silently switches
              ;; to the 2-arity the way /v1 had (#119, #130).
              (is (= [3] (tu/arities skill-params-stub))
                  "MCP must call the 3-arity build-rag-skill-params"))))))))

;; =============================================================================
;; Public identifiers (#122)
;;
;; One string is the MCP tool name, the OpenAI model id, and - via MCPO - an
;; OpenAPI path segment. These pin the two properties it has to keep and the
;; guard the MCP plan promised and never built.
;; =============================================================================

(deftest public-identifier-carries-no-slash-and-no-skill-graph
  (with-stubs [docs-agent rag-agent]
    (fn []
      (let [names (mapv :name (mcp-tools/list-tools {}))]
        (is (seq names))
        (doseq [n names]
          (is (not (str/includes? n "/"))
              (str n " must not carry a slash - it becomes a URL path segment"))
          (is (not (str/includes? n "skill-graph"))
              (str n " must not carry the word skill-graph on the wire"))
          (is (str/includes? n "__")))))))

(deftest public-identifier-round-trips-to-the-real-agent-id
  (testing "the wire form reverses to the slash-carrying id callers resolve with"
    (is (= "builtin.docs-agent" (mcp-tools/agent-id->wire "builtin/docs-agent")))
    (is (= "builtin/docs-agent" (mcp-tools/wire->agent-id "builtin.docs-agent")))
    (testing "only the first separator is reversed, so a dotted name survives"
      (is (= "builtin/docs.agent.v2"
             (mcp-tools/wire->agent-id (mcp-tools/agent-id->wire "builtin/docs.agent.v2")))))
    (with-stubs [docs-agent rag-agent]
      (fn []
        (doseq [tool (mcp-tools/list-tools {})]
          (let [[agent-id _mode] (mcp-tools/parse-tool-name (:name tool))]
            (is (= (get-in tool [:_meta :agent-id]) agent-id)
                "a name that cannot be parsed back would 404 every call")))))))

(deftest tool-names-are-unique-across-enabled-agents
  (testing "the guard the MCP plan promised and never built"
    (with-stubs [docs-agent rag-agent]
      (fn []
        (let [report (mcp-tools/tool-name-report [docs-agent rag-agent])]
          (is (:ok report))
          (is (empty? (:collisions report)))
          (is (empty? (:round-trip-failures report)))
          (is (pos? (:checked report)) "must actually have checked some pairs"))))))

(deftest uniqueness-guard-fails-on-a-deliberate-collision
  (testing "two agents whose ids differ only by the separator collide on the wire"
    ;; builtin/docs-agent and builtin.docs-agent both encode to
    ;; builtin.docs-agent, so they produce the same public identifier and one
    ;; would silently shadow the other on both MCP and /v1. This is the case
    ;; the guard exists to catch.
    (let [twin (assoc docs-agent :id "builtin.docs-agent")
          report (mcp-tools/tool-name-report [docs-agent twin])]
      (is (false? (:ok report)))
      (is (seq (:collisions report)))
      (is (= #{"builtin/docs-agent" "builtin.docs-agent"}
             (set (map :agent-id (:produced-by (first (:collisions report))))))
          "the report must name both agents, not just say a collision happened"))))

;; =============================================================================
;; The two authoritative surfaces agree (#159)
;;
;; server-instructions told clients to send `query`; the advertised inputSchema
;; declared and required `user-query`. The server accepted both, so nothing
;; failed - a strict schema-validating client following our own instructions
;; would have sent an undeclared property.
;;
;; This is the fifth instance in a day of metadata that is authoritative-looking
;; and validated by nothing. The assertion is the point: it is the only thing
;; that can catch the class.
;; =============================================================================

(deftest instructions-and-input-schema-name-the-same-argument
  (with-stubs [docs-agent rag-agent]
    (fn []
      (let [tools (mcp-tools/list-tools {})]
        (is (seq tools))
        (testing "every argument a tool REQUIRES is named in the instructions"
          ;; The general property, not just a check for this one field: if we
          ;; require something, the description that tells a client how to call
          ;; us has to mention it.
          (doseq [tool tools
                  required (get-in tool [:inputSchema "required"])]
            (is (str/includes? mcp-tools/server-instructions required)
                (str (:name tool) " requires '" required
                     "' but server-instructions never mentions it"))))

        (testing "the advertised name is the one we tell clients to send"
          (doseq [tool tools]
            (is (contains? (get-in tool [:inputSchema "properties"])
                           mcp-tools/public-query-property)
                (str (:name tool) " must advertise the argument the instructions name"))
            (is (not (contains? (get-in tool [:inputSchema "properties"]) "user-query"))
                (str (:name tool) " must not advertise the internal name"))))))))

(deftest declared-schema-and-fallback-schema-advertise-the-same-name
  (testing "a graph that declares its own input schema advertises `query` too"
    ;; The shared graph schema declares :user-query, so normalising only the
    ;; fallback would have left tools disagreeing with each other depending on
    ;; whether their graph declared a schema.
    (with-stubs [docs-agent]
      (fn []
        (let [declared (first (mcp-tools/list-tools {}))]
          (is (contains? (get-in declared [:inputSchema "properties"]) "query"))
          (is (= ["query"] (get-in declared [:inputSchema "required"]))))))))

(deftest query-aliases-are-accepted-and-documented
  (testing "both names still work, and the alias is documented where it is met"
    (is (= "hei" (mcp-tools/read-query-argument {"query" "hei"})))
    (is (= "hei" (mcp-tools/read-query-argument {:query "hei"})))
    (is (= "hei" (mcp-tools/read-query-argument {"user-query" "hei"})))
    (is (= "hei" (mcp-tools/read-query-argument {:user-query "hei"})))
    (is (nil? (mcp-tools/read-query-argument {"query" "   "}))
        "blank is not an answer")
    (is (= "primary" (mcp-tools/read-query-argument {"query" "primary" "user-query" "alias"}))
        "query wins when both are present")
    (with-stubs [docs-agent]
      (fn []
        (let [tool (first (mcp-tools/list-tools {}))
              description (get-in tool [:inputSchema "properties" "query" "description"])]
          (is (str/includes? description "user-query")
              "the accepted alias must be documented where the reader meets it"))))))

(defn- invoke-with-arguments
  "Run invoke-tool against docs-agent with persistence stubbed; report what reached the params builder."
  [arguments & [rag-result]]
  (let [params-seen (atom ::not-called)
        invoked? (atom false)]
    (with-stubs [docs-agent]
      (fn []
        (with-redefs [config-core/get-master-key (fn [] "master-key")
                      config-db/get-dataset-by-ref (fn [_ _ _] {:docs-collection "docs"})
                      api-util/build-rag-skill-params (fn [_ params _] (reset! params-seen params) {})
                      data-db/get-conn (fn [] (atom :fake-data-conn))
                      data-db/create-playground-conversation (fn [_ _ _] {:conversation-id "c"})
                      data-db/fetch-conversation-tree (fn [_ _] [])
                      data-db/transact-playground-user-msg (fn [& _] nil)
                      data-db/transact-assistant-msg (fn [& _] nil)
                      invoke/invoke-rag (fn [_]
                                          (reset! invoked? true)
                                          (merge {:status :complete :response "ok" :chunks []}
                                                 rag-result))]
          (assoc (mcp-tools/invoke-tool
                  {:api-key/agent-refs ["builtin/docs-agent"]
                   :api-key/dataset-scopes [{:tenant "altinn-docs" :dataset-config-key "dev"}]}
                  "builtin.docs-agent__self-improve-graph"
                  (merge {"query" "q"} arguments)
                  nil)
                 :params @params-seen
                 :invoked? @invoked?))))))

(deftest overrides-arrive-with-keyword-keys
  (testing "JSON overrides reach the params builder in the keyword shape it reads"
    (is (= {:retrieve-top-k 7
            :retrieve-filter-by {:fields [{:field "type" :selected-options ["Evaluering"]}]}}
           (:params (invoke-with-arguments
                     {"overrides" {"retrieve-top-k" 7
                                   "retrieve-filter-by" {"fields" [{"field" "type"
                                                                    "selected-options" ["Evaluering"]}]}}}))))))

(deftest invalid-overrides-are-refused-before-anything-runs
  (doseq [[label overrides]
          {"overrides that are not an object" "retrieve-top-k=7"
           "a filter value that breaks out of its quoting" {"retrieve-filter-by"
                                                           {"fields" [{"field" "type"
                                                                       "selected-options" ["x`] || type:=[`y"]}]}}
           "a field name that is not an identifier" {"retrieve-filter-by"
                                                     {"fields" [{"field" "type:=[`x`] || title"
                                                                 "selected-options" ["y"]}]}}
           "an auto-filter switch that is not a boolean" {"retrieve-auto-filter" "false"}
           "an integer filter carrying text" {"retrieve-filter-by"
                                              {"fields" [{"field" "year" "value-type" "integer"
                                                          "selected-options" ["2020] || type:=[x"]}]}}}]
    (testing label
      (let [{:keys [error invoked?]} (invoke-with-arguments {"overrides" overrides})]
        (is (= "invalid_overrides" (:code error)))
        (is (= :invalid-params (mcp-tools/error-channel error)))
        (is (false? invoked?))))))

(deftest structured-content-reports-the-filters-retrieval-applied
  (let [caller {:fields [{:field "type" :selected-options ["Evaluering"]}]}
        detected {:fields [{:field "orgs_long" :selected-options ["Digitaliseringsdirektoratet"]}]}
        merged {:fields (into (:fields caller) (:fields detected))}
        applied (fn [rag-result]
                  (get-in (invoke-with-arguments {} rag-result) [:result :structuredContent :filters_applied]))]
    (testing "Each search's filter is listed once, with its source and the detected part"
      (is (= [{:filter merged :source "merged" :auto_detected detected}
              {:filter caller :source "explicit"}]
             (applied {:search-attribution {:filter-applied merged :filter-source :merged
                                            :auto-filter-applied detected}
                       :diagnostics {:search-attributions
                                     [{:filter-applied merged :filter-source :merged
                                       :auto-filter-applied detected}
                                      {:filter-applied caller :filter-source :explicit}]}}))))

    (testing "A detected filter that found nothing is marked as dropped"
      (is (true? (:auto_detected_dropped
                  (first (applied {:search-attribution {:filter-applied merged :filter-source :merged
                                                        :auto-filter-applied detected
                                                        :auto-filter-fallback true}}))))))

    (testing "An unfiltered call reports no filters"
      (is (nil? (applied {:search-attribution {:phrase 3}}))))))


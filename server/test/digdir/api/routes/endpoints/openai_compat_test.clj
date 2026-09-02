(ns digdir.api.routes.endpoints.openai-compat-test
  "Cross-surface equality: the same agent, invoked through /api/mcp and
   through /v1/chat/completions with equivalent inputs, must resolve to the
   same :skill-params.

   `api-util/build-rag-skill-params` is deliberately NOT stubbed — it is the
   code under test. Only the layers beneath it (dataset config, persistence,
   invoke-rag) are, so the real four-layer merge runs on both paths."
  (:require [digdir.test-utils :as tu]
            [clojure.test :refer [deftest testing is]]
            [cheshire.core :as json]
            [digdir.agents.db :as agents-db]
            [digdir.api.routes.endpoints.openai-compat :as openai-compat]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.data.db :as data-db]
            [digdir.mcp.tools :as mcp-tools]
            [digdir.skills.api :as skills-api]
            [digdir.skills.invoke :as invoke]))

(def ^:private agent-skill-params
  "Per-agent tuning that must survive on BOTH surfaces. Distinctive values,
   so a dropped agent layer is visible rather than coincidentally equal to
   the dataset defaults."
  {:builtin/retrieval {:top-k 42 :max-per-document 7}
   :builtin/synthesis {:model "agent-tuned-model"}})

(def ^:private tuned-agent
  {:id "builtin/docs-agent"
   :name "Docs Agent"
   :description "Curates docs."
   :default-skill-graph :docs/self-improve-graph
   :allowed-skill-graphs [:docs/self-improve-graph]
   :allowed-dataset-scopes [{:tenant "altinn-docs" :dataset-config-key "dev"}]
   :skill-params agent-skill-params
   :enabled? true})

(def ^:private principal
  {:api-key/agent-refs ["builtin/docs-agent"]
   :api-key/dataset-scopes [{:tenant "altinn-docs" :dataset-config-key "dev"}]
   :api-key/skill-graphs []
   :api-key/client-id "test-client"})

(def ^:private model-name "builtin.docs-agent__self-improve-graph")

(defn- with-surface-stubs
  "Stub everything beneath the resolution chain, on both surfaces at once."
  [!captured f]
  (with-redefs [config-db/get-conn (fn [] (atom :fake-config-conn))
                config-core/get-master-key (fn [] "master-key")
                agents-db/get-agent (fn [_ agent-id]
                                      (when (= agent-id (:id tuned-agent)) tuned-agent))
                agents-db/list-enabled-agents (fn [_] [tuned-agent])
                skills-api/initialize! (fn [] nil)
                skills-api/get-skill-graph-info (fn [graph-id]
                                                  {:id graph-id
                                                   :name (name graph-id)
                                                   :description "stub"})
                config-db/get-dataset-by-ref (fn [_ _ _]
                                               {:docs-collection "docs"
                                                :chunks-collection "chunks"
                                                :phrases-collection "phrases"
                                                :retrieval-top-k 100})
                ;; MCP persists conversations; /v1 does not. Stubbed so the
                ;; two paths are comparable.
                data-db/get-conn (fn [] (atom :fake-data-conn))
                data-db/create-playground-conversation (fn [_ _ _] {:conversation-id "convo-0"})
                data-db/fetch-conversation-tree (fn [_ _] [])
                data-db/transact-playground-user-msg (fn [& _] nil)
                data-db/transact-assistant-msg (tu/recording-fn nil)
                invoke/invoke-rag (fn [args]
                                    (swap! !captured conj (:skill-params args))
                                    {:status :complete
                                     :response "An answer."
                                     :insufficient? false
                                     :chunks []
                                     :queries []
                                     :diagnostics {}
                                     :raw-result {}
                                     :error nil})]
    (f)))

(defn- skill-params-via-mcp []
  (let [!captured (atom [])]
    (with-surface-stubs !captured
      (fn []
        (mcp-tools/invoke-tool principal model-name {"query" "What changed?"} nil)))
    (first @!captured)))

(defn- skill-params-via-openai [& [extra-body]]
  (let [!captured (atom [])]
    (with-surface-stubs !captured
      (fn []
        (openai-compat/chat-completions-handler
         (merge principal
                {:body (json/generate-string
                        (merge {:model model-name
                                :messages [{:role "user" :content "What changed?"}]}
                               extra-body))}))))
    (first @!captured)))

(deftest same-agent-resolves-to-the-same-skill-params-on-both-surfaces
  (testing "both surfaces reach invoke-rag at all"
    (is (some? (skill-params-via-mcp)) "MCP path did not invoke the agent")
    (is (some? (skill-params-via-openai)) "/v1 path did not invoke the agent"))

  (testing "the agent's own tuning survives on the MCP surface"
    (let [mcp-params (skill-params-via-mcp)]
      (is (= 42 (get-in mcp-params [:builtin/retrieval :top-k])))
      (is (= "agent-tuned-model" (get-in mcp-params [:builtin/synthesis :model])))))

  (testing "…and identically on the OpenAI-compatible surface"
    (let [openai-params (skill-params-via-openai)]
      (is (= 42 (get-in openai-params [:builtin/retrieval :top-k]))
          "agent :skill-params were dropped on /v1")
      (is (= "agent-tuned-model" (get-in openai-params [:builtin/synthesis :model])))))

  (testing "the two surfaces agree exactly"
    (is (= (skill-params-via-mcp) (skill-params-via-openai))
        "same agent, equivalent inputs, different resolved skill-params")))

(deftest dataset-scope-is-accepted-from-the-v1-request
  (testing "an explicit tenant/dataset_config_key in the body selects that scope"
    (let [!scopes (atom [])]
      (with-redefs [mcp-tools/pick-dataset-scope
                    (fn [_agent _principal arguments]
                      (swap! !scopes conj arguments)
                      {:scope {:tenant "altinn-docs" :dataset-config-key "dev"}})]
        (skill-params-via-openai {:tenant "altinn-docs"
                                  :dataset_config_key "dev"}))
      (is (= 1 (count @!scopes)))
      (is (= "altinn-docs" (or (get (first @!scopes) "tenant")
                               (get (first @!scopes) :tenant)))
          "/v1 discarded the request's dataset scope")
      (is (= "dev" (or (get (first @!scopes) "dataset_config_key")
                       (get (first @!scopes) :dataset_config_key)))))))

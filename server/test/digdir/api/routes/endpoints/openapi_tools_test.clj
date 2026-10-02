(ns digdir.api.routes.endpoints.openapi-tools-test
  "The OpenAPI tool surface exists so Open WebUI can tool-call our agents
   without MCPO. That client is not in this test, so these assertions pin the
   things it depends on and would fail SILENTLY on — an empty tool list, a
   renamed operation, a dropped argument — rather than the things that would
   throw.

   Assertions run against the DECODED JSON body, not the handler's Clojure
   map: `{:answer nil}` and `{}` are the same map lookup and different bytes."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [digdir.agents.db :as agents-db]
            [digdir.api.routes.endpoints.openapi-tools :as sut]
            [digdir.mcp.tools :as mcp-tools]
            [digdir.skills.api :as skills-api]))

(def ^:private test-agent
  {:id "builtin/docs-agent"
   :name "Docs Agent"
   :description "Answers questions about the docs"
   :default-skill-graph :docs/self-improve-graph
   :allowed-skill-graphs [:docs/self-improve-graph]
   :allowed-dataset-scopes [{:tenant "altinn-docs" :dataset-config-key "dev"}]
   :enabled? true})

(def ^:private principal
  {:api-key/agent-refs ["builtin/docs-agent"]
   :api-key/dataset-scopes [{:tenant "altinn-docs" :dataset-config-key "dev"}]
   :api-key/skill-graphs []
   :headers {"host" "rag.example.test"}
   :scheme :https})

(def ^:private tool-name "builtin.docs-agent__self-improve-graph")

(defn- with-stubs [f]
  (with-redefs [skills-api/initialize! (fn [] nil)
                skills-api/get-skill-graph-info (fn [g] {:id g
                                                         :name (name g)
                                                         :description "stub graph"})
                agents-db/list-enabled-agents (fn [_] [test-agent])
                agents-db/get-agent (fn [_ id] (when (= id (:id test-agent)) test-agent))]
    (f)))

;; ---------------------------------------------------------------------------
;; The spec
;; ---------------------------------------------------------------------------

(defn- spec-of
  "Decode the spec with STRING keys. Keyword keys are not merely awkward here,
   they are wrong: cheshire turns the path \"/api/tools/x\" into `:/api/tools/x`,
   whose `name` is \"api/tools/x\" — the leading slash is read as a namespace
   separator and silently disappears, which is exactly the character these
   assertions exist to protect."
  [principal]
  (json/parse-string (:body (sut/openapi-spec-handler principal))))

(deftest spec-advertises-one-operation-per-tool
  (with-stubs
    (fn []
      (let [spec (spec-of principal)
            paths (get spec "paths")]
        (testing "every tool list-tools returns becomes exactly one path"
          (is (= (count (mcp-tools/list-tools principal)) (count paths)))
          (is (pos? (count paths))
              "an empty spec is what a broken tool list looks like to Open WebUI"))
        (testing "paths are ABSOLUTE from the server root"
          ;; Open WebUI executes a tool as `connection-url + route-path`
          ;; (execute_tool_server). A relative path here produces a URL with no
          ;; /api/tools segment, and every call 404s.
          (is (every? #(str/starts-with? % "/api/tools/")
                      (keys paths))))
        (testing "openapi version is one Open WebUI's parser accepts"
          (is (= "3.1.0" (get spec "openapi"))))))))

(deftest operation-id-is-the-tool-name
  ;; Open WebUI hands `operationId` to the model as the function name AND
  ;; matches the model's call back to a route by scanning for it. If this
  ;; drifts from the MCP tool name, the same agent has two different names
  ;; depending on which surface you reached it through.
  (with-stubs
    (fn []
      (let [spec (spec-of principal)]
        (doseq [[path item] (get spec "paths")]
          (let [op-id (get-in item ["post" "operationId"])]
            (is (= (sut/tool-path op-id) path)
                "the path and the operationId must name the same tool")
            (is (some? (mcp-tools/parse-tool-name op-id))
                (str op-id " does not parse back to an (agent, mode) pair"))))))))

(deftest spec-carries-the-mcp-input-schema-verbatim
  (with-stubs
    (fn []
      (let [tools (mcp-tools/list-tools principal)
            spec (spec-of principal)
            tool (first tools)
            schema (get-in spec ["paths" (sut/tool-path (:name tool))
                                 "post" "requestBody" "content"
                                 "application/json" "schema"])]
        (testing "the advertised arguments are the MCP ones, not a second copy"
          (is (some? schema))
          (is (= (json/parse-string (json/generate-string (:inputSchema tool)))
                 schema)))
        (testing "the protocol arguments a model needs for multi-turn are advertised"
          ;; #116: a model only emits fields the schema mentions.
          (is (contains? (get schema "properties") "conversation_id")))))))

(deftest spec-is-filtered-per-api-key
  (with-stubs
    (fn []
      (let [visible (spec-of principal)
            ;; A key granted a DIFFERENT agent must see none of this one's tools.
            blind (spec-of (assoc principal :api-key/agent-refs ["builtin/other-agent"]))]
        (is (pos? (count (get visible "paths"))))
        (is (zero? (count (get blind "paths")))
            "the spec is a per-key surface; leaking another key's tools here
             would leak them to the model that reads it")))))

;; ---------------------------------------------------------------------------
;; Arguments
;; ---------------------------------------------------------------------------

(deftest arguments-keep-their-wire-names
  ;; The regression this guards: the shared body reader kebab-cases keys, so
  ;; `conversation_id` becomes `:conversation-id` and `invoke-tool` — which
  ;; looks for "conversation_id"/:conversation_id — silently sees no handle.
  ;; Multi-turn then fails by quietly starting a new conversation every turn,
  ;; with no error anywhere.
  (let [args (sut/read-arguments
               {:body (json/generate-string {:query "hei"
                                             :conversation_id "c-123"
                                             :dataset_config_key "dev"
                                             :tenant "altinn-docs"})})]
    (is (= "c-123" (get args "conversation_id")))
    (is (= "dev" (get args "dataset_config_key")))
    (is (= "hei" (mcp-tools/read-query-argument args))
        "invoke-tool must be able to find the question in what we parsed")))

(deftest empty-body-is-empty-arguments
  (is (= {} (sut/read-arguments {:body nil})))
  (is (= {} (sut/read-arguments {:body ""}))))

;; ---------------------------------------------------------------------------
;; Results and the error split
;; ---------------------------------------------------------------------------

(deftest result-body-leads-with-the-answer
  (let [body (sut/tool-result->body
               {:content [{:type "text" :text "Altinn er en plattform."}
                          {:type "resource_link" :uri "https://x/1" :name "Doc 1"}]
                :structuredContent {:conversation_id "c-1"
                                    :chunks [{:chunk_id "a"}]
                                    :queries ["altinn"]}
                :isError false
                :_meta {:conversation_id "c-1"}})]
    (is (= "Altinn er en plattform." (:answer body)))
    (is (= "ok" (:status body)))
    (is (= "c-1" (:conversation_id body)))
    (is (= [{:uri "https://x/1" :name "Doc 1"}] (:sources body)))
    (is (= ["altinn"] (:queries body)))))

(deftest dispatch-errors-are-http-errors
  ;; A model cannot retry its way out of "no such agent", so it belongs in the
  ;; status line where a client's error handling can see it.
  (doseq [[code expected-status] {"agent_not_found" 404
                                  "invalid_tool_name" 404
                                  "agent_not_authorized" 403}]
    (with-redefs [mcp-tools/invoke-tool (fn [_ _ _ _] {:error {:code code :message "nope"}})]
      (let [resp (sut/tool-call-handler {:path-params {:tool-name tool-name} :body ""})]
        (is (= expected-status (:status resp)) (str code " must map to " expected-status))))))

(deftest recoverable-errors-come-back-as-a-readable-result
  ;; ⚠️ The load-bearing choice in this namespace. Open WebUI turns any status
  ;; >= 400 into an opaque exception string (execute_tool_server), so a 4xx
  ;; here would bury the single most actionable message in the codebase.
  ;; /api/mcp delivers these as `isError: true` results for the same reason.
  (doseq [code ["no_dataset_scope" "missing_query"]]
    (with-redefs [mcp-tools/invoke-tool
                  (fn [_ _ _ _] {:error {:code code :message "pass `tenant` and `dataset_config_key`"}})]
      (let [resp (sut/tool-call-handler {:path-params {:tool-name tool-name} :body ""})
            body (json/parse-string (:body resp) true)]
        (is (= 200 (:status resp))
            (str code " must not be a 4xx — the model needs to read the message"))
        (is (= "error" (:status body)))
        (is (= "pass `tenant` and `dataset_config_key`" (:answer body))
            "the actionable message has to be where a model reads it")))))

(deftest an-exception-is-a-500-not-a-crash
  (with-redefs [mcp-tools/invoke-tool (fn [_ _ _ _] (throw (ex-info "boom" {})))]
    (let [resp (sut/tool-call-handler {:path-params {:tool-name tool-name} :body ""})]
      (is (= 500 (:status resp)))
      (is (= "boom" (get-in (json/parse-string (:body resp) true) [:error :message]))))))

(deftest an-oversized-body-is-a-413-not-a-read-into-memory
  (let [resp (sut/tool-call-handler {:path-params {:tool-name tool-name}
                                     :body (apply str (repeat (inc (* 1024 1024)) "x"))})]
    (is (= 413 (:status resp)))
    (is (= "body_too_large" (get-in (json/parse-string (:body resp) true) [:error :code])))))

(deftest result-body-carries-the-filters-retrieval-applied
  (let [applied [{:filter {:fields [{:field "type" :selected-options ["Evaluering"]}]}
                  :source "explicit"}]]
    (is (= applied (:filters_applied (sut/tool-result->body
                                      {:content [{:type "text" :text "Svar."}]
                                       :structuredContent {:filters_applied applied}}))))))

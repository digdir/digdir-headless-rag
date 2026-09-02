(ns digdir.mcp.transport-test
  (:require [cheshire.core :as json]
            [clojure.set]
            [clojure.test :refer [deftest testing is]]
            [digdir.agents.db :as agents-db]
            [digdir.api.util :as api-util]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.data.db :as data-db]
            [digdir.mcp.tools :as mcp-tools]
            [digdir.mcp.transport :as transport]
            [digdir.skills.api :as skills-api]
            [digdir.skills.builtin.agent.workspace :as workspace]
            [digdir.skills.invoke :as invoke]))

(def ^:private protocol-version "2026-07-28")

(defn- conformant-headers
  "Build the request-metadata headers 2026-07-28 requires, mirroring the body.

   Centralised so every test drives a *conformant* modern request by default;
   the validation tests below override individual headers to make each
   rejection path explicit."
  [body]
  (let [method (or (:method body) (get body "method"))
        params (or (:params body) (get body "params") {})
        nm     (or (:name params) (get params "name"))]
    (cond-> {"mcp-protocol-version" protocol-version
             "mcp-method" (str method)}
      nm (assoc "mcp-name" (str nm)))))

(defn- post
  ([body] (post body (conformant-headers body)))
  ([body headers]
   {:request-method :post
    :uri "/api/mcp"
    :headers headers
    :body (json/generate-string body)
    :api-key/id "k1"}))

(defn- response->json
  [response]
  (json/parse-string (:body response) keyword))

(deftest server-discover-reports-supported-versions-and-identity
  (let [r (transport/handle-mcp-request
            (post {:jsonrpc "2.0" :id 1 :method "server/discover" :params {}}))]
    (is (= 200 (:status r)))
    (let [body (response->json r)]
      (is (= "2.0" (:jsonrpc body)))
      (is (= 1 (:id body)))
      (is (= "complete" (get-in body [:result :resultType])))
      (is (= ["2026-07-28"] (get-in body [:result :supportedVersions])))
      (is (map? (get-in body [:result :capabilities :tools])))
      (is (= "digdir-rag"
             (get-in body [:result :_meta
                           (keyword "io.modelcontextprotocol/serverInfo")
                           :name]))))))

(deftest notification-returns-202-and-no-body
  ;; This revision defines no client-to-server notifications over Streamable
  ;; HTTP; 202 is the politeness path for a client that sends one anyway.
  (let [r (transport/handle-mcp-request
            (post {:jsonrpc "2.0" :method "notifications/initialized"}))]
    (is (= 202 (:status r)))
    (is (= "" (:body r)))))

(deftest unknown-method-returns-404-not-200
  ;; The repair of #139. A JSON-RPC error inside a NON-2XX response is what
  ;; lets a dual-era client distinguish a modern server from a legacy one.
  ;; Returning 200 here is precisely what made our old fallback behaviour
  ;; depend on client leniency rather than on our conformance.
  (let [r (transport/handle-mcp-request
            (post {:jsonrpc "2.0" :id 5 :method "unknown/method"}))
        body (response->json r)]
    (is (= 404 (:status r)))
    (is (= -32601 (get-in body [:error :code])))
    (is (= "unknown/method" (get-in body [:error :data :method])))))

(deftest legacy-initialize-is-refused-but-told-what-we-speak
  ;; The risk inversion of #146, asserted rather than described: a legacy-only
  ;; client opens with `initialize` and this server no longer answers it.
  ;;
  ;; It is refused with -32022 and the supported-version list, NOT with a bare
  ;; "missing header". A legacy client has no fall-forward mechanism, so this
  ;; error may be the only diagnostic its user ever sees; the spec asks a
  ;; modern-only server to name its versions here for exactly that reason.
  ;; Verified against MCP Inspector 2.3.0, which opens this way.
  (let [r (transport/handle-mcp-request
            (post {:jsonrpc "2.0" :id 9 :method "initialize"
                   :params {:protocolVersion "2025-03-26"}}))
        body (response->json r)]
    (is (= 400 (:status r)))
    (is (= -32022 (get-in body [:error :code])))
    (is (= ["2026-07-28"] (get-in body [:error :data :supported])))
    (is (re-find #"2026-07-28" (get-in body [:error :message]))
        "the message itself must name the version, not just the data field")))

(deftest cacheable-results-carry-the-required-hints
  ;; "Servers MUST include caching hints on results with resultType complete"
  ;; for server/discover and tools/list. Omitting them is not cosmetic:
  ;; Claude Code 2.1.238 rejected tools/list outright, retried four times and
  ;; reported the server as having no tools at all.
  (testing "server/discover — public, because identity is identical per caller"
    (let [body (response->json
                 (transport/handle-mcp-request
                   (post {:jsonrpc "2.0" :id 1 :method "server/discover" :params {}})))]
      (is (number? (get-in body [:result :ttlMs])))
      (is (<= 0 (get-in body [:result :ttlMs])))
      (is (= "public" (get-in body [:result :cacheScope])))))
  (testing "tools/list — PRIVATE, because the list is filtered per API key"
    ;; A "public" scope here would let a cache serve one key's tool list to the
    ;; holder of another; the spec warns that public results may be shared
    ;; across authorization contexts even from an authenticated endpoint.
    (with-redefs [mcp-tools/list-tools-response
                  (fn [_] {:tools [] :_meta {:request-id "r"}})]
      (let [body (response->json
                   (transport/handle-mcp-request
                     (post {:jsonrpc "2.0" :id 2 :method "tools/list"})))]
        (is (number? (get-in body [:result :ttlMs])))
        (is (= "private" (get-in body [:result :cacheScope])))))))

(deftest tools-list-bridges-to-tools-namespace
  (with-redefs [mcp-tools/list-tools-response
                (fn [_] {:tools [{:name "t1" :description "d" :inputSchema {}}]
                         :_meta {:request-id "r"}})]
    (let [r (transport/handle-mcp-request
              (post {:jsonrpc "2.0" :id 2 :method "tools/list"}))
          body (response->json r)]
      (is (= 200 (:status r)))
      (is (= 1 (count (get-in body [:result :tools])))))))

(deftest tools-call-returns-result-payload
  (with-redefs [mcp-tools/invoke-tool
                (fn [_ _ _ _]
                  {:result {:content [{:type "text" :text "ok"}]
                            :isError false
                            :_meta {:conversation_id "convo-1"}}})]
    (let [r (transport/handle-mcp-request
              (post {:jsonrpc "2.0" :id 3 :method "tools/call"
                     :params {:name "agent__graph"
                              :arguments {:query "hi"}}}))
          body (response->json r)]
      (is (= 200 (:status r)))
      (is (= "convo-1" (get-in body [:result :_meta :conversation_id]))))))

(deftest tools-call-without-name-is-rejected-at-the-transport-layer
  ;; Under 2026-07-28 a nameless `tools/call` can no longer reach the params
  ;; check: `Mcp-Name` is a required header for this method, so the request is
  ;; malformed at the transport layer first and comes back -32020 rather than
  ;; -32602. There is no way to express "conformant headers, missing body name"
  ;; — the header has to mirror the body, so omitting one omits both.
  (let [r (transport/handle-mcp-request
            (post {:jsonrpc "2.0" :id 4 :method "tools/call"
                   :params {}}))
        body (response->json r)]
    (is (= 400 (:status r)))
    (is (= -32020 (get-in body [:error :code]))))
  (testing "the params-level check still guards the direct dispatch path"
    ;; handle-tools-call's -32602 branch is now unreachable over HTTP but is
    ;; still the correct answer for a caller that bypasses header validation,
    ;; so it stays rather than being deleted as dead code.
    (let [r (transport/handle-mcp-request
              (post {:jsonrpc "2.0" :id 4 :method "tools/call"
                     :params {:name "t1"}}
                    {"mcp-protocol-version" "2026-07-28"
                     "mcp-method" "tools/call"
                     "mcp-name" "t1"}))]
      ;; name present and mirrored -> passes validation, reaches the tool layer
      (is (not= -32020 (get-in (response->json r) [:error :code]))))))

;; =============================================================================
;; Error channels (#117)
;;
;; MCP has two on purpose. `isError: true` sends the failure text BACK TO THE
;; MODEL so it can self-correct; JSON-RPC -32603 means the server broke, which a
;; well-behaved client shows to a human and does not retry. These assert on the
;; wire envelope, not on internals.
;; =============================================================================

(deftest blank-query-goes-back-to-the-model-not-out-as-server-broke
  (testing "an argument error is isError:true with the text intact, not -32603"
    (with-redefs [mcp-tools/invoke-tool
                  (fn [_ _ _ _]
                    ;; verbatim shape from mcp/tools.clj's missing_query branch
                    {:error {:code "missing_query"
                             :message "Tool call requires a non-empty :query argument."}})]
      (let [r (transport/handle-mcp-request
                (post {:jsonrpc "2.0" :id 7 :method "tools/call"
                       :params {:name "agent__graph" :arguments {}}}))
            body (response->json r)]
        (is (= 200 (:status r)))
        (is (nil? (:error body))
            "must not be a JSON-RPC error - the model never sees that envelope")
        (is (true? (get-in body [:result :isError])))
        (is (= "Tool call requires a non-empty :query argument."
               (-> body :result :content first :text))
            "the actionable text must reach the model")))))

(deftest scope-and-authorization-failures-go-back-to-the-model
  (testing "scope and authorization failures are isError:true, not -32603"
    (doseq [{:keys [code message]}
            [{:code "no_dataset_scope" :message "No dataset scope available."}
             {:code "agent_not_authorized" :message "API key cannot access agent: a"}
             {:code "mode_not_authorized" :message "API key cannot use skill graph: g"}
             {:code "agent_disabled" :message "Agent is disabled: a"}]]
      (with-redefs [mcp-tools/invoke-tool (fn [_ _ _ _] {:error {:code code :message message}})]
        (let [body (response->json
                     (transport/handle-mcp-request
                       (post {:jsonrpc "2.0" :id 8 :method "tools/call"
                              :params {:name "agent__graph" :arguments {:query "hi"}}})))]
          (is (nil? (:error body)) (str code " must not be a JSON-RPC error"))
          (is (true? (get-in body [:result :isError])) (str code " must set isError"))
          (is (= message (-> body :result :content first :text))
              (str code " must keep its text")))))))

(deftest unknown-tool-or-agent-is-invalid-params
  (testing "naming something that does not exist is -32602, not -32603"
    (doseq [code ["invalid_tool_name" "agent_not_found" "mode_not_allowed"]]
      (with-redefs [mcp-tools/invoke-tool
                    (fn [_ _ _ _] {:error {:code code :message (str "boom: " code)}})]
        (let [body (response->json
                     (transport/handle-mcp-request
                       (post {:jsonrpc "2.0" :id 9 :method "tools/call"
                              :params {:name "nope__graph" :arguments {:query "hi"}}})))]
          (is (= -32602 (get-in body [:error :code])) (str code " must be invalid-params"))
          (is (= code (get-in body [:error :data :code]))
              "the real code must survive, not be buried"))))))

(deftest genuine-internal-failure-is-still-minus-32603
  (testing "-32603 stays reserved for the server actually breaking"
    (with-redefs [mcp-tools/invoke-tool
                  (fn [_ _ _ _] (throw (ex-info "datahike exploded" {})))]
      (let [body (response->json
                   (transport/handle-mcp-request
                     (post {:jsonrpc "2.0" :id 10 :method "tools/call"
                            :params {:name "agent__graph" :arguments {:query "hi"}}})))]
        (is (= -32603 (get-in body [:error :code])))
        (is (= "datahike exploded" (get-in body [:error :message])))))))

(deftest parse-error-on-bad-json
  (let [r (transport/handle-mcp-request
            {:request-method :post
             :uri "/api/mcp"
             :body "{not json"
             :api-key/id "k1"})
        body (response->json r)]
    (is (= 400 (:status r)))
    (is (= -32700 (get-in body [:error :code])))))

(deftest tools-call-with-progress-token-returns-sse
  (testing "Including _meta.progressToken switches the response to SSE"
    (with-redefs [mcp-tools/invoke-tool
                  (fn [_ _ _ progress-fn]
                    (when progress-fn
                      (progress-fn {:event :stage/started :stage :test :label "Test"}))
                    {:result {:content [{:type "text" :text "ok"}]
                              :isError false
                              :_meta {:conversation_id "convo-1"}}})]
      (let [r (transport/handle-mcp-request
                (post {:jsonrpc "2.0" :id 8 :method "tools/call"
                       :params {:name "agent__graph"
                                :arguments {:query "hi"}
                                :_meta {:progressToken "tok-stream"}}}))]
        (is (= 200 (:status r)))
        (is (re-find #"event-stream" (get-in r [:headers "Content-Type"])))))))

;; =============================================================================
;; Multi-turn over the advertised surface (#116)
;;
;; A model generates tool arguments from the inputSchema and reads the response
;; body. So these drive turn 2 using ONLY what turn 1 put in the response body -
;; not _meta, not out-of-band knowledge - which is the surface an LLM-driven
;; client actually has.
;; =============================================================================

(def ^:private convo-agent
  {:id "builtin/rag-agent"
   :name "RAG Agent"
   :description "Answers questions."
   :default-skill-graph :builtin/agent-rag-graph-bundled
   :allowed-skill-graphs [:builtin/agent-rag-graph-bundled]
   :allowed-dataset-scopes [{:tenant "altinn-docs" :dataset-config-key "dev"}]
   :enabled? true})

(defn- with-conversation-stack
  "Run `f` with the registries stubbed and conversation persistence held in a
   plain in-memory store, so the real conversation threading runs for real."
  [f]
  (let [store (atom {})          ; convo-id -> [{:message/role :message/text}]
        seen-history (atom [])   ; conversation-history invoke-rag was handed
        arities (atom [])]       ; which build-rag-skill-params arity was used
    (with-redefs [config-db/get-conn (fn [] (atom :fake-config-conn))
                  agents-db/list-enabled-agents (fn [_] [convo-agent])
                  agents-db/get-agent (fn [_ id] (when (= id (:id convo-agent)) convo-agent))
                  skills-api/initialize! (fn [] nil)
                  skills-api/get-skill-graph-info
                  (fn [gid] {:id gid :name (name gid) :description "stub"})
                  config-core/get-master-key (fn [] "master-key")
                  config-db/get-dataset-by-ref (fn [_ _ _] {:docs-collection "docs"
                                                            :chunks-collection "chunks"
                                                            :phrases-collection "phrases"})
                  ;; Mirrors the real arity set (2 and 3) rather than
                  ;; (fn [& _] ...), and RECORDS which arity the call site
                  ;; actually took. Accepting three is not the same as
                  ;; asserting the caller uses three: a variadic stub erases
                  ;; the contract, and a bare fixed-3 stub only refuses a
                  ;; change without naming it. A caller switching to the
                  ;; 2-arity now fails an assertion that says so, and one
                  ;; moving to an arity neither the real fn nor this stub has
                  ;; throws. #119 was a real bug behind the erased shape; see
                  ;; #130.
                  api-util/build-rag-skill-params
                  (fn
                    ([_config _params] (swap! arities conj 2) {})
                    ([_config _params _agent-skill-params] (swap! arities conj 3) {}))
                  data-db/get-conn (fn [] (atom :fake-data-conn))
                  data-db/create-playground-conversation
                  (fn [_ _ _] (let [id (str "convo-" (count @store))]
                                (swap! store assoc id [])
                                {:conversation-id id}))
                  data-db/fetch-conversation-tree (fn [_ id] (get @store id []))
                  data-db/transact-playground-user-msg
                  (fn [_ id text _ _ _]
                    (swap! store update id (fnil conj [])
                           {:message/role :user :message/text text}))
                  data-db/transact-assistant-msg
                  (fn [_ id text _]
                    (swap! store update id (fnil conj [])
                           {:message/role :assistant :message/text text}))
                  invoke/invoke-rag
                  (fn [{:keys [conversation-history]}]
                    (swap! seen-history conj (vec conversation-history))
                    {:status :complete
                     ;; The answer states what it saw, so "turn 2 saw turn 1"
                     ;; is visible in the response body rather than only in a
                     ;; captured argument.
                     :response (str "Answer (saw " (count conversation-history) " prior turns)")
                     :insufficient? false
                     :chunks [] :queries []
                     :diagnostics {}
                     :error nil})]
      (f {:store store :seen-history seen-history :arities arities}))))

(defn- call-tool
  [id arguments]
  (response->json
    (transport/handle-mcp-request
      (post {:jsonrpc "2.0" :id id :method "tools/call"
             :params {:name "builtin.rag-agent__agent-rag-graph-bundled"
                      :arguments arguments}}))))

(deftest input-schema-advertises-the-conversation-handle
  (testing "tools/list names the fields a model must emit to hold a conversation"
    (with-conversation-stack
      (fn [_]
        (let [body (response->json
                     (transport/handle-mcp-request
                       (post {:jsonrpc "2.0" :id 20 :method "tools/list"})))
              tool (first (get-in body [:result :tools]))
              props (get-in tool [:inputSchema :properties])]
          (is (some? tool))
          (is (contains? props :conversation_id)
              "a model can only emit fields the schema mentions")
          (is (contains? props :tenant))
          (is (contains? props :dataset_config_key))
          (is (contains? props :overrides))
          (is (re-find #"(?i)conversation" (:description tool))
              "the description should say the handle is worth carrying"))))))

(deftest multi-turn-works-driving-turn-2-from-the-response-body
  (testing "turn 2 uses only the handle turn 1 put in the response body"
    (with-conversation-stack
      (fn [{:keys [seen-history arities]}]
        (let [turn1 (call-tool 21 {:query "Hva er Dialogporten?"})
              ;; The ONLY thing carried across. Deliberately not (:_meta ...):
              ;; _meta is not a channel a model reads.
              handle (get-in turn1 [:result :structuredContent :conversation_id])]
          (is (nil? (:error turn1)))
          (is (string? handle)
              "turn 1 must publish the handle in the response body")

          (let [turn2 (call-tool 22 {:query "Og hvem eier den?"
                                     :conversation_id handle})]
            (is (nil? (:error turn2)))
            (is (= handle (get-in turn2 [:result :structuredContent :conversation_id]))
                "turn 2 stays on the same conversation")
            ;; Turn 1 saw nothing; turn 2 saw turn 1's user message and answer.
            (is (= [] (first @seen-history)))
            (is (= 2 (count (second @seen-history)))
                "turn 2's invocation was handed turn 1's two messages")
            (is (= "Hva er Dialogporten?" (:text (first (second @seen-history)))))
            (is (= "Answer (saw 0 prior turns)" (:text (second (second @seen-history)))))
            ;; And it is visible in the answer itself, not just in a capture.
            (is (= "Answer (saw 2 prior turns)"
                   (-> turn2 :result :content first :text))
                "the second answer demonstrably sees the first turn")
            ;; #130: pin the arity the MCP call site actually uses, so a
            ;; caller switching to the 2-arity form fails here by name
            ;; rather than passing behind an erased stub.
            (is (= [3 3] @arities)
                "both turns must call build-rag-skill-params with 3 args")))))))

;; =============================================================================
;; The description surface (#123)
;;
;; MCP has three primitives and we implement one. These assert the parts of the
;; description surface that cost nothing: what a tool is called and what it
;; returns, how a client is told to use the server, and a link to the documents
;; an answer cited - the resource affordance existing before the capability
;; does.
;; =============================================================================

(deftest server-discover-tells-a-client-how-to-use-the-server
  ;; Was `initialize-tells-a-client-how-to-use-the-server` (#123). The method
  ;; moved with the 2026-07-28 migration (#146) but the GUARANTEE did not: this
  ;; string is the only place on the wire that documents the tool naming, the
  ;; conversation handle and the error split, so the assertion follows it to
  ;; `server/discover` rather than being deleted with `initialize`.
  ;; DiscoverResult has a first-class `instructions` field, so this is its
  ;; proper home rather than a workaround.
  (let [body (response->json
               (transport/handle-mcp-request
                 (post {:jsonrpc "2.0" :id 30 :method "server/discover"
                        :params {}})))
        instructions (get-in body [:result :instructions])]
    (is (string? instructions) "server/discover must carry instructions")
    (is (re-find #"__" instructions)
        "should explain the agent__mode tool naming")
    (is (re-find #"(?i)conversation_id" instructions)
        "should explain how to hold a conversation")))

(deftest tools-list-carries-title-and-output-schema
  (with-conversation-stack
    (fn [_]
      (let [body (response->json
                   (transport/handle-mcp-request
                     (post {:jsonrpc "2.0" :id 31 :method "tools/list"})))
            tools (get-in body [:result :tools])]
        (is (seq tools))
        (doseq [tool tools]
          (is (string? (:title tool))
              (str (:name tool) " must carry a human-facing title"))
          (is (not= (:title tool) (:name tool))
              "title is a display name, distinct from the identifier")
          (is (= "object" (get-in tool [:outputSchema :type]))
              (str (:name tool) " must declare its structured output"))
          (is (contains? (get-in tool [:outputSchema :properties]) :conversation_id)
              "outputSchema must describe what we actually return"))))))

(def ^:private cited-workspace
  "A workspace holding three chunks across two documents, in the shape the
   agent actually builds: `:chunks` keyed by chunk_id, and the document join
   under a COLLECTION-NAMED key rather than at the top level.

   The stub below publishes `(workspace/chunks-for-output cited-workspace)`
   rather than a hand-written vector. That matters: the previous fixture wrote
   `:title` and `:url` at the top level directly, a shape the real pipeline
   never produced, so this test passed against an invented shape while the
   deployed graph published empty objects and no links at all (#460)."
  {:chunks {"c1" {:chunk_id "c1" :doc_num "7" :chunk_index 0 :retrieval-prior 0.9
                  :website_documents_ab897fbdedfa {:title "Årsrapport 2022"
                                                   :url "https://example.test/aarsrapport"
                                                   :total_chunks 3}}
            "c2" {:chunk_id "c2" :doc_num "7" :chunk_index 1 :retrieval-prior 0.8
                  :website_documents_ab897fbdedfa {:title "Årsrapport 2022"
                                                   :url "https://example.test/aarsrapport"
                                                   :total_chunks 3}}
            "c3" {:chunk_id "c3" :doc_num "9" :chunk_index 0 :retrieval-prior 0.7
                  :website_documents_ab897fbdedfa {:title "Tildelingsbrev"
                                                   :total_chunks 1}}}})

(defn- cited-invoke-result
  [_]
  {:status :complete
   :response "Digdir hadde 330 årsverk [1]."
   :chunks (workspace/chunks-for-output cited-workspace)
   :queries [] :diagnostics {} :error nil})

(deftest tools-call-publishes-populated-chunks
  ;; #460. `structuredContent.chunks` came back as `[{} {}]` — the key PRESENT
  ;; and every object EMPTY — because the graph handed the payload builder the
  ;; workspace's chunk MAP and `mapv select-keys` over a map iterates
  ;; `MapEntry`. A present-but-empty array is worse than an absent one: a
  ;; client sees `chunks` and concludes it received them.
  ;;
  ;; Asserted on KEY SETS and populated fields, never on a count — `[{} {}]`
  ;; and two real chunks have the same count, which is precisely why the two
  ;; tests that covered this area did not fail.
  (testing "tools/call publishes chunks a client can actually use"
    (with-conversation-stack
      (fn [_]
        (with-redefs [invoke/invoke-rag cited-invoke-result]
          (let [body (call-tool 33 {:query "Hvor mange årsverk?"})
                chunks (get-in body [:result :structuredContent :chunks])]
            (is (seq chunks) "structuredContent must carry the retrieved chunks")
            (is (every? seq chunks)
                "every chunk must carry keys — [{} {}] is the map-shaped defect")
            (is (= ["c1" "c2" "c3"] (mapv :chunk_id chunks))
                "chunk ids must reach the client; a MapEntry yields nil here")
            (is (= ["Årsrapport 2022" "Årsrapport 2022" "Tildelingsbrev"]
                   (mapv :title chunks))
                "the joined document's title must reach the client")
            (is (= "https://example.test/aarsrapport" (:url (first chunks)))
                "the joined document's url must reach the client")))))))

(deftest cited-answer-carries-resource-links
  (testing "a cited answer links the documents it drew on"
    (with-conversation-stack
      (fn [_]
        (with-redefs [invoke/invoke-rag cited-invoke-result]
          (let [body (call-tool 32 {:query "Hvor mange årsverk?"})
                content (get-in body [:result :content])
                links (filterv #(= "resource_link" (:type %)) content)]
            (is (= "text" (:type (first content)))
                "the answer text still comes first")
            (is (seq links) "a cited answer must carry at least one resource_link")
            (is (= 2 (count links))
                "one link per cited document, not per chunk")
            (is (= "https://example.test/aarsrapport" (:uri (first links)))
                "a document WITH a url is linked by that url, not by the digdir:// fallback")
            (is (= "Årsrapport 2022" (:name (first links))))
            ;; The fallback branch, asserted by VALUE. `string?` was satisfied
            ;; by "digdir://doc/" with a nil doc_num appended, and by the
            ;; placeholder name — both of which is what the defect produced for
            ;; EVERY document, url or not (#460).
            (is (= "digdir://doc/9" (:uri (second links)))
                "a document without a url still needs a stable, dereferenceable-by-us uri")
            (is (= "Tildelingsbrev" (:name (second links)))
                "and it is named by its document, not by the \"Document <n>\" placeholder")))))))
;;; ---------------------------------------------------------------------------
;;; Server Validation (2026-07-28, Streamable HTTP §Request Metadata)
;;;
;;; The point of mirroring body fields into headers is that intermediaries
;;; route on the header while the server executes on the body. If those two
;;; can disagree, the mirroring is a vulnerability rather than a convenience,
;;; so every disagreement is a rejection.
;;; ---------------------------------------------------------------------------

(deftest missing-protocol-version-header-is-rejected
  (let [r (transport/handle-mcp-request
            (post {:jsonrpc "2.0" :id 1 :method "tools/list"}
                  {"mcp-method" "tools/list"}))
        body (response->json r)]
    (is (= 400 (:status r)))
    (is (= -32020 (get-in body [:error :code])))))

(deftest protocol-version-header-must-match-the-body-meta
  (let [r (transport/handle-mcp-request
            (post {:jsonrpc "2.0" :id 1 :method "tools/list"
                   :params {:_meta {(keyword "io.modelcontextprotocol/protocolVersion")
                                    "2025-11-25"}}}
                  {"mcp-protocol-version" "2026-07-28"
                   "mcp-method" "tools/list"}))
        body (response->json r)]
    (is (= 400 (:status r)))
    (is (= -32020 (get-in body [:error :code])))
    (is (re-find #"does not match body" (get-in body [:error :message])))))

(deftest unsupported-version-lists-what-we-do-support
  ;; A client that asks for a version we do not speak must be able to retry
  ;; without guessing, so the supported list travels with the error.
  (let [r (transport/handle-mcp-request
            (post {:jsonrpc "2.0" :id 1 :method "tools/list"}
                  {"mcp-protocol-version" "2025-03-26"
                   "mcp-method" "tools/list"}))
        body (response->json r)]
    (is (= 400 (:status r)))
    (is (= -32022 (get-in body [:error :code])))
    (is (= ["2026-07-28"] (get-in body [:error :data :supported])))
    (is (= "2025-03-26" (get-in body [:error :data :requested])))))

(deftest missing-or-mismatched-mcp-method-header-is-rejected
  (testing "missing"
    (let [r (transport/handle-mcp-request
              (post {:jsonrpc "2.0" :id 1 :method "tools/list"}
                    {"mcp-protocol-version" "2026-07-28"}))]
      (is (= 400 (:status r)))
      (is (= -32020 (get-in (response->json r) [:error :code])))))
  (testing "mismatched"
    (let [r (transport/handle-mcp-request
              (post {:jsonrpc "2.0" :id 1 :method "tools/list"}
                    {"mcp-protocol-version" "2026-07-28"
                     "mcp-method" "tools/call"}))]
      (is (= 400 (:status r)))
      (is (= -32020 (get-in (response->json r) [:error :code]))))))

(deftest tools-call-requires-a-matching-mcp-name-header
  (testing "missing"
    (let [r (transport/handle-mcp-request
              (post {:jsonrpc "2.0" :id 1 :method "tools/call"
                     :params {:name "t1" :arguments {}}}
                    {"mcp-protocol-version" "2026-07-28"
                     "mcp-method" "tools/call"}))]
      (is (= 400 (:status r)))
      (is (= -32020 (get-in (response->json r) [:error :code])))))
  (testing "mismatched — the header names a different tool than the body"
    (let [r (transport/handle-mcp-request
              (post {:jsonrpc "2.0" :id 1 :method "tools/call"
                     :params {:name "t1" :arguments {}}}
                    {"mcp-protocol-version" "2026-07-28"
                     "mcp-method" "tools/call"
                     "mcp-name" "a-different-tool"}))]
      (is (= 400 (:status r)))
      (is (= -32020 (get-in (response->json r) [:error :code]))))))

(deftest mcp-name-is-compared-after-base64-decoding
  ;; Tool names are only SHOULD-constrained to header-safe characters, so a
  ;; name outside that set arrives base64-wrapped. Comparing before decoding
  ;; would reject every such call.
  (with-redefs [mcp-tools/invoke-tool
                (fn [_ tool-name _ _] {:result {:content [] :echo tool-name}})]
    (let [tool "tøøl/with spaces"
          encoded (str "=?base64?"
                       (.encodeToString (java.util.Base64/getEncoder)
                                        (.getBytes ^String tool "UTF-8"))
                       "?=")
          r (transport/handle-mcp-request
              (post {:jsonrpc "2.0" :id 1 :method "tools/call"
                     :params {:name tool :arguments {}}}
                    {"mcp-protocol-version" "2026-07-28"
                     "mcp-method" "tools/call"
                     "mcp-name" encoded}))]
      (is (= 200 (:status r))
          "a base64-sentinel Mcp-Name matching the body must be accepted"))))

(deftest get-and-delete-are-405-not-404
  ;; Both verbs belonged to the pre-2026-07-28 shape (standalone SSE stream,
  ;; session termination). 405 tells a client "wrong verb"; 404 would tell it
  ;; "wrong endpoint" and send it hunting for a different URL.
  (doseq [verb [:get :delete]]
    (let [r (transport/handle-mcp-method-not-allowed {:request-method verb})]
      (is (= 405 (:status r)))
      (is (= "POST" (get-in r [:headers "Allow"]))))))

;; ---------------------------------------------------------------------------
;; Envelope key sets (#195)
;;
;; The assertions above check individual VALUES. A key the producer should not
;; emit is invisible to those — nothing reads it, so nothing fails. That is how
;; a stub emitting `config-key` kept a fabricated field alive through a whole
;; test suite (#191), and how `openapi.yaml` came to advertise a field no
;; handler sends (#184).
;;
;; MCP is the surface where that matters most in this repo, because the
;; consumer is an external client rather than our own UI. The docstring on
;; `jsonrpc-response` records what that costs: Claude Code 2.1.238 silently
;; discarded a `tools/list` response missing `resultType`, retried four times,
;; and reported the server as having no tools — on an HTTP 200 whose body
;; "looked correct".
;;
;; JSON-RPC 2.0 makes the envelope a genuinely CLOSED set, so these assert
;; equality rather than a subset. The `result` payload is per-method and open,
;; so it is asserted as a required subset instead — the distinction #192 drew
;; between a shape you can enumerate and one you can only constrain.

(defn- envelope [body] (set (keys body)))

(defn- success-body [request]
  (let [b (response->json (transport/handle-mcp-request request))]
    (is (contains? b :result)
        (str "expected a success envelope, got: " (pr-str (envelope b))))
    b))

(defn- error-body [request]
  (let [b (response->json (transport/handle-mcp-request request))]
    (is (contains? b :error)
        (str "expected an error envelope, got: " (pr-str (envelope b))))
    b))

(deftest success-envelope-carries-exactly-the-jsonrpc-fields
  (testing "a success response is exactly jsonrpc + id + result"
    (doseq [[label req]
            [["server/discover"
              (post {:jsonrpc "2.0" :id 1 :method "server/discover" :params {}})]
             ["tools/list"
              (post {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}})]]]
      (let [b (success-body req)]
        (is (= #{:jsonrpc :id :result} (envelope b))
            (str label " envelope has unexpected keys: "
                 (pr-str (sort (envelope b)))))))))

(deftest error-envelope-carries-exactly-the-jsonrpc-fields
  (testing "an error response is exactly jsonrpc + id + error"
    (let [b (error-body (post {:jsonrpc "2.0" :id 3 :method "unknown/method" :params {}}))]
      (is (= #{:jsonrpc :id :error} (envelope b))
          (str "error envelope has unexpected keys: " (pr-str (sort (envelope b))))))))

(deftest error-object-carries-only-code-message-and-optional-data
  (testing "jsonrpc-error builds {:code :message} and adds :data only when present"
    (let [b (error-body (post {:jsonrpc "2.0" :id 4 :method "unknown/method" :params {}}))
          err (set (keys (:error b)))]
      (is (empty? (clojure.set/difference err #{:code :message :data}))
          (str "error object has keys outside the JSON-RPC set: "
               (pr-str (sort (clojure.set/difference err #{:code :message :data})))))
      (is (clojure.set/subset? #{:code :message} err)
          "code and message are required by JSON-RPC 2.0"))))

(deftest every-map-result-carries-the-result-type-discriminator
  (testing "resultType is what a real client silently required"
    ;; Asserted as a required subset, not equality: the rest of `result` is
    ;; per-method and legitimately open, so closing it here would be false for
    ;; every method but the one it was written against.
    (doseq [[label req]
            [["server/discover"
              (post {:jsonrpc "2.0" :id 5 :method "server/discover" :params {}})]
             ["tools/list"
              (post {:jsonrpc "2.0" :id 6 :method "tools/list" :params {}})]]]
      (let [result (:result (success-body req))]
        (when (map? result)
          (is (contains? result :resultType)
              (str label " result is missing the resultType discriminator")))))))

(ns digdir.api.conversation-doors-test
  "the doors that do NOT go through the REST handlers' lookup, above all
   the MCP tool path. It never called `conversation-by-id`, so scoping that
   lookup alone could not close it.

   ADOPTED from a pre-registered probe (
   measured on c3d136cd before any fix existed): the doors, the attacker, the
   stubs and the labels are the original probe's. Changed, because the fix changed what the
   probe calls, and each change is stated here:
     - `find-api-conversation!` and `conversations-by-user-paginated` now take the
       request's tenant; the attacker passes ITS OWN tenant (tenant-b);
     - the victim is BOUND to tenant-a, as REST creation now binds it, so a refusal
       below is for the right reason and not merely \"an unbound row\";
     - a D6 refusal is asserted as a refusal (`conversation_not_found`, nothing
       appended, no history crossed). The probe's \"invoke-rag was reached\"
       instrument now lives in the OWNER control, where it must still hold;
     - ADDED: the MCP owner control, an unknown id that must not be upserted, and
       a key naming a tenant it is not granted;
     - EVERY call names its tenant (the project owner: a conversation is addressed by the
       pair (tenant, id), never by id alone). ADDED for that rule: no tenant is
       refused for every key, and a superuser - since the tenant-scope fix a key MARKED
       all-tenant, not one with no scopes - keeps its power in the tenant it names
       and nothing beyond it.

   Victim: key A (tenant-a, client-id \"client-a\"), end user \"alice\".
   Attacker: key B (tenant-b, client-id \"client-b\"), holding the victim's
   conversation id and, where a door needs it, the victim's X-User-Id."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [datahike.api :as d]
            [digdir.agents.db :as agents-db]
            [digdir.api.util :as api-util]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.data.db :as data-db]
            [digdir.mcp.tools :as mcp-tools]
            [digdir.skills.api :as skills-api]
            [digdir.skills.invoke :as invoke]))

(def ^:private secret "ALICE-PRIVATE-7f3c")

(def ^:private rag-agent
  {:id "builtin/rag-agent"
   :name "RAG Agent"
   :description "Answers questions."
   :default-skill-graph :builtin/agent-rag-graph-bundled
   :allowed-skill-graphs [:builtin/agent-rag-graph-bundled]
   :allowed-dataset-scopes []
   :enabled? true})

(defn- mcp-key [id tenant client]
  {:api-key/id id
   :api-key/agent-refs ["builtin/rag-agent"]
   :api-key/dataset-scopes [{:tenant tenant :dataset-config-key "dev"}]
   :api-key/skill-graphs []
   :api-key/client-id client})

(def ^:private key-a (mcp-key "key-a" "tenant-a" "client-a"))
(def ^:private key-b (mcp-key "key-b" "tenant-b" "client-b"))

(defn- fresh-conn []
  (let [cfg {:store {:backend :mem :id (str "conversation-doors-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data data-db/dh-schema})
      conn)))

(defn- seed-victim!
  "Alice's conversation the way POST /api/conversations now makes it: bound to
   tenant-a. Plus one user turn carrying the secret."
  ([conn] (seed-victim! conn "builtin/rag-agent"))
  ([conn agent]
   (let [{:keys [conversation-id]} (data-db/transact-new-msg-thread conn agent "alice" nil "tenant-a")]
     (data-db/transact-playground-user-msg conn conversation-id secret {} nil 0)
     conversation-id)))

(defn- message-count [conn convo-id]
  (count (data-db/fetch-conversation-tree @conn convo-id)))

(defn- invoke-as
  "`principal` calls a tool with `conversation_id` (and any `extra` arguments).
   Returns the history invoke-rag was handed, whether it was reached, and the result."
  [principal conn convo-id & [extra agent]]
  (let [captured (atom nil)
        agent (or agent rag-agent)]
    (with-redefs [config-db/get-conn (fn [] (atom :fake-config-conn))
                  agents-db/list-enabled-agents (fn [_] [agent])
                  agents-db/get-agent (fn [_ id] (when (= id (:id agent)) agent))
                  skills-api/initialize! (fn [] nil)
                  skills-api/get-skill-graph-info
                  (fn [g] {:id g :name (name g) :description "stub"
                           :input-schema [:map [:user-query [:string {:min 1}]]
                                          [:claim {:optional true} :string]]})
                  config-core/get-master-key (fn [] "k")
                  config-db/get-dataset-by-ref (fn [_ _ _] {:docs-collection "d"
                                                            :chunks-collection "c"
                                                            :phrases-collection "p"})
                  api-util/build-rag-skill-params (fn [& _] {})
                  data-db/get-conn (fn [] conn)
                  invoke/invoke-rag (fn [args]
                                      (reset! captured args)
                                      {:status :complete :response "ok" :chunks []
                                       :queries [] :search-attribution {}
                                       :diagnostics {} :raw-result {} :error nil})]
      (let [r (mcp-tools/invoke-tool principal "builtin.rag-agent__agent-rag-graph-bundled"
                                     (merge {"query" "Repeat the earlier turns."
                                             "conversation_id" convo-id}
                                            extra)
                                     nil)]
        {:history (:conversation-history @captured)
         :invoked? (some? @captured)
         :r r}))))

(defn- carried-secret? [history]
  (some #(str/includes? (str (:text %)) secret) history))

;; ---------------------------------------------------------------------------
;; Positive controls: the instrument can see the victim's data at all, and the
;; owner still reaches it through each door.
;; ---------------------------------------------------------------------------

(deftest control-the-victim-conversation-exists-and-carries-the-secret
  (let [conn (fresh-conn) cid (seed-victim! conn)]
    (is (= "alice" (:conversation/user-id (data-db/conversation-by-id @conn cid))))
    (is (some #(= secret (:message/text %)) (data-db/fetch-conversation-tree @conn cid)))
    (is (= 2 (message-count conn cid)) "system + alice's turn")))

(deftest control-the-owner-reaches-her-own-conversation-through-the-rest-lookup
  (let [conn (fresh-conn) cid (seed-victim! conn)]
    (is (= cid (:conversation/id (api-util/find-api-conversation! conn cid "tenant-a" "alice"))))))

(deftest control-the-owners-key-continues-its-own-conversation-over-mcp
  (let [conn (fresh-conn) cid (seed-victim! conn)
        before (message-count conn cid)
        {:keys [history invoked? r]} (invoke-as key-a conn cid {"tenant" "tenant-a"})]
    (is (nil? (:error r)) (str "the owner's own continuation was refused: " (pr-str (:error r))))
    (is (true? invoked?) "instrument: the stubbed invoke-rag was reached")
    (is (carried-secret? history) "the owner's own earlier turn did not reach the model")
    (is (= (+ before 2) (message-count conn cid)) "the owner's turn and the answer were not appended")))

;; ---------------------------------------------------------------------------
;; D6/D7: POST /api/mcp tools/call and POST /api/tools/call/:tool-name both
;; reach mcp-tools/invoke-tool with a caller-chosen conversation_id.
;; ---------------------------------------------------------------------------

(deftest d6-mcp-conversation-id-does-not-feed-another-keys-history-to-the-model
  (let [conn (fresh-conn) cid (seed-victim! conn)
        {:keys [history r]} (invoke-as key-b conn cid {"tenant" "tenant-b"})]
    (is (not (carried-secret? history))
        (str "key B's model context carried alice's turn: " (pr-str history)))
    (is (= "conversation_not_found" (get-in r [:error :code]))
        (str "key B's call on alice's conversation was not refused: " (pr-str r)))))

(deftest d6-mcp-conversation-id-does-not-append-to-another-keys-conversation
  (let [conn (fresh-conn) cid (seed-victim! conn)
        before (message-count conn cid)
        _ (invoke-as key-b conn cid {"tenant" "tenant-b"})
        after (message-count conn cid)]
    (is (= before after)
        (str "key B appended " (- after before) " message(s) to alice's conversation"))))

(deftest d6-variant-victim-on-an-agent-key-b-is-not-granted
  (testing "alice's conversation runs an agent key B has NO agent-ref for"
    (let [conn (fresh-conn)
          cid (seed-victim! conn "tenant-a/private-agent")
          before (message-count conn cid)
          {:keys [history]} (invoke-as key-b conn cid {"tenant" "tenant-b"})]
      (is (not (carried-secret? history)) "history crossed")
      (is (= before (message-count conn cid)) "append crossed"))))

(deftest d6-an-unknown-conversation-id-is-refused-and-not-created
  (let [conn (fresh-conn)
        unknown "no-such-conversation-575"
        {:keys [r invoked?]} (invoke-as key-b conn unknown {"tenant" "tenant-b"})]
    (is (= "conversation_not_found" (get-in r [:error :code]))
        (str "an unknown conversation_id was not refused: " (pr-str r)))
    (is (false? invoked?) "the model was called for an unknown conversation_id")
    (is (nil? (data-db/conversation-by-id @conn unknown))
        "an unknown conversation_id was UPSERTED as a new conversation")))

(deftest d6-a-key-naming-a-tenant-it-is-not-granted-reaches-nothing
  (let [conn (fresh-conn) cid (seed-victim! conn)
        before (message-count conn cid)
        {:keys [history r]} (invoke-as key-b conn cid {"tenant" "tenant-a" "dataset_config_key" "dev"})]
    (is (some? (:error r)) (str "key B named tenant-a and was not refused: " (pr-str r)))
    (is (not (carried-secret? history)) "history crossed")
    (is (= before (message-count conn cid)) "append crossed")))

;; ---------------------------------------------------------------------------
;; D8/D4: the REST lookup and list, on MCP-created conversations, whose owner
;; is the creating key's client-id. The attacker names its OWN tenant.
;; ---------------------------------------------------------------------------

(defn- seed-mcp-conversation-of-key-a! [conn]
  (:conversation-id (data-db/create-playground-conversation
                     conn "builtin/rag-agent"
                     {:user-id "client-a" :tenant "tenant-a" :dataset-config-key "dev"})))

(deftest d8-rest-get-by-id-with-a-client-id-header-does-not-reach-key-a-mcp-conversation
  (let [conn (fresh-conn) cid (seed-mcp-conversation-of-key-a! conn)
        found (try (api-util/find-api-conversation! conn cid "tenant-b" "client-a")
                   (catch clojure.lang.ExceptionInfo _ nil))]
    (is (nil? found) "key B, sending X-User-Id: client-a, resolved key A's MCP conversation")))

(deftest d4-rest-list-with-a-client-id-header-does-not-list-key-a-mcp-conversations
  (let [conn (fresh-conn) cid (seed-mcp-conversation-of-key-a! conn)
        ids (set (map :conversation/id
                      (:conversations (data-db/conversations-by-user-paginated @conn "tenant-b" "client-a" 50 0))))]
    (is (not (contains? ids cid)) "the list keyed only by X-User-Id returned key A's MCP conversation")))

;; ---------------------------------------------------------------------------
;; Over MCP, EVERY call names its tenant, for every key: there is no default.
;; `pick-dataset-scope` used to take the FIRST granted scope silently.
;; ---------------------------------------------------------------------------

(def ^:private key-ab
  {:api-key/id "key-ab"
   :api-key/agent-refs ["builtin/rag-agent"]
   :api-key/dataset-scopes [{:tenant "tenant-a" :dataset-config-key "dev"}
                            {:tenant "tenant-b" :dataset-config-key "dev"}]
   :api-key/skill-graphs []
   :api-key/client-id "client-ab"})

(defn- conversation-count [conn]
  (count (d/q '[:find ?e :where [?e :conversation/id _]] @conn)))

(deftest mcp-a-key-with-several-tenants-must-name-one
  (let [conn (fresh-conn)
        before (conversation-count conn)
        {:keys [r invoked?]} (invoke-as key-ab conn nil)]
    (is (= "tenant_required" (get-in r [:error :code]))
        (str "a key granted two tenants called a tool without naming one, and the FIRST scope was taken silently: "
             (pr-str (or (:error r) (keys r)))))
    (is (false? invoked?) "the model was called for a key that named no tenant")
    (is (= before (conversation-count conn)) "a conversation was created for a key that named no tenant")))

(deftest mcp-a-key-with-several-tenants-acts-in-the-tenant-it-names
  (let [conn (fresh-conn)
        {:keys [r invoked?]} (invoke-as key-ab conn nil {"tenant" "tenant-b"})
        cid (or (get-in r [:result :structuredContent :conversation_id])
                (get-in r [:result :_meta :conversation_id]))]
    (is (nil? (:error r)) (str "naming a granted tenant was refused: " (pr-str (:error r))))
    (is (true? invoked?) "instrument: the stubbed invoke-rag was reached")
    (is (= "tenant-b" (:conversation/tenant (d/pull @conn [:conversation/tenant] [:conversation/id cid])))
        "the conversation was not bound to the tenant the call named")))

(deftest mcp-naming-one-tenant-does-not-reach-a-conversation-in-another
  (let [conn (fresh-conn) cid (seed-victim! conn)
        before (message-count conn cid)
        {:keys [r history]} (invoke-as key-ab conn cid {"tenant" "tenant-b"})]
    (is (= "conversation_not_found" (get-in r [:error :code]))
        "naming tenant-b continued a tenant-a conversation: the UNION of the key's tenants was used")
    (is (not (carried-secret? history)) "history crossed")
    (is (= before (message-count conn cid)) "append crossed")))

(deftest mcp-a-key-with-one-tenant-must-still-name-it
  (let [conn (fresh-conn)
        before (conversation-count conn)
        {:keys [r invoked?]} (invoke-as key-a conn nil)]
    (is (= "tenant_required" (get-in r [:error :code]))
        (str "a key granted ONE tenant called a tool without naming it, and its tenant was DERIVED: "
             (pr-str (or (:error r) (keys r)))))
    (is (false? invoked?) "the model was called for a call that named no tenant")
    (is (= before (conversation-count conn)) "a conversation was created for a call that named no tenant")))

(def ^:private key-superuser
  "the explicit all-tenant marker; a key with no scopes and no marker
   reaches no tenant."
  {:api-key/id "key-su" :api-key/agent-refs ["builtin/rag-agent"] :api-key/all-tenants? true
   :api-key/dataset-scopes [] :api-key/skill-graphs [] :api-key/client-id "client-su"})

(deftest mcp-a-superuser-key-keeps-its-power-in-the-tenant-it-names-and-nothing-beyond
  (let [conn (fresh-conn) cid (seed-victim! conn)]
    (testing "no tenant named: refused, whatever its allocation"
      (is (= "tenant_required" (get-in (:r (invoke-as key-superuser conn cid)) [:error :code]))
          "a superuser addressed a conversation by id alone"))
    (testing "the conversation's own tenant named: its power is unchanged"
      (is (= "dataset_not_authorized"
             (get-in (:r (invoke-as (dissoc key-superuser :api-key/all-tenants?) conn cid {"tenant" "tenant-a" "dataset_config_key" "dev"}))
                     [:error :code]))
          "the same key without the marker reaches no tenant")
      (let [{:keys [r history]} (invoke-as key-superuser conn cid {"tenant" "tenant-a" "dataset_config_key" "dev"})]
        (is (nil? (:error r)) (str "the superuser's POWER changed: " (pr-str (:error r))))
        (is (carried-secret? history) "the superuser did not reach the conversation in the tenant it named")))
    (testing "another tenant named: the pair is the address"
      (let [before (message-count conn cid)
            {:keys [r history]} (invoke-as key-superuser conn cid {"tenant" "tenant-b" "dataset_config_key" "dev"})]
        (is (= "conversation_not_found" (get-in r [:error :code]))
            "a superuser naming tenant-b continued a tenant-a conversation")
        (is (not (carried-secret? history)) "history crossed")
        (is (= before (message-count conn cid)) "append crossed")))))

(deftest mcp-a-superuser-naming-only-a-tenant-must-also-name-a-dataset-when-the-agent-declares-none
  (testing "a key with no scopes, on an agent that declares no scopes, has no dataset to default to"
    (let [conn (fresh-conn) cid (seed-victim! conn)]
      (with-redefs-fn {#'mcp-tools/env-default-scope (fn [] {:tenant "elsewhere" :dataset-config-key "dev"})}
        #(let [{:keys [r invoked?]} (invoke-as key-superuser conn cid {"tenant" "tenant-a"})]
           (is (= "dataset_not_authorized" (get-in r [:error :code]))
               (str "a superuser naming only a tenant was not told to name a dataset: " (pr-str (or (:error r) (keys r)))))
           (is (false? invoked?) "the model was called without a dataset")))
      (testing "unless the server's default dataset lies in that tenant"
        (with-redefs-fn {#'mcp-tools/env-default-scope (fn [] {:tenant "tenant-a" :dataset-config-key "dev"})}
          #(let [{:keys [r history]} (invoke-as key-superuser conn cid {"tenant" "tenant-a"})]
             (is (nil? (:error r)) (str "the env default in the named tenant was not used: " (pr-str (:error r))))
             (is (carried-secret? history) "the superuser did not reach its conversation through the env default")))))))

(def ^:private declaring-agent
  (assoc rag-agent :allowed-dataset-scopes [{:tenant "tenant-a" :dataset-config-key "dev"}]))

(deftest mcp-a-superuser-on-an-agent-that-declares-scopes-gets-the-agents-dataset-and-only-its-tenant
  (testing "the agent SUPPLIES a dataset in its tenant, and LIMITS which tenant may be named"
    (let [conn (fresh-conn) cid (seed-victim! conn)]
      (with-redefs-fn {#'mcp-tools/env-default-scope (fn [] {:tenant "elsewhere" :dataset-config-key "dev"})}
        #(do
           (let [{:keys [r history]} (invoke-as key-superuser conn cid {"tenant" "tenant-a"} declaring-agent)]
             (is (nil? (:error r))
                 (str "a superuser naming only the agent's tenant was refused, though the agent supplies a dataset: "
                      (pr-str (:error r))))
             (is (carried-secret? history) "the superuser did not reach its conversation through the agent's dataset"))
           (let [{:keys [r invoked?]} (invoke-as key-superuser conn cid {"tenant" "tenant-b"} declaring-agent)]
             (is (= "dataset_not_authorized" (get-in r [:error :code]))
                 (str "a superuser named a tenant the agent does not declare, and was not refused: "
                      (pr-str (or (:error r) (keys r)))))
             (is (false? invoked?) "the model was called in a tenant the agent does not declare")))))))

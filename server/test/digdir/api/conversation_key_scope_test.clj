(ns digdir.api.conversation-key-scope-test
  "the REST doors reach a conversation only by the PAIR (tenant,
   conversation id), and every request names its tenant.

   the project owner's rule: conversations MUST be tenant scoped, and it is never possible
   to address one by id alone. So the tenant is REQUIRED on every call, for
   every key; nothing is derived. Since the tenant-scope fix a key with no grant reaches NO
   tenant; a key MARKED all-tenant may name any tenant, but it must name one.
   `X-User-Id` stays trusted and
   caller-owned: one key serves many end users (docs/system-overview.md,
   \"Caller-scoped conversations\"). What was wrong is that the lookup behind the
   ownership check was GLOBAL.

   These run on a REAL store. The older handler tests stub
   `db/conversation-by-id`, which is exactly the part that was global, so they
   could not see this. The end user's id is the SAME on both sides, as it would be
   for an email or a customer number held by two customers.

   The MCP door is `conversation-doors-test`."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [digdir.agents.db :as agents-db]
            [digdir.api.routes :as routes]
            [digdir.config.db :as config-db]
            [digdir.data.db :as db]))

(def ^:private agent-id "builtin/agent-rag-agent")
(def ^:private end-user "shared-end-user@example.com")

(defn- key-request
  "What `wrap-api-key-auth` attaches for a key granted `tenants`, plus the
   end-user header."
  [k tenants]
  {:headers {"x-user-id" end-user}
   :api-key/id (str "api-key-" k)
   :api-key/name (str "customer " k)
   :api-key/client-id (str "client-" k)
   :api-key/agent-refs [agent-id]
   :api-key/scopes #{}
   :api-key/dataset-scopes (mapv (fn [t] {:tenant t :dataset-config-key "docs"}) tenants)
   :api-key/allowed-config-keys []})

(def ^:private key-a (key-request "A" ["tenant-a"]))
(def ^:private key-b (key-request "B" ["tenant-b"]))
(def ^:private key-ab (key-request "AB" ["tenant-a" "tenant-b"]))
(def ^:private key-none (key-request "NONE" []))
(def ^:private key-all
  "the explicit all-tenant marker (the deliberate breadth a key with no
   scopes used to have by allocation)."
  (assoc (key-request "ALL" []) :api-key/all-tenants? true))

(defn- json-body [m] (io/input-stream (.getBytes (json/generate-string m) "UTF-8")))

(defn- with-store
  "Run `f` with a fresh REAL in-memory store as the data DB."
  [f]
  (let [cfg {:store {:backend :mem :id (str "conversation-key-scope-" (random-uuid))}
             :schema-flexibility :read}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn {:tx-data db/dh-schema})
      (try
        (with-redefs [db/get-conn (fn [] conn)
                      config-db/get-conn (fn [] (atom {:db true}))
                      agents-db/list-enabled-agents
                      (fn [_] [{:id agent-id :name "Agent" :default-skill-graph "builtin/agent-rag"
                                :allowed-skill-graphs ["builtin/agent-rag"] :allowed-dataset-scopes []
                                :guardrails {} :enabled? true}])]
          (f conn))
        (finally
          (d/release conn)
          (d/delete-database cfg))))))

(defn- create! [request tenant & [body]]
  (routes/create-conversation-handler
   (assoc request :body (json-body (cond-> (merge {:title "the owner's conversation"} body)
                                     tenant (assoc :tenant tenant))))))

(defn- created-id [response]
  (get-in (json/parse-string (:body response) true) [:conversation :id]))

(defn- create-ok! [request tenant & [body]]
  (let [response (create! request tenant body)]
    (is (= 201 (:status response)) (str "PREMISE: create failed: " (:body response)))
    (created-id response)))

(defn- on [request convo-id & [{:keys [tenant body]}]]
  (cond-> (assoc request :path-params {:id convo-id})
    tenant (assoc-in [:parameters :query :tenant] tenant)
    body (assoc :body (json-body body))))

(defn- listed-ids [request & [tenant]]
  (let [response (routes/list-conversations-handler
                  (cond-> request tenant (assoc-in [:parameters :query :tenant] tenant)))]
    {:status (:status response)
     :ids (set (map :id (:conversations (json/parse-string (:body response) true))))}))

(defn- topic [conn convo-id] (:conversation/topic (db/conversation-by-id @conn convo-id)))

;; ---------------------------------------------------------------------------
;; The tenant is REQUIRED on every call, for every key: nothing is derived.
;; ---------------------------------------------------------------------------

(deftest every-conversation-call-must-name-its-tenant-whatever-the-key
  (with-store
    (fn [conn]
      (let [convo-id (create-ok! key-a "tenant-a")]
        (doseq [[label k] [["a key granted ONE tenant" key-a]
                           ["a key granted TWO tenants" key-ab]
                           ["a SUPERUSER key (no scopes)" key-none]]]
          (testing label
            (is (= 400 (:status (create! k nil))) (str label ": created a conversation without naming a tenant"))
            (is (= 400 (:status (routes/get-conversation-handler (on k convo-id)))) (str label ": READ by conversation id alone"))
            (is (= 400 (:status (routes/update-conversation-handler (on k convo-id {:body {:title "x"}}))))
                (str label ": RENAMED by conversation id alone"))
            (is (= 400 (:status (listed-ids k))) (str label ": LISTED without naming a tenant"))
            (is (= 400 (:status (routes/delete-conversation-handler (on k convo-id)))) (str label ": DELETED by conversation id alone"))))
        (is (some? (db/conversation-by-id @conn convo-id)) "a conversation was deleted by id alone")
        (is (= "the owner's conversation" (topic conn convo-id)) "a conversation was renamed by id alone")))))

;; ---------------------------------------------------------------------------
;; Another customer's key, at each REST door, against the owner at the same door.
;; ---------------------------------------------------------------------------

(deftest another-tenants-key-cannot-read-rename-delete-or-list-the-conversation
  (with-store
    (fn [conn]
      (let [convo-id (create-ok! key-b "tenant-b")]
        (testing "PREMISE: the conversation exists, created through key B for the shared end user"
          (is (= "the owner's conversation" (topic conn convo-id))))
        (is (= 404 (:status (routes/get-conversation-handler (on key-a convo-id {:tenant "tenant-a"}))))
            "key A READ a conversation created through key B")
        (is (= 404 (:status (routes/update-conversation-handler (on key-a convo-id {:tenant "tenant-a" :body {:title "renamed by A"}}))))
            "key A RENAMED a conversation created through key B")
        (is (= "the owner's conversation" (topic conn convo-id))
            "key A's rename was written to key B's conversation")
        (is (not (contains? (:ids (listed-ids key-a "tenant-a")) convo-id))
            "key A's listing ENUMERATED a conversation created through key B")
        (is (= 404 (:status (routes/delete-conversation-handler (on key-a convo-id {:tenant "tenant-a"}))))
            "key A DELETED a conversation created through key B")
        (is (some? (db/conversation-by-id @conn convo-id))
            "key A's delete removed key B's conversation")
        (is (= 403 (:status (routes/get-conversation-handler (on key-a convo-id {:tenant "tenant-b"}))))
            "key A NAMED key B's tenant and was not refused")))))

(deftest the-owning-key-still-reads-renames-lists-and-deletes-it
  (with-store
    (fn [conn]
      (let [convo-id (create-ok! key-b "tenant-b")]
        (is (= "tenant-b" (:conversation/tenant (d/pull @conn [:conversation/tenant] [:conversation/id convo-id])))
            "the conversation was not bound to the tenant the request named")
        (is (= 200 (:status (routes/get-conversation-handler (on key-b convo-id {:tenant "tenant-b"}))))
            "the owning key could not read its own conversation")
        (is (= 200 (:status (routes/update-conversation-handler (on key-b convo-id {:tenant "tenant-b" :body {:title "renamed by B"}}))))
            "the owning key could not rename its own conversation")
        (is (= "renamed by B" (topic conn convo-id)))
        (is (contains? (:ids (listed-ids key-b "tenant-b")) convo-id)
            "the owning key's listing lost its own conversation")
        (is (= 200 (:status (routes/delete-conversation-handler (on key-b convo-id {:tenant "tenant-b"}))))
            "the owning key could not delete its own conversation")
        (is (nil? (db/conversation-by-id @conn convo-id)))))))

;; ---------------------------------------------------------------------------
;; A key granted TWO tenants acts in the one it names. The union of its tenants
;; would quietly re-open the hole for exactly these keys.
;; ---------------------------------------------------------------------------

(deftest a-key-with-several-tenants-is-scoped-to-the-one-it-names
  (with-store
    (fn [conn]
      (is (= 403 (:status (create! key-ab "tenant-c")))
          "a key created a conversation in a tenant it is not granted")
      (let [convo-id (create-ok! key-ab "tenant-a")]
        (is (= "tenant-a" (:conversation/tenant (d/pull @conn [:conversation/tenant] [:conversation/id convo-id])))
            "the conversation was not bound to the tenant the request named")
        (is (= 200 (:status (routes/get-conversation-handler (on key-ab convo-id {:tenant "tenant-a"}))))
            "the key could not read its conversation in the tenant it named")
        (is (= 404 (:status (routes/get-conversation-handler (on key-ab convo-id {:tenant "tenant-b"}))))
            "naming tenant-b reached a tenant-a conversation: the UNION of the key's tenants was used")
        (is (not (contains? (:ids (listed-ids key-ab "tenant-b")) convo-id))
            "the tenant-b listing ENUMERATED a tenant-a conversation")))))

;; ---------------------------------------------------------------------------
;; a key MARKED all-tenant keeps the power a scopeless key used to have
;; by allocation; its addressing is constrained: it names a tenant, and reaches
;; only that tenant. A key with no grant and no marker reaches none.
;; ---------------------------------------------------------------------------

(deftest a-superuser-key-reaches-any-tenant-it-names-and-only-that-one
  (with-store
    (fn [conn]
      (let [convo-id (create-ok! key-b "tenant-b")
            su-id (create-ok! key-all "tenant-c")]
        (is (= 403 (:status (routes/get-conversation-handler (on key-none convo-id {:tenant "tenant-b"}))))
            "a key with no grant and no marker reaches no tenant")
        (is (= "tenant-c" (:conversation/tenant (d/pull @conn [:conversation/tenant] [:conversation/id su-id])))
            "the superuser's conversation was not bound to the tenant it named")
        (is (= 200 (:status (routes/get-conversation-handler (on key-all convo-id {:tenant "tenant-b"}))))
            "the superuser's POWER changed: naming the conversation's tenant did not reach it")
        (is (= 404 (:status (routes/get-conversation-handler (on key-all convo-id {:tenant "tenant-a"}))))
            "the superuser reached a tenant-b conversation while naming tenant-a: the pair was not the address")
        (is (not (contains? (:ids (listed-ids key-all "tenant-a")) convo-id))
            "the superuser's tenant-a listing ENUMERATED a tenant-b conversation")))))

(deftest a-conversation-created-before-the-fix-is-unreachable-through-the-api-but-kept
  (testing "no backfill: a thread written without a tenant is reachable through NO public door, and is not deleted"
    (with-store
      (fn [conn]
        (let [{convo-id :conversation-id} (db/transact-new-msg-thread conn agent-id end-user)]
          (doseq [[k t] [[key-a "tenant-a"] [key-all "tenant-a"]]]
            (is (= 404 (:status (routes/get-conversation-handler (on k convo-id {:tenant t}))))
                "an unbound conversation was readable through the public API")
            (is (not (contains? (:ids (listed-ids k t)) convo-id))
                "an unbound conversation was listed through the public API"))
          (is (some? (db/conversation-by-id @conn convo-id))
              "the unbound conversation is gone; admins must still see it"))))))

(deftest the-scoped-lookup-matches-nothing-for-a-nil-or-blank-tenant
  (testing "a nil datalog :in is unbound and would match every row, so it must be refused first"
    (with-store
      (fn [conn]
        (let [convo-id (create-ok! key-b "tenant-b")]
          (is (some? (db/conversation-in-tenant @conn convo-id "tenant-b")) "PREMISE: the lookup finds it in its tenant")
          (is (nil? (db/conversation-in-tenant @conn convo-id nil)) "a NIL tenant matched a conversation")
          (is (nil? (db/conversation-in-tenant @conn convo-id "  ")) "a BLANK tenant matched a conversation")
          (is (empty? (db/conversations-by-user-in-tenant @conn nil end-user nil))
              "a NIL tenant listed conversations"))))))

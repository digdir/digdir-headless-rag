(ns digdir.data.db
  (:require [nano-id.core :refer [nano-id]]
            [clojure.edn :as edn]
            ;; [hyperfiddle.electric :as e]
            #?(:clj [datahike.api :as d])
            #?(:clj [datahike-jdbc.core])
            #?(:clj [digdir.config.core :as config]))
  #?(:clj (:import [java.security MessageDigest])))

;; Configuration is now handled by digdir.config.core namespace

(def migration-chunk
  ;; Data used by query
  [{:db/ident :message/keyword
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
   {:db/ident :message/chunks
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many}

   {:db/ident :chunk/doc-num
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :chunk/doc-title
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :chunk/chunk-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :chunk/content-markdown
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :chunk/url
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :chunk/sha
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(def migration-conversation-user
  [{:db/ident :conversation/user-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

;; Migration for user permissions (references permission entities from config schema)
(def migration-user-permissions
  [{:db/ident :user/permissions
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "References to permission entities granted to this user"}])

;; API Key schema (migrated from Datalevin)
(def api-key-schema
  [{:db/ident :api-key/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Unique identifier for the API key"}
   {:db/ident :api-key/key
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "The API key value (plaintext)"}
   {:db/ident :api-key/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Descriptive name for the API key"}
   {:db/ident :api-key/created
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when the API key was created (epoch ms)"}
   {:db/ident :api-key/created-by
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "User ID of the person who created the API key"}
   {:db/ident :api-key/revoked
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "Whether the API key has been revoked"}
   {:db/ident :api-key/last-used
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp of the last time the API key was used"}
   {:db/ident :api-key/entity-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "DEPRECATED: Use :api-key/entities instead. Single entity ID for backwards compatibility."}
   {:db/ident :api-key/tenants
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc "Tenant IDs that this API key has access to"}
   {:db/ident :api-key/environments
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc "Environments that this API key has access to (e.g., 'prod', 'test', 'dev')"}
   {:db/ident :api-key/entities
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc "Entity IDs that this API key has access to"}
   {:db/ident :api-key/scopes
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many
    :db/doc "Scopes granted to this API key: :query, :ingest, :admin"}
   {:db/ident :api-key/usage-count
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Number of times this API key has been used"}
   {:db/ident :api-key/expires-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Optional expiration timestamp for the API key"}])

;; Migration for multi-message playground chat with branching support
(def migration-playground-chat
  [;; Branching support - reference to parent message
   {:db/ident :message/parent-message
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to parent message for branching conversations"}

   ;; Branch ordering among siblings
   {:db/ident :message/branch-index
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Index of this branch among siblings at the same level"}

   ;; Per-message config (EDN-encoded)
   {:db/ident :message/config
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "EDN-encoded config map used for this message"}

   ;; Diagnostics storage (EDN-encoded pipeline results)
   {:db/ident :message/diagnostics
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "EDN-encoded diagnostics from RAG pipeline execution"}

   ;; Link to execution ID
   {:db/ident :message/execution-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Execution ID linking to in-memory execution state"}

   ;; Conversation type for filtering playground vs regular chat
   {:db/ident :conversation/type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Type of conversation: :playground, :chat, etc."}

   ;; Tenant for playground conversations (stores config resolution context)
   {:db/ident :conversation/tenant
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Tenant ID for config resolution in playground conversations"}

   ;; Environment for playground conversations (stores config resolution context)
   {:db/ident :conversation/environment
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Environment for config resolution in playground conversations"}])

(def dh-schema
  (concat
   [;; Folder
    {:db/ident :folder/id
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one
     :db/unique :db.unique/identity}
    {:db/ident :folder/name
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}

    ;; prompt.folder
    {:db/ident :prompt.folder/id
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one
     :db/unique :db.unique/identity}
    {:db/ident :prompt.folder/name
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}

    ;; Prompt
    {:db/ident :prompt/id
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one
     :db/unique :db.unique/identity}

    ;; Conversation
    {:db/ident :conversation/id
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one
     :db/unique :db.unique/identity}
    {:db/ident :conversation/topic
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :conversation/messages
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/many}

    ;; Text message
    {:db/ident :message/id
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one
     :db/unique :db.unique/identity}
    {:db/ident :message/text
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :message/completion
     :db/valueType :db.type/boolean
     :db/cardinality :db.cardinality/one}

    ;; Filter message
    {:db/ident :message/id
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one
     :db/unique :db.unique/identity}
    {:db/ident :message.filter/value
     :db/valueType :db.type/string ;; edn - clojure.edn/read-string
     :db/cardinality :db.cardinality/one}

    {:db/ident :active-key-name
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :key/value
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}
    {:db/ident :user/id
     :db/valueType :db.type/string
     :db/unique :db.unique/identity
     :db/cardinality :db.cardinality/one}
    {:db/ident :user/email
     :db/valueType :db.type/string
     :db/unique :db.unique/identity
     :db/cardinality :db.cardinality/one}]
   migration-chunk
   migration-conversation-user
   migration-playground-chat
   api-key-schema))

(comment
  ;; migrate db:
  (d/transact (get-conn) {:tx-data migration-conversation-user})
  ;; migrate for playground chat with branching:
  (d/transact (get-conn) {:tx-data migration-playground-chat})
  ;; migrate for user permissions:
  (d/transact (get-conn) {:tx-data migration-user-permissions})
  ;; migrate for API keys (from Datalevin):
  (d/transact (get-conn) {:tx-data api-key-schema}))

#?(:clj
   (defn init-db []
     (if-let [bootstrap @config/!bootstrap-config]
       (if-let [cfg (get-in bootstrap [:db (:db-env bootstrap)])]
         (do
           (when-not (d/database-exists? cfg)
             (d/create-database cfg)
             (let [conn (d/connect cfg)]
               (d/transact conn {:tx-data dh-schema})))
           (d/connect cfg))
         (println (str "no db config loaded for env: " (:db-env bootstrap))))
       (println (str "no bootstrap config loaded, skipping init-db")))))

#?(:clj
   (defonce ^:private delayed-connection (delay (init-db))))

(defn get-conn [] @delayed-connection)

;; (e/def db) ; injected database ref; Electric defs are always dynamic
;; (e/def auth-conn)

;; Queries

(defn T
  "For debugging
  Input → ___ → Output
           |
           |
           ↓
        Console"
  ([x]
   (prn x)
   x)
  ([tag x]
   (prn tag x)
   x))

#?(:clj
   (defn fetch-convo-messages-mapped
     [dh-conn convo-id]
      (->> (d/q '[:find (pull ?msg [* {:message/chunks [*]}])
                  :in $ ?convo-id
                  :where
                  [?c :conversation/id ?convo-id]
                  [?c :conversation/messages ?msg]]
                dh-conn
                convo-id)
           (map first)
           (map #(update % :message.filter/value edn/read-string))

           (sort-by :message/created <)
           #_T)))


#?(:clj
   (defn fetch-convo-entity-id [db convo-id]
     (d/q '[:find [?entity-id]
            :in $ ?conv-id
            :where
            [?e :conversation/id ?conv-id]
            [?e :conversation/entity-id ?entity-id]]
          db convo-id)))

#?(:clj
   (defn fetch-user-id [user-email]
     (:user/id (d/pull (get-conn) '[:user/id] [:user/email user-email]))))

#?(:clj
   (defn conversations [db]
     (sort-by #(or (:conversation/created %) 0) >
              (d/q '[:find [(pull ?e [:db/id :conversation/id :conversation/topic :conversation/created :conversation/entity-id :conversation/user-id]) ...]
                     :where
                     [?e :conversation/id ?conv-id]
                     (not [?e :conversation/folder])]
                   db))))

#?(:clj
   (defn conversations-paginated
     "Returns paginated conversations for all users.
      page-size: number of conversations per page
      page-index: 0-based page index
      Returns {:conversations [...] :total count :page-size n :page-index n}"
     [db page-size page-index]
     (let [all-convos (conversations db)
           total (count all-convos)
           start (* page-size page-index)
           end (min (+ start page-size) total)
           page-convos (if (< start total)
                         (subvec (vec all-convos) start end)
                         [])]
       {:conversations page-convos
        :total total
        :page-size page-size
        :page-index page-index})))

#?(:clj
   (defn conversations-by-user [db user-id]
     (sort-by #(or (:conversation/created %) 0) >
              (d/q '[:find [(pull ?e [:db/id :conversation/id :conversation/topic :conversation/created :conversation/entity-id :conversation/user-id]) ...]
                     :in $ ?user-id
                     :where
                     [?e :conversation/id ?conv-id]
                     (not [?e :conversation/folder])
                     [?e :conversation/user-id ?user-id]]
                   db user-id))))

#?(:clj
   (defn conversations-by-user-paginated
     "Returns paginated conversations for a specific user.
      page-size: number of conversations per page
      page-index: 0-based page index
      Returns {:conversations [...] :total count :page-size n :page-index n}"
     [db user-id page-size page-index]
     (let [all-convos (conversations-by-user db user-id)
           total (count all-convos)
           start (* page-size page-index)
           end (min (+ start page-size) total)
           page-convos (if (< start total)
                         (subvec (vec all-convos) start end)
                         [])]
       {:conversations page-convos
        :total total
        :page-size page-size
        :page-index page-index})))

#?(:clj
   (defn conversations-by-other-or-unknown-user [db user-id]
     (sort-by #(or (:conversation/created %) 0) >
              (d/q '[:find [(pull ?e [:db/id :conversation/id :conversation/topic :conversation/created :conversation/entity-id :conversation/user-id]) ...]
                     :in $ ?user-id
                     :where
                     [?e :conversation/id ?conv-id]
                     (not [?e :conversation/folder])
                     (not [?e :conversation/user-id ?user-id])]
                   db user-id))))

#?(:clj
   (defn orphan-conversations [db]
     (sort-by #(or (:conversation/created %) 0) >
              (d/q '[:find [(pull ?e [:db/id :conversation/id :conversation/topic :conversation/created :conversation/entity-id :conversation/user-id]) ...]
                     :where
                     [?e :conversation/id ?conv-id]
                     (not [?e :conversation/folder])
                     [(missing? $ ?e :conversation/user-id)]]
                   db))))

#?(:clj
     #?(:clj
     (defn empty-threads-older-than-30-min 
       "Returns all conversations that have exactly 2 messages (system + filter) and were created more than 30 minutes ago"
       [db]
       (let [thirty-min-ago (- (System/currentTimeMillis) (* 60 1000))]
         (->> (d/q '[:find ?conv (count ?msg)
                     :in $ ?cutoff-time
                     :where
                     [?conv :conversation/id]
                     [?conv :conversation/created ?created]
                     [(< ?created ?cutoff-time)]
                     [?conv :conversation/messages ?msg]]
                   db thirty-min-ago)
              (filter #(= (second %) 2))
              (map first)
              (map #(d/pull db [:conversation/id 
                                :conversation/topic 
                                :conversation/created 
                                :conversation/entity-id 
                                :conversation/user-id] %)))))))

#?(:clj
   (defn conversation-by-id [db convo-id]
     (d/q '[:find (pull ?e [:db/id :conversation/id :conversation/topic :conversation/created :conversation/entity-id :conversation/user-id]) .
            :in $ ?conv-id
            :where
            [?e :conversation/id ?conv-id]]
          db convo-id))) 

#?(:clj
   (defn empty-threads-older-than-30-min 
     "Returns entity IDs of all conversations that have exactly 2 messages (system + filter) and were created more than 30 minutes ago"
     [db]
     (let [thirty-min-ago (- (System/currentTimeMillis) (* 30 60 1000))]
       (->> (d/q '[:find ?conv (count ?msg)
                   :in $ ?cutoff-time
                   :where
                   [?conv :conversation/id]
                   [?conv :conversation/created ?created]
                   [(< ?created ?cutoff-time)]
                   [?conv :conversation/messages ?msg]]
                 db thirty-min-ago)
            (filter #(= (second %) 2))
            (map first)))))

#?(:clj
     (defn delete-empty-threads-older-than-30-min 
       "Deletes all conversations that have exactly 2 messages (system + filter) and were created more than 30 minutes ago.
      Returns the number of deleted threads."
       [conn]
       (let [db @conn
             thread-eids (empty-threads-older-than-30-min db)]
         (when (seq thread-eids)
           (d/transact conn (mapv (fn [eid] [:db/retractEntity eid]) thread-eids)))
         (count thread-eids))))

#?(:clj
   (defn conversations-in-folder [db folder-id]
     (sort-by first > (d/q '[:find ?created ?c ?c-id ?topic ?folder-name ?entity-id
                             :in $ ?folder-id
                             :where
                             [?e :folder/id ?folder-id]
                             [?e :folder/name ?folder-name]
                             [?c :conversation/folder ?folder-id]
                             [?c :conversation/id ?c-id]
                             [?c :conversation/topic ?topic]
                             [?c :conversation/created ?created]
                             [?c :conversation/entity-id ?entity-id]]
                           db folder-id))))

#?(:clj
   (defn folders [db]
     (sort-by first > (d/q '[:find ?created ?e ?folder-id ?name
                             :where
                             [?e :folder/id ?folder-id]
                             [?e :folder/name ?name]
                             [?e :folder/created ?created]]
                           db))))

#?(:clj
   (defn get-conversation-chunks [db convo-id]
     (->> (d/q '[:find [(pull ?chunk [*]) ...]
                 :in $ ?convo-id
                 :where
                 [?c :conversation/id ?convo-id]
                 [?c :conversation/messages ?msg]
                 [?msg :message/chunks ?chunk]]
               db convo-id)
          (map #(assoc % :db/id (:db/id %)))
          (distinct)
          (sort-by :chunk/doc-title))))
;;

;; Transactions
#?(:clj
   (defn transact-user-msg [conn convo-id user-query]
     (let [time-point (System/currentTimeMillis)
           tx-data {:conversation/id convo-id
                    :conversation/messages [{:message/id (nano-id)
                                              :message/text user-query
                                              :message/role :user
                                              :message/voice :user
                                              :message/completion true
                                              :message/kind :kind/markdown
                                              :message/created time-point}]}
           _ (prn "transact-user-msg called for query: " user-query)]
       (d/transact conn [tx-data]))))

#?(:clj
   (defn transact-assistant-msg [conn convo-id msg]
     (let [id (nano-id)]
       (d/transact conn [{:conversation/id convo-id
                          :conversation/messages [{:message/id id
                                                   :message/text msg
                                                   :message/role :assistant
                                                   :message/voice :assistant
                                                   :message/completion true
                                                   :message/kind :kind/markdown
                                                   :message/created (System/currentTimeMillis)}]}])
       {:message/id id})))

#?(:clj
   (defn sha256 [s]
     (let [digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
       (apply str (map #(format "%02x" (bit-and % 0xff)) digest)))))

#?(:clj
   (defn transact-used-data [conn msg-id keywords doc-collection-name chunks]
     (let [non-nil-chunks (remove nil? chunks)
           dh-chunks (doall
                      (for [c non-nil-chunks]
                        (let [chunk-map {:chunk/doc-num (:doc_num c)
                                         :chunk/doc-title (get-in c [(keyword doc-collection-name) :title])
                                         :chunk/chunk-id (:chunk_id c)
                                         :chunk/content-markdown (:content_markdown c)
                                         :chunk/url (get-in c [(keyword doc-collection-name) :url])
                                         :chunk/metadata (pr-str (:metadata c))
                                         :chunk/sha (sha256 (:content_markdown c))}]
                          ;; Remove nil values - Datahike doesn't allow storing nil
                          (into {} (remove (comp nil? val) chunk-map)))))]
       (d/transact conn [{:message/id msg-id
                          :message/keywords keywords
                          :message/chunks dh-chunks}]))))

(defn get-chunk [conn chunk-id]
  (d/pull conn '[*] chunk-id))

(defn create-folder [conn]
  (d/transact conn [{:folder/id (nano-id)
                     :folder/name "New folder"
                     :folder/created (System/currentTimeMillis)}]))

(defn rename-convo-topic [conn convo-id new-topic]
  (d/transact conn [{:db/id [:conversation/id convo-id]
                     :conversation/topic new-topic}]))

(defn rename-folder [conn folder-id new-folder-name]
  (d/transact conn [{:db/id [:folder/id folder-id]
                     :folder/name new-folder-name}]))

(defn delete-convo [conn convo-eid]
  (d/transact conn [[:db/retract convo-eid :conversation/id]])) ; TODO: develop consistency of id and eid usage

(defn delete-folder [conn folder-eid]
  (d/transact conn [[:db.fn/retractEntity folder-eid]]))

(defn clear-all-conversations [conn]
  (let [convo-eids (map :e (d/datoms @conn :avet :conversation/id))
        folder-eids (map :e (d/datoms @conn :avet :folder/id))
        m-eids  (set (map first (d/q '[:find ?m
                                       :in $ [?convo-id ...]
                                       :where
                                       [?convo-id :conversation/messages ?m]] @conn convo-eids)))
        retraction-ops (concat
                        (mapv (fn [eid] [:db.fn/retractEntity eid :conversation/id]) convo-eids)
                        (mapv (fn [eid] [:db.fn/retractEntity eid :folder/id]) m-eids)
                        (mapv (fn [eid] [:db.fn/retractEntity eid :folder/id]) folder-eids))]
    (d/transact conn retraction-ops)))

#?(:clj
   (defn transact-new-msg-thread [conn entity-id user-id & [filter-value]]
     (let [convo-id (nano-id)
           time-point (System/currentTimeMillis)
           system-message {:message/id (nano-id)
                           :message/text "You are a helpful assistant."
                           :message/role :system
                           :message/voice :agent
                           :message/completion true
                           :message/kind :kind/text
                           :message/created time-point}
           filter-message (when filter-value
                            {:message/id (nano-id)
                             :message/voice :filter
                             :message/created (inc time-point)
                             :message/completion false
                             :message.filter/value (pr-str filter-value)})
           messages (if filter-message
                      [system-message filter-message]
                      [system-message])
           tx-data
           {:conversation/id convo-id
            :conversation/user-id user-id
            :conversation/entity-id entity-id
            :conversation/topic "Ny tråd"
            :conversation/created time-point
            :conversation/system-prompt "sys-prompt"
            :conversation/messages messages}
           _ (prn "transact-new-msg-thread called" )]
       (d/transact conn [tx-data])
       {:conversation-id convo-id})))


#?(:clj
   (defn set-message-filter [conn id new-message-filter]
     (prn [new-message-filter])
     (d/transact conn [{:db/id id
                        :message.filter/value (pr-str new-message-filter)}])
     nil))

;; NOTE: update-config function removed - entity config now managed via
;; digdir.config.accessor and digdir.config.db namespaces

;; =========Playground Chat Functions=========

#?(:clj
   (defn create-playground-conversation
     "Create a new playground conversation.
      Args:
        conn - Database connection
        entity-id - Entity ID for the conversation
        opts - Optional map with :user-id, :tenant, :environment"
     ([conn entity-id]
      (create-playground-conversation conn entity-id nil))
     ([conn entity-id opts]
      (let [convo-id (nano-id)
            time-point (System/currentTimeMillis)
            {:keys [user-id tenant environment]} (if (string? opts)
                                                   {:user-id opts} ; backwards compat
                                                   opts)
            base-tx (cond-> {:conversation/id convo-id
                             :conversation/entity-id entity-id
                             :conversation/type :playground
                             :conversation/topic "Playground Session"
                             :conversation/created time-point}
                      user-id (assoc :conversation/user-id user-id)
                      tenant (assoc :conversation/tenant tenant)
                      environment (assoc :conversation/environment environment))]
        (d/transact conn [base-tx])
        {:conversation-id convo-id}))))

#?(:clj
   (defn transact-playground-user-msg
     "Create user message with config and optional parent for branching.
      Returns the created message ID."
     [conn convo-id text config parent-msg-id branch-index]
     (let [msg-id (nano-id)
           time-point (System/currentTimeMillis)
           base-msg {:message/id msg-id
                     :message/text text
                     :message/role :user
                     :message/voice :user
                     :message/completion true
                     :message/kind :kind/markdown
                     :message/created time-point
                     :message/config (pr-str config)}
           with-parent (if parent-msg-id
                         (assoc base-msg
                                :message/parent-message [:message/id parent-msg-id]
                                :message/branch-index (or branch-index 0))
                         base-msg)]
       (d/transact conn [{:conversation/id convo-id
                          :conversation/messages [with-parent]}])
       {:message/id msg-id})))

#?(:clj
   (defn transact-playground-assistant-msg
     "Create assistant message with diagnostics and execution reference.
      Returns the created message ID."
     [conn convo-id text diagnostics execution-id parent-msg-id branch-index]
     (let [msg-id (nano-id)
           time-point (System/currentTimeMillis)
           base-msg {:message/id msg-id
                     :message/text text
                     :message/role :assistant
                     :message/voice :assistant
                     :message/completion true
                     :message/kind :kind/markdown
                     :message/created time-point
                     :message/diagnostics (pr-str diagnostics)
                     :message/execution-id execution-id}
           with-parent (if parent-msg-id
                         (assoc base-msg
                                :message/parent-message [:message/id parent-msg-id]
                                :message/branch-index (or branch-index 0))
                         base-msg)]
       (d/transact conn [{:conversation/id convo-id
                          :conversation/messages [with-parent]}])
       {:message/id msg-id})))

#?(:clj
   (defn fetch-conversation-tree
     "Fetch all messages for a conversation, including parent references.
      Returns messages sorted by creation time."
     [db convo-id]
     (->> (d/q '[:find [(pull ?msg [* {:message/parent-message [:message/id]}
                                   {:message/chunks [*]}]) ...]
                 :in $ ?convo-id
                 :where
                 [?c :conversation/id ?convo-id]
                 [?c :conversation/messages ?msg]]
               db convo-id)
          (sort-by :message/created))))

#?(:clj
   (defn get-message-lineage
     "Get all ancestors of a message (for building conversation context).
      Returns messages from root to the specified message, in chronological order."
     [db msg-id]
     (loop [current-id msg-id
            lineage []]
       (if-not current-id
         (reverse lineage)
         (let [msg (d/pull db '[* {:message/parent-message [:message/id]}]
                           [:message/id current-id])]
           (if msg
             (recur (get-in msg [:message/parent-message :message/id])
                    (conj lineage msg))
             (reverse lineage)))))))

#?(:clj
   (defn get-branch-siblings
     "Get all messages that share the same parent (siblings in a branch).
      Useful for finding alternative branches at a given point."
     [db parent-msg-id]
     (if parent-msg-id
       (d/q '[:find [(pull ?msg [:message/id :message/branch-index :message/role
                                 :message/text :message/created]) ...]
              :in $ ?parent-id
              :where
              [?parent :message/id ?parent-id]
              [?msg :message/parent-message ?parent]]
            db parent-msg-id)
       ;; Root messages (no parent) - find all in conversation with no parent
       [])))

#?(:clj
   (defn playground-conversations
     "Get all playground conversations, sorted by creation time (newest first)."
     [db]
     (sort-by #(or (:conversation/created %) 0) >
              (d/q '[:find [(pull ?e [:db/id :conversation/id :conversation/topic
                                      :conversation/created :conversation/entity-id
                                      :conversation/user-id :conversation/type
                                      :conversation/tenant :conversation/environment]) ...]
                     :where
                     [?e :conversation/id ?conv-id]
                     [?e :conversation/type :playground]]
                   db))))

#?(:clj
   (defn playground-conversations-by-user
     "Get playground conversations for a specific user."
     [db user-id]
     (sort-by #(or (:conversation/created %) 0) >
              (d/q '[:find [(pull ?e [:db/id :conversation/id :conversation/topic
                                      :conversation/created :conversation/entity-id
                                      :conversation/user-id :conversation/type
                                      :conversation/tenant :conversation/environment]) ...]
                     :in $ ?user-id
                     :where
                     [?e :conversation/id ?conv-id]
                     [?e :conversation/type :playground]
                     [?e :conversation/user-id ?user-id]]
                   db user-id))))

#?(:clj
   (defn count-branch-children
     "Count how many child messages a given message has (for detecting branch points)."
     [db msg-id]
     (count (d/q '[:find ?child
                   :in $ ?parent-id
                   :where
                   [?parent :message/id ?parent-id]
                   [?child :message/parent-message ?parent]]
                 db msg-id))))

#?(:clj
   (defn delete-playground-conversation
     "Delete a playground conversation and all its messages by conversation ID."
     [conn convo-id]
     (let [db @conn
           ;; Find conversation entity
           convo-eid (d/q '[:find ?e .
                           :in $ ?convo-id
                           :where [?e :conversation/id ?convo-id]]
                         db convo-id)]
       (when convo-eid
         ;; Find all message entities for this conversation
         (let [msg-eids (d/q '[:find [?m ...]
                              :in $ ?convo-id
                              :where
                              [?c :conversation/id ?convo-id]
                              [?c :conversation/messages ?m]]
                            db convo-id)
               ;; Retract messages first, then conversation
               retract-ops (concat
                            (mapv (fn [eid] [:db.fn/retractEntity eid]) msg-eids)
                            [[:db.fn/retractEntity convo-eid]])]
           (d/transact conn retract-ops))))))

#?(:clj
   (defn clear-all-playground-conversations
     "Delete all playground conversations and their messages."
     [conn]
     (let [db @conn
           ;; Find all playground conversation IDs
           convo-ids (d/q '[:find [?convo-id ...]
                          :where
                          [?e :conversation/id ?convo-id]
                          [?e :conversation/type :playground]]
                        db)]
       (doseq [convo-id convo-ids]
         (delete-playground-conversation conn convo-id)))))

(comment

  ;; Helpers

  (defn ->eid-action [data action-fn]
    (let [eid (second (first data))]
      (when eid
        (action-fn eid))))

  (defn ->id-action [data action-fn]
    (let [eid (nth (first data) 2)]
      (when eid
        (action-fn eid))))

  ;; Creation

  ;Create folder
  (create-folder conn)

  ; Existing conversations
  ; Ensure that the convo-id exists in the db
  (transact-user-msg {:convo-id "test-2"
                      :msg "hello world"})
  ; New conversation
  (transact-user-msg {:new-convo? true
                      :convo-id (nano-id)
                      :msg "test message"})

  ; New conversation
  (transact-new-msg-thread conn {:convo-id (nano-id)
                                 :user-query "hvilke kategorier gjelder for KI system risiko?"
                                 :entity-id "7i8dadbe-0101-f0e1-92b8-40b10a61cdcd" ;; AI Guide
                                 })


;; Queries
  (conversations @conn)
  (folders @conn)

;; Update

  ; rename conversation topic
  (-> (conversations @conn)
      (->id-action #(rename-convo-topic conn % "new convo name")))

  ; rename folder
  (-> (folders @conn)
      (->id-action #(rename-folder conn % "Updated folder name")))

  ;; Deletions

  (-> (conversations @conn)
      (->eid-action #(delete-convo conn %)))

  (-> (folders @conn)
      (->eid-action #(delete-convo conn %)))

  (clear-all-conversations conn)

;; TODO
  ;(conversations-in-folder @conn )

;; Database migration scripts
  (require '[datahike.migrate :refer [export-db import-db]])

  (def local-cfg (get-in (config) [:db :local]))
  (def remote-cfg (get-in (config) [:db :remote]))

  (def create-local-db (when-not (d/database-exists? local-cfg) (d/create-database local-cfg)))

  (def local-conn (d/connect local-cfg))
  (def remote-conn (d/connect remote-cfg))

  (conversations @local-conn)
  (conversations @remote-conn)

  (export-db remote-conn "/tmp/eavt-dump")
  (import-db local-conn "/tmp/eavt-dump")

;; End comment
  )

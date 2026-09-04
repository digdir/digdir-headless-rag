(ns digdir.data.db
  (:require #?(:clj [nano-id.core :refer [nano-id]])
            [clojure.edn :as edn]
            [clojure.string :as str]
            ;; [hyperfiddle.electric :as e]
            #?(:clj [datahike.api :as d])
            #?(:clj [datahike-jdbc.core])
            #?(:clj [digdir.config.core :as config])
            #?(:clj [digdir.config.schema :as config-schema])
            #?(:clj [taoensso.timbre :as timbre]))
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

(def migration-conversation-folder
  [{:db/ident :conversation/folder
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to the folder that contains this conversation"}])

(def migration-conversation-tags
  [{:db/ident :conversation/tags
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc "Free-form user-defined tags for a conversation"}])

(def migration-message-tags
  [{:db/ident :message/tags
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc "Free-form user-defined tags for a message"}])

(def ^:private conversation-pull-attrs
  [:db/id
   :conversation/id
   :conversation/topic
   :conversation/created
   :conversation/agent-id
   :conversation/user-id
   :conversation/folder
   :conversation/tags])

(def ^:private playground-conversation-pull-attrs
  (into conversation-pull-attrs
        [:conversation/type
         :conversation/tenant
         :conversation/dataset-config-key
         :conversation/skill-graph-id
         :conversation/view-mode]))

#?(:clj
   (defn conversation-dataset-config-key
     [conversation]
     (:conversation/dataset-config-key conversation)))

#?(:clj
   (defn- normalize-conversation-record
     [conversation]
     conversation))

;; Migration for user permissions (references permission entities from config schema)
(def migration-user-permissions
  [{:db/ident :user/permissions
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "References to permission entities granted to this user"}])

(def migration-user-preferred-language
  [{:db/ident :user/preferred-language
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Preferred language code for the user interface and auth emails"}])

;; API Key and Access Policy schema (migrated from Datalevin)
(def access-policy-schema
  [{:db/ident :access-policy/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Unique identifier for the access policy"}
   {:db/ident :access-policy/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Descriptive name for the policy"}
   {:db/ident :access-policy/created
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when the policy was created (epoch ms)"}
   {:db/ident :access-policy/created-by
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "User ID of the person who created the policy"}
   {:db/ident :access-policy/tenants
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc "Tenant IDs that this policy has access to"}
   {:db/ident :access-policy/clients
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc "Client IDs that this policy has access to"}
   {:db/ident :access-policy/dataset-scopes
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "Explicit dataset scopes that this policy grants access to"}
   {:db/ident :access-policy/agent-refs
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "Explicit agent grants for this policy"}
   {:db/ident :access-policy/skill-graphs
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc "Skill graph IDs that this policy can execute"}
   {:db/ident :access-policy/scopes
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many
    :db/doc "Scopes granted to this policy: :query, :ingest, :admin"}
   {:db/ident :access-policy/config-ceilings
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "Tenant/root ceiling grants attached to this policy"}
   {:db/ident :access-policy/allowed-config-keys
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "Tenant/root allowed-config-key grants attached to this policy"}])

(def confirmation-code-schema
  "Admin-login confirmation codes (#63).

   These used to live in a process-global atom, so every deploy and every
   crash silently invalidated every outstanding code — and the resulting
   failure was byte-for-byte identical to a genuinely wrong code, measured in
   `docs/confirmation-code-lifetime.md`. Persisting them removes the restart
   case entirely rather than merely diagnosing it, and with the :jdbc backend
   it also makes a code minted on one instance valid on another.

   Keyed by email as :db.unique/identity, so requesting a new code upserts
   over the outstanding one — one code in flight per address, which is what
   the atom did too.

   Expired entries are deliberately RETAINED for a grace window rather than
   deleted at expiry: a record that outlives its own validity is the only
   thing that lets the server tell `expired` from `wrong` from `never asked`.
   `digdir.auth.core/purge-stale-confirmation-codes!` sweeps them after that."
  [{:db/ident :confirmation-code/email
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Address the code was minted for; one outstanding code per address"}
   {:db/ident :confirmation-code/code
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Legacy plaintext confirmation code; no longer written"}
   {:db/ident :confirmation-code/code-digest
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "HMAC-SHA-256 of the email-bound confirmation code"}
   {:db/ident :confirmation-code/created-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Epoch millis the code was minted"}
   {:db/ident :confirmation-code/expires-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Epoch millis after which the code no longer validates"}
   {:db/ident :confirmation-code/failed-attempts
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Wrong guesses against this code; the row is retracted at the limit (#211)"}])

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
    :db/doc "Deprecated legacy plaintext value; removed during key-storage migration"}
   {:db/ident :api-key/key-digest
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "SHA-256 digest of a high-entropy API key, used for lookup"}
   {:db/ident :api-key/prefix
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Non-secret leading characters used to identify an API key"}
   {:db/ident :api-key/last-four
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Non-secret final four characters used to identify an API key"}
   {:db/ident :api-key/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Descriptive name for the API key"}
   {:db/ident :api-key/policy
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Stable reference to an access policy"}
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
   {:db/ident :api-key/tenants
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc "DEPRECATED: Use policy instead. Tenant IDs that this API key has access to"}
   {:db/ident :api-key/clients
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc "DEPRECATED: Use policy instead. Client IDs that this API key has access to"}
   {:db/ident :api-key/dataset-scopes
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "DEPRECATED: Use policy instead. Explicit dataset scopes that this API key grants access to"}
   {:db/ident :api-key.dataset-scope/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Stable unique identifier for an API key dataset-scope grant"}
   {:db/ident :api-key.dataset-scope/tenant
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Tenant for an API key dataset-scope grant"}
   {:db/ident :api-key.dataset-scope/dataset-config-key
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Canonical dataset-config-key for an API key dataset-scope grant"}
   {:db/ident :api-key/agent-refs
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "DEPRECATED: Use policy instead. Explicit agent grants for this API key"}
   {:db/ident :api-key.agent-ref/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Stable unique identifier for an API key agent-ref grant"}
   {:db/ident :api-key.agent-ref/agent-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Agent ID granted to the API key"}
   {:db/ident :api-key/skill-graphs
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc "DEPRECATED: Use policy instead. Skill graph IDs that this API key can execute (e.g., 'builtin/simple-qa')"}
   {:db/ident :api-key/scopes
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many
    :db/doc "DEPRECATED: Use policy instead. Scopes granted to this API key: :query, :ingest, :admin"}
   {:db/ident :api-key/usage-count
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Number of times this API key has been used"}
   {:db/ident :api-key/expires-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Optional expiration timestamp for the API key"}
   {:db/ident :api-key/allowed-config-keys
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "DEPRECATED: Use policy instead. Tenant/root allowed-config-key grants attached to this API key"}
   {:db/ident :api-key.allowed-config-key/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Stable allowed-config-key identifier"}
   {:db/ident :api-key.allowed-config-key/root
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Granted root; see digdir.config.structure"}
   {:db/ident :api-key.allowed-config-key/tenant
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Tenant that the allowed-config-key applies to"}
   {:db/ident :api-key.allowed-config-key/node
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Allowed config node for descendant authorization"}
   {:db/ident :api-key.allowed-config-key/node-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Denormalized durable node ID for debugging, export, and split-connection fallback"}
   {:db/ident :api-key.allowed-config-key/tenant-config-key
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Denormalized request-facing node tenant-config-key used by clients"}
   {:db/ident :api-key.allowed-config-key/created-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when the allowed config key was created"}])

(def ^:private api-key-grant-schema-repair-entries
  (->> api-key-schema
       (filter (comp #{:api-key/dataset-scopes
                       :api-key.dataset-scope/id
                       :api-key.dataset-scope/tenant
                       :api-key.dataset-scope/dataset-config-key
                       :api-key/agent-refs
                       :api-key.agent-ref/id
                       :api-key.agent-ref/agent-id
                       :api-key/allowed-config-keys
                       :api-key.allowed-config-key/id
                       :api-key.allowed-config-key/root
                       :api-key.allowed-config-key/tenant
                       :api-key.allowed-config-key/node
                       :api-key.allowed-config-key/node-id
                       :api-key.allowed-config-key/tenant-config-key
                       :api-key.allowed-config-key/created-at}
                     :db/ident))
       vec))

;; Migration for playground view mode preference
(def migration-conversation-view-mode
  [{:db/ident :conversation/view-mode
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "View mode preference: :focused or :detailed"}])

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

   ;; Canonical dataset-config-key for playground conversations (stores config resolution context)
   {:db/ident :conversation/dataset-config-key
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Canonical dataset-config-key for config resolution in playground conversations"}])

(def migration-conversation-agent
  [{:db/ident :conversation/agent-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Agent ID for conversation scope and runtime policy resolution"}])

(def migration-conversation-skill-graph
  [{:db/ident :conversation/skill-graph-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Skill graph ID used for execution in this conversation"}])

(def migration-canonical-dataset-identity
  [{:db/ident :api-key.dataset-scope/dataset-config-key
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Canonical dataset-config-key for an API key dataset-scope grant"}
   {:db/ident :conversation/dataset-config-key
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Canonical dataset-config-key for config resolution in playground conversations"}])

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
   {:db/ident :conversation/folder
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :conversation/tags
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
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
   {:db/ident :message/tags
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
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
     :db/cardinality :db.cardinality/one}
    {:db/ident :user/preferred-language
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}]
   migration-chunk
   migration-conversation-user
   migration-conversation-view-mode
   migration-playground-chat
   migration-conversation-agent
   migration-conversation-skill-graph
   migration-user-preferred-language
   access-policy-schema
   api-key-schema
   confirmation-code-schema))

(defn normalize-tags
  [tags]
  (->> (cond
         (nil? tags) []
         (string? tags) [tags]
         (or (sequential? tags) (set? tags)) tags
         :else [tags])
       (map #(some-> % str str/trim))
       (remove str/blank?)
       distinct
       vec))

(defn- conversation-has-tags?
  [conversation required-tags]
  (let [required-tags (normalize-tags required-tags)]
    (if (seq required-tags)
      (let [conversation-tags (set (normalize-tags (:conversation/tags conversation)))]
        (every? conversation-tags required-tags))
      true)))

(defn- filter-conversations-by-tags
  [conversations tags]
  (if (seq (normalize-tags tags))
    (filterv #(conversation-has-tags? % tags) conversations)
    (vec conversations)))

#?(:clj
   ;; Platform-wrapped so the :cljs lint pass doesn't see :clj-only vars.
   (comment
     ;; migrate db:
     (d/transact (get-conn) {:tx-data migration-conversation-user})
     ;; migrate for playground chat with branching:
     (d/transact (get-conn) {:tx-data migration-playground-chat})
     ;; migrate for user permissions:
     (d/transact (get-conn) {:tx-data migration-user-permissions})
     ;; migrate for API keys (from Datalevin):
     (d/transact (get-conn) {:tx-data api-key-schema})
     ;; migrate for Access Policies:
     (d/transact (get-conn) {:tx-data access-policy-schema})
     ;; migrate for conversation view mode:
     (d/transact (get-conn) {:tx-data migration-conversation-view-mode})
     ;; migrate for agent-scoped conversations:
     (d/transact (get-conn) {:tx-data migration-conversation-agent})
     ;; migrate for skill graph tracking on conversations:
     (d/transact (get-conn) {:tx-data migration-conversation-skill-graph})
     ;; migrate for canonical dataset identity aliases:
     (d/transact (get-conn) {:tx-data migration-canonical-dataset-identity})))

#?(:clj
   (timbre/set-min-level! :info))

#?(:clj
   (defn- ensure-user-preferred-language-schema-tx
     [db]
     (when-not (d/q '[:find ?e .
                      :in $ ?ident
                      :where
                      [?e :db/ident ?ident]]
                    db
                    :user/preferred-language)
       migration-user-preferred-language)))

#?(:clj
   (defn- ensure-canonical-dataset-identity-schema-tx
     [db]
     (when-not (d/q '[:find ?e .
                      :in $ ?ident
                      :where
                      [?e :db/ident ?ident]]
                    db
                    :api-key.dataset-scope/dataset-config-key)
       migration-canonical-dataset-identity)))

#?(:clj
   (defn- ensure-conversation-folder-schema-tx
     [db]
     (when-not (d/q '[:find ?e .
                      :in $ ?ident
                      :where
                      [?e :db/ident ?ident]]
                    db
                    :conversation/folder)
       migration-conversation-folder)))

#?(:clj
   (defn- ensure-conversation-tag-schema-tx
     [db]
     (let [tx1 (when-not (d/q '[:find ?e .
                                :in $ ?ident
                                :where
                                [?e :db/ident ?ident]]
                              db
                              :conversation/tags)
                 migration-conversation-tags)
           tx2 (when-not (d/q '[:find ?e .
                                :in $ ?ident
                                :where
                                [?e :db/ident ?ident]]
                              db
                              :message/tags)
                 migration-message-tags)]
       (vec (concat (or tx1 []) (or tx2 []))))))

#?(:clj
   (defn- schema-attr-entity
     [db ident]
     (d/q '[:find (pull ?e [:db/ident :db/unique :db/valueType :db/cardinality]) .
            :in $ ?ident
            :where [?e :db/ident ?ident]]
          db
          ident)))

#?(:clj
   (defn- schema-drift
     [existing schema-entry]
     (reduce (fn [drift attr]
               (let [existing-value (get existing attr)
                     expected-value (get schema-entry attr ::missing)]
                 (if (or (= expected-value ::missing)
                         (= existing-value expected-value))
                   drift
                   (assoc drift attr {:expected expected-value
                                      :actual existing-value}))))
             {}
             [:db/valueType :db/cardinality :db/unique])))

#?(:clj
   (defn- ensure-api-key-grant-schema-tx
     [db]
     (->> api-key-grant-schema-repair-entries
          (keep (fn [schema-entry]
                  (let [ident (:db/ident schema-entry)
                        existing (schema-attr-entity db ident)
                        drift (when existing (schema-drift existing schema-entry))]
                    (cond
                      (nil? existing) schema-entry
                      (seq drift) schema-entry
                      :else nil))))
          vec)))

#?(:clj
   (defn share-connection-with-config-db!
     "Register the main app connection as the config DB connection.
      This keeps the two logical roles on one physical Datahike connection by default.
      Re-callable: invoked again by `reconnect!` so config reads always go through
      the freshest connection."
     [conn]
     (require 'digdir.config.db)
     ((resolve 'digdir.config.db/set-conn!) conn)))

#?(:clj
   (def all-schemas
     "Single registration point for every schema fragment that must be
      transacted on boot. To add a new schema, register it here rather than
      relying on a side-effecting require or a separate init path — that
      indirection was the root cause of the 2026-04-23 config-def duplication
      incident (config-migration-schema declared :db.unique/identity on
      :config-def/path but was never transacted at boot, so Datahike's
      :schema-flexibility :read accepted writes without enforcing uniqueness
      and duplicates accumulated silently)."
     [{:label :main-data :tx dh-schema}
      {:label :config    :tx config-schema/config-migration-schema}]))

#?(:clj
   (defn- transact-registered-schemas!
     "Transact every fragment in `all-schemas`. 'Already exists' errors for
      attrs previously registered are swallowed (Datahike's idempotent attr
      registration). Any other failure is re-thrown with the schema label
      attached for diagnosability."
     [conn]
     (doseq [{:keys [label tx]} all-schemas]
       (try
         (d/transact conn {:tx-data tx})
         (catch Exception e
           (when-not (re-find #"already exists" (str (.getMessage e)))
             (throw (ex-info (str "Failed to transact schema fragment " label)
                             {:label label} e))))))))

#?(:clj
   (defn- unique-attrs-in-schemas
     "Return the :db/ident keywords of attrs that declare :db/unique in any
      fragment of `schemas`. We walk the declared schema rather than Datahike's
      meta-schema because the declared schema is the source of truth — a
      constraint listed here but not enforced in the live DB is exactly the
      hazard we want to detect."
     [schemas]
     (into []
           (comp (mapcat :tx)
                 (filter (fn [m]
                           (and (:db/ident m)
                                (contains? #{:db.unique/identity :db.unique/value}
                                           (:db/unique m)))))
                 (map :db/ident)
                 (distinct))
           schemas)))

#?(:clj
   (defn audit-uniqueness-invariants!
     "Scan every :db/unique attr declared in `all-schemas` and report any
      attribute-value whose entity count is greater than 1. Emits a log/warn
      per violation (not an exception — historical corruption should surface
      loudly but must not block startup). Returns
      `{:violations [...] :checked <int>}` for programmatic inspection.

      A `:violations` entry is `{:attr :config-def/path :value \"...\" :eids [e1 e2]}`.

      The log message names the relevant one-shot migration when we have one."
     ([conn] (audit-uniqueness-invariants! conn all-schemas))
     ([conn schemas]
      (let [db @conn
            unique-attrs (unique-attrs-in-schemas schemas)
            violations
            (reduce
             (fn [acc attr]
               (let [rows (d/q '[:find ?v ?e
                                 :in $ ?a
                                 :where [?e ?a ?v]]
                               db attr)
                     groups (reduce (fn [g [v e]]
                                      (update g v (fnil conj []) e))
                                    {}
                                    rows)
                     dupes (reduce-kv
                            (fn [a v es]
                              (if (> (count es) 1)
                                (conj a {:attr attr :value v :eids (vec es)})
                                a))
                            []
                            groups)]
                 (into acc dupes)))
             []
             unique-attrs)]
        (doseq [v violations]
          (timbre/warn "Uniqueness invariant violated"
                       (cond-> v
                         (= :config-def/path (:attr v))
                         (assoc :remediation "run migration 2026-04-23-dedupe-config-defs"))))
        {:checked (count unique-attrs)
         :violations violations}))))

#?(:clj
   (defn- apply-config-one-shot-migrations!
     "Apply pending one-shot migrations against the config DB — e.g. clearing
      values that were written before their attribute had a declared schema."
     [conn]
     (require 'digdir.config.db)
     ((resolve 'digdir.config.db/apply-one-shot-migrations!) conn)))

#?(:clj
   (defn- apply-config-post-definition-migrations!
     "Apply migrations that need definitions to exist (e.g. writing global
      values for newly :inherit-owned paths). Runs after
      `ensure-config-definitions-on-boot!`."
     [conn]
     (require 'digdir.config.db)
     ((resolve 'digdir.config.db/apply-post-definition-migrations!) conn)))

#?(:clj
   (defn- ensure-config-definitions-on-boot!
     "Upsert all config definitions so newly-added paths resolve without a manual setup run.
      Idempotent — safe on every boot."
     []
     (require 'digdir.setup.config)
     ((resolve 'digdir.setup.config/ensure-all-config-definitions!))))

#?(:clj
   (defn- preflight-file-backend!
     "For the :file backend, verify the parent directory exists before konserve
      touches the filesystem. Without this, a missing parent surfaces as a cryptic
      'No such file or directory' from UnixFileSystem.createFileExclusively0."
     [cfg]
     (when (= :file (get-in cfg [:store :backend]))
       (when-let [path (get-in cfg [:store :path])]
         (let [f (java.io.File. ^String path)
               parent (.getParentFile (.getAbsoluteFile f))]
           (when (and parent (not (.isDirectory parent)))
             (throw (ex-info
                     (str "Datahike :file backend parent directory does not exist: "
                          (.getAbsolutePath parent)
                          " (resolved from DATAHIKE_FILE_PATH=" path
                          ", cwd=" (System/getProperty "user.dir") "). "
                          "Create the parent directory or set DATAHIKE_FILE_PATH to an absolute path.")
                     {:path path
                      :parent (.getAbsolutePath parent)
                      :cwd (System/getProperty "user.dir")}))))))))

#?(:clj
   (defn- log-resolved-db-path!
     "Print a single banner line at init so we can tell at a glance which
      DB the running JVM is actually attached to (path, backend, and the
      cwd it was resolved from). Otherwise a stale `bb dev` from a
      previous shell can silently keep using an old DATAHIKE_FILE_PATH
      while the file on disk has been bumped to a new version."
     [env cfg]
     (let [backend (get-in cfg [:store :backend])
           path (get-in cfg [:store :path])
           env-var (System/getenv "DATAHIKE_FILE_PATH")
           cwd (System/getProperty "user.dir")]
       (println (str "[digdir.data.db] init-db env=" env
                     " backend=" backend
                     " path=" (pr-str path)
                     " DATAHIKE_FILE_PATH=" (pr-str env-var)
                     " cwd=" (pr-str cwd))))))

#?(:clj
   (defn- bootstrap-cfg
     "Resolve the datahike connection config from the bootstrap config atom.
      Returns nil when no bootstrap is loaded — callers handle that gracefully
      (mirrors init-db!'s behavior pre-refactor)."
     []
     (when-let [bootstrap @config/!bootstrap-config]
       (let [env (:db-env bootstrap)]
         (get bootstrap env)))))

#?(:clj (defonce ^:private !conn (atom nil)))
#?(:clj (defonce ^:private conn-init-lock (Object.)))

#?(:clj
   (defn purge-legacy-plaintext-confirmation-codes!
     "Invalidate confirmation-code rows written by releases that persisted the
      six-digit bearer value directly. They cannot be converted to the new
      email-bound HMAC safely without retaining plaintext, and their lifetime
      is only ten minutes, so a one-time re-login is the secure migration."
     [conn]
     (let [eids (d/q '[:find [?e ...]
                       :where [?e :confirmation-code/code]]
                     @conn)]
       (when (seq eids)
         (d/transact conn
                     {:tx-data (mapv (fn [eid] [:db/retractEntity eid]) eids)}))
       (count eids))))

#?(:clj
   ;; ⚠️ `:keep-history? false` APPLIES HERE TOO, and this is the file people
   ;; check to find out. It is set once in
   ;; `digdir.config.core/load-bootstrap-config` — which builds the store config
   ;; for the DATA database as well as the config ones — so grepping this
   ;; namespace for `keep-history` finds nothing and reads as "falls through to
   ;; Datahike's default", which retains history. That inference is wrong, and
   ;; it generated #520.
   ;;
   ;; Measured on a COPY of a real running store (#520), three ways rather than
   ;; read off the source that created it:
   ;;   stored config          -> :keep-history? false
   ;;   (d/history db)         -> refuses: "history is only allowed on temporal
   ;;                             indexed databases" — a behavioural check, not
   ;;                             a config read
   ;;   supplying the default  -> Datahike rejects the connect, diffing
   ;;                             {:keep-history? true} against the stored false
   ;;
   ;; So turning history off is a NO-OP: it is already off. Nothing in this
   ;; repo calls `d/history`, `d/as-of` or `d/since` either (the `since` hits
   ;; are prose and an `:audit/timestamp` filter), so nothing depends on it.
   ;;
   ;; And the store's size is not history: that same store held 114 MB across
   ;; 11,666 files for 3,228 live datoms, and still opened in 32 ms cold, 2 ms
   ;; warm, answering the playground's own queries in 7-21 ms. Whatever makes
   ;; the playground feel slow, it is not this connection.

   (defn init-db!
     "Full one-time boot sequence: connect, register schemas, apply migrations,
      ensure config definitions, audit invariants. Sets `!conn`. Returns the
      connection (or nil if no bootstrap is loaded). Subsequent calls are
      idempotent because the schema/migration helpers each guard themselves."
     []
     (locking conn-init-lock
       (or @!conn
           ;; A missing bootstrap variable is an ERROR AT THE POINT OF USE,
           ;; naming itself — the rule `digdir.secrets` already states: "there
           ;; is deliberately no arity that returns nil, because this
           ;; repository's most-repeated defect is a configuration error
           ;; arriving later as something else."
           ;;
           ;; This was that defect. `load-bootstrap-config` returns nil on three
           ;; distinct causes; the `when-let` below skipped its body in silence,
           ;; init-db! returned nil, and the nil surfaced as
           ;; `NullPointerException at datahike.writer/transact!` — byte
           ;; identical for all three, naming none of them (#326). Measured on a
           ;; fresh clone, which is the only place it is visible: any configured
           ;; worktree has the variables set.
           (when (nil? @config/!bootstrap-config)
             (throw (config/bootstrap-config-error)))
           (when-let [bootstrap @config/!bootstrap-config]
             (let [env (:db-env bootstrap)]
               (if-let [cfg (get bootstrap env)]
                 (let [_ (log-resolved-db-path! env cfg)
                       _ (preflight-file-backend! cfg)
                       _ (when-not (d/database-exists? cfg)
                           (d/create-database cfg))
                       conn (d/connect cfg)]
                   (transact-registered-schemas! conn)
                   (let [db @conn
                         schema-tx (vec (concat
                                         (or (ensure-user-preferred-language-schema-tx db) [])
                                         (or (ensure-canonical-dataset-identity-schema-tx db) [])
                                         (or (ensure-conversation-folder-schema-tx db) [])
                                         (or (ensure-conversation-tag-schema-tx db) [])
                                         (or (ensure-api-key-grant-schema-tx db) [])))]
                     (when (seq schema-tx)
                       (timbre/info "Applying batched schema migrations...")
                       (d/transact conn {:tx-data schema-tx})))
                   (share-connection-with-config-db! conn)
                   (apply-config-one-shot-migrations! conn)
                   (audit-uniqueness-invariants! conn)
                   (ensure-config-definitions-on-boot!)
                   (apply-config-post-definition-migrations! conn)
                   (let [purged-count
                         (purge-legacy-plaintext-confirmation-codes! conn)]
                     (when (pos? purged-count)
                       (timbre/info "Purged legacy plaintext confirmation codes"
                                    {:count purged-count})))
                   ;; Load dynamically to avoid the compile-time cycle:
                   ;; config.api-keys depends on this namespace for get-conn.
                   ;; This runs after the digest schema is registered and
                   ;; before the connection is published, so no request can
                   ;; observe dormant plaintext credentials after startup.
                   (let [migrate-api-keys!
                         (requiring-resolve
                          'digdir.config.api-keys/migrate-legacy-api-key-storage!)
                         migrated-count (migrate-api-keys! conn)]
                     (when (pos? migrated-count)
                       (timbre/info "Migrated legacy API keys to hashed storage"
                                    {:count migrated-count})))
                   (reset! !conn conn)
                   ;; Spawn the cross-process invalidation poller so external
                   ;; writes (`bb config-set`, `bb dump-import`) propagate
                   ;; without a JVM restart. Best-effort; failure here doesn't
                   ;; affect the boot sequence.
                   (try
                     (require 'digdir.config.cache-invalidation)
                     ((resolve 'digdir.config.cache-invalidation/start-poller!))
                     (catch Exception e
                       (timbre/warn e "Failed to start config-cache-invalidation poller")))
                   conn)
                 (do (println (str "no db config loaded for env: " env))
                     nil))))))))

#?(:clj
   (def init-db
     "Backwards-compat alias for callers that don't yet know about init-db!. The
      `!` suffix is the right modern style — side-effecting, sets the atom. Old
      name retained briefly so an import order shuffle doesn't break anything."
     init-db!))

#?(:clj
   (defn reconnect!
     "Release the current datahike connection and open a fresh one against the
      same bootstrap config. Used by the cache-invalidation poller and the
      `POST /api/admin/config/refresh` endpoint to pick up external writes
      without a full bb dev restart. Skips schema migrations and config-def
      ensures — those are init-time only.

      Safe to call from any thread; serialized through `conn-init-lock`."
     []
     (locking conn-init-lock
       (let [old @!conn
             cfg (bootstrap-cfg)]
         (when (and old cfg)
           (try (d/release old)
                (catch Exception e
                  (timbre/warn e "reconnect! release threw, continuing")))
           (let [conn (d/connect cfg)]
             (share-connection-with-config-db! conn)
             (reset! !conn conn)
             conn))))))

#?(:clj
   (defn get-conn
     "Return the shared datahike connection, running init-db! on first access."
     []
     (or @!conn (init-db!))))

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
   (defn fetch-convo-agent-id [db convo-id]
     (d/q '[:find [?agent-id]
            :in $ ?conv-id
            :where
            [?e :conversation/id ?conv-id]
            [?e :conversation/agent-id ?agent-id]]
          db convo-id)))

#?(:clj
   (defn fetch-user-id [user-email]
     (:user/id (d/pull (get-conn) '[:user/id] [:user/email user-email]))))

#?(:clj
   (defn- pull-conversations-by-eids
     [db eids]
     (mapv #(normalize-conversation-record
             (d/pull db conversation-pull-attrs %))
           eids)))

#?(:clj
   (defn- regular-conversation-records
     [db]
     (->> (d/datoms db {:index :aevt
                        :components [:conversation/id]})
          (map :e)
          (pull-conversations-by-eids db)
          (remove :conversation/folder)
          vec)))

#?(:clj
   (defn conversations
     ([db]
      (conversations db nil))
     ([db tags]
      (let [records (regular-conversation-records db)
            filtered (filter-conversations-by-tags records tags)
            result (sort-by #(or (:conversation/created %) 0) > filtered)]
        result))))

#?(:clj
   (defn conversations-paginated
     "Returns paginated conversations for all users.
      page-size: number of conversations per page
      page-index: 0-based page index
      Returns {:conversations [...] :total count :page-size n :page-index n}"
     ([db page-size page-index]
      (conversations-paginated db page-size page-index nil))
     ([db page-size page-index tags]
      (let [all-convos (conversations db tags)
            total (count all-convos)
            start (* page-size page-index)
            end (min (+ start page-size) total)
            page-convos (if (< start total)
                          (subvec (vec all-convos) start end)
                          [])]
        {:conversations page-convos
         :total total
         :page-size page-size
         :page-index page-index}))))

#?(:clj
   (defn conversations-by-user
     ([db user-id]
      (conversations-by-user db user-id nil))
     ([db user-id tags]
      (let [records (regular-conversation-records db)
            user-records (filter #(= user-id (:conversation/user-id %)) records)
            filtered (filter-conversations-by-tags user-records tags)
            result (sort-by #(or (:conversation/created %) 0) > filtered)]
        result))))

#?(:clj
   (defn conversations-by-user-paginated
     "Returns paginated conversations for a specific user.
      page-size: number of conversations per page
      page-index: 0-based page index
      Returns {:conversations [...] :total count :page-size n :page-index n}"
     ([db user-id page-size page-index]
      (conversations-by-user-paginated db user-id page-size page-index nil))
     ([db user-id page-size page-index tags]
      (let [all-convos (conversations-by-user db user-id tags)
            total (count all-convos)
            start (* page-size page-index)
            end (min (+ start page-size) total)
            page-convos (if (< start total)
                          (subvec (vec all-convos) start end)
                          [])]
        {:conversations page-convos
         :total total
         :page-size page-size
         :page-index page-index}))))

#?(:clj
   (defn conversations-by-other-or-unknown-user [db user-id]
     (let [records (regular-conversation-records db)
            filtered (remove #(= user-id (:conversation/user-id %)) records)
            result (sort-by #(or (:conversation/created %) 0) > filtered)]
       result)))

#?(:clj
   (defn orphan-conversations [db]
     (let [records (regular-conversation-records db)
            filtered (filter #(nil? (:conversation/user-id %)) records)
            result (sort-by #(or (:conversation/created %) 0) > filtered)]
       result)))

#?(:clj
   (defn conversation-by-id [db convo-id]
     (some-> (d/q '[:find ?e .
                    :in $ ?conv-id
                    :where
                    [?e :conversation/id ?conv-id]]
                  db convo-id)
             (#(d/pull db conversation-pull-attrs %))
             normalize-conversation-record))) 

#?(:clj
   (defn empty-threads-older-than-30-min 
     "Returns conversation IDs of all conversations that have exactly 2 messages (system + filter) and were created more than 30 minutes ago"
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
     (let [folder-name (d/q '[:find ?folder-name .
                              :in $ ?folder-id
                              :where
                              [?e :folder/id ?folder-id]
                              [?e :folder/name ?folder-name]]
                            db folder-id)]
       (->> (d/q '[:find [?c ...]
                   :in $ ?folder-id
                   :where
                   [?c :conversation/folder ?folder-id]
                   [?c :conversation/id _]]
                 db folder-id)
            (pull-conversations-by-eids db)
            (mapv (fn [conversation]
                    [(:conversation/created conversation)
                     (:db/id conversation)
                     (:conversation/id conversation)
                     (:conversation/topic conversation)
                     folder-name]))
            (sort-by first >)))))

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
   (defn transact-user-msg
     ([conn convo-id user-query]
      (transact-user-msg conn convo-id user-query nil))
     ([conn convo-id user-query {:keys [agent-id user-id topic created tenant tags]}]
      (let [time-point (System/currentTimeMillis)
            tx-data (cond-> {:conversation/id convo-id
                             :conversation/messages [{:message/id (nano-id)
                                                      :message/text user-query
                                                      :message/role :user
                                                      :message/voice :user
                                                      :message/completion true
                                                      :message/kind :kind/markdown
                                                      :message/created time-point}]}
                      agent-id (assoc :conversation/agent-id agent-id)
                      user-id (assoc :conversation/user-id user-id)
                      topic (assoc :conversation/topic topic)
                      created (assoc :conversation/created created)
                      tenant (assoc :conversation/tenant tenant)
                      (seq (normalize-tags tags)) (assoc :conversation/tags (normalize-tags tags)))
            _ (prn "transact-user-msg called for query: " user-query)]
        (d/transact conn [tx-data])))))

#?(:clj
   (defn set-conversation-tags
     [conn convo-id tags]
     (let [conversation (conversation-by-id @conn convo-id)
           convo-eid (:db/id conversation)
           existing-tags (normalize-tags (:conversation/tags conversation))
           normalized-tags (normalize-tags tags)
           existing-tag-set (set existing-tags)
           normalized-tag-set (set normalized-tags)
           retract-ops (mapv (fn [tag]
                               [:db/retract convo-eid :conversation/tags tag])
                             (remove normalized-tag-set existing-tags))
           add-ops (mapv (fn [tag]
                           [:db/add convo-eid :conversation/tags tag])
                         (remove existing-tag-set normalized-tags))]
       (d/transact conn {:tx-data (vec (concat retract-ops add-ops))}))))

#?(:clj
   (defn transact-assistant-msg
     ([conn convo-id msg]
      (transact-assistant-msg conn convo-id msg nil))
     ([conn convo-id msg diagnostics]
      (let [id (nano-id)
            message (cond-> {:message/id id
                             :message/text msg
                             :message/role :assistant
                             :message/voice :assistant
                             :message/completion true
                             :message/kind :kind/markdown
                             :message/created (System/currentTimeMillis)}
                      diagnostics (assoc :message/diagnostics (pr-str diagnostics)))]
        (d/transact conn [{:conversation/id convo-id
                           :conversation/messages [message]}])
        {:message/id id}))))

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

;; Platform correctness for this pair (#126).
;;
;; Both call `d`, which is required under :clj only, and create-folder also uses
;; System interop - so both would fail if ever called from ClojureScript. The
;; risk in wrapping a live defn is removing something the client was using, so
;; that was checked first: NEITHER HAS A CALLER ANYWHERE, on either platform.
;;
;; (An earlier version of this comment said the neighbours could not be wrapped
;; because delete-convo is called from an Electric namespace. That was wrong -
;; a substring match, corrected under #169 below, where the whole cluster is
;; wrapped and the Electric question is answered.)
;;
;; Wrapping create-folder alone would have made things worse, not better: it
;; was the only cljs-visible user of nano-id, so removing it from the :cljs
;; branch turned one warning into two. Hence the require above is conditional
;; too - measured, not assumed.
#?(:clj
   (defn get-chunk [conn chunk-id]
     (d/pull conn '[*] chunk-id)))

#?(:clj
   (defn create-folder [conn]
     (d/transact conn [{:folder/id (nano-id)
                        :folder/name "New folder"
                        :folder/created (System/currentTimeMillis)}])))

;; Platform correctness, continued (#169).
;;
;; These all call `d`, required under :clj only, so each would fail if reached
;; from ClojureScript. #126 wrapped create-folder and get-chunk and stopped
;; here, on the belief that delete-convo was called from an Electric namespace
;; and so could not be wrapped without answering whether Electric needs the var
;; in the :cljs branch.
;;
;; BOTH HALVES OF THAT TURNED OUT TO BE WRONG, and the answer is worth writing
;; down so the next cleanup pass does not re-litigate it:
;;
;;   1. delete-convo has NO Electric caller. The earlier claim came from a
;;      substring match - playground/ui.cljc contains `on-delete-convo` and
;;      `:pending-delete-convo-id`, which are a callback parameter and a state
;;      key, not calls to this var. Qualified, `db/delete-convo` is called only
;;      from api/routes/conversations.clj and routes_test.clj, both .clj.
;;
;;   2. Electric does not need the var in the :cljs branch anyway. Nine
;;      :clj-only vars in this file are ALREADY called from Electric .cljc
;;      namespaces in the shipping build - get-conn from nine of them - and
;;      db/delete-playground-conversation is called inside an explicit
;;      (e/server ...) block in playground/ui.cljc. The client build is green,
;;      so a server-position call to a :clj-only var compiles. That is evidence
;;      from the artifact, not an argument about what ought to work.
;;
;; So the whole cluster is wrapped. Every one has zero cljs callers; the only
;; live callers are .clj server code.
#?(:clj
   (defn rename-convo-topic [conn convo-id new-topic]
     (d/transact conn [{:db/id [:conversation/id convo-id]
                        :conversation/topic new-topic}])))

#?(:clj
   (defn rename-folder [conn folder-id new-folder-name]
     (d/transact conn [{:db/id [:folder/id folder-id]
                        :folder/name new-folder-name}])))

#?(:clj
   ;; TODO: develop consistency of id and eid usage
   (defn delete-convo [conn convo-eid]
     (d/transact conn [[:db/retract convo-eid :conversation/id]])))

#?(:clj
   (defn delete-folder [conn folder-eid]
     (d/transact conn [[:db.fn/retractEntity folder-eid]])))

#?(:clj
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
       (d/transact conn retraction-ops))))

#?(:clj
   (defn transact-new-msg-thread [conn agent-id user-id & [filter-value]]
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
            :conversation/agent-id agent-id
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
        agent-id - Agent ID for the conversation
        opts - Optional map with :user-id, :tenant, :dataset-config-key"
     ([conn agent-id]
      (create-playground-conversation conn agent-id nil))
     ([conn agent-id opts]
      (let [convo-id (nano-id)
            time-point (System/currentTimeMillis)
            {:keys [user-id tenant dataset-config-key skill-graph-id]} (if (string? opts)
                                                                         {:user-id opts}
                                                                         opts)
            base-tx (cond-> {:conversation/id convo-id
                             :conversation/agent-id agent-id
                             :conversation/type :playground
                             :conversation/topic "Playground Session"
                             :conversation/created time-point}
                      user-id (assoc :conversation/user-id user-id)
                      tenant (assoc :conversation/tenant tenant)
                      dataset-config-key (assoc :conversation/dataset-config-key dataset-config-key)
                      skill-graph-id (assoc :conversation/skill-graph-id skill-graph-id))]
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
       (defn transact-playground-assistant-msg-tx-data
       "Generate transaction data for an assistant message."
       [convo-id text diagnostics execution-id parent-msg-id branch-index]
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
       {:message-id msg-id
       :tx-data [{:conversation/id convo-id
               :conversation/messages [with-parent]}]})))

       #?(:clj
       (defn transact-playground-assistant-msg
       "Create assistant message with diagnostics and execution reference.
       Returns the created message ID."
       [conn convo-id text diagnostics execution-id parent-msg-id branch-index]
       (let [{:keys [message-id tx-data]} (transact-playground-assistant-msg-tx-data
                                     convo-id text diagnostics execution-id parent-msg-id branch-index)]
       (d/transact conn tx-data)
       {:message/id message-id})))

       #?(:clj
       (defn queue-playground-assistant-msg!
       "Queue an assistant message transaction for background processing.
       Returns the message-id immediately."
       ([convo-id text diagnostics execution-id parent-msg-id branch-index]
        (queue-playground-assistant-msg! convo-id text diagnostics execution-id parent-msg-id branch-index nil))
       ([convo-id text diagnostics execution-id parent-msg-id branch-index {:keys [callback error-callback]}]
        (let [{:keys [message-id tx-data]} (transact-playground-assistant-msg-tx-data
                                      convo-id text diagnostics execution-id parent-msg-id branch-index)]
        (require 'digdir.data.background-worker)
        {:message/id message-id
         :queued? ((resolve 'digdir.data.background-worker/queue-transact!) tx-data callback error-callback)}))))

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
   (defn- pull-playground-conversations
     [db entity-ids]
     (mapv #(normalize-conversation-record
             (d/pull db playground-conversation-pull-attrs %))
           entity-ids)))

#?(:clj
   (defn playground-conversations
     "Get all playground conversations, sorted by creation time (newest first)."
     [db]
     (sort-by #(or (:conversation/created %) 0) >
              (pull-playground-conversations
               db
               (d/q '[:find [?e ...]
                      :where
                      [?e :conversation/id _]
                      [?e :conversation/type :playground]]
                    db)))))

#?(:clj
   (defn playground-conversations-by-user
     "Get playground conversations for a specific user."
     [db user-id]
     (sort-by #(or (:conversation/created %) 0) >
              (pull-playground-conversations
               db
               (d/q '[:find [?e ...]
                      :in $ ?user-id
                      :where
                      [?e :conversation/id _]
                      [?e :conversation/type :playground]
                      [?e :conversation/user-id ?user-id]]
                    db user-id)))))

#?(:clj
   (defn playground-conversation-sidebar-page
     "Page-sized fetch for the playground sidebar.

      Returns the `limit` most recent playground conversations for `user-id`.
      If `active-conversation-id` is supplied and not already in that slice,
      it is appended so the active selection stays visible without forcing
      the user to load more.

      Result: {:items [<conversation-map> ...] :total-count <long>}."
     [db user-id {:keys [limit active-conversation-id query]
                  :or {limit 20}}]
     (let [needle (some-> query str str/trim str/lower-case not-empty)
           matches-query? (fn [conversation]
                            (or (nil? needle)
                                (some #(str/includes?
                                        (str/lower-case (str (or % "")))
                                        needle)
                                      [(:conversation/topic conversation)
                                       (:conversation/agent-id conversation)
                                       (:conversation/tenant conversation)
                                       (:conversation/dataset-config-key conversation)])))
           all-user-conversations (vec (playground-conversations-by-user db user-id))
           all-sorted (->> all-user-conversations
                           (filter matches-query?)
                           vec)
           total (count all-sorted)
           limited (vec (take limit all-sorted))
           kept-ids (set (map :conversation/id limited))
           with-active (if (and active-conversation-id
                                (not (contains? kept-ids active-conversation-id)))
                         (if-let [active (some #(when (= active-conversation-id
                                                         (:conversation/id %))
                                                  %)
                                               all-sorted)]
                           (conj limited active)
                           limited)
                         limited)]
       {:items with-active
        :total-count total
        :user-total-count (count all-user-conversations)})))

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
   (defn update-playground-view-mode
     "Update the view mode preference for a conversation."
     [conn convo-id view-mode]
     (d/transact conn [{:db/id [:conversation/id convo-id]
                         :conversation/view-mode view-mode}])))

#?(:clj
   (defn delete-playground-conversation
     "Delete a playground conversation and all its messages by conversation ID.
      When user-id is provided, validates ownership before deleting."
     ([conn convo-id]
      (delete-playground-conversation conn convo-id nil))
     ([conn convo-id user-id]
      (let [db @conn
            ;; Find conversation entity
            convo-eid (d/q '[:find ?e .
                            :in $ ?convo-id
                            :where [?e :conversation/id ?convo-id]]
                          db convo-id)]
        (when convo-eid
          ;; Validate ownership if user-id provided
          (let [owner-id (:conversation/user-id (d/pull db [:conversation/user-id] convo-eid))]
            (when (or (nil? user-id) (= user-id owner-id))
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
                (d/transact conn retract-ops)))))))))

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

#?(:clj
   (defn clear-user-playground-conversations
     "Atomically delete every playground conversation and message owned by user-id.

      A single transaction prevents a partially-cleared sidebar when one of many
      per-conversation transactions is interrupted. Returns the number of deleted
      conversations."
     [conn user-id]
     (let [db @conn
           convo-ids (d/q '[:find [?convo-id ...]
                           :in $ ?user-id
                           :where
                           [?e :conversation/id ?convo-id]
                           [?e :conversation/type :playground]
                           [?e :conversation/user-id ?user-id]]
                         db user-id)
           convo-eids (keep #(d/q '[:find ?e .
                                    :in $ ?convo-id
                                    :where [?e :conversation/id ?convo-id]]
                                  db %)
                            convo-ids)
           msg-eids (if (seq convo-ids)
                      (d/q '[:find [?m ...]
                             :in $ [?convo-id ...]
                             :where
                             [?c :conversation/id ?convo-id]
                             [?c :conversation/messages ?m]]
                           db convo-ids)
                      [])
           retract-ops (concat
                        (mapv (fn [eid] [:db.fn/retractEntity eid]) msg-eids)
                        (mapv (fn [eid] [:db.fn/retractEntity eid]) convo-eids))]
       (when (seq retract-ops)
         (d/transact conn (vec retract-ops)))
       (count convo-ids))))

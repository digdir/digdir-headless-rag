(ns digdir.import-export.entities.conversations
  "Conversation import/export helpers."
  (:require [datahike.api :as d]
            [digdir.import-export.report :as report]))

(def ^:private conversation-pull-pattern
  '[:conversation/id
    :conversation/topic
    :conversation/created
    :conversation/agent-id
    :conversation/pipeline
    :conversation/pipeline-id
    :conversation/user-id
    :conversation/type
    :conversation/tenant
    :conversation/environment
    :conversation/view-mode
    :conversation/tags
    {:conversation/folder [:folder/id]}
    {:conversation/messages [:message/id
                             :message/text
                             :message/role
                             :message/voice
                             :message/completion
                             :message/kind
                             :message/created
                             :message/config
                             :message/diagnostics
                             :message/execution-id
                             :message/branch-index
                             :message/tags
                             :message.filter/value
                             {:message/parent-message [:message/id]}
                             {:message/chunks [:chunk/doc-num
                                               :chunk/doc-title
                                               :chunk/chunk-id
                                               :chunk/content-markdown
                                               :chunk/url
                                               :chunk/metadata
                                               :chunk/sha]}]}])

(defn- normalize-message-entity
  [message]
  (cond-> {:message/id (:message/id message)
           :message/text (:message/text message)
           :message/role (:message/role message)
           :message/voice (:message/voice message)
           :message/completion (:message/completion message)
           :message/kind (:message/kind message)
           :message/created (:message/created message)
           :message/tags (vec (:message/tags message))
           :message/chunks (vec (:message/chunks message))}
    (:message/config message) (assoc :message/config (:message/config message))
    (:message/diagnostics message) (assoc :message/diagnostics (:message/diagnostics message))
    (:message/execution-id message) (assoc :message/execution-id (:message/execution-id message))
    (some? (:message/branch-index message)) (assoc :message/branch-index (:message/branch-index message))
    (:message/tags message) (assoc :message/tags (vec (:message/tags message)))
    (:message.filter/value message) (assoc :message.filter/value (:message.filter/value message))
    (get-in message [:message/parent-message :message/id]) (assoc :message/parent-id (get-in message [:message/parent-message :message/id]))))

(defn export-conversations
  [db]
  (->> (d/q '[:find [?conversation-id ...]
              :where
              [?e :conversation/id ?conversation-id]]
            db)
       sort
       (mapv (fn [conversation-id]
               (d/pull db conversation-pull-pattern [:conversation/id conversation-id])))
       (mapv (fn [conversation]
               (cond-> {:conversation/id (:conversation/id conversation)
                        :conversation/topic (:conversation/topic conversation)
                        :conversation/created (:conversation/created conversation)
                        :conversation/tags (vec (:conversation/tags conversation))
                        :conversation/messages (->> (:conversation/messages conversation)
                                                    (mapv normalize-message-entity)
                                                    (sort-by (juxt :message/created :message/id))
                                                    vec)}
                 (:conversation/agent-id conversation) (assoc :conversation/agent-id (:conversation/agent-id conversation))
                 (or (:conversation/pipeline conversation)
                     (:conversation/pipeline-id conversation))
                 (assoc :conversation/pipeline (or (:conversation/pipeline conversation)
                                                   (:conversation/pipeline-id conversation)))
                 (:conversation/user-id conversation) (assoc :conversation/user-id (:conversation/user-id conversation))
                 (:conversation/type conversation) (assoc :conversation/type (:conversation/type conversation))
                 (:conversation/tenant conversation) (assoc :conversation/tenant (:conversation/tenant conversation))
                 (:conversation/environment conversation) (assoc :conversation/environment (:conversation/environment conversation))
                 (:conversation/view-mode conversation) (assoc :conversation/view-mode (:conversation/view-mode conversation))
                 (get-in conversation [:conversation/folder :folder/id]) (assoc :conversation/folder-id (get-in conversation [:conversation/folder :folder/id])))))))

(defn- conversation-eid
  [db convo-id]
  (d/q '[:find ?e .
         :in $ ?convo-id
         :where
         [?e :conversation/id ?convo-id]]
       db convo-id))

(defn- retract-conversation!
  [conn convo-id]
  (when-let [convo-eid (conversation-eid @conn convo-id)]
    (let [msg-eids (d/q '[:find [?m ...]
                          :in $ ?convo-id
                          :where
                          [?c :conversation/id ?convo-id]
                          [?c :conversation/messages ?m]]
                        @conn convo-id)
          chunk-eids (if (seq msg-eids)
                       (d/q '[:find [?chunk ...]
                              :in $ [?msg ...]
                              :where
                              [?msg :message/chunks ?chunk]]
                            @conn msg-eids)
                       [])]
      (d/transact conn {:tx-data (vec (concat
                                       (map (fn [eid] [:db/retractEntity eid]) chunk-eids)
                                       (map (fn [eid] [:db/retractEntity eid]) msg-eids)
                                       [[:db/retractEntity convo-eid]]))}))))

(declare ->keyword-attr)

(defn- message-import-tx
  [message]
  ;; Datahike rejects nil values on schema-typed attrs, so move every
  ;; nillable field into the cond-> chain. Real exports populate them, but
  ;; pruned/test data exposes the gap.
  ;;
  ;; :message/role, :message/voice, :message/kind are written by live
  ;; runtime code as keywords (e.g. :user / :assistant / :kind/markdown
  ;; in digdir.data.db's transact-* helpers) but JSON-export demotes
  ;; them to strings. After import without coercion the AVET index for
  ;; these attrs ends up with mixed types -- which crashes
  ;; datahike.query/lookup-pattern-coll when later queries iterate the
  ;; index range under a literal-keyword filter (the surface symptom
  ;; was the playground's fetch-conversation-tree websocket failure
  ;; with "Don't know how to create ISeq from: clojure.lang.Keyword").
  ;; Coercing on import keeps both legs of the index uniform.
  (cond-> {:message/id (:message/id message)
           :message/text (:message/text message)
           :message/role (->keyword-attr (:message/role message))
           :message/created (:message/created message)
           :message/chunks (vec (:message/chunks message))}
    (:message/voice message) (assoc :message/voice (->keyword-attr (:message/voice message)))
    (some? (:message/completion message)) (assoc :message/completion (:message/completion message))
    (:message/kind message) (assoc :message/kind (->keyword-attr (:message/kind message)))
    (:message/config message) (assoc :message/config (:message/config message))
    (:message/diagnostics message) (assoc :message/diagnostics (:message/diagnostics message))
    (:message/execution-id message) (assoc :message/execution-id (:message/execution-id message))
    (some? (:message/branch-index message)) (assoc :message/branch-index (:message/branch-index message))
    (:message.filter/value message) (assoc :message.filter/value (:message.filter/value message))
    (:message/parent-id message) (assoc :message/parent-message [:message/id (:message/parent-id message)])))

(defn- ->keyword-attr
  "Coerce a schema-`:keyword`-typed attribute value back to a keyword.

   The export pipeline writes conversations as JSON/JSONL, and JSON has
   no keyword type — so `:playground` round-trips to the string
   \"playground\". Datahike's schema declares :conversation/type and
   :conversation/view-mode as :db.type/keyword, and the AVET index
   chokes (\"Don't know how to create ISeq from: clojure.lang.Keyword\")
   when records of both types coexist under a literal-keyword filter
   pattern. Coercing on import keeps the DB schema-compliant regardless
   of which encoder produced the JSONL. Idempotent: keyword in → keyword
   out, nil in → nil out."
  [v]
  (cond
    (nil? v) nil
    (keyword? v) v
    (string? v) (keyword v)
    :else v))

(defn import-conversations!
  [conn conversations on-conflict]
  (reduce (fn [acc conversation]
            (let [convo-id (:conversation/id conversation)
                  exists? (some? (conversation-eid @conn convo-id))
                  overwrite? (and exists? (= on-conflict :overwrite))]
              (if (and exists? (= on-conflict :skip))
                (update acc :skipped inc)
                (do
                  (when overwrite?
                    (retract-conversation! conn convo-id))
                  (let [tx-data (cond-> {:conversation/id convo-id
                                         :conversation/topic (:conversation/topic conversation)
                                         :conversation/created (:conversation/created conversation)
                                         :conversation/messages (mapv message-import-tx (:conversation/messages conversation))}
                                  (:conversation/agent-id conversation) (assoc :conversation/agent-id (:conversation/agent-id conversation))
                                  (:conversation/pipeline conversation) (assoc :conversation/pipeline (:conversation/pipeline conversation))
                                  (:conversation/user-id conversation) (assoc :conversation/user-id (:conversation/user-id conversation))
                                  (:conversation/type conversation) (assoc :conversation/type (->keyword-attr (:conversation/type conversation)))
                                  (:conversation/tenant conversation) (assoc :conversation/tenant (:conversation/tenant conversation))
                                  (:conversation/environment conversation) (assoc :conversation/environment (:conversation/environment conversation))
                                  (:conversation/tags conversation) (assoc :conversation/tags (vec (:conversation/tags conversation)))
                                  (:conversation/view-mode conversation) (assoc :conversation/view-mode (->keyword-attr (:conversation/view-mode conversation)))
                                  (:conversation/folder-id conversation) (assoc :conversation/folder [:folder/id (:conversation/folder-id conversation)]))]
                    (d/transact conn {:tx-data [tx-data]})
                    (update acc (if overwrite? :overwritten :created) inc))))))
          {:created 0 :skipped 0 :overwritten 0}
          conversations))

(defn preview-import-conversations
  [db conversations on-conflict]
  (report/preview-existing-items conversations
                                 #(some? (conversation-eid db (:conversation/id %)))
                                 on-conflict))

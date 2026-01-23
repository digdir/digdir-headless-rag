(ns digdir.api.routes
  "RAG API endpoints and handlers for the headless API.

  This namespace provides:
  - RAG query endpoint (/api/rag)
  - Conversation management endpoints
  - API key management endpoints
  - User management endpoints
  - API key authentication middleware"
  (:require
   [digdir.config.api-keys :as api-keys]
   [digdir.auth.core :as auth]
   [digdir.rag.core :as rag]
   [digdir.config.permissions :as perms]
   [cheshire.core :as json]
   [clojure.tools.logging :as log]
   [datahike.api :as d]
   [digdir.data.db :as db]
   [nano-id.core :refer [nano-id]]
   [ring.util.response :as res]
   [reitit.ring :as ring]
   [digdir.config.accessor :as cfg]))

;; ===== Utilities =====

(defn get-entity-by-id
  "Get entity configuration by ID.
   Delegates to cfg/get-entity which supports both DB mode and legacy EDN fallback."
  [entity-id]
  (cfg/get-entity entity-id))

;; ===== RAG API Handler =====

(defn api-rag-handler
  "Handle RAG API requests. Expects JSON body with 'query' field.
  Optional fields: conversation-id, model, rerank-top-k, context-top-k"
  [ring-req]
  (try
    (let [entity-id (get ring-req :api-key/entity-id)
          body (slurp (:body ring-req))
          params (json/parse-string body true)

          ;; Validate required parameters
          user-query (:query params)

          _ (when-not user-query
              (throw (ex-info "Missing required field: query" {:status 400})))

          ;; Get entity configuration
          entity-config (get-entity-by-id entity-id)

          _ (when-not entity-config
              (throw (ex-info (str "Entity not found: " entity-id) {:status 404})))

          ;; Build RAG pipeline parameters
          convo-id (or (:conversation-id params) (nano-id))
          selected-model (or (:model params) "gpt-4o-2024-11-20")

          ;; RAG params use 3-level precedence: API param > entity config > global default
          ;; Entity config already includes global defaults via 6-level inheritance
          rag-params {:conversation-id convo-id
                     :entity-id entity-id
                     :original_user_query user-query
                     :translated_user_query user-query
                     :user_query_language_name "Norwegian"
                     :selected-model selected-model
                     ;; Rerank parameters
                     :rerankTopkChunks (or (:rerank-top-k params)
                                           (:rerank-top-k entity-config))
                     :rerankMaxChunkLength (or (:rerank-max-chunk-length params)
                                               (:rerank-max-chunk-length entity-config))
                     :rerankMaxLength (or (:rerank-max-length params)
                                          (:rerank-max-total-length entity-config))
                     ;; Context parameters
                     :contextTopkChunks (or (:context-top-k params)
                                            (:context-top-k entity-config))
                     :contextMaxChunkLength (or (:context-max-chunk-length params)
                                                (:context-max-chunk-length entity-config))
                     :maxContextLength (or (:max-context-length params)
                                           (:context-max-total-length entity-config))
                     ;; Collection names
                     :docsCollectionName (:docs-collection entity-config)
                     :chunksCollectionName (:chunks-collection entity-config)
                     :phrasesCollectionName (:phrases-collection entity-config)
                     ;; Prompts (use new kebab-case property names)
                     :promptRagQueryRelax (:prompt-query-relax entity-config)
                     :promptRagGenerate (:prompt-rag-generate entity-config)
                     :phrase-gen-prompt (:phrase-gen-prompt entity-config)
                     ;; Streaming
                     :stream_callback_msg1 nil
                     :stream_callback_msg2 nil
                     :streamCallbackFreqSec 2.0
                     :maxResponseTokenCount nil}

          ;; Execute RAG pipeline
          conn (db/get-conn)
          result (rag/rag-pipeline rag-params conn)

          ;; Extract relevant information from result
          response-data {:answer (:english_answer result)
                        :conversation-id convo-id
                        :model selected-model
                        :chunks-used (mapv (fn [chunk]
                                            {:chunk-id (:chunk_id chunk)
                                             :doc-title (get-in chunk [(keyword (:docsCollectionName entity-config)) :title])
                                             :doc-num (:doc_num chunk)
                                             :content-markdown (:content_markdown chunk)})
                                          (:chunks result))}]

      (log/info "RAG API request successful" {:entity-id entity-id :conversation-id convo-id})

      (-> (res/response (json/generate-string response-data))
          (res/status 200)
          (res/content-type "application/json")))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "RAG API request failed")
        (-> (res/response
              (json/generate-string {:error (.getMessage e)}))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Unexpected error in RAG API")
      (-> (res/response
            (json/generate-string {:error "Internal server error"}))
          (res/status 500)
          (res/content-type "application/json")))))

;; ===== Retrieval-Only API Handler =====

(defn api-retrieve-handler
  "Handle retrieval-only API requests. Returns ranked chunks without LLM generation.
   Expects JSON body with 'query' field.
   Optional fields: include_query_expansion (default true), top_k, filter"
  [ring-req]
  (try
    (let [entity-id (get ring-req :api-key/entity-id)
          body (slurp (:body ring-req))
          params (json/parse-string body true)

          ;; Validate required parameters
          user-query (:query params)
          _ (when-not user-query
              (throw (ex-info "Missing required field: query" {:status 400})))

          ;; Get entity configuration
          entity-config (get-entity-by-id entity-id)
          _ (when-not entity-config
              (throw (ex-info (str "Entity not found: " entity-id) {:status 404})))

          ;; Build retrieval params (subset of RAG params, no database needed)
          retrieval-params {:entity-id entity-id
                            :original_user_query user-query
                            :translated_user_query user-query
                            :include-query-expansion (get params :include_query_expansion true)
                            :selected-model (or (:model params) "gpt-4o-2024-11-20")

                            ;; Rerank parameters
                            :rerankTopkChunks (or (:top_k params)
                                                  (:rerank-top-k entity-config)
                                                  20)
                            :rerankMaxChunkLength (or (:rerank-max-chunk-length entity-config) 4000)
                            :rerankMaxLength (or (:rerank-max-total-length entity-config) 32000)

                            ;; Context parameters (for rerank-chunks compatibility)
                            :contextTopkChunks (or (:top_k params)
                                                   (:context-top-k entity-config)
                                                   10)
                            :contextMaxChunkLength (or (:context-max-chunk-length entity-config) 4000)
                            :maxContextLength (or (:context-max-total-length entity-config) 32000)

                            ;; Collection names
                            :docsCollectionName (:docs-collection entity-config)
                            :chunksCollectionName (:chunks-collection entity-config)
                            :phrasesCollectionName (:phrases-collection entity-config)

                            ;; Prompts
                            :promptRagQueryRelax (:prompt-query-relax entity-config)
                            :phrase-gen-prompt (:phrase-gen-prompt entity-config)

                            ;; Filter (optional)
                            :filter-by (when-let [filter-input (:filter params)]
                                         {:fields (mapv (fn [f]
                                                          {:field (:field f)
                                                           :selected-options (set (:selected_options f))
                                                           :value-type (keyword (or (:value_type f) "string"))})
                                                        (:fields filter-input))})}

          ;; Execute retrieval pipeline (stateless - no database connection needed)
          result (rag/retrieval-pipeline retrieval-params)

          ;; Format response with rich metadata
          docs-collection-kw (keyword (:docsCollectionName retrieval-params))
          response-data {:chunks (mapv (fn [chunk]
                                         {:chunk_id (:chunk_id chunk)
                                          :content_markdown (:content_markdown chunk)
                                          :metadata (rag/format-metadata-headers (:metadata chunk))
                                          :metadata_raw (:metadata chunk)
                                          :document {:doc_num (:doc_num chunk)
                                                     :title (get-in chunk [docs-collection-kw :title])
                                                     :url (get-in chunk [docs-collection-kw :url])}
                                          :relevance {:rerank_position (:rerank-position chunk)
                                                      :original_position (:original-index chunk)
                                                      :normalized_score (:original-rank chunk)
                                                      :search_types (mapv name (or (:search-types chunk) []))
                                                      :hit_count (:hit-count chunk)}})
                                       (:chunks result))
                         :query_expansion {:enabled (:include-query-expansion retrieval-params)
                                           :expanded_queries (or (:expanded-queries result) [])}
                         :search_stats (:search-attribution result)}]

      (log/info "Retrieval API request successful"
                {:entity-id entity-id
                 :chunks-count (count (:chunks result))})

      (-> (res/response (json/generate-string response-data))
          (res/status 200)
          (res/content-type "application/json")))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Retrieval API request failed")
        (-> (res/response (json/generate-string {:error (.getMessage e)}))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Unexpected error in Retrieval API")
      (-> (res/response (json/generate-string {:error "Internal server error"}))
          (res/status 500)
          (res/content-type "application/json")))))

;; ===== API Key Management Handlers =====

(defn create-api-key-handler
  "Create a new API key for the authenticated user"
  [ring-req]
  (try
    (let [user-id (:user/id ring-req)
          body (slurp (:body ring-req))
          params (json/parse-string body true)

          name (:name params)
          entity-id (:entity-id params)

          _ (when-not name
              (throw (ex-info "Missing required field: name" {:status 400})))
          _ (when-not entity-id
              (throw (ex-info "Missing required field: entity-id" {:status 400})))

          ;; Verify entity exists
          entity (get-entity-by-id entity-id)
          _ (when-not entity
              (throw (ex-info (str "Entity not found: " entity-id) {:status 404})))

          ;; Generate and store API key
          new-key (api-keys/generate-api-key)
          conn (db/get-conn)
          user-email (:user/email ring-req)
          result (api-keys/store-api-key conn new-key name user-id
                                         {:entities [entity-id]
                                          :user-email user-email})]

      (log/info "API key created" {:user-id user-id :api-key-id (:api-key-id result) :entity-id entity-id})

      (-> (res/response
            (json/generate-string
              {:api-key-id (:api-key-id result)
               :api-key (:api-key result)
               :name name
               :entity-id entity-id
               :warning "This is the only time you will see this API key. Store it securely."}))
          (res/status 201)
          (res/content-type "application/json")))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Failed to create API key")
        (-> (res/response
              (json/generate-string {:error (.getMessage e)}))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Unexpected error creating API key")
      (-> (res/response
            (json/generate-string {:error "Internal server error"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn list-api-keys-handler
  "List all API keys for the authenticated user"
  [ring-req]
  (try
    (let [user-id (:user/id ring-req)
          conn (db/get-conn)
          keys (api-keys/list-api-keys conn user-id)]

      (-> (res/response (json/generate-string {:api-keys keys}))
          (res/status 200)
          (res/content-type "application/json")))

    (catch Exception e
      (log/error e "Failed to list API keys")
      (-> (res/response
            (json/generate-string {:error "Internal server error"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn revoke-api-key-handler
  "Revoke an API key"
  [ring-req]
  (try
    (let [user-id (:user/id ring-req)
          user-email (:user/email ring-req)
          api-key-id (get-in ring-req [:path-params :key-id])

          _ (when-not api-key-id
              (throw (ex-info "Missing API key ID" {:status 400})))

          ;; Get key info to verify ownership
          conn (db/get-conn)
          key-info (api-keys/get-api-key-info conn api-key-id)

          _ (when-not key-info
              (throw (ex-info "API key not found" {:status 404})))

          ;; Verify user owns this key
          _ (when-not (= user-id (:api-key/created-by key-info))
              (throw (ex-info "Unauthorized" {:status 403})))

          ;; Revoke the key
          _ (api-keys/revoke-api-key conn api-key-id
                                     {:user-email user-email :user-id user-id})]

      (log/info "API key revoked" {:user-id user-id :api-key-id api-key-id})

      (-> (res/response (json/generate-string {:success true :message "API key revoked"}))
          (res/status 200)
          (res/content-type "application/json")))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Failed to revoke API key")
        (-> (res/response
              (json/generate-string {:error (.getMessage e)}))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Unexpected error revoking API key")
      (-> (res/response
            (json/generate-string {:error "Internal server error"}))
          (res/status 500)
          (res/content-type "application/json")))))

;; ===== Conversation Management Handlers =====

(defn list-conversations-handler
  "List conversations with pagination support. If X-User-Email header is provided, returns user's conversations; otherwise returns all conversations."
  [ring-req]
  (try
    (let [user-email (get-in ring-req [:headers "x-user-email"])
          conn (db/get-conn)
          db @conn
          page-size (try (Integer/parseInt (get-in ring-req [:params "page_size"] "50"))
                         (catch Exception _ 50))
          page-index (try (Integer/parseInt (get-in ring-req [:params "page_index"] "0"))
                          (catch Exception _ 0))
          result (if user-email
                   ;; User-specific conversations
                   (do
                     (when-not (d/entity db [:user/email user-email])
                       (auth/create-new-user {:email user-email}))
                     (let [user-id (:user/id (d/pull db '[:user/id] [:user/email user-email]))]
                       (db/conversations-by-user-paginated db user-id page-size page-index)))
                   ;; All users
                   (db/conversations-paginated db page-size page-index))
          response-data (mapv (fn [conv]
                               {:id (:conversation/id conv)
                                :topic (:conversation/topic conv)
                                :entityId (:conversation/entity-id conv)
                                :userId (:conversation/user-id conv)
                                :folder (:conversation/folder conv)
                                :created (:conversation/created conv)})
                             (:conversations result))]

      (log/info "Listed conversations" {:user-email user-email :page-size page-size :page-index page-index :total (:total result)})

      (-> (res/response (json/generate-string {:conversations response-data
                                                :total (:total result)
                                                :pageSize (:page-size result)
                                                :pageIndex (:page-index result)}))
          (res/status 200)
          (res/content-type "application/json")))

    (catch Exception e
      (log/error e "Failed to list conversations")
      (-> (res/response (json/generate-string {:error "Failed to list conversations"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn create-conversation-handler
  "Create a new conversation"
  [ring-req]
  (try
    (let [user-email (get-in ring-req [:headers "x-user-email"])

          _ (when-not user-email
              (throw (ex-info "Missing X-User-Email header" {:status 400})))

          ;; Ensure user exists, create if needed
          conn (db/get-conn)
          db @conn
          _ (when-not (d/entity db [:user/email user-email])
              (auth/create-new-user {:email user-email}))

          user-id (:user/id (d/pull db '[:user/id] [:user/email user-email]))
          entity-id (get ring-req :api-key/entity-id)
          body (slurp (:body ring-req))
          params (json/parse-string body true)

          entity-config (get-entity-by-id entity-id)
          filter-value (or (:filterValue params)
                          (:default-filter-value entity-config))

          result (db/transact-new-msg-thread conn entity-id user-id filter-value)
          convo-id (:conversation-id result)

          ;; Optionally update title if provided
          _ (when-let [title (:title params)]
              (db/rename-convo-topic conn convo-id title))

          ;; Get the created conversation
          conversation (db/conversation-by-id @conn convo-id)

          response-data {:id (:conversation/id conversation)
                        :topic (:conversation/topic conversation)
                        :entityId (:conversation/entity-id conversation)
                        :userId (:conversation/user-id conversation)
                        :created (:conversation/created conversation)}]

      (log/info "Created conversation" {:user-email user-email :conversation-id convo-id})

      (-> (res/response (json/generate-string {:conversation response-data}))
          (res/status 201)
          (res/content-type "application/json")))

    (catch Exception e
      (log/error e "Failed to create conversation")
      (-> (res/response (json/generate-string {:error "Failed to create conversation"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn get-conversation-handler
  "Get a specific conversation with its messages"
  [ring-req]
  (try
    (let [convo-id (get-in ring-req [:path-params :id])
          conn (db/get-conn)
          conversation (db/conversation-by-id @conn convo-id)

          _ (when-not conversation
              (throw (ex-info "Conversation not found" {:status 404})))

          messages (db/fetch-convo-messages-mapped @conn convo-id)

          response-data {:conversation {:id (:conversation/id conversation)
                                        :topic (:conversation/topic conversation)
                                        :entityId (:conversation/entity-id conversation)
                                        :userId (:conversation/user-id conversation)
                                        :created (:conversation/created conversation)}
                        :messages (mapv (fn [msg]
                                         {:id (:message/id msg)
                                          :text (or (:message/text msg) "")
                                          :role (some-> (:message/role msg) name)
                                          :created (:message/created msg)
                                          :filterValue (:message.filter/value msg)
                                          :chunks (mapv (fn [chunk]
                                                         {:chunkId (:chunk/chunk-id chunk)
                                                          :docTitle (or (:chunk/doc-title chunk) "")
                                                          :docNum (:chunk/doc-num chunk)
                                                          :contentMarkdown (or (:chunk/content-markdown chunk) "")})
                                                       (or (:message/chunks msg) []))})
                                       messages)}]

      (log/info "Retrieved conversation" {:conversation-id convo-id :message-count (count messages)})

      (-> (res/response (json/generate-string response-data))
          (res/status 200)
          (res/content-type "application/json")))

    (catch clojure.lang.ExceptionInfo e
      (let [status (or (:status (ex-data e)) 500)]
        (log/error e "Failed to get conversation")
        (-> (res/response (json/generate-string {:error (.getMessage e)}))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Failed to get conversation")
      (-> (res/response (json/generate-string {:error "Failed to get conversation"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn update-conversation-handler
  "Update a conversation (rename, move to folder, etc.)"
  [ring-req]
  (try
    (let [convo-id (get-in ring-req [:path-params :id])
          body (slurp (:body ring-req))
          params (json/parse-string body true)
          conn (db/get-conn)

          ;; Update title if provided
          _ (when-let [title (:title params)]
              (db/rename-convo-topic conn convo-id title))

          ;; Get updated conversation
          conversation (db/conversation-by-id @conn convo-id)

          response-data {:id (:conversation/id conversation)
                        :topic (:conversation/topic conversation)
                        :entityId (:conversation/entity-id conversation)
                        :userId (:conversation/user-id conversation)
                        :folder (:conversation/folder conversation)
                        :created (:conversation/created conversation)}]

      (log/info "Updated conversation" {:conversation-id convo-id})

      (-> (res/response (json/generate-string {:conversation response-data}))
          (res/status 200)
          (res/content-type "application/json")))

    (catch Exception e
      (log/error e "Failed to update conversation")
      (-> (res/response (json/generate-string {:error "Failed to update conversation"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn delete-conversation-handler
  "Delete a conversation"
  [ring-req]
  (try
    (let [convo-id (get-in ring-req [:path-params :id])
          conn (db/get-conn)
          conversation (db/conversation-by-id @conn convo-id)

          _ (when-not conversation
              (throw (ex-info "Conversation not found" {:status 404})))

          ;; Delete by entity ID
          _ (db/delete-convo conn (:db/id conversation))]

      (log/info "Deleted conversation" {:conversation-id convo-id})

      (-> (res/response (json/generate-string {:success true}))
          (res/status 200)
          (res/content-type "application/json")))

    (catch clojure.lang.ExceptionInfo e
      (let [status (or (:status (ex-data e)) 500)]
        (log/error e "Failed to delete conversation")
        (-> (res/response (json/generate-string {:error (.getMessage e)}))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Failed to delete conversation")
      (-> (res/response (json/generate-string {:error "Failed to delete conversation"}))
          (res/status 500)
          (res/content-type "application/json")))))

;; ===== User Management Handlers =====

(defn list-users-handler
  "List all users with their permissions (admin only)"
  [ring-req]
  (try
    (let [conn (db/get-conn)
          db @conn
          users (d/q '[:find [(pull ?u [:user/id :user/email :user/created
                                         {:user/permissions [:permission/id :permission/name]}]) ...]
                       :where [?u :user/id]]
                     db)
          response-data (mapv (fn [user]
                               {:id (:user/id user)
                                :email (:user/email user)
                                :created (:user/created user)
                                :permissions (mapv (fn [p]
                                                    {:id (:permission/id p)
                                                     :name (:permission/name p)})
                                                  (:user/permissions user))})
                             users)]

      (log/info "Listed users" {:count (count users)})

      (-> (res/response (json/generate-string {:users response-data}))
          (res/status 200)
          (res/content-type "application/json")))

    (catch Exception e
      (log/error e "Failed to list users")
      (-> (res/response (json/generate-string {:error "Failed to list users"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn create-user-handler
  "Create a new user with initial permissions (admin only)"
  [ring-req]
  (try
    (let [admin-id (:user/id ring-req)
          body (slurp (:body ring-req))
          params (json/parse-string body true)

          email (:email params)
          permission-ids (or (:permissions params) [])

          _ (when-not email
              (throw (ex-info "Missing required field: email" {:status 400})))

          ;; Create the user
          conn (db/get-conn)
          result (auth/create-new-user {:email email :creator-id admin-id})]

      (if (:error result)
        (throw (ex-info (:error result) {:status 409}))

        (let [;; Get the newly created user
              user (auth/user-by-email email)
              user-id (:user/id user)

              ;; Grant permissions
              _ (doseq [perm-id permission-ids]
                  (perms/grant-permission! conn user-id perm-id))

              ;; Get updated user with permissions
              db @conn
              updated-user (d/q '[:find (pull ?u [:user/id :user/email :user/created
                                                   {:user/permissions [:permission/id :permission/name]}]) .
                                  :in $ ?user-id
                                  :where [?u :user/id ?user-id]]
                               db user-id)]

          (log/info "Created user" {:email email :user-id user-id :permissions permission-ids})

          (-> (res/response
                (json/generate-string
                  {:user {:id (:user/id updated-user)
                          :email (:user/email updated-user)
                          :created (:user/created updated-user)
                          :permissions (mapv (fn [p]
                                              {:id (:permission/id p)
                                               :name (:permission/name p)})
                                            (:user/permissions updated-user))}}))
              (res/status 201)
              (res/content-type "application/json")))))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Failed to create user")
        (-> (res/response (json/generate-string {:error (.getMessage e)}))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Unexpected error creating user")
      (-> (res/response (json/generate-string {:error "Internal server error"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn get-user-handler
  "Get a specific user with their permissions (admin only)"
  [ring-req]
  (try
    (let [user-id (get-in ring-req [:path-params :id])
          conn (db/get-conn)
          db @conn
          user (d/q '[:find (pull ?u [:user/id :user/email :user/created
                                       {:user/permissions [:permission/id :permission/name]}]) .
                      :in $ ?user-id
                      :where [?u :user/id ?user-id]]
                   db user-id)]

      (if user
        (-> (res/response
              (json/generate-string
                {:user {:id (:user/id user)
                        :email (:user/email user)
                        :created (:user/created user)
                        :permissions (mapv (fn [p]
                                            {:id (:permission/id p)
                                             :name (:permission/name p)})
                                          (:user/permissions user))}}))
            (res/status 200)
            (res/content-type "application/json"))
        (-> (res/response (json/generate-string {:error "User not found"}))
            (res/status 404)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Failed to get user")
      (-> (res/response (json/generate-string {:error "Failed to get user"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn update-user-permissions-handler
  "Update a user's permissions (admin only)"
  [ring-req]
  (try
    (let [user-id (get-in ring-req [:path-params :id])
          body (slurp (:body ring-req))
          params (json/parse-string body true)

          permissions-to-add (:add params)
          permissions-to-remove (:remove params)

          conn (db/get-conn)
          db @conn

          ;; Verify user exists
          user (d/q '[:find ?u .
                      :in $ ?user-id
                      :where [?u :user/id ?user-id]]
                   db user-id)

          _ (when-not user
              (throw (ex-info "User not found" {:status 404})))

          ;; Grant new permissions
          _ (doseq [perm-id permissions-to-add]
              (perms/grant-permission! conn user-id perm-id))

          ;; Revoke permissions
          _ (doseq [perm-id permissions-to-remove]
              (perms/revoke-permission! conn user-id perm-id))

          ;; Get updated user
          updated-db @conn
          updated-user (d/q '[:find (pull ?u [:user/id :user/email :user/created
                                               {:user/permissions [:permission/id :permission/name]}]) .
                              :in $ ?user-id
                              :where [?u :user/id ?user-id]]
                           updated-db user-id)]

      (log/info "Updated user permissions" {:user-id user-id :added permissions-to-add :removed permissions-to-remove})

      (-> (res/response
            (json/generate-string
              {:user {:id (:user/id updated-user)
                      :email (:user/email updated-user)
                      :created (:user/created updated-user)
                      :permissions (mapv (fn [p]
                                          {:id (:permission/id p)
                                           :name (:permission/name p)})
                                        (:user/permissions updated-user))}}))
          (res/status 200)
          (res/content-type "application/json")))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Failed to update user permissions")
        (-> (res/response (json/generate-string {:error (.getMessage e)}))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Unexpected error updating user permissions")
      (-> (res/response (json/generate-string {:error "Internal server error"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn delete-user-handler
  "Delete a user (admin only)"
  [ring-req]
  (try
    (let [target-user-id (get-in ring-req [:path-params :id])
          requesting-user-id (:user/id ring-req)

          ;; Prevent self-deletion
          _ (when (= target-user-id requesting-user-id)
              (throw (ex-info "Cannot delete your own account" {:status 400})))

          conn (db/get-conn)
          db @conn

          ;; Find user entity
          user-eid (d/q '[:find ?u .
                          :in $ ?user-id
                          :where [?u :user/id ?user-id]]
                       db target-user-id)

          _ (when-not user-eid
              (throw (ex-info "User not found" {:status 404})))

          ;; Retract all user attributes
          _ (d/transact conn {:tx-data [[:db/retractEntity user-eid]]})]

      (log/info "Deleted user" {:user-id target-user-id :deleted-by requesting-user-id})

      (-> (res/response (json/generate-string {:success true :message "User deleted"}))
          (res/status 200)
          (res/content-type "application/json")))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Failed to delete user")
        (-> (res/response (json/generate-string {:error (.getMessage e)}))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Unexpected error deleting user")
      (-> (res/response (json/generate-string {:error "Internal server error"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn list-permissions-handler
  "List all available permissions (admin only)"
  [ring-req]
  (try
    (let [conn (db/get-conn)
          db @conn
          permissions (perms/get-all-permissions db)
          response-data (mapv (fn [p]
                               {:id (:permission/id p)
                                :name (:permission/name p)
                                :description (:permission/description p)})
                             permissions)]

      (-> (res/response (json/generate-string {:permissions response-data}))
          (res/status 200)
          (res/content-type "application/json")))

    (catch Exception e
      (log/error e "Failed to list permissions")
      (-> (res/response (json/generate-string {:error "Failed to list permissions"}))
          (res/status 500)
          (res/content-type "application/json")))))

;; ===== Middleware =====

(defn wrap-api-key-auth
  "Middleware to validate API key from X-API-Key header"
  [handler]
  (fn [request]
    (let [api-key (get-in request [:headers "x-api-key"])
          conn (db/get-conn)]
      (if-let [key-info (and api-key (api-keys/validate-api-key conn api-key))]
        (handler (assoc request :api-key/entity-id (:entity-id key-info)))
        (-> (res/response (json/generate-string {:error "Invalid or missing API key"}))
            (res/status 401)
            (res/content-type "application/json"))))))

;; ===== Router =====

(def api-routes
  "API route definitions for API-key authenticated endpoints"
  [["/api"
    ["/rag" {:post {:handler api-rag-handler}}]
    ["/retrieve" {:post {:handler api-retrieve-handler}}]
    ["/conversations" {:get {:handler list-conversations-handler}
                       :post {:handler create-conversation-handler}}]
    ["/conversations/:id" {:get {:handler get-conversation-handler}
                           :put {:handler update-conversation-handler}
                           :delete {:handler delete-conversation-handler}}]]])

(def api-key-routes
  "API route definitions for JWT-authenticated API key management"
  [["/config/api-keys" {:get {:handler list-api-keys-handler}
                        :post {:handler create-api-key-handler}}]
   ["/config/api-keys/:key-id/revoke" {:post {:handler revoke-api-key-handler}}]])

(def admin-routes
  "API route definitions for JWT-authenticated admin operations (user management, permissions)"
  [["/api/users" {:get {:handler list-users-handler}
                  :post {:handler create-user-handler}}]
   ["/api/users/:id" {:get {:handler get-user-handler}
                      :delete {:handler delete-user-handler}}]
   ["/api/users/:id/permissions" {:put {:handler update-user-permissions-handler}}]
   ["/api/permissions" {:get {:handler list-permissions-handler}}]])

(defn- json-not-found [_]
  (-> (res/not-found (json/generate-string {:error "API endpoint not found"}))
      (res/content-type "application/json")))

(def api-router
  "Ring handler for /api/* endpoints that use API key authentication"
  (ring/ring-handler
   (ring/router api-routes)
   json-not-found))

(def api-keys-router
  "Ring handler for /api/keys/* endpoints that use JWT authentication"
  (ring/ring-handler
   (ring/router api-key-routes)
   json-not-found))

(def admin-router
  "Ring handler for admin endpoints that use JWT authentication (user management, etc.)"
  (ring/ring-handler
   (ring/router admin-routes)
   json-not-found))

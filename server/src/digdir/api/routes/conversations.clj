(ns digdir.api.routes.conversations
  "Conversation and user-management handlers for the API entrypoint."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datahike.api :as d]
            [digdir.api.context :as api-ctx]
            [digdir.api.util :refer [api-error-body
                                     param-value
                                     request-body-params
                                     request-path-params
                                     request-query-params
                                     require-external-api-user-id!
                                     find-api-conversation!]]
            [digdir.auth.core :as auth]
            [digdir.config.permissions :as perms]
            [digdir.data.db :as db]
            [digdir.playground.diagnostics :as playground-diagnostics]
            [ring.util.response :as res]))

(defn- parse-int-param
  [params k default min-value]
  (let [raw (param-value params k)]
    (try
      (let [parsed (if (integer? raw)
                     raw
                     (Integer/parseInt (str raw)))]
        (if (>= parsed min-value)
          parsed
          default))
      (catch Exception _
        default))))

(def max-conversation-page-size 100)

(defn parse-page-params
  "Extract bounded page-size and page-index from Ring request query params."
  [ring-req]
  (let [query-params (request-query-params ring-req)]
    {:page-size (min max-conversation-page-size
                     (parse-int-param query-params :page_size 50 1))
     :page-index (parse-int-param query-params :page_index 0 0)}))

(defn- parse-tags-filter
  [query-params]
  (let [raw-tags (param-value query-params :tags)]
    (db/normalize-tags
     (cond
       (nil? raw-tags) []
       (string? raw-tags) (str/split raw-tags #"\s*,\s*")
       (sequential? raw-tags) raw-tags
       :else [raw-tags]))))

(defn- message-diagnostics
  [message]
  (when-let [diagnostics-str (:message/diagnostics message)]
    (try
      (-> diagnostics-str
          edn/read-string
          playground-diagnostics/normalize-diagnostics)
      (catch Exception e
        (log/warn e "Ignoring malformed stored message diagnostics"
                  {:message-id (:message/id message)})
        nil))))

(defn- public-message
  [message include-diagnostics?]
  (let [base-message {:id (:message/id message)
                      :text (or (:message/text message) "")
                      :role (some-> (:message/role message) name)
                      :created (:message/created message)
                      :tags (db/normalize-tags (:message/tags message))
                      :filterValue (:message.filter/value message)
                      :chunks (mapv (fn [chunk]
                                      {:chunkId (:chunk/chunk-id chunk)
                                       :docTitle (or (:chunk/doc-title chunk) "")
                                       :docNum (:chunk/doc-num chunk)
                                       :contentMarkdown (or (:chunk/content-markdown chunk) "")})
                                    (or (:message/chunks message) []))}]
    (if-not include-diagnostics?
      base-message
      (if-let [diagnostics (message-diagnostics message)]
        (assoc base-message :diagnostics diagnostics)
        base-message))))

(defn format-conversation-list-response "Build a JSON Ring response from a paginated db result." [result] (let [response-data (mapv (fn [conv] {:id (:conversation/id conv), :topic (:conversation/topic conv), :agentId (:conversation/agent-id conv), :userId (:conversation/user-id conv), :tags (db/normalize-tags (:conversation/tags conv)), :created (:conversation/created conv)}) (:conversations result))] (-> (res/response (json/generate-string {:conversations response-data, :total (:total result), :pageSize (:page-size result), :pageIndex (:page-index result)})) (res/status 200) (res/content-type "application/json"))))

(defn list-conversations-handler "List conversations with pagination support for the caller-provided external API user id." [ring-req] (try (let [external-user-id (require-external-api-user-id! ring-req) conn (db/get-conn) query-params (request-query-params ring-req) {:keys [page-size page-index]} (parse-page-params ring-req) tags (parse-tags-filter query-params) result (db/conversations-by-user-paginated (clojure.core/deref conn) external-user-id page-size page-index tags)] (log/info "Listed conversations" {:external-user-id external-user-id, :page-size page-size, :page-index page-index, :tags tags, :total (:total result)}) (format-conversation-list-response result)) (catch clojure.lang.ExceptionInfo e (let [status (or (:status (ex-data e)) 500)] (log/error e "Failed to list conversations") (-> (res/response (api-error-body e)) (res/status status) (res/content-type "application/json")))) (catch Exception e (log/error e "Failed to list conversations") (-> (res/response (json/generate-string {:error "Failed to list conversations"})) (res/status 500) (res/content-type "application/json")))))

(defn admin-list-conversations-handler "List all conversations across all users with pagination (admin-only, JWT-authenticated)." [ring-req] (try (let [conn (db/get-conn) query-params (request-query-params ring-req) {:keys [page-size page-index]} (parse-page-params ring-req) tags (parse-tags-filter query-params) result (db/conversations-paginated (clojure.core/deref conn) page-size page-index tags)] (log/info "Admin listed all conversations" {:page-size page-size, :page-index page-index, :tags tags, :total (:total result)}) (format-conversation-list-response result)) (catch Exception e (log/error e "Failed to list conversations (admin)") (-> (res/response (json/generate-string {:error "Failed to list conversations"})) (res/status 500) (res/content-type "application/json")))))

(defn create-conversation-handler "Create a new conversation" [ring-req] (try (let [external-user-id (require-external-api-user-id! ring-req) conn (db/get-conn) params (request-body-params ring-req) agent-id (api-ctx/select-request-agent! ring-req params) filter-value (or (:filter-value params) (:filterValue params)) tags (when (contains? params :tags) (db/normalize-tags (:tags params))) result (db/transact-new-msg-thread conn agent-id external-user-id filter-value) convo-id (:conversation-id result) _ (when-let [title (:title params)] (db/rename-convo-topic conn convo-id title)) _ (when (contains? params :tags) (db/set-conversation-tags conn convo-id tags)) conversation (db/conversation-by-id (clojure.core/deref conn) convo-id) response-data {:id (:conversation/id conversation), :topic (:conversation/topic conversation), :agentId (:conversation/agent-id conversation), :userId (:conversation/user-id conversation), :tags (db/normalize-tags (:conversation/tags conversation)), :created (:conversation/created conversation)}] (log/info "Created conversation" {:external-user-id external-user-id, :conversation-id convo-id, :tags tags}) (-> (res/response (json/generate-string {:conversation response-data})) (res/status 201) (res/content-type "application/json"))) (catch clojure.lang.ExceptionInfo e (let [status (or (:status (ex-data e)) 500)] (log/error e "Failed to create conversation") (-> (res/response (api-error-body e)) (res/status status) (res/content-type "application/json")))) (catch Exception e (log/error e "Failed to create conversation") (-> (res/response (json/generate-string {:error "Failed to create conversation"})) (res/status 500) (res/content-type "application/json")))))

(defn get-conversation-handler "Get a specific conversation with its messages" [ring-req] (try (let [convo-id (:id (request-path-params ring-req)) query-params (request-query-params ring-req) external-user-id (require-external-api-user-id! ring-req) include-diagnostics? (boolean (:include_diagnostics query-params)) conn (db/get-conn) conversation (find-api-conversation! conn convo-id external-user-id) messages (db/fetch-convo-messages-mapped (clojure.core/deref conn) convo-id) response-data {:conversation {:id (:conversation/id conversation), :topic (:conversation/topic conversation), :agentId (:conversation/agent-id conversation), :userId (:conversation/user-id conversation), :tags (db/normalize-tags (:conversation/tags conversation)), :created (:conversation/created conversation)}, :messages (mapv #(public-message % include-diagnostics?) messages)}] (log/info "Retrieved conversation" {:conversation-id convo-id, :external-user-id external-user-id, :message-count (count messages), :include-diagnostics? include-diagnostics?}) (-> (res/response (json/generate-string response-data)) (res/status 200) (res/content-type "application/json"))) (catch clojure.lang.ExceptionInfo e (let [status (or (:status (ex-data e)) 500)] (log/error e "Failed to get conversation") (-> (res/response (api-error-body e)) (res/status status) (res/content-type "application/json")))) (catch Exception e (log/error e "Failed to get conversation") (-> (res/response (json/generate-string {:error "Failed to get conversation"})) (res/status 500) (res/content-type "application/json")))))

(defn update-conversation-handler "Update a conversation (rename, move to folder, etc.)" [ring-req] (try (let [convo-id (:id (request-path-params ring-req)) external-user-id (require-external-api-user-id! ring-req) params (request-body-params ring-req) conn (db/get-conn) _ (find-api-conversation! conn convo-id external-user-id) _ (when-let [title (:title params)] (db/rename-convo-topic conn convo-id title)) _ (when (contains? params :tags) (db/set-conversation-tags conn convo-id (db/normalize-tags (:tags params)))) conversation (find-api-conversation! conn convo-id external-user-id) response-data {:id (:conversation/id conversation), :topic (:conversation/topic conversation), :agentId (:conversation/agent-id conversation), :userId (:conversation/user-id conversation), :tags (db/normalize-tags (:conversation/tags conversation)), :created (:conversation/created conversation)}] (log/info "Updated conversation" {:conversation-id convo-id, :external-user-id external-user-id, :tags (when (contains? params :tags) (db/normalize-tags (:tags params)))}) (-> (res/response (json/generate-string {:conversation response-data})) (res/status 200) (res/content-type "application/json"))) (catch clojure.lang.ExceptionInfo e (let [status (or (:status (ex-data e)) 500)] (log/error e "Failed to update conversation") (-> (res/response (api-error-body e)) (res/status status) (res/content-type "application/json")))) (catch Exception e (log/error e "Failed to update conversation") (-> (res/response (json/generate-string {:error "Failed to update conversation"})) (res/status 500) (res/content-type "application/json")))))

(defn delete-conversation-handler "Delete a conversation" [ring-req] (try (let [convo-id (:id (request-path-params ring-req)) external-user-id (require-external-api-user-id! ring-req) conn (db/get-conn) conversation (find-api-conversation! conn convo-id external-user-id) _ (db/delete-convo conn (:db/id conversation))] (log/info "Deleted conversation" {:conversation-id convo-id, :external-user-id external-user-id}) (-> (res/response (json/generate-string {:success true})) (res/status 200) (res/content-type "application/json"))) (catch clojure.lang.ExceptionInfo e (let [status (or (:status (ex-data e)) 500)] (log/error e "Failed to delete conversation") (-> (res/response (api-error-body e)) (res/status status) (res/content-type "application/json")))) (catch Exception e (log/error e "Failed to delete conversation") (-> (res/response (json/generate-string {:error "Failed to delete conversation"})) (res/status 500) (res/content-type "application/json")))))

(defn list-users-handler "List all users with their permissions (admin only)" [_ring-req] (try (let [conn (db/get-conn) db (clojure.core/deref conn) users (d/q (quote [:find [(pull ?u [:user/id :user/email :user/created #:user{:permissions [:permission/id :permission/name]}]) ...] :where [?u :user/id]]) db) response-data (mapv (fn [user] {:id (:user/id user), :email (:user/email user), :created (:user/created user), :permissions (mapv (fn [p] {:id (:permission/id p), :name (:permission/name p)}) (:user/permissions user))}) users)] (log/info "Listed users" {:count (count users)}) (-> (res/response (json/generate-string {:users response-data})) (res/status 200) (res/content-type "application/json"))) (catch Exception e (log/error e "Failed to list users") (-> (res/response (json/generate-string {:error "Failed to list users"})) (res/status 500) (res/content-type "application/json")))))

(defn create-user-handler "Create a new user with initial permissions (admin only)" [ring-req] (try (let [admin-id (:user/id ring-req) params (request-body-params ring-req) email (:email params) permission-ids (or (:permissions params) []) _ (when-not email (throw (ex-info "Missing required field: email" {:status 400}))) conn (db/get-conn) result (auth/create-new-user {:email email, :creator-id admin-id})] (if (:error result) (throw (ex-info (:error result) {:status 409})) (let [user (auth/user-by-email email) user-id (:user/id user) _ (doseq [perm-id permission-ids] (perms/grant-permission! conn user-id perm-id)) db (clojure.core/deref conn) updated-user (d/q (quote [:find (pull ?u [:user/id :user/email :user/created #:user{:permissions [:permission/id :permission/name]}]) . :in $ ?user-id :where [?u :user/id ?user-id]]) db user-id)] (log/info "Created user" {:email email, :user-id user-id, :permissions permission-ids}) (-> (res/response (json/generate-string {:user {:id (:user/id updated-user), :email (:user/email updated-user), :created (:user/created updated-user), :permissions (mapv (fn [p] {:id (:permission/id p), :name (:permission/name p)}) (:user/permissions updated-user))}})) (res/status 201) (res/content-type "application/json"))))) (catch clojure.lang.ExceptionInfo e (let [data (ex-data e) status (or (:status data) 500)] (log/error e "Failed to create user") (-> (res/response (api-error-body e)) (res/status status) (res/content-type "application/json")))) (catch Exception e (log/error e "Unexpected error creating user") (-> (res/response (json/generate-string {:error "Internal server error"})) (res/status 500) (res/content-type "application/json")))))

(defn get-user-handler "Get a specific user with their permissions (admin only)" [ring-req] (try (let [user-id (:id (request-path-params ring-req)) conn (db/get-conn) db (clojure.core/deref conn) user (d/q (quote [:find (pull ?u [:user/id :user/email :user/created #:user{:permissions [:permission/id :permission/name]}]) . :in $ ?user-id :where [?u :user/id ?user-id]]) db user-id)] (if user (-> (res/response (json/generate-string {:user {:id (:user/id user), :email (:user/email user), :created (:user/created user), :permissions (mapv (fn [p] {:id (:permission/id p), :name (:permission/name p)}) (:user/permissions user))}})) (res/status 200) (res/content-type "application/json")) (-> (res/response (json/generate-string {:error "User not found"})) (res/status 404) (res/content-type "application/json")))) (catch Exception e (log/error e "Failed to get user") (-> (res/response (json/generate-string {:error "Failed to get user"})) (res/status 500) (res/content-type "application/json")))))

(defn update-user-permissions-handler "Update a user's permissions (admin only)" [ring-req] (try (let [user-id (:id (request-path-params ring-req)) params (request-body-params ring-req) permissions-to-add (:add params) permissions-to-remove (:remove params) conn (db/get-conn) db (clojure.core/deref conn) user (d/q (quote [:find ?u . :in $ ?user-id :where [?u :user/id ?user-id]]) db user-id) _ (when-not user (throw (ex-info "User not found" {:status 404}))) _ (doseq [perm-id permissions-to-add] (perms/grant-permission! conn user-id perm-id)) _ (doseq [perm-id permissions-to-remove] (perms/revoke-permission! conn user-id perm-id)) updated-db (clojure.core/deref conn) updated-user (d/q (quote [:find (pull ?u [:user/id :user/email :user/created #:user{:permissions [:permission/id :permission/name]}]) . :in $ ?user-id :where [?u :user/id ?user-id]]) updated-db user-id)] (log/info "Updated user permissions" {:user-id user-id, :added permissions-to-add, :removed permissions-to-remove}) (-> (res/response (json/generate-string {:user {:id (:user/id updated-user), :email (:user/email updated-user), :created (:user/created updated-user), :permissions (mapv (fn [p] {:id (:permission/id p), :name (:permission/name p)}) (:user/permissions updated-user))}})) (res/status 200) (res/content-type "application/json"))) (catch clojure.lang.ExceptionInfo e (let [data (ex-data e) status (or (:status data) 500)] (log/error e "Failed to update user permissions") (-> (res/response (api-error-body e)) (res/status status) (res/content-type "application/json")))) (catch Exception e (log/error e "Unexpected error updating user permissions") (-> (res/response (json/generate-string {:error "Internal server error"})) (res/status 500) (res/content-type "application/json")))))

(defn delete-user-handler "Delete a user (admin only)" [ring-req] (try (let [target-user-id (:id (request-path-params ring-req)) requesting-user-id (:user/id ring-req) _ (when (= target-user-id requesting-user-id) (throw (ex-info "Cannot delete your own account" {:status 400}))) conn (db/get-conn) db (clojure.core/deref conn) user-eid (d/q (quote [:find ?u . :in $ ?user-id :where [?u :user/id ?user-id]]) db target-user-id) _ (when-not user-eid (throw (ex-info "User not found" {:status 404}))) _ (d/transact conn {:tx-data [[:db/retractEntity user-eid]]})] (log/info "Deleted user" {:user-id target-user-id, :deleted-by requesting-user-id}) (-> (res/response (json/generate-string {:success true, :message "User deleted"})) (res/status 200) (res/content-type "application/json"))) (catch clojure.lang.ExceptionInfo e (let [data (ex-data e) status (or (:status data) 500)] (log/error e "Failed to delete user") (-> (res/response (api-error-body e)) (res/status status) (res/content-type "application/json")))) (catch Exception e (log/error e "Unexpected error deleting user") (-> (res/response (json/generate-string {:error "Internal server error"})) (res/status 500) (res/content-type "application/json")))))

(defn list-permissions-handler "List all available permissions (admin only)" [_ring-req] (try (let [conn (db/get-conn) db (clojure.core/deref conn) permissions (perms/get-all-permissions db) response-data (mapv (fn [p] {:id (:permission/id p), :name (:permission/name p), :description (:permission/description p)}) permissions)] (-> (res/response (json/generate-string {:permissions response-data})) (res/status 200) (res/content-type "application/json"))) (catch Exception e (log/error e "Failed to list permissions") (-> (res/response (json/generate-string {:error "Failed to list permissions"})) (res/status 500) (res/content-type "application/json")))))

(ns digdir.api.routes.handlers
  "API key lifecycle handlers: list, create, rotate, revoke, update
   allowed config keys, and list access policies — plus the helpers
   that shape their public response maps.

   No query handlers live here. API-key authenticated query traffic is
   served by the MCP endpoint (POST /api/mcp, digdir.mcp.transport)."
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [digdir.api.context :as api-ctx]
            [digdir.api.util :refer [api-error-body
                                     request-body-params
                                     request-path-params
                                     ]]
            [digdir.config.api-keys :as api-keys]
            [digdir.config.core :as config-core]
            [digdir.config.db :as config-db]
            [digdir.data.db :as db]
            [ring.util.response :as res]))

;; ===== Helper Functions =====

(defn- public-allowed-config-keys
  [allowed-config-keys]
  (mapv (fn [allowed-config-key]
          (let [root (or (:root allowed-config-key) (:api-key.allowed-config-key/root allowed-config-key))
                tenant (or (:tenant allowed-config-key) (:api-key.allowed-config-key/tenant allowed-config-key))
                node-id (or (:node-id allowed-config-key) (:api-key.allowed-config-key/node-id allowed-config-key))
                config-key (or (:tenant-config-key allowed-config-key)
                               (:api-key.allowed-config-key/tenant-config-key allowed-config-key)
                               (:runtime-config-key allowed-config-key)
                               (:dataset-config-key allowed-config-key))
                response-key (api-ctx/root-config-key-param root)]
            (cond-> {:root root
                     :tenant tenant}
              node-id (assoc :node-id node-id)
              config-key (assoc response-key config-key))))
        (or allowed-config-keys [])))

;; ----------------------------------------------------------------------------
;; The skill-graph grant, on the wire (#167)
;;
;; Stored as `:api-key/skill-graphs` / `:access-policy/skill-graphs` - Datahike
;; attributes, so renaming them would mean migrating every existing API key and
;; access policy. A data migration to fix a vocabulary inconsistency is the
;; wrong trade.
;;
;; So the rename happens HERE, at the advertisement boundary, and only here:
;; clients see `modes`, storage keeps `skill-graphs`. Same split #122 settled
;; for the public identifier and #162 for the query argument.
;;
;; Both directions go through this one place on purpose. The failure mode worth
;; avoiding is a half-rename where the request field and the response field
;; disagree, which is worse than the inconsistency it replaces.
;; ----------------------------------------------------------------------------

(def public-modes-field
  "The name clients use for the skill-graph grant, in request and response."
  :modes)

(def stored-modes-attribute
  "Where that grant actually lives, per entity type. Unchanged by the rename."
  {:api-key :api-key/skill-graphs
   :access-policy :access-policy/skill-graphs})

(defn- public-api-key
  [key-info]
  (let [policy-id (get-in key-info [:api-key/policy :access-policy/id])]
    (cond-> {:scopes (vec (or (:api-key/scopes key-info) []))
             :expires-at (:api-key/expires-at key-info)
             :api-key-id (:api-key/id key-info)
             :key-prefix (:api-key/prefix key-info)
             :key-last-four (:api-key/last-four key-info)
             :client-id (first (or (:api-key/clients key-info) []))
             :name (:api-key/name key-info)
             :usage-count (:api-key/usage-count key-info)
             :allowed-config-keys (public-allowed-config-keys (:api-key/allowed-config-keys key-info))
             :created (:api-key/created key-info)
             :revoked (boolean (:api-key/revoked key-info))
             :dataset-scopes (mapv api-ctx/public-dataset-scope (vec (or (:api-key/dataset-scopes key-info) [])))
             :created-by (:api-key/created-by key-info)
             :agent-refs (vec (or (:api-key/agent-refs key-info) []))
             public-modes-field (vec (or (:api-key/skill-graphs key-info) []))
             :last-used (:api-key/last-used key-info)}
      policy-id (assoc :policy-id policy-id))))

(defn- public-access-policy
  [policy-info]
  {:policy-id (:access-policy/id policy-info)
   :name (:access-policy/name policy-info)
   :created (:access-policy/created policy-info)
   :created-by (:access-policy/created-by policy-info)
   :tenants (vec (or (:access-policy/tenants policy-info) []))
   :clients (vec (or (:access-policy/clients policy-info) []))
   :scopes (vec (or (:access-policy/scopes policy-info) []))
   public-modes-field (vec (or (:access-policy/skill-graphs policy-info) []))
   :dataset-scopes (mapv api-ctx/public-dataset-scope (vec (or (:access-policy/dataset-scopes policy-info) [])))
   :agent-refs (vec (or (:api-key.agent-ref/agent-id policy-info)
                        (:access-policy/agent-refs policy-info) []))
   :allowed-config-keys (public-allowed-config-keys (or (:access-policy/allowed-config-keys policy-info)
                                                        (:access-policy/config-ceilings policy-info)))})


;; ===== API Key Management Handlers =====

(defn list-api-keys-handler
  "List all API keys for the authenticated user"
  [ring-req]
  (try
    (let [user-id (:user/id ring-req)
          conn (db/get-conn)
          keys (mapv public-api-key (api-keys/list-api-keys conn user-id))]
      (-> (res/response (json/generate-string {:api-keys keys}))
          (res/status 200)
          (res/content-type "application/json")))

    (catch Exception e
      (log/error e "Failed to list API keys")
      (-> (res/response
            (json/generate-string {:error "Internal server error"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn list-access-policies-handler
  "List all access policies for the authenticated user"
  [ring-req]
  (try
    (let [user-id (:user/id ring-req)
          conn (db/get-conn)
          policies (mapv public-access-policy (api-keys/list-access-policies conn user-id))]
      (-> (res/response (json/generate-string {:access-policies policies}))
          (res/status 200)
          (res/content-type "application/json")))

    (catch Exception e
      (log/error e "Failed to list access policies")
      (-> (res/response
            (json/generate-string {:error "Internal server error"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn create-api-key-handler
  "Create a new API key for the authenticated user"
  [ring-req]
  (try
    (let [user-id (:user/id ring-req)
          user-email (:user/email ring-req)
          params (request-body-params ring-req)
          name (:name params)
          policy-id (:policy-id params)
          dataset-scopes (cond
                           (seq (:dataset-scopes params))
                           (api-ctx/normalize-dataset-scopes! (:dataset-scopes params))

                           :else [])
          client-id (:client-id params)
          agent-refs (api-ctx/validate-agent-refs! (:agent-refs params))
          allowed-config-keys (let [requested-allowed-config-keys (:allowed-config-keys params)]
                                (if (some? requested-allowed-config-keys)
                                  (api-ctx/normalize-allowed-config-keys! requested-allowed-config-keys)
                                  (when-not (or policy-id (empty? dataset-scopes))
                                    (->> dataset-scopes
                                     (map :tenant)
                                     distinct
                                     (mapcat (fn [tenant]
                                               [{:root "platform"
                                                 :tenant tenant
                                                 :tenant-config-key "default"}
                                                {:root "runtime"
                                                 :tenant tenant
                                                 :runtime-config-key "default"}
                                                {:root "dataset"
                                                 :tenant tenant
                                                 :dataset-config-key "default"}]))
                                     vec))))
          ;; public name in, stored name onward - the boundary is this line
          skill-graphs (or (get params public-modes-field) [])
          _ (when-not name
              (throw (ex-info "Missing required field: name" {:status 400})))
          _ (when (and (not policy-id) (not (seq dataset-scopes)))
              (throw (ex-info "Missing required field: dataset-scopes or policy-id" {:status 400})))
          _ (doseq [dataset-scope dataset-scopes]
              (when-not (config-db/get-dataset-by-ref @(db/get-conn)
                                                      dataset-scope
                                                      (config-core/get-master-key))
                (throw (ex-info (str "Dataset not found for dataset-scope: " (pr-str dataset-scope))
                                {:status 404
                                 :dataset-scope dataset-scope}))))

          ;; Generate and store API key
          new-key (api-keys/generate-api-key)
          conn (db/get-conn)
          result (api-keys/store-api-key conn
                                         new-key
                                         name
                                         user-id
                                         {:policy-id policy-id
                                          :clients (when client-id [client-id])
                                          :dataset-scopes dataset-scopes
                                          :agent-refs agent-refs
                                          :allowed-config-keys allowed-config-keys
                                          :skill-graphs skill-graphs
                                          :user-email user-email})]

      (log/info "API key created"
                {:user-id user-id
                 :api-key-id (:api-key-id result)
                 :policy-id (get-in result [:api-key/policy :access-policy/id])})

      (let [key-info (api-keys/get-api-key-info conn (:api-key-id result))]
        (-> (res/response
              (json/generate-string
               (assoc (public-api-key key-info)
                      :api-key (:api-key result)
                      :warning "This is the only time you will see this API key. Store it securely.")))
            (res/status 201)
            (res/content-type "application/json"))))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Failed to create API key")
        (-> (res/response (api-error-body e))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Unexpected error creating API key")
      (-> (res/response
            (json/generate-string {:error "Internal server error"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn rotate-api-key-handler
  "Rotate an API key for the authenticated user"
  [ring-req]
  (try
    (let [user-id (:user/id ring-req)
          user-email (:user/email ring-req)
          api-key-id (:key-id (request-path-params ring-req))
          _ (when-not api-key-id
              (throw (ex-info "Missing API key ID" {:status 400})))

          conn (db/get-conn)
          key-info (api-keys/get-api-key-info conn api-key-id)
          _ (when-not key-info
              (throw (ex-info "API key not found" {:status 404})))
          _ (when-not (= user-id (:api-key/created-by key-info))
              (throw (ex-info "Unauthorized" {:status 403})))

          result (api-keys/rotate-api-key! conn api-key-id
                                           {:user-email user-email
                                            :user-id user-id})]
      
      (log/info "API key rotated"
                {:user-id user-id
                 :old-api-key-id api-key-id
                 :new-api-key-id (:api-key-id result)})

      (let [new-key-info (api-keys/get-api-key-info conn (:api-key-id result))]
        (-> (res/response
              (json/generate-string
               (assoc (public-api-key new-key-info)
                      :api-key (:api-key result)
                      :warning "This is the only time you will see this API key. Store it securely.")))
            (res/status 200)
            (res/content-type "application/json"))))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Failed to rotate API key")
        (-> (res/response (api-error-body e))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Unexpected error rotating API key")
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
          api-key-id (:key-id (request-path-params ring-req))
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
                                     {:user-email user-email
                                      :user-id user-id})]

      (log/info "API key revoked"
                {:user-id user-id
                 :api-key-id api-key-id})

      (-> (res/response (json/generate-string {:success true
                                               :message "API key revoked"}))
          (res/status 200)
          (res/content-type "application/json")))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Failed to revoke API key")
        (-> (res/response (api-error-body e))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Unexpected error revoking API key")
      (-> (res/response
            (json/generate-string {:error "Internal server error"}))
          (res/status 500)
          (res/content-type "application/json")))))

(defn update-api-key-allowed-config-keys-handler
  "Replace the allowed config keys for an API key owned by the authenticated user."
  [ring-req]
  (try
    (let [user-id (:user/id ring-req)
          user-email (:user/email ring-req)
          api-key-id (:key-id (request-path-params ring-req))
          _ (when-not api-key-id
              (throw (ex-info "Missing API key ID" {:status 400})))
          params (request-body-params ring-req)
          _ (when-not (contains? params :allowed-config-keys)
              (throw (ex-info "Missing required field: allowed-config-keys" {:status 400})))
          conn (db/get-conn)
          key-info (api-keys/get-api-key-info conn api-key-id)
          _ (when-not key-info
              (throw (ex-info "API key not found" {:status 404})))
          _ (when-not (= user-id (:api-key/created-by key-info))
              (throw (ex-info "Unauthorized" {:status 403})))
          updated-key (api-keys/replace-api-key-allowed-config-keys!
                       conn
                       api-key-id
                       (api-ctx/normalize-allowed-config-keys! (:allowed-config-keys params))
                       {:user-email user-email
                        :user-id user-id})]
      (log/info "API key allowed config keys updated"
                {:user-id user-id
                 :api-key-id api-key-id
                 :allowed-config-key-count (count (:api-key/allowed-config-keys updated-key))})
      (-> (res/response
            (json/generate-string
             {:api-key-id (:api-key/id updated-key)
              :name (:api-key/name updated-key)
              :allowed-config-keys (public-allowed-config-keys (:api-key/allowed-config-keys updated-key))}))
          (res/status 200)
          (res/content-type "application/json")))

    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            status (or (:status data) 500)]
        (log/error e "Failed to update API key allowed config keys")
        (-> (res/response (api-error-body e))
            (res/status status)
            (res/content-type "application/json"))))

    (catch Exception e
      (log/error e "Unexpected error updating API key allowed config keys")
      (-> (res/response
            (json/generate-string {:error "Internal server error"}))
          (res/status 500)
          (res/content-type "application/json")))))

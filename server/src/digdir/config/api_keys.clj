(ns digdir.config.api-keys
  (:require [datahike.api :as d]
            [digdir.data.db :as db]
            [digdir.config.audit :as audit]
            [nano-id.core :refer [nano-id]]
            [tick.core :as t]))

;; API Key Generation and Management
;;
;; This namespace provides functions for generating, storing, validating,
;; and revoking API keys used for authentication to the RAG API endpoint.
;;
;; Features:
;; - API keys are 32-byte random hex strings (256 bits of entropy)
;; - Keys are stored in plaintext for easy display and management
;; - Keys can be revoked without deletion (maintains audit trail)
;; - Last-used timestamp is tracked for monitoring
;; - All operations are logged to the audit log

(defn generate-api-key
  "Generate a secure random API key (32-byte hex string).
  Format: 'rag_' prefix followed by 64 hex characters.
  Example: rag_a1b2c3d4e5f6...

  The 'rag_' prefix makes it easy to identify RAG API keys in logs/code."
  []
  (let [random-bytes (byte-array 32)]
    (doto (java.security.SecureRandom.)
      (.nextBytes random-bytes))
    (str "rag_" (apply str (map #(format "%02x" %) random-bytes)))))

(def valid-scopes
  "Set of valid API key scopes"
  #{:query :ingest :admin})

(defn store-api-key
  "Store a new API key in the Datahike database.

  Args:
    conn - Datahike connection
    api-key - The plaintext API key
    name - Descriptive name for the key (e.g., 'Production API', 'Test Client')
    created-by - User ID of the person creating the key
    opts - Map with:
           :tenants - Collection of tenant IDs this key has access to
           :environments - Collection of environments this key has access to
           :entities - Collection of entity IDs this key has access to
           :scopes - Set of scopes #{:query :ingest :admin}, defaults to #{:query}
           :expires-at - Optional expiration timestamp (epoch ms)
           :user-email - Email of user creating the key (for audit)

  Returns:
    Map with :api-key-id and :api-key"
  ([conn api-key name created-by]
   (store-api-key conn api-key name created-by {}))
  ([conn api-key name created-by {:keys [tenants environments entities scopes expires-at user-email]}]
   (let [api-key-id (nano-id)
         now (.getTime (t/inst (t/now)))
         validated-scopes (or (seq (filter valid-scopes scopes)) [:query])
         ;; Convert to vectors for storage
         tenants-vec (vec (or tenants []))
         environments-vec (vec (or environments []))
         entities-vec (vec (or entities []))
         tx-data (cond-> {:api-key/id api-key-id
                          :api-key/key api-key
                          :api-key/name name
                          :api-key/created now
                          :api-key/created-by created-by
                          :api-key/revoked false
                          :api-key/scopes (vec validated-scopes)
                          :api-key/usage-count 0}
                   (seq tenants-vec) (assoc :api-key/tenants tenants-vec)
                   (seq environments-vec) (assoc :api-key/environments environments-vec)
                   (seq entities-vec) (assoc :api-key/entities entities-vec)
                   expires-at (assoc :api-key/expires-at expires-at))]
     (d/transact conn {:tx-data [tx-data]})
     ;; Audit log the creation
     (audit/log-api-key-change! conn
       {:action :create
        :api-key-id api-key-id
        :api-key-name name
        :tenants tenants-vec
        :environments environments-vec
        :entities entities-vec
        :user-email user-email
        :user-id created-by})
     {:api-key-id api-key-id
      :api-key api-key})))

(defn create-api-key!
  "Create a new API key with all parameters. This is a convenience function
   that generates the key and stores it in one atomic operation.

   Args:
     conn - Datahike connection
     key-name - Descriptive name for the key
     created-by - User ID of the person creating the key
     opts - Map with:
            :tenants - Collection of tenant IDs this key has access to
            :environments - Collection of environments this key has access to
            :entities - Collection of entity IDs this key has access to
            :scopes - Collection of scopes (will be converted to set and validated)
            :expires-in-days - Optional number of days until expiration (nil for no expiration)
            :user-email - Email of user creating the key (for audit)

   Returns:
     Map with :api-key (plaintext, shown only once) and :name"
  [conn key-name created-by {:keys [tenants environments entities scopes expires-in-days user-email]}]
  (let [new-key (generate-api-key)
        expires-at (when (and expires-in-days (pos? expires-in-days))
                     (+ (System/currentTimeMillis)
                        (* expires-in-days 24 60 60 1000)))]
    (store-api-key conn
                   new-key
                   key-name
                   created-by
                   {:tenants tenants
                    :environments environments
                    :entities entities
                    :scopes (set scopes)
                    :expires-at expires-at
                    :user-email user-email})
    {:api-key new-key
     :name key-name}))

(defn validate-api-key
  "Validate an API key and return associated information if valid.

  Also updates the last-used timestamp and usage count as a side effect.

  Args:
    conn - Datahike connection
    api-key - The API key to validate

  Returns:
    Map with :tenants, :environments, :entities, :api-key-id, :name, :scopes if valid, nil otherwise

  Validation checks:
  - Key exists in database
  - Key is not revoked
  - Key is not expired"
  [conn api-key]
  (when api-key
    (let [db @conn
          key-entity (d/q '[:find (pull ?e [*]) .
                            :in $ ?api-key
                            :where
                            [?e :api-key/key ?api-key]]
                          db api-key)
          now (.getTime (t/inst (t/now)))
          expired? (when-let [expires-at (:api-key/expires-at key-entity)]
                     (> now expires-at))]
      (when (and key-entity
                 (not (:api-key/revoked key-entity))
                 (not expired?))
        ;; Update last-used timestamp and increment usage count
        (d/transact conn
                    {:tx-data [{:api-key/id (:api-key/id key-entity)
                                :api-key/last-used now
                                :api-key/usage-count (inc (or (:api-key/usage-count key-entity) 0))}]})
        ;; Return key info - support both old and new schema
        {:tenants (vec (:api-key/tenants key-entity))
         :environments (vec (:api-key/environments key-entity))
         :entities (vec (:api-key/entities key-entity))
         ;; Backwards compatibility: include entity-id if set
         :entity-id (:api-key/entity-id key-entity)
         :api-key-id (:api-key/id key-entity)
         :name (:api-key/name key-entity)
         :scopes (set (:api-key/scopes key-entity))}))))

(defn revoke-api-key
  "Revoke an API key by ID. Does not delete the key (maintains audit trail).

  Args:
    conn - Datahike connection
    api-key-id - The ID of the API key to revoke
    opts - Optional map with :user-email and :user-id for audit logging

  Returns:
    Boolean - true if key was found and revoked, false otherwise"
  ([conn api-key-id]
   (revoke-api-key conn api-key-id {}))
  ([conn api-key-id {:keys [user-email user-id]}]
   (let [db @conn
         key-entity (d/entity db [:api-key/id api-key-id])]
     (when key-entity
       (d/transact conn
                   {:tx-data [{:api-key/id api-key-id
                               :api-key/revoked true}]})
       ;; Audit log the revocation
       (audit/log-api-key-change! conn
         {:action :revoke
          :api-key-id api-key-id
          :api-key-name (:api-key/name key-entity)
          :entity-id (:api-key/entity-id key-entity)
          :user-email user-email
          :user-id user-id})
       true))))

(defn update-api-key-entity
  "Update the entity-id for an existing API key.

  Args:
    conn - Datahike connection
    api-key-id - The ID of the API key to update
    new-entity-id - The new entity ID to assign
    opts - Optional map with :user-email and :user-id for audit logging

  Returns:
    Boolean - true if key was found and updated, false otherwise"
  ([conn api-key-id new-entity-id]
   (update-api-key-entity conn api-key-id new-entity-id {}))
  ([conn api-key-id new-entity-id {:keys [user-email user-id]}]
   (let [db @conn
         key-entity (d/entity db [:api-key/id api-key-id])
         old-entity-id (:api-key/entity-id key-entity)]
     (when key-entity
       (d/transact conn
                   {:tx-data [{:api-key/id api-key-id
                               :api-key/entity-id new-entity-id}]})
       ;; Audit log the update
       (audit/log-api-key-change! conn
         {:action :update
          :api-key-id api-key-id
          :api-key-name (:api-key/name key-entity)
          :entity-id new-entity-id
          :previous-entity-id old-entity-id
          :user-email user-email
          :user-id user-id})
       true))))

(defn list-api-keys
  "List all API keys created by a specific user.

  Args:
    conn - Datahike connection
    user-id - User ID to filter by

  Returns:
    Vector of maps with API key information (without the actual key)"
  [conn user-id]
  (let [db @conn]
    (d/q '[:find [(pull ?e [:api-key/id
                             :api-key/name
                             :api-key/entity-id
                             :api-key/tenants
                             :api-key/environments
                             :api-key/entities
                             :api-key/created
                             :api-key/revoked
                             :api-key/last-used
                             :api-key/scopes
                             :api-key/usage-count
                             :api-key/expires-at]) ...]
           :in $ ?user-id
           :where
           [?e :api-key/created-by ?user-id]]
         db user-id)))

(defn list-all-api-keys
  "List all API keys in the system (admin function).

  Args:
    db - Datahike db value

  Returns:
    Vector of maps with API key information including creator and the key itself.
    Note: scopes are converted to vectors for Transit serialization compatibility."
  [db]
  (let [results (d/q '[:find [(pull ?e [:api-key/id
                                         :api-key/key
                                         :api-key/name
                                         :api-key/entity-id
                                         :api-key/tenants
                                         :api-key/environments
                                         :api-key/entities
                                         :api-key/created
                                         :api-key/created-by
                                         :api-key/revoked
                                         :api-key/last-used
                                         :api-key/scopes
                                         :api-key/usage-count
                                         :api-key/expires-at]) ...]
                       :where
                       [?e :api-key/id]]
                     db)]
    ;; Convert scopes from set to vector for Transit serialization
    (mapv #(-> %
               (update :api-key/scopes vec)
               (update :api-key/tenants vec)
               (update :api-key/environments vec)
               (update :api-key/entities vec))
          results)))

(defn get-api-key-info
  "Get detailed information about an API key by ID.

  Args:
    conn - Datahike connection
    api-key-id - The ID of the API key

  Returns:
    Map with API key information (without the actual key)"
  [conn api-key-id]
  (let [db @conn]
    (d/q '[:find (pull ?e [:api-key/id
                            :api-key/name
                            :api-key/entity-id
                            :api-key/tenants
                            :api-key/environments
                            :api-key/entities
                            :api-key/created
                            :api-key/created-by
                            :api-key/revoked
                            :api-key/last-used
                            :api-key/scopes
                            :api-key/usage-count
                            :api-key/expires-at]) .
           :in $ ?api-key-id
           :where
           [?e :api-key/id ?api-key-id]]
         db api-key-id)))

(comment
  ;; Usage examples

  ;; Generate a new API key
  (def new-key (generate-api-key))
  ;; => "rag_a1b2c3d4..."

  ;; Store it in the database with multi-tenant/environment/entity support
  (def conn (db/get-conn))
  (def result (store-api-key conn new-key "Test API Key" "user-123"
                             {:tenants ["ka" "altinn"]
                              :environments ["prod" "test"]
                              :entities ["my-bot" "other-bot"]
                              :scopes #{:query :ingest}}))
  ;; => {:api-key-id "abc123", :api-key "rag_a1b2c3d4..."}

  ;; Create key with convenience function
  (create-api-key! conn "My Key" "user-123"
                   {:tenants ["ka"]
                    :environments ["prod"]
                    :entities ["my-bot"]
                    :scopes [:query]
                    :expires-in-days 30})
  ;; => {:api-key "rag_...", :name "My Key"}

  ;; Validate the key
  (validate-api-key conn new-key)
  ;; => {:tenants ["ka" "altinn"], :environments ["prod" "test"], :entities ["my-bot"], ...}

  ;; List keys for a user
  (list-api-keys conn "user-123")
  ;; => [{:api-key/id "abc123", :api-key/name "Test API Key", ...}]

  ;; Revoke a key
  (revoke-api-key conn "abc123")
  ;; => true

  ;; Try to validate revoked key
  (validate-api-key conn new-key)
  ;; => nil
  )

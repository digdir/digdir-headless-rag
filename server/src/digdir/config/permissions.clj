(ns digdir.config.permissions
  "ABAC (Attribute-Based Access Control) for configuration.

   Permissions are evaluated based on multiple attributes:
   - service: :llm, :search, :auth, :storage, :email, :other
   - sensitivity: :public, :internal, :admin-only, :secret
   - function: :prompts, :credentials, :settings, :features, :i18n

   Attribute matching supports:
   - :* - wildcard (matches everything)
   - :exact-value - exact match
   - #{:val1 :val2} - matches any value in set
   - :!value - negation (matches anything except value)"
  (:require [datahike.api :as d]
            [clojure.edn :as edn]
            [clojure.set]
            [clojure.string :as str]))

;; =============================================================================
;; Attribute Parsing
;; =============================================================================

(defn parse-attribute-spec
  "Parse an attribute specification.

   Returns: {:type :wildcard|:exact|:set|:negation, :value ...}

   Examples:
   - :* -> {:type :wildcard}
   - :llm -> {:type :exact :value :llm}
   - #{:llm :search} -> {:type :set :values #{:llm :search}}
   - :!credentials -> {:type :negation :value :credentials}"
  [spec]
  (cond
    (= :* spec)
    {:type :wildcard}

    (and (keyword? spec)
         (str/starts-with? (name spec) "!"))
    {:type :negation :value (keyword (subs (name spec) 1))}

    (set? spec)
    {:type :set :values spec}

    (keyword? spec)
    {:type :exact :value spec}

    :else
    {:type :unknown :raw spec}))

(defn matches-attribute?
  "Check if a config attribute matches a permission spec.

   Args:
     config-attr - The actual attribute value on the config (e.g., :llm)
     permission-spec - The permission spec (e.g., :*, #{:llm :search}, :!credentials)"
  [config-attr permission-spec]
  (let [parsed (parse-attribute-spec permission-spec)]
    (case (:type parsed)
      :wildcard true
      :negation (not= config-attr (:value parsed))
      :set (contains? (:values parsed) config-attr)
      :exact (= config-attr (:value parsed))
      false)))

;; =============================================================================
;; Permission Evaluation
;; =============================================================================

(defn permission->attributes
  "Parse a permission's EDN-encoded attributes string."
  [permission]
  (when-let [attrs-str (:permission/attributes permission)]
    (edn/read-string attrs-str)))

(defn permission->tenants
  "Parse a permission's EDN-encoded tenants string."
  [permission]
  (when-let [tenants-str (:permission/tenants permission)]
    (edn/read-string tenants-str)))

(defn permission->tenant-config-keys
  "Parse a permission's EDN-encoded tenant-config-keys string."
  [permission]
  (when-let [keys-str (:permission/tenant-config-keys permission)]
    (edn/read-string keys-str)))

(defn permission->actions
  "Parse a permission's EDN-encoded actions string."
  [permission]
  (when-let [actions-str (:permission/actions permission)]
    (edn/read-string actions-str)))

(defn matches-context?
  "Check if a permission matches the tenant/tenant-config-key context."
  [permission tenant tenant-config-key]
  (let [perm-tenants (permission->tenants permission)
        perm-keys (permission->tenant-config-keys permission)]
    (and
     ;; Check tenant
     (or (= :* perm-tenants)
         (nil? perm-tenants)
         (and (set? perm-tenants) (contains? perm-tenants tenant)))

     ;; Check tenant-config-key
     (or (= :* perm-keys)
         (nil? perm-keys)
         (and (set? perm-keys) (contains? perm-keys tenant-config-key))))))

(defn permission-matches?
  "Check if a permission matches a config entry and action.

   Args:
     permission - Permission entity
     config-def - Config definition entity
     action - :read or :write
     tenant - Current tenant
     tenant-config-key - Current tenant-config-key

   Returns: true if permission grants the requested access"
  [permission config-def action tenant tenant-config-key]
  (let [perm-attrs (permission->attributes permission)
        perm-actions (permission->actions permission)

        config-service (:config-def/service config-def)
        config-sensitivity (:config-def/sensitivity config-def)
        config-function (:config-def/function config-def)]

    (and
     ;; Check action is allowed
     (or (nil? perm-actions)
         (contains? perm-actions action))

     ;; Check context (tenant/tenant-config-key)
     (matches-context? permission tenant tenant-config-key)

     ;; Check all attribute dimensions
     (matches-attribute? config-service (:service perm-attrs :*))
     (matches-attribute? config-sensitivity (:sensitivity perm-attrs :*))
     (matches-attribute? config-function (:function perm-attrs :*)))))

;; =============================================================================
;; User Permission Queries
;; =============================================================================

(defn get-user-permissions
  "Get all permissions assigned to a user.

   Args:
     db - Datahike database value
     user-id - User ID

   Returns: List of permission entities"
  [db user-id]
  (d/q '[:find [(pull ?p [*]) ...]
         :in $ ?user-id
         :where
         [?u :user/id ?user-id]
         [?u :user/permissions ?p]]
       db user-id))

(defn is-admin?
  "Check if a user has admin-full permission."
  [db user-id]
  (let [permissions (get-user-permissions db user-id)]
    (some #(= "admin-full" (:permission/id %)) permissions)))

;; =============================================================================
;; Access Control Functions
;; =============================================================================

(defn can-access?
  "Check if a user can access a config path with a given action.

   Args:
     db - Datahike database value
     user-id - User ID
     path - Config path (string)
     action - :read or :write
     opts - Options:
       :tenant - Current tenant (default: nil)
       :tenant-config-key - Current tenant-config-key (default: nil)

   Returns: true if access is allowed"
  [db user-id path action & [{:keys [tenant tenant-config-key]}]]
  (let [config-def (d/q '[:find (pull ?e [*]) .
                          :in $ ?path
                          :where [?e :config-def/path ?path]]
                        db path)
        user-permissions (get-user-permissions db user-id)]

    (if (nil? config-def)
      ;; No definition = no access control = allow by default
      true

      ;; Check if any permission grants access
      (some #(permission-matches? % config-def action tenant tenant-config-key)
            user-permissions))))

(defn evaluate-access
  "Evaluate access and return detailed result.

   Returns: {:allowed? bool
             :matched-permission permission-id or nil
             :reason string}"
  [db user-id path action & [{:keys [tenant tenant-config-key]}]]
  (let [config-def (d/q '[:find (pull ?e [*]) .
                          :in $ ?path
                          :where [?e :config-def/path ?path]]
                        db path)
        user-permissions (get-user-permissions db user-id)]

    (cond
      (nil? config-def)
      {:allowed? true
       :matched-permission nil
       :reason "No config definition - access allowed by default"}

      (empty? user-permissions)
      {:allowed? false
       :matched-permission nil
       :reason "User has no permissions assigned"}

      :else
      (if-let [matching (first (filter #(permission-matches? % config-def action tenant tenant-config-key)
                                       user-permissions))]
        {:allowed? true
         :matched-permission (:permission/id matching)
         :reason (str "Matched permission: " (:permission/name matching))}
        {:allowed? false
         :matched-permission nil
         :reason "No matching permission for this config/action"}))))

(defn filter-accessible-configs
  "Filter a list of config definitions to only those accessible by a user.

   Args:
     db - Datahike database value
     user-id - User ID
     config-defs - List of config definition entities
     action - :read or :write
     opts - Options (tenant, tenant-config-key)

   Returns: Filtered list of config definitions"
  [db user-id config-defs action & [opts]]
  (let [user-permissions (get-user-permissions db user-id)
        tenant (:tenant opts)
        tenant-config-key (:tenant-config-key opts)]
    (filter (fn [config-def]
              (some #(permission-matches? % config-def action tenant tenant-config-key)
                    user-permissions))
            config-defs)))

;; =============================================================================
;; Permission Management
;; =============================================================================

(defn grant-permission!
  "Grant a permission to a user.

   Args:
     conn - Datahike connection
     user-id - User ID
     permission-id - Permission ID to grant"
  [conn user-id permission-id]
  (let [db @conn
        user-eid (d/q '[:find ?u .
                        :in $ ?user-id
                        :where [?u :user/id ?user-id]]
                      db user-id)
        perm-eid (d/q '[:find ?p .
                        :in $ ?perm-id
                        :where [?p :permission/id ?perm-id]]
                      db permission-id)]
    (if (and user-eid perm-eid)
      (d/transact conn {:tx-data [[:db/add user-eid :user/permissions perm-eid]]})
      (throw (ex-info "User or permission not found"
                      {:user-id user-id
                       :permission-id permission-id
                       :user-exists? (some? user-eid)
                       :permission-exists? (some? perm-eid)})))))

(defn revoke-permission!
  "Revoke a permission from a user.

   Args:
     conn - Datahike connection
     user-id - User ID
     permission-id - Permission ID to revoke"
  [conn user-id permission-id]
  (let [db @conn
        user-eid (d/q '[:find ?u .
                        :in $ ?user-id
                        :where [?u :user/id ?user-id]]
                      db user-id)
        perm-eid (d/q '[:find ?p .
                        :in $ ?perm-id
                        :where [?p :permission/id ?perm-id]]
                      db permission-id)]
    (when (and user-eid perm-eid)
      (d/transact conn {:tx-data [[:db/retract user-eid :user/permissions perm-eid]]}))))

(defn create-permission!
  "Create a new permission.

   Args:
     conn - Datahike connection
     permission - Map with permission fields:
       :id - Unique permission ID
       :name - Human-readable name
       :description - Description
       :attributes - ABAC attribute map {:service :* :sensitivity #{:public} ...}
       :tenants - Tenant access (:* or #{\"ka\" \"altinn\"})
       :tenant-config-keys - tenant-config-key access (:* or #{\"prod\" \"test\"})
       :actions - Allowed actions (#{:read} or #{:read :write})"
  [conn {:keys [id name description attributes tenants tenant-config-keys actions]}]
  (let [now (System/currentTimeMillis)]
    (d/transact conn {:tx-data [{:permission/id id
                                 :permission/name name
                                 :permission/description description
                                 :permission/attributes (pr-str attributes)
                                 :permission/tenants (pr-str tenants)
                                 :permission/tenant-config-keys (pr-str tenant-config-keys)
                                 :permission/actions (pr-str actions)
                                 :permission/created-at now}]})))

(defn get-all-permissions
  "Get all permission definitions."
  [db]
  (d/q '[:find [(pull ?e [*]) ...]
         :where [?e :permission/id]]
       db))

(defn get-permission
  "Get a permission by ID."
  [db permission-id]
  (d/q '[:find (pull ?e [*]) .
         :in $ ?id
         :where [?e :permission/id ?id]]
       db permission-id))

;; =============================================================================
;; Permission Validation
;; =============================================================================

(defn validate-permission-attributes
  "Validate that permission attributes are well-formed.

   Returns: {:valid? bool :errors [...]}"
  [attributes]
  (let [valid-services #{:llm :search :auth :storage :email :other :*}
        valid-sensitivity #{:public :internal :admin-only :secret :*}
        valid-functions #{:prompts :credentials :settings :features :i18n :*}
        errors (atom [])]

    ;; Check service
    (when-let [service (:service attributes)]
      (when-not (or (contains? valid-services service)
                    (set? service)
                    (and (keyword? service)
                         (str/starts-with? (name service) "!")))
        (swap! errors conj (str "Invalid service: " service))))

    ;; Check sensitivity
    (when-let [sensitivity (:sensitivity attributes)]
      (when-not (or (contains? valid-sensitivity sensitivity)
                    (set? sensitivity)
                    (and (keyword? sensitivity)
                         (str/starts-with? (name sensitivity) "!")))
        (swap! errors conj (str "Invalid sensitivity: " sensitivity))))

    ;; Check function
    (when-let [function (:function attributes)]
      (when-not (or (contains? valid-functions function)
                    (set? function)
                    (and (keyword? function)
                         (str/starts-with? (name function) "!")))
        (swap! errors conj (str "Invalid function: " function))))

    {:valid? (empty? @errors)
     :errors @errors}))

;; =============================================================================
;; Admin User Sync
;; =============================================================================

(defn get-admin-emails
  "Get admin emails from ADMIN_USER_EMAILS env var (bootstrap config).
   This is read directly from environment, not from config database,
   since it's needed before the system is fully configured."
  []
  (let [admins-str (or (System/getenv "ADMIN_USER_EMAILS") "")]
    (when (seq admins-str)
      (set (map str/trim (str/split admins-str #" "))))))

(defn get-user-by-email
  "Get a user entity by email."
  [db email]
  (d/q '[:find (pull ?e [*]) .
         :in $ ?email
         :where [?e :user/email ?email]]
       db email))

(defn sync-admin-permissions!
  "Sync admin-full permission to all users in ADMIN_USER_EMAILS.

   This should be called after migration to ensure admin users
   have the correct permissions in the database.

   Args:
     conn - Datahike connection

   Returns: Map of results {:granted [...] :skipped [...] :not-found [...]}"
  [conn]
  (let [admin-emails (get-admin-emails)
        results (atom {:granted [] :skipped [] :not-found []})]

    (println "Syncing admin permissions for" (count admin-emails) "admin emails...")

    (doseq [email admin-emails]
      (let [db @conn
            user (get-user-by-email db email)]
        (cond
          (nil? user)
          (do
            (println "  User not found:" email)
            ;; NAME THE PATH THAT WORKS (#436). This boot-time hook only GRANTS to
            ;; users that already exist, while the setup wizard's
            ;; `auth.migration/migrate-admin-users!` both CREATES and grants from the
            ;; same ADMIN_USER_EMAILS. One variable, two consumers, opposite
            ;; behaviour — so on an empty database this line is the whole story a
            ;; newcomer gets, and it reads as a dead end when a path exists.
            (println "    ADMIN_USER_EMAILS only GRANTS admin here; it does not create users.")
            (println "    The setup wizard creates them from the same variable:")
            (println "      java -cp app.jar clojure.main -m digdir.setup   (in the container)")
            (println "      bb setup                                        (with the repo toolchain)")
            (swap! results update :not-found conj email))

          ;; Check if already has admin-full
          (some #(= "admin-full" (:permission/id %))
                (get-user-permissions db (:user/id user)))
          (do
            (println "  Already admin:" email)
            (swap! results update :skipped conj email))

          :else
          (do
            (println "  Granting admin-full to:" email)
            (grant-permission! conn (:user/id user) "admin-full")
            (swap! results update :granted conj email)))))

    @results))

(defn ensure-user-has-permission!
  "Ensure a user has a specific permission, granting it if not present.

   Args:
     conn - Datahike connection
     user-id - User ID
     permission-id - Permission ID to ensure"
  [conn user-id permission-id]
  (let [db @conn
        current-perms (get-user-permissions db user-id)]
    (when-not (some #(= permission-id (:permission/id %)) current-perms)
      (grant-permission! conn user-id permission-id))))

(defn get-users-with-permission
  "Get all users that have a specific permission."
  [db permission-id]
  (d/q '[:find [(pull ?u [:user/id :user/email]) ...]
         :in $ ?perm-id
         :where
         [?p :permission/id ?perm-id]
         [?u :user/permissions ?p]]
       db permission-id))

(defn user-has-any-permission?
  "Check if a user has any config permissions at all."
  [db user-id]
  (let [perms (get-user-permissions db user-id)]
    (seq perms)))

(defn can-login?
  "Check if a user can log in to the system.

   A user can log in if:
   1. They exist in the database (by email)
   2. They have at least one permission assigned

   Args:
     db - Datahike database value
     email - User email address

   Returns: {:allowed? bool
             :user user-entity or nil
             :reason string}"
  [db email]
  (let [user (get-user-by-email db email)]
    (cond
      (nil? user)
      {:allowed? false
       :user nil
       :reason "User not found. Contact an administrator to request access."}

      (not (user-has-any-permission? db (:user/id user)))
      {:allowed? false
       :user user
       :reason "Access pending. Contact an administrator to grant permissions."}

      :else
      {:allowed? true
       :user user
       :reason "User authorized"})))

(defn can-manage-config?
  "High-level check: can this user access the config management UI?

   Returns true if user has any config permission."
  [db user-id]
  (user-has-any-permission? db user-id))

;; =============================================================================
;; User Accessible Scopes
;; =============================================================================

(defn get-user-accessible-tenants
  "Get all tenants a user has access to based on their permissions.
   Returns a set of tenant strings that the user can access.

   Args:
     db - Datahike database value
     user-id - User ID
     all-tenants - All available tenants in the system

   Returns: Set of accessible tenant strings"
  [db user-id all-tenants]
  (let [permissions (get-user-permissions db user-id)]
    (if (some #(= :* (permission->tenants %)) permissions)
      ;; User has wildcard access to all tenants
      (set all-tenants)
      ;; Collect specific tenant access from all permissions
      (->> permissions
           (map permission->tenants)
           (filter set?)
           (apply clojure.set/union)
           (or #{})))))


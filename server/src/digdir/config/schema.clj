(ns digdir.config.schema
  "Datahike schema definitions for configuration management.

   Two-entity model:
   - Config Definition: metadata shared across all tenants/environments
   - Config Value: actual values per tenant/environment")

;; =============================================================================
;; Config Definition Schema (metadata - shared across all tenants/environments)
;; =============================================================================

(def config-def-schema
  [{:db/ident :config-def/path
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Dot-separated config path, e.g., 'services.azure-openai.api-key'"}

   {:db/ident :config-def/value-type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Type of value: :string, :boolean, :number, :edn"}

   {:db/ident :config-def/encrypted?
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "Whether values for this config should be encrypted"}

   {:db/ident :config-def/description
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Human-readable description of this config entry"}

   {:db/ident :config-def/category
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Category for UI grouping: :services, :auth, :chat, :features, :i18n"}

   ;; ABAC attributes
   {:db/ident :config-def/service
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Service category: :llm, :search, :auth, :storage, :email, :other"}

   {:db/ident :config-def/sensitivity
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Sensitivity level: :public, :internal, :admin-only, :secret"}

   {:db/ident :config-def/function
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Function category: :prompts, :credentials, :settings, :features, :i18n"}

   {:db/ident :config-def/created-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when definition was created"}

   {:db/ident :config-def/multiline?
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "Whether this config should use multiline modal editor (for long strings)"}])

;; =============================================================================
;; Config Value Schema (data - per tenant/environment)
;; =============================================================================

(def config-value-schema
  [{:db/ident :config/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Unique identifier: tenant:env:entity:path (e.g., 'ka:prod:my-bot:services.azure-openai.api-key')"}

   {:db/ident :config/definition
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to config-def entity"}

   {:db/ident :config/tenant
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Tenant identifier (e.g., 'ka', 'altinn') or nil for global defaults"}

   {:db/ident :config/environment
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Environment (e.g., 'prod', 'test', 'dev') or nil for tenant defaults"}

   {:db/ident :config/entity
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Entity identifier (e.g., 'my-bot') or nil for non-entity-scoped values"}

   {:db/ident :config/value
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "String representation of value (EDN-encoded for complex types, encrypted for secrets)"}

   {:db/ident :config/created-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when value was created"}

   {:db/ident :config/updated-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when value was last updated"}

   {:db/ident :config/deleted-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when value was soft-deleted (nil = active)"}])

;; =============================================================================
;; Audit Log Schema
;; =============================================================================

(def audit-log-schema
  [{:db/ident :audit/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Unique audit log entry ID"}

   {:db/ident :audit/timestamp
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "When the action occurred"}

   {:db/ident :audit/user-email
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Email of user who made the change"}

   {:db/ident :audit/user-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "ID of user who made the change"}

   {:db/ident :audit/action
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Action type: :create, :update, :delete"}

   {:db/ident :audit/config-value
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to the config value entity (if still exists)"}

   {:db/ident :audit/config-def
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to the config definition entity"}

   {:db/ident :audit/config-path
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Config path at time of audit (denormalized for history)"}

   {:db/ident :audit/tenant
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Tenant at time of change"}

   {:db/ident :audit/environment
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Environment at time of change"}

   {:db/ident :audit/entity
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Entity at time of change (if entity-scoped)"}

   {:db/ident :audit/previous-value
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Previous value (redacted for encrypted values)"}

   {:db/ident :audit/new-value
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "New value (redacted for encrypted values)"}

   {:db/ident :audit/ip-address
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "IP address of the requester"}

   ;; API Key audit fields
   {:db/ident :audit/api-key-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "API key ID being audited (for API key operations)"}

   {:db/ident :audit/api-key-name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "API key name at time of audit (denormalized)"}

   {:db/ident :audit/api-key-entity-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Entity ID associated with API key at time of audit"}])

;; =============================================================================
;; Permission Schema (ABAC)
;; =============================================================================

(def permission-schema
  [{:db/ident :permission/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Unique permission identifier"}

   {:db/ident :permission/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Human-readable permission name"}

   {:db/ident :permission/description
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Description of what this permission grants"}

   {:db/ident :permission/attributes
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "EDN-encoded attribute map: {:service :*, :sensitivity #{:public}, :function :prompts}"}

   {:db/ident :permission/tenants
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "EDN-encoded tenant access: :* for all, or #{\"ka\" \"altinn\"} for specific"}

   {:db/ident :permission/environments
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "EDN-encoded environment access: :* for all, or #{\"prod\" \"test\"} for specific"}

   {:db/ident :permission/actions
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "EDN-encoded allowed actions: #{:read} or #{:read :write}"}

   {:db/ident :permission/created-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when permission was created"}])

;; User-to-permission relationship (add to existing user schema)
(def user-permission-schema
  [{:db/ident :user/permissions
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "References to permission entities granted to this user"}])

 ;; Basic user identity attributes used by permission system
 (def user-schema
   [{:db/ident :user/id
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one
     :db/unique :db.unique/identity}
 
    {:db/ident :user/email
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one
     :db/unique :db.unique/identity}])

;; =============================================================================
;; Tenant Entity Schema
;; =============================================================================

(def tenant-schema
  "Schema for tenant entities - first-class tenant management with renaming support.
   The :tenant/id is stable and used in config values, while :tenant/name can be changed."
  [{:db/ident :tenant/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Unique stable tenant identifier (used in config values, cannot be changed)"}

   {:db/ident :tenant/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Display name for the tenant (can be renamed without affecting config values)"}

   {:db/ident :tenant/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when tenant was created"}

   {:db/ident :tenant/created-by
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "User ID who created the tenant"}])

;; =============================================================================
;; Combined Migration Schema
;; =============================================================================

(def config-migration-schema
  "Complete schema for config management system"
  (concat config-def-schema
          config-value-schema
          audit-log-schema
          permission-schema
          user-schema
          user-permission-schema
          tenant-schema))

;; =============================================================================
;; Default Permissions
;; =============================================================================

(def default-permissions
  "Default permission profiles to seed the database"
  [{:permission/id "admin-full"
    :permission/name "Full Admin Access"
    :permission/description "Full read/write access to all configuration"
    :permission/attributes (pr-str {:service :*
                                    :sensitivity :*
                                    :function :*})
    :permission/tenants (pr-str :*)
    :permission/environments (pr-str :*)
    :permission/actions (pr-str #{:read :write})}

   {:permission/id "readonly-all"
    :permission/name "Read-Only All"
    :permission/description "Read-only access to all non-secret configuration"
    :permission/attributes (pr-str {:service :*
                                    :sensitivity #{:public :internal :admin-only}
                                    :function :*})
    :permission/tenants (pr-str :*)
    :permission/environments (pr-str :*)
    :permission/actions (pr-str #{:read})}

   {:permission/id "prompt-editor"
    :permission/name "Prompt Editor"
    :permission/description "Can edit prompts, view public settings"
    :permission/attributes (pr-str {:service :*
                                    :sensitivity #{:public :internal}
                                    :function :prompts})
    :permission/tenants (pr-str :*)
    :permission/environments (pr-str :*)
    :permission/actions (pr-str #{:read :write})}

   {:permission/id "feature-manager"
    :permission/name "Feature Flag Manager"
    :permission/description "Can toggle feature flags"
    :permission/attributes (pr-str {:service :*
                                    :sensitivity #{:public :internal}
                                    :function :features})
    :permission/tenants (pr-str :*)
    :permission/environments (pr-str :*)
    :permission/actions (pr-str #{:read :write})}

   {:permission/id "can-login"
    :permission/name "System Login"
    :permission/description "Base permission allowing system login without config access"
    :permission/attributes (pr-str {:service :auth
                                    :sensitivity :public
                                    :function :*})
    :permission/tenants (pr-str :*)
    :permission/environments (pr-str :*)
    :permission/actions (pr-str #{:read})}])

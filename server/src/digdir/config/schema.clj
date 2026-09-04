(ns digdir.config.schema
  "Datahike schema definitions for configuration management.

   The live model is node-based:
   - Config Definition: metadata shared across roots
   - Config Node / Binding / Node Value: tenant-local tree resolution data")

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
    :db/doc "Whether this config should use multiline modal editor (for long strings)"}

   {:db/ident :config-def/root
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Owning configuration root; see digdir.config.structure"}

   {:db/ident :config-def/ownership
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Ownership pattern: :fork (tenant owns after registration; default)
             or :inherit (tenant values override a live global baseline).
             Absent is treated as :fork."}

   {:db/ident :config-def/deployment-specific?
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "True when the path has NO correct global default, so a shipped
             value is always wrong for somebody: __global__ must not hold one
             and the committed snapshot must not carry one. Roughly the
             complement of :ownership :inherit. The per-path decision lives in
             digdir.config.deployment-specific; this is where it is READ from."}])

;; =============================================================================
;; Config Tree Schema
;; =============================================================================

(def config-node-schema
  [{:db/ident :config.node/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Stable unique config node identifier"}

   {:db/ident :config.node/root
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Root that owns the node; see digdir.config.structure"}

   {:db/ident :config.node/tenant
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Tenant that owns the node"}

   {:db/ident :config.node/label
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Human-readable label for the node"}

   {:db/ident :config.node/tenant-config-key
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Optional stable local slug used for URLs and friendly selection"}

   {:db/ident :config.node/system-managed?
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "Whether the node belongs to the fixed platform-managed chain above the tenant tree"}

   {:db/ident :config.node/parent
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Parent config node in the same tenant and root"}

   {:db/ident :config.node/enabled?
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "Whether the node may participate in selection and resolution"}

   {:db/ident :config.node/created-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when the node was created"}

   {:db/ident :config.node/updated-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when the node was last updated"}])

(def config-node-value-schema
  [{:db/ident :config.value/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Stable unique value identifier derived from root, tenant, node-id, and path"}

   {:db/ident :config.value/root
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Owning configuration root"}

   {:db/ident :config.value/tenant
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Tenant that owns the value"}

   {:db/ident :config.value/node
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Node where this value is defined"}

   {:db/ident :config.value/definition
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Reference to the config definition"}

   {:db/ident :config.value/raw
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "String representation of the stored value, encrypted when required by the definition"}

   {:db/ident :config.value/created-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when the value was created"}

   {:db/ident :config.value/updated-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when the value was last updated"}

   {:db/ident :config.value/deleted-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Optional soft-delete timestamp"}

   {:db/ident :config.value/pin-of-version
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Global version this tenant value was pinned against.
             Present on tenant-level values that were explicitly pinned to
             insulate the tenant from future global edits for this path."}])

;; =============================================================================
;; Global-Config Version Schema
;; =============================================================================

(def config-global-version-schema
  "Version-stamped changelog for edits to the global (__global__) tenant tree.
   Each global write bumps the version; tenant pins may reference a version."
  [{:db/ident :config.global/version
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Monotonic global-config version number"}

   {:db/ident :config.global/created-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when the version was created"}

   {:db/ident :config.global/created-by
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "User ID or system actor that created the version"}

   {:db/ident :config.global/changelog
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Operator-authored changelog explaining what changed and why"}])

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

   {:db/ident :audit/tenant-config-key
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "tenant-config-key at time of change"}

   {:db/ident :audit/client
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Client at time of change"}

   {:db/ident :audit/skill-graph
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Skill graph at time of change"}

   {:db/ident :audit/pipeline
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Pipeline at time of change (if pipeline-scoped)"}

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

   {:db/ident :audit/api-key-pipeline-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Pipeline ID associated with API key at time of audit"}

   ;; Global-config fields (Phase 2.5). Used with :audit/action values
   ;; :global-edit, :pin, :unpin.
   {:db/ident :audit/global-version
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Global-config version this audit entry corresponds to"}

   {:db/ident :audit/changelog
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Operator-authored changelog for a global-edit audit entry"}])

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

   {:db/ident :permission/tenant-config-keys
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "EDN-encoded tenant-config-key access: :* for all, or #{\"prod\" \"test\"} for specific"}

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
     :db/unique :db.unique/identity}

    {:db/ident :user/preferred-language
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one
     :db/doc "Preferred language code for the user interface and auth emails"}])

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
;; Dataset and Pipeline Entity Schema
;; =============================================================================

(def dataset-schema
  [{:db/ident :dataset/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Stable globally unique dataset identifier"}

   {:db/ident :dataset/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Human-readable dataset display name"}

   {:db/ident :dataset/tenant
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Owning tenant for this dataset"}

   {:db/ident :dataset/description
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Short description of the dataset"}

   {:db/ident :dataset/enabled?
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "Whether the dataset is available for selection"}

   {:db/ident :dataset/created-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when the dataset was created"}

   {:db/ident :dataset/updated-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when the dataset was last updated"}])

(def dataset-pipeline-schema
  [{:db/ident :dataset.pipeline/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Stable globally unique pipeline identifier"}

   {:db/ident :dataset.pipeline/dataset
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Owning dataset for this pipeline"}

   {:db/ident :dataset.pipeline/enabled?
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "Whether the pipeline may be executed"}

   {:db/ident :dataset.pipeline/created-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when the pipeline was created"}

   {:db/ident :dataset.pipeline/updated-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when the pipeline was last updated"}])

;; =============================================================================
;; Agent Schema
;; =============================================================================

(def agent-schema
  "Schema for durable agent definitions stored in the config DB."
  [{:db/ident :agent/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Stable unique agent identifier"}

   {:db/ident :agent/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Human-readable display name for the agent"}

   {:db/ident :agent/description
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Short description of the agent's purpose"}

   {:db/ident :agent/instructions
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Primary behavioral instructions for the agent"}

   {:db/ident :agent/default-skill-graph
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Default skill graph ID used by the agent"}

   {:db/ident :agent/allowed-skill-graphs
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many
    :db/doc "Skill graph IDs that the agent is allowed to execute"}

   {:db/ident :agent/allowed-dataset-scopes
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/doc "References to dataset scopes the agent is allowed to access"}

   {:db/ident :agent/guardrails
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "EDN-encoded guardrail and policy configuration for the agent"}

   {:db/ident :agent/skill-params
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc (str "EDN-encoded skill-params overrides applied to every invoke "
                 "call that resolves to this agent. Merged into "
                 "api-util/build-rag-skill-params output below the per-call "
                 "API overrides and above the dataset-config defaults. Shape "
                 "is {<skill-id-keyword> <param-map>}, e.g. "
                 "{:builtin/retrieval {:strategy-weights {:phrase 1.0}}}.")}

   {:db/ident :agent/enabled?
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one
    :db/doc "Whether the agent is available for runtime selection"}

   {:db/ident :agent/created-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when the agent was created"}

   {:db/ident :agent/updated-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when the agent was last updated"}

   {:db/ident :agent.dataset-scope/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Stable unique identifier for an agent dataset-scope entity"}

   {:db/ident :agent.dataset-scope/tenant
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Tenant for the allowed dataset scope"}

   {:db/ident :agent.dataset-scope/dataset-config-key
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Canonical dataset-config-key for the allowed dataset scope"}])

;; =============================================================================
;; Pipeline Execution Schema
;; =============================================================================

(def pipeline-execution-schema
  "Schema for tracking pipeline execution runs"
  [{:db/ident :pipeline-execution/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Unique execution identifier"}

   {:db/ident :pipeline-execution/pipeline-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "ID of the pipeline being executed (format: tenant:tenant-config-key:pipeline-name)"}

   {:db/ident :pipeline-execution/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc "Execution status: :running, :completed, :failed, :cancelled"}

   {:db/ident :pipeline-execution/started-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when execution started"}

   {:db/ident :pipeline-execution/completed-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when execution completed (success or failure)"}

   {:db/ident :pipeline-execution/documents-processed
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Number of documents successfully processed"}

   {:db/ident :pipeline-execution/documents-failed
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Number of documents that failed processing"}

   {:db/ident :pipeline-execution/documents-total
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Total documents discovered for this execution (e.g. sitemap URL count). Optional; only loaders that surface a total set this."}

   {:db/ident :pipeline-execution/last-progress-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp of the most recent progress flush during a running execution"}

   {:db/ident :pipeline-execution/error-message
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Error message if execution failed"}

   {:db/ident :pipeline-execution/started-by
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "User ID who started the execution"}])

;; =============================================================================
;; Combined Migration Schema
;; =============================================================================

;; =============================================================================
;; One-shot Migration Marker
;; =============================================================================

(def schema-migration-schema
  "Marker entities for one-shot data migrations. Each applied migration inserts
   one entity keyed by a stable string ID so subsequent boots skip it."
  [{:db/ident :digdir.migration/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Stable identifier for a one-shot data migration"}

   {:db/ident :digdir.migration/applied-at
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc "Timestamp when the migration completed"}])

(def config-migration-schema
  "Complete schema for config management system"
  (concat config-def-schema
          config-node-schema
          config-node-value-schema
          config-global-version-schema
          audit-log-schema
          permission-schema
          user-schema
          user-permission-schema
          tenant-schema
          dataset-schema
          dataset-pipeline-schema
          agent-schema
          pipeline-execution-schema
          schema-migration-schema))

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
    :permission/tenant-config-keys (pr-str :*)
    :permission/actions (pr-str #{:read :write})}

   {:permission/id "readonly-all"
    :permission/name "Read-Only All"
    :permission/description "Read-only access to all non-secret configuration"
    :permission/attributes (pr-str {:service :*
                                    :sensitivity #{:public :internal :admin-only}
                                    :function :*})
    :permission/tenants (pr-str :*)
    :permission/tenant-config-keys (pr-str :*)
    :permission/actions (pr-str #{:read})}

   {:permission/id "prompt-editor"
    :permission/name "Prompt Editor"
    :permission/description "Can edit prompts, view public settings"
    :permission/attributes (pr-str {:service :*
                                    :sensitivity #{:public :internal}
                                    :function :prompts})
    :permission/tenants (pr-str :*)
    :permission/tenant-config-keys (pr-str :*)
    :permission/actions (pr-str #{:read :write})}

   {:permission/id "feature-manager"
    :permission/name "Feature Flag Manager"
    :permission/description "Can toggle feature flags"
    :permission/attributes (pr-str {:service :*
                                    :sensitivity #{:public :internal}
                                    :function :features})
    :permission/tenants (pr-str :*)
    :permission/tenant-config-keys (pr-str :*)
    :permission/actions (pr-str #{:read :write})}

   {:permission/id "can-login"
    :permission/name "System Login"
    :permission/description "Base permission allowing system login without config access"
    :permission/attributes (pr-str {:service :auth
                                    :sensitivity :public
                                    :function :*})
    :permission/tenants (pr-str :*)
    :permission/tenant-config-keys (pr-str :*)
    :permission/actions (pr-str #{:read})}])

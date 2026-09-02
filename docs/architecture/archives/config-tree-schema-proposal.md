# Config Tree Schema Proposal

> **HISTORICAL** — this proposal has been implemented (the three-root platform/runtime/dataset model is live; see `docs/system-overview.md` §4.1 and `decisions/platform-runtime-dataset-config-roots.md`). Kept as a design record.

## Purpose

This document turns the accepted ADR in [platform-runtime-dataset-config-roots.md](../../../decisions/platform-runtime-dataset-config-roots.md) into a concrete schema proposal for the current Datahike-based system.

It is intentionally practical:

- use naming patterns that fit the current schema
- preserve the split between config DB and main DB
- keep resolution deterministic
- avoid rebuilding the current multi-dimensional config tuple under a different name

This proposal does not require immediate code changes. It defines the target storage model to implement against.

---

## Design Summary

The target model has:

- fixed configuration roots
- tenant-defined nodes per root
- typed bindings from nodes to profiles and entities
- scalar config values stored at `node + path`
- root-aware config definitions
- first-class dataset and pipeline entities
- root-aware config-management grants on API keys

The key simplification is:

- v1/current model: values are keyed by a tuple like `tenant + environment + client + skill-graph + pipeline + path`
- v2 target model: values are keyed by `root + tenant + node-id + path`

Selection and validation happen before value lookup. The value row itself does not carry the full runtime or ingestion target tuple.

---

## Databases

This proposal keeps the current logical split:

- config metadata, config values, agents, datasets, pipelines, nodes, and bindings live under the config role
- API keys, conversations, and messages live under the main app role

With the latest connection-handling change, those two logical roles share one physical Datahike connection by default.

That means Datahike refs across the two logical areas are now available in the default runtime model.

Stable string IDs are still important for:

- durable public identity
- export/import payloads
- audit readability
- optional future cases where the config role is explicitly pointed at a different connection

---

## Root Model

Roots are fixed and globally known:

- `:platform`
- `:runtime`
- `:dataset`

Every config definition belongs to exactly one root.

Every config node belongs to exactly one root and one tenant.

Every config value belongs to exactly one root, one tenant, one node, and one config definition.

---

## Config DB Schema

### Config Definition Schema Additions

Keep the current `:config-def/*` model and add root metadata.

### New attributes

```clojure
{:db/ident :config-def/root
 :db/valueType :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc "Owning configuration root: :platform, :runtime, or :dataset"}
```

### Notes

- Existing attributes such as `:config-def/path`, `:config-def/value-type`, `:config-def/category`, and ABAC metadata remain useful.
- `:config-def/root` becomes mandatory in the target model.
- No path may exist in more than one root.

---

### Config Node Schema

Nodes are tenant-defined inheritance points inside a root.

### Proposed attributes

```clojure
{:db/ident :config.node/id
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/unique :db.unique/identity
 :db/doc "Stable unique node identifier"}

{:db/ident :config.node/root
 :db/valueType :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc "Root that owns the node: :platform, :runtime, or :dataset"}

{:db/ident :config.node/tenant
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc "Tenant that owns the node"}

{:db/ident :config.node/label
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc "Human-readable node label for UI display"}

{:db/ident :config.node/tenant-config-key
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc "Optional stable local slug used for URLs or friendly references"}

{:db/ident :config.node/parent
 :db/valueType :db.type/ref
 :db/cardinality :db.cardinality/one
 :db/doc "Parent config node within the same tenant and root"}

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
 :db/doc "Timestamp when the node was last updated"}
```

### Invariants

- `:config.node/parent` must point to a node with the same `:config.node/root`
- `:config.node/parent` must point to a node with the same `:config.node/tenant`
- no cycles
- single parent only

### Recommended node ID format

Opaque ID, not semantic grammar. Example:

```text
cfg-node_01JQ...
```

Optional `:config.node/tenant-config-key` may carry friendly values like:

```text
frontpage
prod-a
default-materialization
```

The slug is for UI convenience. The ID is the durable reference.

---

### Config Binding Schema

Bindings connect nodes to recognized target types.

This proposal separates:

- node selection
- node compatibility validation

The selected node may be supplied explicitly or found via a profile binding. Entity bindings validate that the selected node is valid for the requested target.

### Proposed attributes

```clojure
{:db/ident :config.binding/id
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/unique :db.unique/identity
 :db/doc "Stable unique config binding identifier"}

{:db/ident :config.binding/root
 :db/valueType :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc "Root that owns the binding"}

{:db/ident :config.binding/tenant
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc "Tenant that owns the binding"}

{:db/ident :config.binding/node
 :db/valueType :db.type/ref
 :db/cardinality :db.cardinality/one
 :db/doc "Node referenced by this binding"}

{:db/ident :config.binding/type
 :db/valueType :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc "Binding type, for example :platform-profile, :runtime-profile, :dataset-profile, :agent, :dataset, or :pipeline"}

{:db/ident :config.binding/value
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc "Bound stable identifier such as an agent-id, dataset-id, pipeline-id, or profile-id"}

{:db/ident :config.binding/created-at
 :db/valueType :db.type/long
 :db/cardinality :db.cardinality/one
 :db/doc "Timestamp when the binding was created"}
```

### Supported binding types

Platform root:

- `:platform-profile`

Runtime root:

- `:runtime-profile`
- `:agent`
- `:dataset`

Dataset root:

- `:dataset-profile`
- `:dataset`
- `:pipeline`

### Invariants

- binding root must match node root
- binding tenant must match node tenant
- binding type must be allowed for that root
- the `(root, tenant, type, value)` tuple should usually map to one node for profile bindings

### Why bindings are separate entities

This gives:

- many-to-many flexibility
- subtree-independent validation
- simpler auditing
- cleaner UI listing and editing

---

### Config Value Schema V2

The existing `:config/*` value schema should be replaced or superseded by a node-based value schema.

### Proposed attributes

```clojure
{:db/ident :config.value/id
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
```

### Recommended value ID format

Use a deterministic internal identifier:

```text
{root}:{tenant}:{node-id}:{path}
```

Example:

```text
runtime:ka:cfg-node_01JQ...:skills.retrieval.top-k
```

### Important simplification

The value row does not store:

- `environment`
- `client`
- `skill-graph`
- `pipeline`
- `agent-id`
- `dataset-id`

Those concerns are handled by:

- selected node
- node ancestry
- typed bindings
- explicit resolution inputs

---

### Dataset Entity Schema

Datasets need first-class global identity in the config DB.

Datasets are intentionally global entities in this model. They are not tenant-owned records. Tenant-specific applicability is expressed through tenant-local bindings, not through a `:dataset/tenant` attribute.

### Proposed attributes

```clojure
{:db/ident :dataset/id
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/unique :db.unique/identity
 :db/doc "Stable globally unique dataset identifier"}

{:db/ident :dataset/name
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc "Human-readable dataset display name"}

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
 :db/doc "Timestamp when the dataset was last updated"}
```

---

### Pipeline Entity Schema

Pipelines become first-class globally unique ingestion/materialization definitions.

### Proposed attributes

```clojure
{:db/ident :dataset.pipeline/id
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/unique :db.unique/identity
 :db/doc "Stable globally unique pipeline identifier"}

{:db/ident :dataset.pipeline/dataset
 :db/valueType :db.type/ref
 :db/cardinality :db.cardinality/one
 :db/doc "Owning dataset for this pipeline"}

{:db/ident :dataset.pipeline/name
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc "Human-readable pipeline display name"}

{:db/ident :dataset.pipeline/source-type
 :db/valueType :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc "Source type such as :website, :folder, :kudos, or :episerver"}

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
 :db/doc "Timestamp when the pipeline was last updated"}
```

### Notes

- `dataset.pipeline/*` is used here to avoid confusion with current config paths under `pipeline.*`.
- The application namespace may still use `digdir.pipeline.*` internally during migration.

---

### Agent Schema Changes

The current durable agent entity is already close to the target model.

Recommended changes:

- preserve `:agent/id`, `:agent/name`, `:agent/description`, `:agent/instructions`, `:agent/default-skill-graph`, `:agent/allowed-skill-graphs`, `:agent/enabled?`
- replace `:agent/allowed-dataset-refs` tuple model with dataset IDs

### Preferred future attributes

```clojure
{:db/ident :agent/allowed-datasets
 :db/valueType :db.type/ref
 :db/cardinality :db.cardinality/many
 :db/doc "Datasets this agent may access"}
```

This aligns the runtime surface with first-class global dataset identity.

---

### Audit Schema Additions

Audit entries need root and node awareness.

### Proposed attributes

```clojure
{:db/ident :audit/config-root
 :db/valueType :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc "Root that owned the affected config change"}

{:db/ident :audit/config-node-id
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc "Node ID that owned the changed value"}

{:db/ident :audit/config-binding-type
 :db/valueType :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc "Optional binding type involved in the action"}

{:db/ident :audit/config-binding-value
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc "Optional binding value involved in the action"}
```

This lets audit views explain changes in node-oriented terms rather than in the current scope tuple.

---

## Main DB Schema Proposal

The main app role keeps API keys and runtime state.

Because the main app role and config role share one physical Datahike connection by default, API key grants may reference config entities directly via refs.

Stable IDs should still be kept where they are the public or durable identity.

### API Key Config-Grant Schema

Add explicit config-management grants.

### Proposed attributes

```clojure
{:db/ident :api-key/config-grants
 :db/valueType :db.type/ref
 :db/cardinality :db.cardinality/many
 :db/doc "Configuration-management grants attached to this API key"}

{:db/ident :api-key.config-grant/id
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/unique :db.unique/identity
 :db/doc "Stable config grant identifier"}

{:db/ident :api-key.config-grant/root
 :db/valueType :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc "Granted root: :platform, :runtime, or :dataset"}

{:db/ident :api-key.config-grant/tenant
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc "Tenant that the grant applies to"}

{:db/ident :api-key.config-grant/node
 :db/valueType :db.type/ref
 :db/cardinality :db.cardinality/one
 :db/doc "Optional subtree root node for this grant"}

{:db/ident :api-key.config-grant/node-id
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc "Optional denormalized node ID for export, debugging, and split-connection fallback"}

{:db/ident :api-key.config-grant/binding-type
 :db/valueType :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc "Optional binding type restriction such as :agent, :dataset, or :pipeline"}

{:db/ident :api-key.config-grant/binding-value
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc "Optional binding value restriction such as a concrete agent-id, dataset-id, pipeline-id, or profile ID"}

{:db/ident :api-key.config-grant/actions
 :db/valueType :db.type/keyword
 :db/cardinality :db.cardinality/many
 :db/doc "Allowed actions such as :read and :write"}
```

### Notes

- `:api-key.config-grant/node` should be used in the default shared-connection runtime
- `:api-key.config-grant/node-id` is kept as a durable denormalized identity for export and fallback use
- grants may be broad or narrow:
  - whole root for a tenant
  - one subtree
  - one binding target

---

## Resolution Proposal

### Node Selection

Node selection must be explicit.

Preferred request contract:

- caller passes `node-id`, or
- caller passes a profile ID that resolves uniquely via a profile binding

Examples:

### Platform

```clojure
{:root :platform
 :tenant "ka"
 :platform-profile "prod-a"
 :path "services.azure-openai.api-endpoint"}
```

### Runtime

```clojure
{:root :runtime
 :tenant "ka"
 :agent-id "website-assistant"
 :dataset-id "kudos"
 :runtime-profile "frontpage"
 :path "skills.retrieval.top-k"}
```

### Dataset

```clojure
{:root :dataset
 :tenant "ka"
 :dataset-id "kudos"
 :pipeline-id "kudos-loader-v2"
 :dataset-profile "standard-materialization"
 :path "pipeline.chunking.maximum-length"}
```

### Selection algorithm

1. If `node-id` is given, use it.
2. Else, if the relevant profile is given, resolve `(root, tenant, binding-type, binding-value)` to exactly one node.
3. Validate that the selected node is compatible with supplied entity bindings such as agent, dataset, or pipeline.
4. Walk parent links upward for value resolution.

This keeps selection explicit and prevents hidden label-based inference.

### Compatibility Validation

After node selection, validate against available bindings.

Examples:

- a Runtime node selected for `agent-id = website-assistant` may require an `:agent` binding for that agent
- a Dataset node selected for `pipeline-id = kudos-loader-v2` may require a `:pipeline` binding for that pipeline

Compatibility rules should be root-specific and fixed in code.

### Value Resolution

Once the node is selected and validated:

1. find a value at `(root, tenant, node-id, path)`
2. if absent, move to parent
3. first match wins
4. decode using the referenced definition

This is intentionally much simpler than the current tuple-based precedence model.

---

## Query Sketches

These are conceptual examples, not final code.

## Load a node by ID

```clojure
[:find (pull ?e [* {:config.node/parent [:config.node/id]}]) .
 :in $ ?node-id
 :where
 [?e :config.node/id ?node-id]]
```

## Resolve a profile binding to a node

```clojure
[:find ?node-id .
 :in $ ?root ?tenant ?binding-type ?binding-value
 :where
 [?b :config.binding/root ?root]
 [?b :config.binding/tenant ?tenant]
 [?b :config.binding/type ?binding-type]
 [?b :config.binding/value ?binding-value]
 [?b :config.binding/node ?n]
 [?n :config.node/id ?node-id]]
```

## Load a value at a node for a path

```clojure
[:find (pull ?v [* {:config.value/definition [*]}]) .
 :in $ ?root ?tenant ?node-id ?path
 :where
 [?n :config.node/id ?node-id]
 [?d :config-def/path ?path]
 [?d :config-def/root ?root]
 [?v :config.value/root ?root]
 [?v :config.value/tenant ?tenant]
 [?v :config.value/node ?n]
 [?v :config.value/definition ?d]
 (not [?v :config.value/deleted-at])]
```

---

## Migration from Current Schema

### Config Definitions

Add `:config-def/root` and classify every existing definition into:

- Platform
- Runtime
- Dataset

Examples:

- `services.auth.*` -> Platform
- `services.typesense.*` -> Platform
- `skills.*` -> Runtime
- `pipeline.source.*` -> Dataset
- `pipeline.documents.*` -> Dataset
- `pipeline.chunks.*` -> Dataset
- `pipeline.storage.*` -> Dataset
- `pipeline.operations.*` -> Dataset

### Config Values

Current values are stored under tuple-style IDs like:

```text
tenant:env:client:skill-graph:pipeline:path
```

Migration should:

1. classify the definition by root
2. create initial tenant-local nodes such as:
   - `platform/default`
   - `runtime/default`
   - `dataset/default`
3. transform tuple-scoped values into node-scoped values
4. place migrated values on the nearest equivalent bootstrap node

### Bootstrap Trees

Recommended initial trees per tenant:

### Platform

```text
default
└── prod
```

### Runtime

```text
default
└── default-runtime
```

### Dataset

```text
default
└── default-materialization
```

These can later be refined by tenant admins.

### Agents, Datasets, and Pipelines

Add first-class dataset and pipeline entities before cutting over runtime and ingestion APIs.

During migration:

- current dataset refs may be translated into initial dataset IDs
- current pipeline names may be translated into global `dataset.pipeline/id` values

### API Keys

Preserve current runtime usage grants during the first cut if needed, but add `:api-key/config-grants` for config-management access.

Later:

- replace dataset-ref tuple grants with dataset IDs
- replace pipeline tuple IDs with global pipeline IDs

---

## Recommended Implementation Phases

1. Add new schema attributes for roots, nodes, bindings, datasets, pipelines, and config grants.
2. Add `:config-def/root` to all definitions.
3. Implement bootstrap trees and node CRUD.
4. Implement binding CRUD and profile-based node selection.
5. Implement node-based value CRUD and root-aware resolution.
6. Add resolution traces and UI inspection.
7. Migrate existing values into bootstrap nodes.
8. Cut runtime and ingestion code over to node-based resolution.

---

## Open Questions

These should be decided during implementation:

1. Whether profile bindings should be first-class entities separate from generic bindings.
2. Whether `dataset.pipeline/*` should be renamed later to `pipeline/*` once legacy ambiguity is removed.
3. Whether config grants need both ref-based and ID-based indexes for admin performance.
4. Whether agent allowed-dataset relationships should remain refs in the config role only or also be denormalized into IDs for export simplicity.

None of these questions change the core proposal:

- root-aware definitions
- node-based values
- typed bindings
- first-class datasets and pipelines
- explicit selection
- deterministic parent-walk resolution

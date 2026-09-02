# Explicit Node Resolution Schema Proposal

Status note (2026-03-31):
This proposal has now been implemented on the live path. The notes below are
kept as the design record for the current ceiling-only, explicit-node model.

## Purpose

This document proposes a concrete schema and API contract for the next iteration
of configuration resolution.

It supersedes the node-selection part of the earlier
[Config Tree Schema Proposal](config-tree-schema-proposal.md).
That earlier proposal assumed that bindings would select the active tenant node.
The design here makes node selection explicit at request time and uses API keys
to constrain where clients may navigate in each tenant/root tree.

The goal is to preserve the old mental model that reduced administrator
cognitive load:

- values may be defined at multiple levels
- the most specific matching value wins
- tenants may define their own internal hierarchy
- clients navigate that hierarchy explicitly within API-key limits

## Design Summary

For each `tenant x root`:

- there is exactly one tenant-defined tree
- that tree has a canonical root node with `slug = "default"`
- clients must supply the concrete tenant `tenant_config_key` for each request
- API keys grant one or more ceiling nodes per root
- the requested node must be equal to or below at least one ceiling node
- compatibility metadata validates the request but never selects the node
- value resolution is nearest ancestor wins

Each root also has a fixed system-managed chain above the tenant tree:

```text
requested tenant node
-> tenant ancestors
-> tenant root node ("default")
-> fixed root chain nodes
-> stop
```

This keeps the product-wide hierarchy stable while making the tenant-specific
part flexible.

## Core Resolution Model

### Inputs

Every resolve request takes:

- authenticated API key
- `tenant`
- `root` in `:platform`, `:runtime`, `:dataset`
- explicit tenant `tenant_config_key`
- one path or many paths
- root-specific compatibility context

Compatibility context:

- Platform:
  - no compatibility payload
- Runtime:
  - `agent-id` required
  - `dataset-id` optional
- Dataset:
  - `dataset-id` required
  - `pipeline-id` optional

### Resolution Steps

1. Authenticate the API key.
2. Resolve the requested `(tenant, root, tenant_config_key)` to the concrete node.
3. Reject if the node is missing, disabled, or belongs to another tenant/root.
4. Load the API key ceiling grants for the same `(tenant, root)`.
5. Authorize if the requested node is equal to or a descendant of at least one
   ceiling node.
6. Determine the matching ceiling for trace purposes.
7. Find the tenant root node for the requested node.
8. Validate compatibility against the tenant root node.
9. Validate that every requested path is defined for the same root.
10. Build the effective resolution chain:
    - requested node
    - tenant ancestors up to `default`
    - fixed root chain nodes
11. Walk that chain for each path and stop at the first value.
12. Return resolved values plus authorization and resolution traces.

### Key Consequences

- Bindings no longer choose the tenant node.
- There is no profile-driven or dataset-driven implicit node selection.
- Compatibility is fail-closed validation metadata.
- API keys constrain navigation but do not choose the node either.

## Schema Proposal

## Config DB

### Config Definition

Keep the current `:config-def/*` structure.

Required attributes:

```clojure
{:db/ident :config-def/path
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/unique :db.unique/identity}

{:db/ident :config-def/root
 :db/valueType :db.type/keyword
 :db/cardinality :db.cardinality/one}

{:db/ident :config-def/value-type
 :db/valueType :db.type/keyword
 :db/cardinality :db.cardinality/one}

{:db/ident :config-def/encrypted?
 :db/valueType :db.type/boolean
 :db/cardinality :db.cardinality/one}
```

Invariants:

- every path belongs to exactly one root
- no path may exist in more than one root

### Config Node

Config nodes remain the tenant-defined inheritance points.

Required attributes:

```clojure
{:db/ident :config.node/id
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/unique :db.unique/identity
 :db/doc "Stable tenant node identifier, unique across all roots and tenants"}

{:db/ident :config.node/root
 :db/valueType :db.type/keyword
 :db/cardinality :db.cardinality/one}

{:db/ident :config.node/tenant
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one}

{:db/ident :config.node/label
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one}

{:db/ident :config.node/tenant-config-key
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one}

{:db/ident :config.node/parent
 :db/valueType :db.type/ref
 :db/cardinality :db.cardinality/one}

{:db/ident :config.node/enabled?
 :db/valueType :db.type/boolean
 :db/cardinality :db.cardinality/one}

{:db/ident :config.node/created-at
 :db/valueType :db.type/long
 :db/cardinality :db.cardinality/one}

{:db/ident :config.node/updated-at
 :db/valueType :db.type/long
 :db/cardinality :db.cardinality/one}
```

New attribute:

```clojure
{:db/ident :config.node/system-managed?
 :db/valueType :db.type/boolean
 :db/cardinality :db.cardinality/one
 :db/doc "True for fixed root-chain nodes managed by the platform"}
```

Recommended invariants:

- parent must share tenant and root
- no cycles
- single parent only
- each tenant/root must contain exactly one canonical tenant root node
- every node must have a non-blank stable per-tenant slug
- the `(tenant, root, slug)` triple must be unique
- that canonical node must have `:config.node/tenant-config-key = "default"`

Required node identity split:

- `:config.node/id`: durable internal ID
- `:config.node/tenant-config-key`: stable request-facing token within a tenant/root

The request contract should use the slug, not the durable internal ID.

Examples:

- internal ID: `cfg-node_01JV...`
- request-facing slug: `default`
- request-facing slug: `frontpage`
- request-facing slug: `prod-a`

### Config Value

Keep the current node-scoped value model.

```clojure
{:db/ident :config.value/id
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/unique :db.unique/identity}

{:db/ident :config.value/root
 :db/valueType :db.type/keyword
 :db/cardinality :db.cardinality/one}

{:db/ident :config.value/tenant
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one}

{:db/ident :config.value/node
 :db/valueType :db.type/ref
 :db/cardinality :db.cardinality/one}

{:db/ident :config.value/definition
 :db/valueType :db.type/ref
 :db/cardinality :db.cardinality/one}

{:db/ident :config.value/raw
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one}

{:db/ident :config.value/created-at
 :db/valueType :db.type/long
 :db/cardinality :db.cardinality/one}

{:db/ident :config.value/updated-at
 :db/valueType :db.type/long
 :db/cardinality :db.cardinality/one}

{:db/ident :config.value/deleted-at
 :db/valueType :db.type/long
 :db/cardinality :db.cardinality/one}
```

Value ID format remains:

```text
{root}:{tenant}:{node-id}:{path}
```

### Compatibility Metadata

The earlier binding model should be split into two concepts:

- navigation-independent compatibility metadata
- optional administrative targeting metadata

Compatibility should be attached to the tenant root node and inherited by the
whole tenant tree unless a later version explicitly adds narrowing semantics.

Recommended new entities:

```clojure
{:db/ident :config.compatibility/id
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/unique :db.unique/identity
 :db/doc "Stable compatibility rule identifier"}

{:db/ident :config.compatibility/root
 :db/valueType :db.type/keyword
 :db/cardinality :db.cardinality/one}

{:db/ident :config.compatibility/tenant
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one}

{:db/ident :config.compatibility/node
 :db/valueType :db.type/ref
 :db/cardinality :db.cardinality/one
 :db/doc "The tenant root node that owns the compatibility rules"}

{:db/ident :config.compatibility/type
 :db/valueType :db.type/keyword
 :db/cardinality :db.cardinality/one
 :db/doc "One of :agent, :dataset, :pipeline"}

{:db/ident :config.compatibility/value
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc "Concrete agent-id, dataset-id, or pipeline-id"}

{:db/ident :config.compatibility/created-at
 :db/valueType :db.type/long
 :db/cardinality :db.cardinality/one}
```

Invariants:

- compatibility node must be the canonical tenant root node for its tenant/root
- allowed types by root:
  - Platform: none
  - Runtime: `:agent`, `:dataset`
  - Dataset: `:dataset`, `:pipeline`
- duplicate `(tenant, root, node, type, value)` rows are not allowed

This separates compatibility from node selection completely.

### Optional Targeting Bindings

If the admin UI still needs named associations like “this node is commonly used
for website traffic” or “this node is the rollout branch for prod-a”, keep
bindings as optional metadata only.

If retained, bindings should no longer participate in request-time selection.

The existing `:config.binding/*` entity can stay, but its semantics change:

- it supports discovery and administration
- it does not select the active node during resolution
- it does not authorize requests

That means profile bindings become advisory metadata, not runtime selectors.

### Dataset and Pipeline Entities

Keep the current durable dataset and pipeline entities.

No schema change is required for this proposal beyond clarifying their role:

- they are first-class global entities
- compatibility rules may reference their stable IDs
- they do not drive tenant node selection

## Main DB

### API Key Config Scope Grants

The existing `:api-key.config-grant/*` shape should be replaced with an explicit
ceiling grant model.

The important semantic change is:

- current model: grants can behave like node+binding restrictions
- proposed model: grants define navigable ceiling nodes only

Recommended replacement:

```clojure
{:db/ident :api-key/config-ceilings
 :db/valueType :db.type/ref
 :db/cardinality :db.cardinality/many
 :db/doc "Tenant/root ceiling grants attached to this API key"}

{:db/ident :api-key.config-ceiling/id
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/unique :db.unique/identity}

{:db/ident :api-key.config-ceiling/root
 :db/valueType :db.type/keyword
 :db/cardinality :db.cardinality/one}

{:db/ident :api-key.config-ceiling/tenant
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one}

{:db/ident :api-key.config-ceiling/node
 :db/valueType :db.type/ref
 :db/cardinality :db.cardinality/one
 :db/doc "A tenant/root node that acts as an authorization ceiling"}

{:db/ident :api-key.config-ceiling/node-id
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc "Denormalized durable node ID for debugging, export, and split-connection fallback"}

{:db/ident :api-key.config-ceiling/tenant-config-key
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/doc "Denormalized request-facing node slug used by clients"}

{:db/ident :api-key.config-ceiling/created-at
 :db/valueType :db.type/long
 :db/cardinality :db.cardinality/one}
```

Recommended invariants:

- an API key may hold multiple ceiling grants per root
- all ceiling grants for one API key must belong to the same tenant if the key
  itself remains tenant-scoped
- every ceiling node must exist and be enabled
- every ceiling row must carry the node slug used at the API surface
- redundant ceilings should be normalized away when one ceiling is an ancestor
  of another ceiling on the same key/root
- when a new API key is created, omitted ceilings default to `["default"]` for
  each enabled root

That migration window has now ended in the live system. New schema,
export/import, and runtime code are ceiling-only.

### Why Multiple Ceilings

Multiple ceilings allow a key to operate over disjoint subtrees without
over-granting access to their common ancestor.

Authorization rule:

- a requested node is allowed if it is equal to or a descendant of at least one
  ceiling node

Trace rule:

- return the nearest matching ceiling node as `matched-ceiling`

## Derived Runtime Structures

These structures do not need durable schema, but the implementation should
materialize them at request time.

### Authorization Trace

```clojure
{:tenant "public-sector-knowledge"
 :root :runtime
 :requested-node "frontpage"
 :allowed-ceilings ["default" "website-a"]
 :matched-ceiling "website-a"
 :authorization :allowed}
```

### Compatibility Trace

```clojure
{:tenant "public-sector-knowledge"
 :root :runtime
 :checked-on-node "default"
 :required {:agent-id "research-assistant"
            :dataset-id "public-docs"}
 :matched {:agent-id "research-assistant"
           :dataset-id "public-docs"}
 :status :ok}
```

### Resolution Trace

```clojure
{:selected-root :runtime
 :selected-tenant "public-sector-knowledge"
 :requested-node "frontpage"
 :matched-ceiling "website-a"
 :tenant-root-node "default"
 :traversal-path ["frontpage" "website-a" "website" "default" "_runtime_global"]
 :winning-node "website"
 :path "skills.retrieval.top-k"
 :stop-reason :matched}
```

## API Contract

### API Key Definition

Create and update payload:

```json
{
  "name": "Website Runtime Key",
  "tenant": "public-sector-knowledge",
  "scopes": ["query"],
  "config_ceilings": {
    "platform": ["default"],
    "runtime": ["default", "website-a"],
    "dataset": ["default"]
  }
}
```

Rules:

- `config_ceilings` entries are arrays of tenant node slugs
- each array must contain at least one node when the root is enabled
- omitted roots default to `["default"]`

### Node Discovery

```text
GET /v1/config/{root}/nodes
```

Response should include only nodes reachable from at least one granted ceiling.

```json
{
  "root": "runtime",
  "tenant": "public-sector-knowledge",
  "ceilings": ["default", "website-a"],
  "nodes": [
    {"tenant_config_key": "default", "parent_slug": null, "label": "Default"},
    {"tenant_config_key": "website", "parent_slug": "default", "label": "Website"},
    {"tenant_config_key": "website-a", "parent_slug": "website", "label": "Website A"},
    {"tenant_config_key": "frontpage", "parent_slug": "website-a", "label": "Frontpage"}
  ]
}
```

### Generic Resolve Endpoint

```text
POST /v1/config/resolve
```

Request:

```json
{
  "tenant": "public-sector-knowledge",
  "root": "runtime",
  "tenant_config_key": "frontpage",
  "paths": [
    "skills.retrieval.top-k",
    "skills.rerank.top-k"
  ],
  "context": {
    "agent_id": "research-assistant",
    "dataset_id": "public-docs"
  }
}
```

Response:

```json
{
  "tenant": "public-sector-knowledge",
  "root": "runtime",
  "tenant_config_key": "frontpage",
  "authorization": {
    "allowed": true,
    "matched_ceiling": "website-a",
    "allowed_ceilings": ["default", "website-a"]
  },
  "compatibility": {
    "status": "ok",
    "checked_on_node": "default",
    "agent_id": "research-assistant",
    "dataset_id": "public-docs"
  },
  "results": {
    "skills.retrieval.top-k": {
      "value": 40,
      "trace": {
        "requested_node": "frontpage",
        "matched_ceiling": "website-a",
        "tenant_root_node": "default",
        "resolution_chain": ["frontpage", "website-a", "website", "default", "_runtime_global"],
        "winning_node": "website",
        "stop_reason": "matched"
      }
    }
  }
}
```

### Convenience Resolve Endpoints

Runtime:

```text
POST /v1/runtime/config/resolve
```

```json
{
  "tenant": "public-sector-knowledge",
  "tenant_config_key": "frontpage",
  "agent_id": "research-assistant",
  "dataset_id": "public-docs",
  "paths": ["skills.retrieval.top-k"]
}
```

Dataset:

```text
POST /v1/dataset/config/resolve
```

```json
{
  "tenant": "public-sector-knowledge",
  "tenant_config_key": "prod-a",
  "dataset_id": "public-docs",
  "pipeline_id": "digdir-docs",
  "paths": ["pipeline.chunks.minimum-length"]
}
```

### Error Contract

Unknown node:

```json
{
  "error": "unknown_node",
  "root": "runtime",
  "tenant": "public-sector-knowledge",
  "tenant_config_key": "frontpage-x"
}
```

Forbidden by ceiling:

```json
{
  "error": "forbidden_node_scope",
  "root": "runtime",
  "tenant": "public-sector-knowledge",
  "requested_node": "frontpage",
  "allowed_ceilings": ["default", "website-a"]
}
```

Compatibility failure:

```json
{
  "error": "incompatible_node_context",
  "root": "runtime",
  "tenant": "public-sector-knowledge",
  "requested_node": "frontpage",
  "checked_on_node": "default",
  "required": {
    "agent_id": "research-assistant",
    "dataset_id": "public-docs"
  }
}
```

Root mismatch:

```json
{
  "error": "definition_root_mismatch",
  "path": "pipeline.chunks.minimum-length",
  "requested_root": "runtime",
  "definition_root": "dataset"
}
```

## Migration Notes

This design requires changes in three areas:

- accessors:
  - stop selecting tenant nodes from bindings
  - require explicit request `tenant_config_key`
- API keys:
  - migrate config grants to explicit ceiling grants
- compatibility:
  - move request validation to tenant-root metadata

Suggested migration stages:

1. Keep current node/value storage.
2. Add API-key ceiling entities.
3. Add compatibility entities on tenant root nodes.
4. Introduce explicit-node resolve endpoints.
5. Deprecate binding-driven selection in accessors.
6. Retain optional bindings only for admin metadata and discovery.

## Recommended Invariants

- request `tenant_config_key` is mandatory
- every tenant/root has a canonical `default` node
- every tenant/root node has a stable per-tenant slug
- API key ceiling grants are root-specific
- multiple ceiling grants are allowed
- authorization succeeds when the requested node is under at least one ceiling
- compatibility is validated on the tenant root node and fails closed
- bindings, if retained, do not participate in request-time node selection
- every successful response includes authorization and resolution traces

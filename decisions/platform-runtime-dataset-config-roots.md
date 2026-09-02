# Architecture Decision: Config Roots with Tenant-Defined Inheritance Trees

> **The roots themselves live in `server/resources/config/config-structure.edn`,
> not in this document.** This file explains *why* the structure is durable;
> the data file states *what it currently is*, and both `config/db.clj` and
> `config/api-keys.clj` derive from it through `digdir.config.structure`.
>
> That split is deliberate. A rationale that also lists the set becomes a copy,
> and a copy in prose is the one nothing checks — it cannot go red, so it goes
> stale silently while reading as authoritative.


**Date**: 2026-03-27
**Status**: Accepted
**Decision**: Keep the three fixed configuration roots from the earlier fixed-hierarchy proposal, but replace each root's fixed internal hierarchy with a tenant-defined inheritance tree constrained by a fixed resolution model.

This ADR is the active implementation target.

It preserves the root split:

- Platform
- Runtime
- Dataset

but changes how inheritance is modeled inside each root.

---

## Context

The v1 ADR split config into three separate roots:

- Platform configuration
- Runtime configuration
- Dataset configuration

That split remains correct. It separates:

- shared infrastructure concerns
- query-time behavior
- materialization concerns

However, the v1 ADR also assumed fixed internal hierarchies:

- Platform: `All Platform -> Tenant -> Environment`
- Runtime: `All Agents -> Agent -> Dataset -> Tenant -> Environment`
- Dataset: `All Datasets -> Dataset -> Pipeline -> Tenant -> Environment`

That fixed model is easy to reason about, but it assumes all tenants have similar organizational and rollout structure.

In practice, tenants may need different inheritance depth and branching patterns:

- some may want runtime overlays like `base -> website -> frontpage`
- some may want branch rollout overlays like `prod -> a` and `prod -> b`
- some may want only `test` and `prod`
- some may want richer platform rollout structure than runtime structure
- some may want almost no hierarchy in dataset configuration

The desired flexibility is not arbitrary configuration semantics. The system still needs:

- deterministic resolution
- clear access control
- explainable inheritance
- root separation
- auditability
- supportability across tenants

So the problem is not whether tenants may define hierarchy. The problem is how to let them do so without turning the config system into a tenant-specific programming language.

---

## Decision

We keep the three fixed roots:

1. Platform
2. Runtime
3. Dataset

Inside each root, each tenant may define its own inheritance tree as data.

The inheritance engine remains fixed and global. Tenants may define tree shape, but not inheritance semantics.

In other words:

- roots are fixed by the product
- resolution rules are fixed by the platform
- tree structure is tenant-defined
- bindings from entities to tree nodes are explicit data

This is a bounded profile-and-overlays model, not an arbitrary graph language.

---

## Summary of the Model

The to-be model is:

- fixed config roots
- tenant-defined inheritance trees per root
- explicit node bindings for runtime and dataset entities
- single-parent inheritance only
- explicit resolution targets
- full resolution traceability

This replaces fixed per-root ladders with tenant-specific trees while preserving deterministic behavior.

---

## What Remains Fixed

These parts are not tenant-definable:

### Root Boundaries

Every config definition belongs to exactly one root:

- Platform
- Runtime
- Dataset

No path can belong to more than one root.

### Resolution Engine

The platform defines how resolution works:

- start at an explicitly selected node
- walk parent links upward
- first matching value wins
- stop at root

Tenants may define tree shape, but they may not define custom precedence logic.

### Root-Specific Selector Domains

Platform resolution selects a platform node together with an explicit `platform-config-key`.

Runtime resolution selects a runtime node together with an explicit `runtime-config-key` and runtime target such as:

- `agent-id`
- optional `dataset-ref`

Dataset resolution selects a dataset node together with an explicit `dataset-config-key` and dataset target such as:

- `dataset-ref`

Pipeline identifiers remain part of the materialization layer, but they are not the request-facing dataset selector.

### Access-Control Semantics

API key permissions continue to be granted at the root level with bounded selectors.

### Audit And Explainability Requirements

Every resolved value must be explainable via a concrete node path and source node.

---

## What Becomes Tenant-Defined

Each tenant may define, per root:

- a set of config nodes
- a tree shape through parent links
- stable node IDs
- human-readable labels
- optional typed bindings to entities or profiles

Examples:

### Runtime tree example

```text
base
└── website
    └── frontpage
```

### Runtime rollout tree example

```text
base
└── prod
    ├── prod-a
    └── prod-b
```

### Platform tree example

```text
default
├── test
└── prod
```

### Dataset tree example

```text
default
└── standard-materialization
```

The important point is that these are still trees, not arbitrary graphs.

---

## Hard Constraints

These constraints are mandatory.

### Single-Parent Only

A node may have zero or one parent.

Allowed:

```text
base -> website -> frontpage
```

Not allowed:

```text
website -> frontpage
campaign -> frontpage
```

No node may inherit from multiple parents.

### Acyclic Only

The node graph must be a tree or forest rooted in one or more root nodes.

Cycles are invalid.

### Stable Node Identity

Each node must have a stable, opaque ID such as:

- `runtime/base`
- `runtime/website`
- `platform/prod-a`

Display labels may change. Node IDs must not be overloaded as user-facing prose.

### Root-Local Only

A node belongs to exactly:

- one tenant
- one root

No node may exist across multiple roots.

No node in one root may inherit from a node in another root.

### Explicit Bindings Only

Named nodes like `website`, `frontpage`, `prod-a`, or `standard-materialization` are not enough by themselves.

Nodes must be bound explicitly to recognized target types.

Examples:

- runtime node bound to one or more `agent-id`s
- runtime node bound to one or more `dataset-ref`s
- dataset node bound to one or more `dataset-ref`s
- dataset node may still carry pipeline materialization metadata, but not as its public selector
- platform node bound to one or more deployment profiles such as `prod`, `test`, `a`, `b`

Bindings may be many-to-many, but they must be explicit data.

### Explicit Selection, Not Inference-By-Label

The system must not guess that a node named `frontpage` applies because a route happened to include `/frontpage`.

At runtime, the selected node or profile must be passed explicitly or derived from an explicit binding table.

### Resolution Trace Required

For every resolved value, the system must be able to report:

- selected root
- tenant
- selected node ID
- traversal path to ancestors
- winning node ID
- winning config value

If the system cannot explain why a value won, the model is too flexible.

---

## Root-Specific Interpretation

The three roots still mean the same thing as in v1.

### Platform Root

Purpose:

- shared infrastructure and service settings
- auth, email, rate limiting, LLM providers, storage backends, external service endpoints

Tenant-defined platform trees are appropriate for:

- deployment stages
- rollout rings
- region overlays
- `prod-a` / `prod-b` style branches

Typical platform node bindings:

- deployment profile
- environment channel
- rollout branch

Example:

```text
default
└── prod
    ├── prod-a
    └── prod-b
```

### Runtime Root

Purpose:

- agent behavior
- prompts
- skill tuning
- guardrails
- response policy

Tenant-defined runtime trees are appropriate for:

- agent family inheritance
- surface-specific overlays
- channel-specific response behavior
- staged runtime experiments

Example:

```text
base
└── website
    └── frontpage
```

This might mean:

- `base` applies to all runtime traffic for a tenant
- `website` applies to a website surface
- `frontpage` applies to a specific website surface variant

But those meanings must be represented by explicit bindings, not by names alone.

### Dataset Root

Datasets are globally identified and are not tenant-owned entities. Tenant-specific access and applicability are mediated through tenant-local bindings, not through a `dataset/tenant` field.

Purpose:

- source definitions
- extraction and transforms
- chunking and indexing
- storage layout
- materialization operations

Tenant-defined dataset trees are appropriate for:

- materialization profiles
- source-specific overlays
- ingestion rollout branches


Some tenants may keep this nearly flat, which is acceptable.

---

## Target Resolution Contract

The resolution contract must remain explicit.

### Platform Resolution

Inputs:

- `tenant-id`
- `platform-node-id`
- `path`

Algorithm:

1. Start at `platform-node-id`
2. Check for exact value
3. If missing, move to parent
4. Repeat until root
5. If not found, return no value

### Runtime Resolution

Inputs:

- `tenant-id`
- `agent-id`
- `runtime-config-key`
- `path`

Algorithm:

1. Resolve the runtime node explicitly or via binding tables
2. Start at that node
3. Check for exact value for the given runtime target
4. Walk ancestors upward
5. First match wins

### Dataset Resolution

Inputs:

- `dataset-ref`

Algorithm:

1. Resolve the dataset node explicitly or via binding tables
2. Start at that node
3. Check for exact value for the given dataset target
4. Walk ancestors upward
5. First match wins

The exact storage schema may vary, but the operational contract must remain this simple.

---

## Data Model Sketch

This ADR does not require a final schema, but the model should look roughly like this.

### Root node

```clojure
{:config.node/id "runtime/frontpage"
 :config.node/root :runtime
 :config.node/tenant "ka"
 :config.node/label "Frontpage"
 :config.node/parent "runtime/website"
 :config.node/enabled? true}
```

### Binding

```clojure
{:config.binding/root :runtime
 :config.binding/tenant "ka"
 :config.binding/node-id "runtime/frontpage"
 :config.binding/type :agent
 :config.binding/value "website-assistant"}
```

Possible binding types:

- `:runtime`
- `:dataset`
- `:platform`

### Value

```clojure
{:config.value/root :runtime
 :config.value/tenant "ka"
 :config.value/node-id "runtime/frontpage"
 :config.value/path "skills.retrieval.top-k"
 :config.value/value "40"}
```

This keeps:

- root explicit
- tenant explicit
- node explicit
- binding explicit

and avoids encoding semantics into ad hoc strings.

---

## UI Implications

The UI should still expose three top-level tabs:

- Platform
- Runtime
- Dataset

Inside each tab, the tenant can manage:

- nodes
- parent links
- bindings
- values

The UI must not expose arbitrary graph editing.

Supported operations:

- create node
- rename label
- set parent
- bind node to entities or profiles
- view effective path to root
- edit values at node
- inspect resolution trace

The UI must validate:

- no cycles
- single parent only
- valid root and tenant ownership
- valid binding types for that root

---

## Permission Model

Root-based permissions from v1 remain valid.

Config-management permissions should be grantable by:

- root
- tenant
- optional node subtree
- optional binding target

Examples:

- allow editing Platform root under subtree `platform/prod`
- allow editing Runtime root for bindings targeting agent `website-assistant`
- allow editing Dataset root for bindings targeting dataset `kudos`

This is stricter and clearer than unrestricted tenant-wide graph editing.

---

## Operational Risks

This model is safer than fully arbitrary hierarchy, but it still introduces real complexity.

### Risk 1: Hidden semantics in node names

Mitigation:

- require explicit bindings
- treat labels as presentation only

### Risk 2: Debugging difficulty

Mitigation:

- require resolution traces
- show selected node and winning ancestor in UI and diagnostics

### Risk 3: Permission sprawl

Mitigation:

- root-based permissions remain primary
- subtree permissions are explicit and inspectable

### Risk 4: Tenants recreating one-off config grammars

Mitigation:

- fixed engine
- fixed binding types
- no custom precedence logic
- no multiple inheritance

### Risk 5: Overuse of deep trees

Mitigation:

- encourage shallow trees
- document that depth should be justified operationally
- add warnings for excessive depth

---

## Why This Is Preferable to the Fixed Hierarchy Model

Compared with v1:

- it preserves the valuable split into Platform, Runtime, and Dataset roots
- it removes the false assumption that all tenants share the same inheritance ladder
- it allows rollout branches and surface overlays without changing core semantics
- it keeps the engine deterministic and supportable

Compared with fully arbitrary hierarchy:

- it avoids graph complexity
- it avoids tenant-specific precedence rules
- it preserves explainability
- it preserves bounded access control

This is the main tradeoff:

- more flexibility than fixed ladders
- much less danger than arbitrary config graphs

---

## Migration Guidance from v1

If the system is already moving toward the v1 three-root model, the v2 change should be implemented as:

1. keep the same root classification of config definitions
2. replace fixed root ladders with root-local node trees
3. add explicit node entities and binding entities
4. migrate existing fixed-scope values into default tenant trees
5. bind current fixed concepts into initial node layouts

Suggested initial bootstrap:

### Platform bootstrap

```text
default
└── prod
```

### Runtime bootstrap

```text
default
└── default-runtime
```

Bind current tenant runtime traffic to `default-runtime`.

### Dataset bootstrap

```text
default
└── default-materialization
```

Bind current dataset and pipeline traffic to `default-materialization`.

This allows rollout in two phases:

- first change root separation
- then allow tenants to refine tree shape

---

## Acceptance Criteria

This ADR should be considered successfully implemented only if the system can do all of the following:

1. classify every config definition into exactly one root
2. store tenant-defined trees per root with single-parent validation
3. store explicit bindings between nodes and recognized target types
4. resolve config deterministically by walking parent links
5. explain each resolved value via a trace
6. enforce permissions by root and bounded subtree or binding target
7. expose manageable node editing in the admin UI without arbitrary graph semantics

---

## Final Position

We adopt tenant-defined inheritance trees inside fixed roots, with strict constraints.

This is explicitly:

- not arbitrary graph configuration
- not multiple inheritance
- not label-based inference
- not tenant-defined precedence semantics

It is:

- fixed roots
- fixed engine
- tenant-defined trees
- explicit bindings
- deterministic resolution
- explainable results

# Config Tree Schema Implementation Plan

## Purpose

This plan turns the target architecture in:

- [platform-runtime-dataset-config-roots.md](../../decisions/platform-runtime-dataset-config-roots.md)
- [config-tree-schema-proposal.md](../../docs/architecture/config-tree-schema-proposal.md)

into a staged implementation sequence for the current codebase.

The implementation strategy is intentionally incremental:

- stabilize the architecture first
- add schema and storage primitives before changing runtime resolution
- prove the new model with one root before migrating everything
- avoid a single all-or-nothing cutover until the model has been exercised

---

## Current Status

As of 2026-03-29, Phases 0-10 are complete. The V2 model is live across all three roots, the compatibility layer has been removed from normal operation, and the remaining tuple-scoped schema/data retirement work is now complete as well.

- `platform-runtime-dataset-config-roots.md` is the active target architecture.
- Phase 0 is complete.
- Phase 1 is complete.
- Phase 2 is complete.
- Phase 3 is complete.
- Phase 4 is complete.
- Phase 5 is complete.
- Phase 6 is complete.
- Phase 7 is complete.
- Phase 8 is complete.
- Phase 9 is complete.
- Phase 10 is complete.
- Phase 10 operator-safety hardening is complete.
- The compatibility layer used in normal runtime, platform, dataset, and public config-management flows has been removed.
- The disconnected legacy inheritance-table implementation has been removed from `config/ui.cljc`.
- The live tuple-scoped schema/data layer has been retired from config schema, config export/import, setup flows, and active tests.

### Completed so far

- architecture freeze completed, with v2 marked as the active implementation target and v1 marked as superseded
- schema bootstrap completed for `:config-def/root`, `:config.node/*`, `:config.binding/*`, `:config.value/*`, `:dataset/*`, `:dataset.pipeline/*`, and `:api-key.config-grant/*`
- low-level storage APIs completed in `config/db.clj` for:
  - rooted definition persistence
  - config node CRUD
  - config binding CRUD
  - dataset and dataset-pipeline CRUD
  - node-scoped value CRUD
  - parent-walk `resolve-node-value`
- initial Phase 3 resolver work completed in `config/db.clj` for:
  - `resolve-node-value-with-trace`
  - root-local selection helpers for platform, runtime, and dataset nodes
  - disabled-node enforcement during selection and resolution
  - maximum tree-depth enforcement during parenting validation
- initial runtime accessor proof slice completed in `config/accessor.clj` for:
  - runtime-node selection through the V2 model
  - traced runtime value lookup
  - runtime config loading for selected `skills.*` paths
- an initial Platform accessor slice now exists in `config/accessor.clj`:
  - rooted `services.*`, `auth.*`, and `email.*` definitions can resolve through Platform V2 nodes via `get-platform-value-with-trace`
  - `cfg/get` now prefers Platform V2 for `:platform`-rooted definitions
  - rooted Platform definitions are now V2-only; legacy tuple-scoped fallback has been removed for Platform families
  - missing current Platform profiles now fail closed instead of silently falling back
- Platform cutover now has an operational bootstrap path in `setup.clj` and `bb.edn`:
  - `bootstrap-tenant-platform-tree!` snapshots a tenant/profile's resolved legacy Platform view into a real Platform V2 profile
  - tenant setup now seeds a default Platform profile alongside the default Runtime tree
  - `bb bootstrap-platform-tree <tenant> [profile-id]` exists for existing tenants
  - setup-side Platform definitions now explicitly upsert `:root :platform`, so bootstrap/backfill can move existing `services.*` families onto Platform V2 resolution
- first concrete runtime call-site integration completed in `playground/core.cljc` for:
  - V2-first runtime skill config loading in playground chat execution
  - persisted runtime resolution metadata (`source`, selected node, traces, resolution error) in execution diagnostics
- tenant runtime-tree bootstrap now exists in `config/ops.clj` for:
  - idempotent creation of a default runtime node chain
  - runtime-profile, agent, and optional dataset bindings
  - seeding of node-scoped `skills.*` values on base and leaf nodes
- tenant setup now seeds the default runtime tree in `setup.clj`, and an explicit `bb bootstrap-runtime-tree` task exists for existing dev/test tenants
- a real dev tenant (`ka`) has now been bootstrapped and verified to resolve runtime config through the V2 runtime tree
- the public RAG API route now uses V2 runtime skill config for agent-scoped runtime reads and no longer reads tuple-scoped runtime skill config in the no-agent path
- the playground UI selected-pipeline loader now resolves runtime defaults through the same V2 runtime model instead of tuple-scoped runtime skill config
- full-system export/import now carries config-tree entities and portable config-management grants for migration:
  - config nodes
  - config bindings
  - datasets and dataset pipelines
  - node-scoped config values
  - API key config grants via denormalized `node-id`
- runtime path rewrite in migration transforms now also updates exported node-scoped values
- legacy tuple-scoped runtime `skills.*` values now migrate during transform into tenant bootstrap runtime nodes, with per-value move reporting and synthesized bootstrap nodes/bindings in the transformed payload
- dataset bindings are still not auto-created during migration; instead the transform emits explicit review candidates for legacy pipeline-scoped runtime values so a later enrichment pass can bind datasets only where the mapping is deterministic
- a first deterministic enrichment rule now exists: if the transformed payload already contains exactly one dataset-pipeline whose `id` or `name` exactly matches the legacy pipeline token, the transform auto-creates the runtime dataset binding; otherwise the item stays in manual review
- implementation docs reconciled so the accepted ADR, schema proposal, archived v1 write-up, and implementation plan no longer disagree on the target model or filenames
- focused tests added and passing for the new storage layer
- focused playground tests now cover both successful V2 runtime resolution and fail-closed runtime resolution errors
- focused ops tests now cover runtime-tree bootstrap creation and idempotent re-bootstrap updates
- root-independent config-tree bootstrap now exists in `config/ops.clj` for:
  - `:platform`, `:runtime`, and `:dataset`
  - shared default node layout and profile-binding defaults per root
  - shared node/binding/value seeding across roots with root-specific value normalization only where required
- focused ops tests now also prove Phase 5 bootstrap for:
  - `services.*` values on the Platform root
  - `pipeline.*` materialization values on the Dataset root
  - dataset and pipeline compatibility bindings through the same generic bootstrap API
- Phase 9 runtime cutover has started beyond writes:
  - legacy tuple-scoped runtime writes and deletes are blocked in the legacy Config UI
  - runtime-root definitions are hidden from the legacy inheritance editor with an explicit redirect to the Diagnostics tree editor
  - `routes/get-pipeline-config` now fails closed for agent-scoped runtime reads when V2 runtime resolution fails
  - `playground/core.cljc` now fails closed for runtime skill config resolution instead of falling back to legacy tuple-scoped skill config
- a cross-cutting performance and consolidation pass has been applied:
  - `config/db.clj`: added `resolve-node-values-batch` which prefetches the ancestor chain once (depth queries for the walk, plus 1 batch query for all node values across all ancestors), then resolves multiple paths in-memory. Total cost is depth + 2 queries per batch, versus N * depth queries for per-path resolution
  - `config/db.clj`: `get-pipeline` now uses batch resolution instead of per-property queries (~80 queries reduced to depth + 2)
  - `config/db.clj`: `upsert-skill-settings!` now uses `set-values-batch!` for a single transaction instead of per-property transactions
  - `config/accessor.clj`: `load-runtime-config-v2-with-trace` now uses batch resolution for all paths in a single ancestor walk instead of resolving each path independently
  - `config/ops.clj`: consolidated 10+ `get-all-*`/`get-tenant-*` query pairs into parameterized `query-*` helpers, reducing ~100 lines of duplication
  - `config/ops.clj`: extracted shared `export-data*` helper to eliminate duplication between `export-full` and `export-tenant`
  - `config/ops.clj`: clone operations (`clone-tenant`, `clone-environment`) now batch transactions in groups of 100 instead of one transaction per value
  - `config/ui.cljc`: extracted `with-admin-conn` helper to eliminate 8 repeated permission/connection/error wrappers in operation handlers
- Phase 10 hardening has started in `config/ui.cljc`:
  - tree diagnostics now expose derived node depth and effective-disabled state
  - the Diagnostics tab now shows soft depth warnings once a node reaches depth 4 or deeper
  - admin tree mutations now invalidate the accessor cache after successful structure, binding, or node-value edits
  - semantic dry-run reparent preview now shows which effective values and winning nodes would change before a parent mutation is submitted
  - semantic dry-run binding preview now shows the effective config a pending binding would expose before it is created
  - destructive tree mutations now export tenant-scoped pre-mutation snapshots through the existing config export pipeline
  - destructive delete/reset actions now show concrete preview details for the exact bindings and direct values being removed
  - node-value reset previews now also show the post-reset effective value and winning node, not just the removed override
  - the remaining Phase 10 work is no longer UI hardening; it is the actual tuple-model cleanup described below

### Verification status

- `bb test-config` passed with config ops included
- latest observed result after tuple-schema/data retirement: `69 tests`, `309 assertions`, `0 failures`, `0 errors`
- `clj -M:test -n digdir.config.accessor-test` passed
- latest observed result: `11 tests`, `35 assertions`, `0 failures`, `0 errors`
- `clj -M:test -n digdir.playground.core-test` passed
- latest observed result: `4 tests`, `20 assertions`, `0 failures`, `0 errors`
- `clj -M:test -n digdir.migration.system-test` passed
- latest observed result: `11 tests`, `115 assertions`, `0 failures`, `0 errors`
- `clj -M:test -n digdir.config.api-keys-test` passed
- latest observed result: `21 tests`, `112 assertions`, `0 failures`, `0 errors`
- `clj -M:test -n digdir.api.routes-test` passed
- latest observed result: `63 tests`, `166 assertions`, `0 failures`, `0 errors`
- `clj -M:test -n digdir.playground.ui-test` passed
- latest observed result: `17 tests`, `43 assertions`, `0 failures`, `0 errors`
- `clj -M:test -n digdir.config.ui-test` passed
- latest observed result: included in `bb test-config`; focused UI assertions expanded through helper coverage
- live verification for tenant `ka` returned:
  - `{:source :v2, :node-id "runtime/ka/default-runtime", :skill-count 13}`
- live verification for tenant/profile `ka/dev` returned:
  - `services.typesense.collection-prefix` definition is now rooted at `:platform`
  - `cfg/get` resolves `services.typesense.collection-prefix` through Platform V2 with trace:
    - `{:selected-root :platform, :selected-tenant "ka", :selected-node "platform/ka/prod", :winning-node "platform/ka/prod", :decoded-value "KUDOS_alpha_"}`
  - additional live Platform bootstrap tasks completed for:
    - `altinn/dev`
    - `altinn-docs/dev`

---

## Scope

### In scope

- new schema for config roots, nodes, bindings, and node-based values
- first-class dataset and pipeline entities
- config-management grant model for API keys
- root-aware resolution engine
- bootstrap migration from tuple-scoped values to node-scoped values
- admin tooling and UI for nodes, bindings, and resolution traces

### Out of scope for the first execution slice

- full user-authored graph editing UX beyond bounded tree editing
- immediate migration of all existing runtime and ingestion flows
- removal of all legacy tuple-scoped config rows in the first pass
- complete pipeline/dataset naming cleanup beyond what is required for the node model

---

## Guiding Constraints

These are non-negotiable and should be enforced from the first schema patch:

- roots remain fixed: `:platform`, `:runtime`, `:dataset`
- nodes are tenant-local and root-local
- nodes have single parent only
- cycles are forbidden
- bindings use fixed supported types
- resolution is explicit parent walk only
- every resolved value must be explainable by trace

---

## Main Files

### Primary implementation files

- [schema.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/schema.clj)
- [db.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/db.clj)
- [ops.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/ops.clj)
- [accessor.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/accessor.clj)
- [api_keys.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/api_keys.clj)
- [data/db.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/data/db.cljc)
- [routes.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/api/routes.clj)

### Primary test files

- [db_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/config/db_test.clj)
- [ops_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/config/ops_test.clj)
- [api_keys_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/config/api_keys_test.clj)
- [routes_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/api/routes_test.clj)

### Primary UI files

- [api_keys.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/ui/api_keys.cljc)
- [audit.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/ui/audit.cljc)
- [permissions.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/ui/permissions.cljc)

---

## Phase 0: Architecture Freeze

Status: Complete

## Goal

Make the target model explicit so implementation does not drift between fixed hierarchies and tenant-defined trees.

## Work

1. Treat [platform-runtime-dataset-config-roots.md](/Users/bdbrodie/dev/digdir/rag/decisions/platform-runtime-dataset-config-roots.md) as the active target model.
2. Keep [new-config-hierarchies.md](/Users/bdbrodie/dev/digdir/rag/docs/architecture/new-config-hierarchies.md) as the archived fixed-hierarchy predecessor, but reference the accepted ADR as the active direction in future planning.
3. Keep [config-tree-schema-proposal.md](/Users/bdbrodie/dev/digdir/rag/docs/architecture/config-tree-schema-proposal.md) as the concrete storage contract.

## Exit criteria

- implementation work references v2, not v1, when discussing inheritance shape
- the team agrees that the first execution slice will prove only the Runtime root

---

## Phase 1: Schema Bootstrap

Status: Complete

## Goal

Add the new structural schema without changing live config resolution.

## Files

- [schema.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/schema.clj)
- [data/db.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/data/db.cljc)

## Work

### Config DB additions

Add:

- `:config-def/root`
- `:config.node/*`
- `:config.binding/*`
- `:config.value/*`
- `:dataset/*`
- `:dataset.pipeline/*`
- root-aware audit fields if included in first slice

### Main DB additions

Add:

- `:api-key/config-grants`
- `:api-key.config-grant/*`

### Migration boot

- extend schema initialization so the new attributes are transacted automatically
- do not remove old config tuple schema yet

## Notes

- The current shared Datahike connection model means config grants may use refs directly.
- Keep denormalized IDs where they are useful for export/import and diagnostics.

## Exit criteria

- application boots with the expanded schema
- no existing code path breaks
- tests covering schema presence pass

### Completion notes

- schema primitives were added to `schema.clj` and `data/db.cljc`
- schema-level tests were added for config-tree entities and config-grant refs

---

## Phase 2: Low-Level Storage API

Status: Complete

## Goal

Implement CRUD for nodes, bindings, datasets, pipelines, and node-scoped values before changing any public resolution behavior.

## Files

- [db.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/db.clj)
- [db_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/config/db_test.clj)

## Work

### Add node APIs

- `create-config-node!`
- `update-config-node!`
- `delete-config-node!`
- `get-config-node`
- `list-config-nodes`
- `set-config-node-parent!`

### Add validation helpers

- root-local validation
- tenant-local validation
- cycle detection
- allowed binding-type-by-root validation

### Add binding APIs

- `create-config-binding!`
- `delete-config-binding!`
- `list-config-bindings`
- `find-config-node-by-binding`

### Add dataset and pipeline entity APIs

- `create-dataset!`
- `update-dataset!`
- `list-datasets`
- `create-dataset-pipeline!`
- `update-dataset-pipeline!`
- `list-dataset-pipelines`

### Add node-based value APIs

- `set-node-value!`
- `get-node-value`
- `delete-node-value!`
- `list-node-values`

## Exit criteria

- nodes can be created and parented safely
- invalid cycles are rejected
- bindings can be created and queried
- node-scoped values can be written and read back

### Completion notes

- `upsert-definition!` now persists `:config-def/root`
- `config/db.clj` now contains root-aware CRUD for nodes, bindings, datasets, pipelines, and node-scoped values
- parent-walk `resolve-node-value` is available as the low-level resolver primitive
- tests cover rooted definitions, parent inheritance, cycle rejection, binding lookup, dataset/pipeline CRUD, and leaf-node deletion semantics

---

## Phase 3: Resolution Engine V2

Status: In Progress

## Goal

Implement the new parent-walk resolver without cutting over current config accessors yet.

## Files

- [db.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/db.clj)
- [accessor.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/accessor.clj)
- [db_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/config/db_test.clj)

## Work

### Add explicit selection helpers

- `resolve-platform-node!`
- `resolve-runtime-node!`
- `resolve-dataset-node!`

Selection priority:

1. explicit `node-id`
2. explicit profile binding
3. no implicit label guessing

### Add compatibility checks

- runtime node valid for `agent-id`
- runtime node valid for optional `dataset-id`
- dataset node valid for `dataset-id`
- dataset node valid for optional `pipeline-id`

### Add parent-walk value resolution

- `resolve-node-value`
- `resolve-node-value-with-trace`
- `load-node-config`

### Add trace payload

Trace should include:

- selected root
- selected tenant
- selected node
- ancestor path
- winning node
- path
- decoded value

### Progress notes

- initial low-level selection helpers now exist in `config/db.clj`
- `resolve-node-value-with-trace` now exists in `config/db.clj`
- disabled nodes are rejected for explicit selection and stop ancestor traversal during resolution
- initial runtime accessor integration now exists in `config/accessor.clj`
- `bb test-config` now includes the new runtime accessor proof-slice tests
- remaining work in this phase is to connect these primitives into concrete runtime call sites and broader compatibility-aware config loading

## Exit criteria

- resolution works without old tuple precedence
- trace output is stable and readable
- tests cover simple, deep, and missing-value cases

---

## Phase 4: Runtime Root Proof Slice

Status: In Progress

## Goal

Prove the model end-to-end using only the Runtime root.

## Why Runtime First

- it is where the current conceptual mismatch is worst
- `skills.*` is already the canonical runtime namespace
- it exercises agent, dataset, and profile bindings without forcing full ingestion cutover

## Files

- [db.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/db.clj)
- [accessor.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/accessor.clj)
- [routes.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/api/routes.clj)
- [playground/core.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/playground/core.cljc)
- [skills/context.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/context.clj)
- [routes_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/api/routes_test.clj)

## Work

### Bootstrap one runtime tree per tenant

Suggested bootstrap:

```text
default
└── default-runtime
```

### Bootstrap bindings

- bind `default-runtime` to one runtime profile
- bind one agent
- bind one dataset

### Move one canonical path family

Use:

- `skills.retrieval.top-k`

or another small `skills.*` slice as the first migrated path family.

### Add a parallel resolution path

- keep old tuple-scoped access for existing runtime code
- add a new V2 runtime access path for the proof slice
- do not delete old code yet

### Progress notes

- the playground chat execution path now prefers `cfg/get-runtime-skill-config-v2-with-trace`
- the playground records runtime resolution source, selected node, traces, and fallback errors in `:resolved-runtime-context`
- legacy pipeline-scoped `get-skill-config` remains as the fallback path when V2 runtime resolution is not available
- tenant-local runtime tree bootstrap now exists in `config/ops.clj` and is covered by focused tests
- the default tenant-setup flow now seeds one builtin-agent runtime profile, and existing tenants can be bootstrapped explicitly via `bb bootstrap-runtime-tree`
- the `ka` dev tenant has been bootstrapped and verified through the V2 runtime accessor path
- the public `api-rag-handler` path now exercises the same V2-first runtime resolution approach as playground
- remaining work in this phase is to decide whether retrieval-only or admin/debug runtime surfaces should adopt V2-aware diagnostics before broader cutover

## Exit criteria

- one real runtime path resolves through node inheritance in dev/test
- route/playground code can ask for a runtime value through V2
- resolution trace is visible for debugging

---

## Phase 5: Platform and Dataset Root Bootstrap

Status: Complete

## Goal

Extend the proven model to the other two roots.

## Files

- [db.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/db.clj)
- [setup.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/setup.clj)
- [pipeline/core.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/pipeline/core.clj)
- [pipeline/collections.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/pipeline/collections.clj)

## Work

### Platform bootstrap

Suggested default tree:

```text
default
└── prod
```

Start by migrating a small, clearly shared family such as:

- `services.rate-limiting.*`

or:

- `services.typesense.*`

### Dataset bootstrap

Suggested default tree:

```text
default
└── default-materialization
```

Start by migrating a small, clearly materialization-scoped family such as:

- `pipeline.operations.*`

or:

- `pipeline.storage.*`

### Dataset and pipeline entities

- create first-class dataset and pipeline records
- stop relying on pipeline name as the only durable identifier for new code

### Progress notes

- Phase 5 bootstrap is now implemented through the root-independent `bootstrap-config-tree!` API in `config/ops.clj`
- runtime bootstrap now delegates to the same generic primitive rather than maintaining a separate code path
- focused tests in `config/ops_test.clj` now prove:
  - Platform bootstrap with `services.auth.session-max-age` and `features.playground.enabled`
  - Dataset bootstrap with `pipeline.chunking.size` and `pipeline.indexing.enabled`
  - resolution through the shared V2 parent-walk engine for all three roots
- tenant setup remains runtime-specific for now because Platform bootstrap is still handled by the existing setup flows and Dataset bootstrap requires explicit dataset/pipeline identity rather than a tenant-only default

## Exit criteria

- all three roots can resolve values through the V2 engine
- at least one path family has been proven in each root

---

## Phase 6: Export, Transform, and Import Support

Status: In Progress

## Goal

Make the new model migratable and operable.

## Files

- [ops.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/ops.clj)
- [system.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/migration/system.clj)
- [ops_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/config/ops_test.clj)

## Work

### Export additions

Export:

- config nodes
- bindings
- datasets
- pipelines
- node-scoped values
- config grants

### Transform additions

- classify existing definitions by root
- rewrite legacy runtime aliases to canonical `skills.*`
- map tuple-scoped values into bootstrap nodes

### Import additions

- import nodes before node-scoped values
- import datasets before pipelines
- import config grants after nodes exist

### Completion notes so far

- `config/ops.clj` export/import now supports:
  - `:nodes`
  - `:bindings`
  - `:datasets`
  - `:dataset-pipelines`
  - `:node-values`
- `migration/system.clj` now includes those sections in the system envelope and preserves `:api-key/config-grants`
- the migration transform now rewrites legacy runtime aliases inside exported node-scoped values as well as tuple-scoped values
- the migration transform now classifies inferred definition roots and moves legacy tuple-scoped runtime `skills.*` values into tenant bootstrap runtime nodes:
  - tenant-only values move to the tenant base node
  - more specific legacy values move to the tenant leaf node
  - the transform reports each move and the dropped legacy scope dimensions
  - the transform also reports candidate dataset-binding reviews for migrated runtime values that previously depended on legacy pipeline scope
- the transform now includes a conservative post-transform enrichment pass:
  - exact dataset-pipeline match by `dataset.pipeline/id` or `dataset.pipeline/name` -> auto-bind dataset
  - exact dataset match by `dataset/id` or `dataset/name` -> auto-bind dataset when no stronger pipeline match exists
  - anything else remains in the review report
- tenant-scoped config exports now prune unrelated global dataset entities:
  - datasets are included only when referenced by the tenant's exported config bindings
  - dataset pipelines are included only when referenced by tenant-local pipeline bindings
  - pipeline-linked datasets are pulled in automatically so tenant exports remain self-contained
- round-trip coverage in `migration/system_test.clj` now proves import/export for:
  - runtime nodes and bindings
  - dataset and dataset-pipeline entities
  - node-scoped runtime values
  - API key config grants via `node-id`
- focused config export tests now prove tenant export pruning for datasets and dataset pipelines

### Remaining work

- broaden the deterministic enrichment rules only if additional mapping sources are introduced, for example explicit legacy tuple metadata on dataset or dataset-pipeline records

## Exit criteria

- full export/import round trip works for new entities
- dry-run transform explains what will move where

---

## Phase 7: API Key Config-Grant Implementation

Status: In Progress

## Goal

Add root-aware config-management permissions without breaking existing runtime usage keys.

## Files

- [api_keys.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/api_keys.clj)
- [data/db.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/data/db.cljc)
- [routes.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/api/routes.clj)
- [api_keys_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/config/api_keys_test.clj)

## Work

### Add config grant CRUD

- create config-management grants
- list config-management grants
- validate read/write permissions by root, tenant, subtree, and binding target

### Preserve existing usage grants

- keep query/ingest/admin usage flows working during the transition
- do not mix config-management grants with runtime dataset access checks

### Progress notes

- `validate-api-key` now returns normalized `:config-grants`, and `wrap-api-key-auth` now injects them into the Ring request alongside existing dataset, agent, and scope grants
- `config/api_keys.clj` now includes reusable grant enforcement helpers for:
  - root and tenant matching
  - action matching (`:read` / `:write`)
  - optional subtree matching through node ancestry
  - optional binding-type and binding-value restrictions
- API key creation now accepts and persists normalized config-management grants:
  - grant roots are validated against `:platform`, `:runtime`, and `:dataset`
  - grant actions are validated against `:read` and `:write`
  - node-scoped grants validate referenced node IDs before transact
  - created API keys now carry config grants through the normal JWT-authenticated API-key management flow
- existing API keys can now replace their config-management grants through the JWT-authenticated management surface:
  - `config/api_keys.clj` exposes `replace-api-key-config-grants!`
  - `routes.clj` exposes `PUT /config/api-keys/:key-id/config-grants`
  - the route requires ownership by the authenticated user before replacing grants
  - empty `config-grants` payloads are allowed for explicit grant removal
- `routes.clj` now exposes API-key-authenticated `/api/config-get` and `/api/config-set` routes
- those config routes require both:
  - API key `:admin` scope
  - a matching config-management grant for the resolved root and tenant
- dataset-root pipeline admin handlers in `routes.clj` are now config-grant aware when requests carry `:api-key/config-grants`:
  - list pipelines requires tenant-wide `:dataset` read access
  - get/list-executions allow pipeline-scoped `:dataset` read grants
  - create/update/delete/execute allow pipeline-scoped `:dataset` write grants
  - handlers now preserve `ExceptionInfo` status codes so denied requests return `403` instead of collapsing to `500`
- the legacy debug config routes remain available behind the separate debug secret and are not changed by API-key grant enforcement
- focused tests now cover:
  - direct grant matcher behavior in `config/api_keys_test.clj`
  - grant replacement behavior in `config/api_keys_test.clj`
  - config grant injection in `wrap-api-key-auth`
  - successful `/api/config-get` authorization with a matching grant
  - rejected `/api/config-set` writes when the key only grants `:read`
  - successful and rejected JWT-managed config-grant update flows in `api/routes_test.clj`
  - dataset-root pipeline handler grant checks in `api/routes_test.clj`
- direct API-key-authenticated pipeline admin endpoints are explicitly deferred:
  - the current groundwork is the handler-level dataset-root authorization already present in `routes.clj`
  - a dedicated API-key route surface for pipeline admin operations should be added in a later Phase 7 follow-up rather than mixed into the current Phase 8 work

## Exit criteria

- admin/config routes can enforce root-aware config grants
- existing runtime API key behavior remains intact

---

## Phase 8: Admin UI and Diagnostics

## Goal

Make the model operable by humans.

## Files

- [api_keys.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/ui/api_keys.cljc)
- [audit.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/ui/audit.cljc)
- [permissions.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/ui/permissions.cljc)
- new config tree UI files as needed

## Work

### Tree UI

For each root tab:

- list nodes
- show ancestry
- edit parent
- create bindings
- edit node values

### Trace UI

Show:

- selected node
- ancestor walk
- winning node
- winning value

### Validation UX

- reject cycles
- reject invalid binding types
- warn on deep trees

### Progress notes

- initial Phase 8 diagnostics now live in `config/ui.cljc`:
  - `get-runtime-trace-data` loads runtime V2 traces in a UI-friendly shape
  - `format-runtime-trace` normalizes selected node, traversal path, winning node, stopped-at node, decoded value, and stop reason text
- the admin UI now exposes an actual `Diagnostics` tab in `config/ui.cljc`:
  - operators can enter runtime selectors and one or more config paths
  - the UI renders selected node metadata plus per-path trace cards
  - the panel is intentionally read-only and diagnostics-first
- the Diagnostics tab now also includes generic tree inspection:
  - operators can choose `:platform`, `:runtime`, or `:dataset`
  - the UI lists nodes, parent relationships, enabled state, grouped bindings, and direct node-scoped values
  - dataset-root inspection also shows the current dataset/pipeline catalog
- the Diagnostics tab now includes the first editable tree operation:
  - admins can create a binding directly from the tree inspector
  - binding types are constrained by root to keep the UI aligned with the V2 selection model
  - successful creation refreshes the inspector immediately so the change is inspectable in the same panel
- the Diagnostics tab now supports broader generic tree editing:
  - admins can create nodes with optional parent selection
  - each node card supports label/enabled updates and leaf-node deletion
  - nodes can be reparented or promoted back to root through the same generic mutation flow
  - bindings can be deleted directly from the node card
  - node-scoped values can be set from raw input and reset in place
  - root-local definitions are surfaced in the inspector so value editing can show type/encryption hints and block unknown paths
  - all mutations flow through one root-independent `mutate-config-tree!` helper in `config/ui.cljc`
- the inspector now includes operator-facing feedback and previews:
  - successful mutations surface a green notice banner inside the diagnostics panel
  - parent changes show a preview before the mutation is submitted
  - node deletion cards show a lightweight removal summary before confirmation
  - binding and node deletion confirmations now include more specific context
- the inspector now surfaces inline validation and draft state:
  - node cards show unsaved field summaries when label, enabled state, or parent differ from persisted data
  - the value editor explains whether it is waiting for a path, blocked on an unknown path, or ready with a typed definition
  - these messages are derived from shared helper functions rather than button-local conditionals
- the tree editor no longer requires admins to type raw node IDs for common operations:
  - create-node now derives a stable node ID from label/slug and shows it as a preview instead of an editable primary field
  - node selectors and parent selectors prefer human labels while still exposing the stable ID secondarily
  - direct value editing now selects from root-local definition paths instead of requiring free-form path entry
- focused `config.ui-test` coverage now proves:
  - trace formatting for resolved and stopped traces
  - end-to-end runtime trace loading against an in-memory config DB
  - diagnostics path input parsing for the new tab form
  - tree diagnostics data shaping and end-to-end runtime tree inspection loading
  - admin-only binding creation through the new diagnostics edit helper
  - generic node lifecycle mutations through the diagnostics edit helper
  - generic binding deletion and node-value set/reset through the same helper
  - definition-aware diagnostics payloads and generic node reparent/clear-parent mutations
  - stable preview and success-label helpers for the diagnostics editor UX
  - stable dirty-state and value-editor status helpers for inline validation UX
  - node-id suggestion and node-option labeling helpers for the ID-light editing flow
- Phase 8 still starts from diagnostics-first foundations:
  - the trace and tree data layers now support both inspection and meaningful editing
  - remaining work is about polishing the editing model, not introducing it from scratch

## Exit criteria

- an admin can inspect and edit a tree without touching raw EDN or IDs directly
- resolution traces are understandable in the UI

---

## Phase 9: Controlled Cutover

## Goal

Move production behavior from tuple-scoped config to node-scoped config without carrying both forever.

## Work

1. cut runtime reads to V2 resolution for migrated paths
2. cut platform reads to V2 resolution for migrated paths
3. cut dataset/materialization reads to V2 resolution for migrated paths
4. stop writing new tuple-scoped values for migrated path families
5. remove old resolution for fully migrated families

## Recommended order

1. Runtime
2. Platform
3. Dataset

Runtime should cut first because it is the strongest conceptual win and easiest to validate with traces.

### Progress notes

- runtime write cutover has started in `config/ui.cljc`:
  - legacy tuple-scoped writes and deletes are now rejected for `:runtime`-root definitions
  - the legacy inheritance table hides runtime-root definitions and shows an explicit notice directing admins to the Diagnostics tab tree editor
  - this prevents new tuple-scoped runtime drift while preserving legacy tuple editing for remaining Platform and Dataset-era families
- runtime read cutover has now started at the first concrete call sites:
  - `routes/get-pipeline-config` now requires V2 runtime config for agent-scoped runtime reads and returns a `409` with runtime-resolution metadata instead of silently falling back
  - `routes/get-pipeline-config` no longer reads tuple-scoped runtime skill config in the no-agent path; it returns pipeline data only unless agent-scoped V2 runtime config is requested
  - `playground/core.cljc` now requires V2 runtime config for playground chat execution and fails closed before pipeline execution when runtime selection/resolution fails
  - `playground/ui.cljc` selected-pipeline loading now resolves runtime defaults through V2 runtime config instead of tuple-scoped runtime skill config
  - focused tests cover both the successful V2 path and the fail-closed error path for route and playground runtime reads, plus the playground UI helper path
- Platform read cutover has now started in the primary accessor:
  - `cfg/get` now prefers Platform V2 node resolution for `:platform`-rooted definitions
  - the default Platform selector is the current tenant plus current environment as `:platform-profile`
  - rooted Platform families no longer use tuple-scoped fallback at all
  - missing current Platform profiles now fail closed instead of silently falling back
  - `setup/bootstrap-tenant-platform-tree!` now provides the migration path out of that fallback by seeding a tenant/profile Platform leaf from currently resolved legacy Platform values
  - new tenants created through setup now get both a default Platform profile and the default Runtime tree
  - tenant/profile `ka/dev` has now been bootstrapped and verified live on the Platform V2 path for `services.typesense.collection-prefix`
  - existing active tenants have now had `dev` Platform bootstrap tasks executed (`ka`, `altinn`, `altinn-docs`), so the fallback is now mainly protecting unbootstrapped profiles rather than the common dev path
  - Platform families now require Platform V2 resolution everywhere
- Dataset read cutover is now implemented end-to-end:
  - `config.db/get-pipeline` now resolves `pipeline.*` materialization values through Dataset V2 node resolution instead of tuple-scoped `resolve-value`
  - `pipeline/core.clj`, `pipeline/executor.clj`, routes, playground defaults, and other call sites now switch together under that shared `get-pipeline` path
  - Dataset compatibility bindings are now allowed to resolve through ancestor nodes, which supports one shared dataset/pipeline base node with environment/profile-specific materialization leaves
  - `config.ops/bootstrap-dataset-tree!` now provides the root-independent dataset/materialization bootstrap primitive
  - `setup/bootstrap-tenant-dataset-tree!` and `bb bootstrap-dataset-tree <tenant> <environment> <pipeline-id> [dataset-id]` now provide the legacy snapshot path into Dataset V2
  - pipeline creation, update, duplication, and collection-name tracking now keep Dataset V2 materialization trees in sync for tenant-local pipelines
  - the legacy tuple-scoped Config UI now hides `:dataset`-rooted definitions and directs those edits to the Diagnostics tree editor
  - live Dataset V2 bootstrap has been executed and verified for `ka/dev/kudos` and `altinn/dev/assistant`
  - the remaining unmigrated dataset edge case is legacy global `_/_/*` pipeline config, which still needs an explicit migration decision because Dataset trees are tenant-local by design

## Exit criteria

- migrated path families no longer depend on tuple-scoped `config/id`
- old resolution remains only for unmigrated path families

---

## Phase 10: Legacy Cleanup

## Goal

Remove the old model once all required path families have cut over.

## Files

- [db.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/db.clj)
- [accessor.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/accessor.clj)
- [schema.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/schema.clj)
- migration/export code as needed

## Work

- remove tuple-scoped resolution paths
- deprecate old `:config/*` tuple fields if no longer needed
- simplify accessors so they no longer advertise pipeline-based override semantics for runtime config
- remove compatibility-only migration branches
- add post-cutover safety and operator UX:
  - semantic reparent preview that shows which effective values and winning nodes would change
  - dry-run binding preview that shows the effective config a new binding would produce
  - optional pre-mutation tenant snapshot hooks for destructive tree edits
  - effective-disabled tree diagnostics based on ancestor disablement
  - soft depth warnings before the hard max-depth validation threshold
  - cache invalidation on direct tree mutations while any legacy accessor path remains

## Progress notes

- Phase 10 has started with low-risk hardening rather than immediate tuple-model deletion:
  - `build-config-tree-diagnostics` now computes `:depth`, `:depth-warning`, `:effectively-disabled?`, and `:disabled-by-node-id` for each node
  - the Diagnostics tab now surfaces those fields inline so operators can see deep-tree and ancestor-disabled states without running traces first
  - `mutate-config-tree!` now calls `accessor/invalidate-cache!` after every successful tree mutation, covering node create/update/delete, reparenting, binding changes, and node-value edits
  - dry-run reparent preview is now implemented:
    - `config.db/preview-node-parent-change` resolves root-local definitions against both the current and preview parent chain without committing a mutation
    - `config.ui/get-node-parent-preview-data` decodes and diffs those results into path-level preview data
    - the Diagnostics node card now shows a semantic preview summary plus the first changed paths whenever a parent draft differs from the persisted parent
  - dry-run binding preview is now implemented:
    - `config.ui/get-binding-preview-data` validates pending bindings, rejects duplicate selector values, and batch-resolves the effective rooted config for the target node
    - the Diagnostics "Create binding" form now shows a semantic preview summary, a binding-selection note, and the first resolved paths before creation
    - binding creation is blocked in the UI when preview validation fails, so duplicate bindings fail before mutation rather than only at submit time
  - pre-mutation snapshot hooks are now implemented for destructive tree mutations:
    - `mutate-config-tree!` exports a tenant-scoped snapshot through `config.ops/export-to-file` before delete/reparent operations when tenant/root context is available
    - snapshots land under `server/state/config-tree-snapshots/<tenant>/...` and reuse the existing export format rather than inventing a new rollback artifact
  - destructive preview polish is now implemented in the Diagnostics tree editor:
    - node deletion previews list the exact bindings and direct values that will be removed, not just aggregate counts
    - binding deletion and node-value reset confirmations now include concrete preview text for the exact selector/override being removed
    - node-value reset previews also show the effective resolved value after the direct override is removed
  - focused `config.ui-test` coverage now proves the helper labels, derived diagnostics state, cache invalidation behavior, semantic reparent previews, semantic binding previews, destructive snapshot export hooks, richer delete/reset preview helpers, and reset-after-delete resolution previews
- the clean-cut removal has now been applied to the remaining compatibility surface:
  - `config.accessor` no longer exports tuple-scoped raw/bulk/section/pipeline accessors or tuple-scoped write wrappers
  - the old tuple-scoped pipeline CRUD helpers and legacy runtime skill accessor have been removed from `config.db`
  - `/api/config-get`, `/api/config-set`, `/api/debug/config-get`, and `/api/debug/config-set` have been removed from `routes.clj`
  - pipeline tests and integration tests now seed and assert Dataset V2 trees directly rather than tuple-scoped `config/value` rows
  - route and playground tests no longer stub legacy runtime fallback helpers because those call paths no longer exist
- the final dead-code cleanup has now been completed:
  - the disconnected inheritance-table implementation and its orphaned tests have been removed
  - `config/ui.cljc` has had a final low-risk DRY pass for tab rendering and stale style cleanup
- the only remaining follow-up is optional:
  - decide whether tuple-scoped schema/data fields should be retired immediately or left in place for migration/import history

## Exit criteria

- runtime, platform, and dataset config all resolve through nodes and parent walk
- the old scope tuple is no longer part of normal config reads
- the old tuple-scoped public config routes and accessor entry points are gone
- the disconnected legacy inheritance-table UI is gone

---

## Testing Strategy

## Unit tests

Add direct tests for:

- cycle detection
- root-local parent validation
- binding validation
- profile selection
- parent-walk resolution
- resolution trace shape

## Integration tests

Add integration coverage for:

- runtime root proof slice through route or playground access
- config grant enforcement
- export/import round trip

## Migration tests

Add dry-run and transformed-payload tests for:

- definition root classification
- legacy runtime alias rewrites
- bootstrap node creation
- tuple value to node value mapping

---

## Recommended First Execution Slice

If implementation starts immediately, the first PR should do only this:

1. add schema primitives
2. add node and binding CRUD
3. add node-based value CRUD
4. add resolver with trace
5. add tests
6. no route cutover yet

This keeps the first slice reviewable and lowers rollback risk.

The second PR should prove Runtime root resolution for one `skills.*` path family.

---

## Completion Definition

This plan is complete when:

- all three roots use node-based resolution
- datasets and pipelines are first-class entities
- config-management API keys use root-aware grants
- UI supports bounded tree editing and trace inspection
- tuple-scoped config resolution is removed from normal operation

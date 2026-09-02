# Explicit Node Resolution Clean-Cut Plan

Status note (2026-03-31):
This plan has been executed on the live path and is now kept as the
implementation record for the explicit-node cutover.

## Purpose

This plan turns the target model in:

- [explicit-node-resolution-schema-proposal.md](../../../docs/architecture/explicit-node-resolution-schema-proposal.md)
- [explicit-node-resolution-gap-analysis.md](../../../docs/architecture/explicit-node-resolution-gap-analysis.md)

into a concrete one-go implementation plan.

This is intentionally a clean cut:

- no long-lived compatibility layer
- no dual resolution semantics in normal runtime
- no profile-driven fallback during request-time node selection

## Assumptions

- request-facing node identity is the stable per-tenant slug
- durable internal node IDs remain internal
- each tenant/root has exactly one canonical root slug: `default`
- API keys authorize via one or more ceiling nodes per root
- compatibility is stored on tenant root nodes and validated fail-closed
- optional bindings may remain only as admin metadata

## Strategy

Reuse what is already good:

- rooted definitions
- node/value storage
- ancestry walk resolution
- batch trace generation

Replace what is now wrong for the target model:

- binding-driven selection
- config grants
- profile-driven defaults
- binding-based compatibility

## Workstreams

## Workstream 1: Freeze the Runtime Contract

### Goal

Make the cutover boundary explicit before editing storage and APIs.

### Work

1. Treat `node_slug` as the only request-facing node selector.
2. Remove profile-driven selection from the target design.
3. Freeze the rule that API keys grant ceilings per root, not binding
   restrictions.
4. Freeze the rule that compatibility is validated on the tenant root node.

### Exit Criteria

- schema proposal and gap analysis remain aligned
- no new work is added that depends on profile-driven selection

## Workstream 2: Schema Changes

### Goal

Add the durable fields and entities required for the new contract.

### Files

- `server/src/digdir/config/schema.clj`
- `server/src/digdir/data/db.cljc`

### Work

1. Make `:config.node/slug` required in practice and enforce non-blank values in
   DB helpers.
2. Add `:config.node/system-managed?`.
3. Add compatibility entities:
   - `:config.compatibility/id`
   - `:config.compatibility/root`
   - `:config.compatibility/tenant`
   - `:config.compatibility/node`
   - `:config.compatibility/type`
   - `:config.compatibility/value`
   - `:config.compatibility/created-at`
4. Add API-key ceiling entities:
   - `:api-key/config-ceilings`
   - `:api-key.config-ceiling/id`
   - `:api-key.config-ceiling/root`
   - `:api-key.config-ceiling/tenant`
   - `:api-key.config-ceiling/node`
   - `:api-key.config-ceiling/node-id`
   - `:api-key.config-ceiling/node-slug`
   - `:api-key.config-ceiling/created-at`
5. Keep `:api-key/config-grants` only for migration import, not for new runtime
   logic.

### Exit Criteria

- schema migrations exist for all new attributes/entities
- test DB boot can transact them cleanly

## Workstream 3: Data Backfill and Migration

### Goal

Convert live data into the new selection and auth model.

### Files

- `server/src/digdir/config/db.clj`
- `server/src/digdir/config/ops.clj`
- `server/src/digdir/migration/system.clj`
- `server/src/digdir/setup.clj`

### Work

1. Backfill a stable slug for every existing config node.
2. Enforce per-tenant/root slug uniqueness.
3. Ensure every tenant/root has a canonical root slug `default`.
4. Introduce fixed system-managed root-chain nodes, or a clearly equivalent
   representation, for each root.
5. Backfill root compatibility rows from existing compatibility bindings:
   - runtime `:agent` and `:dataset`
   - dataset `:dataset` and `:pipeline`
6. Mark existing profile bindings as advisory metadata or remove them if they
   are no longer useful.
7. Convert API key config grants into ceiling grants:
   - copy grant node ref, node ID, and node slug into new ceiling rows
   - fail migration if a config grant’s binding restriction cannot be safely
     expressed by ceiling + root compatibility semantics
8. Update export/import transforms to emit:
   - node slugs
   - compatibility rows
   - API key ceiling grants

### Exit Criteria

- migration script can transform a live export without ambiguity
- backfill reports all nodes with stable slugs
- no active key depends on binding-restricted config grants after migration

## Workstream 4: Resolver Rewrite

### Goal

Replace binding-driven selection with explicit slug selection.

### Files

- `server/src/digdir/config/db.clj`
- `server/src/digdir/config/accessor.clj`

### Work

1. Add `get-config-node-by-slug` and descendant checks by slug.
2. Replace `resolve-selected-node` with explicit slug resolution.
3. Remove request-time profile-binding selection from:
   - `resolve-platform-node!`
   - `resolve-runtime-node!`
   - `resolve-dataset-node!`
4. Introduce explicit compatibility validation against the tenant root node.
5. Extend resolution traces to include:
   - requested slug
   - matched ceiling
   - tenant root slug
   - fixed-chain nodes
6. Update batch resolution helpers to accept the resolved node and emit the new
   trace structure.

### Exit Criteria

- all accessors resolve from explicit slug input
- no normal runtime path depends on profile ID selection

## Workstream 5: API Key Auth Rewrite

### Goal

Switch config authorization from grants to ceilings.

### Files

- `server/src/digdir/config/api_keys.clj`
- `server/src/digdir/api/routes.clj`

### Work

1. Replace config-grant normalization with config-ceiling normalization.
2. Replace `config-grant-matches?` and `require-config-grant!` with:
   - `node-under-any-ceiling?`
   - `find-matching-config-ceiling`
   - `require-config-ceiling!`
3. Return the nearest matching ceiling for trace purposes.
4. Update API key validation middleware to expose ceiling grants, not config
   grants.
5. Replace route handlers and admin payloads that currently use
   `config-grants`.

### Exit Criteria

- no runtime auth path calls `require-config-grant!`
- API key admin CRUD reads and writes ceiling grants only

## Workstream 6: Public API Contract Rewrite

### Goal

Make explicit node navigation part of the public config/runtime contract.

### Files

- `server/src/digdir/api/routes.clj`

### Work

1. Add explicit resolve endpoints that require `node_slug`.
2. Add node discovery endpoints that list only nodes reachable from granted
   ceilings.
3. Remove `runtime-profile-id` from request-time config resolution.
4. Ensure no dataset, agent, or pipeline default implies config-node selection.
5. Keep dataset/agent defaults only if they remain product-level request
   conveniences unrelated to config-node choice.

### Exit Criteria

- every request-time config resolution route requires `node_slug`
- no route accepts profile-based config-node selection

## Workstream 7: UI Rewrite

### Goal

Make the admin UI reflect slugs, ceilings, and root compatibility.

### Files

- `server/src/digdir/config/ui.cljc`
- `server/src/digdir/config/ui/api_keys.cljc`

### Work

1. Make slug a required editable field on node creation/update.
2. Show slug as the user-facing identity, not internal node ID.
3. Replace binding-centric API key editing with ceiling editing.
4. Add root compatibility editing on tenant root nodes.
5. Reframe diagnostics around:
   - slug
   - matched ceiling
   - tenant root compatibility
   - resolution chain
6. Demote optional bindings to metadata-only UI.

### Exit Criteria

- UI can create and edit slugs, ceilings, and compatibility
- UI no longer implies that bindings select nodes at runtime

## Workstream 8: Bootstrap and Setup Rewrite

### Goal

Seed new tenants directly into the new model.

### Files

- `server/src/digdir/config/ops.clj`
- `server/src/digdir/setup.clj`

### Work

1. Rewrite bootstrap helpers to create canonical root nodes with slug
   `default`.
2. Stop creating required profile bindings for runtime selection.
3. Seed compatibility on the tenant root node instead of leaf bindings.
4. Seed initial ceiling grants on API keys using `default`.
5. Update setup commands and `bb` tasks to speak in slugs and ceilings.

### Exit Criteria

- fresh tenant bootstrap produces only clean-cut structures
- setup no longer relies on platform/runtime/dataset profile bindings

## Workstream 9: Test Rewrite

### Goal

Make tests prove the new semantics, not the retired ones.

### Files

- `server/test/digdir/config/accessor_test.clj`
- `server/test/digdir/config/db_test.clj`
- `server/test/digdir/config/api_keys_test.clj`
- `server/test/digdir/api/routes_test.clj`
- `server/test/digdir/config/ui_test.clj`
- `server/test/digdir/migration/system_test.clj`

### Work

1. Rewrite selection tests to use explicit slugs.
2. Add ceiling auth tests:
   - exact ceiling
   - descendant under ceiling
   - disjoint subtree rejection
   - multi-ceiling success
3. Add root-compatibility tests:
   - runtime root compatibility
   - dataset root compatibility
   - clear failure errors
4. Add fixed-chain trace tests.
5. Remove tests that assert profile-driven selection and binding-restricted
   config grants.

### Exit Criteria

- `bb test-config` passes on the new semantics
- API route tests prove required `node_slug` behavior

## Workstream 10: Cutover Execution

### Goal

Ship the new model in one deployment boundary.

### Pre-cutover Checks

1. Export a full backup.
2. Run a dry-run transform that:
   - backfills slugs
   - computes compatibility rows
   - converts config grants to ceilings
   - reports any ambiguous grants or duplicate slug collisions
3. Fix all reported collisions or unsupported grants before deployment.

### Deployment Sequence

1. Apply schema migrations.
2. Run the one-shot data migration/backfill.
3. Deploy the code that reads only:
   - slugs
   - ceiling grants
   - root compatibility metadata
4. Run smoke tests for:
   - API key validation
   - explicit config resolve
   - node discovery
   - runtime config resolution
   - dataset config resolution

### Post-cutover Cleanup

1. Remove dead profile-selection code paths.
2. Remove config-grant runtime usage.
3. Remove binding-based compatibility enforcement.
4. Archive or mark superseded docs that describe binding-driven selection.

## Acceptance Criteria

The cut is complete when:

- every request-facing resolver uses `node_slug`
- every API key uses ceiling grants
- compatibility is validated on tenant root nodes
- bindings no longer participate in request-time node selection
- node discovery is available to clients
- traces include authorization and resolution data
- setup/bootstrap/export/import all use the new model
- old selection semantics are gone from tests and normal runtime paths

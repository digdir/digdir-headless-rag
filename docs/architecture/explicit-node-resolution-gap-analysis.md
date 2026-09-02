# Explicit Node Resolution Gap Analysis

> **HISTORICAL** — superseded by [`decisions/platform-runtime-dataset-config-roots.md`](../../decisions/platform-runtime-dataset-config-roots.md) and `docs/system-overview.md` §4.1. Kept as a design record.

Status note (2026-03-31):
This document is now a historical pre-cutover analysis. The live system has since closed the core gaps it describes: request-facing root-specific selector usage, ceiling-only API-key auth, tenant-root compatibility entities, explicit public resolve endpoints, and ceiling-only export/import.

## Purpose

This document compares the current implementation with the target model defined
 in:

- [explicit-node-resolution-schema-proposal.md](archives/explicit-node-resolution-schema-proposal.md)

The goal was to identify what could be reused, what had to be rewritten, and
what had to be migrated for a clean cut.

## Executive Summary

The current codebase already has the right storage primitive for one part of the
target design:

- rooted config definitions
- tenant/root config nodes
- parent-walk value resolution
- batch path resolution with traces

The current codebase does not yet implement the new selection and authorization
 model:

- node selection is still binding-driven in practice
- public APIs still rely on implicit defaults for dataset and agent grants
- API key config grants are still action + node + optional binding restrictions
- compatibility is stored as bindings on arbitrary nodes, not as tenant-root
  metadata
- request-facing node identity is not based on stable per-tenant slugs
- fixed system-managed chain nodes above the tenant tree do not exist yet

This means the clean cut is not a small patch. The storage layer is reusable,
but the resolver contract, API-key auth model, bootstrap flows, admin UI, and
 migration/export surfaces all need coordinated change.

## Reusable Parts

## 1. Root-aware config definitions

Current implementation already supports `:config-def/root` and rejects
cross-root lookups.

Relevant files:

- `server/src/digdir/config/schema.clj`
- `server/src/digdir/config/accessor.clj`

Impact:

- keep this as-is

## 2. Node/value storage and ancestry walk

Current implementation already stores:

- `:config.node/*`
- `:config.value/*`
- single-parent ancestry
- `resolve-node-value-with-trace`
- `resolve-node-values-batch`

Relevant files:

- `server/src/digdir/config/schema.clj`
- `server/src/digdir/config/db.clj`

Impact:

- keep the value row model
- keep the batch resolution machinery
- extend the trace model rather than replacing it

## 3. API key persistence and middleware plumbing

Current implementation already has:

- API key entity storage
- validation middleware
- admin endpoints for API key CRUD
- audit logging for key changes

Relevant files:

- `server/src/digdir/data/db.cljc`
- `server/src/digdir/config/api_keys.clj`
- `server/src/digdir/api/routes.clj`

Impact:

- reuse the operational plumbing
- replace the config grant payload shape and matching rules

## Gaps

## 1. Request identity uses internal node IDs, not stable slugs

Target:

- clients send explicit root-specific selectors such as `platform-config-key`, `runtime-config-key`, or `dataset-config-key`
- slugs are stable within `(tenant, root)`
- durable internal node IDs stay internal

Current implementation:

- resolution accepts `:node-id`
- root-specific request slugs exist only as optional metadata
- no slug lookup API is part of the resolver contract

Relevant files:

- `server/src/digdir/config/schema.clj`
- `server/src/digdir/config/accessor.clj`
- `server/src/digdir/config/db.clj`

Gap:

- make slug required and request-facing
- add slug lookup and uniqueness enforcement

## 2. Node selection is still binding-driven

Target:

- bindings do not select the active node
- request slug is mandatory

Current implementation:

- `resolve-selected-node` chooses by explicit node ID or profile binding
- `resolve-platform-node!`, `resolve-runtime-node!`, and
  `resolve-dataset-node!` still accept profile-driven selection

Relevant file:

- `server/src/digdir/config/db.clj`

Gap:

- remove binding-driven selection from runtime resolution
- remove profile fallback as a normal code path

## 3. Compatibility is modeled as arbitrary node bindings

Target:

- compatibility is validated at the tenant root node
- compatibility never selects a node
- runtime compatibility: `agent`, optional `dataset`
- dataset compatibility: `dataset`, optional `pipeline`

Current implementation:

- compatibility is stored in `:config.binding/*`
- compatibility is checked by walking the selected node’s ancestry
- profile bindings and compatibility bindings share the same entity model

Relevant files:

- `server/src/digdir/config/schema.clj`
- `server/src/digdir/config/db.clj`
- `server/src/digdir/config/ops.clj`

Gap:

- introduce root-node compatibility entities
- stop using bindings for compatibility enforcement

## 4. API key auth is grant-based, not ceiling-based

Target:

- API keys carry 1..N ceiling nodes per root
- authorization is descendant-of-any-ceiling
- binding restrictions disappear from config auth

Current implementation:

- `:api-key/config-grants`
- grant matching can include node subtree plus binding type/value restrictions
- route-level auth calls `require-config-grant!`

Relevant files:

- `server/src/digdir/data/db.cljc`
- `server/src/digdir/config/api_keys.clj`
- `server/src/digdir/api/routes.clj`

Gap:

- replace config grants with explicit ceiling entities
- replace matching logic with descendant-of-any-ceiling using node slug inputs

## 5. Public API contract still relies on implicit defaults

Target:

- request must carry explicit root-specific selectors
- no implicit node selection from profiles, datasets, or agents

Current implementation:

- runtime resolution can still take `runtime-profile-id`
- runtime node is optional
- dataset selection defaults from API key grants when only one dataset is
  available
- agent selection defaults from API key grants when only one agent is available
- no generic explicit config resolve endpoint exists

Relevant file:

- `server/src/digdir/api/routes.clj`

Gap:

- add explicit-node resolve endpoints
- require request root-specific selectors
- remove profile arguments from runtime config resolution

Note:

- dataset or agent request defaults may still remain in the wider product if the
  team wants them, but they must no longer imply config-node selection

## 6. Fixed root chain above the tenant tree is not represented

Target:

- tenant tree resolves into a fixed system-managed chain above `default`

Current implementation:

- resolution stops once the tenant parent chain ends
- there is no system-managed node concept in live resolution

Relevant files:

- `server/src/digdir/config/db.clj`
- `server/src/digdir/config/schema.clj`

Gap:

- add system-managed nodes or an equivalent fixed-chain abstraction
- include fixed chain nodes in traces and value lookup

## 7. Bootstrap, setup, and migration are built around binding-driven selection

Target:

- bootstrap seeds canonical root nodes and values
- optional bindings may remain as admin metadata only
- compatibility is created on tenant root nodes

Current implementation:

- bootstrap functions create profile bindings on leaf nodes
- runtime and dataset bootstrap also attach compatibility through bindings
- setup derives dataset profile IDs and platform profile IDs
- migration exports/imports `config.binding` and `api-key.config-grant`

Relevant files:

- `server/src/digdir/config/ops.clj`
- `server/src/digdir/setup.clj`
- `server/src/digdir/migration/system.clj`

Gap:

- rewrite bootstrap to seed slugs, ceilings, and compatibility
- stop treating profile bindings as required runtime selectors
- update migration/export/import formats

## 8. Admin UI is binding-centric

Target:

- node tree UI should center on:
  - slug
  - ceiling reachability
  - root compatibility
  - explicit request resolution traces

Current implementation:

- diagnostics emphasize bindings per node
- binding preview UX assumes bindings materially affect resolution
- runtime trace UI still accepts `profile-id`

Relevant file:

- `server/src/digdir/config/ui.cljc`

Gap:

- reframe tree diagnostics around slug identity and compatibility metadata
- demote bindings to optional advisory metadata

## 9. Tests are aimed at the current semantics

Target:

- tests should prove explicit slug selection, ceiling authorization, root-node
  compatibility, and fixed-chain trace output

Current implementation:

- tests assert profile-driven selection
- tests assert binding-restricted API key grants
- tests assert runtime and dataset compatibility via node bindings

Relevant files:

- `server/test/digdir/config/accessor_test.clj`
- `server/test/digdir/config/db_test.clj`
- `server/test/digdir/config/api_keys_test.clj`
- `server/test/digdir/api/routes_test.clj`
- `server/test/digdir/config/ui_test.clj`

Gap:

- large test rewrite required

## Clean-Cut Implications

A one-go cutover is feasible because the storage layer is already node-based.
It is not feasible as a small compatibility patch because too many surfaces
assume the old semantics:

- accessors
- route auth
- API key admin payloads
- setup/bootstrap
- migration/export/import
- UI
- tests

The clean cut should therefore:

- keep nodes and values
- replace selection semantics
- replace auth semantics
- backfill stable slugs
- migrate compatibility and ceiling data
- retire runtime dependence on profile bindings

## Recommended Cut Boundary

The clean-cut branch should be considered complete only when all of the
following are true:

- every request-facing config resolver requires an explicit root-specific selector
- API keys use ceiling grants, not config grants
- compatibility is validated on tenant root nodes
- bindings no longer participate in runtime selection
- bootstrap/setup/migration/export/import use the new model
- admin UI exposes slugs and ceilings as first-class concepts
- old binding/profile-based resolver tests are removed or rewritten

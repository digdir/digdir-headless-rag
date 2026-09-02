# Import/Export Routine Plan

Status: Completed on 2026-04-13.

Follow-on:

- transform-retirement analysis and decision work completed in `plans/completed/import-export-transform-retirement-decision-plan.md`

## Progress Update

Completed so far:

- `server/src/digdir/import_export/model.clj` now owns canonical envelope/version helpers and system-envelope validation
- `server/src/digdir/import_export/registry.clj` now owns the shared dependency-ordered system entity registry used by both export and import
- `server/src/digdir/import_export/files.clj` now owns the JSON file edge wrappers
- `server/src/digdir/import_export/system.clj` now exists as the public entry point
- `server/src/digdir/import_export/export.clj` and `server/src/digdir/import_export/import.clj` now own the main coordination flow
- `bb migration-export` and `bb migration-import` now call `digdir.import-export.system`, while `bb migration-transform` now calls `digdir.import-export.transform`
- `server/src/digdir/import_export/transforms/system.clj` now owns the extracted `transform-export` pipeline
- `server/src/digdir/import_export/transforms/explicit_node_resolution.clj` now owns the cutover report logic
- `server/src/digdir/import_export/entities/config.clj` now owns config payload assembly and config import/export boundary logic
- `server/src/digdir/import_export/entities/users.clj`, `agents.clj`, `api_keys.clj`, `folders.clj`, and `conversations.clj` now own those entity-specific import/export paths
- steady-state `export-system` now emits canonical normalized system exports, while `import-system` requires canonical shape rather than silently transforming legacy payloads
- `server/src/digdir/import_export/entities/api_keys.clj` now exports API keys through the normalized public API-key view rather than raw entity pulls, so dataset scopes and agent refs survive roundtrip
- `server/src/digdir/import_export/files.clj` now preserves full namespaced keyword keys when writing JSON, so file-based export/transform/import keeps the same canonical shape as the in-memory path
- `export-system` now uses a dedicated canonical export path rather than calling the full `transform-export` migration pipeline
- `server/src/digdir/import_export/canonical/system.clj` now owns the live export canonicalization logic, rather than leaving that steady-state behavior inside the migration transform namespace
- `server/src/digdir/import_export/transforms/system.clj` has been reduced to explicit migration-only logic; the duplicated canonicalization and explicit-node-resolution helpers were removed in favor of the canonical module
- the remaining transform namespace wrapper around canonical export normalization has been removed; the explicit migration path now calls the canonical module directly
- explicit migration entry points now live in `server/src/digdir/import_export/transform.clj`, so `digdir.import-export.system` is back to steady-state export/import/report entry points only
- the old `server/src/digdir/migration/system.clj` compatibility wrapper has been deleted
- direct runtime callers and tests now target `digdir.import-export.system` rather than the legacy namespace
- the full `digdir.import-export.system-test` namespace now passes after the `system` versus `transform` public split: 16 tests, 150 assertions, 0 failures, 0 errors
- runbook dry-run testing exposed that `bb config-get` and `bb pipeline-config` had drifted to removed debug HTTP endpoints; they now use local config inspection helpers again, so readiness checks target the env-backed table directly rather than stale `/api/debug/*` routes
- the runbook readiness block has now been revalidated against the current live table using a real sample dataset/materialization combination: `digdir / altinn-docs-materialization / altinn-docs`
- fresh-table import investigation found no deterministic importer bug in the current artifact or code path; a prior `Connection reset` / `Broken pipe` failure reproduced as a transport-level Postgres/Datahike backend error, while clean reruns of config-only import, per-entity import, and full import all succeeded against new fresh tables
- end-to-end fresh-table smoke checks now pass on `rag_full_two_conn_retry_20260411T162900Z`: `/up`, retrieval, first-turn `/api/rag`, and follow-up `/api/rag` all succeeded against the imported table
- runbook smoke examples have been updated to the current API contract: canonical dataset refs, kebab-case request keys, `X-User-Id` for `/api/rag`, and `runtime-config-key`

Closed in this plan:

- the import/export refactor itself
- the system versus transform public split
- runbook execution against a real export/transform/import cycle
- fresh-table smoke validation on an imported table
- readiness and smoke-check doc drift discovered during that validation

Deferred to the follow-on decision plan:

- decide whether the current explicit transform module is still needed after the cutover window
- retire and delete the transform path once the retirement checklist is satisfied

## Assumption

There is no top-level `migration.clj` file in this repo today. I assumed the “organically messy migration namespace” primarily means `server/src/digdir/migration/system.clj`, because that is where the current full import/export logic lives. I also reviewed `server/src/digdir/auth/migration.clj`; that namespace is not a useful foundation for a new general import/export routine.

## Recommendation In One Sentence

If I were building this from scratch in stop-the-world mode, I would keep the current V2 config import/export engine concepts, delete unnecessary historical compatibility from live code, and make schema evolution happen through explicit export/transform/import flows rather than runtime compatibility layers.

## Stop-The-World Rule

This plan should optimize for a clean steady state, not for carrying old shapes indefinitely.

- the live codebase should target one canonical model
- old export shapes should be handled by explicit transforms during migration work, not by permanent compatibility branches in core runtime code
- once a cutover is complete, transitional code should be removed rather than preserved as infrastructure
- future schema changes should follow the same pattern: export current canonical data, transform it offline or in a dedicated migration tool, then import into the new canonical model

## Terminology

In the current deployment, `config` and `main` are logical import/export domains over the same underlying Datahike table, not separate physical databases.

That means:

- a "fresh target" means a fresh `ADH_POSTGRES_TABLE`
- "config" refers to config-domain entities such as definitions, nodes, datasets, node-values, and config-scoped users
- "main" refers to app-domain entities such as API keys, folders, and conversations
- the code may still keep separate config/main modules and import phases for clarity and ordering, even though both domains currently share one physical store

## Current Transform Status

The current explicit transform module is not an abstract compatibility framework waiting for hypothetical future use. It exists for a specific known migration family that is still represented in this repo and its cutover tooling, including:

- legacy pipeline-era runtime config paths
- legacy composite pipeline IDs
- tuple-scoped runtime values
- explicit-node-resolution export cleanup

That means:

- `digdir.import-export.system` is the steady-state export/import/report surface
- `digdir.import-export.transform` is the explicit cutover surface for this known historical migration family
- future schema changes should create their own explicit transform modules only when needed
- once the current migration window is closed and these transforms are no longer operationally required, they should be deleted rather than preserved as permanent infrastructure

### Transform Retirement Checklist

Because the current deployment only has a single live Datahike table, transform retirement should be decided based on retained artifacts and rollback needs, not on whether multiple environments still exist.

Retire the current explicit transform path only when all of these are true:

- exports produced from the current live table can be restored through `digdir.import-export.system` without any transform step
- the rollback and disaster-recovery plan uses canonical exports only
- no retained pre-canonical export artifacts are still supported operationally
- no operator workflow still requires `bb migration-transform`
- the support boundary for older export families has been documented explicitly

Recommended execution order:

1. prove current-table `export -> import` works with no transform step
2. inventory retained JSON exports and backups that predate canonical 2.0 shape
3. transform and re-archive any artifacts you still intend to support
4. declare older pre-canonical artifacts unsupported after a specific date
5. delete `digdir.import-export.transform`, the underlying transform namespaces, and `bb migration-transform`

This checklist remains valid, but the actual retirement decision and deletion work completed in `plans/completed/import-export-transform-retirement-decision-plan.md`.

## Findings

### 1. The repo already has two very different layers

- `server/src/digdir/config/ops/sync.clj` is the closest thing to a clean core import/export engine.
- `server/src/digdir/migration/system.clj` is a compatibility-heavy cutover layer that mixes:
  - full-system export
  - legacy-shape normalization
  - cross-database import orchestration
  - migration reporting
  - entity-specific import logic
- `server/src/digdir/auth/migration.clj` is an auth backfill helper, not an import/export subsystem.

### 2. `config/ops/sync.clj` contains the strongest reusable ideas

These parts are worth preserving, even if the namespace layout changes:

- a canonical export envelope with `:version`, `:scope`, `:exported-at`, and `:data`
- deterministic export of definitions, nodes, datasets, dataset-pipelines, node-values, and audit
- dry-run preview support
- explicit `:on-conflict` handling
- tenant pre-registration before importing tenant-scoped nodes
- parent-aware node import ordering
- encrypted value re-encryption during export/import
- small file wrappers around pure-ish data import/export functions

This file is still larger than I would want, but the structure is coherent and the tests around it are meaningful.

### 3. `migration/system.clj` is useful mainly as a source of extraction targets and test coverage

The current system migration namespace is doing too many jobs at once. Its size is a signal: about 1,939 LOC versus about 765 LOC for `config/ops/sync.clj`.

The high-value parts are:

- the idea of `export -> transform -> import`
- some normalization logic for legacy exports
- roundtrip coverage in `server/test/digdir/import_export/system_test.clj`

The low-value parts, for a fresh design, are:

- keeping export, transform, import, and reporting in one namespace
- embedding entity-specific import mechanics directly in one monolith
- keeping historical cutover logic resident in the steady-state import/export engine

My read is that this namespace should be broken apart. The reusable engine pieces should move into canonical import/export modules, the historical transform logic should become explicit migration transforms, and any leftover one-off cutover code should be deleted after use.

### 4. `auth/migration.clj` should not be reused as a base

I would not reuse `server/src/digdir/auth/migration.clj` for a new import/export routine.

Why:

- it is domain-specific to auth backfill
- it depends on env vars and setup-time behavior
- it prints directly to stdout instead of exposing structured progress/events
- it mixes reads, writes, and reporting in one imperative pass
- it has no general envelope, validation, preview, or versioning model

At most, its design pattern of idempotent migration steps is conceptually fine. The code itself is not a good substrate for general import/export.

## What I Would Reuse

### Reuse Directly

- `digdir.config.db/init-config-db!`
- `digdir.config.db/register-tenant!`
- `digdir.config.db/upsert-definitions-batch!`
- `digdir.config.db/get-definition`
- `digdir.config.db/get-config-node`
- `digdir.config.db/get-node-value`
- `digdir.config.db/make-node-value-id`
- `digdir.config.db/create-dataset!`
- `digdir.config.db/update-dataset!`
- `digdir.config.db/create-dataset-pipeline!`
- `digdir.config.db/update-dataset-pipeline!`
- `digdir.config.ops.bootstrap/ensure-config-node!`
- `digdir.config.ops.util/re-encrypt-config-value`

### Reuse With Refactoring

- the canonical config export/import shape from `digdir.config.ops.sync`
- the preview model from `digdir.config.ops.sync`
- the parent-ordering logic for node import
- the system-level roundtrip tests from `digdir.import-export.system-test`
- selected `transform-export` logic, but only after recasting it as explicit migration transforms rather than permanent live-code adapters

### Do Not Reuse As-Is

- `digdir.auth.migration/*`
- the monolithic structure of `digdir.migration.system`
- direct `println`-driven progress reporting for core import/export flows

## Fresh Design

### Core Rule

Separate these concerns strictly:

1. Canonical export/import engine
2. Explicit transform steps for migration jobs
3. Entity-specific codecs/importers
4. UI or CLI wrappers

### Proposed Module Layout

- `server/src/digdir/import_export/model.clj`
  - canonical envelope
  - version constants
  - schema/validation helpers
- `server/src/digdir/import_export/export.clj`
  - export coordinator
  - dry-run summary
- `server/src/digdir/import_export/import.clj`
  - import coordinator
  - preview and apply pipeline
- `server/src/digdir/import_export/transform.clj`
  - explicit transform runner for migration jobs
- `server/src/digdir/import_export/entities/config.clj`
- `server/src/digdir/import_export/entities/users.clj`
- `server/src/digdir/import_export/entities/agents.clj`
- `server/src/digdir/import_export/entities/api_keys.clj`
- `server/src/digdir/import_export/entities/folders.clj`
- `server/src/digdir/import_export/entities/conversations.clj`
- `server/src/digdir/import_export/transforms/`
  - one namespace per real migration transform, e.g. a dated or named cutover transform
- `server/src/digdir/import_export/files.clj`

### Coordinator Shape

I would define a registry of importable/exportable entity groups with:

- `:key`
- `:depends-on`
- `:export-fn`
- `:preview-fn`
- `:import-fn`

That gives three benefits:

- import order is explicit instead of buried in one coordinator
- preview and apply can share the same registry
- adding or deleting an entity group becomes local work

### Canonical Flow

#### Export

1. Read canonical entities from storage.
2. Normalize them into one canonical export shape only.
3. Sort deterministically.
4. Emit counts and optional report metadata.
5. Serialize to JSON only at the outer boundary.

#### Import Preview

1. Parse file/string input.
2. Validate envelope and version.
3. Require canonical import shape.
4. Run entity previews in dependency order.
5. Return a structured preview plan with creates, updates, skips, and errors.

#### Import Apply

1. Parse and validate exactly as preview does.
2. Require canonical import shape.
3. Initialize schema and seed prerequisites.
4. Import entity groups in dependency order.
5. Return structured results only; UI/CLI decides how to print them.

#### Transform

1. Read an exported artifact.
2. Apply one named transform pipeline to produce the next canonical target shape.
3. Emit a transformed artifact plus a migration report.
4. Import that transformed artifact with the normal canonical import path.

## Concrete Design Decisions

### 1. Make canonical shape the only write path

Old exports should never be imported directly into storage-specific code paths. They should first be transformed into the canonical target artifact, then imported through the normal canonical importer.

### 2. Keep dry-run first-class

The current preview support is worth keeping. I would extend it so preview and apply share the same entity registry and validation path.

### 3. Prefer structured events over `println`

Core import/export should return structured status and accept an optional progress callback or atom. Any printing belongs in setup/CLI/UI wrappers.

### 4. Keep file I/O at the edges

`import-data` / `export-data` style functions should remain the main contract. File-based helpers should stay thin wrappers.

### 5. Prefer transforms over compatibility layers

`migration/system.clj` currently accumulates a lot of historical knowledge. I would move only the still-needed pieces into explicit transform jobs and keep the canonical engine ignorant of old quirks. The transform job may know about legacy shape; the live importer should not.

### 6. Preserve idempotent upsert behavior

This is one of the better traits of the current code. Imports should remain safe to retry, with explicit conflict behavior.

### 7. Remove transitional code after cutover

Because we are in stop-the-world mode, the default should be deletion, not deprecation. If a transform exists only to get one historical export family into the new shape, it should live in migration tooling and be removed when that migration window closes.

## Implementation Plan

## Phase 0: Freeze The Boundary

Objective:

- decide the canonical export envelope and supported scopes
- decide whether the new routine is config-only, full-system, or both

Deliverables:

- namespace layout decision
- canonical entity registry
- one accepted export JSON shape

Acceptance criteria:

- one canonical in-memory representation
- one explicit transform entry point for migration jobs
- no assumption that the live importer accepts historical shapes directly

## Phase 1: Extract A Canonical Engine

Objective:

- carve the reusable engine out of `digdir.config.ops.sync`

Work:

- move envelope/version helpers into a dedicated module
- move preview/apply coordination into separate import/export modules
- keep current config tests passing with minimal behavior change

Acceptance criteria:

- existing config roundtrip tests still pass
- old callers can still use thin wrappers

Progress:

- completed in part
- envelope/version helpers and canonical system-envelope validation now live in `digdir.import-export.model`
- public entry points have been extracted from `digdir.migration.system`
- `digdir.import-export.system` now exists as a thin facade
- orchestration has been split into `digdir.import-export.export` and `digdir.import-export.import`
- config payload assembly now lives in `digdir.import-export.entities.config`

## Phase 2: Convert System Migration Into Entity Modules Plus Explicit Transforms

Objective:

- stop using `digdir.migration.system` as the main engine

Work:

- extract users/agents/api-keys/folders/conversations into separate entity modules
- extract only still-needed transform logic into explicit migration transform namespaces
- reduce `transform-export` to a migration-tool concern, not a steady-state importer concern

Acceptance criteria:

- system roundtrip tests still pass
- the system coordinator becomes small and mostly declarative
- historical transforms are callable explicitly as `export -> transform -> import`, not hidden inside canonical import

Progress:

- completed in part
- explicit transform logic has been extracted into dedicated transform namespaces
- config, users, agents, api keys, folders, and conversations now live in dedicated entity namespaces
- the system import/export coordinators now share an explicit dependency-ordered entity registry
- canonical file I/O and envelope helpers have been split out of the coordinators
- the steady-state importer now requires canonical shape and rejects legacy payloads unless they are transformed explicitly first
- the exporter no longer depends on the full `transform-export` pipeline
- canonical export normalization now lives in its own steady-state module
- the remaining gap is cleanup around any migration-only code that is no longer needed after the current cutover

## Phase 3: Unify Preview, Apply, And Reporting

Objective:

- make preview and import share the same dependency graph and result format

Work:

- create shared result shapes for preview/apply
- move report-building to a separate reporter module
- make UI and setup code consume the new structured result instead of bespoke assumptions

Acceptance criteria:

- one preview contract across config and system imports
- no core import namespace depends on `println`

Progress:

- started
- `digdir.import-export.report` now centralizes export counting and shared system import result metadata
- `digdir.import-export.system/preview-import-system` now exists and reuses the extracted entity/config boundaries
- the system import/export paths now share an explicit dependency-ordered registry
- system import results now use a structured `:entities` map as the canonical contract
- canonical file output now preserves key namespaces, so the file-based migration path exercises the same canonical shape as in-memory export/import
- remaining: keep deleting migration-only code once the active cutover no longer needs it, broaden coverage around the canonical `:entities` contract, and continue moving docs/tooling to the split `system` vs `transform` public surface

## Phase 4: Delete Legacy Paths After Cutover

Objective:

- finish with a clean steady state

Work:

- remove direct use of `digdir.migration.system` internals
- delete dead compatibility branches from canonical import/export code
- keep only the explicit transform tooling that is still operationally required
- remove transform tooling too once the migration window closes, if it is genuinely one-off

Acceptance criteria:

- canonical engine has no legacy-shape branches
- old-shape handling does not live in the canonical engine
- any remaining transform code is explicit, isolated, and justified by an active migration need

Progress:

- completed in part
- the old namespace wrapper file has been deleted
- active code now routes through `digdir.import-export.*` only

## Test Plan

- preserve current config roundtrip tests
- preserve current system roundtrip tests
- add snapshot-style tests for canonical export envelopes
- add per-entity preview/import tests
- add transform tests for each historical export shape we intentionally migrate
- add overwrite/skip conflict tests for every entity module
- add corrupted-input tests for envelope validation and dependency failures
- add at least one end-to-end `export -> transform -> import` test for each supported migration flow

## Bottom Line

I would build the new routine around the current `digdir.config.ops.sync` ideas and DB helper APIs, not around `digdir.auth.migration.clj`, and not around the monolithic structure of `digdir.migration.system`.

The short version:

- reuse the V2 config import/export engine concepts
- reuse the DB upsert/bootstrap primitives
- reuse the tests
- convert historical normalization into explicit transform steps, not permanent compatibility layers
- do not reuse `auth/migration.clj` except as a reminder that idempotent steps are good

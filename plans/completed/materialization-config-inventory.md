# Materialization Config Inventory

Date: 2026-03-29

## Purpose

This document is the concrete output for Workstream B / Phase B1 in
[deployment-polish-and-materialization-migration-plan.md](/Users/bdbrodie/dev/digdir/rag/plans/completed/deployment-polish-and-materialization-migration-plan.md).

It inventories the remaining hard-coded materialization behavior and classifies each item as:

- already represented in Dataset-root config
- missing Dataset-root definition or contract
- derived behavior that should probably remain code-derived
- topology assumptions that need refactoring for the new shared-dataset model

## Current Status

As of this inventory:

- the target tenants `digdir` and `public-sector-knowledge` have been bootstrapped live in dev
- `digdir/public-docs` now exists as one dataset with two first-class pipelines:
  - `altinn-docs`
  - `digdir-docs`
- those pipelines now resolve through distinct per-pipeline materialization leaves under one shared dataset base:
  - `dataset/digdir/public-docs/altinn-docs/materialization`
  - `dataset/digdir/public-docs/digdir-docs/materialization`
- `public-sector-knowledge/kudos` preserves the existing `KUDOS_preprod_v4_*` storage values

This means the remaining materialization migration must support:

- one dataset fed by multiple pipelines
- one shared dataset base with per-pipeline materialization leaves where source-specific values differ
- pipeline-specific durable records for names and source identity
- explicit Dataset-root contracts for the active target datasets without any executor-side fallback path

## Files Reviewed

- [pipeline/core.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/pipeline/core.clj)
- [pipeline/collections.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/pipeline/collections.clj)
- [pipeline/executor.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/pipeline/executor.clj)
- [setup.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/setup.clj)
- loader wrappers under [pipeline/loaders](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/pipeline/loaders)

## Findings

### 1. Executor no longer owns the contract alone, and the live target execution path is now strict

The canonical default contract now lives in
[materialization.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/pipeline/materialization.clj),
and [executor.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/pipeline/executor.clj#L97)
uses that mapping rather than owning separate ad hoc defaults.

In addition, [ops.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/ops.clj)
now has `seed-pipeline-materialization-defaults!` and
`seed-target-materialization-defaults!`, which write the missing defaults onto
real Dataset-root nodes during target-topology bootstrap.

As of the latest cutover slice, the active deployment targets no longer rely on
that fallback-derived seeding path for bootstrap correctness. The bootstrap path
now merges an explicit materialization contract for:

- `digdir/public-docs`
- `public-sector-knowledge/kudos`

That means `seed-target-materialization-defaults!` now acts mainly as a
verification/no-op pass for those targets immediately after bootstrap.

As of the latest clean-cut retirement slice:

- target bootstrap/seeding uses explicit target-contract helpers only
- execution no longer applies implicit fallback values for any pipeline
- non-target sparse configs now surface missing values as `nil` instead of
  being silently backfilled by retired default maps

Execution for the active deployment tenants `digdir` and
`public-sector-knowledge` now fails closed if any of these values are absent
from Dataset-root config. The explicit target contract is:

- Kudos source:
  - `:kudos/use-preprod?` -> `false`
  - `:kudos/starting-page` -> `1`
  - `:documents/limit` -> `20000`
  - `:documents/offset` -> `0`
- Website source:
  - `:base-url` -> `"http://localhost:1313"`
  - `:urls/limit` -> `30000`
  - `:urls/offset` -> `0`
- Folder source:
  - `:files/limit` -> `300000`
  - `:files/offset` -> `0`
- Episerver source:
  - `:language` -> `"no"`
  - `:pages/include-page-types` -> `[]`
  - `:pages/limit` -> `100000`
  - `:pages/offset` -> `0`
- Shared chunking:
  - `:chunks/strategy` -> `:header-based`
  - `:chunks/minimum-length` -> `333`
  - `:chunks/maximum-length` -> `256000`
  - `:chunks/hash-changer` -> `1`
- Shared search phrase generation:
  - `:search-phrases/model` -> `"gpt-4o"`
  - `:search-phrases/fallback-model` -> `:google/gemma-3-27b-it`
  - `:search-phrases/prompt` -> `"Generate search phrases for: REPLACE_ME"`
  - `:search-phrases/hash-changer` -> `1`
- Shared storage/execution:
  - `:store/coll-prefix` -> target-specific explicit values
  - `:parallelism/documents` -> `3`
  - `:parallelism/store` -> `1`
  - `:fault-tolerance/max-document-failures` -> `10`

Target-specific live values now verified in dev:

- `digdir/public-docs`
  - `:store/coll-prefix` -> `"website_"`
- `public-sector-knowledge/kudos`
  - `:store/coll-prefix` -> `"KUDOS_preprod_v4_"`

Classification:

- now seeded into Dataset-root config for deployment targets:
  - all source limits/offsets
  - website base URL
  - episerver language/include-page-types
  - chunking defaults
  - search phrase defaults
  - storage prefix
  - parallelism and failure tolerance
- probably remain code-derived:
  - dispatch on `:source-type`
- no longer allowed to stay implicit at execution time for deployment targets:
  - all values listed above when the tenant is `digdir` or `public-sector-knowledge`

Live verification:

- `bb bootstrap-deployment-target-topology`
- `bb seed-target-materialization-defaults dev`
- `clj -M:test -n digdir.pipeline.materialization-test`

Both commands now complete successfully, and the reseed task currently reports
empty `:seeded-paths` / `:actions` for `digdir/public-docs` and
`public-sector-knowledge/kudos`. That means the explicit Dataset-root contract
for the current deployment targets is already present in dev.

Final live verification also completed successfully:

- `digdir/dev/altinn-docs` executed cleanly with `:source-type :website`
- `digdir/dev/digdir-docs` executed cleanly with `:source-type :website`
- `public-sector-knowledge/dev/kudos` executed cleanly with `:source-type :kudos`

The focused materialization tests confirm that execution now rejects missing
seeded values for the active tenants and that sparse non-target configs no
longer receive implicit loader defaults.

### 2. Collection naming still mixes derived logic and config fallback

[collections.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/pipeline/collections.clj#L43) still owns:

- implicit prefix fallback:
  - explicit `:collection-prefix`
  - else sanitized `:pipeline-name`
  - else `"pipeline_"`
- config-hash subset used for collection versioning
- automatic generation and persistence of missing collection names

Classification:

- should stay code-derived:
  - hash computation itself
  - transforming a prefix plus hash into `documents/chunks/phrases` collection names
- should be explicit config inputs:
  - `pipeline.storage.collection-prefix`
  - hash-affecting source/chunk/phrase settings
- code status now:
  - generated collection names write back to the dataset base binding rather than a pipeline-specific leaf
  - all compatible pipelines now inherit the same stored collection names through the shared dataset base
  - the remaining verification task is the live dev DB rewrite from the older shared-leaf topology to the new shared-base-plus-per-pipeline-leaf topology

### 3. Pipeline CRUD still encoded the old one-pipeline-one-dataset shape

[pipeline/core.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/pipeline/core.clj#L166) still assumes:

- a dataset record with `dataset-id == pipeline-name`
- a pipeline record with `dataset-id == pipeline-name`
- dataset base nodes under `dataset/<tenant>/<pipeline>/default`
- materialization leaves under `dataset/<tenant>/<env>/<pipeline>/materialization`
- `:dataset`, `:pipeline`, and `:dataset-profile` bindings all anchored to that per-pipeline tree

This was structurally wrong for:

- `digdir/public-docs`
- any future dataset fed by multiple pipelines

Classification:

- not just a default migration issue
- requires refactoring the CRUD/bootstrap path so:
  - dataset identity is independent
  - multiple pipelines can bind into one dataset tree
  - shared dataset leaves can coexist with pipeline-specific records

Code status now:

- CRUD/bootstrap accepts explicit `dataset-id`
- target bootstrap now creates per-pipeline leaves under one shared dataset base
- `bootstrap-dataset-tree!` now binds pipeline compatibility on the leaf instead of the base node

### 4. Setup already seeds most of the needed definitions

[setup.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/setup.clj#L625) already defines most of the relevant Dataset-root paths:

- `pipeline.ui.*`
- `pipeline.source.*`
- `pipeline.documents.*`
- `pipeline.chunks.*`
- `pipeline.search-phrases.*`
- `pipeline.storage.*`
- `pipeline.operations.*`

This is good news: the migration is more about using those definitions consistently than inventing a large new schema.

Potential gaps or mismatches to verify:

- executor still consumes flat property aliases like `:website-base-url` and `:chunk-strategy`
- these need a clean single mapping path from Dataset-root definitions to loader config
- loader docs mention `:episerver/use-preprod?`, but setup does not currently seed a matching Dataset-root path

### 5. Loader wrappers are thin and mostly not the problem

The namespaces under [pipeline/loaders](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/pipeline/loaders) mostly:

- receive loader-formatted config
- delegate to existing loader implementations
- contain only example/comment defaults

Classification:

- low migration risk
- keep them thin
- migrate config contract at the executor/config boundary, not inside each wrapper

## Recommended Target Contract

### Should Be Explicit Dataset-Root Config

- source identity and source-specific parameters
- document limits and offsets
- chunking strategy and chunk size bounds
- search phrase model, fallback model, and prompt
- storage collection prefix
- parallelism settings
- max document failures

### Should Remain Derived

- collection hash calculation
- final collection-name formatting from prefix + hash
- loader dispatch by source type
- execution bookkeeping counters and timestamps

### Needs Architectural Refactor

- any code path that assumes `dataset-id == pipeline-id`
- any code path that generates dataset node IDs from `pipeline-name`
- any code path that writes collection names back through a per-pipeline dataset tree

## Concrete Migration Backlog

### B2 Design Tasks

1. Define the canonical Dataset-root-to-loader mapping layer.
   One place should translate `pipeline.source.*`, `pipeline.documents.*`, `pipeline.chunks.*`, `pipeline.search-phrases.*`, `pipeline.storage.*`, and `pipeline.operations.*` into loader keys.

2. Lock the explicit execution contract to the Dataset-root materialization model.
   Current state:
   - deployment targets are now seeded with explicit Dataset-root contracts
   - executor-side fallback defaults have been removed from live execution
   Next step:
   - keep the target contract authoritative during subsequent cleanup
   - avoid reintroducing implicit defaults in bootstrap or loader paths

3. Define the shared-dataset write-back model for collection names.
   Recommendation:
   - collection names should live on the selected dataset materialization leaf
   - pipeline-specific display/name/source identity should stay on first-class `dataset.pipeline` records or pipeline-specific bindings

### B3 Implementation Tasks

1. Refactor [pipeline/core.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/pipeline/core.clj) so CRUD/bootstrap accepts explicit `dataset-id` instead of forcing `pipeline-name`.
2. Refactor [pipeline/collections.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/pipeline/collections.clj) to stop assuming per-pipeline dataset node paths.
3. Extract a dedicated `dataset-config->loader-config` conversion layer from [executor.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/pipeline/executor.clj).
4. Seed explicit Dataset-root values for the target tenants:
   - `digdir/public-docs`
   - `public-sector-knowledge/kudos`
5. Keep executor hard-coded defaults retired from the live execution path.

## Immediate Next Step

Current implementation progress:

- the canonical Dataset-root to loader-config mapping now exists in [executor.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/pipeline/executor.clj)
- pipeline CRUD/bootstrap now accepts explicit `dataset-id` in [core.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/pipeline/core.clj)
- collection write-back now targets the resolved dataset leaf directly in [collections.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/pipeline/collections.clj)

Next:

- finish the remaining collection-tracking and setup/bootstrap cleanup around shared dataset identity
- run end-to-end materialization checks for `digdir/public-docs` and `public-sector-knowledge/kudos`
- keep the explicit Dataset-root contract authoritative while closing the remaining Workstream B items

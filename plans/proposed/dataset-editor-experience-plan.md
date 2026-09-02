# Dataset Editor Experience Plan

Status: Proposed on 2026-04-13.

Priority note:

- sequencing changed on 2026-04-13
- `plans/in-progress/pipeline-record-vs-config-tree-refactor-proposal.md` is now the prerequisite refactor
- this Dataset editor plan should proceed after Stage 1 of that refactor lands

Implementation note added on 2026-04-13:

- the first Diagnostics deep-link attempt exposed a practical constraint in the current admin dev build:
  adding more startup/query-param logic directly to `server/src/digdir/config/ui.cljc` can push the Shadow/Jackson analyzer cache over its default string-size limit
- because of that, dataset-scoped hierarchical-editor entry should not be implemented by continuing to grow the generic `ConfigTreeDiagnostics` startup path
- instead, dataset-scoped editor behavior should be introduced through a smaller wrapper namespace/route that computes initial dataset context outside the giant generic diagnostics namespace

## Goal

Consolidate the current dataset-related operator work into one coherent Dataset editor experience.

The target experience should let an operator:

- understand what a dataset is and what it powers
- edit dataset metadata and lifecycle state
- edit dataset-level runtime configuration through a clear entry point
- manage child materialization pipelines in context without confusing them with the dataset itself
- move between Operator Console, Diagnostics/config editing, and runtime-facing surfaces without vocabulary drift

## Source Artifacts This Plan Consolidates

- `plans/completed/operator-console-public-api-implementation-plan.md`
- `plans/completed/operator-console-public-api-clarity-plan.md`
- `plans/partially-completed/dataset-level-runtime-ref-full-fix-plan.md`
- `plans/partially-completed/api-contract-cleanup-plan.md`
- `plans/completed/config-tree-schema-implementation-plan.md`
- `server/docs/DATASETS.md`

## Current State

The product already has important pieces of the target experience, but they are split across separate mental models.

### 1. Operator Console dataset shell exists

The current Operator Console already provides:

- dataset list and dataset detail views
- dataset creation
- nested child pipeline management
- per-pipeline execution and status visibility

Primary file:

- `server/src/digdir/pipeline/ui/pipelines.cljc`

### 2. Dataset runtime editing exists, but not as a coherent dataset editor

Dataset-rooted definitions were removed from the legacy tuple-scoped config editor and redirected to the Diagnostics tree editor.

That was the correct architecture cleanup, but from an operator perspective it means “editing a dataset” is still split between:

- dataset metadata in the Operator Console
- dataset runtime config in the Diagnostics/config UI
- pipeline/materialization editing back in the Operator Console

Primary files:

- `server/src/digdir/config/ui.cljc`
- `server/src/digdir/config/ui/common.cljc`
- `server/src/digdir/config/ui/inheritance.cljc`

### 3. Runtime-facing surfaces are moving to dataset-first terminology

Playground, API-key UI, and dataset docs now mostly use `tenant` + `dataset-config-key`, but there is still cleanup work to keep pipeline/materialization concepts from leaking into runtime-facing labels and flows.

Primary files:

- `server/src/digdir/playground/ui.cljc`
- `server/src/digdir/playground/ui/common.cljc`
- `server/src/digdir/playground/ui/observability.cljc`
- `server/src/digdir/config/ui/api_keys.cljc`

### 4. Dataset metadata lifecycle is still incomplete as an editor experience

The intended console contract includes dataset parent-resource lifecycle operations, but the live route file currently exposes only:

- `GET /console-api/datasets/:dataset-id`

The current Operator Console UI also exposes dataset creation, but not an obvious dataset metadata edit/delete flow in the dataset detail screen.

Primary files:

- `server/src/digdir/api/routes/endpoints.clj`
- `server/src/digdir/api/routes/datasets.clj`
- `server/src/digdir/pipeline/ui/pipelines.cljc`

## Product Definition

The Dataset editor should be treated as one product slice with three clearly separated sections.

### Section A: Dataset Overview

Owns:

- display name
- description
- stable dataset identity display
- enabled/disabled or lifecycle state
- top-level usage and health summary

This is the “what is this dataset?” layer.

### Section B: Dataset Runtime Configuration

Owns:

- dataset-rooted retrieval/runtime settings
- dataset-level policy/config surfaces that affect runtime behavior
- clear affordances to inspect effective dataset config
- links to the underlying diagnostics/config tree only when needed

This is the “how does this dataset behave at runtime?” layer.

### Section C: Materialization Pipelines

Owns:

- child pipeline list
- pipeline creation and editing
- source/import settings
- execution controls
- recent execution history

This is the “how is this dataset produced?” layer.

The core rule is:

- the dataset is the primary object
- pipelines are child implementation details behind the dataset
- runtime config belongs to the dataset, not to a hidden chosen child pipeline

## Design Principles

### 1. Dataset-first navigation

An operator should always land on a dataset page first, then choose between overview, runtime config, and pipelines.

### 2. No ambiguity between runtime and materialization

The UI must not imply that editing a pipeline is the same as editing the dataset selected by Playground or public APIs.

### 3. One obvious metadata editor

Dataset name/description/state should be editable directly from the dataset detail experience rather than requiring a separate mental model or code-only route.

### 4. One obvious runtime-config entry point

If the actual config editor remains the Diagnostics tree editor, the dataset page must still provide a first-class entry point into the correct dataset root and explain why the operator is leaving the overview screen.

### 5. Runtime surfaces stay dataset-only

Playground, API keys, and public docs should present datasets as the selected runtime object and treat child pipelines as operator-side implementation detail.

## Implementation Plan

## Phase 0: Freeze the Dataset Editor Boundary

Objective:

- define exactly what “Dataset editor” includes and what remains a linked-but-separate expert tool

Decisions to freeze:

1. Which dataset metadata fields are editable in the main dataset page
2. Whether dataset enable/disable and delete belong in the first release of the editor
3. Which dataset-rooted runtime settings should be surfaced directly in the dataset page versus linked out to Diagnostics
4. Which pipeline metrics belong in the default overview summary

Acceptance criteria:

- one approved scope statement exists for metadata, runtime config, and pipelines
- no dataset-level field remains ownerless between Operator Console and Diagnostics UI

Current decision:

- Phase 1 should be implemented before any more Diagnostics deep-linking work
- Phase 2 should start with a dataset-page runtime summary plus a stable handoff action, not with query-param-driven preselection inside `ConfigTreeDiagnostics`

## Phase 1: Complete Dataset Metadata Lifecycle

Objective:

- make dataset metadata editing a first-class Operator Console flow

Primary files:

- `server/src/digdir/api/routes/endpoints.clj`
- `server/src/digdir/api/routes/datasets.clj`
- `server/src/digdir/pipeline/ui/pipelines.cljc`
- `server/src/digdir/pipeline/core.clj`
- `server/src/digdir/config/db.clj`

Concrete changes:

1. Add the missing console dataset lifecycle endpoints needed for the editor contract:
   - `PUT /console-api/datasets/:dataset-id`
   - `DELETE /console-api/datasets/:dataset-id` if deletion is in scope
2. Add dataset edit affordances to the dataset detail view.
3. Support editing at least:
   - dataset name
   - dataset description
   - enabled/disabled state if supported by the data model
4. Add clear success/error feedback and dirty-state handling.
5. Define deletion safeguards if delete is allowed:
   - pipeline dependency messaging
   - confirmation UX
   - clear behavior for soft-delete vs hard-delete

Acceptance criteria:

- an operator can edit dataset metadata from the dataset detail screen
- the dataset detail screen no longer requires code-level knowledge or hidden routes for normal dataset lifecycle work
- route tests cover the final console dataset lifecycle contract

## Phase 2: Add a Real Dataset Runtime Config Entry Point

Objective:

- make dataset runtime configuration discoverable and legible from the dataset page

Primary files:

- `server/src/digdir/pipeline/ui/pipelines.cljc`
- `server/src/digdir/config/ui.cljc`
- `server/src/digdir/config/ui/common.cljc`
- `server/src/digdir/config/ui/inheritance.cljc`

Concrete changes:

1. Add a “Runtime Config” section or tab on the dataset detail page.
2. Show a compact dataset runtime summary on the dataset page:
   - selected dataset config key
   - key retrieval/runtime bindings that define whether the dataset is usable
   - missing/invalid required config indicators
3. Add a first-class action that opens the Diagnostics/config editor scoped to the current dataset root.
4. If feasible, inline a small curated subset of high-value dataset settings directly in the dataset page instead of forcing all edits through the generic tree editor.
5. Explain the boundary clearly:
   - dataset runtime config belongs to the dataset
   - pipeline source/materialization config belongs to child pipelines

Implementation constraint:

- do not keep adding dataset-specific startup/query-param behavior directly to `server/src/digdir/config/ui.cljc`
- prefer one of:
  - a small dataset-scoped wrapper route/component that prepares initial editor context before entering the generic tree UI
  - a dataset-page inline summary/editor card backed by focused server helpers

Implementation progress on 2026-04-13:

- dataset detail now exposes a `Runtime Configuration` callout with a stable `Open Diagnostics` action
- pipeline rows and the pipeline edit form now hand off into Diagnostics via in-memory navigation context instead of query-param boot logic
- `ConfigTreeDiagnostics` now consumes that handoff by auto-loading the target tenant/root and applying a node filter, which makes the hierarchical editor usable for pipeline materialization editing without growing a product-specific route wrapper yet

Acceptance criteria:

- an operator can discover where dataset runtime config lives without prior architecture knowledge
- the dataset page makes dataset readiness legible before the operator drops into the generic config tree
- the editor no longer hides dataset runtime editing behind unrelated terminology

## Phase 3: Keep Pipeline Management In Context, But Secondary

Objective:

- keep pipeline/materialization tools accessible without letting them dominate the dataset editor mental model

Primary files:

- `server/src/digdir/pipeline/ui/pipelines.cljc`
- `server/src/digdir/pipeline/ui/executions.cljc`

Concrete changes:

1. Keep child pipelines as a dedicated section within the dataset page.
2. Preserve pipeline create/edit/execute flows, but frame them as “Pipelines feeding this dataset.”
3. Ensure overview copy and headings do not imply that selecting one pipeline changes the dataset selected by runtime surfaces.
4. Keep execution history reachable from the dataset page, with deeper details still available in dedicated execution views.

Acceptance criteria:

- operators can manage ingestion without confusing pipeline identity with dataset identity
- the dataset page remains dataset-first even when pipeline-heavy datasets have many child pipelines

## Phase 4: Cross-Surface Naming and Navigation Cleanup

Objective:

- make the Dataset editor consistent with Playground, API-key UI, and docs

Primary files:

- `server/src/digdir/playground/ui.cljc`
- `server/src/digdir/playground/ui/common.cljc`
- `server/src/digdir/playground/ui/observability.cljc`
- `server/src/digdir/config/ui/api_keys.cljc`
- `server/docs/DATASETS.md`
- relevant API docs/OpenAPI files

Concrete changes:

1. Ensure runtime surfaces describe dataset scope with `tenant` + `dataset-config-key`.
2. Remove copy that implies Playground users are choosing a materialization pipeline.
3. Keep API-key dataset grant UI aligned with `dataset-scopes`.
4. Align dataset-page labels with the same vocabulary used in docs and external APIs.
5. Make links between Operator Console and runtime-facing concepts explicit:
   - “This dataset is what agents and API keys access”
   - “These pipelines produce that dataset”

Acceptance criteria:

- the same dataset mental model appears in Operator Console, Playground, API-key UI, and docs
- pipeline terminology remains available only where it is operationally necessary

## Phase 5: Validation and Documentation

Objective:

- lock the Dataset editor experience into tests, docs, and operator expectations

Primary files:

- `server/test/digdir/api/routes_test.clj`
- `server/test/digdir/playground/ui_test.clj`
- `server/test/digdir/config/ui_test.clj`
- `server/docs/DATASETS.md`
- operator-console/API docs affected by the final contract

Concrete changes:

1. Add route tests for dataset edit/delete if implemented.
2. Add UI tests or equivalent verification for:
   - dataset metadata editing
   - runtime-config entry point visibility
   - dataset-first copy in the detail view
3. Update docs to explain the three-layer model:
   - dataset overview
   - dataset runtime config
   - child pipelines/materialization
4. Add one operator-focused walkthrough for the common tasks:
   - create dataset
   - edit metadata
   - open runtime config
   - add/update pipeline
   - execute and inspect status

Acceptance criteria:

- docs and tests describe one coherent Dataset editor model
- no core operator task requires guessing which UI owns dataset metadata versus runtime config versus pipeline config

## Recommended Delivery Order

1. Phase 0 boundary decision
2. Phase 1 metadata lifecycle completion
3. Phase 2 runtime-config entry point
4. Phase 3 in-context pipeline refinement
5. Phase 4 naming/navigation cleanup
6. Phase 5 tests and docs

This order creates value quickly:

- first make the dataset page a true editor
- then make runtime config discoverable
- then polish the surrounding mental model

## Success Criteria

This plan is done only when all of the following are true:

- the Operator Console dataset detail page is the obvious starting point for editing a dataset
- dataset metadata can be edited directly from that page
- dataset runtime config has a first-class, dataset-scoped entry point
- child pipelines remain manageable from the same dataset context without becoming the primary runtime object
- Playground, API-key UI, docs, and console copy all reinforce the same dataset-first model
- operators no longer need to infer architecture boundaries just to know where to edit a dataset

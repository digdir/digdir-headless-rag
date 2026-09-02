# Deployment Polish And Materialization Migration Plan

## Purpose

This plan defines the final polish work needed before deployment after the V2 config-tree rollout is complete.

It has two main workstreams:

- normalize the live tenant, dataset, pipeline, and agent landscape into a smaller and clearer target model
- migrate remaining hard-coded pipeline materialization behavior into the configuration database

---

## Current Status

As of 2026-03-29:

- the V2 config-tree implementation is complete
- live config is V2-only for Platform, Runtime, and Dataset roots
- export/import and admin editing are already on the new model
- the deployment-focused cleanup and hardening work is complete
- the initial live audit is captured in `plans/in-progress/deployment-polish-audit.md`
- the recovered public-docs lineage is now treated as `altinn + assistant` feeding the future `digdir/public-docs` target
- an idempotent target-topology bootstrap helper now exists in `server/src/digdir/config/ops.clj` to create the target tenants, datasets, pipelines, shared dataset trees, and placeholder agents from the audited source lineage
- the target-topology bootstrap has now been executed live in dev with rollback export `server/state/migrations/deployment-polish-pre-bootstrap-1774785950611.json`
- the hard-coded materialization inventory is now captured in `plans/in-progress/materialization-config-inventory.md`
- the first B2/B3 refactor slice is now in code:
  - executor has a canonical Dataset-root to loader-config mapping layer
  - pipeline CRUD/bootstrap can target explicit dataset IDs instead of forcing `dataset-id == pipeline-name`
  - collection tracking writes back to the resolved dataset leaf rather than rebinding via per-pipeline bootstrap shortcuts
- a first B4 seeding slice is now in code:
  - `config.ops/seed-pipeline-materialization-defaults!` writes missing Dataset-root materialization defaults onto selected dataset leaves
  - `bootstrap-deployment-target-topology!` now seeds those defaults for `digdir/public-docs` and `public-sector-knowledge/kudos`
  - shared-dataset leaves are seeded once and reused by all compatible pipelines
  - live verification now exists via `bb bootstrap-deployment-target-topology` and `bb seed-target-materialization-defaults dev`
  - the current dev target tenants report empty reseed actions, which means the explicit Dataset-root defaults are already present on the target leaves
- the next executor cutover slice is now in code:
  - execution for `digdir` and `public-sector-knowledge` now requires explicit Dataset-root materialization values instead of silently falling back to hard-coded defaults
  - focused pipeline materialization tests now cover strict target-tenant execution and the absence of implicit fallback values on sparse non-target configs
- the next bootstrap cutover slice is now in code:
  - target-topology bootstrap now merges an explicit materialization contract for `digdir/public-docs` and `public-sector-knowledge/kudos` before writing Dataset-root values
  - `seed-target-materialization-defaults!` is now effectively a verification/no-op pass for those targets immediately after bootstrap
  - target-topology proof tests now assert empty reseed actions because the full required contract is written during bootstrap itself
- the final fallback-retirement slice is now in code:
  - executor-side materialization fallback has been removed from live execution
  - target bootstrap/seeding now requires an explicit materialization contract and fails closed when one is missing
  - non-target pipelines no longer receive implicit loader defaults from the retired fallback maps
  - the only remaining flexibility is code-derived behavior that is intentionally still derived, such as dispatch and collection-name generation
- the final shared-dataset topology slice is now in code:
  - `public-docs` now uses one shared dataset base with per-pipeline materialization leaves rather than one shared leaf for all pipelines
  - dataset-wide collection names now write back to the dataset base so all compatible pipelines inherit the same storage target
  - target bootstrap can now recover from the current target tenants themselves after source-tenant retirement, not only from the original `altinn` / `ka` lineage
- the source-tenant retirement path is now implemented in dry-run/apply form:
  - `config.ops/preview-tenant-retirement!` summarizes tenant-local roots, bindings, datasets, pipelines, and conversation counts
  - `config.ops/retire-source-tenants!` is dry-run by default and requires an explicit conversation strategy for apply mode
  - `bb preview-target-tenant-retirement dev` and `bb retire-source-tenants dev delete --apply` are now the operational retirement path
- live retirement has now been executed with conversation deletion:
  - `altinn`, `altinn-docs`, and `ka` conversations were deleted as requested
  - only `digdir` and `public-sector-knowledge` remain in `:all-tenants`
  - the preview now reports zero remaining Platform, Runtime, and Dataset roots for the retired tenants
  - rollback/export checkpoint from the destructive slice exists at `server/state/migrations/source-tenant-retirement-mid-delete-20260329-154349.json`
- final live topology verification is now complete:
  - `digdir/dev/altinn-docs` resolves through `dataset/digdir/public-docs/altinn-docs/materialization`
  - `digdir/dev/digdir-docs` resolves through `dataset/digdir/public-docs/digdir-docs/materialization`
  - both inherit the shared `public-docs` collection names from the dataset base
  - `public-sector-knowledge/dev/kudos` resolves through its own `kudos` materialization leaf and preserves the `KUDOS_preprod_v4_*` storage naming
  - live `collection-prefix` values now match the explicit target contracts:
    - `altinn-docs` -> `website_`
    - `digdir-docs` -> `website_`
    - `kudos` -> `KUDOS_preprod_v4_`
- final end-to-end materialization verification is now complete:
  - `digdir/dev/altinn-docs` executed successfully
  - `digdir/dev/digdir-docs` executed successfully
  - `public-sector-knowledge/dev/kudos` executed successfully

This plan is complete and ready to move to `plans/completed/`.

---

## Scope

### In scope

- tenant consolidation and naming cleanup
- dataset and pipeline target-state cleanup
- placeholder agent creation for the new target tenants
- preservation of important existing configuration values during consolidation
- analysis and implementation of materialization-config migration from hard-coded code paths into config DB

### Out of scope

- designing or implementing the new skills required by the placeholder agents
- broad product/UX redesign unrelated to deployment readiness
- speculative tenant models beyond the two target tenants named here

---

## Target Topology

### Tenant 1: `digdir`

Target dataset set:

- `public-docs`
  - single target for public-facing documentation
  - examples include sources such as `docs.altinn.studio` and `docs.digdir.no`

Target pipelines:

- preserve the current pipeline definition currently represented by `altinn-docs`
- add a new similar pipeline called `digdir-docs`
- both pipelines should materialize into the single dataset `public-docs`

Target agents:

- preserve the current agent set
- add `interactive-doc-improve`
  - interactive interview-style documentation improvement agent
  - initial instructions may be placeholder-only
  - include notes about useful future skills for interviewing, gap detection, and change proposal drafting
- add `plain-language-qualtiy-check`
  - placeholder semi-autonomous plain-language review agent
  - preserve the requested identifier exactly for now
  - include notes about useful future skills for plain-language scoring, rewrite suggestions, and readability review

### Tenant 2: `public-sector-knowledge`

Target dataset set:

- `kudos`
  - single target for public-sector organization documentation
  - includes annual reports, mandate letters, and similar organizational material
  - current Typesense-backed `kudos` configuration values must be preserved

Target pipelines:

- preserve the current pipeline configuration represented today by tenant `ka`
- map that into the new tenant with the `kudos` dataset as the stable target

Target agents:

- preserve the current agent set
- add `cross-sector-researcher`
  - interactive agent that compiles answers across organizations into structured columns
  - placeholder instructions are sufficient for this phase

---

## Workstream A: Tenant And Entity Normalization

### Phase A1: Audit Current Live State

Objectives:

- inventory all current tenants
- inventory all datasets, dataset-pipelines, runtime nodes, and bindings
- inventory all current agents and their dataset access patterns
- identify duplication, near-duplicates, and stale entities

Tasks:

- export the current live config and main-DB entity state
- produce a mapping table from current tenant/dataset/pipeline/agent identities to target identities
- identify which existing entities can be renamed in place versus recreated and rebound

Primary outputs:

- current-to-target identity map
- list of entities to keep, merge, recreate, or retire

### Phase A2: Define The Concrete Target Mapping

Objectives:

- make the consolidation deterministic before mutating live data

Tasks:

- define exact target IDs for:
  - tenants
  - datasets
  - pipelines
  - agents
- define whether existing agent IDs stay global and unchanged or need cleanup aliases
- define how `altinn-docs` and `digdir-docs` pipelines map into `public-docs`
- define how current `ka`-derived dataset/materialization config maps into `public-sector-knowledge/kudos`
- formalize the recovered lineage that the old public-docs surface is currently represented by tenant `altinn` and pipeline `assistant`, while tenant `altinn-docs` survives only as a Platform-root shell

Required constraints:

- preserve important `kudos` materialization/storage values
- do not accidentally break dataset bindings or agent compatibility bindings during renames
- keep current agents available unless explicitly retired

### Phase A3: Placeholder Agent Definitions

Objectives:

- add the new deployment-target agents without blocking on future skill work

Tasks:

- create placeholder instructions for:
  - `interactive-doc-improve`
  - `plain-language-qualtiy-check`
  - `cross-sector-researcher`
- attach recommended future skill notes in agent instructions or adjacent docs
- ensure each new agent is bound to the correct target dataset(s)

Recommended instruction shape:

- role and objective
- interaction style
- expected output form
- explicit limitations until future skills exist
- notes on likely future skills/tooling

### Phase A4: Execute Tenant And Entity Migration

Objectives:

- apply the normalized target topology in the config DB and main DB

Tasks:

- create or rename the two target tenants
- create or verify target datasets
- create or update target dataset-pipelines
- rebind runtime trees, dataset trees, and agent compatibility bindings to the target identities
- migrate or recreate target agent records
- retire or disable superseded tenant-local nodes and bindings

Implementation preference:

- prefer explicit migration scripts or idempotent ops helpers over manual admin-UI edits
- preserve auditability and dry-run visibility where possible

### Phase A5: Verify Consolidated Topology

Verification:

- both target tenants resolve correctly through Platform, Runtime, and Dataset roots
- `public-docs` resolves both preserved and new documentation pipelines
- `kudos` preserves current materialization and storage behavior
- all preserved agents still resolve usable runtime config
- all new placeholder agents resolve cleanly and appear in admin surfaces

### Phase A6: Retire Original Tenants

Objectives:

- leave only the two target tenants in the live system once consolidation is verified
- remove stale tenant-local nodes, bindings, and selector noise before deployment

Tasks:

- identify the original tenants superseded by `digdir` and `public-sector-knowledge`
- verify that all required Platform, Runtime, and Dataset bindings have been recreated under the target tenants
- capture rollback exports before destructive cleanup
- remove or archive original tenant-local nodes, bindings, and tenant records
- confirm that admin selectors, diagnostics, and export/import surface only the target tenants

Constraints:

- do not remove original tenants until Phase A5 verification is complete
- preserve rollback artifacts before deleting tenant-local structures
- ensure no active dataset bindings, agent compatibility bindings, or pipeline materialization references still point at the original tenants

---

## Workstream B: Migrate Hard-Coded Materialization Config To Config DB

### Goal

Remove remaining hard-coded materialization configuration from code paths and make Dataset-root configuration the single source of truth for pipeline materialization behavior.

### Phase B1: Analysis Inventory

Objectives:

- identify every remaining hard-coded materialization decision

Tasks:

- inventory hard-coded defaults and derived config in:
  - `pipeline/core.clj`
  - `pipeline/collections.clj`
  - `setup.clj`
  - related ingestion/materialization modules
- classify each setting into:
  - already represented in `pipeline.*`
  - missing Dataset-root definition
  - derived/computed value that should remain derived
- identify secrets versus non-secret operational settings
- identify which values are currently tenant-specific, environment-specific, pipeline-specific, or dataset-wide

Primary output:

- hard-coded materialization inventory with recommended target config path for each item

### Phase B2: Target Design

Objectives:

- decide what the final Dataset-root contract should look like

Tasks:

- define missing `pipeline.*` and `dataset.*` definitions needed for materialization
- decide which current computed behaviors remain code-derived and which become explicit config
- define bootstrap and migration behavior for existing live tenants
- define how collection naming, source parameters, chunking, indexing, and execution controls should be represented

Constraints:

- avoid moving purely derived/invariant logic into config without reason
- keep root ownership clear: materialization in Dataset, shared services in Platform, answer behavior in Runtime

### Phase B3: Implementation Tasks

Tasks:

- add any missing Dataset-root config definitions
- update ingestion/materialization code to read Dataset-root config instead of hard-coded defaults
- add or extend ops/bootstrap helpers so existing materialization values can be seeded cleanly
- migrate current live hard-coded values into dataset trees for the two target tenants
- remove old code branches once Dataset-root reads are proven

Likely file areas:

- `server/src/digdir/pipeline/core.clj`
- `server/src/digdir/pipeline/collections.clj`
- `server/src/digdir/setup.clj`
- `server/src/digdir/config/db.clj`
- `server/src/digdir/config/ops.clj`
- relevant tests under `server/test/digdir/pipeline/*`

### Phase B4: Verification And Cutover

Verification:

- pipeline materialization works with zero reliance on hard-coded per-pipeline settings
- dataset trees contain the required materialization state
- current `kudos` materialization produces the same effective storage/collection behavior as before
- `public-docs` pipelines resolve correct materialization settings through Dataset V2
- export/import roundtrips the new materialization config fully

---

## Delivery Order

Recommended order:

1. complete Workstream A Phase A1 and A2 first
2. complete Workstream B Phase B1 and B2 second
3. implement the target topology and placeholder agents
4. retire original tenants once the normalized topology is verified
5. implement materialization-config migration
6. run end-to-end verification on both target tenants

Reasoning:

- the materialization migration should target the final tenant/pipeline/dataset topology, not the pre-cleanup landscape

---

## Risks

- accidental loss of `kudos` materialization values during tenant normalization
- preserving agent IDs while moving dataset bindings may leave hidden duplication if not audited carefully
- moving too much derived logic into config may increase complexity instead of reducing it
- materialization migration touches deployment-critical ingestion paths and needs stronger regression checks than ordinary config cleanup

---

## Verification Checklist

- target tenants exist as `digdir` and `public-sector-knowledge`
- target datasets exist as `public-docs` and `kudos`
- `altinn-docs`-derived and `digdir-docs` pipelines both materialize into `public-docs`
- `kudos` retains current effective materialization/storage behavior
- new placeholder agents are created and correctly bound
- original tenants are removed after verified cutover
- no critical hard-coded materialization settings remain outside approved derived logic
- export/import remains clean after the topology and materialization migration

---

## Immediate Next Step

Completed:

- collection naming and shared-dataset materialization assumptions were cleaned up
- the final target-topology rewrite was applied and verified in the live dev DB
- end-to-end materialization runs completed successfully for `digdir/public-docs` and `public-sector-knowledge/kudos`
- the deployment-polish plan is ready to close

# Pipeline Domain Rename — Direct Cutover Plan (No Shims)

> Status: Completed (direct cutover landed).  
> This document is retained as a historical execution plan and intentionally includes legacy token examples for migration traceability.

## Objective

Perform a **direct rename** of the remaining config/playground domain from legacy terminology to `pipeline` terminology, with **no wrappers, aliases, or compatibility shims**.

After this plan is complete:

1. Runtime APIs and storage use `pipeline` naming only.
2. Config domain internals use `pipeline` naming only.
3. UI labels and data contracts use `pipeline` naming only.
4. Seed/import data is migrated in stored JSON files (for example `config/config-values-current.json`) and loaded into a fresh DB.

## Hard Constraints

- No dual naming (`entity` + `pipeline`) in code paths.
- No temporary alias functions (for example `get-pipeline-names -> get-entity-names`).
- No runtime fallback translation from legacy keys/fields.
- Migration is done by transforming stored config export files before import.
- Validation target is a clean DB initialized from migrated files.

## Current Residual Surface (Item #3 Scope)

The remaining `entity` footprint is concentrated in:

- `server/src/digdir/config/db.clj`
- `server/src/digdir/config/accessor.clj`
- `server/src/digdir/config/ui.cljc`
- `server/src/digdir/config/ui/api_keys.cljc`
- `server/src/digdir/config/validator.clj`
- `server/src/digdir/config/schema.clj` (config scope field naming)
- `server/src/digdir/playground/core.cljc` and `server/src/digdir/playground/ui.cljc` (leftover internals/comments)
- API docs still referencing legacy-scoped behavior in some endpoints

## Final Naming Model

### Domain language

- `entity` -> `pipeline`
- `entities` -> `pipelines`
- `entity-id` -> `pipeline-id`
- `source-entity-id` -> `source-pipeline-id`
- `new-entity-id` -> `new-pipeline-id`
- `default-entity-id` -> `default-pipeline-id`

### Config scope field naming

- `:config/entity` -> `:config/pipeline`
- `:audit/entity` -> `:audit/pipeline`
- Any resolver arg key `entity` -> `pipeline`

### Resolution-level key naming

- `:entity` -> `:pipeline`
- `:entity-env` -> `:pipeline-env`
- `:entity-tenant` -> `:pipeline-tenant`
- `:entity-tenant-env` -> `:pipeline-tenant-env`

## Cutover Strategy (Direct)

## 1. Schema-first rename

1. Rename schema idents and docs:
- `:config/entity` -> `:config/pipeline`
- `:audit/entity` -> `:audit/pipeline`

2. Update all Datalog queries/pulls/transacts to new idents.

3. Remove old idents from schema definitions entirely.

## 2. Core config engine rename

In `server/src/digdir/config/db.clj`:

1. Rename core ID builders/parsers and semantics:
- `make-config-id` third segment becomes `pipeline`
- parsed output key `:entity` -> `:pipeline`

2. Rename resolver and CRUD signatures:
- all `entity` args -> `pipeline`
- all `:config/entity` reads/writes -> `:config/pipeline`

3. Rename level-key conventions used by inheritance tables:
- `:entity*` family -> `:pipeline*` family

4. Rename entity operations to pipeline operations:
- `get-entity` -> `get-pipeline`
- `create-entity!` -> `create-pipeline!`
- `update-entity!` -> `update-pipeline!`
- `delete-entity!` -> `delete-pipeline!`
- `soft-delete-entity!` -> `soft-delete-pipeline!`
- `duplicate-entity!` -> `duplicate-pipeline!`
- `list-entities` -> `list-pipelines`
- `list-all-entities` -> `list-all-pipelines`
- `get-entities-by-tenant` -> `get-pipelines-by-tenant`
- `get-entity-names` -> `get-pipeline-names`

5. Rename supporting constants/maps:
- `entity-property-to-path` -> `pipeline-property-to-path`
- `path-to-entity-property` -> `path-to-pipeline-property`
- `entity-property-paths` -> `pipeline-property-paths`
- `entity-properties` -> `pipeline-properties`

## 3. Config accessor/API surface rename

In `server/src/digdir/config/accessor.clj`:

1. Replace opts key `:entity` with `:pipeline`.
2. Rename API functions:
- `get-for-entity` -> `get-for-pipeline`
- `get-entity` -> `get-pipeline`
- `list-entities` -> `list-pipelines`
- `get-entities` -> `get-pipelines`
- `get-entities-for-tenant` -> `get-pipelines-for-tenant`

3. Update all call sites across server code and tests.

## 4. Config UI rename

In `server/src/digdir/config/ui.cljc` and `server/src/digdir/config/ui/api_keys.cljc`:

1. Rename all state/props/handler names from entity->pipeline.
2. Rename user-facing labels/buttons/messages from Entity->Pipeline.
3. Rename inheritance column keys to pipeline naming (`pipeline-env`, etc).
4. Rename data sources:
- `list-entities`/`get-entity-names` calls -> pipeline equivalents.

## 5. Validator/spec rename

In `server/src/digdir/config/validator.clj`:

1. Rename specs:
- `::entity` -> `::pipeline`
- `::entities` -> `::pipelines`
- `::default-entity-id` -> `::default-pipeline-id`

2. Rename `:chat` shape accordingly.
3. Update error messages and tests.

## 6. Playground and runtime clean pass

In `server/src/digdir/playground/*` and `server/src/digdir/rag/core.cljc`:

1. Remove leftover variable names/comments using `entity` where they now refer to pipeline config.
2. Ensure debug payload keys use `pipeline-*` naming only.

## 7. Docs + API contract updates

Update all user-facing docs to pipeline-only terminology:

- `server/docs/api/*.md`
- `server/docs/api/openapi.yaml`
- `server/docs/PIPELINES.md`
- `server/docs/pipeline-architecture.md`

## Data Migration via Stored Config Files (Fresh DB)

## Source files

Primary source:
- `config/config-values-current.json`

Any additional exported config snapshots should be migrated with the same transform.

## JSON transform rules

Apply deterministic transforms before import:

1. Rename key names in objects:
- `config/entity` -> `config/pipeline`
- `audit/entity` -> `audit/pipeline`
- `entity-id` -> `pipeline-id`
- `source-entity-id` -> `source-pipeline-id`
- `new-entity-id` -> `new-pipeline-id`
- `default-entity-id` -> `default-pipeline-id`

2. Rewrite `config/id` segment format:
- old: `tenant:env:entity:path`
- new: `tenant:env:pipeline:path`

3. Rewrite level keys and enum-like values in embedded maps/EDN strings:
- `entity-tenant-env` -> `pipeline-tenant-env`
- `entity-tenant` -> `pipeline-tenant`
- `entity-env` -> `pipeline-env`
- `entity` -> `pipeline` (context-sensitive; avoid accidental replacements in free text)

4. Rewrite config definition descriptions where they encode old concept names
- for example `Entity property: ...` -> `Pipeline property: ...`.

5. Keep cryptographic envelopes untouched (`encryption`, ciphertext values). Only mutate metadata keys/ids.

## Migration implementation

Create a one-off migration utility in repo (script/CLI), run as:

1. Read source JSON.
2. Apply schema-aware transformations (not blind global string replace).
3. Write `config/config-values-pipeline-cutover.json`.
4. Validate output:
- JSON parseable
- every `config/id` has 4 segments
- no forbidden legacy keys remain

## Fresh DB procedure

1. Drop/recreate DB.
2. Apply renamed schema.
3. Import migrated JSON (`config-values-pipeline-cutover.json`).
4. Run smoke tests and API checks.

No in-place DB migration is part of this plan.

## Execution Phases

## Phase A — Mechanical Rename (Code)

1. Rename idents, functions, args, docs in config modules.
2. Update all call sites and tests.
3. Remove any temporary aliases immediately (none allowed to remain).

Deliverable: code compiles, test suite green against renamed code.

## Phase B — Data File Migration Tool

1. Implement JSON migration tool.
2. Produce migrated file.
3. Validate transformed data.

Deliverable: `config/config-values-pipeline-cutover.json` and validation report.

## Phase C — Fresh DB Bring-up

1. Initialize fresh DB with new schema.
2. Import migrated file.
3. Run focused + integration tests.

Deliverable: reproducible bootstrap from migrated file only.

## Phase D — Documentation Closure

1. Remove remaining entity wording from docs that describe runtime/config concepts.
2. Keep only historical references in architecture archive docs if needed.

Deliverable: docs reflect pipeline-only vocabulary.

## Test Plan

## Unit/Focused

- `digdir.config.db-test`
- `digdir.config.ui-test`
- `digdir.config.api-keys-test`
- `digdir.api.routes-test`
- `digdir.pipeline.*` and `digdir.docs.pipeline.*` tests impacted by config naming

## Integration

1. Create/update/delete pipeline config via UI/API paths.
2. Create API key with pipeline scopes.
3. Start conversation and playground execution using pipeline selection.
4. Import migrated JSON into fresh DB and verify behavior parity.

## Static checks

Run grep gates (must return zero in code/docs except archived migration docs):

- `entity-id`
- `:config/entity`
- `:audit/entity`
- `tenant-entity`
- `entity-env`
- `default-entity-id`

## Acceptance Criteria

1. No runtime/config code depends on `entity` naming.
2. No wrappers/shims/aliases exist for old names.
3. Fresh DB can be built from migrated config JSON and passes tests.
4. OpenAPI + endpoint docs are pipeline-only for active APIs.

## Risks and Mitigations

1. Large rename blast radius in config logic.
- Mitigation: do mechanical rename with compiler/test gates at each commit boundary.

2. Missed transforms in JSON migration.
- Mitigation: schema-aware migration tool + post-transform grep/validation checks.

3. Hidden usage in EDN/serialized fields.
- Mitigation: targeted decoder/rewriter for known structured string fields; fail fast on unknown legacy tokens.

## Out of Scope

- Backward compatibility with legacy pre-cutover DB instances.
- Runtime auto-migration of old config paths.
- Alias endpoints or dual API contracts.

## Recommended Commit Breakdown

1. `refactor(config-schema): rename entity scope to pipeline scope`
2. `refactor(config-db): rename entity model/functions/levels to pipeline`
3. `refactor(config-accessor-ui): pipeline-only naming`
4. `refactor(playground-rag): pipeline terminology cleanup`
5. `feat(migration): add config-values entity->pipeline transformer`
6. `docs(api): pipeline-only terminology`
7. `test: update and pass renamed domain suite`

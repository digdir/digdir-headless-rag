# Implementation Plan: Operator Console and Nested Dataset/Pipeline APIs

Status: Completed on 2026-03-30.

## 1. Objective
Implement a clean cut to the new architecture:

- rebrand the internal admin app as the `Operator Console`
- move JWT-only APIs to `/console-api/...`
- introduce a new globally unique `dataset-id`
- model pipelines as child resources under datasets
- keep pipeline execution operator-console-only
- remove temporary aliases and compatibility layers
- document all public API changes for internal client upgrades

Reference artifact:
- `plans/completed/operator-console-public-api-route-auth-matrix.md`

## 2. Non-Goals
- No temporary alias routes
- No compatibility payload fields kept only for transition
- No mixed JWT/API-key access to the same logical public surface
- No preservation of the old flat admin route structure once the cut is made

## 3. Phase 1: Define and Freeze the Target Contract

### 3.1 Dataset ID Model
- Define a new globally unique opaque dataset ID
- Store the user-defined dataset `name` separately
- Define the persistence and lookup rules for tenant ownership and authorization
- Use the matrix document as the source-of-truth summary for ID and route semantics

### 3.2 Pipeline Child Model
- Define pipeline identity under a dataset
- Decide whether the child identifier is:
  - opaque and globally unique, or
  - unique within the dataset scope
- Require that a dataset exists before a pipeline can be created

### 3.3 Route/Auth Matrix
Create a source-of-truth matrix listing:
- route
- audience
- auth type
- resource
- operation
- operator-console-only vs public

Deliverable:
- checked-in architecture/matrix doc that can drive implementation and review

## 4. Phase 2: Repair Existing Auth Boundary

### Files
- `server/src/digdir/api/http.clj`
- `server/src/digdir/api/routes.clj`

### Actions
- Update the JWT middleware route matching so it actually reflects the declared operator-only route surface
- Rename middleware comments and route comments from `admin` to `operator console` where appropriate
- Ensure current JWT-only pipeline routes and API-key routes are consistently gated before broader restructuring

Success criteria:
- JWT/operator-only routes are enforced consistently in middleware
- no stale `/api/keys` assumptions remain in auth handling

## 5. Phase 3: Introduce Dataset Parent Resource in Persistence

### Files (likely)
- `server/src/digdir/config/db.clj`
- `server/src/digdir/pipeline/core.clj`
- `server/src/digdir/config/ops.clj`
- schema/migration files under `server/src/digdir/config/` and related tests

### Actions
- Add the new canonical dataset record keyed by globally unique dataset ID
- Update pipeline records to reference the parent dataset explicitly
- Enforce “pipeline cannot exist without dataset”
- Ensure at least one child pipeline can be attached to a dataset before it is considered fully configured/queryable

Success criteria:
- datasets and pipelines are stored with explicit parent/child semantics
- the old `tenant:environment:pipeline-name` value is no longer the canonical dataset identifier

## 6. Phase 4: Move Operator APIs to `/console-api/...`

### Current JWT-only routes to move
- `/config/api-keys`
- `/config/api-keys/:key-id/config-grants`
- `/config/api-keys/:key-id/revoke`
- `/api/users`
- `/api/users/:id`
- `/api/users/:id/permissions`
- `/api/permissions`
- `/api/conversations` admin listing
- `/api/pipelines`
- `/api/pipelines/:id`
- `/api/pipelines/:id/execute`
- `/api/pipelines/:id/executions`

### Target direction
- `/console-api/users`
- `/console-api/users/:id`
- `/console-api/users/:id/permissions`
- `/console-api/permissions`
- `/console-api/conversations`
- `/console-api/api-keys`
- `/console-api/api-keys/:key-id/config-grants`
- `/console-api/api-keys/:key-id/revoke`
- `/console-api/datasets`
- `/console-api/datasets/:dataset-id`
- `/console-api/datasets/:dataset-id/pipelines`
- `/console-api/datasets/:dataset-id/pipelines/:pipeline-id`
- `/console-api/datasets/:dataset-id/pipelines/:pipeline-id/execute`
- `/console-api/datasets/:dataset-id/pipelines/:pipeline-id/executions`

Success criteria:
- operator-console routes are clearly separated by path and auth model
- JWT-only APIs no longer live under ambiguous public-looking prefixes

## 7. Phase 5: Redesign Public APIs Around Dataset Parent Resources

### Public API principles
- API-key auth only
- dataset-first
- multi-tenant by design
- no public pipeline execution route
- public dataset and nested pipeline state is read-only

### Actions
- redesign public management/state routes as nested read-only resources
- redesign public read/query routes to use the new dataset ID model
- update authorization code to operate on new dataset IDs and nested pipeline ownership where needed

Possible target direction:
- `/api/datasets` `GET`
- `/api/datasets/:dataset-id` `GET`
- `/api/datasets/:dataset-id/pipelines` `GET`
- `/api/datasets/:dataset-id/pipelines/:pipeline-id` `GET`

Success criteria:
- public API paths and resource shapes reflect the new parent/child model
- API-key auth is the only auth mode used by public APIs
- all public dataset/pipeline management exposure is read-only

## 8. Phase 6: Update Operator Console UI

### Likely files
- `server/src/digdir/pipeline/ui/pipelines.cljc`
- routing and console navigation files under `server/src/digdir/ui/`

### Actions
- rename the internal admin UI conceptually to `Operator Console`
- make datasets the top-level console resource
- show pipelines as nested child resources within a dataset view
- require dataset creation before pipeline creation
- expose pipeline execution/status in the dataset context

Dataset view should include child pipeline signals such as:
- last run time
- changed record count
- failure status
- active pipeline count

Success criteria:
- the console IA reflects parent dataset / child pipeline semantics
- users no longer encounter dataset/pipeline ambiguity in the console

### Current Status

Completed:
- dataset-first hierarchy in `server/src/digdir/pipeline/ui/pipelines.cljc`
- pipelines shown as child resources of datasets
- dataset creation required before pipeline creation
- per-pipeline execution action available from dataset context
- user-facing branding renamed to `Digdir Operator Console`
- datasets elevated to a top-level navigation resource instead of a Config sub-tab
- dataset list view now shows active/running and failed pipeline counts plus latest run time
- dataset detail view now shows per-pipeline last run, changed/processed counts, failure state, and recent execution history
- dataset detail summary now shows running/failed totals and latest dataset activity

Notes:
- execution details currently live primarily in `server/src/digdir/pipeline/ui/executions.cljc`
- the dataset detail view now surfaces the plan’s required execution signals directly, while the dedicated executions view remains available for deeper inspection

## 9. Phase 7: Rewrite Docs, Spec, and Examples

### Files
- `server/docs/DATASETS.md`
- `server/docs/PIPELINES.md`
- `server/docs/api/*`
- `server/docs/api/openapi.yaml`
- `server/docs/api/examples/*`
- any new operator-console docs

### Actions
- split operator-console docs from public API docs
- keep dataset docs read/access focused
- keep pipeline docs ingestion/materialization focused
- document all public API breaking changes explicitly
- update OpenAPI and examples to match the clean-cut contract

Deliverables:
- public API migration guide
- route change table
- payload field change table
- ID model change explanation

## 10. Phase 8: Verification

### Verification targets
- route tests
- auth middleware tests
- operator-console route tests
- public API tests
- docs/spec validation

### Required checks
- no remaining stale JWT/public route overlap
- no stale flat dataset/pipeline route structures
- no lingering old payload fields in public contracts
- docs and code use the same audience/resource terminology

Verification completed:
- `clj -M:test -n digdir.api.routes-test`
- `clj -M:test -n digdir.config.ui-test`
- `clj -M:test -n digdir.config.db-test`
- `clj -M:test -n digdir.pipeline.core-test`
- `clj -M:test -n digdir.pipeline.integration-test`
- `clj -M:test -n digdir.migration.system-test`
- `server/docs/api/openapi.yaml` parses successfully
- `server/docs/api/examples/postman-collection.json` parses successfully
- terminology sweep completed across active source/docs touched by this plan

## 11. Concrete Breaking Changes To Document
- old JWT-only admin routes -> new `/console-api/...` routes
- old flat pipeline routes -> new nested dataset/pipeline routes where applicable
- old canonical dataset ID `tenant:environment:pipeline-name` -> new opaque dataset ID
- old payload fields tied to the flat/legacy structure -> new parent/child resource payloads
- public auth boundary clarified to API-key-only
- pipeline execution restricted to operator-console/JWT only

## 12. Immediate Next Actions
- [x] Write the route/auth matrix
- [x] Design the new dataset ID shape and persistence changes
- [x] Patch `api/http.clj` so current JWT-only handling matches declared routes
- [x] Design `/console-api/...` route tree
- [x] Decide which nested management routes are public vs operator-only
- [x] Begin schema/persistence changes for parent dataset / child pipeline model
- [x] Complete Phase 6 Operator Console UI status/branding/navigation work
- [x] Run full default test suite and validate docs/spec artifacts

## 13. Status Snapshot

Current implementation status:

- [x] Phase 1: target contract, dataset ID model, and route/auth matrix
- [x] Phase 2: JWT/operator auth boundary repair
- [x] Phase 3: dataset parent persistence and child pipeline model
- [x] Phase 4: operator routes moved to `/console-api/...`
- [x] Phase 5: public read-only `/api/datasets/...` surface
- [x] Phase 6: dataset-first Operator Console UI slice fully complete
- [x] Phase 7: docs, OpenAPI, curl examples, and Postman aligned
- [x] Phase 8: final verification and consistency sweep for the full plan

Phase 8 note:
- verification completed for backend, docs, and the dataset-first Operator Console UI slice
- `clj -M:test` passed on the default test path after moving live rerank evaluation suites to explicit opt-in execution

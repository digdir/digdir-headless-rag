# Strategic Plan: Operator Console, Public APIs, Datasets, and Pipelines

Status: Completed on 2026-03-30.

## 1. Objective
Eliminate the long-running ambiguity between:

- the internal JWT-authenticated interactive admin application
- the public multi-tenant machine-facing APIs
- datasets as read targets
- pipelines as producer/configuration resources

The result should be a stable system model where:

- the internal admin UI is rebranded as the `Operator Console`
- `datasets` are parent resources and read targets
- `pipelines` are child resources under datasets
- agents read datasets, not pipelines directly
- operator-console APIs use JWT/cookie auth
- public APIs use API keys exclusively

## 2. Strategy: Explicit Surface and Resource Boundaries
Shift from a mixed model to an explicitly separated model.

- **Surface Boundary:** split `Operator Console` from `Public APIs`
- **Auth Boundary:** JWT only for operator-console APIs, API keys only for public APIs
- **Resource Boundary:** datasets are parent/read resources; pipelines are child/producer resources
- **Documentation Boundary:** operator-console docs, public API docs, dataset docs, and pipeline docs must each describe only one mental model

Reference artifact:
- `plans/completed/operator-console-public-api-route-auth-matrix.md`

## 3. Target Resource Model

### Dataset
A dataset is the parent resource and the read target.

- globally unique opaque `dataset-id`
- user-defined `name`
- tenant ownership and any metadata required for policy/routing
- agent access is granted to datasets
- API keys grant dataset access

### Pipeline
A pipeline is a child resource under exactly one dataset.

- belongs to one dataset
- encapsulates ingestion/materialization configuration
- may have its own child ID or unique-per-dataset name
- cannot exist unless its parent dataset already exists
- execution remains operator-console-only

### Core Relationship
- pipelines produce datasets
- datasets are the targets that agents and public query APIs read
- public clients do not execute pipelines

## 4. Surface Model

### Operator Console
Internal tool, backed by Electric UI and JWT/cookie authentication.

Responsibilities:
- operator workflows
- dataset lifecycle management
- pipeline lifecycle management
- pipeline execution
- user/permission management
- internal operational visibility

### Public APIs
Machine-facing multi-tenant APIs authenticated with API keys.

Responsibilities:
- public query and retrieval
- public dataset selection
- public read-only dataset and nested pipeline state, using API-key auth only

Non-responsibilities:
- no JWT dependency
- no operator-console-only execution surface

## 5. Current JWT-Only API Surface
This is the current operator/admin API surface that should be reviewed and rehomed under the operator-console model.

- `/config/api-keys` `GET`, `POST`
- `/config/api-keys/:key-id/config-grants` `PUT`
- `/config/api-keys/:key-id/revoke` `POST`
- `/api/users` `GET`, `POST`
- `/api/users/:id` `GET`, `DELETE`
- `/api/users/:id/permissions` `PUT`
- `/api/permissions` `GET`
- `/api/conversations` `GET` for operator/admin listing
- `/api/pipelines` `GET`, `POST`
- `/api/pipelines/:id` `GET`, `PUT`, `DELETE`
- `/api/pipelines/:id/execute` `POST`
- `/api/pipelines/:id/executions` `GET`

Important note:
- the middleware in `server/src/digdir/api/http.clj` does not fully match the declared JWT-only route surface, so this boundary is currently inconsistent in code

## 6. Target Route Model

### Operator Console APIs
Move JWT-only operator APIs under `/console-api/...` to make the audience and auth model unambiguous.

Target direction:
- `/console-api/datasets`
- `/console-api/datasets/:dataset-id`
- `/console-api/datasets/:dataset-id/pipelines`
- `/console-api/datasets/:dataset-id/pipelines/:pipeline-id`
- `/console-api/datasets/:dataset-id/pipelines/:pipeline-id/execute`
- `/console-api/datasets/:dataset-id/pipelines/:pipeline-id/executions`

Plus operator-only identity/permissions/config routes:
- users
- permissions
- operator conversation listing
- operator API-key management

### Public APIs
Public APIs remain under `/api/...` and use API keys exclusively.

Public APIs should be dataset-oriented where they express read/access concerns.

Public read-only management/state APIs should be dataset-first and pipeline-nested:
- parent dataset resources
- child pipeline resources
- no flat producer routes that blur parent/child semantics
- no public mutation or execution routes for pipelines

## 7. Documentation Model

### Operator Console Docs
Describe:
- operator workflows
- JWT auth
- internal-only status
- navigation and permissions
- dataset-first nested UI model

### Dataset Docs
Describe:
- dataset IDs
- dataset access model
- dataset refs
- API key scoping
- agent access
- read-time semantics only

### Pipeline Docs
Describe:
- ingestion
- materialization
- chunking
- source config
- execution
- pipeline status and lifecycle

### Public API Docs
Describe:
- public query APIs
- public management APIs, if exposed
- API-key auth only
- no operator-console assumptions

## 8. Success Criteria
- [x] The term `Operator Console` replaces `Admin UI` in user-facing architecture docs.
- [x] JWT-only routes are clearly separated under `/console-api/...`.
- [x] Public APIs use API keys exclusively.
- [x] Dataset resources use a globally unique opaque ID rather than `tenant:environment:pipeline-name`.
- [x] Pipelines are modeled and documented as child resources under datasets.
- [x] Agents are documented and implemented as reading datasets, not pipelines directly.
- [x] Operator docs and public API docs no longer mix audiences or auth models.
- [x] All public API breaking changes are documented for internal client upgrades.

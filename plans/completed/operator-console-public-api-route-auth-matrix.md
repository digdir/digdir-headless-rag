# Route/Auth Matrix: Operator Console and Public APIs

Status: Completed on 2026-03-30. This matrix reflects the implemented route and auth split.

## 1. Objective
Provide a source-of-truth matrix for:

- current route audience and auth behavior
- mismatches between declared routes and middleware handling
- target route structure after the operator-console/public API split

This document is intended to drive implementation sequencing and review.

## 2. Core Model

### Audience
- `Operator Console`: internal operator workflows, JWT/cookie auth
- `Public API`: machine-facing multi-tenant APIs, API-key auth
- `Debug`: operational/debug endpoints with debug API key

### Resource Model
- `dataset`: parent read target
- `pipeline`: child producer/configuration resource under a dataset
- `agent`: reads datasets
- `api key`: grants access to datasets and other scoped capabilities

### Auth Model
- `JWT cookie`: operator-console-only
- `API key`: public API only
- `Debug API key`: debug routes only

## 3. Current Declared Route Surface

### 3.1 Public API-Key Routes

| Route | Methods | Audience | Auth | Resource | Purpose |
|------|---------|----------|------|----------|---------|
| `/api/rag` | `POST` | Public API | API key | dataset/query | RAG query |
| `/api/retrieve` | `POST` | Public API | API key | dataset/query | Retrieval-only |
| `/api/datasets` | `GET` | Public API | API key | datasets | List visible datasets |
| `/api/datasets/:dataset-id` | `GET` | Public API | API key | datasets | Dataset detail and status |
| `/api/datasets/:dataset-id/pipelines` | `GET` | Public API | API key | pipelines | Child pipeline state under dataset |
| `/api/datasets/:dataset-id/pipelines/:pipeline-id` | `GET` | Public API | API key | pipelines | Child pipeline detail/state |
| `/api/conversations` | `GET`, `POST` | Public API | API key | conversations | Conversation list/create |
| `/api/conversations/:id` | `GET`, `PUT`, `DELETE` | Public API | API key | conversations | Conversation detail/update/delete |
| `/api/skills` | `GET` | Public API | API key | skills | List skills |
| `/api/skills/tools` | `GET` | Public API | API key | skills | Tool definitions |
| `/api/skills/:id` | `GET` | Public API | API key | skills | Skill detail |
| `/api/skills/:id/execute` | `POST` | Public API | API key | skills | Execute skill |
| `/api/skill-graphs` | `GET` | Public API | API key | skill graphs | List graphs |
| `/api/skill-graphs/:id` | `GET` | Public API | API key | skill graphs | Graph detail |
| `/api/skill-graphs/:id/execute` | `POST` | Public API | API key | skill graphs | Execute graph |
| `/api/skill-graphs/execute` | `POST` | Public API | API key | skill graphs | Execute custom graph |

### 3.2 Debug Routes

| Route | Methods | Audience | Auth | Resource | Purpose |
|------|---------|----------|------|----------|---------|
| `/api/debug/dataset-config` | `GET` | Debug | Debug API key | dataset/debug | Inspect resolved dataset config |
| `/api/debug/chunk` | `GET` | Debug | Debug API key | chunk/debug | Inspect a chunk |

### 3.3 JWT-Only Operator Console Routes

| Route | Methods | Audience | Auth | Resource | Purpose |
|------|---------|----------|------|----------|---------|
| `/console-api/api-keys` | `GET`, `POST` | Operator Console | JWT cookie | api keys | List/create keys |
| `/console-api/api-keys/:key-id/config-grants` | `PUT` | Operator Console | JWT cookie | api keys | Replace config grants |
| `/console-api/api-keys/:key-id/revoke` | `POST` | Operator Console | JWT cookie | api keys | Revoke key |
| `/console-api/users` | `GET`, `POST` | Operator Console | JWT cookie | users | List/create users |
| `/console-api/users/:id` | `GET`, `DELETE` | Operator Console | JWT cookie | users | User detail/delete |
| `/console-api/users/:id/permissions` | `PUT` | Operator Console | JWT cookie | users | Update permissions |
| `/console-api/permissions` | `GET` | Operator Console | JWT cookie | permissions | List permissions |
| `/console-api/conversations` | `GET` | Operator Console | JWT cookie | conversations | Operator conversation listing |
| `/console-api/datasets` | `GET`, `POST` | Operator Console | JWT cookie | datasets | List/create parent datasets |
| `/console-api/datasets/:dataset-id` | `GET` | Operator Console | JWT cookie | datasets | Dataset detail |
| `/console-api/datasets/:dataset-id/pipelines` | `GET`, `POST` | Operator Console | JWT cookie | pipelines | List/create child pipelines |
| `/console-api/datasets/:dataset-id/pipelines/:pipeline-id` | `GET`, `PUT`, `DELETE` | Operator Console | JWT cookie | pipelines | Child pipeline detail/update/delete |
| `/console-api/datasets/:dataset-id/pipelines/:pipeline-id/execute` | `POST` | Operator Console | JWT cookie | pipelines | Execute pipeline |
| `/console-api/datasets/:dataset-id/pipelines/:pipeline-id/executions` | `GET` | Operator Console | JWT cookie | pipelines | List pipeline executions |

## 4. Current Middleware Status

The current JWT middleware in `server/src/digdir/api/http.clj` now matches the declared Operator Console route surface.

### 4.1 Current Middleware Matching

| Middleware Check | Current Match |
|------|------|
| Operator Console JWT gate | `uri` starts with `/console-api/` |
| Public API key gate | `uri` starts with `/api/` excluding debug routes |
| Debug API key gate | `uri` starts with `/api/debug/` |

### 4.2 Current Verification Notes

| Note | Status |
|------|--------|
| JWT middleware route matching | Aligned with `/console-api/...` |
| Public API route matching | Aligned with `/api/...` |
| Debug route matching | Aligned with `/api/debug/...` |
| Remaining work | Verification and terminology cleanup only |

### 4.3 Verification Outcome
- No known auth-boundary mismatch remains in middleware after the `/console-api/...` cutover.

## 5. Target Route/Auth Matrix

### 5.1 Target Operator Console Routes

All operator-only routes move under `/console-api/...` and use JWT/cookie auth.

| Target Route | Methods | Audience | Auth | Resource | Notes |
|------|---------|----------|------|----------|------|
| `/console-api/users` | `GET`, `POST` | Operator Console | JWT cookie | users | Internal-only |
| `/console-api/users/:id` | `GET`, `DELETE` | Operator Console | JWT cookie | users | Internal-only |
| `/console-api/users/:id/permissions` | `PUT` | Operator Console | JWT cookie | users | Internal-only |
| `/console-api/permissions` | `GET` | Operator Console | JWT cookie | permissions | Internal-only |
| `/console-api/conversations` | `GET` | Operator Console | JWT cookie | conversations | Operator listing only |
| `/console-api/api-keys` | `GET`, `POST` | Operator Console | JWT cookie | api keys | Internal operator workflow |
| `/console-api/api-keys/:key-id/config-grants` | `PUT` | Operator Console | JWT cookie | api keys | Internal operator workflow |
| `/console-api/api-keys/:key-id/revoke` | `POST` | Operator Console | JWT cookie | api keys | Internal operator workflow |
| `/console-api/datasets` | `GET`, `POST` | Operator Console | JWT cookie | datasets | Parent resource lifecycle |
| `/console-api/datasets/:dataset-id` | `GET`, `PUT`, `DELETE` | Operator Console | JWT cookie | datasets | Parent resource lifecycle |
| `/console-api/datasets/:dataset-id/pipelines` | `GET`, `POST` | Operator Console | JWT cookie | pipelines | Child resources under dataset |
| `/console-api/datasets/:dataset-id/pipelines/:pipeline-id` | `GET`, `PUT`, `DELETE` | Operator Console | JWT cookie | pipelines | Child resource lifecycle |
| `/console-api/datasets/:dataset-id/pipelines/:pipeline-id/execute` | `POST` | Operator Console | JWT cookie | pipelines | Operator-only execution |
| `/console-api/datasets/:dataset-id/pipelines/:pipeline-id/executions` | `GET` | Operator Console | JWT cookie | pipelines | Operator-only history |

### 5.2 Target Public Routes

All public routes remain under `/api/...` and use API keys exclusively.

| Target Route | Methods | Audience | Auth | Resource | Notes |
|------|---------|----------|------|----------|------|
| `/api/rag` | `POST` | Public API | API key | dataset/query | Public query |
| `/api/retrieve` | `POST` | Public API | API key | dataset/query | Public retrieval |
| `/api/conversations` | `GET`, `POST` | Public API | API key | conversations | Public conversation scope |
| `/api/conversations/:id` | `GET`, `PUT`, `DELETE` | Public API | API key | conversations | Public conversation scope |
| `/api/skills` | `GET` | Public API | API key | skills | Public machine-facing API |
| `/api/skills/tools` | `GET` | Public API | API key | skills | Public machine-facing API |
| `/api/skills/:id` | `GET` | Public API | API key | skills | Public machine-facing API |
| `/api/skills/:id/execute` | `POST` | Public API | API key | skills | Public machine-facing API |
| `/api/skill-graphs` | `GET` | Public API | API key | skill graphs | Public machine-facing API |
| `/api/skill-graphs/:id` | `GET` | Public API | API key | skill graphs | Public machine-facing API |
| `/api/skill-graphs/:id/execute` | `POST` | Public API | API key | skill graphs | Public machine-facing API |
| `/api/skill-graphs/execute` | `POST` | Public API | API key | skill graphs | Public machine-facing API |

### 5.3 Public Dataset/Pipeline Management Decision Point

Decision: `Option C`

Public APIs should expose dataset and nested pipeline state as read-only resources.

All modifications and all pipeline execution remain operator-console-only.

| Option | Public Routes | Auth | Notes |
|------|------|------|------|
| `A` | No public management routes | API key | Keep public APIs read/query focused; management is operator-only |
| `B` | Public dataset management only | API key | Expose dataset parent resources, no public pipeline execution |
| `C` | Public dataset + nested pipeline management | API key | Expose nested child resources, but keep execution operator-only |

Applied direction:
- expose dataset state publicly
- expose nested pipeline state publicly
- keep pipeline mutations operator-console-only
- keep pipeline execution operator-console-only

That means the public nested resources are informational/state-oriented, not lifecycle-control endpoints.

## 5.4 Target Public Read-Only Management Shape

| Target Route | Methods | Audience | Auth | Resource | Notes |
|------|---------|----------|------|----------|------|
| `/api/datasets` | `GET` | Public API | API key | datasets | List readable datasets |
| `/api/datasets/:dataset-id` | `GET` | Public API | API key | datasets | Dataset detail and status |
| `/api/datasets/:dataset-id/pipelines` | `GET` | Public API | API key | pipelines | Child pipeline state under dataset |
| `/api/datasets/:dataset-id/pipelines/:pipeline-id` | `GET` | Public API | API key | pipelines | Child pipeline detail/state |

Explicit exclusions from the public API:
- no public dataset mutation endpoints
- no public pipeline mutation endpoints
- no public pipeline execution endpoints

## 6. Target Naming Rules

| Concern | Preferred Term |
|------|------|
| Internal interactive tool | `Operator Console` |
| JWT-only internal routes | `Operator Console APIs` |
| Parent read target resource | `dataset` |
| Child producer/configuration resource | `pipeline` |
| Agent access target | `dataset` |
| API-key access target | `dataset` |
| Ingestion execution | `pipeline` |

## 7. Required Breaking Changes To Track

| Old | New |
|------|------|
| JWT-only admin routes under mixed prefixes | JWT-only operator routes under `/console-api/...` |
| Flat operator pipeline routes | Nested dataset/pipeline routes |
| Canonical dataset ID `tenant:environment:pipeline-name` | New opaque globally unique `dataset-id` |
| Mixed admin/public terminology | Explicit `Operator Console` vs `Public API` terminology |

## 8. Immediate Next Steps
- [x] Add this matrix as a referenced artifact from both in-progress plan docs
- [x] Patch `api/http.clj` so current auth middleware matches current declared JWT route surface
- [x] Decide public management option `A`, `B`, or `C`
- [x] Design the new dataset persistence model and ID generation rules
- [x] Draft the target `/console-api/...` route tree in code comments or a follow-up plan artifact

## 9. Dataset Persistence Model and ID Rules

### 9.1 Dataset ID
- `dataset-id` is a globally unique opaque identifier
- it is allocated once at dataset creation time
- it is the only canonical dataset identifier used in APIs
- it is not derived from tenant, environment, or pipeline name

### 9.2 Dataset Fields
- `id`: opaque globally unique dataset ID
- `name`: user-defined display name
- `tenant`: owning tenant or tenant binding used for authorization
- additional metadata needed for ownership, status, and policy

### 9.3 Pipeline Fields
- `pipeline-id`: child identifier under a dataset
- `dataset-id`: required parent reference
- configuration fields for source, chunking, materialization, and runtime defaults
- status fields needed for operator and public read-only visibility

### 9.4 Integrity Rules
- a dataset must exist before a pipeline can be created
- every pipeline belongs to exactly one dataset
- a dataset may have one or more pipelines
- a dataset becomes queryable only when it has enough configured/active child pipeline state to produce a valid read target

### 9.5 Public Visibility Requirements
Public dataset and pipeline state should be able to expose fields such as:
- dataset name
- dataset status
- pipeline last-run timestamp
- pipeline change counts
- pipeline health/failure status

without exposing operator-only mutation or execution controls.

## 10. Target `/console-api/...` Route Tree

### 10.1 Operator Console Identity and Access
- `/console-api/users`
- `/console-api/users/:id`
- `/console-api/users/:id/permissions`
- `/console-api/permissions`

### 10.2 Operator Console Conversations
- `/console-api/conversations`

### 10.3 Operator Console API Keys
- `/console-api/api-keys`
- `/console-api/api-keys/:key-id/config-grants`
- `/console-api/api-keys/:key-id/revoke`

### 10.4 Operator Console Datasets
- `/console-api/datasets`
- `/console-api/datasets/:dataset-id`

### 10.5 Operator Console Pipelines
- `/console-api/datasets/:dataset-id/pipelines`
- `/console-api/datasets/:dataset-id/pipelines/:pipeline-id`
- `/console-api/datasets/:dataset-id/pipelines/:pipeline-id/execute`
- `/console-api/datasets/:dataset-id/pipelines/:pipeline-id/executions`

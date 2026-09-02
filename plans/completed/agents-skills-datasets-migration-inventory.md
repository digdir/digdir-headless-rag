# Agents, Skills, and Datasets Migration Inventory

## Purpose

This document turns Phase 0 of the clean-cut refactor into a concrete inventory of the current code paths that still depend on:

- composite `pipeline-id` strings
- pipeline-scoped conversations
- pipeline-centric API key authorization
- missing first-class agent definitions

It is intended to be used alongside:

- `decisions/agents-skills-and-datasets.md`
- `plans/in-progress/agents-skills-datasets-clean-cut-plan.md`
- `plans/in-progress/agents-skills-datasets-clean-cut-checklist.md`

## Scope boundary

This inventory distinguishes between two different uses of `pipeline`:

1. public/runtime identity and persistence
2. internal config scope for dataset/materialization configuration

The clean cut targets category 1 directly. Category 2 still matters where route handlers, UI, or tooling currently force users to pass composite `pipeline-id` strings, but this inventory does not treat every internal `:config/pipeline` occurrence as a mandatory rename target.

## Summary

The system currently uses composite `pipeline-id` strings as a portable identity token across:

- API key persistence and validation
- auth middleware request context
- public RAG and retrieval APIs
- conversation persistence and list views
- playground execution setup
- pipeline admin CRUD
- execution/audit records
- Babashka operational tasks
- a large portion of route and UI test fixtures

At the same time:

- skill graphs already exist as a first-class runtime concept
- agent behavior exists informally in builtin skill graphs
- no durable `agent` entity model currently exists
- conversations are still scoped to a pipeline instead of an agent

## 1. Main DB persistence inventory

### File

- `server/src/digdir/data/db.cljc`

### Current pipeline-centric schema

- `:api-key/pipelines`
- `:conversation/pipeline-id`
- `:api-key/skill-graphs`

### Current conversation helpers and queries

These all read or expose `:conversation/pipeline-id` directly:

- `fetch-convo-pipeline-id`
- `conversations`
- `conversations-by-user`
- `orphan-conversations`
- `conversation-by-id`
- `conversations-in-folder`

### Current conversation writes

These persist pipeline identity as the conversation scope:

- `transact-new-msg-thread`
- `create-playground-conversation`

### Implication for the cut

- add first-class `:conversation/agent-id`
- stop persisting `:conversation/pipeline-id`
- replace `:api-key/pipelines` with explicit dataset-ref grants
- preserve `:api-key/skill-graphs` for the first cut if low-level graph execution remains supported

## 2. Config DB and audit inventory

### Files

- `server/src/digdir/config/schema.clj`
- `server/src/digdir/config/audit.clj`
- `server/src/digdir/config/ops.clj`

### Relevant persisted fields

- `:audit/api-key-pipeline-id`
- `:pipeline-execution/pipeline-id`

### Notes

The config DB still uses `tenant/environment/client/skill-graph/pipeline/path` as part of config-row scoping and uniqueness. That internal scoping model is not the same as the public composite `pipeline-id` problem, but several runtime and tooling paths currently bridge into it through composite pipeline strings.

### Implication for the cut

- add durable agent entities to the config DB
- refactor audit/event records away from singular `api-key-pipeline-id`
- refactor pipeline execution records to carry explicit dataset refs
- keep internal config-row scoping stable unless a later phase intentionally changes it

## 3. API key model and validation inventory

### File

- `server/src/digdir/config/api_keys.clj`

### Current model

`store-api-key` and `create-api-key!` persist broad, parallel collections:

- `:tenants`
- `:environments`
- `:clients`
- `:pipelines`
- `:skill-graphs`

`validate-api-key` then collapses this into a singular runtime target:

- `:client-id` = first client
- `:pipeline-id` = first pipeline

### Concrete hotspots

- `store-api-key`
- `create-api-key!`
- `validate-api-key`
- `revoke-api-key`
- `list-api-keys`

### Implication for the cut

- replace parallel tenant/environment/pipeline collections with explicit dataset refs
- add explicit agent grants
- stop deriving a singular runtime `:pipeline-id` during validation
- return structured grants instead:
  - `:api-key/agent-refs`
  - `:api-key/dataset-refs`
  - `:api-key/skill-graphs` if retained

## 4. Auth middleware and request context inventory

### File

- `server/src/digdir/api/routes.clj`

### Current request-context contract

Auth validation currently injects pipeline-centric request fields such as:

- `:api-key/pipeline-id`
- `:api-key/client-id`
- `:api-key/skill-graphs`

Downstream handlers assume a single selected pipeline is already present.

### Implication for the cut

- replace singular pipeline context with explicit grants
- attach `:api-key/agent-refs`
- attach `:api-key/dataset-refs`
- keep `:api-key/client-id` only if still justified
- push agent resolution and dataset authorization down to a dedicated policy layer

## 5. Runtime route inventory

### File

- `server/src/digdir/api/routes.clj`

### Shared helper coupling

- `get-pipeline-config`
  - accepts composite `pipeline-id`
  - uses `pipeline/parse-pipeline-id`

### Public chat and retrieval endpoints

These currently depend on `:api-key/pipeline-id`:

- `api-rag-handler`
- `api-retrieve-handler`
- conversation creation paths that default to the API key pipeline

### API key CRUD route coupling

`create-api-key-handler` currently:

- requires request field `pipeline-id`
- validates the pipeline through `get-pipeline-config`
- stores `:pipelines [pipeline-id]`
- returns `pipeline-id` in the response

### Conversation payload coupling

Several handlers still serialize conversation responses with:

- `:pipelineId (:conversation/pipeline-id conversation)`

### Pipeline admin CRUD

These admin handlers still accept composite IDs in the path and parse them:

- `get-pipeline-handler`
- `create-pipeline-handler`
- `update-pipeline-handler`
- `delete-pipeline-handler`
- `run-pipeline-handler`
- `list-pipeline-executions-handler`

### Skill graph execution endpoint

`execute-skill-graph-handler` currently:

- requires `:api-key/pipeline-id`
- resolves config through `get-pipeline-config`
- builds execution opts from the resolved pipeline config
- logs against `pipeline-id`

### Config accessor/debug routes

Config-oriented handlers still document and accept `pipeline-id` as an optional public parameter for config resolution.

### Implication for the cut

- chat and conversation routes become agent-centric
- low-level retrieval/debug routes accept explicit dataset refs
- skill graph execution must authorize through agent refs or dataset refs, not a single pipeline-id
- pipeline admin CRUD may still manage datasets, but should stop using composite IDs as the only transport format

## 6. Pipeline identity helper inventory

### File

- `server/src/digdir/pipeline/core.clj`

### Current helper surface

- `make-pipeline-id`
- `parse-pipeline-id`
- create/update flows returning composite IDs

### Implication for the cut

- remove these helpers from normal request, UI, and persistence flows
- retain parsing only in offline migration tooling if needed
- update create/list/update semantics to pass explicit `{tenant, environment, pipeline}`

## 7. Pipeline execution inventory

### Files

- `server/src/digdir/pipeline/core.clj`
- `server/src/digdir/pipeline/executor.clj`
- `server/src/digdir/pipeline/ui/executions.cljc`
- `server/src/digdir/config/schema.clj`

### Current coupling

Pipeline execution records and UI still key execution history on `pipeline-id`.

### Implication for the cut

- execution records should carry explicit dataset refs
- execution UI should render dataset refs, not composite strings
- this remains compatible with the ADR because a pipeline is still the dataset/materialization unit

## 8. Admin UI inventory

### Files

- `server/src/digdir/config/ui.cljc`
- `server/src/digdir/config/ui/api_keys.cljc`
- `server/src/digdir/config/db.clj`

### Pipeline admin UI

The config UI still uses pipeline IDs throughout:

- create pipeline modal state
- duplicate pipeline modal state
- delete pipeline actions
- pipeline checkbox lists
- pipeline name resolution helpers

### API key UI

The API key modal currently stores and displays pipeline access through `:api-key/pipelines`.

Recent fixes changed this to use fully-qualified composite IDs in the picker to avoid ambiguity, which was a pragmatic stopgap, not the target model.

Concrete current helpers:

- `available-pipeline-ids`
- `pipeline-label`
- API key display tags built from `:api-key/pipelines`
- server-side option loading that uses `pipeline/list-pipelines` plus `pipeline/make-pipeline-id`

### Implication for the cut

- API key UI must move to explicit dataset-ref grants and agent grants
- admin UI should stop passing composite IDs between client and server
- display labels can remain friendly, but state and transport should be structured

## 9. Playground inventory

### Files

- `server/src/digdir/playground/core.cljc`
- `server/src/digdir/playground/ui.cljc`

### Current state

The playground already executes skill graphs, but it still retains pipeline-scoped assumptions.

Relevant current behavior:

- `execute-skills-pipeline`
  - accepts `rag-params`
  - builds collection inputs directly from dataset config
  - selects a skill graph from config
  - executes the graph without a first-class agent definition
- conversation creation still persists `pipeline-id`
- UI state still includes `selected-pipeline-id`
- the playground exposes `:skill-graph` selection but not `agent-id`

### Implication for the cut

- introduce agent selection as the top-level playground identity
- scope conversations to `agent-id`
- treat dataset access as agent policy or explicit dataset-ref context
- keep skill-graph selection only where direct graph testing remains an intentional expert-mode feature

## 10. Skills and agent-layer inventory

### Existing skill graph layer

Files:

- `server/src/digdir/skills/api.clj`
- `server/src/digdir/skills/templates/core.clj`
- `server/src/digdir/skills/templates/builtin.clj`
- `server/src/digdir/skills/builtin/agent.clj`
- `server/src/digdir/skills/context.clj`

### Current state

- builtin skill graphs are registry-backed
- runtime execution already supports graph IDs like `builtin/agent-rag`
- the system has an "agentic" execution style inside skill graphs
- no durable `agent` entity exists yet
- there is no formal policy object binding:
  - instructions
  - guardrails
  - allowed skill graphs
  - allowed dataset refs

### Implication for the cut

- add a first-class agent definition layer in config storage
- bind generic skills and skill graphs behind agent policy
- move conversation identity from pipeline to agent
- keep builtin skill graphs registry-backed for the first cut

## 11. Tooling and operational task inventory

### Files

- `bb.edn`
- `server/src-dev/digdir/tools/config.clj`

### Current state

Many Babashka tasks still require `<tenant> <env> <pipeline-id>` positional arguments or optional `pipeline-id` arguments for diagnostics and config access.

High-volume examples include:

- `chunk`
- `chunk-find`
- `retrieve-debug`
- `rerank-debug`
- `rerank-benchmark`
- `capture-isolation-candidates`
- `capture-rerank-language-candidates`
- `rerank-language-benchmark`
- `agent-budget-benchmark`
- `config-get`
- `config-set`
- `pipeline-config`
- `backfill-schema`

### Implication for the cut

- low-level operational tasks should accept explicit dataset refs
- tasks that are really dataset-materialization operations may still talk about pipelines, but should use explicit parameterization rather than composite IDs

## 12. Export/import and migration inventory

### Files

- `server/src/digdir/config/ops.clj`
- existing JSON export/import artifacts and supporting tests

### Current state

Export/import code already understands config scopes including `pipeline`, and main DB export/import will need to rewrite API key and conversation payloads that currently encode pipeline identity in the old model.

### Implication for the cut

The migration tooling needs to transform at least:

- API key grants
  - from parallel arrays and composite pipeline strings
  - to explicit agent refs and dataset refs
- conversations
  - from `pipeline-id`
  - to `agent-id`
- execution and audit records
  - from singular `pipeline-id`
  - to explicit dataset refs or updated grant metadata

## 13. Test inventory

### High-impact test areas

The following suites contain direct assumptions about composite pipeline identity or pipeline-scoped conversations:

- `server/test/digdir/config/api_keys_test.clj`
- `server/test/digdir/api/routes_test.clj`
- `server/test/digdir/playground/core_test.clj`
- `server/test/digdir/pipeline/core_test.clj`
- `server/test/digdir/pipeline/integration_test.clj`
- `server/test/digdir/test_utils.clj`
- various `server/test/digdir/rag/*` and tooling tests that still pass or assert `pipeline-id`

### Implication for the cut

Testing work must include:

- API key grant roundtrips with structured dataset refs and agent refs
- conversation creation/fetch/list using `agent-id`
- route payload updates from `pipelineId` to agent- and dataset-aware shapes
- export -> transform -> import fixture coverage
- execution/audit assertions that no longer depend on singular `pipeline-id`

## 14. Recommended first implementation slices

Based on the current dependency graph, the cleanest order remains:

1. add config-DB agent entities and main-DB dataset-ref/agent-ref schema
2. refactor API key persistence and validation
3. refactor auth middleware request context
4. refactor conversation persistence to `agent-id`
5. refactor public routes into agent-scoped chat and dataset-scoped low-level access
6. refactor playground selection and persistence
7. refactor admin/API key UI
8. refactor execution/audit/export/import tooling
9. rewrite affected tests and migration fixtures

## Exit criteria for Phase 0

Phase 0 should be considered complete when this inventory is stable enough that an implementer can answer, before coding:

- where composite `pipeline-id` still enters the system
- where it is persisted
- where conversation identity is still pipeline-scoped
- which existing skill-graph surfaces already exist
- which files will need new first-class agent definitions

This document is intended to satisfy that requirement.

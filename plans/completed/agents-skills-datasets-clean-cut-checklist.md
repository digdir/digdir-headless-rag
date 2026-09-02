# Agents, Skills, and Datasets Clean-Cut Checklist

## Recommended Decisions

### 1. Store agent definitions in the config DB

Decision:

- store agent definitions in the config DB as dedicated entities

Why:

- agents are durable operational configuration
- they bind datasets, skill graphs, and guardrails
- they should be exportable/importable with the rest of environment configuration
- they belong closer to config-managed policy than to conversation history

Not chosen:

- main DB storage for agents

Reason:

- that would split runtime policy from the existing environment-scoped configuration system
- it complicates export/import cutover

### 2. API keys should support both agent refs and dataset refs

Decision:

- persist both `agent` grants and explicit dataset-ref grants

Use:

- conversational/chat APIs: agent grants
- low-level retrieval/diagnostic APIs: dataset-ref grants

Why:

- public/product flows should be agent-centric
- low-level operational/debug use cases still need direct dataset access
- this avoids overloading agents as the only authorization surface

Constraint:

- do not infer dataset access from separate tenant/environment/pipeline arrays

### 3. Keep dataset usage traces lightweight in the first cut

Decision:

- persist `agent-id` as first-class conversation identity
- keep used dataset refs in diagnostics/execution metadata in the first cut
- add normalized dataset-usage entities only if post-cut reporting needs require them

Why:

- minimizes schema and migration complexity
- preserves the clean conceptual boundary
- avoids over-designing trace storage before concrete query/reporting requirements are known

### 4. Keep builtin skill graphs registry-backed in the first cut

Decision:

- durable agent definitions may reference skill graph IDs
- skill graphs themselves remain registry/code-backed for the first cut

Why:

- reduces migration scope
- avoids introducing two large storage refactors at once
- preserves current working skill graph execution path

Follow-up:

- durable user-managed skill graph storage can be a later phase

## Implementation Order

## Phase 0: branch setup and migration inventory

- [x] Create a dedicated clean-cut branch.
- [x] Inventory every runtime use of composite `pipeline-id`.
- [x] Inventory every persisted field that stores pipeline identity.
- [x] Inventory every route that reads or writes pipeline identity.
- [x] Inventory every UI component that stores or renders pipeline identity.
- [x] Inventory every export/import path involved in the cut.

Acceptance:

- [x] Inventory committed or appended to the plan.

## Phase 1: new schema foundation

### Config DB

- [x] Add agent schema to [schema.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/schema.clj).
- [x] Add agent dataset-ref entity attributes.
- [x] Add agent guardrail/configuration attributes.

### Main DB

- [x] Add API key agent-ref entity attributes to [db.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/data/db.cljc).
- [x] Add API key dataset-ref entity attributes to [db.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/data/db.cljc).
- [x] Add `:conversation/agent-id` to [db.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/data/db.cljc).
- [x] Stop planning around `:conversation/pipeline-id` as a target field.

Acceptance:

- [x] Fresh DB boots with new schema.
- [x] Schema-related tests pass.

## Phase 2: agent definition layer

- [x] Add `server/src/digdir/agents/core.clj`.
- [x] Add `server/src/digdir/agents/db.clj`.
- [x] Add `server/src/digdir/agents/policy.clj`.
- [x] Implement agent validation.
- [x] Implement agent loading/listing APIs.
- [x] Seed builtin agents for current product surfaces.

Acceptance:

- [x] Agents can be created/loaded/listed from durable storage.
- [x] Builtin seeded agents resolve expected dataset refs and skill graph IDs.

## Phase 3: API key data model refactor

- [x] Replace old API key tenant/environment/pipeline arrays in [db.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/data/db.cljc).
- [x] Refactor [api_keys.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/api_keys.clj) to store agent refs and dataset refs.
- [x] Refactor API key validation to return explicit grants.
- [x] Refactor audit logging to stop emitting singular pipeline-id semantics.
- [x] Update API key listing/get-info whom-functions.

Acceptance:

- [x] API key create/list/validate tests pass with the new model.
- [x] No runtime path depends on `:api-key/pipeline-id`.

## Phase 4: auth middleware and request context

- [x] Update `wrap-api-key-auth` in [routes.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/api/routes.clj).
- [x] Attach `:api-key/agent-refs`.
- [x] Attach `:api-key/dataset-refs`.
- [x] Keep `:api-key/client-id` if still needed.
- [x] Update downstream handlers to consume explicit grants.

Acceptance:

- [x] Request context no longer contains singular composite pipeline identity.

## Phase 5: conversation persistence refactor

- [x] Replace conversation pipeline scope with `agent-id` in [db.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/data/db.cljc).
- [x] Update `create-playground-conversation`.
- [x] Update standard conversation creation paths.
- [x] Update conversation list/fetch queries to pull `:conversation/agent-id`.
- [x] Update any response JSON still returning `pipelineId`.

Acceptance:

- [x] New conversations persist `agent-id`.
- [x] Conversation fetch/list endpoints no longer depend on `pipeline-id`.

## Phase 6: runtime flow refactor

### Chat/RAG flow

- [x] Refactor chat/RAG routes in [routes.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/api/routes.clj) to be agent-centric.
- [x] Resolve selected/default agent from API key grants.
- [x] Resolve allowed datasets through the agent policy layer.

### Retrieval/diagnostic flow

- [x] Refactor low-level retrieval/debug APIs to require explicit dataset refs.
- [x] Remove composite pipeline-id request/response semantics from these APIs.

Acceptance:

- [x] Public conversational flows are agent-scoped.
- [x] Low-level data access flows are dataset-ref-scoped.

## Phase 7: skill execution context

- [x] Refactor [context.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/context.clj) to accept `agent-id` and dataset refs.
- [x] Refactor [api.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/api.clj) to pass explicit dataset refs.
- [x] Update retrieval-oriented skills to accept structured dataset refs.
- [x] Preserve generic skill semantics.

Acceptance:

- [x] Skills execute without composite pipeline IDs.
- [x] Retrieval skills operate over explicit dataset refs.

## Phase 8: agent-bound tool surface

- [x] Introduce a way for agents to expose dataset-bound tool bindings.
- [x] Keep internal skills generic.
- [x] Add agent-visible aliases like `query_altinn_docs` only as bindings, not primitive skill implementations.

Acceptance:

- [x] Agent tool definitions reflect allowed datasets without duplicating retrieval logic.

## Phase 9: UI refactor

### API keys UI

- [x] Refactor [api_keys.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/ui/api_keys.cljc) to show/edit:
  - [x] agent grants
  - [x] dataset-ref grants
- [x] Remove legacy tenant/environment/pipeline parallel selection semantics.

### Playground UI

- [x] Refactor [ui.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/playground/ui.cljc) to select agents first.
- [x] Show allowed datasets as agent context, not as conversation identity.
- [x] Remove pipeline-scoped conversation assumptions.

### Config/admin UI

- [x] Update labels/copy so pipelines are treated as datasets/materialization definitions.

Acceptance:

- [x] No UI component uses composite pipeline IDs as canonical state.
- [x] Playground sessions are clearly agent-scoped.

## Phase 10: export, transform, import

- [x] Introduce a full-system export command/tool.
- [x] Export config DB and main DB state needed for cutover.
- [x] Add export version `2.0`.
- [x] Implement offline transform tool:
  - [x] convert pipeline IDs to dataset refs
  - [x] convert API key grants
  - [x] convert conversations from pipeline scope to agent scope
  - [x] produce migration report
- [x] Implement import path for fresh DB.

Acceptance:

- [x] Export -> transform -> import succeeds on test fixtures.
- [x] Migration report contains expected counts and no malformed IDs.

## Phase 11: tests

- [x] Update API key tests.
- [x] Update route tests.
- [x] Update conversation persistence tests.
- [x] Update playground tests.
- [x] Add agent CRUD/load tests.
- [x] Add migration roundtrip tests.
- [x] Add smoke/integration tests for:
  - [x] agent-scoped chat
  - [x] dataset-ref-scoped retrieval

Acceptance:

- [x] Updated targeted test namespaces pass.

## Phase 12: documentation and tooling cleanup

- [x] Update architecture docs to align with the ADR.
- [x] Update `bb.edn` debug/tasks away from composite pipeline-id inputs where applicable.
- [x] Remove dead composite parsing/building helpers from runtime code.

Acceptance:

- [x] Documentation no longer presents composite pipeline IDs as the public model.

## Migration Readiness Checklist

Primary runbook:

- [agents-skills-datasets-cutover-runbook.md](/Users/bdbrodie/dev/digdir/rag/docs/runbooks/agents-skills-datasets-cutover-runbook.md)

- [x] Builtin agents defined and seeded.
- [x] API key validation works with new grant model.
- [x] Chat routes resolve agents correctly.
- [x] Retrieval routes resolve dataset refs correctly.
- [x] Playground creates agent-scoped conversations.
- [x] Export/transform/import path passes dry-run verification.
- [x] Old DB backup and raw exports are archived.

## Cutover Checklist

- [x] Freeze writes.
- [x] Export old config DB.
- [x] Export old main DB.
- [x] Checksum and archive both exports.
- [x] Run transform tool.
- [x] Create fresh databases.
- [x] Apply new schemas.
- [x] Import transformed data.
- [x] Deploy refactored application.
- [x] Run manual smoke checks:
  - [x] list agents
  - [x] create API key
  - [x] run chat against agent
  - [x] run retrieval against dataset ref
  - [x] open playground conversation
- [x] Unfreeze writes.

## Rollback Checklist

- [ ] Stop new application.
- [ ] Restore old DB snapshots.
- [ ] Redeploy old application.
- [ ] Verify legacy chat flow.
- [ ] Verify legacy retrieval flow.
- [ ] Verify admin login and API key validation.

## Notes

### Keep

- `config/id` may remain composite if it is purely an internal config-row uniqueness key.

### Remove from runtime/public model

- composite `pipeline-id`
- conversation-scoped pipeline identity
- API key tenant/environment/pipeline parallel arrays

### Defer

- durable user-managed skill graph storage
- normalized dataset-usage entities unless reporting needs force them

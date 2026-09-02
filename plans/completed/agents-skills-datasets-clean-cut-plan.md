# Agents, Skills, and Datasets Clean-Cut Implementation Plan

## Context

The codebase is in the middle of two related transitions:

1. from pipeline-centric runtime APIs toward skill-based execution
2. from single-pipeline conversational assumptions toward agentic, multi-dataset behavior

The new ADR in [agents-skills-and-datasets.md](/Users/bdbrodie/dev/digdir/rag/decisions/agents-skills-and-datasets.md) defines the target model:

- pipelines are dataset materialization definitions
- dataset access is represented explicitly as `{tenant, dataset-config-key}`
- skills remain generic capabilities
- agents become first-class definitions with policy, access, and guardrails
- conversations are scoped to agents, not pipelines

This plan turns that model into a concrete clean-cut refactor and migration sequence.

## Assumptions

### In scope

- clean-cut schema and API refactor
- offline export -> transform -> fresh DB -> import migration
- durable agent definitions
- explicit dataset refs in persistence and runtime APIs
- conversation scope migration from pipeline to agent
- UI updates for API keys and playground/admin flows

### Out of scope

- renaming all `pipeline` namespaces
- changing the internal config value uniqueness strategy if `:config/id` remains internal-only
- fully general user-authored skill graph CRUD unless required by the agent model
- dual-runtime compatibility beyond the offline transform/import boundary

### Pragmatic assumption

Builtin skill graphs remain registry-driven during the first cut. Agent definitions reference skill graph IDs by durable identifier, but skill graphs themselves can remain partly code-backed while agent definitions become durable.

## Target Model

## 1. Dataset refs

Canonical dataset ref:

```clojure
{:tenant "altinn-docs"
 :dataset-config-key "assistant"}
```

Use this shape everywhere a concrete dataset target is selected or granted.

### Explicitly rejected

- parallel collections of tenants/environments/pipelines
- composite `tenant:env:pipeline` as the public/runtime identity model

## 2. Agents

Introduce a first-class agent definition model.

An agent definition includes:

- `:agent/id`
- `:agent/name`
- `:agent/description`
- `:agent/instructions`
- `:agent/default-skill-graph`
- `:agent/allowed-skill-graphs`
- `:agent/allowed-dataset-refs`
- `:agent/guardrails`
- `:agent/enabled?`
- optional UI metadata

### Recommended storage

Store agent definitions in the config DB as dedicated entities, not as ordinary config path/value pairs.

Reason:

- agents are configuration, but not scalar config values
- they are structured, durable, and enumerable
- they need references to multiple datasets and graphs
- they are a better fit as entities than as flattened path/value config rows

## 3. API keys

Split API key authorization into two distinct models:

### Query/chat keys

Primary public conversational flow should be **agent-scoped**.

Query API keys should grant:

- allowed agents
- optional allowed skill graphs if low-level graph execution remains public

### Low-level dataset access keys

If the system continues to expose direct dataset-level retrieval/query APIs, allow explicit dataset refs on API keys as a separate grant model.

### Recommended initial cut

Support both:

- `:api-key/agent-refs`
- `:api-key/dataset-refs`

but make the main chat/RAG flow use agent refs.

This avoids blocking low-level retrieval APIs while moving the user-facing model to agents.

## 4. Conversations

Conversations should persist:

- `:conversation/agent-id`
- optional `:conversation/tenant`
- conversation metadata as today

They should no longer persist a single legacy pipeline identity field.

Dataset usage should instead be attached to:

- per-message diagnostics
- execution records
- explicit used-dataset traces

## 5. Skill execution context

Skill context should accept:

- `:tenant`
- `:dataset-ref` or `:dataset-refs`
- `:agent-id`
- `:skill-graph-id`

Generic retrieval skills should operate over explicit dataset refs.

## 6. Tool surface

Maintain a separation between:

- **generic skills** used internally
- **agent-exposed tools** which may be dataset-bound aliases

Example:

- internal skill: `:builtin/retrieval`
- agent-visible tool binding: `query_altinn_docs`

The binding layer should sit in the agent/tool exposure model, not in the primitive skill implementation.

## Schema Plan

## A. Main DB (`server/src/digdir/data/db.cljc`)

### Add

API key ref entities:

- `:api-key.dataset-ref/id` if needed
- `:api-key.dataset-ref/tenant`
- `:api-key.dataset-ref/dataset-config-key`
- `:api-key/dataset-refs` ref many

- `:api-key.agent-ref/id` if needed
- `:api-key.agent-ref/agent-id`
- `:api-key/agent-refs` ref many

Conversation fields:

- `:conversation/agent-id`

Optional usage trace entities:

- `:message.used-dataset-refs` ref many
- `:message.dataset-ref/tenant`
- `:message.dataset-ref/dataset-config-key`

### Remove / stop reading

- `:api-key/tenants`
- `:api-key/environments`
- `:api-key/pipelines`
- `:conversation/legacy-pipeline-identity`

### Keep only if justified

- `:conversation/tenant`

These can remain if they are useful for agent/config resolution defaults.

## B. Config DB (`server/src/digdir/config/schema.clj`)

### Add agent entities

- `:agent/id`
- `:agent/name`
- `:agent/description`
- `:agent/instructions`
- `:agent/default-skill-graph`
- `:agent/allowed-skill-graphs`
- `:agent/guardrails`
- `:agent/enabled?`

Agent dataset ref entities:

- `:agent.dataset-ref/tenant`
- `:agent.dataset-ref/dataset-config-key`
- `:agent/allowed-dataset-refs`

Optional agent tool bindings:

- `:agent.tool-binding/id`
- `:agent.tool-binding/name`
- `:agent.tool-binding/skill-id`
- `:agent.tool-binding/dataset-tenant`
- `:agent.tool-binding/dataset-config-key`
- `:agent/allowed-tool-bindings`

### Keep as internal implementation detail

`config/id` may remain composite if it stays an internal uniqueness key for config value rows only.

## Export / Transform / Import Plan

## Export version

Bump config export version from `1.0` to `2.0` in [ops.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/ops.clj).

## Export shape changes

### Replace composite references

Transform these old shapes:

- API key legacy pipeline identity strings
- conversation legacy pipeline identity
- audit legacy pipeline identity
- execution legacy pipeline identity payloads if exported
- legacy dataset tuple payloads

into explicit structured refs.

### Add durable agent exports

Export agent entities separately from scalar config values.

Recommended export top-level shape:

```clojure
{:version "2.0"
 :data {:definitions [...]
        :values [...]
        :agents [...]
        :api-keys [...]
        :conversations [...]
        :audit [...]}}
```

If conversations/API keys remain in the main DB and config exports are config-only, then create a separate full-system export tool rather than overloading current config export semantics.

### Recommended implementation decision

Do not force non-config entities into the existing config export format.

Instead, introduce a **system export/import tool** that orchestrates:

- config DB export/import
- main DB export/import

The current [ops.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/ops.clj) can remain the config export layer, but the cutover should be driven by a new top-level migration tool.

## Offline transform tool

Create a dedicated transform tool with these responsibilities:

1. read old config export JSON
2. read old main DB export JSON if conversations/API keys are exported separately
3. rewrite composite legacy pipeline identities into explicit dataset refs
4. rewrite old API key scope model
5. rewrite legacy conversation pipeline scope to agent scope
6. emit a migration report

### Required report contents

- total API keys transformed
- total dataset refs created
- total conversations transformed
- total composite IDs parsed
- malformed IDs encountered
- fields dropped
- defaults applied

### Strictness

Fail hard on malformed composite IDs.

Do not silently coerce.

## Agent migration strategy

There is no existing durable agent entity model, so the transform must define how legacy pipeline-scoped conversations map to agents.

### Recommended first-cut rule

Create a deterministic default agent per legacy pipeline-backed product surface.

For existing conversational flows:

- map legacy pipeline-backed chat flows to one default agent per old pipeline surface
- seed builtin agents for known current usage

Examples:

- `agent/altinn-docs-assistant`
- `agent/ka-kudos-assistant`

This is an acceptable transitional mapping for the fresh DB import because the target model is changing the product boundary.

## Runtime Refactor Workstreams

## 1. API key model and middleware

Files:

- [api_keys.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/api_keys.clj)
- [data/db.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/data/db.cljc)
- [routes.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/api/routes.clj)

Changes:

- replace legacy API key pipeline identity fields with explicit agent and/or dataset grants
- `validate-api-key` returns:
  - `:agent-refs`
  - `:dataset-refs`
  - `:skill-graphs`
- `wrap-api-key-auth` attaches explicit auth context to the request

Recommended request context:

```clojure
{:api-key/agent-refs [...]
 :api-key/dataset-refs [...]
 :api-key/skill-graphs [...]
 :api-key/client-id ...}
```

## 2. Agent definition layer

Add:

- `server/src/digdir/agents/core.clj`
- `server/src/digdir/agents/db.clj`
- `server/src/digdir/agents/policy.clj`

Responsibilities:

- CRUD/load agent definitions
- resolve allowed datasets
- resolve allowed skill graphs
- expose agent-visible tool bindings
- validate agent definitions

## 3. Skill execution context

Files:

- [skills/context.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/context.clj)
- [skills/api.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/api.clj)

Changes:

- accept explicit dataset refs rather than implicit pipeline identity
- include `:agent-id` in execution context
- pass explicit dataset refs to retrieval-oriented skills

## 4. Retrieval skill surface

Files:

- builtin retrieval/rerank/synthesis skills
- graph runner

Changes:

- retrieval skill inputs should accept one or more dataset refs
- skill graphs that are dataset-dependent should receive dataset refs explicitly
- keep retrieval generic

## 5. Routes/API surface

Files:

- [routes.clj](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/api/routes.clj)

Changes:

### Public conversational API

Move from:

- pipeline-selected flow

to:

- agent-selected flow

Recommended request model:

```json
{"agent":"altinn-docs-assistant","query":"..."}
```

or key-defaulted agent if only one is allowed.

### Low-level retrieval/graph APIs

Require explicit dataset refs:

```json
{"dataset":{"tenant":"...","dataset-config-key":"..."},"query":"..."}
```

Do not accept composite IDs.

## 6. Conversation persistence

Files:

- [data/db.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/data/db.cljc)
- [playground/core.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/playground/core.cljc)
- [playground/ui.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/playground/ui.cljc)

Changes:

- create conversations with `agent-id`
- stop storing conversation legacy pipeline identity
- store used dataset refs in diagnostics or usage refs per turn

Recommended playground behavior:

- select agent first
- derive allowed datasets from the selected agent
- optionally allow agent-specific dataset overrides only if the agent policy allows it

## UI Workstreams

## 1. API key UI

Files:

- [config/ui/api_keys.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/ui/api_keys.cljc)

Target:

- show/query agents for conversational keys
- show explicit dataset refs only where needed for low-level dataset access
- no composite ID internal state

## 2. Pipeline admin UI

Files:

- [config/ui.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/config/ui.cljc)

Target:

- keep dataset materialization management here
- clarify naming in UI copy that these are datasets/materialization definitions

## 3. Playground UI

Files:

- [playground/ui.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/playground/ui.cljc)

Target:

- conversations scoped to agent
- skill graph becomes a lower-level execution detail unless explicitly surfaced for debugging
- pipeline selector replaced with agent selector for ordinary usage

## Ordered Implementation Phases

## Phase 0: prep and inventory

1. Freeze target architecture in ADR.
2. Inventory all composite legacy pipeline identity usage.
3. Inventory all persistence fields carrying legacy pipeline identity.
4. Inventory all APIs that currently require or return legacy pipeline identity.

Acceptance:

- exhaustive migration inventory checked into the plan or a checklist

## Phase 1: schema foundation

1. Add agent schema to config DB.
2. Add dataset-ref and agent-ref entities to main DB for API keys.
3. Add `conversation/agent-id`.
4. Add optional per-message/per-execution dataset usage entities.

Acceptance:

- new empty DB can boot with new schema
- schema tests pass

## Phase 2: durable agent layer

1. Introduce agent CRUD/load layer.
2. Seed builtin agents matching current product use cases.
3. Add validation for:
   - allowed skill graphs
   - allowed dataset refs
   - tool bindings

Acceptance:

- builtin agents can be listed and loaded durably

## Phase 3: API key model refactor

1. Replace old API key scope fields in code.
2. Update storage and validation.
3. Update auth middleware.
4. Update audit logging.

Acceptance:

- API key roundtrip tests pass with agent refs and/or dataset refs

## Phase 4: runtime and route refactor

1. Refactor public chat/RAG flows to use `agent-id`.
2. Refactor low-level retrieval/graph flows to use explicit dataset refs.
3. Remove runtime composite legacy pipeline identity parsing.

Acceptance:

- no public route depends on composite legacy pipeline identities

## Phase 5: conversation and playground refactor

1. Persist conversation `agent-id`.
2. Remove conversation legacy pipeline identity reads/writes.
3. Update playground creation/execution flow.
4. Record used dataset refs in diagnostics/traces.

Acceptance:

- playground can create and continue agent-scoped conversations

## Phase 6: export/transform/import tooling

1. Implement full-system export.
2. Implement transform tool from old model to new.
3. Implement fresh import into new DB.
4. Add roundtrip tests.

Acceptance:

- export -> transform -> import works on test fixtures

## Phase 7: UI cutover

1. Update API key modal and tables.
2. Update playground selectors.
3. Update admin copy and labels.

Acceptance:

- no UI stores or displays composite IDs as canonical state

## Phase 8: docs and cleanup

1. Update architecture docs.
2. Update CLI/debug tooling.
3. Remove dead composite-ID helpers from runtime code.

Acceptance:

- repo no longer documents composite pipeline identities as the public model

## Verification Plan

## Automated

Add tests for:

- agent CRUD/load
- API key validation with agent refs
- API key validation with dataset refs
- route auth context
- conversation persistence with `agent-id`
- retrieval execution with explicit dataset refs
- export -> transform -> import roundtrip
- migrated fixture equivalence

## Manual smoke tests

1. Create agent definitions.
2. Create API key for one or more agents.
3. Run chat flow against selected agent.
4. Verify conversation shows correct agent and used datasets.
5. Run low-level retrieval against explicit dataset ref.
6. Verify playground sessions are agent-scoped.

## Migration verification

Before cut:

- count API keys
- count conversations
- count playground conversations
- count audit entries
- snapshot representative records

After import:

- counts match expected transformed totals
- no missing agent refs
- no malformed dataset refs
- sample conversations resolve expected agent
- sample dataset usage traces look correct

## Cutover Runbook

1. Freeze writes.
2. Export config DB and main DB.
3. Archive raw exports with checksums.
4. Run transform tool to new format.
5. Create fresh databases.
6. Apply new schemas.
7. Import transformed data.
8. Deploy refactored app.
9. Run smoke tests:
   - agent listing
   - API key auth
   - chat flow
   - retrieval flow
   - playground flow
10. Unfreeze writes.

## Rollback Strategy

Rollback is deployment-wide, not mixed-mode.

Keep:

- original DB snapshots
- original raw exports
- transformed exports
- previous application artifact

Rollback steps:

1. stop new app
2. restore old DB snapshots
3. redeploy old app
4. verify old chat and retrieval flows

Do not attempt to run old app against the new schema.

## Adopted Recommendations

## 1. Store agent definitions in the config DB

Adopted:

- config DB as dedicated entities

Reason:

- agents are durable deployment-scoped operational policy
- they belong with exportable/importable configuration

## 2. API keys grant both agents and dataset refs

Adopted:

- both, with different intended use

Use:

- public conversational APIs: agents
- low-level retrieval/debug flows: dataset refs

## 3. Keep dataset usage traces lightweight in the first cut

Adopted:

- persist `agent-id` as first-class conversation identity
- keep used dataset refs in diagnostics/execution metadata in the first cut
- defer normalized usage entities unless reporting requirements justify them

## 4. Keep builtin skill graphs registry-backed in the first cut

Adopted:

- skill graphs may remain code/registry-backed
- agent definitions reference durable graph IDs

Not part of this cut:

- full durable user-managed skill graph storage

## Recommended First Execution Slice

If implemented incrementally inside the clean-cut branch, the highest-value order is:

1. finalize agent storage decision
2. build new schema
3. build agent layer
4. refactor API keys and auth context
5. refactor conversations to `agent-id`
6. refactor public routes to agent-centric flow
7. build export/transform/import tooling
8. update UI
9. run dry migration
10. cut over

# Agents, Skills, and Datasets Clean-Cut Refactor Prompt

You are planning a clean-cut architectural refactor for this codebase.

The architecture decision has already been made:

- pipelines are the system's dataset materialization definitions
- concrete dataset access is modeled via explicit structured refs:

```clojure
{:tenant "altinn-docs"
 :environment "dev"
 :pipeline "assistant"}
```

- skills are generic capabilities
- skill graphs are reusable workflows over skills
- agents are first-class policy-governed actors with agency
- conversations should be scoped to agents, not to pipelines

Your task is to produce a repo-aware implementation plan for this refactor.

## Primary Goal

Design a clean-cut, end-to-end migration that:

1. removes composite `pipeline-id` as a public/runtime identity
2. replaces pipeline access with explicit dataset refs
3. introduces first-class agent definitions
4. moves conversation scope from pipeline to agent
5. preserves the distinction between:
   - datasets/pipelines
   - skills and skill graphs
   - agents
6. performs the change via:
   - export current database contents
   - transform exported data
   - create fresh database/schema
   - reimport transformed data

Do not optimize for backward runtime compatibility. This is a clean cut.

## Conceptual Model

### Dataset / pipeline

Treat pipelines as dataset materialization definitions. They define:

- source
- ingestion/materialization
- persistent retrieval substrate
- dataset defaults used by retrieval-related capabilities

### Skill

Treat skills as generic capabilities. Examples:

- retrieve
- rerank
- synthesize
- summarize
- fact-check
- plan

Do not redesign the system so that dataset-specific operations like `query_altinn_docs` become the primitive skill abstraction.

Instead:

- keep generic internal skills
- optionally allow dataset-bound tool aliases at the agent/tool surface

### Agent

Treat an agent as a first-class definition that includes:

- purpose
- behavioral spec / instructions
- allowed skills and/or skill graphs
- allowed dataset refs
- guardrails
- answer policy
- fallback/escalation policy
- optional memory policy

Agents have agency. Skills do not.

### Conversation

Conversations must be scoped to an agent, not a pipeline.

The system should be able to record which dataset refs were used during turns or executions, but conversation identity itself should not assume a single pipeline target.

## Required Design Outcomes

### 1. Explicit dataset refs everywhere a concrete dataset is referenced

The canonical model must be a structured tuple:

```clojure
{:tenant "..."
 :environment "..."
 :pipeline "..."}
```

For multi-target access, use a collection of such tuples.

Do not use:

- parallel arrays/sets of tenants, environments, pipelines
- composite string IDs as the canonical application-facing model

### 2. First-class agent definitions

Add a durable agent definition model and identify:

- where it lives in config/storage
- how it is loaded
- how it binds skills, graphs, datasets, and guardrails

### 3. Agent-scoped conversations

Refactor persistence and runtime so conversations are scoped to agents.

Define how dataset usage is recorded separately from conversation identity.

### 4. Generic skills, optional dataset-bound tool aliases

Clarify the architecture for:

- internal generic retrieval skills
- optional agent-visible wrappers like `query_altinn_docs`

The plan must preserve generic skill composition while allowing a cleaner tool surface for agents where useful.

## Migration Strategy

The plan must assume a clean-cut migration:

1. export old DB contents
2. transform old exported data into the new shape
3. create a fresh DB with the new schema
4. import transformed data
5. deploy the new app against the new DB

Avoid runtime dual-mode compatibility unless strictly necessary for the offline transform/import path.

## Required Deliverables

Produce a detailed implementation plan covering:

1. target domain model
2. target schema changes
3. code area inventory
4. export format redesign
5. offline transform strategy
6. import/bootstrap strategy for a fresh DB
7. runtime/API refactor sequence
8. UI refactor sequence
9. agent definition rollout
10. conversation migration
11. verification plan
12. cutover runbook
13. rollback strategy

The plan should be concrete and execution-oriented, not just conceptual.

## Questions The Plan Must Answer

### Dataset refs

1. How exactly are explicit dataset refs stored in Datahike?
2. Which old fields are removed or replaced?
3. Where, if anywhere, may composite IDs remain as purely internal implementation details?

### Agents

4. What is the persisted shape of an agent definition?
5. How are allowed datasets attached to an agent?
6. How are allowed skills or skill graphs attached to an agent?
7. How are guardrails represented?

### Conversations and execution

8. How do conversations reference agents?
9. How is per-turn or per-execution dataset usage recorded?
10. How are execution traces updated to reflect the new model?

### Skills and tool surface

11. How should generic retrieval skills accept dataset refs?
12. Where should dataset-bound aliases like `query_altinn_docs` live, if they exist?
13. How should the model distinguish between a generic skill and an agent-exposed tool binding?

### Migration

14. How will old pipeline-scoped conversation and API key data be transformed?
15. How will old exports be rewritten?
16. What must be verified before and after import?

## Code Areas To Inspect

At minimum, account for these:

- `decisions/skill-based-agentic-rag.md`
- `decisions/agents-skills-and-datasets.md`
- `docs/architecture/skill-based-agentic-rag.md`
- `docs/architecture/skills-pipeline-plan.md`
- `server/src/digdir/config/schema.clj`
- `server/src/digdir/data/db.cljc`
- `server/src/digdir/config/db.clj`
- `server/src/digdir/config/api_keys.clj`
- `server/src/digdir/config/ops.clj`
- `server/src/digdir/api/routes.clj`
- `server/src/digdir/pipeline/core.clj`
- `server/src/digdir/skills/api.clj`
- `server/src/digdir/skills/context.clj`
- `server/src/digdir/skills/templates/`
- `server/src/digdir/config/ui/api_keys.cljc`
- `server/src/digdir/config/ui.cljc`
- `server/src/digdir/playground/ui.cljc`
- `bb.edn`
- relevant tests under `server/test`
- current export JSON in `config-defs/`

## Constraints

- Prefer explicit structure over string encoding.
- Prefer one canonical representation per concept.
- Keep skills generic.
- Make agents first-class.
- Do not scope conversations to datasets in the target model.
- Favor a coherent end state over preserving short-term convenience.

## Expected Output Format

Write the plan as an engineering plan with:

- context
- assumptions
- target model
- workstreams
- ordered implementation phases
- verification
- cutover
- rollback

Call out unresolved choices explicitly.

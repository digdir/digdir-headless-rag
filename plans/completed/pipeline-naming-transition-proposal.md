# Pipeline Naming and Transition Proposal

## Goal
Reserve “pipeline” for materialization/ingestion (stateful, expensive compute), and use a distinct term for runtime execution of skills.

## Proposed Terms
- Pipeline: a specific configuration for how to materialize a dataset for retrieval purposes.
- Materialized view: stored, expensive artifacts produced by pipelines.
- Skill definition: data describing a skill’s inputs, outputs, parameters, tool definition, version, and tags.
- Skill implementation: code that executes a skill definition.
- Skill graph: a directed acyclic graph of skills; the governed, callable unit of runtime execution (replaces “workflow” and “template”).
- API key: provides access to pipelines and skill graphs, with optional filters for tenant, environment, pipeline, and skill graph.

## Code/Data Boundary

### Skill
- Code: skill implementation (logic, validation, side effects, service clients).
- Data: skill definition (inputs/outputs/parameters, tool definition, version, tags).

### Skill graph
- Data: step DAG, input/output wiring, static parameters, conditions, on-error policy, version/tags.
- Code: graph runner, validation semantics, execution scheduling, error handling.

### Execution scope
- Data: resolved parameters + tenant/env scope + ACL/policy + audit metadata.
- Code: resolution rules (config inheritance), policy enforcement, execution entrypoint.

### Guiding Rule
- If a concern changes per tenant/env or per customer, it should usually be data.
- If it affects system safety, correctness, or side effects, it should remain code-controlled.

## Phased Transition
1. Phase 0 (now): Update docs to introduce the split. Start using “skills workflow” in new documentation and comments.
2. Phase 1: Add aliases in UI and API. Example UI labels: “Pipelines (Ingestion)” and “Skill Graphs (Runtime)”.
3. Phase 2: Migrate code references and API names where feasible. Keep backwards-compatible routes and config paths.
4. Phase 3: Remove legacy naming where safe, retain migration notes in docs.

## Renaming Checklist

### Code
- Update module names/comments that refer to runtime execution as “pipeline”:
  - server/src/digdir/rag/core.cljc comments and public function docstrings.
  - server/src/digdir/playground/core.cljc logs, status labels, and comments.
  - server/src/digdir/pipeline/skills/* docstrings if they refer to “pipeline execution” meaning runtime.
- Introduce explicit naming in skills graph runner:
  - server/src/digdir/pipeline/skills/graph/runner.clj docstrings should say “skills workflow” or “skill graph”.
- Decide on public API aliases:
  - Keep existing endpoints but add new ones with skills-workflows or skill-graphs naming.
- Audit config paths:
  - Avoid new pipeline.* paths for runtime behavior. Use skills.* or workflow.*.

### Docs
- Add a short glossary:
  - ARCHITECTURE_DECISION.md
  - SKILLS_README.md
- Update conceptual diagrams and terminology:
  - docs/architecture/skills-pipeline-plan.md
  - SKILLS_README.md
- Add a migration note explaining the naming split and why.

### UI
- Rename tabs/labels:
  - server/src/digdir/config/ui.cljc
  - server/src/digdir/pipeline/ui/skills.cljc
- Update copy in tooltips and descriptions to clarify “pipeline = ingestion”.

### API
- Add new endpoints or route aliases:
  - server/src/digdir/api/routes.clj with redirect or alias for runtime workflow execution.
- Ensure response payloads avoid the term “pipeline” for runtime where possible.

### Tests
- Update test names and descriptions:
  - server/test/digdir/rag/skills/*
  - server/test/digdir/pipeline/skills/*
- Keep legacy compatibility tests if endpoints are aliased.

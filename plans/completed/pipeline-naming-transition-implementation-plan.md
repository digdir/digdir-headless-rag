# Pipeline Naming Transition Implementation Plan

## Objective
Separate “pipeline” (materialization/ingestion) from runtime skills execution, and formalize skill graphs as the API-facing unit. This plan is structured for parallel workstreams with clear boundaries and integration points.

## Terminology
- Pipeline: a specific configuration for how to materialize a dataset for retrieval purposes.
- Materialized view: stored, expensive artifacts produced by pipelines.
- Skill definition: data describing a skill’s inputs, outputs, parameters, tool definition, version, and tags.
- Skill implementation: code that executes a skill definition.
- Skill graph: a directed acyclic graph of skills; the governed, callable unit of runtime execution.
- API key: provides access to pipelines and skill graphs, with optional filters for tenant, environment, pipeline, and skill graph.

## Code/Data Boundary
- Skill (definition vs implementation):
  - Data: skill definition (inputs/outputs/parameters, tool definition, version, tags).
  - Code: skill implementation (logic, validation, side effects, service clients).
- Skill graph:
  - Data: step DAG, input/output wiring, static parameters, conditions, on-error policy, version/tags.
  - Code: graph runner, validation semantics, execution scheduling, error handling.
- Execution scope:
  - Data: resolved parameters + tenant/env scope + ACL/policy + audit metadata.
  - Code: resolution rules (config inheritance), policy enforcement, execution entrypoint.

Guiding rule:
- If a concern changes per tenant/env or per customer, it should usually be data.
- If it affects system safety, correctness, or side effects, it should remain code-controlled.

## Workstreams (Parallelizable)

### Track A: Terminology + Docs Alignment (Docs Team)
**Scope**: Update conceptual docs and onboarding materials to reflect the new naming split.

Steps
1. Add a glossary and naming split overview to the following:
   - ARCHITECTURE_DECISION.md
   - SKILLS_README.md
   - docs/architecture/skills-pipeline-plan.md
2. Update references to “pipeline” that mean runtime execution to “workflow” or “skill graph.”
3. Add migration note that explains why the split exists and how to interpret old names.
4. Ensure docs consistently define:
   - pipeline (materialization)
   - materialized view
   - skill definition
   - skill implementation
   - skill graph
   - API key scope

Deliverables
- Updated docs with consistent terminology and a short glossary.

Dependencies
- None.

---

### Track B: API + Policy Surface (Backend Team)
**Scope**: Introduce skill graphs as the policy and API key entry point, making updates to existing routes as needed.

Steps
1. Define a minimal skill graph execution model (even if stored in-memory initially):
   - graph id, tenant, environment, allowed pipelines, policy rules, version.
2. Add API alias routes for skill graph execution:
   - Existing routes stay, new routes map to the same handlers.
3. Define API key scope resolution:
   - API key -> allowed pipelines + skill graphs (with optional filters for tenant/env).
4. Ensure runtime execution uses skill graph scope + pipeline access rules.
5. Add logging/audit metadata fields for skill graph execution.

Deliverables
- Backwards-compatible API with skill-graph-first entry points.
- Policy enforcement at skill graph boundary.

Dependencies
- Agreed naming from Track A.

---

### Track C: UI Naming + Entry Points (Frontend Team)
**Scope**: Reflect new terminology in the UI with minimal disruption.

Steps
1. Rename UI tabs/labels:
   - “Pipelines” -> “Pipelines (Ingestion)”
   - “Skills” -> “Skill Graphs (Runtime)”
2. Update tooltips/descriptions to clarify pipeline = ingestion.
3. Add a skill graph execution entry point (if applicable) that references skill graphs.
4. Ensure existing functionality remains accessible during the transition.

Deliverables
- UI labels and descriptions aligned with new terminology.

Dependencies
- Track A glossary for consistent text.

---

### Track D: Code Semantics + Internal Naming (Platform Team)
**Scope**: Reduce confusion in code by aligning runtime naming with skill graphs.

Steps
1. Update docstrings/comments in runtime code to use “skill graph”:
   - server/src/digdir/rag/core.cljc
   - server/src/digdir/playground/core.cljc
   - server/src/digdir/pipeline/skills/graph/runner.clj
2. Audit config paths: avoid new `pipeline.*` for runtime behavior; prefer `skills.*` or `skill-graph.*`.
3. Add internal alias types (optional):
   - e.g. `skill-graph-execution` record or map shape to make it explicit in code.

Deliverables
- Runtime code comments and naming aligned with the new conceptual model.

Dependencies
- None, but ideally after Track A for consistent language.

---

### Track E: Compatibility + Migration Support (Backend Team)
**Scope**: Maintain existing integrations while introducing skill-graph-first semantics.

Steps
1. Keep legacy endpoints and parameters working.
2. Provide translation layer from legacy pipeline execution to skill graph execution.
3. Add integration tests that cover both legacy and skill graph routes.
4. Provide migration guidance in docs with examples.

Deliverables
- No breaking changes; explicit migration path.

Dependencies
- Track B for workflow endpoint plumbing.

---

## Integration Checkpoints

Checkpoint 1: Terminology locked
- Track A complete
- Shared glossary and naming contract

Checkpoint 2: Skill graph entry points live
- Track B and Track E basic routes in place
- Legacy path tested

Checkpoint 3: UI and docs aligned
- Track C and A complete

Checkpoint 4: Internal naming aligned
- Track D complete

## Risks and Mitigations
- Risk: Confusion between “skill graph” and legacy “template/workflow” references.
  - Mitigation: Add explicit glossary and examples in docs and UI tooltips.
- Risk: API key policy regressions.
  - Mitigation: Add tests for API key scope resolution across legacy and workflow routes.
- Risk: Partial rename causes inconsistent user experience.
  - Mitigation: Phase in UI labels with dual naming (e.g., “Pipelines (Ingestion)”).

## Suggested Next Actions
1. Confirm the term “skill graph” for external-facing docs and UI.
2. Assign owners for Tracks A–E.
3. Schedule Checkpoint 1 review after docs updates.

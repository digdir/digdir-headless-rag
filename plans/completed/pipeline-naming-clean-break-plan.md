# Pipeline Naming Transition — Clean Break Plan

## Objective

Eliminate the overloaded use of "pipeline" for runtime execution. After this work, the codebase has a single clear rule:

- **`pipeline`** = ingestion and materialization (loading, chunking, indexing).
- **`skill-graph`** = runtime execution (query planning, retrieval, reranking, synthesis).

No legacy aliases, no dual naming, no translation layers. One concept, one name, everywhere.

## Terminology (Final)

| Term | Meaning |
|---|---|
| **Pipeline** | A configuration for materializing a dataset — loading sources, chunking, generating search phrases, storing artifacts. |
| **Materialized view** | The stored output of a pipeline (collections of docs, chunks, phrases). |
| **Skill** | A discrete, composable runtime capability (e.g., retrieval, reranking). Has a definition (data) and implementation (code). |
| **Skill graph** | A DAG of skills — the callable unit of runtime execution. Replaces "template" as the external-facing name. |

## What Changes

### 1. Move runtime code out of `digdir.pipeline.*`

The skill graph runner, optimizer, templates, and built-in skills currently live under the pipeline namespace. Move them to a dedicated top-level namespace.

| Current path | New path |
|---|---|
| `digdir.pipeline.skills.api` | `digdir.skills.api` |
| `digdir.pipeline.skills.graph.runner` | `digdir.skills.graph.runner` |
| `digdir.pipeline.skills.graph.optimizer` | `digdir.skills.graph.optimizer` |
| `digdir.pipeline.skills.context` | `digdir.skills.context` |
| `digdir.pipeline.skills.builtin.*` | `digdir.skills.builtin.*` |
| `digdir.pipeline.templates.core` | `digdir.skills.templates.core` |
| `digdir.pipeline.templates.builtin` | `digdir.skills.templates.builtin` |
| `digdir.pipeline.templates.migration` | _(deleted — see §3)_ |
| `digdir.pipeline.ui.skills` | `digdir.skills.ui` |

After this move, `digdir.pipeline.*` contains only ingestion code: `executor`, `core`, `loaders/*`, `ui/pipelines`.

The existing `digdir.rag.skills.core` (skill protocol and schemas) stays where it is — it's already correctly namespaced.

### 2. Rename config paths

Skill parameters currently use `pipeline.skills.{skill-id}.*`. Rename to `skills.{skill-id}.*`.

| Current key pattern | New key pattern |
|---|---|
| `pipeline.skills.{id}/enabled` | `skills.{id}/enabled` |
| `pipeline.skills.{id}/model` | `skills.{id}/model` |
| `pipeline.skills.{id}/prompt` | `skills.{id}/prompt` |
| `pipeline.skills.{id}/temperature` | `skills.{id}/temperature` |
| `pipeline.skills.{id}/top-k` | `skills.{id}/top-k` |
| `pipeline.skills.{id}/max-tokens` | `skills.{id}/max-tokens` |

Write a one-time data migration to rename existing stored config paths. This is a simple prefix replacement on the config store with no behavioral change.

### 3. Remove the legacy-to-skill migration layer

`digdir.pipeline.templates.migration` maps legacy config properties (`:rerank-enabled`, `:prompt-rag-generate`, etc.) to skill parameters. Delete it.

- Remove `map-legacy-to-skill-params` and all call sites.
- Remove the dual legacy ID / `pipeline-id` lookup path in API route handlers — use `pipeline-id` only (for selecting which materialized data to query against).
- Any legacy configs still using old properties need migration to the new `skills.*` keys. Write a one-time migration script for this.

### 4. Rename "template" to "skill graph" in code internals

The template registry (`templates/core.clj`, `templates/builtin.clj`) uses "template" internally. After the namespace move:

- Rename `register-template!` → `register-skill-graph!`
- Rename `instantiate-template` → `instantiate-skill-graph`
- Rename `template-registry` → `skill-graph-registry`
- Rename the `:builtin/*` template IDs only if they appear in external APIs; otherwise leave them as-is to minimize churn.

### 5. Clean up API routes

Current state: API routes already have separate `/api/pipelines/*` (ingestion) and `/api/skill-graphs/*` (runtime) endpoints. The RAG endpoints (`/api/rag`, `/api/retrieve`) internally call skill graph execution.

Changes:
- Remove any legacy ID fallback logic in `/api/rag` and `/api/retrieve` handlers.
- Ensure `pipeline-config` parameter in skill execution context is renamed to something unambiguous (e.g., `skill-params` or `execution-config`) so it doesn't suggest a pipeline association.
- No new routes needed. No legacy routes to maintain.

### 6. Clean up UI labels

The UI already has "Pipelines (Ingestion)" and "Skill Graphs (Runtime)" labels. Simplify:

- "Pipelines (Ingestion)" → **"Pipelines"** (it's the only meaning now, no disambiguation needed)
- "Skill Graphs (Runtime)" → **"Skill Graphs"**
- "Skill Definitions" tab → **"Skills"**
- "Tool Definitions" tab → **"Tools"**

### 7. Update docs

- Rewrite the glossary section in `ARCHITECTURE_DECISION.md` and `SKILLS_README.md` to use the final terminology without referencing the old naming.
- Remove migration notes and "formerly known as" language — treat the new names as if they were always the names.
- Update `docs/architecture/skills-pipeline-plan.md` to reflect the clean model.

## Execution Order

This is a sequential plan — each phase builds on the previous one.

### Phase 1: Namespace move
Move all runtime skill code from `digdir.pipeline.*` to `digdir.skills.*`. Update all `require`/`import` references across the codebase. Run tests to verify nothing broke.

This is the highest-value change — it makes the physical code layout match the conceptual model.

### Phase 2: Config path migration
Rename `pipeline.skills.*` → `skills.*` config paths. Write and run the data migration. Update all code that reads/writes these paths.

### Phase 3: Remove legacy compat
Delete `migration.clj`. Remove legacy ID fallback paths. Remove dual-lookup logic. Run the legacy config migration script to move any remaining old properties.

### Phase 4: Internal renames
Rename template → skill graph in function names, var names, and docstrings. Rename `pipeline-config` → `skill-params` in execution context.

### Phase 5: UI + docs
Simplify UI labels. Rewrite docs with clean terminology. No hedging, no dual names.

## What We're Explicitly Not Doing

- **No legacy route aliases.** Old names go away. Consumers update.
- **No dual naming in the UI.** Labels are clean from day one.
- **No translation layers.** Legacy config is migrated, not shimmed.
- **No backwards-compatible API key resolution.** API keys reference pipelines (for data access) and skill graphs (for execution). One model.
- **No phased rollout with checkpoints.** This is a focused cleanup, not a multi-quarter program.

## Risks

| Risk | Response |
|---|---|
| Breaking existing API consumers | Communicate the change. Provide the migration script. This is a cleanup, not a surprise — consumers should already be using the new endpoints. |
| Config migration misses edge cases | Write the migration script with a dry-run mode. Review output before applying. |
| Large diff from namespace move | Do the namespace move as a single atomic commit with no other changes. Makes it easy to review and revert if needed. |

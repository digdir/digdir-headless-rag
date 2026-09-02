# Plan: Architecture Convergence — Remaining Gaps

Tracks the remaining work to bring the codebase into full alignment with
`docs/architecture/consolidated-target-architecture.md`.

## Context

The Clean Break Plan (phases 1-5) and namespace refactor are complete.
Core domain model, config tree, skill execution contract, and migration
isolation are all in good shape. What remains are refinement gaps
identified by diffing the target spec against current code.

## Tasks

### 1. Standardize I/O Vocabulary with Malli Schema
**Status:** complete
**Priority:** high
**Files:**
- `server/src/digdir/rag/skills/core.clj` (add canonical schemas)
- `server/src/digdir/skills/builtin/*.clj` (align declared outputs)

**Problem:** Skills use inconsistent key names for the same concepts:
- `:search-phrases` (query-planner) vs `:queries` (retrieval input)
- `:chunks` (retrieval) vs `:reranked-chunks` (rerank) vs `:context-docs` (synthesis input)
- `:response` (synthesis) vs `:text` (other skills)

The graph runner wires outputs to inputs explicitly per-step, so the
runtime works — but there is no canonical schema documenting the
standard vocabulary, making it hard to validate graphs or reason about
compatibility.

**Plan:**
- Define Malli schemas in `core.clj` for the canonical I/O types:
  `Query`, `Chunks`, `ContextDocs`, `SearchPhrases`, `Response`,
  `SearchAttribution`, `Citations`
- Add a `CanonicalIO` registry map from concept keyword to schema
- Document the standard vocabulary and which skills produce/consume each type

### 2. Add Skim vs Full-Read Distinction to Trace Capture
**Status:** complete
**Priority:** high
**Files:**
- `server/src/digdir/skills/builtin/agent/workspace.clj` (record-read!)
- `server/src/digdir/skills/builtin/agent/tools.clj` (call site)
- `server/src/digdir/skills/builtin/agent/core.clj` (trace format)

**Problem:** Architecture requires distinguishing metadata-only skim
operations from full-content reads in traces. The `max_content_length`
parameter exists on the `read_chunks` tool and is passed to retrieval,
but is not recorded in read-history entries.

**Plan:**
- Pass `max-content-length` through `read-source` map to `record-read!`
- Store `:max-content-length` in read-history entries (nil = full read)
- Update `format-trace-file` to render `skim=true` when max-content-length is set
- Update success criterion in agentic-observability-plan.md

### 3. Link Sufficiency Decisions to Iteration Numbers
**Status:** complete
**Priority:** high
**Files:**
- `server/src/digdir/skills/builtin/agent/workspace.clj`
- `server/src/digdir/skills/builtin/agent/loop.clj`

**Problem:** Sufficiency decisions are recorded but not tagged with the
iteration number that produced them. Cannot reconstruct "after turn 3,
agent decided insufficient and chose re-search."

**Plan:**
- Add `:iteration` field to sufficiency decision entries
- Pass current iteration number from the loop to `record-sufficiency-decision!`

### 4. Per-Turn Budget Snapshots
**Status:** complete
**Priority:** medium
**Files:**
- `server/src/digdir/skills/builtin/agent/loop.clj`
- `server/src/digdir/skills/builtin/agent/workspace.clj`

**Problem:** Budget state is only captured globally at execution end.
Cannot trace when budget pressure became a factor.

**Plan:**
- Capture `budget-state` snapshot in each iteration-history entry
- Add `:budget-snapshot` to turn record in loop

### 5. Remove Legacy Config Key Aliases with Descriptive Errors
**Status:** complete
**Priority:** medium
**Files:**
- `server/src/digdir/api/routes/common.clj`
- `server/src/digdir/api/routes/endpoints/debug.clj`
- `server/src/digdir/api/routes/datasets.clj`

**Problem:** ~23 fallback aliases for generic `config_key`/`config-key`
were silently accepted. Callers had no signal to migrate.

**Resolution:** Removed all legacy aliases. API now rejects retired
parameter names (`config_key`, `config-key`, `tenant-config-key`,
`pipeline`, `pipeline-id`) with a 400 error that names the canonical
replacement.

### 6. Track Executed Skill Graph in Conversation Schema
**Status:** complete
**Priority:** medium
**Files:**
- `server/src/digdir/data/db.cljc`
- `server/src/digdir/playground/core.cljc`

**Problem:** Conversations don't record which skill graph was executed.

**Plan:**
- Add `:conversation/skill-graph-id` attribute to schema
- Populate it at execution time

### 7. Rename `execute-skills-pipeline` and Update Legacy Docstrings
**Status:** complete
**Priority:** low
**Files:**
- `server/src/digdir/playground/core.cljc`
- `server/src/digdir/skills/templates/builtin.clj`

**Plan:**
- Rename `execute-skills-pipeline` to `execute-skill-graph`
- Update docstrings that say "pipeline" where "skill graph" is canonical

### 8. Confirm and Close Open Questions (Section 10)
**Status:** complete
**Priority:** low

All preferred directions have been confirmed and implemented:
- 10.1: Context-specific config keys (Option B) — Implemented in `api.context`.
- 10.2: Persist all reasoning (Option B) — Implemented in `workspace` and `diagnostics`.
- 10.3: Dataset-specific tool naming visible (Option B) — Implemented via the standardized I/O vocabulary.

**Action:** Architecture convergence is now considered 100% complete.

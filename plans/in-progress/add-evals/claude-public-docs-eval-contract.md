# public-docs Eval Contract Freeze (Automated Plan Phase 0)

Status: Produced 2026-04-18 as Phase 0 of `public-docs-automated-evals-plan.md`, consuming the decisions from `claude-public-docs-evals-requirements.md`. **Decision 3 revised 2026-04-20** to reflect the single-source reality of the materialized corpus — see §V1 Scope Pivot below and `claude-public-docs-dataset-report.md` Appendix B.

This document freezes the seven decisions the automated plan called out, so the later phases have a stable contract.

## V1 Scope Pivot (2026-04-20)

Runtime inspection found that `public-docs` is currently materialized as a **single-source Altinn-NO corpus** (62 docs / 177 chunks, zero Digdir Docs, zero English). The original contract's mandatory multi-slice gate assumed a two-pipeline bilingual corpus that does not exist in the snapshot.

For v1 the following relaxations apply, all scoped to this document:

- Decision 3's mandatory slice list reduces to `:source-slice :altinn-docs` and `:language :no` only.
- The `:source-slice :digdir-docs` and `:language :en` mandatory gates are **deferred**, not removed. They re-activate automatically when the corpus includes either.
- All other decisions (1, 2, 4, 5, 6, 7) stand as originally frozen.

Revert when the corpus expands: restore Decision 3 §"Required slices" to its original four-slice list in one coordinated PR, re-baseline, and re-promote the expanded inventory.

## Decision 1 — Eval Layers

**Frozen**: three layers, as proposed.

- **Layer A (deterministic)**:
  - A.1 Rerank isolation fixture (fixed candidate pool → golden must survive top-K).
  - A.2 Retrieval merge regression (unit-level fixture; added only if/when a `public-docs`-specific merge bug appears).
- **Layer B (live retrieval + rerank)**: primary gate via `bb rerank-benchmark-report`.
- **Layer C (live agent smoke)**: secondary gate via `bb agent-budget-report`.

Weight at v1: B carries the gate; A.1 runs in CI for fast feedback; C runs on-demand + nightly as a smoke check, not a ranking signal.

## Decision 2 — Oracle Format

**Frozen**: extend the existing suite EDN shape. No second schema.

Required fields per case:

- `:id` — stable, kebab-case, unique within the suite.
- `:query` — user-visible text.
- `:golden-chunk-ids` — vector, ≥1 entry.

Optional fields (extend as needed):

- `:language` — `:no` | `:en` | `:nn` | `:mixed`.
- `:pair-id` — string, groups bilingual or paraphrase pairs.
- `:source-slice` — `:altinn-docs` | `:digdir-docs`. **New** — see Decision 3.
- `:query-family` — `:exact-lookup` | `:paraphrase` | `:navigational` | `:factual-numeric` | `:comparison`.
- `:context-required` — boolean. Default false.
- `:max-acceptable-rank` — integer override for this case. Default inherits from runner flag.
- `:expected-answer-pattern` — regex, required for agent smoke cases.
- `:budget-dimension` — keyword, required for agent cases.
- `:current-budget`, `:relaxed-budget` — maps, required for agent cases.
- `:notes` — freeform string, never read by tooling.

## Decision 3 — Gated Scope

**Frozen**: gate at the dataset boundary with slice awareness where slices exist.

Required slices, each independently gated (v1 — revised 2026-04-20):

- `:source-slice :altinn-docs`
- `:language :no`

**Deferred** (re-activate when the corpus is expanded — see V1 Scope Pivot):

- `:source-slice :digdir-docs`
- `:language :en`

Required representation (not gated, but the suite is incomplete without each):

- `:query-family :exact-lookup`
- `:query-family :paraphrase`

Aggregate-only pass is rejected. If a gated slice fails, the run fails. In v1 this reduces to "the aggregate must pass plus the single-slice aggregate must match," because the two v1 slices overlap with the whole suite.

This requires one framework change: extend `digdir.tools.diagnostics/rerank-benchmark` summary aggregation to include `:summary-by-source-slice` alongside the existing `:summary-by-language`. **Landed 2026-04-20** (see `digdir.tools.diagnostics/slice-summary`). The per-slice gating logic is ready for v2 content without further runner work.

## Decision 4 — Stack Coverage

**Frozen**: two benchmarks, different signals.

- **Full user-path benchmark**: `bb rerank-benchmark-report` — runs query relaxation, retrieval, auto-filter, rerank, context selection end-to-end. This is the gate.
- **Isolation benchmark**: `bb rerank-isolation-eval` + captured candidate fixture — removes retrieval and query-planning noise. Diagnostic only; regression gate on survival of golden in the top-K.

## Decision 5 — Thresholds

**Frozen**: no-regression first, absolute thresholds after baseline.

Before baseline (first three runs):

- No case errors.
- No case loses all goldens from retrieved candidates.
- `bb rerank-benchmark-report --fail-on-gate true` must pass at the runner's default gate (`max-acceptable-rank 10`, `require-context false`).

After baseline is recorded:

- Per slice (source + language):
  - `golden-present-in-retrank` ≥ baseline - 0.05.
  - `mrr` ≥ baseline × 0.90.
  - `p50-rank` ≤ baseline + 2.
- Per case:
  - No regression past the case's `:max-acceptable-rank`.
  - No case may lose all goldens.

Agent smoke:

- Zero infra-errors.
- Zero `insufficient-context`.
- Golden present in retrieval trace.
- Answer pattern matches.

## Decision 6 — Live vs Snapshot

**Frozen**: both. The two populations serve distinct purposes.

- Snapshot / isolation fixtures: CI-friendly, deterministic, debugging-oriented. Regression signal is binary (survives top-K or not).
- Live benchmarks: production-like quality signal. Required before any dataset-level tuning.

Snapshot coverage is mandatory. Live coverage is mandatory before changing:

- `skills.retrieval.*`
- `skills.rerank.*`
- `skills.agent.*`
- pipeline materialization config that could shift chunk IDs.

## Decision 7 — Golden Drift Policy

**Frozen**: human approval with classified cause.

A golden change is allowed iff the commit message or PR description states one of:

- `golden-update: source-drift` — website content moved or changed.
- `golden-update: materialization-drift` — chunker changed, chunk IDs shifted.
- `golden-update: retrieval-tuning` — we deliberately moved the expected answer location.
- `golden-update: oracle-correction` — the original golden was wrong.

Exploratory cases are kept outside the enforced suite files until they have stabilized across three consecutive runs.

No golden may change as a drive-by edit inside an unrelated PR.

## Reference

- Requirements detail: `claude-public-docs-evals-requirements.md`.
- Query inventory: `claude-public-docs-query-inventory.md`.
- Suite specs: `claude-public-docs-suite-specs.md`.
- Ops: `claude-public-docs-eval-ops.md`.

# public-docs Evals Suite - Discovery and Requirements Plan

Status: Proposed on 2026-04-18.

Source plans:

- `plans/proposed/public-docs-evals-suite-plan.md`
- `plans/proposed/public-docs-automated-evals-plan.md`

## Role In Sequence

This is Phase 1 of a two-plan sequence for `digdir/public-docs`.

Its job is to produce the dataset report and freeze the eval contract that the implementation plan will execute. It does not build the suite itself.

The follow-on implementation plan is:

- `plans/proposed/codex-public-docs-automated-evals-plan.md`

## Goal

Lay the groundwork for a dataset-specific evals suite targeting the `public-docs` dataset in the `digdir` tenant, with decisions grounded in observed dataset characteristics rather than guesses.

This plan assumes one product decision is already made:

- LLM-as-judge is in scope for v1

## Why This Phase Exists

The repo already has useful eval building blocks, but `public-docs` still needs a dataset-specific policy before fixture creation and gating work starts.

Existing surface already identified:

- `digdir.llm.structured-eval` for structured judge outputs
- `digdir.tools.diagnostics` for golden-chunk tracking and agent budget profiling
- live rerank benchmark patterns driven by `:query` plus `:golden-chunk-ids`
- deterministic retrieval and merge regression fixtures

What is missing is the `public-docs` decision layer:

- which slices of the dataset are mandatory
- which metrics are gating versus diagnostic
- what the v1 oracle format is
- how goldens and content drift will be reviewed
- how LLM judging fits alongside chunk-level retrieval signals

## Constraints To Preserve

### 1. Dataset-first evaluation

The primary eval target is:

- `{:tenant "digdir" :dataset-config-key "public-docs"}`

not an individual pipeline.

### 2. Source-aware coverage

`public-docs` is populated by at least:

- `altinn-docs`
- `digdir-docs`

The suite contract must therefore be source-aware. Aggregate pass/fail alone is not sufficient.

### 3. Live content drift is real

Because the dataset is website-backed:

- chunk IDs can change after rematerialization
- answer wording can change without a product regression
- a strong eval policy needs both stable snapshot-style checks and live health checks

### 4. LLM judging is additive, not a replacement

Judge-based answer grading should complement:

- golden chunk presence
- rank-based retrieval metrics
- smoke-style answer pattern checks where they remain useful

It should not become the only oracle for v1.

## Deliverable 1 - `public-docs` dataset analysis report

Target file:

- `server/docs/evals/public-docs-dataset-report.md`

Read-only exploration; no production code changes.

### Inputs to gather

1. Dataset shape
   - Resolve context for `{:tenant "digdir" :dataset-config-key "public-docs"}`.
   - Record dataset-id, collection prefix, and feeding pipelines.
2. Corpus inventory
   - Document, chunk, and phrase counts.
   - Avg, median, and p95 chunk length in characters and tokens if available.
   - Domain and URL breakdown.
   - Language mix if detectable.
   - Freshness spread and any obvious skew.
3. Content character
   - Sample chunks across both source lineages.
   - Note structural traits such as lists, headings, tables, code blocks, and reference-heavy prose.
   - Note whether the corpus looks better suited to exact lookup, paraphrase, synthesis, or policy interpretation queries.
4. Retrieval config
   - Effective retrieval, rerank, auto-filter, and query-relaxation settings.
   - Embedder and reranker model choices.
5. Usage signals
   - Any query logs, diagnostics, or playground traces for `public-docs`.
   - Common success and failure patterns if visible.
6. Known failure modes
   - Recent plans, commits, issues, or notes touching missed retrievals, bad ranking, hallucinated answers, or drift.

### Report structure

One section per input above, plus a closing `Implications for evals` section that recommends:

- likely high-value query families
- expected suite size for v1
- which metrics should be gating first
- where LLM judging is likely to be reliable or noisy

## Deliverable 2 - `public-docs` evals requirements and decisions

Target file:

- `server/docs/evals/public-docs-evals-requirements.md`

Produced after Deliverable 1 so decisions are grounded in observed dataset characteristics.

### Requirements section

The requirements document must freeze decisions for:

- scope: retrieval-only, end-to-end agent, or both
- metric set: recall-oriented metrics, rank metrics, chunk presence, judge correctness, cost, and latency
- test-set composition: curated, synthetic, log-derived, or hybrid
- suite slices: source lineage, language, and query-family quotas
- mode split: CI-gated, scheduled live runs, and on-demand debugging
- reproducibility posture: fixtures, pinned models where possible, and snapshot policies
- runner surface: existing harness reuse versus new entrypoints

### Decisions that must be explicit

The requirements document must record a recommendation for each of these:

- judge model choice for v1
- judge rubric and structured output shape
- whether judge grading is gating, advisory, or mixed by suite
- golden set construction strategy
- storage location for suite fixtures and judge rubrics
- live-versus-snapshot policy
- golden drift update policy
- baseline and regression policy

## Initial Recommendations To Carry Forward

These are strong starting assumptions for Deliverable 2 unless Deliverable 1 disproves them:

- The suite should remain dataset-first, not pipeline-first.
- Source-aware slices for `altinn-docs` and `digdir-docs` should be mandatory.
- The existing EDN-style query suite shape should remain the primary fixture format.
- Judge grading should be included for v1 end-to-end answer evals, but paired with chunk-level evidence checks.
- Aggregate scoring should never mask a slice-specific regression.

## Open Questions

These remain legitimate questions for discovery, but they should now be answered inside the deliverables rather than blocking work from starting:

1. Which runtime profile should be treated as the default `public-docs` eval target?
2. How much English coverage is required for v1, and should parity be explicit or minimal?
3. Which query families deserve mandatory quotas in the enforced suite?
4. Which answer tasks are stable enough for judge-gated evaluation versus smoke-only coverage?
5. How much of the corpus and diagnostics surface is directly accessible in the current environment?

## Out Of Scope For This Plan

- implementing the evals suite itself
- building the final golden set
- changing retrieval, rerank, or agent behavior
- changing CI configuration

## Acceptance Criteria

This phase is complete when:

- `server/docs/evals/public-docs-dataset-report.md` exists and reflects actual dataset observations
- `server/docs/evals/public-docs-evals-requirements.md` exists and freezes the v1 contract
- the requirements doc explicitly includes LLM-as-judge in v1
- the implementation plan can proceed without reopening first-principles scope questions

## Sequencing

1. Produce the dataset report.
2. Produce the requirements and decisions document.
3. Review both for sign-off.
4. Execute `plans/proposed/codex-public-docs-automated-evals-plan.md`.

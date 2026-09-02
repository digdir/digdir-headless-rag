# Public Docs Automated Evals Plan - Implementation

Status: Proposed on 2026-04-18.

Source plans:

- `plans/proposed/public-docs-automated-evals-plan.md`
- `plans/proposed/public-docs-evals-suite-plan.md`
- `plans/proposed/codex-public-docs-evals-suite-plan.md`

## Role In Sequence

This is Phase 2 of the `public-docs` evals work.

It assumes the discovery and requirements work in `codex-public-docs-evals-suite-plan.md` has already been completed and signed off. This document converts that contract into implementation phases, suite structure, and operating policy.

## Goal

Introduce automated evals for `digdir/public-docs` that are strong enough to catch regressions in retrieval, reranking, and end-to-end answer quality without creating constant churn from live website content changes.

This implementation plan assumes one product decision is fixed:

- LLM-as-judge is part of v1

## Working Principles

### 1. Keep the system boundary correct

Pipelines produce datasets. APIs and agents read datasets. The primary eval target is therefore:

- `{:tenant "digdir" :dataset-config-key "public-docs"}`

not an individual materialization pipeline.

### 2. Preserve source-aware coverage

`public-docs` has at least two source lineages in practice:

- `altinn-docs`
- `digdir-docs`

No overall pass condition is acceptable unless both lineages meet minimum slice-level expectations.

### 3. Separate stable regression checks from live health checks

Because `public-docs` is website-backed:

- chunk IDs can move
- wording can drift
- source pages can change independently of retrieval quality

The suite needs both deterministic fixtures and live dataset-backed runs.

### 4. Use layered oracles

V1 should combine:

- evidence-level checks such as golden chunk presence and rank
- answer-surface checks such as regex or structured expectations where useful
- LLM judge grading for end-to-end correctness and groundedness

No single oracle should carry the whole gate.

## Eval Layers

## Layer A: Deterministic retrieval and merge regression

Purpose:

- catch logic regressions in merge and ranking behavior
- run quickly and reliably in normal test workflows
- provide stable debugging signals independent of live site drift

Mechanism:

- fixture-based tests like the existing retrieval merge regression suite
- fixed candidate-pool rerank isolation cases where ordering is the point under test

## Layer B: Live retrieval and rerank benchmark

Purpose:

- measure whether the current `public-docs` dataset still surfaces the right evidence
- detect regressions caused by retrieval settings, query relaxation, auto-filtering, or reranking

Mechanism:

- suite-driven live benchmark using `:query` and `:golden-chunk-ids`
- slice-aware reporting across source lineage, language, and query family

## Layer C: End-to-end answer eval

Purpose:

- verify that the user-facing answer path is correct, grounded, and operationally healthy
- catch failures where evidence is retrieved but the final answer still fails

Mechanism:

- small live suite using golden chunks plus answer expectations
- LLM-as-judge structured grading for correctness and groundedness
- optional regex checks retained for narrow smoke assertions where they are high signal

Decision:

- Layers A, B, and C are all in scope for v1
- Layers B and C are the primary quality signals
- Layer A remains the CI-friendly debugging and regression substrate

## Oracle Format

Use the existing suite vocabulary as the primary fixture format.

Required retrieval fields:

- `:id`
- `:query`
- `:golden-chunk-ids`

Recommended metadata:

- `:language`
- `:pair-id`
- `:source-slice`
- `:query-family`
- `:notes`

For answer-eval cases, add fields required by the chosen runner and judge harness, for example:

- `:expected-answer-pattern` when regex smoke checks are useful
- judge rubric or grade profile reference
- any case-specific exclusions or notes needed for stable judging

Decision:

- do not invent a second benchmark schema for v1 unless the signed-off requirements phase proves it necessary
- extend the current suite shape only where answer judging needs explicit metadata

## Scope That Gets Gated

Gate the dataset as a whole, but require slice-aware pass conditions.

Minimum slices for v1:

- `altinn-docs`
- `digdir-docs`
- Norwegian queries
- English queries
- exact known-item lookup queries
- paraphrase queries

Additional query-family quotas may be added if the discovery phase shows they are important enough.

Decision:

- no gate should pass solely on an aggregate score
- each required slice must meet minimum coverage and threshold rules

## Threshold Policy

Start with interpretable gates and conservative no-regression rules.

For live retrieval and rerank:

- no case may lose all golden chunks from retrieved candidates
- no case may regress beyond configured acceptable rank bounds
- aggregate no-regression checks should cover at least:
  - golden-present-in-rerank
  - MRR
  - p50 rank

For end-to-end answer eval:

- no infrastructure errors
- no `insufficient-context` outcomes for cases that should be answerable
- golden chunk presence remains required
- judge grading must meet the minimum accepted rubric outcome
- regex smoke checks must pass where configured

Decision:

- prefer stable no-regression gates before aggressive absolute targets
- absolute targets should be tightened only after repeated runs establish variance

## Live Versus Snapshot Policy

Use both.

Snapshot-backed evals are for:

- deterministic regression
- CI reliability
- rerank and merge debugging

Live evals are for:

- production-like quality health
- dataset drift detection
- answer quality validation
- configuration tuning

Decision:

- snapshot tests are required for stable engineering feedback
- live benchmarks and live answer evals are required before changing retrieval, rerank, or agent-budget behavior on `public-docs`

## Goldens And Drift Management

We need an explicit policy for changing evidence goldens, answer expectations, and judge behavior.

Decision:

- changing a golden chunk, answer expectation, or judge rubric is an intentional eval update
- each update should state whether the cause was:
  - source content drift
  - chunking or materialization drift
  - retrieval tuning
  - answer-policy change
  - oracle correction
- exploratory cases should remain outside enforced suites until repeated stable runs justify promotion

## Proposed Suite Structure

## Suite 1: `public-docs` live retrieval and rerank benchmark

Purpose:

- primary evidence-quality gate

Contents:

- 20-40 cases initially
- balanced across `altinn-docs` and `digdir-docs`
- mix of Norwegian and English
- mix of exact and paraphrase queries

## Suite 2: `public-docs` rerank isolation fixture

Purpose:

- fixed-candidate reranker regression and debugging

Contents:

- a small number of hard cases with captured candidate pools
- cases selected because rerank ordering or top-k cutoffs are the failure mode

## Suite 3: `public-docs` answer-eval suite

Purpose:

- primary end-to-end answer-quality gate

Contents:

- 8-15 cases initially
- only stable, high-signal questions
- both chunk expectations and judge expectations
- regex checks only where they add useful precision

## Suite 4: exploratory candidate suite

Purpose:

- hold potential future gate cases without making enforced suites noisy

Contents:

- hard cases under investigation
- known flaky cases
- newly discovered query families
- judge cases whose rubric or outcome is not yet stable

## Implementation Phases

## Phase 0: Freeze the eval contract

Objective:

- confirm the discovery outputs and final v1 policy before fixture generation begins

Deliverables:

- approved suite schema for `public-docs`
- approved required slices
- approved initial gate rules
- approved judge rubric and minimum accepted outcome
- approved policy for updating goldens and judge expectations

Acceptance criteria:

- one written decision exists for each major v1 policy
- no disagreement remains about dataset-first scope

## Phase 1: Seed the `public-docs` query inventory

Objective:

- assemble the first candidate pool of cases before promotion into enforced suites

Work:

1. Collect candidate queries from both Altinn Docs and Digdir Docs.
2. Label each case with source slice, language, and query family.
3. Identify intended golden chunks.
4. Mark whether the case is suitable for retrieval-only, answer-eval, or both.
5. Separate stable cases from exploratory cases.

Acceptance criteria:

- at least 20 candidate retrieval cases exist
- both major source slices are represented
- an English subset exists
- a small set of candidate answer-eval cases exists

## Phase 2: Build the live retrieval benchmark

Objective:

- establish the first enforceable evidence-quality benchmark

Work:

1. Create a `public-docs` benchmark suite in the existing fixture format.
2. Run the live benchmark repeatedly to measure baseline variance.
3. Tune acceptable-rank and context rules conservatively.
4. Quarantine unstable cases into exploratory status.

Acceptance criteria:

- the suite is stable across repeated runs
- slice-level failures are visible
- initial retrieval thresholds are documented

## Phase 3: Build deterministic isolation coverage

Objective:

- make ranking regressions debuggable and CI-friendly

Work:

1. Capture fixed candidate pools for selected hard cases.
2. Add isolation fixtures for those cases.
3. Add or extend deterministic retrieval and merge regression tests for known `public-docs` failure modes.

Acceptance criteria:

- at least one fixed-candidate rerank isolation suite exists for `public-docs`
- at least one deterministic regression test covers a real `public-docs` failure mode

## Phase 4: Build the answer-eval suite

Objective:

- enforce end-to-end answer quality for a small but trusted subset of `public-docs`

Work:

1. Select a stable subset of `public-docs` questions.
2. Define judge rubric and structured output schema.
3. Add case metadata needed by the judge harness.
4. Retain regex smoke checks only where they add clear value.
5. Run repeated baselines and quarantine unstable judge cases.

Acceptance criteria:

- the suite catches obvious answer regressions
- judge outcomes are stable enough to be trusted on the chosen cases
- false positives remain low enough for routine use

## Phase 5: Operationalize and maintain

Objective:

- make the evals part of normal tuning and release work

Work:

1. Define suite ownership.
2. Define when live retrieval and answer evals must be run.
3. Define how golden, rubric, and fixture changes are reviewed.
4. Keep exploratory cases separate from enforced suites.

Acceptance criteria:

- ownership is explicit
- the update workflow is documented
- the suite does not drift silently

## Initial Recommendation

The first practical v1 should be modest but real:

1. Create one `public-docs` live retrieval benchmark with 20-40 cases.
2. Require coverage across both `altinn-docs` and `digdir-docs`.
3. Create one small answer-eval suite with 8-15 stable cases.
4. Include LLM judging in that answer-eval suite from the start.
5. Add fixed-candidate isolation fixtures only for the hardest or most valuable failure modes.
6. Use no-regression gates before aggressive absolute targets.

## Non-Goals For V1

- pipeline-specific gates that replace dataset-level gates
- complete automation of golden refresh for live website drift
- judge-only gating without evidence-level retrieval checks

## Open Questions

These should be narrow implementation questions, not first-principles scope questions:

1. Which judge model gives the best cost-versus-stability tradeoff for the chosen rubric?
2. Which answer dimensions are actually gating in v1: correctness, groundedness, completeness, or a subset?
3. Should `public-docs` live evals resolve against one runtime profile or multiple profiles?
4. Do we want a dedicated Babashka task for `public-docs`, or is the generic diagnostics entrypoint sufficient?

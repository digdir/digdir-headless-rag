# Public Docs Automated Evals Plan

Status: Proposed on 2026-04-18.

## Goal

Introduce automated evals for `digdir/public-docs` that are strong enough to catch regressions in retrieval, reranking, and end-to-end agent behavior without creating constant churn from live website content changes.

The plan should preserve the existing system boundary:

- pipelines produce datasets
- datasets are the runtime objects that APIs and agents read
- evals should therefore be defined primarily at the dataset boundary, not at the individual pipeline boundary

For `digdir/public-docs`, this still needs source-aware coverage because the dataset is produced from at least two website lineages in practice:

- `altinn-docs`
- `digdir-docs`

## Why This Needs A Plan

The repo already has useful eval machinery, but it is not yet framed as a `public-docs` dataset policy.

Existing building blocks already present:

- deterministic fixture tests for retrieval merge behavior
- live rerank benchmarks driven by query suites with `golden-chunk-ids`
- rerank isolation tests on fixed candidate pools
- live agent budget benchmarks with optional answer-pattern checks

What is missing is the decision layer:

- what exactly should be gated for `digdir/public-docs`
- what the oracle format should be
- which slices of the dataset must be covered
- which failures are acceptable drift versus true regression

## Existing Repo Constraints

### 1. Evals should target datasets first

The dataset model is explicit:

- datasets are the read-time objects
- agents and public APIs select datasets, not pipelines

That means the primary eval object should be:

- `{:tenant "digdir" :dataset-config-key "public-docs"}`

not one individual materialization pipeline.

### 2. `public-docs` has multiple source lineages

The deployment-target tests show `public-docs` being populated from both:

- Altinn Docs
- Digdir Docs

This means a single blended score is not enough. A gate can look healthy overall while one source regresses badly.

### 3. The current benchmark format is chunk-centric

The existing live benchmark format already supports:

- `:query`
- `:golden-chunk-ids`
- optional `:language`
- optional `:pair-id`

That is a strong fit for retrieval and rerank evals.

### 4. The current agent answer check is regex-based

The existing agent benchmark supports `:expected-answer-pattern`, but the check is only a regex match against the final response.

That is acceptable for a small smoke suite, but not sufficient as the only quality signal.

### 5. Live website-backed datasets will drift

`public-docs` is website-backed, so:

- chunk IDs can change after re-materialization
- the best supporting chunk can move
- content edits can legitimately change expected answers

The eval strategy must therefore separate:

- deterministic regression checks
- live production-like health checks

## Decisions To Freeze

## Decision 1: What Eval Layers We Want

Adopt three distinct eval layers.

### Layer A: Deterministic retrieval and merge regression

Purpose:

- catch logic regressions in merge/ranking behavior
- run fast in normal test workflows
- avoid dependency on live Typesense or live site content

Mechanism:

- fixture-based tests like the existing retrieval merge regression suite

### Layer B: Live retrieval and rerank benchmark

Purpose:

- measure whether the current `public-docs` dataset can still surface the right chunks
- detect regressions caused by retrieval parameters, query relaxation, auto-filtering, or reranking

Mechanism:

- suite-driven live benchmark using `golden-chunk-ids`

### Layer C: End-to-end agent answer smoke eval

Purpose:

- verify that the full user-facing path still returns acceptable answers
- catch failures where retrieval technically works but the agent loop still fails

Mechanism:

- small live suite using `golden-chunk-ids` plus `expected-answer-pattern`

Decision:

- all three layers should exist
- only Layers A and B should be expected to carry most of the quality gate weight initially
- Layer C should begin as smoke coverage, not as the main ranking signal

## Decision 2: What The Oracle Format Should Be

Use the existing suite vocabulary as the primary format.

Required fields for retrieval/rerank cases:

- `:id`
- `:query`
- `:golden-chunk-ids`

Optional but recommended fields:

- `:language`
- `:pair-id`
- `:source-slice`
- `:notes`

For agent smoke cases, add:

- `:expected-answer-pattern`

Decision:

- do not invent a second benchmark schema for v1
- extend the current suite shape only with metadata fields needed for dataset slicing and review

## Decision 3: What Scope Gets Gated

Gate the dataset as a whole, but require source-aware slices.

Minimum required slices:

- `altinn-docs`
- `digdir-docs`
- Norwegian queries
- English queries
- exact known-item lookup queries
- paraphrase queries

Decision:

- no gate should pass solely on an aggregate dataset score
- each required slice must meet minimum coverage and threshold rules

## Decision 4: How Much Of The Retrieval Stack Is In Scope

The existing live rerank benchmark exercises more than just the reranker. It includes:

- query relaxation
- retrieval
- auto-filtering unless disabled
- reranking
- context selection

Decision:

- keep one primary benchmark that measures the full retrieval path as users experience it
- also keep one isolation-style benchmark that removes retrieval/query-planning noise when debugging reranker behavior

This gives two different signals:

- user-path quality
- component isolation

## Decision 5: What Thresholds We Will Enforce

Start with simple, interpretable gates.

For live retrieval+rereank benchmark:

- no case may lose all golden chunks from retrieved candidates
- no case may regress beyond the configured `max-acceptable-rank`
- context presence should be required only for a smaller subset of cases at first
- the suite should enforce no aggregate regression in:
  - `golden-present-in-rerank`
  - `mrr`
  - `p50-rank`

For agent smoke benchmark:

- no infra errors
- no `insufficient-context`
- golden chunk must be present
- answer pattern must match

Decision:

- prioritize “no regression” and stable percentile/rank thresholds over ambitious absolute targets
- tune absolute thresholds only after a few repeated runs establish baseline variance

## Decision 6: Live Versus Snapshot Policy

Use both.

Snapshot-backed evals should be used for:

- deterministic regression
- CI reliability
- debugging ranking logic

Live evals should be used for:

- production-like quality health
- dataset drift detection
- configuration tuning

Decision:

- snapshot tests are required for stable engineering feedback
- live benchmarks are required before changing retrieval, rerank, or agent-budget behavior on `public-docs`

## Decision 7: Goldens And Drift Management

We need an explicit policy for updating goldens.

Decision:

- changing a golden chunk or answer pattern should be treated as an intentional eval update
- each update should state whether the cause was:
  - source content drift
  - chunking/materialization drift
  - retrieval tuning
  - oracle correction
- exploratory cases should live outside enforced suites until they are stable enough to promote

## Proposed Suite Structure

## Suite 1: `public-docs` rerank benchmark

Purpose:

- primary live dataset-quality gate

Contents:

- 20-40 cases initially
- roughly balanced across `altinn-docs` and `digdir-docs`
- mix of Norwegian and English
- mix of exact and paraphrase queries

Per-case fields:

- `:id`
- `:query`
- `:golden-chunk-ids`
- `:language`
- `:source-slice`
- `:pair-id` when bilingual or paraphrase-paired

## Suite 2: `public-docs` rerank isolation fixture

Purpose:

- fixed-candidate reranker regression/debugging

Contents:

- a small number of hard cases with captured candidate pools
- cases selected specifically because they are sensitive to rerank ordering or top-k cutoffs

## Suite 3: `public-docs` agent smoke suite

Purpose:

- verify the end-to-end answer path

Contents:

- 8-12 cases initially
- only stable, high-signal questions
- each case should have both chunk and answer expectations

Per-case fields:

- `:id`
- `:query`
- `:golden-chunk-ids`
- `:expected-answer-pattern`
- `:source-slice`

## Suite 4: exploratory candidate suite

Purpose:

- store potential future gate cases without making the gate noisy

Contents:

- hard cases under investigation
- known flaky cases
- newly discovered query families

Decision:

- only promote cases from exploratory to enforced suites after repeated stable runs

## Implementation Plan

## Phase 0: Freeze The Eval Contract

Objective:

- agree on scope, suite shape, and gate policy before generating a lot of fixtures

Deliverables:

- approved suite schema for `public-docs`
- approved required slices
- approved initial gate rules
- approved policy for updating goldens

Acceptance criteria:

- one written decision exists for each decision listed in this document
- no disagreement remains about dataset-level versus pipeline-level gating

## Phase 1: Seed The `public-docs` Query Inventory

Objective:

- assemble the first candidate pool of queries before turning them into enforced gates

Work:

1. Collect candidate queries from both Altinn Docs and Digdir Docs.
2. Label each case with source slice and language.
3. Mark each case as exact lookup, paraphrase, navigational, or factual.
4. Identify the intended golden chunk(s).
5. Separate stable cases from exploratory cases.

Acceptance criteria:

- at least 20 candidate rerank cases exist
- both major source slices are represented
- at least a small English subset exists

## Phase 2: Create The First Live Rerank Benchmark Suite

Objective:

- establish the first enforceable dataset-quality benchmark

Work:

1. Create a `public-docs` benchmark suite in the existing EDN format.
2. Run the live rerank benchmark repeatedly to measure baseline variance.
3. Tune `max-acceptable-rank` and context requirements conservatively.
4. Remove or quarantine unstable cases into exploratory status.

Acceptance criteria:

- the suite is stable across repeated runs
- the suite reports useful slice-level failures
- initial gate thresholds are documented

## Phase 3: Add Deterministic Isolation Coverage

Objective:

- make ranking regressions debuggable and CI-friendly

Work:

1. Capture fixed candidate pools for selected hard cases.
2. Add isolation fixtures for those cases.
3. Add deterministic tests around retrieval merge behavior where relevant.

Acceptance criteria:

- at least one fixed-candidate rerank isolation suite exists for `public-docs`
- at least one deterministic retrieval/merge regression suite exists or is extended to cover a `public-docs` failure mode

## Phase 4: Add End-To-End Agent Smoke Evals

Objective:

- verify the user-visible answer path without overfitting to semantic judging infrastructure

Work:

1. Select a small stable subset of `public-docs` questions.
2. Add `expected-answer-pattern` to those cases.
3. Run them through the existing agent benchmark machinery.
4. Treat them as smoke gates rather than the primary ranking gate.

Acceptance criteria:

- the suite catches obvious answer regressions
- false positives remain low enough that the suite is trusted

## Phase 5: Operationalize And Maintain

Objective:

- make the evals part of normal tuning and release work

Work:

1. Define who owns suite maintenance.
2. Define when live evals must be run.
3. Define how golden changes are reviewed.
4. Keep exploratory cases separate from enforced suites.

Acceptance criteria:

- ownership is explicit
- the eval update workflow is documented
- the suite is not silently drifting without review

## Initial Recommendation

The first practical version should be modest:

1. Create one `public-docs` live rerank benchmark suite with 20-40 cases.
2. Require coverage across both `altinn-docs` and `digdir-docs`.
3. Add one small `public-docs` agent smoke suite with 8-12 stable cases.
4. Add fixed-candidate isolation fixtures only for the hardest or most valuable failure modes.
5. Use no-regression gates before trying to impose aggressive absolute targets.

## Non-Goals For V1

- semantic LLM-as-judge answer grading
- a brand-new benchmark storage format
- pipeline-specific gates that replace dataset-level gates
- complete automation of golden refresh for live website drift

## Open Questions

1. Should `public-docs` live evals resolve against one default runtime config only, or multiple runtime profiles?
2. Do we want to gate bilingual parity explicitly, or only require minimum English coverage?
3. Which query families are important enough to deserve mandatory slice quotas?
4. Should context presence be mandatory for all golden chunks, or only for a curated subset of answer-critical cases?
5. Do we want a dedicated Babashka task for `public-docs` suites, or is the generic diagnostics entrypoint sufficient?

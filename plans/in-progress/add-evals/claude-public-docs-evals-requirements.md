# public-docs Evals Suite — Requirements & Decisions

Status: Produced 2026-04-18 as Deliverable 2 of `public-docs-evals-suite-plan.md`.

Builds on `claude-public-docs-dataset-report.md` (the dataset analysis report). Read that first — thresholds and gate rules here assume its findings.

This document is the decision layer. It does not propose code changes. The automated implementation plan (`public-docs-automated-evals-plan.md`) and its claude-prefixed expansions consume these decisions as input.

## 1. Requirements — What The Suite Must Do

### 1.1 Scope

The suite must cover **both** retrieval and end-to-end agent behavior, with retrieval weighted as the primary gate. Rationale: retrieval errors are the most common root cause of bad answers for a website-backed dataset, and they are cheaper to isolate than agent behavior.

### 1.2 Metric set

| Metric | Source | Layer |
|---|---|---|
| `golden-present-in-retrank` (% of cases where a golden chunk lands in rerank output) | existing `rerank-benchmark` summary | retrieval |
| `mrr` on golden chunk rank | existing runner | retrieval |
| `p50-rank` | existing runner | retrieval |
| `golden-present-in-context` | existing runner | retrieval (context selection) |
| `max-acceptable-rank` gate per case | runner flag | retrieval |
| `require-context` gate per case | runner flag | retrieval |
| answer-pattern match | `agent-budget-benchmark` via `:expected-answer-pattern` | agent |
| infra-error rate | runner payload | agent |
| `insufficient-context` rate | existing agent loop | agent |

Deliberately **out of scope for v1**:

- LLM-as-judge answer grading. Defer until Layer B signal is trusted.
- Latency SLA gates. Observe but do not enforce initially.
- Cost gates. Record but do not enforce.
- nDCG. `mrr` + `p50-rank` + hard rank caps are sufficient for a cold-start suite with few cases.

### 1.3 Test-set shape & size

Hand-curated, stable cases only in the enforced suites. Cold-start; no log-mined goldens until playground data is available.

**V1 sizing (revised 2026-04-20 to match the materialized single-source corpus):**

- **Rerank benchmark (Layer B)**: 10-15 cases, authored from the concrete 62-doc Altinn-NO content. Single `:source-slice :altinn-docs`, single `:language :no`.
- **Rerank isolation fixture (Layer A, rank path)**: 2-4 hard cases with captured candidate pools.
- **Retrieval merge deterministic fixture (Layer A, merge logic)**: extend an existing fixture with one `public-docs`-specific case if and when a failure mode is seen.
- **Agent smoke (Layer C)**: 4-6 cases drawn from the rerank benchmark.
- **Exploratory**: unbounded, quarantined from gates.

**V2 sizing (target when the corpus expands to include digdir-docs and/or English):**

- Rerank benchmark: 20-40 cases, source-balanced.
- Agent smoke: 8-12 cases.
- Other layers proportionally.

See `claude-public-docs-dataset-report.md` Appendix B for the pivot rationale.

### 1.4 Mode

- Rerank benchmark: on-demand via `bb rerank-benchmark-report` + required before any change to retrieval, rerank, auto-filter, query-relaxation, or dataset-level `skills.*` config for `public-docs`. Not a blocking CI gate yet — too expensive and flaky for PR-level enforcement. A nightly run can be added once three stable runs establish variance; release-blocking is out of scope for v1.
- Rerank isolation fixture: fast; **CI-gated** once stable. This is the layer that earns a PR gate.
- Retrieval merge deterministic test: **CI-gated** — runs in normal `bb test`.
- Agent smoke: on-demand + nightly. Not blocking on PRs.

### 1.5 Framework posture

Reuse the existing benchmark harness. No new benchmark framework. The only framework-level work identified:

- **Add `:source-slice` to suite schema and summary aggregation.** The runner already slices by `:language`; extending it to an arbitrary slice key is a small, local change.
- **Allow the runner to resolve against `{:tenant "digdir" :config-root "runtime" :config-key "public-docs"}`.** Already supported via positional args to `bb rerank-benchmark`; no code change needed.

### 1.6 Reproducibility

- Suites are EDN files checked into `server/test/fixtures/rerank/` and `server/test/fixtures/agent/`.
- Goldens are chunk IDs. Chunk IDs are a function of content+chunking, so a pipeline re-materialization *can* change them. See §2 Decision 5.
- Runners emit canonical EDN last-line output (already the case). Report artifacts can be diffed if captured.
- ColBERT model version is not pinned in the suite; it is pinned by the deployed service. Record service+version in the baseline doc, not the suite file.

## 2. Decisions List

Each entry: options considered, recommendation, rationale, remaining open question.

### Decision 1: Judge model

- **Options**: Haiku 4.5 (cheap, fast), Sonnet 4.6 (balanced), Opus 4.7 (best reasoning).
- **Recommendation**: **no LLM judge in v1**. Answer correctness is checked by regex against `:expected-answer-pattern`. Promote to LLM-as-judge only after Layer B is trusted and regex proves insufficient.
- **Why**: LLM judges add noise and cost without clear signal given only 8-12 agent cases. The regex approach already catches the failure modes we care about (missing number, missing entity).
- **Open**: none for v1. Revisit when ≥50 agent cases exist.

### Decision 2: Golden set construction

- **Options**: hand-curated, LLM-generated from chunks, log-mined, hybrid.
- **Recommendation**: **hand-curated** for v1. Seeded from the public sitemaps and authored with explicit source slice + language labels. See `claude-public-docs-query-inventory.md` for the template and the v0 seed.
- **Why**: Cold start; no playground logs surfaced. LLM-generated queries tend to be uniform and do not reflect real navigational intent.
- **Open**: whether to add a log-mined slice once playground data accumulates (expected during normal use). Treat as a Phase 6 task.

### Decision 3: Storage location

- **Options**: EDN in repo, Datahike, external.
- **Recommendation**: **EDN in repo** under `server/test/fixtures/rerank/` and `server/test/fixtures/agent/`, prefixed `public_docs_*`.
- **Why**: Matches current convention (`benchmark_suite.edn`, `mono_no_suite.edn`). Reviewable, diffable, versioned with code.
- **Open**: none.

### Decision 4: Runner surface

- **Options**: add `bb eval:public-docs` meta-task, add a test namespace, extend admin UI.
- **Recommendation**: **no new top-level runner**. Invoke the existing `bb rerank-benchmark-report`, `bb rerank-language-report`, `bb agent-budget-report`, `bb rerank-isolation-eval` with the `public-docs` suite paths.
- **Why**: The existing runners already support every flag needed. Adding a meta-task adds surface without value.
- **Open**: whether to add a `Makefile`-style convenience bundle (e.g. `bb evals-public-docs`) that runs all three + prints a combined table, once the suites are stable. Deferred.

### Decision 5: Golden drift policy

- **Options**: auto-refresh on chunk-id change, fail loud, human approval.
- **Recommendation**: **human approval, with classified cause**. A changed golden requires a commit whose message states the cause:
  - `source-drift` — website content changed.
  - `materialization-drift` — chunker config changed and chunk IDs shifted.
  - `retrieval-tuning` — we deliberately moved the expected answer location.
  - `oracle-correction` — the original golden was wrong.
- **Why**: Accidental refresh destroys the regression signal. Classification makes later investigation tractable.
- **Open**: whether to add a helper task (e.g. `bb public-docs-golden-diff`) that shows "what chunk IDs changed since last baseline." Useful but not required.

### Decision 6: Baseline snapshot strategy

- **Options**: no snapshot, keep last-known-good `summary` EDN, keep full per-case history.
- **Recommendation**: **keep last-known-good summary** as `server/test/fixtures/baselines/public_docs_baselines.edn` after each deliberate tuning round. New runs compare aggregate metrics against it and surface "regressed vs baseline" in the report.
- **Why**: Absolute thresholds are unreliable with a small suite. No-regression against a known baseline is the most honest signal. Placing it under `fixtures/baselines/` (new subdir) keeps it version-controlled while visibly separating *oracle input* (suites) from *snapshot output* (baselines).
- **Open**: requires a small report-level diff helper. Could be a `bb` wrapper that loads the baseline and compares. Scope later.

### Decision 7: Source slicing

- **Options**: aggregate-only, label-only (report slice but do not gate), gate per slice.
- **Recommendation**: **gate per slice**. Each of `altinn-docs` and `digdir-docs` must independently pass the rerank gate. Aggregate-only pass is insufficient.
- **Why**: One source can regress while the other compensates, hiding the signal.
- **Resolved**: runner will surface `:summary-by-source-slice` analogously to `:summary-by-language`. Small change to `digdir.tools.diagnostics/rerank-benchmark` summary aggregation + `rerank-benchmark-report` rendering. **Landed 2026-04-20.**
- **V1 status (2026-04-20 pivot)**: per-slice gating is **latent but not active** — only `:altinn-docs` has content, so slice-gate == aggregate-gate in v1. The mechanism stands and activates automatically when a second source materializes.

### Decision 8: Bilingual parity

- **Options**: parity required, parity reported, parity ignored.
- **Recommendation**: **parity reported, not gated for v1**. Pair NO/EN cases via `:pair-id` where appropriate so the report can show per-pair rank deltas. Do not enforce equal rank until we see enough EN cases to be meaningful.
- **Why**: EN coverage in the corpus is asymmetric; forcing parity would over-fit the suite to a bias we do not yet understand.
- **Open**: revisit once ≥10 bilingual pairs exist.
- **V1 status (2026-04-20 pivot)**: zero English content exists in the materialized corpus. Bilingual parity is **not measurable** in v1. Revisit when the corpus expands.

### Decision 9: Context presence requirement

- **Options**: always required, required for answer-critical cases only, reported only.
- **Recommendation**: **required for answer-critical cases only**. Tag those cases with `:context-required true`. Runner invocation uses `--require-context true` for the agent smoke suite; for the rerank benchmark, `--require-context` stays off and a separate per-case field drives enforcement.
- **Why**: Context selection is a separate failure mode from rerank; gating both aggressively produces noise.
- **Open**: whether to extend the runner to honor a per-case `:context-required` flag. Current flag is suite-global.

### Decision 10: Reuse of `structured-eval` and `diagnostics`

- **Recommendation**: use as-is. `digdir.llm.structured-eval` stays unused in v1 (Decision 1). `digdir.tools.diagnostics` is the backing runner for `agent-budget-benchmark`; no changes needed.
- **Open**: none.

## 3. Gate Rules (v0, pre-baseline)

These are the rules the runners should enforce *after* the first three stable runs establish variance. Before that, enforce only "no case errors" and "no case loses all goldens."

### Rerank benchmark gate (`bb rerank-benchmark-report --fail-on-gate true`)

For each source slice (`altinn-docs`, `digdir-docs`) independently, and aggregate:

- `golden-present-in-retrank` ≥ baseline - 0.05 (no regression beyond one case equivalent).
- `mrr` ≥ baseline × 0.90.
- `p50-rank` ≤ baseline + 2.
- per-case: no case may lose all goldens from retrieved candidates; no case may regress past its `:max-acceptable-rank` (default 10).

### Rerank isolation fixture gate (`bb rerank-isolation-eval`)

- Every case must keep its golden in the top-K of the captured pool. Deterministic; no variance budget.

### Agent smoke gate (`bb agent-budget-report --fail-on-gate true`)

- Zero infra-errors.
- Zero `insufficient-context`.
- `golden-present` = true.
- `:expected-answer-pattern` matches final response.

## 4. Cross-References

- `claude-public-docs-eval-contract.md` — freezes the 7 decisions from the automated plan; most are answered above.
- `claude-public-docs-query-inventory.md` — Phase 1 candidate pool.
- `claude-public-docs-suite-specs.md` — EDN-shaped specs per suite.
- `claude-public-docs-eval-ops.md` — ownership, cadence, golden-update workflow.

## 5. Open Questions — Resolved 2026-04-18

All four sign-off blockers are resolved:

1. **Extend `rerank-benchmark` to slice by `:source-slice` now** — do it as a small, local framework change mirroring `:summary-by-language`, rather than an out-of-band wrapper. Feeds Decision 7 and §1.5. Land before or during Phase 2.
2. **Baseline EDN location**: `server/test/fixtures/baselines/public_docs_baselines.edn` — new subdir, visibly separates oracle input from snapshot output. Feeds Decision 6.
3. **CI posture**: on-demand + required-before-tuning for live rerank benchmark; nightly is a follow-up once three stable runs establish variance; no release-blocking gate in v1. Deterministic isolation fixture (Layer A.1) is the layer that earns a PR gate. Feeds §1.4.
4. **Golden-update owner**: `bdb@itonomi.com` is sole owner for now. Named in `claude-public-docs-eval-ops.md` when that doc lands (Phase 5). Revisit when team grows.

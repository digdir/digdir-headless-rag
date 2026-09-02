# public-docs Suite Specs — Phase 2

Status: Produced 2026-04-20 as Phase 2 of `public-docs-automated-evals-plan.md`, consuming the frozen contract, the requirements doc, and the Phase 1 query inventory.

This document specifies the exact EDN shapes, file paths, runner invocations, and gate semantics for every suite that the `public-docs` evals will produce. It is a desk-work deliverable — the suite files themselves are not yet populated. Populating them requires a `bb retrieve-debug` sweep to fill `:golden-chunk-ids` and is tracked in §8 below.

## 1. File Layout

All suite EDN lives under version control in `server/test/fixtures/` to match existing convention (dataset report §7.6).

```
server/test/fixtures/
├── rerank/
│   ├── public_docs_rerank_suite.edn                   ;; Suite 1 (enforced, live)
│   ├── public_docs_rerank_exploratory.edn             ;; Suite 4 (quarantined)
│   ├── public_docs_rerank_isolation_candidates.edn    ;; Suite 2 (captured pools)
│   └── public_docs_rerank_isolation_candidates_noautofilter.edn  ;; optional paired capture
├── agent/
│   └── public_docs_agent_smoke.edn                    ;; Suite 3 (enforced, live, smoke)
└── baselines/
    └── public_docs_baselines.edn                      ;; Baseline snapshot (requirements Decision 6)
```

Filenames use underscores because the existing runner's fixture resolver treats hyphens in paths inconsistently on some shells. Prefix `public_docs_` is mandatory so no accidental collision with `kudos`-scoped suites.

## 2. Suite 1 — Live Rerank Benchmark (primary gate)

**Path**: `server/test/fixtures/rerank/public_docs_rerank_suite.edn`
**Runner**: `bb rerank-benchmark-report digdir runtime public-docs --suite server/test/fixtures/rerank/public_docs_rerank_suite.edn`
**Mode**: on-demand + required-before-tuning (requirements §1.4). Not CI-gated on PRs.
**Gate layer**: Layer B (contract Decision 1).

### 2.1 EDN shape

```clojure
{:cases
 [{:id                  "altinn-app-data-model-add"       ;; kebab-case, unique
   :query               "How do I add a data model to an Altinn 3 app?"
   :language            :en                                ;; :no | :en | :nn | :mixed
   :source-slice        :altinn-docs                       ;; :altinn-docs | :digdir-docs — required
   :query-family        :navigational                      ;; representation, not gated
   :pair-id             "altinn-data-model"                ;; string; groups bilingual or paraphrase pairs
   :golden-chunk-ids    ["<chunk-id>"]                     ;; vector, ≥1 entry
   :max-acceptable-rank 10                                 ;; optional per-case override
   :context-required    false                              ;; optional per-case override; default false
   :notes               "canonical app-dev page"}          ;; freeform, never read by tooling
  ...]}
```

Field names that the runner already understands (`:id`, `:query`, `:language`, `:pair-id`, `:golden-chunk-ids`) map directly to the existing normalizer at `server/src-dev/digdir/tools/diagnostics.clj` around `normalize-benchmark-case`. The added fields for `public-docs`:

| Field | Runner behavior today | Action |
|---|---|---|
| `:source-slice` | Threaded through case result + summary; already landed. | Runner emits `:summary-by-source-slice`. |
| `:query-family` | Ignored — representation only. | No runner change. Tool humans use it; docs reference it. |
| `:max-acceptable-rank` (per-case) | **Not yet honored per-case.** Current flag is suite-global. | Deferred — see §9. Use the suite-global flag for v1. |
| `:context-required` (per-case) | **Not yet honored per-case.** Current flag is suite-global. | Deferred — see §9. |

Unsupported-per-case fields are still accepted in the EDN (the normalizer keeps unknown keys inside the merged case map), so writing them today is not wasted — they pre-stage the Phase 6 runner change.

### 2.2 Size & source-balance requirements

**V1 (revised 2026-04-20 per dataset-report Appendix B):**

- ≥3 cases per required slice: `:source-slice :altinn-docs`, `:language :no`.
- ≥1 case with `:query-family :exact-lookup`.
- ≥1 case with `:query-family :paraphrase`.
- Target **10-15 total cases** drawn from the 62-doc Altinn-NO corpus (requirements §1.3 v1 sizing).

**V2 (deferred until digdir-docs + English materialize):**

- ≥3 cases per required slice: `:source-slice :altinn-docs`, `:source-slice :digdir-docs`, `:language :no`, `:language :en`.
- Nynorsk optional (`:nn`). If present, never gated as a required slice — report-only.
- Target 20-40 total cases, source-balanced.

### 2.3 Gate semantics (contract Decision 5)

Pre-baseline (first three runs):

- No case errors.
- No case loses all goldens from retrieved candidates.
- `--fail-on-gate true` must pass with runner defaults (`--max-acceptable-rank 10`, `--require-context false`).

Post-baseline, per slice (source + language) independently, and aggregate:

- `golden-present-in-retrank` ≥ `baseline - 0.05`.
- `mrr` ≥ `baseline × 0.90`.
- `p50-rank` ≤ `baseline + 2`.

Per case:

- No regression past the case's `:max-acceptable-rank` (or runner default if per-case override is not yet honored).
- No case may lose all goldens.

Per-slice enforcement is **computed from** `:summary-by-source-slice` and `:summary-by-language` in the runner payload — the runner already surfaces them (requirements §4.7, landed 2026-04-20). The gate-enforcement helper that cross-references against the baseline EDN is the remaining Phase 2 code change (§7).

## 3. Suite 2 — Rerank Isolation Fixture (deterministic)

**Path**: `server/test/fixtures/rerank/public_docs_rerank_isolation_candidates.edn`
**Runner**: `bb rerank-language-benchmark digdir runtime public-docs --fixture <path>` + `bb rerank-language-report` for compact output.
**Captured via**: `bb capture-rerank-language-candidates digdir runtime public-docs --suite <live-suite> --out <fixture>`.
**Mode**: **CI-gated once stable** (requirements §1.4).
**Gate layer**: Layer A.1 (contract Decision 1).

### 3.1 EDN shape (captured fixture)

The capture runner writes this automatically — included here so reviewers and future authors know what to expect:

```clojure
{:capture       {:captured-at "2026-04-20T..."
                 :dataset-ref {:tenant "digdir" :dataset-config-key "public-docs"}
                 :suite "server/test/fixtures/rerank/public_docs_rerank_suite.edn"
                 :collection-names {...}
                 :effective-params {...}
                 :elapsed-ms 12345.6}
 :cases
 [{:id                    "altinn-app-data-model-add"
   :pair-id               "altinn-data-model"
   :language              :en
   :source-slice          :altinn-docs
   :query                 "How do I add a data model..."
   :queries               ["..."]                           ;; relaxed query set at capture time
   :golden-chunk-ids      ["<chunk-id>"]
   :search-attribution    {...}
   :merged-chunk-ids      ["..."]
   :retrieved-chunk-ids   ["..."]
   :error                 nil}
  ...]}
```

`:source-slice` is now threaded through the capture path (landed 2026-04-20 with the framework change) so captured fixtures carry the slice automatically.

### 3.2 Selection rubric

Capture should be driven from a **curated subset** of Suite 1 — 3-6 cases — chosen because they are rank-sensitive or historically noisy. Not every live case deserves an isolation fixture. Candidates for the first capture:

- Any bilingual pair where the EN and NO ranks diverge by ≥5 in the live run.
- Any `:factual-numeric` case where auto-filter fallback affected ordering.
- Any case whose rerank rank fluctuated across the three baseline runs.

### 3.3 Gate semantics

Deterministic: every captured case must keep its golden in the rerank top-K. No variance budget. Failure is a binary signal: either the rerank path regressed, or the capture is stale and needs to be re-run (classified as `materialization-drift` — contract Decision 7).

## 4. Suite 3 — Agent Smoke

**Path**: `server/test/fixtures/agent/public_docs_agent_smoke.edn`
**Runner**: `bb agent-budget-report digdir runtime public-docs --suite <path>`.
**Mode**: on-demand + nightly; not blocking on PRs (requirements §1.4).
**Gate layer**: Layer C (contract Decision 1).

### 4.1 EDN shape

Extends Suite 1's per-case shape with agent-specific fields (contract Decision 2 optional fields):

```clojure
{:cases
 [{:id                      "digdir-krr-reservasjon-varighet"
   :query                   "Hvor lenge varer en reservasjon mot digital kommunikasjon i KRR?"
   :language                :no
   :source-slice            :digdir-docs
   :query-family            :factual-numeric
   :golden-chunk-ids        ["<chunk-id>"]
   :expected-answer-pattern "(?is)\\b(inntil\\s+\\d+|\\d+\\s*(år|ar|år))\\b"   ;; regex, required
   :budget-dimension        :read-chars                                         ;; required for agent
   :current-budget          {:max-iterations 6 :max-tool-calls 12 :max-chunks 12 :max-read-chars 20000}
   :relaxed-budget          {:max-iterations 10 :max-tool-calls 20 :max-chunks 20 :max-read-chars 40000}
   :context-required        true                                                ;; answer-critical
   :notes                   "factual-numeric; runner's --require-context true applies"}
  ...]}
```

### 4.2 Size & composition (requirements §1.3)

- 8-12 cases drawn from Suite 1.
- All cases must be stable in Suite 1 (baseline-confirmed) before promotion to Suite 3.
- Mix ≥3 Norwegian and ≥3 English; single Nynorsk smoke permitted, never gated.
- Preference for `:factual-numeric` and `:exact-lookup` — they have tractable answer patterns.

### 4.3 Gate semantics (requirements §3, contract Decision 5)

The runner is invoked with `--fail-on-gate true --require-context true`. A case passes iff all of:

- No infra error.
- No `insufficient-context`.
- Golden present in retrieval trace.
- `:expected-answer-pattern` matches the final response.

## 5. Suite 4 — Exploratory Candidates (quarantined)

**Path**: `server/test/fixtures/rerank/public_docs_rerank_exploratory.edn`
**Runner**: same as Suite 1 but never with `--fail-on-gate true`.
**Mode**: on-demand only; never in CI or release pipelines.
**Gate layer**: none.

### 5.1 EDN shape

Identical to Suite 1 (§2.1). The distinction is purely file-level — reviewers and tooling know to exclude this file from any CI rollup.

### 5.2 Promotion workflow (Phase 1 §8, contract Decision 7)

A case promotes from Suite 4 → Suite 1 iff:

1. `:golden-chunk-ids` is non-empty and validated via `bb retrieve-debug`.
2. Three consecutive `bb rerank-benchmark` runs produce the same primary `:rerank-position` (±2 slots).
3. No run produces an error on the case.
4. Any `:max-acceptable-rank` override in the suite file is accompanied by a `:notes` entry.

Demotion (Suite 1 → Suite 4) is legitimate when a case becomes noisy after source drift. Commit message carries `golden-update: source-drift` per contract Decision 7.

## 6. Baseline Snapshot

**Path**: `server/test/fixtures/baselines/public_docs_baselines.edn`
**Purpose**: provide the no-regression anchor for post-baseline gates (requirements Decision 6).
**Lifecycle**: updated after each deliberate tuning round, by the golden owner (requirements §5, resolved: `bdb@itonomi.com`).

### 6.1 EDN shape

```clojure
{:recorded-at      "2026-04-20T10:00:00Z"
 :recorded-by      "bdb@itonomi.com"
 :suite            "server/test/fixtures/rerank/public_docs_rerank_suite.edn"
 :runner           "rerank-benchmark"
 :effective-params {:top-k 100
                    :context-top-k 30
                    :max-acceptable-rank 10
                    :auto-filter-enabled true}
 :dataset-ref      {:tenant "digdir" :dataset-config-key "public-docs"}
 :colbert-service  {:endpoint "..." :version "..."}       ;; informational
 :aggregate
 {:cases                      26
  :cases-succeeded            26
  :golden-present-in-retrank  24
  :golden-present-in-context  20
  :mrr                        0.62
  :p50-rank                   3
  :p95-rank                   12}

 :by-source-slice
 {:altinn-docs {:cases 12 :golden-present-in-retrank 11 :mrr 0.58 :p50-rank 4 :p95-rank 13}
  :digdir-docs {:cases 14 :golden-present-in-retrank 13 :mrr 0.66 :p50-rank 3 :p95-rank 11}}

 :by-language
 {:no {:cases 10 :mrr 0.64 :p50-rank 3}
  :en {:cases 15 :mrr 0.60 :p50-rank 4}
  :nn {:cases 1  :mrr 0.25 :p50-rank 18}}   ;; informational only — :nn not gated

 :per-case
 {"altinn-app-data-model-add"     {:rerank-position 2 :context-position 2}
  "altinn-access-list-definition" {:rerank-position 1 :context-position 1}
  ...}}
```

Numbers above are illustrative — they will be replaced with real values after the first three stable benchmark runs.

### 6.2 What triggers a baseline refresh

Any of:

- Three stable runs with the current config establish the **first** baseline (Go-live trigger).
- A deliberate retrieval/rerank/auto-filter tuning change is merged → re-record baseline.
- A `golden-update: retrieval-tuning` commit lands → re-record baseline.

What does **not** trigger a refresh:

- Source drift (goldens updated but parameters unchanged) — baseline aggregates still apply.
- Individual case noise — per-case numbers are informational, not gate inputs.

## 7. Baseline Diff Helper (new code, Phase 2 code change)

The gate rule "`mrr ≥ baseline × 0.90`" requires a small piece of tooling that does not exist today:

- Read the current runner payload.
- Read the baseline EDN at `server/test/fixtures/baselines/public_docs_baselines.edn`.
- Produce pass/fail per slice and aggregate.

Recommended implementation (requirements Decision 6, "scope later"):

- A new top-level `bb` task: `bb public-docs-gate-report`. Takes the last-emitted runner payload (file or stdin) + the baseline path. Emits a diff table and exits non-zero on regression.
- Kept in `bb.edn` as shell orchestration, reusing the existing `rerank-benchmark-report` output parsing.

Not needed to start running the benchmark on-demand. Only needed when we move from "manual inspection" to "gate enforcement." Track as a Phase 2 follow-up, not a blocker.

## 8. Golden Fill-In & First-Run Plan

Authoring the suite files hinges on resolving the runtime blockers from dataset report §9:

1. Resolve `skills.*` config (§9.1 of dataset report) via `bb config-get` sweep.
2. For each of the 26 candidates in `claude-public-docs-query-inventory.md`:
   - `bb retrieve-debug digdir runtime public-docs "<query>" --top-k 20`.
   - Identify the chunk IDs that truly answer the query.
   - Record them in `public_docs_rerank_exploratory.edn`.
3. Run `bb rerank-benchmark` against the exploratory suite three times.
4. Promote stable cases (§5.2) into `public_docs_rerank_suite.edn`.
5. Record the aggregate as `public_docs_baselines.edn`.
6. Capture isolation fixtures for 3-6 hardest cases.
7. Select 8-12 agent smoke cases from the stable suite; add answer patterns; author `public_docs_agent_smoke.edn`.

None of these steps are blocked by code changes. The framework change (`:source-slice` aggregation) already landed.

## 9. Deferred Runner Enhancements

Fields named in §2.1 that are not yet honored per-case:

- **`:max-acceptable-rank` per-case**: runner currently treats this as a suite-global CLI flag. Per-case override requires a small change to `run-benchmark-case` at `server/src-dev/digdir/tools/diagnostics.clj` (substitute the CLI default with `(or (:max-acceptable-rank case-def) max-acceptable-rank)`). Ship with Phase 2 work once the first baseline is recorded and outlier cases emerge.
- **`:context-required` per-case**: same shape as above (requirements Decision 9). Runner currently treats `--require-context` as suite-global. The agent smoke suite can keep global `true` for all cases; the rerank suite can keep global `false` and add the per-case escape for answer-critical rows only.

Neither change is a blocker for starting the benchmark runs. Both are recommended to land before the post-baseline gates are enforced.

## 10. Cross-References

- Phase 0 (contract): `claude-public-docs-eval-contract.md`.
- Requirements: `claude-public-docs-evals-requirements.md`.
- Dataset shape: `claude-public-docs-dataset-report.md`.
- Phase 1 candidate pool: `claude-public-docs-query-inventory.md`.
- Ops + ownership: `claude-public-docs-eval-ops.md` (Phase 5, not yet authored).

## 11. Change Log

- 2026-04-20: Initial suite specs. Framework change for `:source-slice` aggregation already landed (see `digdir.tools.diagnostics/slice-summary`).
- 2026-04-20 (later): §2.2 revised for v1 single-source scope per dataset-report Appendix B. Multi-slice spec preserved under v2.

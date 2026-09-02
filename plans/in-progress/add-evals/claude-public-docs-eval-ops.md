# public-docs Evals — Operations & Maintenance

Status: Produced 2026-04-20 as Phase 5 of `public-docs-automated-evals-plan.md`, consuming the frozen contract, requirements, inventory, and suite specs.

This document defines *how* the `public-docs` evals are owned, run, and updated over time. Scope is organizational, not code. For EDN shapes see `claude-public-docs-suite-specs.md`; for decisions see `claude-public-docs-eval-contract.md`.

## 1. Ownership

| Role | Owner | Fallback |
|---|---|---|
| Suite maintainer (authoring, golden updates) | bdb@itonomi.com | — (sole owner for now) |
| Baseline refresh authority | bdb@itonomi.com | — |
| Runtime sweep (fill-in goldens, variance runs) | bdb@itonomi.com | — |
| Config-change gatekeeper (sign-off before `skills.*` tuning) | bdb@itonomi.com | — |

Sole ownership is intentional for v1. Revisit when a second engineer joins the `public-docs` surface or when the inventory doubles in size. When that happens, split into:

- **Suite maintainer** (daily): approves golden updates, adjudicates classifier labels.
- **Platform owner** (per-tuning): signs off on `skills.*` config changes and the accompanying baseline refresh.

No automated PR assignee for now. Until a second owner exists, PRs that touch any `public_docs_*` fixture or `skills.*` config scoped to `digdir/public-docs` must have `bdb@itonomi.com` in the review chain.

## 2. Cadence

### 2.1 Live rerank benchmark (Suite 1)

- **On-demand**: any time a developer suspects retrieval/rerank regression on `public-docs`.
- **Required-before-merge** (enforced via reviewer check, not CI) for any change to:
  - `skills.retrieval.*` scoped to `{digdir, runtime, public-docs}`.
  - `skills.rerank.*` scoped to same.
  - `skills.agent.*` scoped to same.
  - Pipeline materialization config that could shift chunk IDs (any `pipelines.{altinn-docs,digdir-docs}.*`).
- **Not** a blocking PR CI gate (requirements §1.4 — too expensive and too flaky for PR-level enforcement on a cold-start suite).
- **Nightly run**: eligible once three stable runs establish the baseline (requirements §5 Q3 resolution). Until then, no nightly.

### 2.2 Rerank isolation fixture (Suite 2)

- **CI-gated on PRs** once the first capture is stable (requirements §1.4).
- Re-captured whenever the deterministic test flips from green to red — the expected classifier is `materialization-drift` (see §4).

### 2.3 Retrieval merge deterministic test (Layer A.2)

- Runs in normal `bb test`. No opt-in, no env gate.
- Added only if/when a `public-docs`-specific merge failure mode appears (requirements §1.3). Not pre-populated.

### 2.4 Agent smoke (Suite 3)

- **On-demand**: when modifying agent loop, sufficiency classifier, or `read_signals` behavior.
- **Nightly** once the rerank benchmark goes nightly. Aligned so regressions are attributable to a single date.
- **Not** blocking PRs.

### 2.5 Exploratory sweep (Suite 4)

- **On-demand only**. Never in any automated schedule.
- Revisited quarterly to prune cases that have been unstable >3 months — those are either promoted (if they stabilize) or dropped.

## 3. Golden Update Workflow

### 3.1 Classifier required (contract Decision 7)

Every commit that changes `:golden-chunk-ids` (or adds/removes a case from an enforced suite) must carry one of these tokens in the commit message **first line** or PR description:

| Classifier | Meaning | Typical cause |
|---|---|---|
| `golden-update: source-drift` | Website content moved or changed. | Altinn or Digdir published a doc edit. |
| `golden-update: materialization-drift` | Chunker config changed, chunk IDs shifted. | A pipeline re-materialization round-tripped IDs. |
| `golden-update: retrieval-tuning` | We deliberately moved the expected answer location. | Tuning changed which chunk wins for the query. |
| `golden-update: oracle-correction` | The original golden was wrong. | Case-authoring error found post-merge. |

No catch-all or "other" classifier. If none fits, stop and revise the proposal — the failure mode is not yet understood, and rushing a golden change loses the regression signal (requirements §2 Decision 5).

### 3.2 Drive-by edits are rejected

A PR whose primary purpose is unrelated to `public-docs` evals must not include a golden change, even if the diff would be small. Open a separate PR with the correct classifier. This is a hard review rule, not a guideline.

### 3.3 Review checklist

Reviewer confirms, in order:

1. Classifier is present and accurate.
2. The case's `:id` still matches the query intent after the change.
3. If the classifier is `source-drift` or `materialization-drift`, the underlying source/chunk change is linked (URL, pipeline commit, or `bb capture-*` run artifact).
4. If the classifier is `retrieval-tuning`, the accompanying `skills.*` config change is in the same PR.
5. If the classifier is `oracle-correction`, the original authoring mistake is described in the PR body.

If any check fails, request changes. Do not squash-through.

### 3.4 Baseline coupling

- `source-drift` and `oracle-correction` → baseline aggregates unchanged; no baseline refresh required.
- `materialization-drift` → baseline refresh required (aggregate `p50-rank` and `mrr` will shift).
- `retrieval-tuning` → baseline refresh required.

When a refresh is required, the same PR re-records `server/test/fixtures/baselines/public_docs_baselines.edn` using the three-run average across the new config. Do not merge a tuning PR that ships a config change without the updated baseline.

## 4. Drift Handling Playbooks

### 4.1 Isolation fixture flips red

Likely classifier: `materialization-drift`. Steps:

1. Re-run `bb capture-rerank-language-candidates digdir runtime public-docs --suite <live-suite> --out <fixture>` against the current collection.
2. Diff the old vs new fixture's `:merged-chunk-ids` — if sets are disjoint, chunk IDs shifted and the capture must be refreshed.
3. Commit the new capture with `golden-update: materialization-drift`. Same PR updates any `:golden-chunk-ids` in Suite 1 that now reference stale IDs.
4. Refresh baseline (§3.4).

### 4.2 Live benchmark regresses on one slice but not the other

**V1 (single-source Altinn-NO — 2026-04-20 pivot):** this playbook is latent. With only one materialized source, a slice regression is always an aggregate regression. Fall through to §4.3.

**V2 (multi-source — restore when digdir-docs + English materialize):** Likely cause: single source changed (Altinn or Digdir published, but not both). Steps:

1. Check the per-slice delta in `:summary-by-source-slice` (slice gate is source-aware — contract Decision 3).
2. If only one slice regressed, inspect `:summary-by-language` for a correlated language skew.
3. If the failing cases share a `:query-family`, suspect a query-planning regression rather than source drift.
4. If source-drift is confirmed, update only the affected cases' goldens with `golden-update: source-drift`. Leave the other slice's cases untouched. Baseline aggregates do not refresh; per-case `:rerank-position` comparisons update naturally on next run.

### 4.3 `golden-present-in-retrank` drops but `mrr` does not

Typically means a case is missing its golden entirely (hard miss). Steps:

1. Find the case(s) where `(:rerank-position g) nil` in the results vector.
2. Run `bb retrieve-debug digdir runtime public-docs "<query>"` to see whether the golden is in the top-100 at all.
3. If not in top-100, the source likely changed — classifier `source-drift`, author updated golden.
4. If in top-100 but beyond rerank top-K, increase `--top-k` is not the answer — the signal is real. Either tune rerank (→ `retrieval-tuning`) or flag the case as exploratory (→ move to Suite 4).

### 4.4 Nynorsk case regresses below rank 20

Ignore. Nynorsk is report-only per requirements Decision 8 and suite-specs §2.2. Log the run, do not promote, do not gate.

### 4.5 Agent smoke case fails answer pattern but golden is present

Three possibilities:

1. Model drift (e.g., LLM version bumped upstream) — if the smoke suite is otherwise stable, patch the regex to accept the new phrasing. Classifier `oracle-correction` with a brief explanation.
2. Sufficiency classifier over-restricted — reproduce standalone, file a bug against the classifier, do not update the golden.
3. Insufficient-context path fired incorrectly — same as above. The answer pattern is the oracle; do not weaken it to make the test pass.

## 5. Change-Tracking Conventions

### 5.1 PR title prefixes

- `evals(public-docs): ...` — any change to a `public_docs_*` fixture or to this doc family.
- `evals(public-docs): runner: ...` — a change to `digdir.tools.diagnostics` or `bb.edn` task definitions that affects the `public-docs` suites.
- `config(public-docs): ...` — a `skills.*` config change for `digdir/public-docs`. Triggers required-before-merge rerank benchmark (§2.1).

### 5.2 Commit scope discipline

One logical change per commit. Specifically:

- Do not combine a golden update and a query text edit in one commit.
- Do not combine a config tuning and a baseline refresh in one commit — use two commits in the same PR.
- Do not combine a `source-drift` golden update with any unrelated case changes.

### 5.3 What to record in the PR body

For any PR that touches an enforced suite or the baseline:

```
## Eval change summary
- Suite: public_docs_rerank_suite.edn
- Classifier: golden-update: source-drift
- Cases touched: digdir-krr-lookup, digdir-krr-email
- Baseline refresh: no
- Run artifact: (paste the last-emitted EDN payload or link)
```

This is a reviewer aid, not a template the runner reads.

## 6. Failure Triage

When a gate fails, do not reopen a PR with fixtures relaxed. Investigate in this order:

1. **Is this an infra blip?** Re-run once. If the error is `:error-type :infra-unavailable` from the Typesense preflight, ignore and re-run later. Do not gate on transient infra.
2. **Is this a single-case regression?** Check `:failures` in the payload. If one case failed and others are stable, treat as a case-level investigation; do not blame the runner or the suite.
3. **Is this an aggregate regression on a slice?** Check `:summary-by-source-slice` and `:summary-by-language`. If one slice regressed wholesale, suspect a config or source change.
4. **Is this a baseline mismatch?** If the runner says pass but the baseline-diff helper says regress, trust the baseline-diff. Either tune until parity, or refresh the baseline with classifier `retrieval-tuning`.
5. **Is the eval itself broken?** Only after ruling out the above. A suspected eval bug requires a runner-code fix, not a suite edit.

## 7. Quarterly Health Check

Every quarter (first review cadence: 2026-07-20), the owner runs through:

- [ ] Are all enforced cases still stable? (three-run variance check)
- [ ] Any exploratory case older than three months that still is not stable? Demote or drop.
- [ ] Is the bilingual-pair count still below 10? Gate status stays "report-only" — no change.
- [ ] Has playground-log volume crossed the threshold for log-mining (requirements Decision 2 open item)? If so, propose a Phase 6 amendment.
- [ ] Is the baseline older than 90 days with no config change? Verify by re-running; if aggregates have drifted by >5% without a tuning change, investigate source drift before refreshing.

Record the quarterly outcomes in a short appendix to this doc, dated. Do not create separate review docs.

## 8. Retirement / Sunset

This eval suite is retired or transferred when:

- The `public-docs` dataset is replaced by a successor dataset, or
- Ownership transfers. In that case, the new owner confirms this document's §1 reflects their team before assuming responsibility.

In either case, move the suite files from `server/test/fixtures/` to `server/test/fixtures/archived/YYYY-MM/` rather than deleting — the historical fixtures remain useful for back-comparison.

## 9. Cross-References

- Phase 0 (contract): `claude-public-docs-eval-contract.md`.
- Requirements: `claude-public-docs-evals-requirements.md`.
- Dataset shape: `claude-public-docs-dataset-report.md`.
- Phase 1 candidate pool: `claude-public-docs-query-inventory.md`.
- Phase 2 suite specs: `claude-public-docs-suite-specs.md`.

## 10. Change Log

- 2026-04-20: Initial ops doc. Ownership recorded as sole-owner (`bdb@itonomi.com`) pending team growth.
- 2026-04-20 (later): §4.2 marked as v2-only playbook per dataset-report Appendix B. V1 is single-source.

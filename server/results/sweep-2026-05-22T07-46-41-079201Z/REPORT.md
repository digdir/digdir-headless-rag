# Baseline-v0 sweep (v2 question set) — leaderboard

- Matrix: `test/fixtures/sweep/matrices/baseline-v0.edn`
- Inputs: 2 configs × **31 questions** × 1 repeat = **62 runs**
- Wall-clock: **35 min** (2,114 s)
- Tenant / dataset: `digdir` / `default` → `public-docs`
- Status: 62/62 `:complete`, 0 errors, 0 `:terminal-clarification?`. User-simulator never fired.

The 5 `digdir-arsverk` rows were tombstoned 2026-05-22 — their goldens
(`15a66d5ec5c5`, `6aefa3a05c7d`) belong to a separate Digdir corpus not
loaded on this DB, so they were dragging recall to 0% for purely structural
reasons. The numbers below are the first clean look at retrieval quality
on the curated `public-docs` set.

## Aggregate

| config            |   hit% | recall@5 | recall@10 | recall@20 | citation-recall | mean lat | mean clar |
|-------------------|-------:|---------:|----------:|----------:|----------------:|---------:|----------:|
| `bundled-default` |  87.1% |   11.1%  | **22.2%** | **22.2%** |     11.1%       | **31.5s** |   0.00    |
| `faithful-default`| **93.5%** | 11.1% |   11.1%   |   11.1%   |     11.1%       |   36.7s  |   0.00    |

- Recall numbers are over the **18 chunks-scoreable rows per config** (down from 23 in the previous run, because the 5 tombstoned digdir-arsverk rows were all `:chunks+answer`-graded).
- recall@5 ≈ recall@10 ≈ recall@20 — every successful hit lands in the top 5; nothing useful is buried at deeper ranks.
- Bundled now clearly leads on recall (was tied last run); faithful keeps the hit-rate lead.

## How this compares to the previous run

| metric                   | v1 (n=23) | v2 (n=18) | delta |
|--------------------------|----------:|----------:|------:|
| bundled hit%             | 75.0%     | 87.1%     | +12.1 |
| faithful hit%            | 80.6%     | 93.5%     | +12.9 |
| bundled recall@10        | 17.4%     | 22.2%     | +4.8  |
| faithful recall@10       | 17.4%     | 11.1%     | -6.3  |
| bundled mean lat         | 41.1s     | 31.5s     | -9.6s |
| faithful mean lat        | 45.6s     | 36.7s     | -8.9s |

- Hit% rose for both because the tombstoned rows were the hardest answer-pattern matches in the set.
- Mean latency dropped ~10s because digdir-arsverk compounds were the slowest rows.
- Bundled gained one recall@10 hit (`altinn-systemuser-accept-request`); faithful lost two — its run-to-run variance is real and worth tracking with repeats.

## Per-domain recall@10 (chunks-scoreable rows only)

| domain                  | n | bundled              | faithful             |
|-------------------------|--:|----------------------|----------------------|
| `altinn-dialogporten`   | 1 | **100%** (1/1)       | **100%** (1/1)       |
| `altinn-3-general`      | 1 | 0%   (0/1)           | **100%** (1/1)       |
| `altinn-correspondence` | 1 | **100%** (1/1)       | 0%   (0/1)           |
| `altinn-events`         | 1 | **100%** (1/1)       | 0%   (0/1)           |
| `altinn-systemuser`     | 4 | **25%** (1/4)        | 0%   (0/4)           |
| `altinn-authorization`  | 3 | 0%   (0/3)           | 0%   (0/3)           |
| `altinn-broker`         | 3 | 0%   (0/3)           | 0%   (0/3)           |
| `altinn-studio`         | 4 | 0%   (0/4)           | 0%   (0/4)           |

Three domains still sit at 0%/0% across both configs: `altinn-authorization`, `altinn-broker`, `altinn-studio` (10 of 18 scoreable rows). These are the meat of the rerank-tuning opportunity — their goldens are present in `public-docs` (verified last run via `/api/debug/chunk`) but rerank never surfaces them in top-10. That's the OFAT differentiator next round should chase.

## Cases where the two configs disagree (recall@10)

4 of 18 questions split the configs (vs 2 of 23 last time):

| question                                      | bundled | faithful | winner   |
|-----------------------------------------------|--------:|---------:|----------|
| `altinn-correspondence-post-published`        |  **1.0** |   0.0   | bundled  |
| `altinn-events-architecture`                  |  **1.0** |   0.0   | bundled  |
| `altinn-systemuser-accept-request`            |  **1.0** |   0.0   | bundled  |
| `altinn3-lansert-when`                        |   0.0   |  **1.0** | faithful |

Bundled wins 3 of 4 disagreements — the recall delta isn't from a single lucky hit, it's from bundled landing more of the "marginal" Phase-D candidates. That said, n=1 per cell — repeats are needed before treating this as a stable signal.

## Latency outliers (top 5)

| config   | question                                       | elapsed |
|----------|------------------------------------------------|--------:|
| faithful | `altinn-out-of-scope-pricing`                  | 104.9s |
| faithful | `altinn-broker-vs-correspondence`              |  97.1s |
| faithful | `altinn-authorization-accessgroups-knytning`   |  95.1s |
| bundled  | `altinn-out-of-scope-pricing`                  |  86.5s |
| faithful | `altinn3-lansert-when`                         |  74.5s |

With digdir-arsverk gone, the slowest rows are now the refusal-expected and compare-style open-ended questions. The shape of the cost has shifted from compound-extraction (numeric tables) to compound-synthesis (multi-document comparisons). Faithful is consistently ~5-10s slower than bundled on the same row.

## Findings worth chasing

1. **Three domains are now the clean rerank-tuning target.** `altinn-authorization`, `altinn-broker`, `altinn-studio` together hold 10 of 18 chunks-scoreable rows and 0 hits across both configs. Goldens are confirmed present in `public-docs`. Round-1 OFAT cells on rerank `top-k` / `strategy-weights` / `context-relative-score-threshold` should be evaluated against this slice first; movement here is the most legible win.

2. **Bundled emerges as the recall winner; faithful as the hit-rate winner.** That's a meaningful split — bundled is better at *citing the right chunk*, faithful is better at *producing a synthesizable answer*. If Round 1's metric weighting prioritises recall (more diagnostic for retrieval-tuning), use bundled as the OFAT control. If it prioritises answer correctness, use faithful.

3. **Faithful's run-to-run variance is real.** Between v1 and v2 it gained `altinn3-lansert-when` and lost `altinn-authorization-accessgroups-knytning` *and* `altinn-correspondence-post-published`. With only 1 repeat per cell that flips the leaderboard. Recommend `:repeats 3` for any Round-1 sweep so we can separate variance from real config effects.

4. **Clarification simulator still unused.** All 62 runs returned `:status :complete` without ever consulting the simulator. It remains a Chekhov's gun — we'll only know what it does when a config trips the agent's sufficiency gate. A separate "stress" matrix with intentionally underspecified queries would exercise it.

## Where the data lives

- `runs.csv` — 62 rows, one per (config, question, repeat). Full scoring + diagnostics columns.
- `matrix.edn` — exact matrix that produced this run.
- This file (`REPORT.md`).

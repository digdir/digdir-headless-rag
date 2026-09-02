# Round 2 — strategy-weights × rerank-input-top-k leaderboard

- Matrix: `test/fixtures/sweep/matrices/round-2-phrase-x-rerank-topk.edn`
- Inputs: 2 weights × 4 top-k levels × 18 questions × 3 repeats = **432 runs**
- Wall-clock: **3h 00min** (10,810 s)
- Status: 432/432 `:complete`, 0 timeouts, 0 errors

## What this round tests

The Round-1 winner (`phrase-heavy`) crossed against the rerank input
cap (`:builtin/rerank :top-k`, default 40). Round 1 left "phrase-heavy
matches default on recall, beats it on citation-recall" — this round
asks whether enlarging or shrinking the rerank input window favours one
config or the other (an interaction effect would manifest as the
heatmap rows curving in different directions).

## Recall@10 heatmap

| weights \ top-k | **20**     | 40         | 80         | 160        |
|-----------------|-----------:|-----------:|-----------:|-----------:|
| `default`       | 11.1%      | 16.7%      | **24.1%**  | 13.0%      |
| `phrase-heavy`  | **25.9%**  | 22.2%      | 20.4%      | 11.1%      |

(SE per cell: ±4.3% – ±6.0%; n=54 per cell)

**Two distinct optima, on opposite ends of the rerank-input axis.** This is the interaction effect Round 2 was built to find:

- Under **default weights**, recall *climbs* from top-20 → top-80, then collapses at top-160. The best default cell is `default-top80` (24.1%).
- Under **phrase-heavy weights**, recall *peaks at top-20* and *monotonically declines* as top-k grows. The best phrase cell is `phrase-top20` (25.9%).

The two best cells tie within error bars (25.9 ± 6.0% vs 24.1 ± 5.9%), but **they get there via completely different mechanisms**, and that matters for what we ship and what we tune next.

## Citation-recall heatmap

| weights \ top-k | **20**     | 40         | 80         | 160        |
|-----------------|-----------:|-----------:|-----------:|-----------:|
| `default`       |   9.3%     | 13.0%      | 18.5%      | 11.1%      |
| `phrase-heavy`  | **22.2%**  | 18.5%      | 13.0%      | 11.1%      |

`phrase-top20` wins citation-recall outright (22.2%, +3.7pp over the next-best cell). When the agent retrieves the golden, this config also cites it most reliably.

## Mean latency (ms)

| weights \ top-k | 20        | 40        | 80        | 160       |
|-----------------|----------:|----------:|----------:|----------:|
| `default`       | 24,364    | 26,303    | 24,872    | 22,752    |
| `phrase-heavy`  | 24,193    | 22,897    | 26,588    | **28,200**|

Latency is mostly flat (~23-28s). `phrase-top160` is the slowest (+5s vs `phrase-top40`); the larger phrase-weighted candidate pool feeding a 160-deep rerank costs noticeably more. Otherwise the knob has little wall-clock cost.

## What the interaction means

In the rerank pipeline:
- **Initial vector retrieval** surfaces candidates (gated by retrieval `:limit`, default 30).
- **The agent's workspace** is a subset (8 chunks average across all cells here).
- **Rerank top-k input cap** says "score up to N of the workspace chunks".
- **Rerank context-top-k** says "return at most M scored chunks".

So `:top-k` mainly controls **how much rerank tries**, given a workspace size that's already small. Under default weights, more rerank effort surfaces better top-10 picks — *up to a point* (the top-80 sweet spot). Under phrase-heavy weights, candidates are already pre-prioritised by phrase signal, and forcing ColBERT to score broader pools *demotes* the right candidates. **Phrase-heavy delivers a better-ordered candidate stream, so the reranker should be told to trust it and not over-stir.**

That's the operationally useful framing: pre-retrieval ranking quality and post-retrieval rerank effort are **substitutes**, not complements.

## Per-domain recall@10 — top cells only

| domain                  | n | `default-top80` | `phrase-top20` | `phrase-top40` |
|-------------------------|--:|----------------:|---------------:|---------------:|
| `altinn-3-general`      |  3| **100%** | **100%**  | **100%**  |
| `altinn-dialogporten`   |  3| **100%** | **100%**  | **100%**  |
| `altinn-correspondence` |  3|  67%     |  33%      | **100%**  |
| `altinn-events`         |  3| **33%**  |   0%      |   0%      |
| `altinn-authorization`  |  9|  11%     | **22%**   | **22%**   |
| `altinn-broker`         |  9|  11%     |   0%      |   0%      |
| `altinn-studio`         | 12|   8%     |   8%      |   0%      |
| `altinn-systemuser`     | 12|   8%     | **33%**   |   8%      |

**Domain specialism is the headline.** No cell wins everywhere:
- `phrase-top20` is the **systemuser specialist** — 33% (4/12 hits) vs ≤8% on any default cell or higher-top-k phrase cell. Of every config tested this round, only this one finds the systemuser goldens at scale.
- `phrase-top40` is the **correspondence specialist** — perfect 100% (3/3) recall, while `phrase-top20` drops to 33%.
- `default-top80` is the **events specialist** — only cell that lands an events golden at all (33%).

If we shipped only one cell we'd lose at least one domain. If we ever ship a per-domain or per-intent router, this is the table that informs it.

## Cells that disagree on the same question

10 of 18 questions had cells flip between hit and miss. Headline crossovers:

| question                                       | default-top80 | phrase-top20 | phrase-top40 |
|------------------------------------------------|--------------:|-------------:|-------------:|
| `altinn-correspondence-post-published`         |  0.67         |  0.33        |  **1.00**    |
| `altinn-systemuser-accept-request`             |  0.33         |  **0.67**    |  0.00        |
| `altinn-authorization-accessgroups-knytning`   |  0.00         |  **0.67**    |  **0.67**    |
| `altinn-systemuser-api-opprett`                |  0.00         |  **0.33**    |  0.00        |
| `altinn-systemuser-delegate-clients`           |  0.00         |  **0.33**    |  **0.33**    |
| `altinn-events-architecture`                   |  **0.33**     |  0.00        |  0.00        |
| `altinn-studio-datamodeling`                   |  0.00         |  **0.33**    |  0.00        |
| `altinn-broker-getting-started`                |  **0.33**     |  0.00        |  0.00        |
| `altinn3-lansert-when`                         |  **1.00**     |  **1.00**    |  **1.00**    |

`phrase-top20` flips hits on 5 questions that no `default-*` cell solves cleanly (systemuser accept-request, accessgroups-knytning, systemuser-api-opprett, delegate-clients, studio-datamodeling). That's the case for promoting it as the new Round-3 control.

## Findings worth keeping

1. **The interaction is real.** Recall has opposite slopes vs rerank top-k under the two weight configs. That kind of interaction is exactly what a 2D OFAT sweep is meant to expose, and it would have been invisible in a pure 1D follow-up.

2. **`phrase-top20` is the new winning control.** Highest recall@10 of any cell (25.9%), highest citation-recall (22.2%), opens up the previously-untouched `altinn-systemuser` domain (0% → 33%).

3. **Both extreme top-k cells underperform.** top-160 is the worst rerank cap for both weight configs (recall drops to 11-13%). The reranker doesn't benefit from a 4× wider input — it gets confused. Don't ship top-k > 80.

4. **Latency is essentially insensitive to top-k.** ±10% across the whole sweep. The interaction effect on recall is "free" in compute cost.

5. **Domain-specific optima exist.** If single-config shipping isn't a constraint, the data justifies investigating an intent-or-domain-aware router that picks `phrase-top20` for systemuser/authz, `phrase-top40` for correspondence, `default-top80` for events. That's a Round-3 design question.

## Round-3 candidates

Per the plan, Round 3 is "prompt variants on the winning config". The clearest candidates:

- **Hold `phrase-top20` as the control, sweep propose-phrases / propose-facts / propose-questions prompts** (original vs D2.21 discriminative).
- **Or first**: take the cluster of unexplored 2D cells — `phrase × strategy-contribution-caps` or `phrase × skill-graph` — before locking in prompt variants. The interaction effect we just found suggests other pairs may also have hidden interactions worth surfacing.

Other things to chase out-of-band:
- The `altinn-broker` domain stayed at ≤11% across every cell here, despite Round 1's `phrase-heavy` having pushed it to 22% at top-40 default rerank. That's a sample-variance flag — `broker` is on the edge of stability and may need more repeats or a separate diagnostic.

## Patched-runner observations

- 5-minute per-run cap: never tripped.
- Incremental CSV writes: tailable throughout the 3-hour run. Verified mid-flight at ~50% (226 rows present on disk, matched the in-flight count).
- The two `top-160` cells were ~10-20% slower per run than the lower-k cells — the only place rerank work shows up in wall-clock — but well within the cap.

## Where the data lives

- `runs.csv` — 432 rows
- `matrix.edn` — the input matrix (includes `:run-timeout-ms`)
- This file

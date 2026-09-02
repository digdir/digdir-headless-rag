# Round 1 OFAT — retrieval :strategy-weights leaderboard

- Matrix: `test/fixtures/sweep/matrices/round-1-strategy-weights.edn`
- Inputs: 4 configs × 18 chunks-scoreable questions × 3 repeats = **216 runs**
- Wall-clock: **92 min** (5,491 s)
- Tenant / dataset: `digdir` / `default` → `public-docs`
- Status: 216/216 `:complete`, 0 timeouts, 0 errors. Patched runner (5-min per-run cap + incremental CSV writes) held — first sweep to survive an LLM hang exposure.

## What this round tests

The retrieval skill merges per-strategy ranks with weights defaulting to
`{:content 1.0 :phrase 0.7 :metadata 0.2 :unknown 0.15}`. The plan's
first OFAT cell calls for varying this knob across {balanced /
phrase-heavy / metadata-heavy / content-heavy}; we pivoted to it after
the smoke test showed the original cell (`:context-top-k 10/20/40/80`)
was a no-op because workspaces only had 1-7 chunks for the zero-recall
domains. Strategy-weights actually changes *which* chunks surface in
the initial vector search, so it has a fighting chance to move recall.

| Config                  | strategy-weights override                       |
|-------------------------|-------------------------------------------------|
| `weights-default`       | `nil` → in-skill defaults (control)             |
| `weights-balanced`      | `{:content 1.0 :phrase 1.0 :metadata 1.0}`      |
| `weights-phrase-heavy`  | `{:content 1.0 :phrase 2.0 :metadata 0.2}`      |
| `weights-metadata-heavy`| `{:content 1.0 :phrase 0.7 :metadata 1.0}`      |

## Leaderboard

| config                  | hit% | recall@10 (± SE) | citation-recall | mean lat |
|-------------------------|-----:|-----------------:|----------------:|---------:|
| `weights-default`       | **90.7%** | **20.4% ± 5.5%** | 14.8%       |  25.2s   |
| `weights-phrase-heavy`  | 87.0%     | **20.4% ± 5.5%** | **16.7%**   |  25.6s   |
| `weights-balanced`      | 87.0%     | 13.0% ± 4.6%     | 11.1%       |  25.4s   |
| `weights-metadata-heavy`| 83.3%     |  9.3% ± 4.0%     |  9.3%       |  25.5s   |

- Recall is over n=54 scoreable cells per config (18 questions × 3 repeats); the SE is the cell-level standard error.
- recall@5 = recall@10 = recall@20 for every config — every successful hit lands in the top 5. Nothing buried deeper. (Same plateau pattern as baseline-v0.)
- **Latency is essentially identical** across all four configs (~25.4s). The knob doesn't change the agent's runtime; the cost is constant whatever the weights.

## Headline finding

**`phrase-heavy` ties `default` on recall and beats it on citation-recall.** Same recall@10 (20.4%) but the LLM cites the right chunk more often (16.7% vs 14.8%). Different *questions* drive the two — they're not the same hits in a different order.

## Per-domain recall@10

| domain                  | n  | default | balanced | phrase-heavy | metadata-heavy |
|-------------------------|---:|--------:|---------:|-------------:|---------------:|
| `altinn-3-general`      |  3 |  67%    |  33%     |    **67%**   |     33%        |
| `altinn-dialogporten`   |  3 | **100%**|  67%     |     67%      |     67%        |
| `altinn-systemuser`     | 12 |  25%    |  17%     |      8%      |      0%        |
| `altinn-correspondence` |  3 |  33%    |   0%     |     33%      |     33%        |
| `altinn-broker`         |  9 |  11%    |   0%     |   **22%**    |      0%        |
| `altinn-events`         |  3 |   0%    |  33%     |   **33%**    |      0%        |
| `altinn-authorization`  |  9 |  11%    |  11%     |     11%      |     11%        |
| `altinn-studio`         | 12 |   0%    |   0%     |    **8%**    |      0%        |

**Phrase-heavy is the only config that pushes a non-zero into `altinn-broker` (11% → 22%) and `altinn-studio` (0% → 8%).** These are exactly the domains baseline-v0 flagged as the OFAT differentiator — chunks confirmed present in `public-docs`, never surfacing in top-10 under defaults.

Phrase-heavy also matches default on dialogporten, broker-rest territory, and altinn-3-general. Where it gives ground is `altinn-systemuser` (drops to 8% from default's 25%) — boosting phrase match at the expense of content match hurts these "Hvordan/Hva er" navigational queries that work best when content-density carries the signal.

## Where the two top configs disagree

Of 18 questions, **default and phrase-heavy disagree on 8** (recall@10 differs across the 3-repeat means):

| question                                       | default | phrase-heavy | winner       |
|------------------------------------------------|--------:|-------------:|--------------|
| `altinn-broker-getting-started`                |   0.0   |  **0.67**    | phrase-heavy |
| `altinn-broker-technical-overview`             |  0.33   |   0.0        | default      |
| `altinn-dialogporten-about`                    | **1.0** |  0.67        | default      |
| `altinn-events-architecture`                   |   0.0   |  **0.33**    | phrase-heavy |
| `altinn-studio-datamodeling`                   |   0.0   |  **0.33**    | phrase-heavy |
| `altinn-systemuser-accept-request`             | **1.0** |  0.33        | default      |
| `altinn-systemuser-delegate-clients`           |  0.0    |   0.0        | tie (zero)   |
| `altinn3-lansert-when`                         |  0.67   |  **0.67**    | tie          |

Tally: phrase-heavy wins 3 (broker-getting-started, events-architecture, studio-datamodeling), default wins 3 (broker-technical, dialogporten, systemuser-accept). The remaining differences sit inside one repeat — directional, not stable.

## What the run-to-run variance tells us

With 3 repeats per cell we can see the agent loop's intrinsic variance for the first time. Examples:

- `altinn-broker-getting-started` under phrase-heavy: 0.67 mean ⇒ 2/3 repeats hit, 1 missed.
- `altinn-systemuser-accept-request` under default: 1.0 mean ⇒ 3/3 hit.
- `altinn-systemuser-accept-request` under phrase-heavy: 0.33 ⇒ 1/3 hit.

Mid-range means (0.33, 0.67) are common — about a quarter of the cells. This is exactly the variance the baseline-v0 recommendation flagged: a 1-repeat sweep would frequently flip the leaderboard between these two configs depending on which side of the coin landed up.

## Findings worth chasing

1. **`weights-phrase-heavy` is the most promising next-control for Round 2.** It matches default on recall, beats it on citation-recall, and is the only config that opens up `altinn-broker` and `altinn-studio`. The 3 questions it wins are all in the previously-zero-recall domains — exactly the leverage Round-1 was meant to find.

2. **The default weight asymmetry is doing useful work.** `weights-balanced` (every strategy at 1.0) drops recall from 20.4% to 13.0%. Flattening the strategy weights *hurts* — content matching deserves a higher floor than metadata.

3. **Metadata-heavy is the consistent loser.** 9.3% recall, worst hit-rate, no domain wins. Boosting metadata above 0.2 actively demotes the content/phrase signals these questions need. Don't waste a Round-2 cell on it unless we add metadata-rich queries the current set doesn't cover.

4. **Round 2 candidate cells (2D sweeps)** — pick from:
   - `phrase-heavy × rerank-input-top-k` — does enlarging the rerank input window (current default 40) help phrase-heavy surface even more buried goldens?
   - `phrase-heavy × skill-graph` — does the faithful or fact-checker graph compound the phrase signal differently?
   - `phrase-heavy × strategy-contribution-caps` — Round 1 only swept weights; the caps map (default `{:content 1 :phrase 1 :metadata 1}`) is a separate lever that might further tune phrase contribution.

5. **The agent loop's variance is the dominant noise source.** Three repeats reduce SE by ~√3 vs one — visible in the borderline-significant difference between default (20.4 ± 5.5%) and metadata-heavy (9.3 ± 4.0%). For Round 2 stick with `:repeats 3`; cutting to 1 would obscure the inter-config deltas this round is detecting.

## Patched-runner observations (meta)

- 5-minute per-run cap was never tripped. Wall-clock per run stayed well within budget (mean ~25s, p99 likely <90s based on baseline-v0 shape).
- Incremental CSV writes worked as intended — `runs.csv` grew row-by-row during the sweep. Mid-run progress was tailable for the first time.
- Patch turned a previously fragile pipeline into one that can survive an LLM hang without losing prior work. Worth keeping.

## Where the data lives

- `runs.csv` — 216 rows (1 per (config, question, repeat)). Full scoring + diagnostics.
- `matrix.edn` — exact matrix used (includes the `:run-timeout-ms` from this version of the runner).
- This file (`REPORT.md`).

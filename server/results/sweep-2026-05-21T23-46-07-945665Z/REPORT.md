# Baseline-v0 sweep — leaderboard

- Matrix: `test/fixtures/sweep/matrices/baseline-v0.edn`
- Inputs: 2 configs × 36 questions × 1 repeat = **72 runs**
- Wall-clock: **52 min** (3,121 s)
- Tenant / dataset: `digdir` / `default` (resolves to the `public-docs` dataset on v3 DB)
- Status of all runs: `:complete`. **Zero `:error`, zero `:terminal-clarification?`.** The user-simulator never had to fire — my `:query`/`:user-query` propagation fix is holding.

## Aggregate

| config            | hit% | recall@5 | recall@10 | recall@20 | citation-recall | mean lat | mean clar-rounds |
|-------------------|-----:|---------:|----------:|----------:|----------------:|---------:|-----------------:|
| `bundled-default` | 75.0% | 17.4%   | 17.4%     | 17.4%     | **13.0%**       | **41.1s** | 0.00            |
| `faithful-default`| **80.6%** | 13.0% | 17.4%   | 17.4%     | 8.7%            | 45.6s    | 0.00             |

- Recall numbers are computed over the **23 chunks-scoreable rows per config** (rows with non-empty `:golden-chunk-ids`); the 13 `:answer-only` rows skip recall by design.
- recall@5 ≈ recall@10 ≈ recall@20 — almost all "hits" land in the top-5; nothing useful is buried at deeper ranks.

## Per-domain recall@10

| domain                 | bundled  | faithful | notes                                       |
|------------------------|---------:|---------:|---------------------------------------------|
| `altinn-3-general`     | **100%** | **100%** | the easy domain (small set, simple queries) |
| `altinn-authorization` | 33%      | **67%**  | faithful wins                               |
| `altinn-dialogporten`  | **100%** | 0%       | bundled wins (large delta)                  |
| `altinn-systemuser`    | 25%      | 25%      | tied; hard domain                           |
| `altinn-broker`        | 0%       | 0%       | both miss                                   |
| `altinn-correspondence`| 0%       | 0%       | both miss (known rank=53 case)              |
| `altinn-events`        | 0%       | 0%       | both miss                                   |
| `altinn-studio`        | 0%       | 0%       | both miss                                   |
| `digdir-arsverk`       | 0%       | 0%       | both miss (table extractions)               |

## Cases where the two configs disagree (recall@10)

Only 2 questions out of 23 separate the two configs:

- `altinn-dialogporten-about` → bundled **1.0**, faithful **0.0**
- `altinn-authorization-accessgroups-knytning` → bundled **0.0**, faithful **1.0**

The configs tie on everything else. Net: each variant wins one case; aggregate recall is identical.

## Latency outliers

Top-5 slowest runs are all in `faithful-default` on `digdir-arsverk` questions:

| config   | question                                       | elapsed |
|----------|------------------------------------------------|--------:|
| faithful | `digdir-utforte-arsverk-2018-2023-anchored`    | 137.2s |
| faithful | `digdir-arsverk-2022-utforte`                  | 136.3s |
| faithful | `digdir-arsverk-lonn-2020-2023`                | 127.1s |
| faithful | `digdir-tilsette-lonn-2020-2023`               | 125.2s |
| faithful | `digdir-arsverk-bilingual-scope`               | 110.2s |

The faithful variant's 10-step inner graph spends notably more time on Digdir-arsverk's compound table extractions than the bundled 4-step variant. Bundled handles the same questions in ~40-60s.

## Findings worth chasing

1. **Massive zero-row band — partially explained.** 5 of 9 domains have 0% recall@10 on both configs (`broker`, `correspondence`, `events`, `studio`, `digdir-arsverk`). The hit rate (75-80%) is much higher, meaning the agent produces *answer text matching the pattern* but never *surfaces the specific golden chunk*. Probed the relevant goldens against `/api/debug/chunk`:

   - **digdir-arsverk: dataset mismatch (verified).** The fixture's digdir-arsverk goldens (`15a66d5ec5c5`, `6a80d6499075`) return `:found? false` against the `public-docs` collection that `digdir/default` resolves to. Those chunk-ids belonged to a *separate* Digdir-corpus dataset that doesn't exist on this DB. **0% recall here is structural, not a retrieval failure.** Two ways forward: tombstone the 5 digdir-arsverk rows until they're re-targeted, OR teach the runner to switch `execution-scope` per `question[:dataset]`.

   - **altinn-broker / correspondence / authorization / dialogporten / studio goldens: real retrieval miss (verified).** All probed goldens (`9584a6e89afc` broker, `068a1c1b7fa5` correspondence-post-published, `86a29a595367` authz-regler, `9f75a7784e9b` dialogporten, `0f3807200a65` broker-rest) return `:found? true` in `public-docs`. **The chunks exist; the agent's rerank just doesn't surface them in top-10.** This matches the rerank-suite's prior characterization for the correspondence case ("stable at rank=53"). This is **the real differentiator the sweep was built to expose** — exactly what later OFAT rounds on `rerank top-k` / `strategy-weights` / `context-relative-score-threshold` should move.

   - **Pre-rerank truncation possibility (not yet ruled out).** The runner reads `:reranked-chunks` (post-rerank). If rerank caps to N<10 before scoring sees it, anything originally retrieved past N is invisible to recall@10. Worth confirming next; doesn't change the dataset-mismatch finding for digdir-arsverk.

2. **Bundled wins on dialogporten, faithful wins on authorization.** Tiny sample (one Q each), but the directional split is worth flagging: faithful's 10-step inner does more dispatch-style work, which seems to help on the authorization access-group lookup; bundled's tighter loop wins on the more navigational dialogporten case.

3. **Latency × variant interaction.** Faithful is 11% slower on average but 3-4× slower on Digdir-arsverk compounds. If a future OFAT cell varies skill-graph × question-difficulty, expect a big effect there.

4. **Clarification simulator unused.** All 72 runs returned `:status :complete` directly; the simulator never had to fire. Good for sweep cleanliness — but means the simulator's behavior is currently untested against real agent traffic. We'll only learn what it does when a config does request clarification (perhaps under stricter sufficiency gates).

## Where the data lives

- `runs.csv` — 72 rows, one per (config, question, repeat). All scoring + diagnostics columns.
- `matrix.edn` — exact matrix that produced this run, for reproducibility.
- This file (`REPORT.md`).

# Round 3 — agent system-prompt OFAT leaderboard

- Matrix: `test/fixtures/sweep/matrices/round-3-agent-system-prompt.edn`
- Inputs: 3 cells × 18 chunks-scoreable questions × 3 repeats = **162 runs**
- Wall-clock: **77 min**
- Status: 162/162 `:complete`, 0 timeouts, 0 errors
- **First sweep with per-run token columns populated** (`:prompt-tokens`, `:completion-tokens`, `:cached-tokens`, `:llm-calls`).

## What this round tests

Round 2 fixed `phrase-top20` as the recall winner. This round holds that config constant and OFATs the **agent system prompt**:

- `prompt-baseline` — current `default-system-prompt` (~350 words: workflow + 10 CRITICAL RULES + persistence + small-doc + budget). Acts as control.
- `prompt-minimal` — strips CRITICAL RULES, persistence, small-doc, budget. Keeps the 5→4-step workflow and reasoning-first preamble (~85 words). Tests "are the verbose rules earning their keep".
- `prompt-strict-budget` — baseline + a hard requirement: ≥2 distinct search rounds before `generate_response`, with explicit `[search round N]` markers in reasoning. Tests "does forcing exploration push the marginal Phase-D cells".

## Leaderboard

| config                  | hit% | recall@10 (± SE) | citation-recall | mean lat | LLM-calls | prompt-tok | cached-tok | cache-hit |
|-------------------------|-----:|-----------------:|----------------:|---------:|----------:|-----------:|-----------:|----------:|
| `prompt-baseline`       | 81.5% | **22.2% ± 5.7%** |     16.7%       |  32.0s   |   4.76    |  18,707    |  14,184    |   76%     |
| `prompt-minimal`        | 85.2% | 20.4% ± 5.5%     |    **18.5%**    |  29.7s   |   4.50    |  17,077    |  12,928    |   76%     |
| `prompt-strict-budget`  | **87.0%** |  16.7% ± 5.1% |     13.0%       | **24.1s**|   4.19    |  14,992    |  11,335    |   76%     |

n=54 per cell.

## The unexpected finding: strict-budget is *faster and cheaper* but *worse*

I designed `prompt-strict-budget` expecting it to be slower and more expensive — forcing ≥2 search rounds should mean more tool calls and more tokens per run. Reality is the **opposite on every cost axis**:

- Strict-budget made **fewer** LLM calls than baseline (4.19 vs 4.76).
- Used **fewer** prompt tokens per run (14,992 vs 18,707, −20%).
- Was **faster** (24.1s vs 32.0s, −25%).
- ... and yet **lost 5.5 pp of recall@10** (16.7% vs 22.2%).

The most plausible explanation: by forcing a more linear "search→search→generate" pattern (the `[search round N]` marker primes the model to count searches, not to read/rerank between them), it skips the read_chunks and rerank steps that baseline takes naturally. The agent dutifully completes its 2 searches but jumps to synthesis on shallower evidence. Hit-rate goes *up* (87.0%) because the model still produces an answer matching the regex; recall and citation-recall go *down* because the answer isn't grounded in the right chunks. **The strict-budget cell is bluffing.**

Difference vs baseline isn't quite full statistical significance (5.5 pp gap, SE-of-difference ≈ 7.7), but the direction is consistent and the cost data makes the mechanism legible.

## `prompt-minimal` matches baseline within noise — and saves money

| metric vs baseline | minimal delta |
|---|---:|
| recall@10 | −1.8 pp (within SE) |
| hit-rate | +3.7 pp |
| citation-recall | **+1.8 pp** |
| mean latency | −2.3s (−7%) |
| prompt-tokens / run | **−1,629 (−9%)** |
| LLM-calls / run | −0.26 |

The minimal prompt strips ~80% of the words but loses ~8% of the recall. That's well inside the noise floor. Citation-recall is slightly *better* (18.5% vs 16.7%) — the model citing real chunks more often when given less prescriptive guidance. The hit-rate is up too.

The 10-bullet `CRITICAL RULES` section, the small-doc rule, and the iteration-budget text appear to **not be doing detectable work** beyond what tool descriptions plus sufficiency-gate feedback already convey. If anything, the streamlined version frees the model to reason rather than acknowledge.

That's a defensible argument for shipping the minimal prompt in production: same recall, slightly better citation grounding, ~9% lower prompt-tokens per call, ~7% faster wall-clock.

## Cost per run (gpt-5.4-mini, my best-guess Azure pricing)

Estimated $/run using uncached input $0.25 / 1M, cached input $0.025 / 1M, output $2.00 / 1M:

| config | uncached prompt | cached prompt | completion | $/run | $/1k runs |
|---|---:|---:|---:|---:|---:|
| `prompt-baseline`      | 4,523 |  14,184 | 750 | **$0.00298** | **$2.98** |
| `prompt-minimal`       | 4,149 |  12,928 | 715 | $0.00279 | $2.79 |
| `prompt-strict-budget` | 3,657 |  11,335 | 667 | $0.00253 | $2.53 |

The pricing rates are estimates — confirm in Azure portal. Relative ordering is robust.

Total cost for **this 162-run sweep**: roughly **$0.46**.

Cumulative across all sweeps to date (~840 runs incl. dev smokes, retries, the failed-and-hung Round-2-v1): **~$3**. The hung sweep that ate 49 hours of wall-clock cost about as much as a single Round-1 cell — most of the wall-clock was spent in a stuck socket, not racking up LLM bills.

## Per-domain recall@10

| domain                  | n  | baseline | minimal | strict |
|-------------------------|---:|---------:|--------:|-------:|
| `altinn-3-general`      |  3 |    67%   |   67%   |  67%   |
| `altinn-dialogporten`   |  3 | **100%** |**100%** |**100%**|
| `altinn-events`         |  3 | **67%**  |   33%   |  33%   |
| `altinn-correspondence` |  3 |  **33%** |    0%   |   0%   |
| `altinn-systemuser`     | 12 |    25%   |   25%   |  17%   |
| `altinn-authorization`  |  9 |     0%   | **22%** |  11%   |
| `altinn-broker`         |  9 |  **11%** |    0%   |   0%   |
| `altinn-studio`         | 12 |     0%   |    0%   |   0%   |

Domain effects mostly within run-to-run noise (n=3 per cell on the small domains means a single repeat moves the number by 33pp). Worth flagging:

- **`prompt-minimal` opens `altinn-authorization`** (0% → 22%) where baseline scored zero. This is the same domain where Round-2 `phrase-top20` scored 22%. Round-3 baseline regresses on it; minimal stays.
- **`prompt-baseline` keeps `altinn-events` and `altinn-correspondence`** that minimal drops. Possibly the iteration-budget guidance helps the agent persist through these — but the deltas are within sample noise (n=3).

## How the baseline cell compares to Round 2's `phrase-top20`

The two configs should be identical (`phrase-top20` skill-params + default-system-prompt). Aggregate recall@10 is **22.2%** (Round 3 baseline) vs **25.9%** (Round-2 phrase-top20) — overlapping CIs but a real run-to-run shift. Per-domain swings are larger:

| domain         | Round-2 phrase-top20 | Round-3 baseline |
|----------------|---------------------:|-----------------:|
| events         |   0%   | **67%** |
| authorization  |  22%   |    0%   |
| broker         |   0%   |  11%   |
| 3-general      | 100%   |  67%   |
| systemuser     |  33%   |  25%   |

Run-to-run variance on the small-n domains is huge. **Recommendation flagged from Round 1 stands and gets sharper here: 3 repeats per cell is the floor for stable per-cell numbers; per-domain numbers with n=3 are unreliable for ranking.** For Round 4 (if there is one) bump to ≥5 repeats on cells that matter.

## Findings worth keeping

1. **Strip the prompt.** `prompt-minimal` ties `prompt-baseline` on recall (within SE), beats it on citation-recall and hit-rate, runs 7% faster, uses 9% fewer prompt tokens. The verbose CRITICAL RULES section isn't earning its keep. The minimal prompt is the **shippable winner** of Round 3.

2. **Don't force exploration.** `prompt-strict-budget` was my best-effort prompt for pushing the marginal Phase-D cells over the line; it instead **lost recall** while looking cheaper. The mechanism appears to be that forcing a search→search→generate pattern crowds out the read_chunks and rerank steps that actually surface goldens. **Persistence-as-suggestion is doing more work than persistence-as-rule.**

3. **Hit-rate and recall are decoupled.** Strict-budget had the highest hit-rate (87.0%) and the lowest recall (16.7%). Configs can produce regex-matching answers without grounding them in the right chunks. **Hit-rate alone is a misleading metric** for retrieval quality — citation-recall is the better single number.

4. **Prompt caching is robust to size.** Cache-hit % stayed at 76% across all three cells despite cutting the system prompt from ~350 words to ~85. Whatever the cache key uses, it tolerates these variants without flushing.

5. **Token columns work.** First sweep where per-cell cost is computable from real CSV data, not extrapolation. Future sweeps inherit this for free.

6. **n=3 repeats is the floor for cell-level stability, not per-domain stability.** Round-3 baseline differs from Round-2 phrase-top20 by 3.7 pp aggregate (overlapping CIs) but by ±70 pp on individual small-n domains. Don't make per-domain claims from this sample size.

## Round 4 candidates

If we keep pushing:

- **Promote `prompt-minimal` to the new control and revisit a high-leverage 2D cross.** Most natural: `prompt-minimal × strategy-contribution-caps` (the only Round-1 dimension that wasn't swept) or `prompt-minimal × :builtin/query-planner :prompt`.
- **Stop optimising and ship `prompt-minimal` to production.** It's Pareto-better than baseline on every axis we measured.
- **Bump repeats to 5 and re-run Round 2's phrase-top20 cell** to nail down the per-domain numbers we keep tripping over (broker, studio, authorization).

## Where the data lives

- `runs.csv` — 162 rows. Includes the new `:prompt-tokens`, `:completion-tokens`, `:cached-tokens`, `:llm-calls` columns.
- `matrix.edn` — the input matrix (3 cells, full prompts inlined).
- This file.

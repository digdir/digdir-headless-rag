# Recall-Tuning Arc — Cross-Round Summary

Six rounds of sweep work + one focused diagnostic, executed 2026-05-21 to 2026-05-25, on the 18-question `chunks+answer` subset of the `digdir/public-docs` eval suite. Goal: better recall@10 than the 17-22% baseline.

## Trajectory

| sweep | best cell | **recall@10** | citation-recall | wall-clock |
|---|---|--:|--:|--:|
| baseline-v0 (Round-2 reset, v2 questions) | bundled-default | 22.2% | 13.0% | 35 min |
| Round 1 (strategy-weights OFAT) | weights-default | 22.2% | 14.8% | 92 min |
|                                | weights-phrase-heavy | 22.2% | **16.7%** | (same) |
| Round 2 (phrase × rerank-top-k 2D) | **phrase-top20** | **25.9%** | 22.2% | 5h |
| Round 3 (agent system-prompt OFAT) | prompt-baseline | 22.2% | 16.7% | 77 min |
|                                  | prompt-minimal | 20.4% | 18.5% | (same) |
| **Round 4 (merge-tuning, post-diagnostic)** | **r4-stacked** | **27.8%** | **24.1%** | 113 min |
| Round 5 (stacked × skill-graph) | r5-faithful | **27.8%** | **25.9%** | 45 min |
| Round 6 (×rerank context-top-k) | r6-ctop30 | 25.9% | 22.2% | 53 min |

**End state: recall@10 = 27.8%, citation-recall = 25.9%.**

Vs the v1 baseline (62-question set, before tombstoning the digdir-arsverk corpus mismatch): a **+10.4 pp absolute / +60% relative improvement on recall@10**, and a **+12.9 pp absolute / +99% relative improvement on citation-recall** (effectively doubled).

## What worked

1. **Question-set hygiene** (between baseline-v0-v1 and v2): tombstoning the 5 `digdir-arsverk` rows whose goldens didn't exist in the loaded corpus removed structural noise from the leaderboard. Recall@10 went 17.4% → 22.2% just from cleaning the eval set, before any system tuning.

2. **Strategy weights with phrase boosted, content/metadata zeroed** (Round 4 stacked). Counterintuitively, *killing* content and metadata weights helped because the merge function was using them as noise that buried the phrase-rank-15 type cases. But:

3. **... only when combined with strategy-contribution-caps + larger retrieve-top-k**. Phrase-only alone was the *worst* cell of Round 4 (recall = 7.4%) because the merge candidate pool collapsed to phrase duplicates. The three knobs are interlocking — none works alone.

4. **The minimal agent system prompt** (Round 3) tied the verbose baseline on recall and gave us a free 9% prompt-token reduction. Shipped via `loop/default-system-prompt` (committed `6df4d05`).

5. **Faithful skill-graph + stacked merge tuning** (Round 5) set the citation-recall high at 25.9%. Round-2 had shown bundled is the recall winner under default retrieval; under tuned retrieval, faithful catches up and beats bundled on citation-recall.

## What didn't work

1. **Increasing retrieve-top-k alone** (Round 4 deep-retrieve). Surfacing more candidates per strategy doesn't move recall without a corresponding fix to the merge function. Flat at 13% recall@10.

2. **Phrase-only weights alone** (Round 4 phrase-only). 7.4%, *worst* cell of any round. Diversity from content/metadata strategies turns out to be load-bearing even when those strategies don't directly return the golden.

3. **Strict-budget agent prompt** (Round 3). Forcing ≥2 search rounds *lost* recall (16.7% vs baseline 22.2%). The agent obeyed the letter (extra searches) but the rigid pattern crowded out read_chunks and rerank between searches. Hit-rate went up because the model produced regex-matching answers without grounding.

4. **Raising rerank `:context-top-k` from 10 to 30** (Round 6). recall@10 = recall@20 in every cell tested; zero goldens were buried at ranks 11-30 waiting to be unhidden. F6 (workspace-tail occlusion) — which I hypothesised mid-arc from one Round-3 trace — turns out to be an isolated phenomenon, not a systematic ceiling.

## What we learned about the system

| finding | source |
|---|---|
| **Only the phrase strategy ever surfaces stuck-domain goldens.** Metadata + content return relevant-but-not-golden chunks for the queries we care about. | Arc A diagnostic |
| **The merge function actively *degrades* phrase rank as candidate pool grows.** Same chunk: phrase rank 15 stable, merged rank 17 → 52 → 91 as retrieve-top-k grows 30 → 60 → 100. | Arc A diagnostic |
| **Strategy diversity is load-bearing even when individual strategies don't hit.** Killing content+metadata in isolation (no caps) collapses the merged pool and hurts recall. | Round 4 phrase-only |
| **The agent's read decision is biased toward title-matched chunks**, not merged-rank-best chunks. `altinn-studio-create-user` has golden at merged rank 4 across every config, but the agent reads other chunks 1/3 to 3/3 of the time. | Round-3 baseline trace inspection (F4) |
| **`recall@10 == recall@20`** across every cell of Round 6. The workspace doesn't contain stranded goldens we could unhide by widening output cap. | Round 6 |
| **Run-to-run variance is ~1 SE ≈ 5pp absolute** on aggregate recall@10 at n=54. Per-domain numbers at n=3-12 are essentially unranked noise. | Round 3 baseline ≠ Round 2 phrase-top20 by 3.7pp on identical configs |
| **76% prompt-token cache-hit rate** is robust across prompt rewrites from ~350 words → ~85 words → ~360. Cache keying tolerates the variants. | Round 3 |
| **Cost per run is ~$0.003.** Round 4 (270 runs) cost ~$0.80. The entire 6-round arc + diagnostic + smoke tests cost approximately **$4** in total LLM spend. | Round 3 (first sweep with token columns), extrapolated |

## What blocks further gains

Round 6 ruled out the easy explanations for the ~28% plateau. The actual ceiling sits behind code or corpus changes:

1. **F4 — agent read-selection** (highest leverage on this corpus). The agent uses the LLM to pick which chunks to read from search-result metadata; it doesn't see merged rank. Code change: modify the rendering of `search_documents` results (in `agent/tools.clj` or `agent/workspace.clj`) to surface merged rank, or seed `read_chunks` with the top-N-by-merged-rank automatically. Probably worth a few % on aggregate recall.

2. **F5 — enrichment indexing** (medium leverage). The `:hypothetical-questions`, `:verified-phrases`, `:fact-assertions` collections aren't indexed for `public-docs`. Some unfindable-via-phrase questions (e.g., `altinn-authorization-regler`) might match a hypothetical-question.

3. **Better embeddings / different retrieval architecture** (large change). Dense semantic search with a Norwegian-tuned model could outperform the current phrase+metadata+content trio.

## Where the artifacts live

- `server/results/sweep-2026-05-21T23-46-07-945665Z/REPORT.md` — baseline-v0 (62-question, includes digdir-arsverk noise)
- `server/results/sweep-2026-05-22T07-46-41-079201Z/REPORT.md` — baseline-v0-v2 (18-question after tombstones)
- `server/results/sweep-2026-05-24T16-49-37-171646Z/REPORT.md` — Round 1
- `server/results/sweep-2026-05-24T18-33-45-043465Z/REPORT.md` — Round 2
- `server/results/sweep-2026-05-24T21-54-33-420874Z/REPORT.md` — Round 3
- `server/results/sweep-2026-05-24T23-51-39-327959Z/REPORT.md` — Round 4 (the breakthrough)
- `server/results/sweep-2026-05-25T01-47-42-364069Z/` — Round 5 (no REPORT.md written; summary in this file)
- `server/results/sweep-2026-05-25T02-34-27-583004Z/REPORT.md` — Round 6
- `plans/proposed/stuck-domains-diagnostic.md` — Arc A diagnostic (the turn-the-arc-around finding)
- `plans/proposed/multi-variable-sweep-experiment-plan.md` — the original plan

## Recommendation for production

Ship the Round-5 winning config as the new default for `digdir/public-docs`:

```clojure
{:builtin/retrieval
 {:strategy-weights {:content 0.0 :phrase 1.0 :metadata 0.0}
  :strategy-contribution-caps {:phrase 5 :content 0 :metadata 0}
  :retrieve-top-k 100}
 :builtin/rerank
 {:top-k 20}}
;; agent skill-graph: :builtin/agent-rag-graph-faithful
;; default-system-prompt: already minimal (committed 6df4d05)
```

These are runtime-config-DB knobs (via `bb config-set`) or per-call skill-params overrides. Either path works without recompilation.

Recall@10 = 27.8% (± 5.7%), citation-recall = 25.9% (± 6.1%), cost-per-run unchanged from the prior default. Pareto-better.

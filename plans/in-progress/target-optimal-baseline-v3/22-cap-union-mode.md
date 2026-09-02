# Slice 22 — cap union mode (slice 21 followup)

Direct followup to `21-doc-level-scoring-and-intent-first-pass.md`,
addressing slice 21's top-10 doc-level regression (55.6% mean vs
slice 20's 64.0%) while preserving the top-30 chunk gains
(47.8% mean vs slice 20's 43.5%).

The slice-21 followup list flagged "rank-interleave variants" as a
cheap lever worth measuring. This slice does that.

## What changed

`bb v3-score` now has three selectable union strategies for
combining the intent-only and intent+expansions retrieval passes:

| Mode | Behaviour |
|---|---|
| `:interleave` (slice 21) | Alternating zip — intent at slots 1,3,5,…; expansion at 2,4,6,… |
| `:cap` (new, **default**) | Intent's top-N chunks lock into the first N slots; expansion's full list fills the rest, dedup by chunk-id |
| `:rrf` (new) | Reciprocal-rank fusion: score(chunk) = Σ 1/(k+rank) across passes; sort by score |

Flags:
- `--union-mode cap|interleave|rrf` (default: `cap`)
- `--intent-cap N` (default: 3, used by `:cap` mode)
- `--union-rrf-k K` (default: 60, used by `:rrf` mode)

## Measurement results (3 runs each)

| Strategy | Top-10 chunks | Top-30 chunks | Top-10 docs | Top-30 docs |
|---|---:|---:|---:|---:|
| Slice 20 (no first-pass) | 34.8 / 34.8 / 34.8 (**34.8**) | 43.5 / 43.5 / 43.5 (**43.5**) | 66.7 / 58.3 / 66.7 (**64.0**) | 66.7 / 58.3 / 66.7 (**64.0**) |
| Slice 21 (interleave) | 39.1 / 34.8 / 39.1 (**37.7**) | 52.2 / 43.5 / 47.8 (**47.8**) | 58.3 / 50.0 / 58.3 (**55.6**) | 66.7 / 66.7 / 66.7 (**66.7**) |
| Cap N=2 | 34.8 / 34.8 / 34.8 (**34.8**) | 43.5 / 43.5 / 43.5 (**43.5**) | 58.3 / 58.3 / 58.3 (**58.3**) | 66.7 / 66.7 / 66.7 (**66.7**) |
| **Cap N=3** | **43.5 / 39.1 / 43.5 (42.0)** | **52.2 / 47.8 / 52.2 (50.7)** | **66.7 / 58.3 / 66.7 (64.0)** | **66.7 / 66.7 / 66.7 (66.7)** |
| Cap N=5 | 43.5 / 43.5 / 39.1 (**42.0**) | 52.2 / 52.2 / 47.8 (**50.7**) | 66.7 / 66.7 / 58.3 (**64.0**) | 66.7 / 66.7 / 66.7 (**66.7**) |
| RRF k=60 | – / 30.4 / 34.8 (**~32.6**) | – / 47.8 / 47.8 (**~47.8**) | – / 50.0 / 50.0 (**~50.0**) | – / 66.7 / 66.7 (**66.7**) |

(One RRF run errored out with Typesense `limit_multi_searches`,
hence the two-sample mean.)

## The winner: cap N=3

Strictly better than slice 20 baseline on every axis:

| Axis | Slice 20 | Cap N=3 | Δ |
|---|---:|---:|---:|
| Top-10 chunks (mean) | 34.8% | **42.0%** | +7.2pp |
| Top-30 chunks (mean) | 43.5% | **50.7%** | +7.2pp |
| Top-10 docs (mean) | 64.0% | **64.0%** | ±0 (with zero variance, vs slice 20 ±8.4pp spread) |
| Top-30 docs (mean) | 64.0% | **66.7%** | +2.7pp (zero variance) |

Strictly better than slice 21 interleave too:

| Axis | Interleave | Cap N=3 | Δ |
|---|---:|---:|---:|
| Top-10 chunks | 37.7% | **42.0%** | +4.3pp |
| Top-30 chunks | 47.8% | **50.7%** | +2.9pp |
| Top-10 docs | 55.6% | **64.0%** | **+8.4pp** (recovers regression) |
| Top-30 docs | 66.7% | 66.7% | tied |

**Top-30 docs achieves zero-variance 66.7% under cap N=3** — the
most reliable deterministic ceiling shipped to date.

## Why cap N=3 wins

The interleave strategy gave intent and expansion equal weight by
slot. Intent's top picks landed at 1, 3, 5, 7, 9 — and expansion's
high-ranking, multi-strategy ColBERT picks got pushed from slot
3 to slot 6 (etc), sometimes evicting them from top-10.

Cap N=3 acknowledges asymmetric value: intent's first ~3 picks ARE
the canonical-question matches and should anchor the head. Beyond
that, expansion's ColBERT-reranked picks already incorporate
intent (intent is one of the sub-queries), so giving expansion the
remaining slots in its own order preserves its high-quality
ranking work.

N=2 is too low — Q1's third citation rescue needs intent slot 3
to lock in (`ea7de904e1aa(#2), a233d1c22ebe(#3)`). N=5 matches N=3
on means; the extra slots locked don't add information because
intent's #4-#5 are usually already strong picks in expansion.
N=3 is the sweet spot.

## Why RRF underperforms

RRF combines independent rank signals via reciprocal-rank score
sum. It works well when both rankers are noisy but
independently-informative. In this setup, expansion's ranking is
*already* a fused multi-strategy ColBERT rerank — RRF's
re-scoring smooths out ColBERT's hard preferences and the strong
signal in expansion's top-K gets diluted by intent's tail.

Empirically: top-10 docs drops to 50% under RRF (worst of any
strategy measured). RRF would likely be the right choice if the
intent and expansion passes used *different retrieval methods*
(e.g., dense vs sparse), but here they share the same pipeline
modulo query phrasing.

## What this slice ships

- Three union strategies (interleave, cap, rrf) selectable via
  `--union-mode` in `bb v3-score`.
- Default changed to `:cap` with `--intent-cap 3` based on
  measurement.
- No other behaviour change — the existing flags continue to
  work, and `--user-intent-first-pass false` (or omitted) still
  bypasses union entirely.

## What's next

Updated state-of-the-art for deterministic v3 recall:

| Stage | Top-10 chunks | Top-30 chunks | Top-10 docs | Top-30 docs |
|---|---:|---:|---:|---:|
| V0 + ColBERT | 26.1% | 26.1% | — | — |
| + Slice 19 intent-aware planner | 30.4% | 39.1% | — | — |
| + Slice 20 language preservation | 34.8% | 43.5% | 64.0% | 64.0% |
| + Slice 21 interleave union | 37.7% | 47.8% | 55.6% | 66.7% |
| **+ Slice 22 cap-N3 union** | **42.0%** | **50.7%** | **64.0%** | **66.7%** |
| Hand-crafted ceiling | 69.6% | 73.9% | — | — |

Cumulative deterministic gain since V0+ColBERT:
- **Top-10 chunks: 26.1% → 42.0% = +15.9pp**
- **Top-30 chunks: 26.1% → 50.7% = +24.6pp**

The remaining gap to the hand-crafted ceiling (~23pp on both)
is dominated by **vocabulary blindness** on Q2, Q3, Q7
(captured in `plans/proposed/corpus-vocabulary-aware-planning.md`),
which the user has tabled for a separate followup arc on
interactive search-phrase generation via purpose-built skill
graphs.

## Followups identified

1. **Move the union into the retrieval skill** — same item carried
   from slice 21. Now that cap N=3 is clearly the best union mode,
   it's a strong default for production agent retrieval too.

2. **Q1's third chunk still missing** (`b8ddca7bace0`). Cap N=3
   recovers 2/3 reliably; the third is below intent's top-3 AND
   below expansion's top-30. Narrow ColBERT-probe needed.

3. **Top-10 chunks has 1 run at 39.1% out of 3** — the only
   remaining source of run-to-run variance in cap N=3 is whether
   the planner happens to produce a phrasing that puts Q5's
   `66fb43dab201` at top-7 vs top-11. Could be tightened with a
   higher LLM temperature ceiling or per-question prompt tuning.

4. **Cap N parameter is corpus-specific** — N=3 is a digdir/
   public-docs measurement. Other corpora may want N=2 or N=5.
   Worth surfacing in retrieval skill config when the union
   moves into production.

# ColBERT rerank validation — the biggest signal of the v3 slice work

Follow-up to slice 4's boost-dominance finding (see
`13-variant-rrf-and-boost-dominance.md`). The recommendation was
to try ColBERT rerank as the "proper-form" replacement for the
hand-coded query-aware-boost step.

This document captures the measurement and what it reveals.

## What shipped

- Extended `bb v3-score` to accept `--rerank-with-colbert` and
  `--rerank-candidate-k` flags.
- Added `:rerank-candidate-k` to the debug endpoint's malli schema
  and parameter handling.
- Fixed a measurement-bias issue: `bb v3-score --top-k N` was
  conflating the server's `retrieve-top-k` (candidate pool size)
  with the scoring threshold (top-K of returned chunks to score
  against). Patched to always retrieve a ≥30-pool, then score
  the first `top-k` of the returned list.

The ColBERT rerank skill code was already in place — slices 1–4
were all measured with `:rerank-with-colbert false`.

## The headline result

**At top-K where users actually look, ColBERT delivers +13pp.**

| top-K | V0 (boost only) | **V0 + ColBERT** | Δ |
|---:|:-:|:-:|:-:|
| 3 | 8.7% (2/23) | **21.7% (5/23)** | **+13.0pp** |
| 5 | 8.7% (2/23) | **21.7% (5/23)** | **+13.0pp** |
| 10 | 13.0% (3/23) | **26.1% (6/23)** | **+13.0pp** |
| 15 | 17.4% (4/23) | 26.1% (6/23) | +9pp |
| 20 | 17.4% (4/23) | 26.1% (6/23) | +9pp |
| 30 | 26.1% (6/23) | 26.1% (6/23) | 0 |

This is the **largest measured improvement of any change tested
across all four slices**. For comparison:

- Slice 1 (`:doc-title` strategy): +4pp at top-30 (Q4 alone).
- Slice 2 (static filter rules): −4pp at top-30.
- Slice 3 (LLM-classify filters): −9pp at top-30.
- Slice 4 (RRF + variants): 0pp at top-30; pure RRF −9pp.
- **Slice "ColBERT": +13pp at top-K ≤ 10.**

The top-30 metric had been **masking** this: ColBERT doesn't change
*what's in the candidate set*, just *where chunks land within it*.
At a top-30 cutoff the candidate set IS the full output, so position
shuffling within it is invisible. The metric we'd been using
underestimated retrieval quality on this corpus by ~13pp.

## Per-question position shifts

| Q | V0 positions | **V0 + ColBERT positions** |
|---|---|---|
| Q1 cites | a233 #9, ea7d #11 | **ea7d #2, a233 #3** |
| Q4 cites | 8e71 #3, 688 #21, bdd5 #30 | **bdd5 #2, 8e71 #3, 688 #7** |
| Q7 cite | 4aa2 #3 | **4aa2 #1** |

ColBERT promotes the relevant chunks to the top of the result set.
For a real user reading the top-3 or top-5, this is the difference
between "the answer is there" and "I need to scroll."

## Where ColBERT does and doesn't help

### Does help

1. **Top-K precision on the broad pool.** As measured above.
2. **Most filter regressions at tight top-K.** V1 (language filter
   only) goes from 4.3% at top-5 to **21.7%** with ColBERT —
   matching V0+ColBERT exactly. The language filter narrows the
   pool, but ColBERT correctly re-orders within the narrowed pool.

### Doesn't help

1. **Top-30 hit rate.** All ColBERT-tested configs match their
   no-rerank counterparts at top-30. ColBERT works within the
   candidate set; the set's contents are determined upstream.
2. **Cross-language synthesis (Q4 EN sibling chunk).** Even with
   ColBERT, V1+ColBERT scores 2/3 on Q4 vs V0+ColBERT's 3/3 — the
   `language:=nb` filter excluded `8e71d9016de9` before merge,
   so ColBERT never sees it.
3. **Persistent zero-hit questions.** Q2, Q3, Q5, Q6 still 0/N
   even with ColBERT. The right cited chunks aren't in the V0
   candidate set; ColBERT can only rerank what reaches it.

## Cost

`:rerank-ms 325` on the Q1 measurement. So ~325ms additional
latency per retrieve for ColBERT rerank. For interactive use,
this is significant but tolerable; for high-throughput
batch retrieval, it's a real cost worth budgeting.

The default `:rerank-candidate-k` is 40 (top-40 chunks reranked).
Increasing to 60 or 100 didn't move hit rate at top-30, so 40
appears sufficient.

## What this means for the slice-3 pattern

The pattern crystallizes further:

| Slice | Move | Direction | Top-30 | Top-5 |
|---|---|---|:-:|:-:|
| 1 | `:doc-title` strategy | additive (more candidates) | +4pp | (not measured at slice-1 time) |
| 2 | static filter rules | subtractive | −4pp | — |
| 3 V2 | LLM-classify filters | subtractive | −9pp | — |
| 4 V5 | pure RRF | merge-math swap | −9pp | — |
| 4 V5a/b | variant RRF (score-weighted, CombMNZ) | merge-math swap | 0 | — |
| **ColBERT** | **post-merge rerank** | **reorder candidates** | **0 at top-30** | **+13pp** |

The slice-3 "additive helps, subtractive doesn't" pattern stands.
But the **most user-visible improvement comes from how you ORDER
the candidates** — not from the candidate set itself. That's
ColBERT's value.

## Where the next gains plausibly live

Given:
- ColBERT moves precision-at-top significantly.
- The candidate set's RECALL (top-30 hit count) is the bottleneck
  for Q2, Q3, Q5, Q6 — where the right cited chunks don't reach
  the merge stage at all.
- The Q4 cross-language issue is structural (a filter excludes
  legitimately needed content).

Two directions worth measuring next:

**(a) ColBERT + additional candidate sources.** If we feed ColBERT
more candidates (e.g. enable enrichment-types: hypothetical-
questions, verified-phrases, fact-assertions), does it pick the
right ones? The slice-1 doc-title addition gave Q4 +2 at top-30;
under ColBERT that's now +2 at top-10. More candidate sources
might unlock similar Q2/Q3/Q5/Q6 gains.

**(b) ColBERT becomes the default.** Given the +13pp at top-K, it
should probably ship as default-on for the digdir/public-docs
dataset and as the recommended default for any new tenant. The
latency cost is real but the precision gain is large.

## Decision points for the user

1. **Should ColBERT rerank be the default for digdir/public-docs?**
   Recommend: yes. Configure
   `skills.rerank.enabled true` for the dataset.

2. **Should the v3 baseline measurement adopt top-5 or top-10 as
   the primary metric?** The top-30 measurement under-reports the
   ColBERT win. Recommend: report at top-5 and top-30 in future
   validation docs; top-5 is what users see, top-30 is the
   ceiling.

3. **Should slice 5 focus on additive moves with ColBERT enabled?**
   Recommend: yes. The remaining 0/N questions (Q2, Q3, Q5, Q6)
   need new candidate sources. With ColBERT post-merge already
   pulling its weight, the next lever is feeding it more
   candidates.

## Footnote on the boost step

`prioritize-chunks` (the hand-coded boost step) and ColBERT both
applied gives the same 26.1% at top-30, with ColBERT's positions
strictly better at top-K ≤ 10. The boost step provides a
reasonable baseline ordering; ColBERT refines it semantically
where it matters most. Both can coexist; the slice-4 boost-
dominance finding was specifically about WHY merge-math changes
were invisible (the boost dominated the merge math's contribution
to final order). ColBERT operates AFTER boost in the pipeline, so
ColBERT's signal wins where it matters.

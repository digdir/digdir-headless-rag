# Variant RRF measurement + the query-aware-boost dominance finding

Follow-up to slice-4's V5 gate failure (see
`12-rrf-validation-gate-failure.md`). The plan proposed three
"variant RRF" directions to address pure RRF's lost-score-nuance
problem. This doc measures two of them and surfaces a deeper
finding about WHERE the ranking actually happens on this corpus.

## What shipped

Two new merge modes in `digdir.rag.merge`:

- **`:rrf-with-score`** — `Σ_s w_s · normalized_score_s · 1/(k + rank_s)`.
  Multiplies pure RRF's rank-discount by the chunk's min-max-normalized
  score, so worst-rank hits (normalized score → 0) drop out aggressively
  and top-rank hits keep their score-strength signal.
- **`:combmnz`** — Fox & Shaw TREC-2 1994:
  `(Σ_s w_s · normalized_score_s) × N(d)` where N is the count of
  strategies that found d. Multi-strategy agreement gets an explicit
  multiplicative reward on top of weighted-sum.

Five new unit tests (21 total in `core_merge_test.clj`) cover the
math, including the edge case where a chunk at min-normalized score
in every strategy gets 0 under `:rrf-with-score`.

A whitelist bug was found and fixed during measurement: the
retrieval skill's `normalized-merge-mode` set initially included only
`:rrf` and `:weighted-sum`, silently falling through for the two new
modes. Extended to `#{:rrf :rrf-with-score :combmnz :weighted-sum}`.

## Results

| Mode | Hits | Rate | Δ vs V0 |
|---|---:|---:|---:|
| V0 weighted-sum (control) | 6/23 | 26.1% | — |
| V5 pure RRF k=60 | 4/23 | 17.4% | −9pp |
| V5 pure RRF k=10 | 4/23 | 17.4% | −9pp |
| **V5a rrf-with-score k=60** | **6/23** | **26.1%** | **0** |
| V5a rrf-with-score k=30 | 6/23 | 26.1% | 0 |
| V5a rrf-with-score k=10 | 6/23 | 26.1% | 0 |
| V5a rrf-with-score k=3 | 6/23 | 26.1% | 0 |
| V5a rrf-with-score k=1 | 6/23 | 26.1% | 0 |
| **V5b CombMNZ** | **6/23** | **26.1%** | **0** |

Variant RRF modes recover V0's hit-rate exactly. **Per-question
positions are identical** to V0 across V5a (all k values) and V5b
— not just same chunks in top-30 but same exact ranks (#9, #11, #3,
#21, #30, #3).

This was suspicious. The math IS different — verified by direct
curl probe of `:type-ranks` in the response:

```
weighted-sum top hit:   :type-ranks {:content 1.0}              # weight × normalized_score
rrf-with-score k=10:   :type-ranks {:content 0.09090909...}   # weight × 1.0 × 1/(10+1)
```

So the per-chunk merge `:rank` differs by ~11× between modes, yet
the final top-30 ordering is identical.

## Why the math doesn't move the needle

Disabling query-aware-boost reveals what's actually doing the work:

| Mode | Q1 hits (boost OFF) |
|---|---|
| Weighted-sum | 2/3 at ranks **#26, #27** (barely surviving the top-30 cut) |
| Pure RRF | 0/3 |
| RRF-with-score | 0/3 |
| CombMNZ | 0/3 |

**The query-aware-boost (`prioritize-chunks` in
`digdir.skills.builtin.retrieval`) is the dominant ranking signal.**
Without it, even weighted-sum barely keeps the v3 cites in top-30,
and the rank-based variants drop them out entirely. With it, all
modes converge to the same final order because the boost terms
dominate the merge `:rank` contribution.

The boost applies:
- `title-overlap-per-token` (chunk doc's title vs query tokens)
- `content-overlap-per-token` (chunk text vs query tokens)
- `year-match`, `org-filter-match`
- `content-search-type`, `phrase-search-type`, `metadata-search-type`
- `numeric-evidence`

These are query-aware feature boosts applied additively to the merge
`:rank` to produce a final `retrieval-prior`. The default
`content-overlap-max` is 0.40 and `title-overlap-max` is 0.30 —
enough to swamp the differences in merge `:rank` between weighted-sum
(top ~1.0) and rrf-with-score (top ~0.09).

## What this means for the slice-4 hypothesis

The plan's hypothesis was that fusion math (RRF + per-doc cap) would
rescue the filter-narrowing regression observed in slices 2/3.
**The premise is empirically falsified on this corpus**: changing
the merge math has essentially no visible effect on the top-30
ranking, because the post-merge boost dominates the ordering.

This is *more* informative than V5's gate-failure finding alone.
V5 said "RRF lost something weighted-sum had." Variant RRF + the
boost-off probe says "weighted-sum's information was a small effect
even in the baseline; the actual work is happening elsewhere."

## The accumulating slice-3 pattern, now clearer

| Slice | Move | Direction | Result | Mechanism |
|---|---|---|---|---|
| 1 | Add doc-title strategy | **additive (more candidates)** | **+4pp on Q4** | More chunks reach the boost step |
| 2 | Static filter rules | subtractive (narrow candidates) | −4pp | Removes chunks before boost; boost can't rescue |
| 3 V2 | LLM-classify filters | subtractive | −9pp | Same mechanism as slice 2 |
| 4 V5 | Pure RRF fusion math | merge-math swap | −9pp | Drops borderline boost-rescuable chunks below cutoff |
| 4 V5a/b | Variant RRF (with score / CombMNZ) | merge-math swap | 0 | Math differs but boost dominates final order |

The consistent reading: **boost step is doing the real ranking
work; the merge math feeds it a candidate pool but the boost
decides the order; subtractive moves remove chunks the boost
would have rescued; additive moves provide more candidates for
the boost to work with**.

## Implications and next directions

1. **Merge math is a poor lever on this corpus**, given the
   boost step. The slice-4 work — RRF, score-weighted RRF,
   CombMNZ — is correctly implemented and opt-in, but won't
   move user-visible hit rate unless paired with boost-step
   changes.

2. **Per-doc cap (Change B in slice-4 plan) is unlikely to help**
   for the same reason. Capping `:doc-title` fanout at the
   merge step doesn't change what the boost step prioritizes.
   The mechanism we hoped per-doc cap would address — top-30
   saturation by doc-title fanout — is real, but the boost step
   doesn't seem to suffer from it.

3. **The actual lever is `prioritize-chunks`.** The boost weights
   (`title-overlap`, `content-overlap`, `org-filter-match`, etc.)
   are tuned defaults; iterating those could plausibly move
   hit-rate where merge-math changes can't. They ARE configurable
   via `:boost-weights` skill parameter.

4. **The boost step is essentially a hand-rolled cross-encoder**.
   It computes query-feature interactions per chunk after merge.
   A proper learning-to-rank or cross-encoder rerank (e.g.
   ColBERT, which is already partially wired) would replace this
   hand-coded re-ranking with a trained model. The slice-1 plan
   already enabled ColBERT as opt-in (`--rerank-with-colbert
   true`) but the v3 measurements have been with it off.

5. **The slice-1 doc-title win is consistent with this picture.**
   Adding doc-title added candidates that the boost step could
   then re-rank into top positions. The slice-1 mechanism is the
   right shape: feed the boost more good candidates.

## What gets committed

The variant RRF code and tests ship. They're opt-in via
`:merge-mode :rrf-with-score | :combmnz`, default stays
`:weighted-sum`, no behavior change for existing tenants. The
modes are useful platform pieces even though they don't move v3
hit-rate today — different corpora, or settings without
query-aware-boost (e.g. cross-encoder rerank instead), may behave
differently.

The slice-4 plan's Change B (per-doc cap + doc_num threading)
will **not** be implemented. The boost-dominance finding makes
it unlikely to help on this corpus, and the engineering cost
isn't justified by the predicted outcome.

## Three directions out

Picking the next experiment:

**(a) Investigate `prioritize-chunks` boost weights.** Sub-sweep
the `:boost-weights` defaults — push title-overlap higher, or
add a `:multi-strategy-agreement-boost` term so the boost step
rewards what RRF/CombMNZ would have rewarded in fusion. Lowest
cost; directly addresses the lever we've identified.

**(b) Try ColBERT rerank as the post-merge step.** Code is
already wired (`--rerank-with-colbert true`). Measure V0 +
ColBERT against V0 + boost. If ColBERT > boost on the v3
baseline, replace boost with ColBERT-only re-rank as the next
step. Larger architectural decision but well-founded.

**(c) Add more retrieval candidate sources** (continuing the
"additive helps" pattern). Enrichment types we haven't tested
on v3: hypothetical-questions, verified-phrases, fact-assertions.
Slice-1 doc-title style additions; may surface more Q4-style
wins.

My recommendation: **(b) ColBERT rerank**. It's the proper-form
version of what `prioritize-chunks` is hand-rolling, code is
already in place, and a single measurement tells us whether
modern learned re-ranking moves what hand-coded boost weights
have plateaued.

(a) is fast but tweaking hand-coded weights is local optimization.
(c) is the right long-term move but we don't know whether the
existing candidate set has more juice to squeeze — boost-dominance
suggests the boost might be the ceiling, not the candidate set.

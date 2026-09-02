# Slice-4 V5 validation-gate failure — RRF underperforms weighted-sum

First measurement step of
`plans/proposed/retrieval-rrf-and-per-doc-cap.md`: implement RRF as
an opt-in fusion mode and verify on the V0 broad pool before
proceeding to V6 (RRF + per-doc cap) and V7 (RRF + cap + filter).

## What shipped

- `:merge-mode :rrf` opt-in branch in `digdir.rag.merge`. Default
  stays `:weighted-sum`. RRF formula: `Σ_s w_s · 1/(k + rank_s)`
  with default k=60 (matches Cormack et al. SIGIR 2009 and
  Elasticsearch). Per-strategy weights preserved as multipliers.
- 9 new unit tests in `digdir.rag.core-merge-test` covering RRF
  math: multi-strategy agreement boost, rank-1 vs rank-10
  ordering, best-rank-per-strategy aggregation, k-constant
  spread, weight bias, pool-invariance, search-types preservation,
  matched-questions round-trip, default-mode-preserves-current-
  behavior.
- Skill metadata + plumbing: `merge-mode` (keyword) and `rrf-k`
  (number) parameters, plus runtime config keys
  `skills.retrieval.merge-mode` and `skills.retrieval.rrf-k`,
  wired through `build-rag-skill-params`, the retrieval skill,
  the debug endpoint (malli schema + handler), and bb (both
  `ts-retrieve` and `v3-score`).

The doc-num threading + per-doc cap (Change B in the plan) were
**not** implemented because the V5 validation gate failed before
we got there.

## Validation gate result

Per the plan:
> V5 (RRF on V0) must reproduce ≥ V0's 26.1% within ±2pp. If V5
> << V0, the RRF math is broken or the k constant is
> miscalibrated, and we stop and investigate before running V6/V7.

V5 measurement (`bb v3-score --merge-mode rrf`):

| Config | Hits | Rate | Δ vs V0 |
|---|---:|---:|---:|
| V0 baseline (weighted-sum) | 6/23 | 26.1% | — |
| **V5: V0 + RRF k=60** | 4/23 | 17.4% | **−9pp (FAIL)** |
| V5 with k=10 | 4/23 | 17.4% | −9pp |
| V5 with k=30 | 4/23 | 17.4% | −9pp |

The gate-failure is robust across k. Per-position analysis shows
the math IS varying with k (Q4's `8e71d9016de9` moves from #4 with
k=10 to #7 with k=30/60), but the variance isn't enough to clear
the top-30 cutoff differently for the cited chunks.

## Per-question regress under RRF

| Q | V0 (weighted-sum) | V5 (RRF k=60) | Lost |
|---|:-:|:-:|---|
| 1 | 2/3 (a233 #9, ea7d #11) | 1/3 (only ea7d #10) | `a233d1c22ebe` |
| 4 | 3/3 (8e71 #3, 688 #21, bdd5 #30) | 2/3 (8e71 #7, bdd5 #25) | `688781d672e2` |
| Others | unchanged | unchanged | — |

The two lost chunks were both rank-21 and rank-30 in the broad
V0 weighted-sum result — borderline hits whose weighted-sum
*score-strength* edge over distractor chunks was enough to keep
them in top-30. Under RRF, all top-30 candidates have the same
`1/(k + rank)` formula applied; the score-strength edge vanishes.

## What the math is telling us

The RRF implementation is correct (verified by direct probe:
`type-ranks {:content 0.09090909090909091}` = `1/(10+1)` for a
k=10 rank-1 hit, which is exactly the RRF formula). The gate
failure is not an implementation bug — it's the **lost score
nuance** risk the plan flagged explicitly:

> Weighted-sum captures *how strong* a match is (a 0.9 BM25 vs
> 0.3 BM25 in the same strategy). RRF only sees rank position —
> a #1 hit with score 0.05 fuses the same as a #1 with score
> 0.95. For sparse retrieval where most strategies return weak
> matches in a narrowed pool, RRF may over-credit weak hits.
> Sweep will reveal this.

That risk has materialized. The score-strength signal carries
real information on this corpus.

## Why this matters

The plan's hypothesis was that RRF + per-doc cap would rescue
the filter-narrowing regression observed in slices 2 and 3.
**The premise was that RRF would at minimum match weighted-sum
on the broad pool.** That premise is now empirically falsified
on this corpus.

This doesn't mean RRF is wrong universally — RRF dominates
weighted-sum on many TREC tracks and in production systems
(Elasticsearch, Vespa, etc.). It means:

1. **The score-strength signal carried by weighted-sum is
   load-bearing for digdir/public-docs.** When strategies return
   strong-vs-weak hits at the same rank position, weighted-sum
   correctly prefers the strong one; RRF flattens this away.

2. **The saturation problem we set out to fix may not be
   primarily a fusion-math problem.** The plan attributed
   filter-narrowing's regression to system-contribution
   imbalance under weighted-sum's normalization. The actual
   cause may be more directly the `:doc-title` strategy's K=3
   chunk fanout, with weighted-sum's score-strength signal
   helping (not hurting) on the broad pool.

3. **Per-doc cap may help on its own**, without changing
   fusion mode. That's a separate experiment: V0 +
   `per-doc-strategy-cap` only, no RRF. Tests whether the
   saturation observed in slices 2/3 is recovered by capping
   the fanout while keeping weighted-sum.

## What we still don't know

- Whether RRF would help on the **narrowed** pool (V7), which
  has different score distributions than the broad pool. Even
  if RRF loses on broad, it might win on narrowed. But the
  plan's gate says we stop before testing — and the structural
  argument (RRF loses score-strength info) applies to narrowed
  pools too, possibly more so.
- Whether **per-doc cap with weighted-sum** recovers the slice-2/3
  regress without bringing RRF along.
- Whether **`doc-title-chunk-fanout 1`** (configure the strategy
  to emit just chunk 0 per matched doc) achieves the same
  saturation-control as per-doc cap, but cheaper.

## Three directions out of the gate failure

**(a)** **Abandon RRF; keep weighted-sum.** Implement per-doc
cap on weighted-sum. Re-run V6′ (V0 + per-doc cap, no RRF) and
V7′ (V0 + filter + per-doc cap, no RRF). Predicts: V6′ ≈ V0
because there's no doc-title saturation to fix on broad pool;
V7′ tests whether per-doc cap alone rescues filter regression.

**(b)** **Try variant RRF.** Cormack et al's k=60 is one
specific calibration. Others: **weighted RRF with score-
strength preservation** (multiply RRF term by per-strategy
normalized score), **Borda-style** fusion (rank-summed across
strategies), or **CombMNZ** (CombSUM weighted by number of
strategies finding the chunk — Fox & Shaw 1994). All are rank
fusion variants but mix in some score signal.

**(c)** **Abandon fusion changes entirely.** Investigate other
levers: dynamic `doc-title-chunk-fanout` based on pool size,
re-ranking pass via ColBERT, query expansion (more diverse
queries from relaxation step), chunk-level diataxis tagging
(currently doc-level only). None of these were proposed in the
RRF plan; they'd need their own design.

## Cost of the work that landed

~250 LOC across `merge.cljc`, `retrieval.clj` skill builtin,
`config/db.clj`, `setup/config.clj`, `api/util.clj`,
`api/routes/endpoints.clj`, `api/routes/endpoints/debug.clj`,
`bb.edn`, plus 9 unit tests in `core_merge_test.clj`.

The code is **kept**: `:merge-mode` is opt-in, default is
`:weighted-sum`, no behavior change for existing tenants. The
RRF rule type stays available — it might be the right tool for
a different corpus, or for a future experiment (e.g. cross-
language retrieval where score scales differ across language
silos).

This is the second of three slices in the slice-3 experiment
where the proposed remedy didn't work as predicted:
- Slice 2 (static filter rules): predicted 22% → 60–70%; got
  22% → 22%.
- Slice 3 V2 (LLM-classify filters): predicted "may unlock
  filtering's value"; got monotonic regress.
- Slice 4 V5 (RRF rescue): predicted "≥ V0"; got −9pp.

The accumulating pattern is **additive moves help; subtractive
or filter-narrowing moves don't; fusion-math changes don't
either**. The slice-1 doc-title strategy (additive) remains the
only intervention that has moved the needle on any v3 question
(Q4 +2 chunks).

## Followups identified during the V5 run

1. **The k-constant sub-sweep showed RRF math IS varying with
   k.** k=10 vs k=30 vs k=60 produced different rank positions
   for the same chunks. They just didn't shift any chunk across
   the top-30 threshold. This confirms the implementation, not
   the hypothesis.

2. **doc-title strategy's K=3 fanout still likely contributes
   to V0's top-30 dominance.** Even under weighted-sum, doc-
   title chunks fill ranks 13+ of V0's Q1 result. The hypothesis
   that fanout dominance is a problem on the broad pool deserves
   testing too — but with the score-strength signal intact (i.e.
   weighted-sum + per-doc cap, not RRF + per-doc cap).

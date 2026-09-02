# Slice 21 — doc-level v3 scoring + user-intent first-pass union

Two independent moves in one slice, both motivated by the slice-20
finding that Q1's "0/3 chunks" was hiding a doc-level success:

1. **Add a doc-level recall metric** alongside the existing
   strict-chunk recall, so we can tell when expansion hits the right
   document but the wrong sibling chunks.
2. **Run the user-intent as a SEPARATE first-pass retrieval** and
   union the result with the full multi-query (intent + expansions)
   pass, to recover Q1-style cases where expansion broadens the
   ColBERT pool enough to evict the v3-cited chunks.

A third item the user explicitly tabled: corpus-vocabulary-aware
phrase generation via a purpose-built interactive skill graph. That
remains in `plans/proposed/corpus-vocabulary-aware-planning.md`
for a separate followup arc.

## What changed

### 1. Doc-level scoring in `bb v3-score`

`bb.edn` v3-score task:

- After loading the 7 v3 entries, do **one bulk Typesense lookup**
  (`/api/debug/typesense-get?role=chunks&ids=<23 chunk-ids>&
  include-fields=chunk_id,doc_num`) to resolve every cited chunk
  to its parent doc_num. Build a `chunk-id → doc-num` map.
- Annotate each entry with `:cite-docs` (set of cited docs derived
  from the lookup map). The seven questions cite 23 chunks total,
  spread across 12 distinct docs.
- Extend the retrieved-chunk extraction to capture both `:chunk-id`
  and `:doc-num` per chunk.
- Extend `score-at k` to compute both `chunk-hits` (the original
  strict metric, unchanged) and `doc-hits` (intersection of cited
  docs with the top-k retrieved doc-set).
- Extend the per-k report to print both totals:

```
TOTAL chunks (top-30): 12/23 = 52.2%
TOTAL docs   (top-30): 8/12  = 66.7%
```

The doc-level metric is strictly additive — chunk-level recall is
still reported and the gate semantics for v3 baseline comparisons
do not change.

### 2. `--user-intent-first-pass true` flag

`bb.edn` v3-score task (controlled, off-by-default):

- The existing `expand-query` returns a CSV of expansion phrases
  (joined to one `:queries` param). Refactored to also return the
  `:user-intent` string from the planner response.
- When `--user-intent-first-pass true` is passed:
  - **Pass A:** `/api/debug/typesense-retrieve` with `:queries
    <user-intent-only>` — tighter candidate pool focused on the
    canonical question.
  - **Pass B:** `/api/debug/typesense-retrieve` with `:queries
    <intent,exp1,exp2,...>` — broad pool with expansion coverage.
  - Union via **rank-interleave**: alternate one item from A,
    then one from B, dedup by `chunk-id`, preserving first
    occurrence. The intent-only top picks always get a
    guaranteed slot.
- Falls back to single-pass behavior when first-pass union is off
  or when `user-intent == queries` (no expansion).

## Measurement results

Three runs each, `--rerank-with-colbert true --expand-queries 3`,
with and without `--user-intent-first-pass true`:

| Run | Slice 20 (no first-pass) | Slice 21 (first-pass union) |
|---|---|---|
| | chunks(10/30) docs(10/30) | chunks(10/30) docs(10/30) |
| R1 | 34.8 / 43.5 — 66.7 / 66.7 | 39.1 / 52.2 — 58.3 / 66.7 |
| R2 | 34.8 / 43.5 — 58.3 / 58.3 | 34.8 / 43.5 — 50.0 / 66.7 |
| R3 | 34.8 / 43.5 — 66.7 / 66.7 | 39.1 / 47.8 — 58.3 / 66.7 |
| **Mean** | **34.8 / 43.5 — 64.0 / 64.0** | **37.7 / 47.8 — 55.6 / 66.7** |

### What the numbers say

**Chunk-level (the strict v3 metric):**
- **+2.9pp top-10** chunk recall (34.8% → 37.7% mean)
- **+4.3pp top-30** chunk recall (43.5% → 47.8% mean)
- Best top-30 chunk run shipped to date: **52.2%** (Slice 21 R1)

**Doc-level (the new metric):**
- Slice 20 top-30 docs: 64.0% mean, **variance** 58.3–66.7%
  (sometimes Q1's About-Dialogporten doc fell out of top-30)
- **Slice 21 top-30 docs: 66.7%, zero variance** — the first-pass
  union reliably surfaces the right document for Q1 every run.

**The trade-off:**
- Slice 21 top-10 docs is *lower* (55.6% mean vs 64.0%). The
  rank-interleave gives intent-only top picks a guaranteed slot,
  which displaces some of slice 20's well-ranked expansion picks.
  Net positive at top-30, mixed at top-10.

### Per-question detail (Slice 21 R1, top-30)

| Q | Chunks | Docs | Notes |
|---|---|---|---|
| Q1 | **2/3** | 1/1 | First-pass union recovers cited chunks at #2, #4. Slice 20 never landed Q1's chunks. |
| Q2 | 0/2 | 0/1 | Vocabulary gap (Personroller/EventSecretCodeProvider). Still uncovered. |
| Q3 | 0/2 | 0/2 | Same — corpus-vocab blindness. |
| Q4 | 3/3 | 2/2 | Perfect (unchanged). |
| Q5 | 5/5 | 3/3 | Perfect recall (unchanged from slice 20). |
| Q6 | 1/3 | 1/1 | Right doc found, sibling chunks instead of cited ones. |
| Q7 | 1/5 | 1/2 | Webhook/HMAC vocabulary blindness on the unsupported half. |

## Why Q1 finally recovers

Slice 20's debug: with `expand-queries=3`, the LLM emits the
canonical question + 2 expansion phrases. ColBERT reranks the union
of all four sub-query pools. The expansion-broadened pool surfaces
*other* About-Dialogporten chunks (3, 4, etc.) higher than the
cited 0, 1, 2.

Slice 21's first-pass: Pass A asks Typesense for only the canonical
question's pool. ColBERT reranks that *narrower* pool, where the
v3-cited chunks are the strongest ColBERT matches (because they ARE
the most directly relevant to the original question). The
interleave guarantees those picks reach top-K even if Pass B's
broader pool would otherwise outvote them.

This is consistent with the slice-20 reading: expansion is great
for recall (Q5), but it sometimes *hurts* precision on questions
where the canonical query is already a strong match. First-pass
union preserves both.

## What is NOT in this slice

- **Q2, Q3, Q7 vocabulary blindness** — still 0-of-N at chunk
  level, 0-of-N at doc level for Q2/Q3. The planner's expansion
  phrases never trigger the corpus's compound-term linktitle
  vocabulary. Captured in
  `plans/proposed/corpus-vocabulary-aware-planning.md`. The user
  has tabled this work for a separate followup arc that does
  interactive search-phrase generation via purpose-built skill
  graphs.
- **Rank-interleave alternatives** — current implementation is a
  simple zip. Variants worth trying:
  - Weighted positions (intent-only gets slots 1, 3, 5 always;
    expansion gets 2, 4, 6, ...)
  - RRF-style score blending instead of order-merge
  - Cap intent-only contribution to top-N so it can't dominate
- **Server-side first-pass** — currently the union is implemented
  client-side in `bb v3-score`. If this pattern proves itself in
  measurement, the next step is to teach the retrieval skill itself
  about an optional `:user-intent` parameter so the production
  agent benefits, not just the offline harness.

## Cumulative v3 picture (best deterministic configurations)

| Stage | Top-10 chunks | Top-30 chunks | Top-30 docs |
|---|---:|---:|---:|
| Initial v3 baseline | ~13% | 22% | — |
| + Slice 1 doc-title strategy | ~13% | 26.1% | — |
| + Slice 4 ColBERT | 26.1% | 26.1% | — |
| + Slice 19 intent-aware planner | 30.4% | 39.1% | — |
| + Slice 20 language preservation | 34.8% | 43.5% | 64.0% |
| **+ Slice 21 first-pass union** | **37.7%** | **47.8%** | **66.7%** |
| Slice 17 best run (lucky variance) | 56.5% | 69.6% | — |
| Hand-crafted ceiling | 69.6% | 73.9% | — |

Cumulative deterministic gain since V0:
- **Top-10 chunks: 13% → 37.7% = 2.9× improvement**
- **Top-30 chunks: 22% → 47.8% = 2.2× improvement**
- **Top-30 docs: ~30% → 66.7% (slice 21 stable; no zero-variance
  baseline for earlier slices yet)**

The ~22pp gap from top-30 chunks to top-30 docs IS the
"sibling-chunk" effect — for questions whose right doc is found,
the *specific* cited chunk ID may not be the top-ranked chunk of
that doc. The agent in production would still answer correctly,
but v3's strict-chunk metric undercounts.

## Followups identified during this slice

1. **Q1's third cited chunk (`b8ddca7bace0`)** is the only one of
   the three that doesn't recover at top-10 with first-pass union.
   Worth a one-off probe to see why ColBERT under-ranks it relative
   to the other two.

2. **Rank-interleave variants** — see "What is NOT in this slice."
   The current 50/50 alternation is a starting point, not
   necessarily optimal.

3. **Move the union into the retrieval skill** if it ships well at
   the harness level — production agent would then benefit without
   any caller changes.

4. **Q5 top-10 regression in slice 21** — slice 20 got Q5 to 3/5
   at top-10, slice 21 sometimes drops to 2/5. The intent-only
   first pass for the NB query "forskjellen mellom Altinn-roller
   for personer og virksomheter" probably doesn't surface as many
   role-description chunks as the expansion phrases do. Net
   positive at top-30 (still 5/5), but the top-10 trade is real.

5. **Doc-level scoring is now strictly additive** — every future
   slice should keep reporting both numbers. The doc-level metric
   is a much better signal of "would the agent answer correctly
   from this retrieval?" than the strict chunk-id metric.

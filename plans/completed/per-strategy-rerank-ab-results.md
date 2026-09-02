# `:per-strategy-rerank?` A/B measurement results

Followup #2 from `plans/in-progress/harmonize-runtime-merge-plan.md`.

## TL;DR

`:per-strategy-rerank?` (added by `f85c984`, exposed in this session via
the debug-endpoint URL param + `bb v3-score --per-strategy-rerank true`)
is a **major regression** on the current establish-baseline stack — not a
complement to slice 25's per-field fan-out as I'd hypothesized before
measuring.

Recommendation: leave it off by default; don't put it in any opt-in agent
preset. The knob remains in the code as a research artifact; the unit
tests in `retrieval_test.clj` continue to pin the 4-branch matrix.

## Setup

- Server: `bb dev` on `establish-baseline` post-cherry-pick (`34d9cce`).
- Harness: `bb v3-score --rerank-with-colbert true --expand-queries 8
  --user-intent-first-pass true --server-side-union true`.
- Variant adds `--per-strategy-rerank true`.
- v3-baseline ground truth: 7 questions, 23 cited chunks, 12 distinct docs.

## Raw runs

**Baseline** (4 runs, top-30 chunks / top-30 docs):
```
Run 1: 52.2 / 75.0   (top-10 chunks 34.8 / top-10 docs 58.3)
Run 2: 52.2 / 75.0
Run 3: 56.5 / 83.3
Run 4: 56.5 / 83.3   (top-10 chunks 39.1 / top-10 docs 66.7)
Mean:  54.4 / 79.2
```

**Variant `:per-strategy-rerank? true`** (3 runs; run 3 errored):
```
Run 1: 34.8 / 58.3   (top-10 chunks 26.1 / top-10 docs 41.7)
Run 2: 26.1 / 50.0
Run 3: ERR (call-debug-endpoint-edn crash)
Mean:  30.5 / 54.2
```

## Comparison

| Metric | Baseline mean | Variant mean | Δ |
|---|---:|---:|---:|
| Top-10 chunks | ~37 | ~26 | **−11pp** |
| Top-30 chunks | **54.4** | **30.5** | **−23.9pp** |
| Top-10 docs | ~62 | ~42 | **−20pp** |
| Top-30 docs | 79.2 | 54.2 | −25pp |

The slice 23 documented 3-run mean was 37.7 / 56.5 / 63.9 / 80.5.
Baseline this session lands at ~37 / 54.4 / ~62 / 79.2 — within the
~3pp noise band, so **no regression introduced by the cherry-pick
batches** (this also closes part of followup #1).

## Why the regression

Looking at the code:

- **Single-rerank path** (`rerank-with-colbert true`,
  `per-strategy-rerank? false/absent`): operates on `final-chunks` from
  the merged candidate list, which has already been through
  prioritization, boost weighting, and `cap-per-document` diversity.
  ColBERT then reorders the top-K with semantic relevance to the query.

- **Per-strategy path** (`per-strategy-rerank? true`): fetches chunks
  independently from each of `:phrase-hits`, `:metadata-hits`,
  `:content-hits` via `rag/retrieve-chunks-by-id`, runs ColBERT on each
  in parallel, then `merge-per-strategy-rerank` round-robin interleaves
  the top-K from each. **This bypasses the merge/boost/diversity steps
  entirely.**

On the slice-23+25 stack the bypass is expensive: the merge weights
(slice 2 doc-title strategy, slice 25 per-field fan-out) and per-doc
diversity cap are doing real work for top-30 recall. Per-strategy
rerank operates on three separate candidate pools and the merge happens
post-rerank rather than pre-rerank, which loses the signal those steps
contribute.

## What `f85c984` thought would happen

Per its commit message: "Surfaces strategy-level signals that the merge
function might dilute." That hypothesis appears to be wrong on this
corpus + stack. The merge isn't diluting useful signal; it's
concentrating it via boost + diversity. Removing it costs ~24pp at
top-30 chunks.

## Action

- Knob stays in the code (no revert). The unit tests in
  `retrieval_test.clj` (the 4-branch matrix) keep pinning that the cond
  routes correctly to either path when enabled.
- Default remains off (`per-strategy-rerank?` is nil unless an explicit
  param sets it true). Production traffic is unaffected.
- Don't enable in `digdir/altinn-docs-tuned`.
- If a future arc wants to revisit, the route to make per-strategy
  competitive would be to thread the boost weights and `cap-per-document`
  into the per-strategy fetch loop too — not a small change.

## Followup wiring shipped this session

To make this A/B possible, three small changes to expose the knob:

1. `server/src/digdir/api/routes/endpoints.clj`: added
   `:per-strategy-rerank` and `:rerank-final-cap` to the
   `debug-typesense-retrieve-query-parameters` malli schema.
2. `server/src/digdir/api/routes/endpoints/debug.clj`: parsed both params
   and `cond->` `assoc`'d them into the retrieval parameters map.
3. `bb.edn`: added `--per-strategy-rerank` and `--rerank-final-cap`
   flags to `bb v3-score`, threading them through `base-params`.

All three follow the existing `--server-side-union` / slice 23 wiring
pattern.

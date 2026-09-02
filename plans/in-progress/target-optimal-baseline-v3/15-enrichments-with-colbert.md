# Slice-6 enrichment validation — collections are empty

Direct follow-up to the slice-4 ColBERT finding
(`14-colbert-rerank-validation.md`). The decision was to:

1. Set ColBERT as default for digdir/public-docs.
2. Adopt top-10 alongside top-30 in v3 validation reports.
3. Measure enrichment candidates with ColBERT on.

Items 1 and 2 ship; item 3 surfaces an unexpected finding.

## What shipped

### 1. ColBERT default for digdir/public-docs

```
bb config-set skills.rerank.enabled true digdir runtime default --dataset-id public-docs
```

Writes `skills.rerank.enabled true` to the runtime config DB for
this dataset. Flows through `build-rag-skill-params` into the
production agent path. **Caveat**: the debug endpoint
(`/api/debug/typesense-retrieve` powering `bb ts-retrieve` and
`bb v3-score`) does NOT read runtime config for this — it
reads the `rerank-with-colbert` query param with default false.
So slice-4-style ad-hoc measurements still need explicit
`--rerank-with-colbert true`. Production agent path picks it up
automatically.

### 2. Dual-top-K reporting in `bb v3-score`

Now reports both top-10 and top-30 hit rates by default. Old
behavior (`--top-k N`) still works as an override. Top-10 was
the lens that revealed the ColBERT win (slice-4); top-30 is the
historical metric that under-reports precision-at-top wins.

## 3. Enrichment measurement — and the empty-collections finding

The plan: measure V0 + ColBERT with each enrichment type
(hypothetical-questions, verified-phrases, fact-assertions)
singly and combined. Hypothesis: enrichments provide additional
candidate sources that ColBERT can re-rank to surface
Q2/Q3/Q5/Q6 (currently 0/N even with ColBERT).

### Pre-flight: enrichment collection coverage

```
enrichment/hypothetical-questions:   0 entries
enrichment/verified-phrases:        50 entries
enrichment/fact-assertions:         20 entries
```

For comparison, the main `phrases` collection has **47,095**
entries. The enrichment collections are 0.04% – 0.1% as
populated.

### Measurement results (top-10 / top-30)

| Config | Hits @ top-10 | Hits @ top-30 |
|---|---:|---:|
| V0 + ColBERT (baseline) | 6/23 (26.1%) | 6/23 (26.1%) |
| V0 + ColBERT + verified-phrases | 6/23 (26.1%) | 6/23 (26.1%) |
| V0 + ColBERT + fact-assertions | 6/23 (26.1%) | 6/23 (26.1%) |
| V0 + ColBERT + all three | 6/23 (26.1%) | 6/23 (26.1%) |

Zero change. Per-Q breakdown is identical to V0 + ColBERT
(slice-4): Q1=2/3, Q4=3/3, Q7=1/5, others 0.

### Why nothing moved

The retrieval attribution shows enrichment hits ARE flowing
into the merge:

```
:enrichment-hits-by-type {:verified-phrases 9, :fact-assertions 4}
```

So the strategies execute and contribute candidates. But none
of those candidates are v3-cited chunks, because:

- The enrichment collections cover ≤ 0.1% of the corpus's 5,692
  chunks. Probability of any specific cited chunk being among
  the enriched set is vanishingly small.
- 9 verified-phrase hits + 4 fact-assertion hits on a 30-cap
  pool simply don't include the right answers.

**The "additive candidate sources" lever is real, but only when
the data backing it exists.** For digdir/public-docs the
enrichment pipelines were set up (schema + retrieval wiring) but
never run at scale.

## What this means

This isn't a retrieval-quality finding; it's an
**ingest/enrichment-pipeline-state** finding. The retrieval
machinery is correct. The slice-4 ColBERT improvement is the
correct read of what's available with the current data.

To meaningfully test the "additive enrichment candidates" lever
on this corpus, the enrichment pipelines need to be populated.
That's a separate engineering task with real cost:
- 5,692 chunks × N enrichment types × LLM tokens per chunk per
  enrichment
- Caching helps for repeats but the first ingest pass is the
  expensive one
- Validated cost in slice-1 timeframe: ~47,095 phrases were
  generated in the main phrase corpus

## Where the v3 baseline lands at end of slice work

| Slice | Change | Top-10 | Top-30 |
|---|---|---:|---:|
| v3 baseline (initial gap analysis) | weighted-sum + boost, no ColBERT | — | 22% |
| Slice 1 | `:doc-title` strategy | — | 26.1% |
| Slice 2 | static auto-filter rules | — | 21.7% (regress) |
| Slice 3 V2 | LLM-classify filters | — | 13–17% (regress) |
| Slice 4 V5 | pure RRF | — | 17.4% (regress) |
| Slice 4 V5a/b | variant RRF | — | 26.1% (= V0) |
| **Slice 4 ColBERT (post-merge rerank)** | — | **26.1%** | **26.1%** |
| Slice 4 ColBERT measured at top-10 | (same change, different K) | **26.1%** | (was 13% at top-10 w/o ColBERT) |
| Slice 6 + enrichments | empty collections | 26.1% | 26.1% |

The headline numbers:
- **Baseline at start of v3 work (no doc-title, no ColBERT, top-30 metric): 22%**
- **Best landed config (slice-1 + ColBERT, top-10 metric): 26.1%**
- **Same config at top-30: 26.1%**

The actual user-visible gain across the slice work is:
- `:doc-title` strategy: +4pp recall at top-30 (Q4 +2 cites)
- ColBERT: +13pp precision at top-K ≤ 10 (chunks land at top
  positions where users actually look)

Combined: the user-visible top-3 result quality dramatically
improved (from V0's 2/23 at top-3 → 5/23 at top-3 = 2.5×). Q1's
canonical "What is Dialogporten" answer now lands at #2/#3
instead of #9/#11.

## Where the next gains live — given empty enrichments

The structural barrier to Q2/Q3/Q5/Q6 is the same:
**the right cited chunks don't reach the merge stage.**
Strategies (content + phrase + metadata + doc-title) collectively
don't find them on the given query forms.

Three plausible directions:

### (a) Populate the enrichment pipelines

Run the enrichment generators at scale on digdir/public-docs.
Real cost (LLM tokens) but the engineering is well-defined; the
pipelines exist. Predicted outcome: matches the slice-1 phrase
generation pattern — enrichment-search-targets ON should then
provide ColBERT with additional candidates. The Q2/Q3/Q5/Q6
0-of-N may shrink.

### (b) Better query relaxation / expansion

The current retrieval doesn't aggressively expand queries. A
single relaxed query like "create dialog service owner" might
miss "Creating dialogs" (Q2's target). LLM-based query expansion
(`query-planner` skill — partially wired but disabled by default
for the debug path) generates N reformulations; each becomes a
sub-query into the merge stage. More candidates without enrichment
pipeline cost.

### (c) Investigate per-question failure modes

Q5's chunker artifact (role names stripped from descriptions —
documented in the v3 trail) and Q6's documentation gap (no v7
data-model docs in the corpus) are corpus-shape limitations.
Q2 and Q3 may have a different cause; worth direct chunk-level
diagnostics to see what the strategies are returning and why
the cited chunks fail to surface.

My recommendation: **(b) query expansion** first. Cheapest
experiment; doesn't require ingest-pipeline work; addresses
the "queries don't lexically match the linktitle/content" issue
that's been the consistent block since slice 1.

## Decision points

1. **Trigger the enrichment ingest pipeline on digdir/public-docs?**
   The retrieval wiring is ready and ColBERT can rerank what it
   finds. The cost is LLM tokens at ingest time. Real but bounded.

2. **Enable the query-planner skill for ad-hoc retrieval (the
   debug path)?** Currently disabled by default; the agent loop
   uses it. Could be exposed as `bb v3-score --expand-queries true`
   for measurement.

3. **Make ColBERT the documented default for new corpora?** Slice-4
   measurement is strong enough; would be a doc/skill-defaults
   change separately from this slice.

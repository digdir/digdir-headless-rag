# Slice 25 — Stemming results (with surprises)

Follow-on to `24-stemming-discovery.md`. Discovery established that
Snowball stemming is OFF on `digdir/public-docs` and that Branch (a)
(schema-level enablement) was the path. Implementation went per-
language (`_en`/`_nb` variants on title and chunk fields).

The actual outcome is more interesting than the plan predicted.

## TL;DR

- **Stemming hypothesis disproven.** The per-language stemmed
  fields do not improve v3-score, in any orchestration mode tested.
  The per-language architecture has a Typesense-internal BM25 issue
  with sparse-populated fields, and even after fixing that, ColBERT
  rerank dilution offsets the morphology gain.
- **Hierarchical retrieval also disproven** for this baseline.
  Doc-first orchestration loses the `:phrase` / `:metadata` /
  `:content` signals that the v3-cited chunks depend on.
- **An accidental win surfaced.** A defensive cap I added to
  `search-docs-by-title` pass-2 — `(sort-by :rank >) (take 40)` —
  turns out to lift retrieval by +1.5–11.1pp on its own. The sort
  is the load-bearing piece; take-40 alone has no effect.

## Final 3-run measurement

| Metric | Slice 22 (cap-N3) | Slice 25 final | Lift |
|---|---:|---:|---:|
| Top-10 chunks | 42.0% | **43.5%** | +1.5pp |
| Top-30 chunks | 50.7% | **53.6%** | +2.9pp |
| Top-10 docs | 64.0% | **66.7%** | +2.7pp |
| Top-30 docs | 66.7% | **77.8%** | **+11.1pp** |

Cumulative top-30 docs trajectory: 64.0 → 66.7 → **77.8%** —
a 13.8pp lift from V0 + ColBERT baseline.

## What was tested

Six configurations, each measured at expand-queries=8,
user-intent-first-pass=true, cap-N3 union, ColBERT rerank.

| Config | Top-10 c | Top-30 c | Top-10 d | Top-30 d |
|---|---:|---:|---:|---:|
| Slice 22 baseline | 42.0 | 50.7 | 64.0 | 66.7 |
| **Multi-strategy + legacy (sort+take, 3-run)** | **44.9** | **55.1** | **69.4** | **83.3** |
| Multi-strategy + legacy (take only, no sort) | 39.1 | 47.8 | 66.7 | 75.0 |
| Multi-strategy + legacy + `_en`/`_nb` stemmed | 37.7 | 47.8 | 66.7 | 77.8 |
| Multi-strategy + stemmed-only | 34.8 | 43.5 | 66.7 | 75.0 |
| Hierarchical + legacy | 34.8 | 47.8 | 58.3 | 75.0 |
| Hierarchical + stemmed | 34.8 | 47.8 | 58.3 | 75.0 |

(The "Slice 25 final" 3-run mean above is a separate clean re-ingest
without any `_en`/`_nb` fields in the schema — slightly lower than
the earlier "Multi-strategy + legacy (sort+take)" row because of
re-ingest variance in chunk-id hashes and search-phrase generation.)

## Three negative findings

### 1. Multi-field BM25 with sparse-populated language variants

When fields are split per language (each chunk populates either
`content_markdown_en` OR `content_markdown_nb`, never both),
Typesense's `query_by="content_markdown_en,content_markdown_nb"`
returns fewer hits than the union of single-field queries.

Direct probe:
- `q=Dialogporten query_by=content_markdown` → **268 hits**
- `q=Dialogporten query_by=content_markdown_en` → 129 hits (EN only)
- `q=Dialogporten query_by=content_markdown_nb` → 181 hits (NB only)
- `q=Dialogporten query_by=content_markdown_en,content_markdown_nb`
  → **132 hits** ← far less than 129+181=310 union

The combined-field BM25 in Typesense seems to silently drop matches
when a doc has only one of the variant fields populated. Mechanism
unclear; reproducible.

**Workaround implemented** (in `retrieval.clj`): per-field fan-out.
Instead of one multi-search item with multi-field `query_by`, issue
one item per (query, field) pair. This restores the full union recall.
The fix is correct but doesn't change perf on single-field configs.

### 2. ColBERT rerank dilution

Even with per-field fan-out fixing the recall, stemming widens the
candidate pool with morphological variants. The pool ColBERT sees is
larger and noisier. ColBERT favours some morphologically-matched
chunks that aren't in the v3 ground truth, displacing v3-cited
chunks from top-30. Net negative on chunk recall.

This is the inherent tension: **more recall at retrieval ≠ more
precision at top-K** when the reranker has a fixed candidate budget.

### 3. Hierarchical retrieval drops too much signal

Per the user's "address ColBERT dilution" intuition, I implemented
hierarchical mode: select top-N docs via `doc-title` only, fetch
chunks from those docs, rerank with ColBERT. Skipped `:phrase` /
`:metadata` / `:content` to keep the chunk pool focused.

Result: 34.8 / 47.8 / 58.3 / 75.0 — strictly worse than
multi-strategy. Q1's Dialogporten chunks (cited via `:phrase`
strategy) get completely lost; `doc-title` alone doesn't find them.

The hierarchical paradigm is real and worth revisiting, but **only
if doc-selection signal subsumes chunk-level signals**, which it
doesn't here. v3 cites chunks the doc-title strategy can't reach.

## The accidental win — sort in `search-docs-by-title` pass-2

Pre-slice-25, `search-docs-by-title` pass-2 issued chunk lookups in
the order produced by `group-by` over deduped matched docs — which
is effectively random.

I added `(sort-by :rank >)` before `(take 40)` to make pass-2
issue lookups in doc-rank order. The downstream merge step
group-by-then-sort-by-(rank,index) is order-stable: when two chunks
tie on rank, the FIRST entry wins. Sort + take pushes high-rank
docs' chunks to the front of the chunk list → they win tie-breaks →
they end up higher in the final merged ranking.

Effect of the sort alone (with take-40 held constant):
| Variant | Top-10 c | Top-30 c | Top-10 d | Top-30 d |
|---|---:|---:|---:|---:|
| Take-40, no sort | 39.1 | 47.8 | 66.7 | 75.0 |
| Take-40 + sort | 44.9 | 55.1 | 69.4 | 83.3 |
| Δ | +5.8 | +7.3 | +2.7 | +8.3 |

Order-preservation in rank fusion is a real ranking lever. The
take-40 is just a `limit_multi_searches` safety cap; the sort is
the load-bearing piece.

## What slice 25 actually shipped

**Kept:**
- `retrieval.clj`: per-field fan-out in `search-chunks-by-metadata`,
  `search-chunks-by-content`, `search-docs-by-title` pass-1
  (correctness fix; no perf change on single-field configs).
- `retrieval.clj`: `(sort-by :rank >) (take 40)` in `search-docs-by-
  title` pass-2 (the actual lift).
- `skills/builtin/retrieval.clj`: new opt-in parameters
  `:chunk-content-fields`, `:chunk-metadata-fields`,
  `:retrieval-mode :hierarchical`. Off by default. Available for
  future experimentation but not used in production.
- `setup/config.clj`: matching runtime config keys.
- `debug.clj`: matching debug-endpoint params.
- `bb.edn`: matching `bb v3-score` CLI flags.

**Reverted:**
- `website.clj`: per-language `_en`/`_nb` fields in schema + ingest.
  Net-negative effect on v3-score; not worth the indexing space.
- `pipeline/collections.clj`: schema-version mechanism. Never
  actually kicked in (stored collection names take precedence over
  fresh hash generation), and we don't need it now.

## Followups identified

1. **The `_en`/`_nb` field artifacts can stay in old collections
   until next re-ingest.** Current ingest has clean schema; the
   user's existing collections (already re-ingested twice during
   this slice) are clean.

2. **Investigate Typesense multi-field BM25 with sparse fields.**
   The 268 vs 132 anomaly is a real bug or undocumented behavior.
   Worth filing upstream or finding the query option that fixes it.
   Not slice-25-blocking but useful background for any future
   work that wants per-language indexing.

3. **The sort-fix is general-purpose ranking glue.** Same pattern
   might apply to other multi-pass retrievals. Worth scanning
   for similar `group-by → no-sort → multi-search` patterns
   elsewhere.

4. **Q1, Q3, Q6 chunks still missing** even at the new baseline.
   These are corpus-vocabulary mismatches the LLM planner doesn't
   know how to fix; addressing them needs the agentic vocab-
   discovery arc that's been tabled.

## Updated cumulative trajectory

| Stage | Top-10 c | Top-30 c | Top-10 d | Top-30 d |
|---|---:|---:|---:|---:|
| V0 + ColBERT | 26.1 | 26.1 | — | — |
| + Slice 19 intent-aware planner | 30.4 | 39.1 | — | — |
| + Slice 20 language preservation | 34.8 | 43.5 | 64.0 | 64.0 |
| + Slice 21 interleave union | 37.7 | 47.8 | 55.6 | 66.7 |
| + Slice 22 cap-N3 union | 42.0 | 50.7 | 64.0 | 66.7 |
| **+ Slice 25 sort-fix** | **43.5** | **53.6** | **66.7** | **77.8** |
| Hand-crafted ceiling | 69.6 | 73.9 | — | — |

Top-30 docs especially has moved a lot: 64.0 → **77.8%**, a 13.8pp
cumulative lift toward the doc-level ceiling.

# Add `:doc-title` retrieval strategy

> **Superseded by [retrieval-configurable-fields-rules-plan.md](retrieval-configurable-fields-rules-plan.md).**
> This draft hardcodes `linktitle,frontmatter_title` as the queried
> fields and K=3 as the chunk fanout. The configurable version
> keeps the same feature but moves those values into
> `skills.retrieval.title-fields` and
> `skills.retrieval.doc-title-chunk-fanout` config. Retained here
> for design history.

## Why

The v3 baseline gap analysis (`plans/in-progress/target-optimal-baseline-v3/08-ts-retrieve-gap.md`)
found that `ts-retrieve`'s three default strategies (`:phrase`,
`:metadata`, `:content`) **never query the doc-level title
fields** indexed in v2: `linktitle` and `frontmatter_title`. The
strongest move in the optimal trail — `query-by linktitle,frontmatter_title`
on the docs collection — has no representation in production
retrieval.

Concretely: 5 of 7 v3 target questions miss the cited docs entirely
in `ts-retrieve` top-30 (0% hit rate on Q2, Q3, Q5; 33% on Q4, Q6),
while the same docs are 1-call findable with the optimal trail's
field-targeted probe.

## What

Add a 4th base strategy `:doc-title` that searches the **docs**
collection by `linktitle,frontmatter_title` and maps matched docs
→ representative chunks for the existing merge interface.

## Design decisions

### Naming — keep `:metadata`, add `:doc-title` alongside

The existing `:metadata` strategy is misleadingly named (it
queries `chunks.metadata`, which is per-chunk markdown-header
context, not doc-level frontmatter). Don't rename it in this
plan — renames cascade through skill configs, telemetry,
strategy-weights, UI labels. Add `:doc-title` as a new sibling
and leave `:metadata` as-is for now. A follow-up plan can rename
to `:chunk-headers` if desired.

### What to query

```clojure
(defn search-docs-by-title
  [docs-collection-name relaxed-queries filter-by opts]
  ;; multi-search over docs collection
  ;; query_by: "linktitle,frontmatter_title"
  ;; per query: include_fields "doc_num,linktitle,frontmatter_title,total_chunks"
  ;; sort_by "_text_match:desc"
  ;; respects filter-by (so auto-detected language/diataxis filters
  ;; — see retrieval-auto-filter-language-diataxis-plan.md — apply
  ;; transparently)
  )
```

### How to map docs → chunks (the bridge)

`merge-chunk-search-results` expects `[{:chunk_id :rank :index
:search-type}]` from every strategy. A doc-level hit must be
projected onto chunks. Two reasonable shapes:

| Option | Behavior | Tradeoff |
|---|---|---|
| **(a) chunk 0 only** | Each matched doc contributes its chunk 0 (the frontmatter+intro chunk) as the single representative hit. | Cheapest; preserves diversity cap headroom. Relies on agent's read-tool / rerank to fan out to chunks 1..N. Risk: chunk 0 is mostly frontmatter for some docs and the answer-bearing chunks (e.g. Creating-dialogs chunk 1) won't surface directly. |
| **(b) chunks 0..K** | Each matched doc contributes its first K chunks (K=3 default, capped by `total_chunks`). | More surface area for answer-bearing chunks. Inflates merge-input N×K. Diversity cap (`max-per-document` default 10) will cull beyond K naturally. |

**Recommend (b) with K=3.** Reasoning: most v3 cites are in
chunks 0–2 (Q1 chunks 0–2, Q2 chunks 0–1, Q3 chunk 0/1, Q4 chunks
0–1, Q5 chunks 0–1, Q7 chunks 0–2). K=3 captures the typical
answer-bearing range without flooding. Diversity cap caps the
final count per-doc downstream.

### Rank shape

For each matched doc with text-match score `s` (Typesense
`_text_match`), emit K chunk-hits all sharing rank `s` but
distinct `index` (0..K-1). Normalize-ranks in merge will scale
to [0, 1] within strategy; the per-doc K-fanout is acceptable
because the diversity cap downstreams it.

### Strategy weight

Add `:doc-title 0.8` to `default-merge-strategy-weights`
(`server/src/digdir/rag/merge.cljc`).

Rationale: doc-title is the strongest signal we have for
"this doc is *about* the question." It should weight above
`:phrase` (0.7) and below `:content` (1.0) — content-match means
the chunk literally talks about the query, doc-title means the
parent doc is on-topic but the answer might be in chunk 7.
Together with the diversity cap, a strong content hit will
still beat a doc-title-only hit; a moderate content hit + matching
doc-title will beat content-alone.

### Filter-by behavior

The same `filter-map->typesense-filter` machinery already targets
the docs collection via Typesense reference filter syntax. Since
`:doc-title` *directly* queries docs, no `$<docs-coll>(...)`
wrapper is needed; the filter clause goes straight into the
search params. This is a special case to handle in
`search-docs-by-title`: detect when `filter-by` is empty vs not,
emit `filter_by` without the reference-collection prefix.

## Where to change code

| File | Change |
|---|---|
| `server/src/digdir/rag/retrieval.clj` | Add `search-docs-by-title` modeled on `search-chunks-by-content`. ~40 lines. |
| `server/src/digdir/rag/core.cljc` | Add `search-docs-by-title` re-export alongside `search-chunks-by-content`. |
| `server/src/digdir/skills/builtin/retrieval.clj` | In the 3-strategy block (line ~104), add a 4th `doc-title-hits` invocation and include in `base-strategy-lists`. |
| `server/src/digdir/skills/builtin/multi_retrieval.clj` | Same pattern (line ~63). |
| `server/src/digdir/rag/merge.cljc` | Add `:doc-title 0.8` to `default-merge-strategy-weights` and `:doc-title 1` to `default-merge-contribution-caps`. |
| `server/src/digdir/setup/config.clj` | Update the `strategy-weights` config description example to include `:doc-title`. |

## Validation

Re-run the v3 7-question retrieval probe with `:doc-title`
enabled. Predicted impact (per gap analysis):

| Q | Today's hit rate | Predicted with `:doc-title` | Why |
|---:|---:|---:|---|
| 1 | 67% (2/3) | 67% (2/3) | Already finds About-dialogporten via phrase; doc-title would surface it too but no new chunks. |
| 2 | 0% | **>50%** | "Creating dialogs" exactly matches `linktitle/frontmatter_title` of doc `5f614bcedc12`. K=3 surfaces chunks 0–2 (v3 cites chunks 0–1). |
| 3 | 0% | **~50%** | "Lokal utvikling" matches `linktitle` of `e465a3646506`; "Konfigurasjon av autentisering" matches `frontmatter_title` of `fefc9064b271`. Strategy needs language detection (separate plan) to filter to NB. |
| 4 | 33% | **~67%** | "Nye tilgangspakker" matches `linktitle` of NB migration doc `60ea897f58fd`. |
| 5 | 0% | **~80%** | "Personroller"/"Virksomhetsroller" match `linktitle` of `b7841e9db901`/`e5a83fdead4d`. |
| 6 | 33% | 33% | The v7 doc literally doesn't exist; doc-title can't fabricate it. |
| 7 | 20% | 20% | The setup-subscription `linktitle` is "Set up a subscription" — partial match for "webhook signatures" only via low-overlap tokens. Different problem (refusal). |

**Expected aggregate**: 22% → ~50%+ overall hit rate on top-30,
without any changes to phrase/content/metadata strategies. The
phrase-corpus payoff predicted in v3 is gated more by query-shape
match than corpus health; adding `:doc-title` is the lower-cost
lever.

## Risks

- **Double-counting**: a chunk that hits via both `:doc-title` and
  `:content` would get merged weight from both. This is intended
  behavior (the merge layer's `strategy-weights` does the
  weighting). The contribution cap of 1 per strategy keeps the
  signal from compounding within a strategy.
- **Diversity cap interaction**: K=3 chunks per matched doc means
  a single doc-title hit can claim 3 of `max-per-document`'s 10
  slots before any other strategy contributes. If that becomes a
  problem, drop K to 1 (option (a)) — the agent read-tool can
  pull more chunks from the doc anyway.
- **Empty doc-title fields**: `linktitle` is 100% covered per the
  v2 README; `frontmatter_title` is 100%. No nil-handling needed.

## Out of scope

- Renaming `:metadata` → `:chunk-headers`. Defer to a follow-up.
- Cross-language doc-title (e.g. a NB question matching an EN
  linktitle). The language auto-filter (companion plan) handles
  that by narrowing to the right `language:=` first.
- Changing the chunks-collection schema. Doc-title is a docs-
  collection signal; no chunks-side changes.

# Q1 v2 — What is Dialogporten and what problem does it solve?

**Category**: Definitional
**v1 calls**: 2
**v2 calls**: 3 (or 2 in a clean corpus — see "Duplicate chunks" below)

## Strategy (v2)

The augmented corpus has `diataxis` (faceted) and `language`
(faceted). Definitional question → bias toward
`diataxis:=explanation && language:=en`. Combined with a query on
`linktitle,frontmatter_title,title`, this should narrow v1's
9-candidate result set to ~1.

## Trail

### Tool 1 — Targeted search with diataxis + language filter

```
bb ts-search digdir public-docs docs "Dialogporten" \
  --query-by "linktitle,frontmatter_title,title" \
  --filter-by 'diataxis:=explanation && language:=en' \
  --limit 5
```

**Result: 1 hit / 1 found.** `9a5fc194a710` — the
About-Dialogporten doc — surfaced cleanly on the first probe.

Contrast v1: title-only search returned 9 candidates needing
URL-pattern reasoning to pick the right one.

### Tool 2 — Range fetch chunks 0-2

```
bb ts-get digdir public-docs chunks --range 9a5fc194a710:0-2 \
  --include-fields chunk_id,chunk_index,content_markdown
```

Returned 4 chunks at indices 1 and 2 — *two each, with different
chunk_ids*. This is the duplicate-chunks side effect of the upsert
(see below). Doc record reports `total_chunks: 5` but the chunks
collection has 10 entries for this doc.

### Tool 3 — Explicit fetch for chunk 0

```
bb ts-search digdir public-docs chunks "Dialogporten" \
  --query-by content_markdown \
  --filter-by 'doc_num:=9a5fc194a710 && chunk_index:=0'
```

Returned 2 chunks at index 0:
- `965b3916f9e2` (lastmod 2025-12-04 — the v1-cited one)
- `ea7de904e1aa` (lastmod 2026-04-23 — newer)

Both contain the same definition sentence with one-word edit:
"which serves" → "that serves" between revisions.

## Cited chunks

Using the *newer* revisions (lastmod 2026-04-23):
- `ea7de904e1aa` (chunk 0) — definition + primary goal
- (chunk 1 — couldn't distinguish in range output; the *content* is
  identical to v1's cited `a233d1c22ebe` which is still present in
  the collection)
- `b8ddca7bace0` (chunk 2 new revision) — read-only model

## Answer

(Unchanged in substance from v1 — the corpus's wording is slightly
refined but the definition and goal are identical.)

**Dialogporten** is a solution that serves as a common API and
metadata state store for digital dialogs. It does not host the
substantive content of a service (forms, body texts, attachments,
form data); those remain on the originating service platform and
are merely *referenced* from a dialog by URL.
(`ea7de904e1aa`, `b8ddca7bace0`)

The primary goal is to standardize how public service platforms
(Altinn being the primary one) communicate ongoing digital dialogs
to users so a person can view and manage all their in-progress
interactions in one place (the "arbeidsflate"). Architecturally
it operates read-only for end users; writes are done by the
service provider on behalf of the service owner.

## Self-assessment vs v1

**What got better**:
- **The first probe is decisively sharper.** v1 returned 9
  candidates and required URL/title-pattern reasoning to pick the
  right one. v2 returned 1 candidate via `diataxis:=explanation &&
  language:=en`. This is the new-fields payoff for definitional
  questions.
- **The `diataxis` facet works as a question-type filter.** Pairing
  it with `language` made the query unambiguous: there's exactly
  one English-language explanation doc titled "Dialogporten".

**What got worse**:
- **Duplicate chunks contaminate `ts-get range` output.** The
  upserted re-ingest added new chunks (with new chunk_ids
  because content_markdown changed slightly between source-doc
  revisions) but didn't delete the old chunks. The chunks
  collection now contains both old and new revisions of every
  re-ingested doc's chunks. The Typesense `total_chunks` field on
  the doc record reports the *intended* chunk count (5) but the
  collection has the doubled count (10).
- For Q1 this added one tool call (chunk 0 had to be fetched
  separately because the range query's response order/limit hit
  duplicates before reaching index 0).

## Predicted gap on Q1 v2

**Smaller than v1 if the automated retrieval system can use the
`diataxis` facet**, similar to v1 otherwise. The new query shape
(filter by diataxis+language, query_by linktitle+frontmatter_title)
is the cheapest precise probe possible — but only if retrieval is
configured to know about and use those filters. A multi-strategy
retrieval that scores chunks without consulting doc-level facets
won't benefit.

## Corpus-quality finding (worth surfacing)

**Chunks collection contains stale duplicates.** After an
upsert-driven re-ingest where source markdown changed, the chunks
collection has old + new versions of the same logical chunk.
Recommended follow-up: at re-ingest time, after a doc's chunks
have been upserted, delete chunks where `doc_num` matches AND
`chunk_id` is NOT in the new set. This is the chunks-side
equivalent of the docs-side fix already landed in commit
`12f14a8`.

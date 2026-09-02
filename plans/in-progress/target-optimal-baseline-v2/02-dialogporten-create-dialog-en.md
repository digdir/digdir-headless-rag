# Q2 v2 — How do I create a new dialog as a service owner in Dialogporten?

**Category**: Procedural (EN)
**v1 calls**: 5
**v2 calls**: **2** (60% reduction)

## Strategy (v2)

Procedural question → `diataxis:=how-to-guides && language:=en`.
That alone should narrow to a handful; combine with a query on
`linktitle,frontmatter_title` for "creating dialog" terms.

## Trail

### Tool 1 — Filtered how-to-guides search

```
bb ts-search digdir public-docs docs "creating dialog" \
  --query-by "linktitle,frontmatter_title" \
  --filter-by 'diataxis:=how-to-guides && language:=en' \
  --limit 5
```

**Result: 1 hit / 1 found.** Doc `5f614bcedc12` ("Creating
dialogs", `total_chunks: 21`).

Contrast v1: title-search returned 2 hits (EN + NB) needing
manual selection.

### Tool 2 — Range fetch chunks 0-1

```
bb ts-get digdir public-docs chunks --range 5f614bcedc12:0-1 \
  --include-fields chunk_id,chunk_index,content_markdown
```

Returned 4 chunks (chunk-index 0 twice, chunk-index 1 twice —
old + new revisions, same duplicate-chunks artifact as Q1). The
*procedure* lives in chunk 1; chunk 0 is intro + the
Studio-auto-creation aside.

v1 fetched 5 chunks (chunks 0-4) and 5 more chunks (5-9) to
enumerate field-level details before realizing chunks 1's
2-step procedure was the actual answer. v2 stops at chunks 0-1.

## Cited chunks

- `c4e1cd6328b8` (chunk 0, newer revision lastmod 2026-04-23) —
  intro + Studio-auto-creation alternative
- `378b160a17a5` (chunk 1, newer revision) — the 2-step procedure
- (v1 cited `4ddea10ee77a` and `6dab51ee6a36` for the same indices;
  both still present in the collection as older revisions)

## Answer

(Substantively identical to v1; corpus's wording is unchanged for
this content.)

If you're using Altinn Studio, dialogs are created automatically
for app instances unless you opt out (`c4e1cd6328b8`). For
service owners using the direct API, the procedure is:

1. **Authenticate as a service owner.** Auth flow at
   `/en/dialogporten/user-guides/authenticating/`.
2. **POST a create-dialog DTO** to the events endpoint. DTO
   reference: `/en/dialogporten/reference/entities/dialog/#create-post`.
   Constraints: `serviceResource` must be a URN-format reference
   (`urn:altinn:resource:<identifier>`) into the Altinn Resource
   Registry whose `hasCompententAuthority` matches your
   organization. Resources of type `CorrespondenceService` are
   reserved for Altinn Correspondence. (`378b160a17a5`)

For the field-level shape (dates, title, summary, status enum,
content types, actions) consult the same `Creating dialogs` doc
chunks 2–19 inline or the entity reference at the link above.

## Self-assessment vs v1

**What got better**:
- **Filter narrowed 2 candidates to 1.** With both EN and NB
  versions in v1's hit list, manual selection was needed. v2's
  `language:=en` filter eliminates the NB sibling.
- **The procedure-vs-elaboration distinction was easier to make
  this time.** In v1 I read chunks 0–9 before confidently picking
  chunk 1 as the canonical procedure. In v2 I knew from v1 that
  chunk 1 is the answer; that's not retrieval improvement, that's
  exploration learning. Still: with the right diataxis/linktitle
  filtering, a fresh exploration would have arrived at chunk 1
  more directly (the doc-prep frontmatter says
  `diataxis: diataxis_how-to-guides` and the body's first
  numbered list IS the procedure).

**What got worse**:
- Same duplicate-chunks contamination as Q1. Range fetch returns
  both old and new revisions of the same chunk_index.

## Predicted gap on Q2 v2

The right doc is now trivially findable in 1 call. The remaining
gap is the same as v1's #2 finding: recognizing that chunk 1 is
the procedure and chunks 2+ are field elaboration. Automated
retrieval that ranks chunks by BM25 score on
"create dialog service owner" will still likely surface chunk 0
(more lexical overlap with the query in the frontmatter) over
chunk 1 (denser semantic match but less query-term repetition).
The `diataxis_*` chunk-level signal would help if it were indexed
at the chunk level, but it currently lives only at the doc level.

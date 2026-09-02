# Q2 v3 — How do I create a new dialog as a service owner in Dialogporten?

**Category**: Procedural (EN)
**v1 calls**: 5
**v2 calls**: 2
**v3 calls**: 2

## Strategy (unchanged from v2)

`diataxis:=how-to-guides && language:=en` + query on
`linktitle,frontmatter_title` for "creating dialog" terms.

## Trail

### Tool 1 — Filtered how-to-guides search

```
bb ts-search digdir public-docs docs "creating dialog" \
  --query-by "linktitle,frontmatter_title" \
  --filter-by 'diataxis:=how-to-guides && language:=en' \
  --limit 5
```

**1 hit / 1 found** — `5f614bcedc12` "Creating dialogs"
(`total_chunks: 21`). Identical to v2.

### Tool 2 — Range fetch chunks 0-1

```
bb ts-get digdir public-docs chunks --range 5f614bcedc12:0-1 \
  --include-fields chunk_id,chunk_index,content_markdown
```

**Result: 2 chunks, one per index.** v2 got 4 rows (duplicate
revisions); v3's orphan-delete makes the response honest.

| chunk_index | chunk_id | content |
|---:|---|---|
| 0 | `c4e1cd6328b8` | intro + Studio-auto-creation aside |
| 1 | `378b160a17a5` | the 2-step procedure |

## Cited chunks

- `c4e1cd6328b8` (chunk 0) — intro + the "if you use Altinn Studio,
  dialogs are auto-created" alternative
- `378b160a17a5` (chunk 1) — the canonical 2-step procedure

(Same logical chunks as v2; only the new-revision chunk_ids
remain in v3 because old revisions were orphan-deleted.)

## Answer

(Substantively identical to v1/v2.)

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

## Self-assessment vs v2

**What stayed the same**: call count (2), the docs probe (1 hit),
the cited content. The retrieval path is unchanged.

**What got better**: range-fetch returns the intended 2 rows
instead of 4. The "I had to mentally pick the newer revision"
overhead from v2 is gone — there's only one revision now.

## Predicted gap on Q2 v3

Same as v2. The remaining BM25-vs-semantic mismatch (chunk 0 has
more query-term repetition, chunk 1 has the actual procedure)
is unchanged by phrase-corpus health. The fix path here would be
chunk-level diataxis or a chunk-level "is_procedure" signal —
neither exists yet.

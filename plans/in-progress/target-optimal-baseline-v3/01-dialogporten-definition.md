# Q1 v3 — What is Dialogporten and what problem does it solve?

**Category**: Definitional
**v1 calls**: 2
**v2 calls**: 3 (the +1 was the duplicate-chunks workaround)
**v3 calls**: 2

## Strategy (unchanged from v2)

Definitional question → bias toward
`diataxis:=explanation && language:=en`. Combined with a query on
`linktitle,frontmatter_title,title`, this should narrow to ~1
candidate. Then range-fetch its 5 chunks.

## Trail

### Tool 1 — Targeted search with diataxis + language filter

```
bb ts-search digdir public-docs docs "Dialogporten" \
  --query-by "linktitle,frontmatter_title,title" \
  --filter-by 'diataxis:=explanation && language:=en' \
  --limit 5
```

**Result: 1 hit / 1 found.** `9a5fc194a710` — the
About-Dialogporten doc. Identical to v2.

### Tool 2 — Range fetch chunks 0-4

```
bb ts-get digdir public-docs chunks --range 9a5fc194a710:0-4 \
  --include-fields chunk_id,chunk_index,content_markdown
```

**Result: exactly 5 chunks, one per index 0..4. No duplicates.**

| chunk_index | chunk_id | content lede |
|---:|---|---|
| 0 | `ea7de904e1aa` | "Dialogporten is a solution that serves as a common API and metadata state store..." |
| 1 | `a233d1c22ebe` | "Dialogporten contains 'dialogs', which are representations..." |
| 2 | `b8ddca7bace0` | "Actual content, such as body texts... are not included... merely _referenced_..." |
| 3 | `20391f3e6ac4` | "The Altinn platform will automatically make all app instances..." |
| 4 | `93576af31a2a` | "Users can access their dialogs by logging in to altinn.no..." |

In v2 this same range request returned **10 rows** (two each at
indices 0–4) because old-revision chunks lingered alongside new
ones; v2 needed a Tool 3 follow-up search to disambiguate chunk 0.
The orphan-delete fix (commit `7719efd`) eliminated that workaround.

## Cited chunks

- `ea7de904e1aa` (chunk 0) — definition + primary goal
- `a233d1c22ebe` (chunk 1) — what a "dialog" is, structurally
- `b8ddca7bace0` (chunk 2) — read-only model, content-referenced

(Same logical chunks as v2; the chunk_ids are the post-fix revision
IDs, matching v2's "new revision" cites.)

## Answer

(Identical in substance to v1 and v2.)

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
(`ea7de904e1aa`, `b8ddca7bace0`, `93576af31a2a`)

## Self-assessment vs v2

**What got better**:
- **Range-fetch is honest again.** v2 had to issue a follow-up
  `chunks` search to disambiguate which chunk-0 revision to cite;
  v3's `ts-get range=0-4` returns the exact intended chunk set
  (one per index). The corpus-quality finding flagged in v2 is now
  resolved, so the call count drops 3 → 2.
- **Cited chunk_ids are stable for downstream comparison.** v2
  cites both old and new revisions across the trail; v3 cites a
  single canonical revision per chunk_index.

**What did not change**:
- The retrieval path is identical (1 sharp docs probe + 1 range
  fetch). The cleaner phrase corpus is irrelevant here — this
  question is fully answered from chunk-content with no need for
  phrase-level retrieval signal.

## Predicted gap on Q1 v3

Smaller than v2 only insofar as the automated retrieval system
won't trip on duplicate chunks (e.g. ranking the same chunk twice
or surfacing a stale revision's wording). The optimal trail
itself is already minimal at 2 calls — no further headroom for
this question.

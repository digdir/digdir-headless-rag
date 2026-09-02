# Slice 25 — Stemming discovery (Branch (a) confirmed)

Discovery phase of slice 25 (morphology expansion). Findings
support Branch (a) — schema migration to enable Snowball
stemming. Implementation pending alignment on the schema-change
mechanics, because the PATCH endpoint is more invasive than the
initial reading suggested.

## What Typesense supports

| Property | Verified |
|---|---|
| Per-field stemming | `stem: true` on a string field |
| Tokenizer & language | Snowball, language auto-selected via `locale` |
| Languages | Snowball default + ISO 639 two-letter codes; Norwegian Bokmål `nb` not explicitly listed |
| Custom dictionaries | Optional, separate artifact (`stem_dictionary`) |
| Schema updates | PATCH endpoint, but **adding `:stem` to an existing field requires drop + re-add** — Typesense doesn't support modifying field properties in place |
| Data persistence | Drop affects schema and in-memory index only; disk-stored data persists |
| Operation behavior | Writes block during the schema update (across the cluster on HA); reads unblocked |

## What the live schema looks like

`server/src/digdir/docs/website.clj:250-275`

- `docs.title` — `:locale "en"`, no `:stem`.
- `docs.linktitle`, `docs.frontmatter_title` — no locale, no `:stem`.
- `chunks.content_markdown` — `:locale "en"`, no `:stem`.
- `chunks.metadata` — no locale, no `:stem`.

**Stemming is verifiably off everywhere.** `:locale` alone sets
tokenization, not stemming.

## Empirical confirmation

### English (Q2)

| Query | Hits | Q2 target `5f614bcedc12` |
|---|---:|---|
| `q=create` query_by=frontmatter_title | 7 | absent |
| `q=creating` query_by=frontmatter_title | 6 | **#3** |
| Overlap between sets | **0** | — |

Snowball-en stems both `create` and `creating` → `creat`, so an
overlap of zero proves the field is unstemmed.

### Norwegian (Q3)

| Query | Hits | Q3 target `e465a3646506` |
|---|---:|---|
| `q=utvikling` query_by=frontmatter_title,linktitle | 5 | **#2** |
| `q=utvikle` same | 12 | absent |
| Overlap between sets | **0** | — |

Same pattern. Snowball-nb (if applied) would stem both → `utvikl`.

## The implementation question

The plan's "PATCH endpoint avoids re-ingest" assumption was
**partially true** — disk data persists across drop+re-add, but
the re-add must re-build the in-memory index from disk. Behavior
not fully documented. Risks on the live collection:

- **Window of write blocking.** Any concurrent ingest pauses
  for the duration of the schema patch.
- **Search degradation while re-indexing.** If the re-add
  doesn't auto-reindex from disk, the field is empty until
  documents are re-upserted.
- **No simple rollback.** Once dropped, the field is gone from
  the in-memory index. Recovery requires re-applying the
  original schema definition (and possibly re-upserting docs).

## What's left to decide before changes go live

Implementation options ordered from safest to fastest:

1. **Clone-and-test.** Create a sister collection
   (e.g., `website_documents_stem_test`), copy a sample of docs
   in, apply the stemmed schema, verify the divergence probes
   now overlap, then promote. Higher confidence; takes longer.
2. **In-place drop+re-add with a write-pause window.** Stop
   ingest, apply the PATCH, run the divergence probes
   immediately, then re-measure v3. Risk-tolerant; faster.
3. **Schema definition update + full re-ingest from source.**
   Change the schema in `website.clj`, run the ingest pipeline,
   produces a clean new collection. Highest cost but most
   predictable.

The user has chosen **English-first** scope, deferring Norwegian
to a follow-up slice if Q3 still misses after Q2 improves.

## What stays open

- Confirm whether Snowball-nb works via empirical test (after
  English ships) by patching `linktitle` to `stem: true` +
  `locale: "nb"` on a clone.
- After the English schema patch lands, the divergence-probe
  test should produce overlapping sets. If it doesn't, that's
  evidence Typesense's "stem: true" implementation diverges from
  documentation.
- v3 measurement plan unchanged: 3 runs of cap-N3 with the new
  schema, compared against slice 22's 42.0% / 50.7% / 64.0% /
  66.7%.

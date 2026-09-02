# Slice 25 — Per-language stemmed fields, implementation plan

Follow-on to `24-stemming-discovery.md`. The user opted for:
**source-update + full re-ingest, both languages simultaneously,
with `_en` and `_nb` suffixed fields, all with `stem: true`.**

Corpus distribution justifies the both-languages scope: 1044 EN
docs / 1068 NB docs / 27 untagged.

## Net schema change

### Docs collection (`website-docs-schema`)

Add — keep existing untouched first pass for safety; remove in a
follow-up commit once probes pass:

| New field | Type | Stem | Locale |
|---|---|---|---|
| `title_en` | string | true | `en` |
| `title_nb` | string | true | `nb` |
| `linktitle_en` | string | true | `en` |
| `linktitle_nb` | string | true | `nb` |
| `frontmatter_title_en` | string | true | `en` |
| `frontmatter_title_nb` | string | true | `nb` |

### Chunks collection (`website-chunks-schema`)

| New field | Type | Stem | Locale |
|---|---|---|---|
| `content_markdown_en` | string | true | `en` |
| `content_markdown_nb` | string | true | `nb` |
| `metadata_en` | string | true | `en` |
| `metadata_nb` | string | true | `nb` |
| `language` | string | — | — (facet+filter) |

Chunks currently lack `language`. Add it for parity with docs
and so the retrieval-side language filter (already used on docs)
can be applied to chunks too.

## Ingest changes

`prepare-website-doc` (website.clj:294-301):
- Pick language from the URL-derived `:language` (`en`, `nb`, or nil).
- For each `title|linktitle|frontmatter_title`, populate the
  matching `_<lang>` variant only. Untagged docs (no `/en/` or
  `/nb/` in URL) — populate **neither** suffix (safer than wrong).

`prepare-website-chunks` (website.clj:303-310):
- Inherit language from the doc (already linked via `:doc_num`).
- For each `content_markdown|metadata`, populate the matching
  `_<lang>` variant. Set `:language` on the chunk too.

Implementation note: the loader maps over chunks within
`(prepare-website-chunks config chunks)`; the per-doc language
is the same for all chunks in that batch.

## Retrieval changes

Three call sites in `server/src/digdir/rag/retrieval.clj`:

| Line | Strategy | Current `:query_by` | New `:query_by` |
|---|---|---|---|
| 333 | `:metadata` | `"metadata"` | `"metadata_en,metadata_nb"` |
| 374 | `:content` | `"content_markdown"` | `"content_markdown_en,content_markdown_nb"` |
| 447 | `:doc-title` | dynamic from `title-fields` config | new defaults add `_en,_nb` variants |

Both languages queried per call; Snowball applies locale per
field; whichever matches wins on `_text_match:desc`.

Retrieval skill default `title-fields` config (currently
`["linktitle" "frontmatter_title"]`) becomes
`["linktitle_en" "linktitle_nb" "frontmatter_title_en" "frontmatter_title_nb"]`.

## Forcing a fresh collection

`extract-config-subset` (collections.clj:14-25) drives the
collection name hash. Schema fields are NOT in the hash subset,
so changing the schema alone yields the same collection name.

Two acceptable approaches:
1. **Add a schema-version key** to the hash subset (e.g.,
   `:schema-version`). One-line code change in `extract-config-subset`.
   Bump the value in the pipeline config to force a new hash.
2. **Bump `:collection-prefix`** in pipeline config. No code change.

Approach (1) is more durable — future schema changes can bump
the version cleanly. We'll use it.

## Migration sequence

1. **Code changes (reversible)**
   - Update schemas, ingest, retrieval, retrieval-skill config defaults.
   - Add `:schema-version` to hash subset and bump it.
2. **bb test + bb lint** — must pass before any destructive step.
3. **Trigger re-ingest** — full pipeline run against public-docs.
   This is the destructive step. Old collections (`ab897fbdedfa`)
   remain in Typesense until manually deleted; new collections
   come up alongside.
4. **Empirical verification** — re-run the divergence probes on
   the new collections; expect overlap > 0.
5. **Update retrieval to point to new collection** — automatic
   if collection names come from the pipeline config, but verify.
6. **v3 measurement** — 3 runs of `bb v3-score` with the
   slice-22 cap-N3 union default. Compare against:
   - Slice 22: top-10 42.0% / top-30 50.7% / top-10 docs 64.0% / top-30 docs 66.7%.
7. **Write results** — `26-stemming-results.md`.
8. **Cleanup** — drop the legacy collection after a few days
   of stable operation.

## Risk register

| Risk | Mitigation |
|---|---|
| `nb` locale not actually supported by Snowball | Empirical probe after re-ingest. If broken, revert NB fields to no stem; keep EN. |
| Re-ingest fails partway | Old collection unchanged. Switch retrieval back to the old name temporarily. |
| Per-strategy weights tuned for single-field query_by need re-tuning for multi-field | Defer to a follow-up if v3 numbers regress despite stemming working. |
| Untagged docs (URL has no /en/ or /nb/) become unsearchable | They're a tiny fraction of the corpus; explicitly logged. Worst case: keep legacy `title`/`linktitle`/`frontmatter_title` fields populated as a fallback. |
| Search latency increases due to multi-field query_by | Negligible per Typesense docs — multi-field is the common pattern. |

## What this slice does NOT ship

- Norwegian-specific stemming follow-up if `nb` locale turns out
  not to work — that's a separate fix.
- Migrating away from the legacy `title`/`linktitle`/
  `frontmatter_title`/`content_markdown`/`metadata` fields. We
  leave them in the schema as untouched fallback for now;
  remove in a follow-up commit if/when the new fields are
  proven stable.
- Re-running pre-existing measurements that aren't on the slice
  22 cap-N3 default; we benchmark against slice 22 only.

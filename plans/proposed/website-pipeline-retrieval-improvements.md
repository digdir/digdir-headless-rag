# Website pipeline retrieval improvements

## Goal

Close the retrieval-config and chunker-behavior gaps surfaced by the
target-optimal baseline trails
(`plans/in-progress/target-optimal-baseline/`) so the next round of
baseline measurement isn't contaminated by index defects we already
know about.

## Evidence base

- **42.4% chunk-metadata coverage** (vs 74.4% in kudos): 3,409 of
  5,919 chunks in `website_chunks_ab897fbdedfa` have empty `{}`
  metadata. The metadata-search strategy
  (`digdir.rag.retrieval/search-chunks-by-metadata`) is dead for
  these chunks.
- **NB title-search returned 0 hits in Q3, Q4, Q5** (Norwegian
  procedural / temporal / cross-language questions in the
  target-optimal baseline). Indexed `title` is derived from the URL
  path segment ("Roles altinn", "New accessgroups") rather than from
  the Hugo frontmatter title ("Roller fra Altinn", "Innføring av nye
  tilgangspakker som erstatning for dagens Altinn 2 roller") or
  `linktitle` ("Altinn-roller", "Nye tilgangspakker"), which are
  what users actually type.
- **`diataxis_*` frontmatter is in every doc's chunk content but
  not indexed.** It maps directly to question type
  (`explanation` → definitional, `how-to-guides` → procedural,
  `reference` → lookup). Free signal currently going unused.
- **No `language` field on docs.** Filtering by URL prefix doesn't
  work as `filter_by` (Typesense's filter is equality-only on
  strings), forcing URL-pattern reasoning that automated retrieval
  can't easily express.
- **`println` debug pollution in chunker.** `chunking.clj`
  lines 139–141 and 153–154 fire for every doc during ingest.

## Proposed changes

Six discrete changes, organized by impact area. Each is independently
implementable; the order in §"Execution order" below sequences them.

### Doc-level fields (cheap, in-place safe)

**(A) Index Hugo `linktitle` as a searchable doc field.**

- File: `server/src/digdir/docs/website.clj`
- `website-docs-schema` (~line 193): add
  ```
  {:facet false :index true :name "linktitle" :optional true :sort false :type "string"}
  ```
  Locale defaults to none — Typesense will tokenize permissively
  enough that both EN and NB queries match.
- `url-to-doc` (~line 177) currently doesn't parse markdown
  frontmatter. Add a step in `mk-fetch-markdown-t` (~line 338) or
  `mk-prepare-document-t` (~line 352) that, once markdown is
  fetched, parses YAML frontmatter and merges relevant fields into
  the doc map.
- `prepare-website-doc` (~line 233): extend `select-keys` to
  include `:linktitle`.

**(B) Index Hugo `diataxis` as a faceted doc field.**

- Same file. Schema:
  ```
  {:facet true :index true :name "diataxis" :optional true :sort false :type "string"}
  ```
- Same frontmatter-extraction step as (A) populates it.
- `prepare-website-doc` `select-keys` += `:diataxis`.

**(D) Derive `language` from URL prefix, store as faceted field.**

- Schema:
  ```
  {:facet true :index true :name "language" :optional true :sort false :type "string"}
  ```
- New helper `derive-language`: `/en/...` → `"en"`,
  `/nb/...` → `"nb"`, else `nil`. Call from `url-to-doc`.
- `prepare-website-doc` `select-keys` += `:language`.

#### Optional but recommended: replace URL-derived `title` with Hugo `title`

The current `title` field is `extract-title-from-url(loc)`, producing
"Roles altinn" from `/en/altinn-studio/.../roles_altinn/`. The Hugo
frontmatter title is "Roller fra Altinn" — strictly more useful.

- Two paths:
  - **A1 (purely additive, safest)**: add `frontmatter_title` as a
    new field; leave URL-derived `title` as-is. Backwards-compatible
    with all existing `$docs(url,title)` joins.
  - **A2 (replacement)**: replace `title`'s value with the
    frontmatter title (keeping the field name). Slight casing
    differences and Norwegian/English title text in existing joined
    output — likely acceptable but worth flagging.

Recommend A1 to avoid surprising any current consumer.

### Chunker improvements (expensive, requires full re-ingest)

**(E) Strip Hugo frontmatter before chunking.**

- File: `server/src/digdir/rag/chunking.clj`, function
  `split-into-chunks-by-headers` (~line 88).
- Add a pre-processing step that detects YAML frontmatter
  (`^---\n...\n---\n` at the start of `page-content`) and removes
  it before the line-by-line header-walk begins.
- Cleaner first chunks; frontmatter data is captured at doc-prep
  time (A/B/D above) anyway.

**(F) Use frontmatter title (or `linktitle`) as a synthetic
`Header 0` breadcrumb for chunks that would otherwise have empty
metadata.**

- Same file. After chunking, walk the chunk list: for any chunk
  with empty `{}` metadata, populate it with
  `{"Header 0" <frontmatter-title>}`.
- Pass the frontmatter title down through `chunk-doc` (in
  `digdir/docs/loader.clj` ~line 258) or the website pipeline's
  equivalent so the chunker has it available.
- Effect: closes ~58% empty-metadata gap. Every chunk gets at
  least the doc title as a breadcrumb; chunks under deeper
  headers get the full path.

**(G) Remove debug `println`s.**

- File: `server/src/digdir/rag/chunking.clj`, lines 139–141 and
  153–154. The `(println "All chunks created:" ...)` and per-chunk
  `(println "Chunk metadata:" ...)` are leftover dev logging that
  floods stdout during ingest. Either delete or gate behind an
  env-checked debug flag (`RAG_DEBUG_LOGGING` already exists per
  `digdir.rag.core`, follows the same pattern).

## Re-ingest strategy

| Change | Strategy | Cost |
|---|---|---|
| A, B, D, A1 (doc-level fields only) | **In-place schema patch** via Typesense `PATCH /collections/website_documents_ab897fbdedfa` to add optional fields. **Docs-only re-ingest**: re-fetch sitemap, re-fetch markdown for each URL, parse frontmatter, upsert docs only. No chunks, no phrases regenerated. | ~5–30 min for 2,100 URL fetches (local Hugo source per user). Zero LLM calls. |
| E, F, G (chunker changes) | **New hash, new collection names.** Chunker change → new chunk content_markdown → new chunk_ids → cascading rebuild. Bump one of the hashed config values (`:chunks/hash-changer` in `website.clj:322` exists for exactly this purpose). Full pipeline run: fetch → chunk → phrase-generate → store. | Hours; ~5,919 LLM calls for phrase generation. Old collections stay live as fallback. |

The Tier 1+2 (A/B/D/A1) changes are safely backward-compatible: new
optional fields are invisible to existing consumers. The chunker
changes (E/F) produce a parallel collection set; existing readers
keep working on the old collections until repointed.

## Implementation map (files + approximate line numbers)

| File | Function / area | Change |
|---|---|---|
| `server/src/digdir/docs/website.clj` | `website-docs-schema` (~193) | Add 3 (or 4) optional fields |
| `server/src/digdir/docs/website.clj` | `url-to-doc` (~177) | Add language derivation |
| `server/src/digdir/docs/website.clj` | `mk-fetch-markdown-t` or after (~338) | Frontmatter parser + merge into doc map |
| `server/src/digdir/docs/website.clj` | `prepare-website-doc` (~233) | `select-keys` += new fields |
| `server/src/digdir/docs/website.clj` | new helper | `parse-yaml-frontmatter` (regex extract YAML block + parse) |
| `server/src/digdir/docs/website.clj` | new helper | `derive-language-from-url` |
| `server/src/digdir/rag/chunking.clj` | top of `split-into-chunks-by-headers` (~88) | Frontmatter-strip pre-process (E) |
| `server/src/digdir/rag/chunking.clj` | after chunk-list built | Synthetic Header-0 backfill (F) |
| `server/src/digdir/rag/chunking.clj` | lines 139–141, 153–154 | Delete or gate `println`s (G) |
| `server/src/digdir/docs/loader.clj` | `chunk-doc` (~258) | Pass frontmatter title through to chunker (for F) |
| `server/src/digdir/docs/website.clj` | `:chunks/hash-changer` (~322) | Bump from `1` to `2` to force chunks/phrases re-hash |
| Typesense (external) | `PATCH /collections/website_documents_ab897fbdedfa` | Add optional fields A/B/D (or A1) |

## Verification plan

After re-ingest:

1. `bb ts-search digdir public-docs docs "Altinn-roller" --query-by linktitle --limit 5` should return Norwegian-titled hits (currently returns 0).
2. `bb ts-search digdir public-docs docs "tilgangspakker" --query-by linktitle --limit 5` should return the migration doc.
3. `bb ts-search digdir public-docs docs "*" --facet-by diataxis --limit 0` should show the distribution of doc types (explanation / how-to-guides / reference).
4. `bb ts-search digdir public-docs docs "*" --facet-by language --limit 0` should show NB vs EN counts.
5. For chunker-side changes, the empty-metadata count should drop from 3,409 → near 0:
   ```
   bb ts-search digdir public-docs chunks "Header" --query-by metadata --limit 1
   ```
   should return `~5,919 / 5,919` instead of `2,510 / 5,919`.

## Re-baseline plan

After verification, re-run the 7 questions from
`plans/in-progress/target-optimal-baseline/` against the augmented
corpus. Capture trails in `plans/in-progress/target-optimal-baseline-v2/`
(parallel directory). Expected deltas (predictions, not promises):

- **Q3 (NB procedural auth)** — should drop from 14 calls toward
  5–6 once `--query-by linktitle` finds the right doc on the
  first probe.
- **Q4 (Roller→Tilgangspakker)** — already 5 calls; expect 3
  with linktitle indexing.
- **Q5 (Roller persons vs enterprises)** — 6 → ~4 with Norwegian
  linktitle making the parent overview doc directly
  title-searchable.
- **Q2 (Creating dialogs)** — should stay around 5; this question
  was already in the easy regime.
- **Q1, Q6, Q7** — likely unchanged. These weren't bottlenecked
  on the indexing issues.
- **New possibility**: with `diataxis` faceted, the strategy
  "search docs filtered by `diataxis:=explanation` for definitional
  questions" becomes available — measure whether it improves Q1's
  source-doc-selection in the chunks tier.
- **Chunker fixes**: separately, the
  `digdir.rag.retrieval/search-chunks-by-metadata` strategy goes
  from "useless on 58% of chunks" to "useful on ~100%". Direct
  measure via running a sweep with metadata-strategy weight high
  before/after.

## Risks & open questions

- **Shared Typesense host.** `typesense-test.digdir.cloud` is
  shared infra. In-place patches (A/B/D) add optional fields and
  are backward-compatible; chunker re-ingest (E/F/G) produces a
  new collection set rather than touching the live one, so
  existing consumers continue against the current collection.
- **Frontmatter shape variation.** Some docs may not have
  `linktitle` (it's a Hugo convention but optional). All new
  fields should be `:optional true`. The frontmatter parser must
  cope with missing fields gracefully.
- **A1 vs A2 choice on the `title` field.** Recommend A1 (additive
  `frontmatter_title` field, keep URL-derived `title`). A2 is
  cleaner but changes the meaning of `title` for any current
  consumer of the `$docs(url,title)` join.
- **The chunker change affects the kudos/folder/episerver
  pipelines too.** `digdir.rag.chunking/split-into-chunks-by-headers`
  is shared. Stripping frontmatter is safe (kudos PDFs don't have
  YAML frontmatter). The synthetic Header-0 backfill needs the
  doc title to be passed in; for non-website pipelines, the kudos
  loader already has a `title` field on docs, so the same
  mechanism should work — but verify.
- **`:chunks/hash-changer` bump invalidates the existing chunks
  AND phrases collections.** The OLD `website_*_ab897fbdedfa`
  collections stay live until something points away from them.
  Need to coordinate the dataset-config flip (or run side-by-side
  for comparison).

## Suggested execution order

1. **G first** — delete the `println`s. Trivial, zero risk, makes
   the re-ingest logs readable.
2. **Implement A/B/D code changes** (in `website.clj`). Adds
   frontmatter parser, language deriver, schema fields. Don't
   touch Typesense yet.
3. **Schema-patch Typesense** for the docs collection to add the
   new fields (Typesense `PATCH /collections/...`).
4. **Docs-only re-ingest** for digdir/public-docs. ~5–30 min.
   Verify per §"Verification plan" steps 1–4.
5. **Re-baseline Tier 1+2** — re-run the 7 questions, write to
   `target-optimal-baseline-v2/`. Compare v1↔v2 metrics; this is
   the natural pause point to see how much the doc-level fixes
   alone bought us.
6. **Decide whether chunker fixes (E/F) are worth it** based on
   v2 baseline gap analysis. If yes, proceed with steps 7–9.
7. **Implement E/F code changes** (in `chunking.clj` and the
   `chunk-doc` data flow). Bump `:chunks/hash-changer`.
8. **Full re-ingest** into new `website_*_<newhash>` collections.
   Hours; LLM cost for phrase generation. Verify §"Verification
   plan" step 5.
9. **Re-baseline a third time** (v3) — measure how much the
   chunker fixes bought on top of v2.

## What the executor returns with

A summary documenting:
- Which steps completed, which were deferred
- Verification probe results (counts before / after)
- v2 (and maybe v3) baseline trails — placed in
  `plans/in-progress/target-optimal-baseline-v2/` and (if done)
  `target-optimal-baseline-v3/`
- A comparison table: v1 vs v2 (vs v3) call counts per question,
  qualitative notes on what got easier/harder

…then resume the broader thread: measure the gap between the
target-optimal trails and the automated retrieval stack
(`bb ts-retrieve` with `RAG_TS_RETRIEVE_ENABLED=true`), which is
the original goal this all flows into.

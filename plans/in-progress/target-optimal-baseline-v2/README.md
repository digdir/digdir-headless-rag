# Target-optimal baseline v2 — augmented corpus

Re-run of the 7-question target-optimal baseline against the
augmented `digdir/public-docs` corpus. Same protocol, same
questions, same tools — but now with the four new indexed fields
on docs:

- `linktitle` — Hugo frontmatter `linktitle` (the short
  user-natural form, e.g. "Nye tilgangspakker", "Personroller")
- `frontmatter_title` — Hugo frontmatter `title` (the full
  descriptive title)
- `diataxis` — Hugo frontmatter `diataxis` (explanation /
  how-to-guides / reference / tutorials) → faceted
- `language` — derived from URL prefix (`en` / `nb`) → faceted

Coverage after re-ingest:

| Field | Coverage |
|---|---|
| `linktitle` | 100% (2136/2136) |
| `frontmatter_title` | 100% |
| `language` | 98.7% (2109/2136) |
| `diataxis` | 68.2% (1457/2136) — not every doc has the frontmatter field |

## Protocol (unchanged from v1)

1. Reasoning first, retrieval second.
2. Tool budget ~10 calls per question.
3. Grounding rule: every claim cites `chunk_id`s; refuse if not
   supported.
4. Trail recording: every tool call captured verbatim.
5. Self-assessment with explicit comparison to v1.

## Question index

| # | File | Category | v1 calls | v2 calls | Δ | Status |
|---|---|---|---:|---:|---:|---|
| 1 | [01-dialogporten-definition.md](01-dialogporten-definition.md) | Definitional | 2 | 3* | +1 | ✅ |
| 2 | [02-dialogporten-create-dialog-en.md](02-dialogporten-create-dialog-en.md) | Procedural EN | 5 | **2** | −3 | ✅ |
| 3 | [03-altinn-app-auth-dev-nb.md](03-altinn-app-auth-dev-nb.md) | Procedural NB | **14** | **5** | **−9** | ✅ |
| 4 | [04-altinn-roller-tilgangspakker.md](04-altinn-roller-tilgangspakker.md) | Temporal | 5 | 3 | −2 | ✅ |
| 5 | [05-altinn-roller-persons-vs-enterprises.md](05-altinn-roller-persons-vs-enterprises.md) | Cross-language | 6 | 6 | 0 | ✅ |
| 6 | [06-altinn-studio-v7-v8-data-model.md](06-altinn-studio-v7-v8-data-model.md) | Cross-doc synthesis | 8 | 4 | −4 | ✅ partial refusal |
| 7 | [07-webhook-hmac-sha512-refusal.md](07-webhook-hmac-sha512-refusal.md) | Refusal | **14** | 4† | −10 | ✅ confident no |
| | | **TOTAL** | **54** | **27** | **−27 (50% reduction)** | |

\* Q1: would have been 2 in a clean corpus; +1 was needed because
duplicate chunks (old + new revisions of same chunk_index) crowded
out chunk 0 from the range-fetch response.

† Q7: 4 calls assumes v1 knowledge of the canonical doc_num. A
v1-blind fresh exploration would still be 5–6 calls — refusal-
confirmation doesn't benefit much from the new fields.

v1 totals: 54 calls, avg 7.7, median 6.
**v2 totals: 27 calls, avg 3.9, median 4.**

## Predictions for v2 (from the improvements plan)

- Q3 (NB procedural auth): 14 → ~5–6 once `--query-by linktitle`
  finds the auth doc on first probe.
- Q4 (Roller→Tilgangspakker): 5 → ~3 with linktitle indexing.
- Q5 (Roller persons vs enterprises): 6 → ~4 with Norwegian
  linktitle making the parent overview directly title-searchable.
- Q1 (Dialogporten): probably unchanged (already 2 calls).
- Q2 (Creating dialogs): probably ~5 still.
- Q6 (v7 v8 data model): no change — corpus coverage gap, not
  indexing.
- Q7 (HMAC-SHA512 refusal): no change — refusal discipline issue.

New strategy enabled by `diataxis` faceting: bias definitional
queries toward `diataxis:=explanation`, procedural toward
`how-to-guides`, etc. Worth trying on Q1 / Q2 to see if it
trims further.

## Cross-cutting findings

### The improvements worked

- **Tool call count halved**: 54 → 27. Average per question
  7.7 → 3.9.
- **Q3 (NB procedural auth)** — the v1 disaster question (14
  calls, over budget) — dropped to 5. The 9-call gap was *entirely*
  the title-search dud problem: `linktitle` containing the actual
  Norwegian term ("Autentisering", "Lokal utvikling") closed it.
- **Q2** went from 5 → 2 because `diataxis:=how-to-guides &&
  language:=en` narrowed 2 candidates to exactly 1.
- **Q4 / Q5** — NB queries that previously needed EN-title
  fallback or chunk-content fallback now hit `linktitle` directly.

### The new strategy that emerged

**Filter by `diataxis` and `language`, query by `linktitle` and
`frontmatter_title`.** This is the cheapest precise probe for
almost every question:

| Question type | Filter | Query field |
|---|---|---|
| Definitional ("what is X?") | `diataxis:=explanation && language:=<L>` | `linktitle,frontmatter_title` |
| Procedural ("how do I X?") | `diataxis:=how-to-guides && language:=<L>` | `linktitle,frontmatter_title` |
| Reference lookup | `diataxis:=reference && language:=<L>` | `linktitle,frontmatter_title` |
| Tutorial / walkthrough | `diataxis:=tutorials && language:=<L>` | `linktitle,frontmatter_title` |

When this returns 0 or 1 wrong hit (e.g. Q3 Tool 1), the
escape is to broaden either: drop the diataxis filter,
broaden it to a multi-value `diataxis:=[a,b]`, or fall back to
chunk-content search.

### What didn't get better

- **Refusal questions (Q7, Q6).** The new fields don't help when
  the answer is "the corpus doesn't say". The dominant failure
  mode (literal-query-matches-chunk → fluent fabrication) is
  unchanged.
- **Cross-doc synthesis (Q3, Q5, Q4).** When the answer needs
  pieces from 2+ docs, retrieval still needs 2+ searches.
  Fields make each search sharper but don't reduce the count.

### New corpus-quality findings surfaced during v2

1. **Duplicate chunks in the chunks collection.** The upsert-
   driven re-ingest added new chunks (with new chunk_ids when
   `content_markdown` changed slightly) but didn't delete the
   old ones. Multiple docs now have old + new revisions of the
   same `chunk_index`. `total_chunks` on the doc record shows
   the *intended* count; the collection has roughly double.

   Recommended fix: at re-ingest time, after a doc's chunks have
   been upserted, delete chunks where `doc_num` matches AND
   `chunk_id` is NOT in the new set. Equivalent to the docs-side
   fix landed in commit `12f14a8`.

2. **The NB sibling of the Tilgangspakker migration doc is
   shorter than its EN sibling** (2 chunks vs 3) — missing the
   11-question Q&A. Cross-language synthesis required reading
   both docs.

3. **`linktitle` indexing didn't help refusal questions.** The
   setup-subscription doc's `linktitle` is "Set up a
   subscription" but searching with `query-by linktitle "subscription"`
   returned 0 because of how Typesense tokenizes / matches the
   field. Worth investigating tokenization settings for
   `linktitle`.

4. **New "Altinn-2 datamodel" doc appeared** (`837753b0cd68`,
   added between v1 and v2 ingests). Could mislead retrieval
   systems answering Q6 if they confuse Altinn 2 (platform) with
   v7 (app NuGet version).

### Implications for the gap-measurement phase

- The retrieval gap on procedural/temporal NB questions should
  narrow considerably with these field additions. If the
  automated retrieval can use `diataxis`/`language` facets and
  `linktitle`/`frontmatter_title` as searchable fields, much of
  the v1 gap closes.
- The retrieval gap on refusal/cross-doc-synthesis questions is
  largely *unchanged*. These are judgment problems retrieval
  improvements can't address.
- The duplicate-chunks issue will inflate retrieved hit counts
  for any chunk-level retrieval — recommend fixing before
  measuring multi-strategy retrieval quality.

### Next step

Measure the actual gap: enable `RAG_TS_RETRIEVE_ENABLED=true`,
re-run the 7 questions through `bb ts-retrieve`, compare its
retrieved chunks against this v2 baseline's cited chunks per
question. Where does the automated retrieval pick differently?
Why? What does the gap analysis tell us about which fixes
matter most for the next iteration?

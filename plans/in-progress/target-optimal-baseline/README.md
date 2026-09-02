# Target-optimal baseline — Claude with filesystem-style Typesense tools

This directory captures one careful operator's attempt to answer a
curated 7-question set against the `digdir/public-docs` corpus using
only the `ts-search` and `ts-get` debug tools (the `ts-retrieve`
multi-strategy wrapper is intentionally disabled).

The aim isn't to score retrieval; it's to establish a **target** the
automated retrieval stack can be measured against. Each answer doc
records the full retrieval trail, so the trail itself becomes the
artifact the automated system has to reproduce or beat.

## Protocol

Per question:

1. **Reasoning first, retrieval second.** Strategy written before
   any tool call.
2. **Tool budget**: aim for ≤10 retrieval calls. Going over is
   recorded as evidence of retrieval difficulty.
3. **Grounding rule**: every claim cites `chunk_id`s; corpus-silent
   means "I cannot answer this from the corpus" — no fallback to
   prior knowledge.
4. **Trail recording**: every tool call captured verbatim with
   parameters and hit count.
5. **Self-assessment**: what was hard, what surprised me, what an
   automated system would plausibly miss.

## Question index

| # | File | Category | Tool calls | Status |
|---|---|---|---:|---|
| 1 | [01-dialogporten-definition.md](01-dialogporten-definition.md) | Definitional | 2 | ✅ |
| 2 | [02-dialogporten-create-dialog-en.md](02-dialogporten-create-dialog-en.md) | Procedural EN | 5 | ✅ |
| 3 | [03-altinn-app-auth-dev-nb.md](03-altinn-app-auth-dev-nb.md) | Procedural NB | **14** | ✅ over budget |
| 4 | [04-altinn-roller-tilgangspakker.md](04-altinn-roller-tilgangspakker.md) | Temporal | 5 | ✅ |
| 5 | [05-altinn-roller-persons-vs-enterprises.md](05-altinn-roller-persons-vs-enterprises.md) | Cross-language | 6 | ✅ |
| 6 | [06-altinn-studio-v7-v8-data-model.md](06-altinn-studio-v7-v8-data-model.md) | Cross-doc synthesis | 8 | ✅ partial refusal |
| 7 | [07-webhook-hmac-sha512-refusal.md](07-webhook-hmac-sha512-refusal.md) | Refusal | **14** | ✅ confident no |

**Totals**: 54 tool calls across 7 questions (avg 7.7, median 6).
Two questions blew through the 10-call budget — Q3 because of NB
vocabulary mismatch, Q7 because confirming the *absence* of a
feature requires verifying multiple plausible places.

## Cross-cutting findings

### Tool surface: what worked

- **The `ts-search` / `ts-get` filesystem analogy held up.** Every
  question reduced to a `grep`-and-`Read` rhythm: search docs
  collection for likely titles, get parent metadata, drill into
  the most plausible doc by chunk-range or specific IDs. No call
  shape was missing — once the defaults were fixed
  (commit `963ea1e`), the tools were sufficient.
- **Parallel probing was decisive.** After Q3's wasted calls on a
  single-search-at-a-time strategy, batching 2–4 search probes per
  Bash invocation gave coverage in fewer round trips. Q4's
  parallel-title-and-chunk probe found the right doc in 2 calls
  total.
- **Reading whole docs (small ones) is cheaper than searching
  in-doc.** For 1–5-chunk docs, `ts-get range=doc:0-N` was
  consistently better than a chunk-content search scoped to the
  doc. For 10+ chunk docs (Q2's `Creating dialogs` at 20), a
  hybrid worked: range-fetch the first 5 for orientation, then
  search for terms-of-interest within the doc.

### Corpus-shape findings (carry forward to retrieval design)

1. **The indexed `title` is URL-path-derived, not frontmatter-derived.**
   This was the single biggest source of pain. Doc
   `a285a4a303da` has indexed title "New accessgroups"
   (URL-cased) but frontmatter title "Innføring av nye
   tilgangspakker som erstatning for dagens Altinn 2 roller".
   Norwegian title-searches consistently returned 0 hits in Q3,
   Q4, Q5 because the frontmatter Norwegian title isn't indexed.
   The frontmatter even has a `linktitle` field
   ("Nye tilgangspakker", "Personroller", "Virksomhetsroller") —
   exactly what users type — and *that* isn't indexed either.

   **Suggested fix**: index `linktitle` as a secondary searchable
   field alongside `title`, or replace the URL-derived `title`
   with the frontmatter one.

2. **`diataxis_*` frontmatter is a free signal that's not used for
   retrieval.** Every doc tags itself
   `diataxis_explanation` / `diataxis_how-to-guides` /
   `diataxis_reference` (the Diátaxis docs framework). This maps
   directly to question type:
   - Definitional Q → `diataxis_explanation`
   - Procedural Q → `diataxis_how-to-guides`
   - Reference lookup → `diataxis_reference`

   Promoting `diataxis` to an indexed/faceted field would let
   retrieval bias toward the right *kind* of doc for the
   question type — a query-classifier+facet move that's currently
   impossible because the field isn't indexed.

3. **NB and EN siblings have identical indexed `title`.** The
   `/en/` and `/nb/` variants of a doc share the same URL-derived
   title. Language disambiguation falls entirely on URL pattern
   reasoning, which doesn't work as `filter_by` because
   Typesense's filter is equality-only on strings. A `language`
   field on docs would close this gap.

4. **One doc had its English-titled URL containing Norwegian
   content.** The migration doc (Q4) is at `/en/` but its content
   is entirely Norwegian. Language-by-URL is unreliable in at
   least one case.

5. **Chunk boundaries can lose semantic structure.** The Altinn-
   roles docs (Q5) had a chunk that contained all role
   *descriptions* (`Beskrivelse: ... Kan delegeres: ja`) but
   missing the role *names* — the chunker decoupled list-item
   headers from their bodies. Any retrieval that surfaces this
   chunk surfaces an unverifiable answer.

6. **Two v8 reference docs use a version-callout pattern within
   chunk content** — embedding "**v7**" / "**v4, v5, v6**"
   sections in a v8 doc to document how a feature evolved.
   Version-specific answers aren't in version-specific docs;
   they're embedded in the canonical v8 doc. A URL-prefix filter
   on `/v7/` would miss them entirely.

7. **The corpus's webhook security model is unusual.** Altinn
   Events uses authenticated registration + TLS + IP whitelisting,
   *not* per-event signatures (Q7). This is a consistent
   architectural choice across three docs, not an oversight — but
   it'd be easy for a fluency-biased retrieval to fabricate a
   webhook-signature answer because the literal phrase appears
   21 times in the corpus.

### Retrieval-strategy patterns that emerged

| Pattern | When it worked | Cost |
|---|---|---|
| Title-search first | Q1, Q2 (English procedural with literal-term titles) | Cheap when the term IS in the indexed title |
| **Chunk-content fallback** | Q3, Q4, Q5 (NB queries) | Always necessary when title-search returns 0 |
| Range-fetch full doc | Q1 (5 chunks), Q4 (3 chunks), Q5 (NB roles docs 1-2 chunks each) | Cheap for small docs |
| In-doc filter+search | Q2 (`POST` within Creating-dialogs) | Useful for narrowing a 20-chunk doc |
| Multi-id `ts-get` | Q4 (parent + 2 leaves in parallel) | Excellent when chunk IDs already known |
| Confirm-then-stop | Q7 (verify HMAC chunks aren't about webhooks) | Essential for refusal discipline |

### Where the gap with automated retrieval will be largest

In order of expected severity:

1. **Refusal discipline (Q7, Q6).** The literal-query-matches-chunk
   pattern that fluency-biased synthesis exploits is exactly what
   produces confident wrong answers on these. A trail like "found
   21 webhook-signature chunks, read three full docs, confirmed
   no algorithm specified, called out the alternative security
   model" requires *not* doing the thing the retrieval signal
   invites.
2. **Cross-doc synthesis (Q3, Q5, Q7).** 5 of 7 answers required
   composing across ≥2 distinct docs. Single-doc retrieval would
   misanswer all of them.
3. **NB vocabulary handling (Q3, Q4, Q5).** Title-search on
   Norwegian terms consistently returned 0 hits. A retrieval
   system that doesn't fall back to chunk-content search on
   title-search empty results will silently fail.
4. **Honest scoping (Q6).** The right answer to a question whose
   premise isn't fully supported is partial refusal *with*
   what-the-corpus-does-support. Q6 specifically rewarded saying
   "the corpus is v8-only here" instead of inferring a v7-vs-v8
   comparison from migration notes.
5. **Pragmatic-vs-literal interpretation (Q2).** The Creating-
   dialogs doc's first info-box says "if you're using Altinn
   Studio, dialogs are created automatically — you may not need
   to do this." A retrieval focused on the literal "how do I
   create" wording would drop the most useful up-front fact for
   a real user.

### Where the gap will be smallest

- **Q1 (definitional)**: a perfectly-titled `About dialogporten` doc
  with frontmatter `diataxis: diataxis_explanation` is exactly
  what any retrieval system should find. The hard part is
  picking the right *source doc* from 9 "Dialogporten"-titled
  candidates, but BM25 ranking + URL-pattern reasoning gets close.
- **Q4 (temporal)**: once chunk-content fallback fires, the
  migration doc is one of two top hits. The temporal answer is
  literally in one chunk's table.

### Open questions for the actual sweep

1. **How do these trails compare to what the production retrieval
   skill produces?** This is the natural next step: replay each
   of the 7 questions through `ts-retrieve` (enable
   `RAG_TS_RETRIEVE_ENABLED`) and compare its hit list against my
   cited chunks per question. Where it picks differently, why?
2. **Should `linktitle` and `diataxis` be indexed?** Both would
   strictly improve retrieval on this corpus without changing
   anything about the retrieval algorithm.
3. **Is the chunker's behavior on Altinn-roles list-style content
   a known issue?** The role-names-stripped-from-descriptions
   pattern is severe enough that an automated answer would be
   *worse than nothing* (confident, plausible, unverifiable).
4. **The corpus has 21 webhook-signature chunks across 5+ docs
   and ZERO algorithm specifications.** Is this an intentional
   architectural stance that should be documented somewhere
   user-discoverable, or a documentation gap?

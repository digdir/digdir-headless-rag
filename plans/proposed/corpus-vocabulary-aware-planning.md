# Corpus-vocabulary-aware query planning

Followup from slice 18
(`plans/in-progress/target-optimal-baseline-v3/18-intent-aware-query-planner.md`).

## The gap

The intent-aware query-planner produces grammatically reasonable
canonical questions in the corpus language, but doesn't always
hit the corpus's compound-term linktitle vocabulary.

Examples observed during slice 18:

| User query | Hand-crafted phrase | LLM-generated user-intent |
|---|---|---|
| "Hva er forskjellen mellom Altinn-roller for personer og virksomheter?" | `Personroller, Virksomhetsroller` | `Forskjellen mellom Altinn-roller for personer og virksomheter` |
| "Does Altinn support webhook signatures using HMAC-SHA512?" | `EventSecretCodeProvider` | (typically `webhook signature HMAC SHA-512`) |

The hand-crafted phrases use **compound terms** (`Personroller`,
`Virksomhetsroller`, `EventSecretCodeProvider`) that match the
corpus's `linktitle` field exactly. The LLM produces
**decomposed** phrasings (`roller for personer`,
`webhook signature`) that miss the compound-term BM25 boost.

The slice-18 measurement (hand-crafted: 70%+ top-30; LLM:
~50-70%) shows this gap is real.

## Two plausible approaches

### (a) Seed the LLM with high-precision linktitle terms

At planning time, fetch the corpus's `linktitle` /
`frontmatter_title` indexed values. Pass a sampling (the
high-IDF ones) as "Domain vocabulary the corpus uses" in the
LLM prompt:

```
The corpus indexes the following high-precision terms (use them
verbatim in search phrases when relevant):
  Personroller, Virksomhetsroller, Altinn-roller,
  EventSecretCodeProvider, Tilgangspakker, ...

User query: {{query}}

Generate the user-intent and 4-6 search phrases. When the query
relates to one of the indexed terms above, prefer that compound
form.
```

Cost: one extra Typesense facet-query per planner invocation,
or pre-cached at startup. The vocabulary changes only on
re-ingest, so caching is cheap.

Risk: the LLM may over-apply the compound form to unrelated
queries (false positives). Mitigations: keep the user-intent
as a clean intent (not a vocabulary-stuffed phrase); only the
expansion phrases get the vocabulary nudge.

### (b) Post-LLM nearest-linktitle search

After the LLM generates `:user-intent`, run a separate query
against the linktitle index and add the top-1 or top-2 nearest
matches as expansion phrases. Treats the LLM as the
disambiguator and the corpus index as the vocabulary source.

Cost: one extra Typesense search per planner invocation.

Risk: linktitle BM25 isn't always sharp on user-question shape
(this is the original v3 finding — the user's natural-language
query doesn't directly hit linktitle). The user-intent IS
sharper (it's already cleaned), so BM25 against the user-intent
should work better than BM25 against the raw query. But not
guaranteed.

## Recommendation when picked up

(a) first, because it's a prompt-only change (no extra Typesense
calls per request after the first), and because the LLM's
contextual judgment about WHICH vocabulary terms apply is
generally better than BM25's lexical match.

(b) as an additive fallback for cases where the LLM's
suggestions don't include any compound terms.

## Measurement plan

Same setup as slice 18 (`bb v3-score --rerank-with-colbert true
--expand-queries 5`):

- V0 + ColBERT baseline: 26.1% / 26.1%
- Slice-18 intent-aware planner: ~50-70% / ~50-70%
- **+ vocabulary-aware planner**: target 65-75% / 70-80%
- Hand-crafted ceiling: 69.6% / 73.9%

The hand-crafted phrases ARE compound terms by construction.
A vocabulary-aware LLM planner should approach the ceiling more
reliably than the slice-18 planner does.

## Why this hasn't shipped yet

Slice 18 closed when LLM-driven planning matched (or got close
to) the hand-crafted upper bound with much less variance from
slice 17. The remaining 5-10pp gap is small enough that other
levers (more retrieval candidate sources, smarter rerank
training data) may pay back more per engineering hour.

This note exists so the option is documented for the next
iteration without losing the design insight.

## Status — tabled at slice 21 for a separate arc

Slice 21 (`plans/in-progress/target-optimal-baseline-v3/
21-doc-level-scoring-and-intent-first-pass.md`) confirmed that
Q2, Q3, and Q7's remaining 0-of-N misses are precisely the
vocabulary-blindness cases captured in this plan. They are NOT
solvable by the current planner's general-purpose phrasing
expansion — at chunk OR doc level.

The user has explicitly tabled this work for **a separate
followup arc focused on interactive search-phrase generation
using purpose-built skill graphs**. The intuition: instead of a
single-shot LLM phrasing pass, the agent itself searches the
corpus's linktitle/frontmatter-title space, learns the
compound-term vocabulary that appears around the user's intent,
and emits search phrases that align with that vocabulary.

This is more flexible than option (a) (static linktitle seeding)
because the agent can adapt vocabulary discovery per question
without a corpus-wide curation step. It's a generalization of
option (b) (post-LLM nearest-linktitle search) into a multi-step
agentic loop.

When that arc kicks off, this plan becomes its design context.

# Query expansion — hand-crafted upper bound

The slice-4 ColBERT measurement landed precision-at-top wins
(+13pp at top-10) but left recall flat — Q2/Q3/Q5/Q6 stayed
0-of-N because their cited chunks didn't reach the candidate
set. The hypothesis from slice-6: better query expansion would
provide more sub-queries into the merge stage, giving ColBERT
more candidates to rerank.

This document captures the hand-crafted upper-bound measurement
— pass curated expansion phrases (the same shape the v3 baseline
trail used manually) and see what recall ceiling exists.

## Method

For each v3 question, hand-crafted 3 expansion phrases mirroring
the curated probes from the v3 baseline trail. These are the
linktitle/frontmatter_title terms a human operator would type
after reading the corpus shape:

| Q | Question | Hand-crafted expansion phrases |
|---|---|---|
| 01 | What is Dialogporten and what problem does it solve? | `Dialogporten,About Dialogporten,what is Dialogporten` |
| 02 | How do I create a new dialog as a service owner in Dialogporten? | `Creating dialogs,create dialog service owner,POST dialog API` |
| 03 | Hvordan setter jeg opp autentisering for en Altinn-app i utviklingsmiljøet? | `Konfigurasjon av autentisering,Lokal utvikling Altinn,OIDC provider Altinn app` |
| 04 | Når går Altinn over fra Altinn-roller til Tilgangspakker… | `Nye tilgangspakker,Altinn 2 roller avvikles,tilgangspakker timeline` |
| 05 | Hva er forskjellen mellom Altinn-roller for personer og virksomheter? | `Personroller,Virksomhetsroller,Altinn-roller forskjell` |
| 06 | Compare how Altinn Studio v7 and v8 handle data model definition… | `Migrating from v7,data model v8,Altinn 2 datamodel` |
| 07 | Does Altinn support webhook signatures using HMAC-SHA512? | `webhook signature HMAC,event subscription secret,EventSecretCodeProvider` |

The expansion phrases were passed comma-separated via the
debug endpoint's existing `:queries` parameter (which already
supports multi-query relaxation as comma-separated text).
ColBERT rerank enabled, default rerank-candidate-k=40.

## Results — by far the biggest measured lift

| Q | V0+ColBERT (literal) | + Hand expansion (top-10) | + Hand expansion (top-30) |
|---|:-:|:-:|:-:|
| Q1 | 2/3 | 2/3 | 3/3 |
| Q2 | **0/2** | **2/2** | 2/2 |
| Q3 | **0/2** | **1/2** | 0/2 |
| Q4 | 3/3 | 3/3 | 3/3 |
| Q5 | **0/5** | **5/5** | 5/5 |
| Q6 | **0/3** | **1/3** | 1/3 |
| Q7 | 1/5 | **2/5** | 3/5 |
| **Total @ top-10** | **6/23 (26.1%)** | **16/23 (69.6%)** | — |
| **Total @ top-30** | 6/23 (26.1%) | — | **17/23 (73.9%)** |

**Δ vs V0+ColBERT: +43pp at top-10, +48pp at top-30.**

Per-question highlights:
- **Q5 jumps 0/5 → 5/5**, all five role-description chunks
  surface at top-8. The "Personroller / Virksomhetsroller /
  Altinn-roller forskjell" expansion is exactly the linktitle
  vocabulary the indexed fields were designed to match.
- **Q2 0/2 → 2/2** — "Creating dialogs" as a single curated
  phrase matches the doc's linktitle/frontmatter_title; the
  natural-language question "How do I create a new dialog as a
  service owner..." doesn't.
- **Q7 1/5 → 2/5** — the new "EventSecretCodeProvider" expansion
  surfaces `9b4017645a43` (the chunk slice-3 V2's LLM-classify
  was supposed to surface but didn't). At top-10 it's at **#1**.

What didn't improve (still 0-of-N or low):
- Q3's "Lokal utvikling Altinn" expansion only got 1/2 — the
  reference doc `fefc9064b271` (Autentisering) surfaces but the
  how-to doc `e465a3646506` (Lokal utvikling) doesn't.
- Q6's data-model-migration question still mostly misses — the
  corpus content gap on v7-side data-model docs hasn't changed.
- Q7's full 5/5 isn't achievable; some of the cited chunks
  (`6fcb16e12b0e`, `c5c607199c7e`) are at lower positions and
  drop out of top-10.

## What this proves

**Recall on this corpus is bottlenecked by query-form
mismatch.** The cited chunks ARE retrievable; the user's
natural-language question form doesn't lexically match the
linktitle/frontmatter_title or content_markdown fields.
Curated expansion phrases that mirror the indexed-field
vocabulary close the gap dramatically.

ColBERT's precision-at-top win (slice 4) + query expansion's
recall win (this measurement) compose: ColBERT puts the right
chunks at top positions IF they reach the candidate set; query
expansion gets them into the candidate set.

The combined effect on top-10 hit rate against v3 cites:
- Slice 0 (initial gap analysis): 22% top-30 / unknown top-10
- Slice 4 ColBERT: 26.1% top-30 / 26.1% top-10
- **Slice 6 hand-expansion + ColBERT: 73.9% top-30 / 69.6% top-10**

That's a **2.7× improvement** at top-10 from the slice-1
baseline, and **>3× from the initial v3 gap**.

## What this DOESN'T prove

This is a **hand-crafted upper bound**. The expansion phrases
were authored knowing the corpus's vocabulary. An LLM-driven
query planner that has only the user's question (no corpus
context) may or may not generate phrases of similar quality.

The next experiment is the LLM-driven version. Three scenarios
to anticipate:

1. **LLM matches hand-crafted** → query-expansion is a clean
   shippable win. Default-on for digdir/public-docs would shift
   top-30 hit rate to ~70%.
2. **LLM ≈ V0+ColBERT** → the LLM generates over-generic
   reformulations that don't match the corpus's specific
   linktitle vocabulary. Need corpus-aware expansion (e.g. seed
   the LLM with known linktitle terms, or use embeddings to find
   nearest linktitle to the query).
3. **LLM somewhere in between** → tells us the LLM captures some
   but not all of the human curation. Quantifies the gap.

## Implementation: what query expansion looks like in production

The query-planner skill already exists at
`server/src/digdir/skills/builtin/query_planner.clj`. It
generates search phrases from a user question via LLM. It's used
in the agent loop today but not exposed for the debug retrieval
path that `bb v3-score` and `bb ts-retrieve` use.

Two paths to production-quality query expansion:

### (a) Expose query-planner as a debug endpoint

Add `/api/debug/query-planner?query=...&max-phrases=N` that
runs the skill and returns the generated phrases as JSON.
`bb v3-score --expand-queries N` calls it per question, then
passes the phrases comma-separated to typesense-retrieve.

Clean separation: ad-hoc measurement vs production agent path
can share the same query-planner skill.

### (b) Direct integration

Have the retrieval skill's debug endpoint call query-planner
internally when a new `:expand-queries N` parameter is set.
Bigger change; conflates expansion + retrieval.

Recommend (a) — smaller, parallel to the existing slice-1
plumbing pattern.

## Decision points

1. **Build the query-planner debug endpoint** as the next
   experiment? Predicted outcome: between V0+ColBERT (26%) and
   hand-crafted upper bound (70%), depending on prompt quality.

2. **If LLM ≈ hand-crafted**, **enable query-planner by default
   for the production agent path on digdir/public-docs?** The
   agent loop uses it conditionally today (`skills.query-planner.enabled`).
   Default-on the dataset config.

3. **Reframe the v3 baseline?** With hand-crafted expansion at
   70%+, "what's the realistic upper bound" changes. The
   remaining 26-30% misses are now genuinely structural (corpus
   content gaps, chunker artifacts, comparative question shapes
   the LLM probably can't fix either).

# ts-retrieve gap analysis vs v3 cited chunks

`bb ts-retrieve` issued with the literal question text per
v3 question, default flags, `--retrieve-top-k 30`. For Q5 and
Q7 also re-ran with `--rerank-with-colbert true` to test
whether ColBERT closes the gap.

Per-question I report: which v3 cited chunks appear in
ts-retrieve's top-30, at what rank, by which strategy, and
which chunks were missed.

## Per-question hit/miss

### Q1 — Dialogporten definition

**v3 cites**: `ea7de904e1aa` (#0), `a233d1c22ebe` (#1),
`b8ddca7bace0` (#2) — all from `9a5fc194a710` "About dialogporten"

**ts-retrieve top-30**:
- `a233d1c22ebe` at **#9** (`:metadata`)
- `ea7de904e1aa` at **#10** (`:phrase`)
- `b8ddca7bace0` — **missing**

**Hit rate: 2/3.** Ranks 9 and 10, behind 8 `:content` hits
from other Dialogporten-adjacent docs (`9f8e5e3a4868` landing
page, `642d681288fb`, etc.). The phrase strategy did surface
the canonical chunk 0 — the cleaner phrases helped here.

### Q2 — Create a dialog as service owner (EN)

**v3 cites**: `c4e1cd6328b8` (#0), `378b160a17a5` (#1) — from
`5f614bcedc12` "Creating dialogs"

**ts-retrieve top-30**: doc `5f614bcedc12` **does not appear at
all**. Top hit is `9a5fc194a710#3` (About-dialogporten chunk 3,
:phrase :metadata).

**Hit rate: 0/2.** The doc whose v2/v3 optimal probe was a
single `diataxis:=how-to-guides && language:=en` filter is
entirely missed by ts-retrieve's default strategies. The
`:metadata` strategy does not apply diataxis or language filters
the way the optimal human trail did.

### Q3 — Altinn-app auth in dev (NB)

**v3 cites**: `89698907c227` (auth config doc `fefc9064b271`),
`a3a3d87967da` (local-dev doc `e465a3646506`)

**ts-retrieve top-30**: neither `fefc9064b271` nor
`e465a3646506` appears.

**Hit rate: 0/2.** Both docs were trivially findable via
linktitle/frontmatter_title with diataxis filters; ts-retrieve
returns mostly Dialogporten/authorization docs by content match.

### Q4 — Tilgangspakker migration timeline (NB)

**v3 cites**: `bdd5427ec8d5` (#0), `688781d672e2` (#1) from NB
migration doc `60ea897f58fd`; `8e71d9016de9` (#2) from EN
sibling `a285a4a303da`

**ts-retrieve top-30**:
- `8e71d9016de9` at **#11** (`:content`) ✓
- NB migration doc `60ea897f58fd` — **missing entirely**

**Hit rate: 1/3.** The EN sibling's Q&A chunk surfaces by
content match; the NB-primary doc with the actual timeline
(answering the *when* part of the question) is missed.

### Q5 — Personroller vs Virksomhetsroller — **PREDICTED GAIN**

**v3 cites**: `75c3ec105b6e` (parent), `36b260f5d51f`,
`df90a3ca5a45` (persons), `66fb43dab201`, `a136030a86f8`
(enterprises) — across 3 docs

**ts-retrieve top-30**: **none of the 3 cited docs appear.**

With `--rerank-with-colbert true`: also 0/5 (rerank reorders
the same set; doesn't fetch new chunks).

**Hit rate: 0/5.** The predicted phrase-corpus win **did not
materialize**. The phrases collection has e.g.
`"helse-, sosial- og velferdsrelaterte tjenester"` attached to
`a136030a86f8`, verified by direct probe in v3. But
ts-retrieve's `:phrase` strategy on the natural-language query
"Hva er forskjellen mellom Altinn-roller for personer og
virksomheter?" doesn't return that phrase. Two possible causes:

1. **Phrase tokenization / scoring**: "personer og
   virksomheter" doesn't match phrase "personroller" /
   "virksomhetsroller" with enough BM25 weight to make the
   top-30 cut.
2. **Query relaxation**: if the `prompt-query-relax` step
   rewrites the question, the rewrites may not preserve the
   compound NB role terms.

### Q6 — Altinn Studio v7 vs v8 data model

**v3 cites**: `c7c8705b5968` (migration), `82a143cfd981` (v8
concept), `d871d2d193b8` (Altinn-2-datamodel)

**ts-retrieve top-30**:
- `109cbc33aa68` at #12 — chunk 2 of `837753b0cd68` (the
  Altinn-2-datamodel doc, same v3 cite under a different
  chunk_id since v3 cited chunk 0). One cite from the same
  doc.
- `c7c8705b5968` — missing
- `82a143cfd981` — missing

**Hit rate: 1/3 (by doc).** The right migration-from-v7 doc and
the v8 data-model concept doc are both missing. The
Altinn-2-datamodel doc — which v3 explicitly called out as a
potential trap for retrieval ("could be mistaken for a
v7-related doc") — is the only one present.

### Q7 — Webhook HMAC-SHA512 — **PREDICTED GAIN**

**v3 cites**: `6fcb16e12b0e`, `4aa2b0740446`, `c5c607199c7e`
(setup-subscription doc), `09eeadadc174`, `9b4017645a43`
(NEW v3 webhook-secret cites from `af9c4ce14a2b`)

**ts-retrieve top-30**:
- `4aa2b0740446` at **#3** (`:content`) ✓
- `6fcb16e12b0e`, `c5c607199c7e` — missing (setup-subscription
  chunks 0 and 2)
- `09eeadadc174` — **missing** (the IEventSecretCodeProvider chunk)
- `9b4017645a43` — **missing** (the chunk whose phrase is
  "webhook secret defined")

With `--rerank-with-colbert true`: same 1/5 hit rate; rerank
puts `4aa2b0740446` at #1 but doesn't fetch the missing
webhook-secret chunks.

**Hit rate: 1/5.** The headline predicted win **did not
materialize**. The phrase `"webhook secret defined"` exists
in the phrases collection (verified by direct probe in v3),
but on the literal question "Does Altinn support webhook
signatures using HMAC-SHA512?", ts-retrieve's :phrase strategy
doesn't return chunk `9b4017645a43`. The lexical gap between
"webhook signature" (query) and "webhook secret defined"
(phrase) is too wide for the current scoring.

## Summary

| Q | v3 cites | retrieve hits | rate | Predicted? |
|---:|---:|---:|---|---|
| 1 | 3 | 2 (#9, #10) | 67% | — |
| 2 | 2 | 0 | 0% | — |
| 3 | 2 | 0 | 0% | — |
| 4 | 3 | 1 (#11) | 33% | — |
| 5 | 5 | 0 | 0% | **MISS** |
| 6 | 3 | 1 | 33% | — |
| 7 | 5 | 1 (#3) | 20% | **MISS** |
| **Total** | **23** | **5** | **22%** | |

## The gap is large — and the headlines are the misses

This is the **opposite** of the v3 README's prediction. The two
phrase-corpus wins (Q5 role-name recovery, Q7 webhook-secret
discovery) that v3 identified as the cleanest tests of
multi-strategy retrieval **both failed**. The phrase corpus
is healthy and contains the right entries (verified by direct
`ts-search phrases` probes during the v3 baseline), but
`ts-retrieve` doesn't surface them for natural-language queries.

### Why the predicted wins didn't materialize

**Q5**: phrase `"Personroller"` IS attached to chunk
`36b260f5d51f`, but the user's NB question contains "personer
og virksomheter" (decomposed), not "personroller" (compound).
The phrase strategy is doing literal phrase BM25, not semantic
matching.

**Q7**: phrase `"webhook secret defined"` is attached to chunk
`9b4017645a43`, but the user's question contains "webhook
signatures using HMAC-SHA512". The lexical overlap is "webhook"
alone; the phrase strategy needs more shared tokens to score
above the threshold.

In both cases, the optimal-trail probe used a curated phrase
that matched the corpus entry; ts-retrieve uses the user's raw
query. **The phrase corpus's payoff is bottlenecked by
phrase-strategy query construction, not by phrase-corpus
quality.**

### Where ts-retrieve DOES partially work

- Q1: phrase strategy surfaces the canonical chunk 0 of
  About-dialogporten at #10. The query "What is X" lexically
  matches phrases like "Dialogporten is a solution that
  serves as".
- Q4: content strategy surfaces the EN Q&A chunk (`8e71d9016de9`)
  at #11. This is the chunk with the highest lexical overlap
  with "endringene for tjenesteeiere".
- Q7: content strategy surfaces setup-subscription chunk 1
  (`4aa2b0740446`) at #3. This is the chunk that *does*
  contain "webhook" prominently.

### What's missing in ts-retrieve vs the optimal trail

The optimal v3 trail's cheapest precise probe was:

> `query-by linktitle,frontmatter_title` +
> `filter-by diataxis:=<type> && language:=<L>`

This single move closed Q2, Q3 (partial), Q4, Q5. **None of
that filter shape appears to be applied by ts-retrieve's
`:metadata` strategy**, judging by what it retrieves. The
fields are indexed; the strategy is leaving them on the table
for natural-language queries.

A retrieval that:
1. Classified the query as definitional / procedural / reference /
   refusal,
2. Inferred its language,
3. Then applied `diataxis:=<inferred> && language:=<inferred>`
   to a `linktitle,frontmatter_title`-targeted search,

...would close most of the gap on Q2/Q3/Q4/Q5/Q6. The fields
were added in v2 specifically for this; the phrase-corpus fix
in v3 doesn't address it because it's a different lever.

## What the cleaner phrases actually changed (revised)

Honest accounting compared to my v3 README claims:

- **v3 vs v2 (optimal trail)**: cleaner phrases enabled
  discovering one new doc on Q7 by *direct phrase probe*.
  This is still real but only matters if something queries
  the phrases collection the way the v3 trail did.
- **v3 vs v2 (ts-retrieve)**: no measurable change. The phrase
  corpus was already populated (just bloated with duplicates
  and partial failures); ts-retrieve's :phrase strategy's
  hit-set looks similar in shape. The duplicates would have
  shown up as repeated hit-counts, not as different documents.
- **The predicted wins did not transfer to production retrieval.**
  Reproducing the optimal trail's win shape on Q5 and Q7
  requires a different query construction than ts-retrieve
  uses today.

## Implications

1. **The phrase corpus alone isn't enough.** Cleaning it was
   necessary (the bloat from v2 made every collection-level
   metric noisy) but not sufficient. Multi-strategy retrieval
   has to construct queries that match the phrase corpus's
   shape.
2. **The biggest near-term win is wiring the v2 indexed fields
   into ts-retrieve.** linktitle / frontmatter_title / diataxis
   / language are present in the schema but apparently not in
   the :metadata strategy. Adding a query-type-classification
   step and using those fields would have closed Q2, Q3, Q4,
   Q5, Q6 in the optimal trail.
3. **ColBERT rerank can't compensate for a missing candidate.**
   The Q5 and Q7 reranked outputs reorder the same 30 chunks;
   they don't fetch new ones. The retrieval-set construction
   is where the gap lives.
4. **The corrected significance rating**: the v3 baseline
   change vs v2 is about a **3 out of 10**, not the 4–6 I
   suggested when extrapolating to ts-retrieve. The hypothesis
   that cleaner phrases would deliver measurable retrieval
   gains on these questions is **falsified** by this gap
   analysis. The fix is upstream of the phrase corpus — in how
   queries are constructed for the strategies that consume it.

## Suggested next steps

1. **Inspect the `:metadata` strategy's query construction.**
   If it doesn't filter by diataxis/language or query
   linktitle/frontmatter_title, wire that up.
2. **Test a curated-query variant of ts-retrieve.** Add a
   `--queries` form that uses the same compound-NB terms
   the optimal trail used (e.g.
   `"Personroller,Virksomhetsroller,Altinn-roller"`). If that
   surfaces the v3 cites, the gap is purely query-construction.
3. **Re-run after either fix.** Measure the same 23 cited
   chunks against the new top-30s. The expected change if the
   metadata-strategy hypothesis is right: hit rate jumps from
   22% to ~70%+ on the structurally-findable cites (Q2/Q3/Q4/
   Q5).

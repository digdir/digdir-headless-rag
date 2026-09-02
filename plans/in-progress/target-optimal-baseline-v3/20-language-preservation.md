# Slice 20 — strengthened language preservation in planner

Direct follow-up to slice 19's Q1 regression finding. Slice 19's
analysis showed the planner was translating Q1's English query
into Norwegian, causing the EN-cited About-Dialogporten chunks
to drop out of top-K. This slice removes the conflicting
`same-language-rule` prompt fragment and replaces it with an
explicit LANGUAGE rule baked into the intent-extraction step.

## What changed

In `server/src/digdir/skills/builtin/query_planner.clj`'s
`build-default-prompt`:

1. Removed the reference to `prompt-fragments/same-language-rule`
   from the phrase-generation rules (and removed the unused
   `prompt-fragments` require).

2. Replaced "Keep the user-intent in the user's original
   language — do NOT translate." with a stronger LANGUAGE rule:

> LANGUAGE: Use EXACTLY the same language as the user's message.
> If the user wrote in English, the user-intent MUST be in English.
> If Norwegian, Norwegian. Do NOT translate between languages even
> if the corpus has docs in a different language — the retriever
> searches the same language as the query, so translation moves
> the search away from same-language matches.

3. Added a reminder to the phrase-generation step: "Every phrase
   MUST be in the same language as the user's message (see
   LANGUAGE rule above)."

The two changes together give the LLM one strong, non-conflicting
language-preservation rule. The slice-19 problem was that
`same-language-rule` and the older "do not translate" instruction
were competing for attention; consolidating into one explicit rule
removes the ambiguity.

## Verification — translation IS now suppressed

Direct curl probe for the Q1 EN query:

```
:user-intent "What is Dialogporten and what problem does it solve?"
:queries     ["What is Dialogporten and what problem does it solve?"
              "What is Dialogporten"
              "Dialogporten problem it solves"
              "Dialogporten solves what problem"
              "what problem does Dialogporten solve"]
```

All phrases in English. Compare to slice 19 (translated):
`"Hva er Dialogporten og hva løser det?"` and four other NB phrases.

Q5 (NB query) confirms the rule works bidirectionally:
```
:user-intent "forskjellen mellom Altinn-roller for personer og virksomheter"
```
Still Norwegian. ✓

## Measurement results

| Config | Top-10 | Top-30 |
|---|---:|---:|
| V0 + ColBERT (baseline) | 26.1% | 26.1% |
| Slice 19 (translated) | 30.4% | 39.1% |
| **Slice 20 (language preserved)** | **34.8%** | **43.5%** |
| Slice 17 best (lucky variance) | 56.5% | 69.6% |
| Hand-crafted ceiling | 69.6% | 73.9% |

**+8.7pp top-10, +17.4pp top-30 vs V0+ColBERT.** The biggest
deterministic configuration shipped so far.

### Per-question (top-10 / top-30)

| Q | Slice 4 ColBERT | Slice 19 (translated) | **Slice 20** |
|---|---|---|---|
| Q1 | 2/3 / 2/3 | 0/3 / 0/3 | **0/3 / 0/3** |
| Q2 | 0/2 / 0/2 | 0/2 / 0/2 | 0/2 / 0/2 |
| Q3 | 0/2 / 0/2 | 0/2 / 0/2 | 0/2 / 0/2 |
| Q4 | 3/3 / 3/3 | 3/3 / 3/3 | 3/3 / 3/3 |
| Q5 | 0/5 / 0/5 | 2/5 / 4/5 | **3/5 / 5/5** ← perfect recall |
| Q6 | 0/3 / 0/3 | 1/3 / 1/3 | **1/3 / 1/3** |
| Q7 | 1/5 / 1/5 | 1/5 / 1/5 | 1/5 / 1/5 |
| **Total** | 6 / 6 | 7 / 9 | **8 / 10** |

The wins:
- **Q5 lands 5/5 at top-30** — matching the hand-crafted ceiling
  for that question. The corpus-aligned NB phrases now reliably
  surface the role-description chunks.
- **+1 chunk vs slice 19 at top-10** and **+1 chunk at top-30**.

## The surprising Q1 finding

**Q1 still 0/3 even though translation was fixed.** Expected
slice 20 to recover the slice-4 result (2/3 at #2/#3) because the
user-intent is now back in English. Instead Q1 stays 0/3.

Investigating the top-10 for Q1 with `expand-queries=3`:

```
#5  chunk=d67af305d6ff  doc=9a5fc194a710 ← About-Dialogporten chunk
#6  chunk=20391f3e6ac4  doc=9a5fc194a710 ← About-Dialogporten chunk
#7  chunk=f3899e00b3c2  doc=c128b3970ecb
...
```

The **About-Dialogporten doc is being surfaced** (`9a5fc194a710`
chunks 3 and another show up at #5 and #6), but the **v3-cited
chunks 0, 1, 2** (`ea7de904e1aa`, `a233d1c22ebe`, `b8ddca7bace0`)
are not in the top-10 of the expanded-query result.

The mechanism: with 3 sub-queries, the chunks-by-content strategy
returns ~30 hits per sub-query (90 candidates after dedup). ColBERT
reranks more candidates. Different candidates from the broader pool
win — including OTHER About-Dialogporten chunks (3, 4) at the
expense of the v3-cited chunks 0, 1, 2.

**This isn't a translation problem — it's a pool-composition
problem.** Query expansion that helps Q5 dramatically (+5) hurts
Q1's specific ColBERT-rescued chunks.

In other words: ColBERT alone (slice 4) rescued Q1's cited chunks
out of a narrower candidate pool. Adding expansion phrases broadens
the pool, and ColBERT now finds OTHER good candidates that rank
higher than the v3 cites — same doc, different chunks.

## Implications

The v3 baseline measures recall of *specific cited chunk IDs*. Slice
20's Q1 "miss" surfaces the right DOCUMENT (`9a5fc194a710`) just
at different chunks (3, 4, others) instead of the v3-trail's chosen
chunks (0, 1, 2). From a user's perspective, the answer is *there*
— just not at the specific chunks the v3 trail manually picked.

Two readings:

1. **The v3 measurement is over-strict on per-chunk recall.** If
   we relaxed scoring to "did we surface ANY chunk from the cited
   doc?", Q1 would score positively (the About-Dialogporten doc IS
   in top-10). Same probably true for several other "0" rows.

2. **Even by strict-chunk-id scoring, slice 20 is net positive.**
   8/23 top-10 hits is +2 over V0+ColBERT (6/23). The Q1 regression
   is masked by Q5 and Q6 gains.

A doc-level recall metric would tell us where slice 20 actually
sits relative to the human upper bound. That's a small additional
piece of v3-scoring infra worth adding.

## What this slice ships

- One-line change in the prompt (LANGUAGE rule), plus removal of
  the `prompt-fragments/same-language-rule` reference.
- Existing 13 query-planner unit tests continue to pass.
- New deterministic top-10/top-30 baseline: **34.8% / 43.5%**.

## Followups identified

1. **Add a doc-level v3 scoring metric.** Strict-chunk-id recall
   under-reports actual answer-availability when expansion shifts
   to sibling chunks of the same source doc. Quick add to
   `digdir.sweep.v3-cites`: load both chunk-IDs and their parent
   doc-nums, score on both.

2. **Q1 pool-composition fix.** Three options:
   - Boost user-intent's retrieval results in merge (weight 1.5
     on the user-intent sub-query, 1.0 on expansions)
   - Run user-intent as a SEPARATE first-pass retrieval, then
     UNION with expansion-multi-query results
   - Lower `:doc-title-chunk-fanout` or `:max-per-document` to
     prevent any single doc from grabbing too many top-K slots
     before ColBERT picks the v3-style preferred chunks

3. **Q2, Q3, Q7 still 0-of-N.** The planner's expansion phrases
   for these don't trigger the corpus's compound-term linktitles
   (captured in `plans/proposed/corpus-vocabulary-aware-planning.md`).
   Try LLM seeding with high-precision linktitle terms.

## Cumulative v3 picture (best deterministic configurations)

| Stage | Top-10 | Top-30 |
|---|---:|---:|
| Initial v3 baseline | ~13% | 22% |
| + Slice 1 doc-title strategy | ~13% | 26.1% |
| + Slice 4 ColBERT | 26.1% | 26.1% |
| + Slice 19 intent-aware planner (translated) | 30.4% | 39.1% |
| **+ Slice 20 language preservation** | **34.8%** | **43.5%** |
| Slice 17 best run (lucky variance) | 56.5% | 69.6% |
| Hand-crafted ceiling | 69.6% | 73.9% |

The cumulative deterministic gain since V0 is now:
- **Top-10: 13% → 34.8% = 2.7× improvement**
- **Top-30: 22% → 43.5% = 2.0× improvement**

The gap to the ceiling is ~30pp at both top-10 and top-30 — a
combination of the Q1 pool-composition issue and the
Q2/Q3/Q6/Q7 corpus-vocabulary blindness. Both are tractable
next steps.

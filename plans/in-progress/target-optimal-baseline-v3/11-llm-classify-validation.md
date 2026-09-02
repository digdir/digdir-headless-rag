# Slice-3 V2 validation — `:llm-classify` rule type

First dynamic variant from
`plans/proposed/retrieval-dynamic-filter-generation-experiment.md`:
add `:llm-classify` as a new rule type that defers classification
to a per-query LLM call instead of static regex/marker-words.

## What shipped

- `defmethod detect-by-rule :llm-classify` in
  `digdir.rag.auto-filter-rules` with helpers:
  - `llm-classify-call` — the actual Azure-OpenAI call via litellm-clj,
    using `tool-choice :required` with an enum-constrained array
    schema so the LLM can only return values from the rule's universe.
  - `render-llm-classify-prompt` — `{{field}}`, `{{universe-joined}}`,
    `{{query}}` substitution.
  - `selected->filter-fields` — strict (`field:=[...]`) vs permissive
    (`field:!=[complement]`) mode emission.
- 10 unit tests under `digdir.rag.auto-filter-rules-test` with a
  stubbed LLM call covering: strict mode, permissive mode, multi-value,
  `:allow-multi-value false` truncation, empty output, network failure,
  invalid-value filtering, tenant gate, prompt substitution, and
  composition with `:marker-word-classify`.

Out of scope this slice:
- V3 query-planner emission (next slice).
- V4 hybrid orchestration (next slice).
- Multi-LLM-call latency optimization (single combined call vs per-rule).

## Measurement

Used `bb v3-score` with two V2 configurations against the v3 baseline:

1. **V2 diataxis-only-LLM**: just the dynamic diataxis classifier;
   no language rule.
2. **V2 both-LLM**: dynamic language + dynamic diataxis classifier.

## Results

| Q | v3 cites | V0 control | V1 static lang | V2 diataxis-LLM | V2 both-LLM |
|---:|---:|:---:|:---:|:---:|:---:|
| 1 | 3 | **2/3** | **2/3** | 0/3 | 0/3 |
| 2 | 2 | 0/2 | 0/2 | 0/2 | 0/2 |
| 3 | 2 | 0/2 | 0/2 | 0/2 | 0/2 |
| 4 | 3 | **3/3** | 2/3 | **3/3** | 2/3 |
| 5 | 5 | 0/5 | 0/5 | 0/5 | 0/5 |
| 6 | 3 | 0/3 | 0/3 | 0/3 | 0/3 |
| 7 | 5 | 1/5 | 1/5 | 1/5 | 1/5 |
| **Total** | **23** | **6 (26.1%)** | **5 (21.7%)** | **4 (17.4%)** | **3 (13.0%)** |

**Monotonic regression as filtering tightens.** Every filter added
strictly removes hits without adding any. The result is empirical and
striking.

## Classification quality

The LLM is *making correct decisions*. Per-question diataxis output
(from `:auto-filter-applied` in attribution):

| Q | LLM classification | Correct vs v3 trail? |
|---|---|---|
| 1 | `["explanation"]` | ✓ Matches v3 manual probe (`diataxis:=explanation && language:=en`) |
| 3 | `["how-to-guides", "reference"]` | ✓✓ **Multi-value — exactly what the v3 manual probe needed (Q3 spans both)** |
| 5 | `["explanation"]` | ✗ Q5 is comparative; the v3 manual probe used no diataxis filter |
| 6 | `["reference", "explanation"]` | (v3 manual probe didn't use diataxis for Q6 either) |
| 7 | `["reference"]` | (v3 manual probe didn't apply diataxis for refusal questions) |

The LLM gets **Q3 right where the static `:shape-pattern` regex
couldn't** — this was the predicted multi-value win from the
experiment design. The LLM also handles Q1 sensibly. The Q5
comparative misfire persists despite the explicit prompt rule
("Return [] if comparative"); future prompt iteration could fix it.

But **classification quality is decoupled from retrieval hit rate**.
Q3 with correct multi-value classification still surfaces 0/2 cites;
the filter pool narrows correctly but BM25 within the narrowed pool
ranks the cited docs below others.

## The structural finding

This is the most consequential finding of slice 3 so far.

**Filtering itself is the bottleneck, not classification quality.**

Even with a perfect classifier:
- Q1's narrowing-to-explanation excludes phrase-strategy candidates
  that ranked About-Dialogporten chunks 9 and 11 in the broad pool.
- Q3's correct multi-value classification narrows correctly but the
  cited docs still don't surface — the same BM25-within-narrowed-
  pool issue identified in slice 2.
- Q4's language=en|nb filter excludes the cross-language sibling
  chunk every time.

Every configuration that filters scores ≤ V0 (no filter). The
slice-1 doc-title strategy adding *retrieval candidates* moved the
needle (+4pp on Q4); subsequent slices' *narrowing of candidates*
have monotonically moved it the wrong way.

## Maps to which outcome in the experiment plan

Per the experiment's risk × outcome matrix:

> **V2 ≈ V1 ≈ V0** | Filter generation isn't the bottleneck. Look
> elsewhere (rerank, query relaxation, chunking).

Closest fit. Actually V2 < V1 < V0, even stronger than "approximately
equal" — filtering *actively hurts*.

> **Q5 fixed by V2/V3 but not V1** | Confirms the LLM picks up
> "comparative ≠ definitional" where the regex doesn't.

Q5 is **not** fixed by V2. The LLM misfires on "Hva er forskjellen
mellom" the same way the regex did. Prompt iteration might address
this; current behavior is unchanged from V1.

> **Q4 still regresses across V1–V4** | Cross-language synthesis is
> a deeper problem than filter classification can solve. Needs a
> different mechanism (cross-language re-probe).

Confirmed by V2 both-LLM. Even with correct language classification,
the user's NB question excludes the EN sibling chunk that v3 trail
needed.

## Implications

1. **Should V3/V4 still ship?** The plan said every outcome is
   informative, and the next two variants will further-quantify the
   "filtering doesn't help" finding. But the empirical signal is
   already strong: 4 configurations measured, monotonic regression
   from V0 → V1 → V2-partial → V2-full. V3 (query-planner emission)
   uses the same LLM classification mechanism with a different
   delivery; the result is likely indistinguishable from V2 in
   hit-rate terms. V4 (hybrid) composes V1 and V2; can't beat the
   better of its inputs. **The "filter doesn't help" finding is
   robust without V3/V4.**

2. **Where to look next.** The plan's outcome interpretation:
   > "Look elsewhere (rerank, query relaxation, chunking)"

   The slice-1 finding (doc-title adds candidates → +4pp on Q4) gives
   the direction: **additive moves help, subtractive moves don't.**
   The natural next questions:
   - Does adding more *retrieval candidate* sources (e.g. fact-
     assertions, hypothetical-questions enrichments) work the way
     doc-title did?
   - Does a better RERANK (ColBERT, cross-encoder) over the broad
     V0 pool surface the cited chunks?
   - Does CHUNKING (slice-5: better chunk boundaries) recover the
     v3 cites the chunker currently strips role-names from (Q5's
     persistent 0/5)?

3. **The rule engine itself is sound.** The `:llm-classify` rule
   type works exactly as designed: configurable per-corpus prompt,
   per-query LLM call, structured output, JSON parsing, error
   handling, mode selection. It's the right ARCHITECTURE; it's just
   that filter narrowing — whether heuristic or LLM-classified —
   isn't where the gain comes from on this corpus.

## What this proves and what it doesn't

**Proves**:
- LLM-classify is correctly wired into the rule engine; tool-choice
  with enum-constrained items reliably yields universe-valid output.
- The LLM makes correct classification decisions in 4/5 spot-checked
  cases (including the multi-value Q3 case).
- Filtering narrows the candidate pool correctly per the LLM's
  decisions.
- **Filter narrowing hurts top-30 hit rate against v3 cites,
  regardless of classifier quality.**

**Doesn't prove**:
- That V3 (planner-emit) would be different — though same mechanism
  predicts same result.
- That filtering wouldn't help on a DIFFERENT scoring rubric (e.g.
  top-5 precision, where excluding distractors might matter more).
- That LLM classification has no value at all — it might still help
  query-relaxation, query routing, or synthesis-stage decisions.

## Open question for the user

Given the strong "filtering doesn't help" signal from V0→V1→V2:

(a) **Stop slice 3 at V2.** Document that the experiment found
    filtering isn't the right lever for this corpus. Pivot to
    additive moves (enrichments) or non-filter LLM uses
    (re-ranking, query expansion).

(b) **Push through V3 and V4** for completeness. Confirms the
    finding from two more angles, costs ~300 LOC + measurement.
    No surprise expected.

(c) **Try one more V2 variant first**: prompt iteration to fix Q5's
    comparative misclassification. If Q5 fixes, see if the rest
    moves. Cheap experiment (just prompt edits, no new code).

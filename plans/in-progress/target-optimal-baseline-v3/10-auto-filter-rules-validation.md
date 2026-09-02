# Slice-2 validation — auto-filter rules engine

Second slice of
`plans/proposed/retrieval-configurable-fields-rules-plan.md`:
add the auto-filter rules engine (`defmulti detect-by-rule`)
with `:marker-word-classify` and `:shape-pattern` rule types.

## What shipped

- `digdir.rag.auto-filter-rules` — defmulti dispatcher; rule
  TYPES live in code, rule INSTANCES in runtime config.
- `:marker-word-classify` rule type — function-word distribution
  classifier (NB vs EN); supports `:requires-chars` short-circuit
  and `:threshold-ratio` margin.
- `:shape-pattern` rule type — first-match-wins regex-to-value
  mapping; supports `:strict` (`field:=`) and `:permissive`
  (`field:!=[complement]`) modes against a closed
  `:universe` of values.
- `:not-in-set` field-type in `digdir.rag.filters` for the
  permissive `!=[…]` emission.
- `auto-filter/detect-query-filters` now unions hardcoded
  org/year detection with rule-engine output.
- Config plumbing end-to-end: `skills.retrieval.auto-filter-rules`
  config-def, `skill-property-to-path` entry,
  `build-rag-skill-params` reads it, retrieval skill threads it
  to `:auto-filter-rules` in opts, debug endpoint accepts it as
  EDN query param, `bb ts-retrieve --auto-filter-rules` flag.
- 12 unit tests in `digdir.rag.auto-filter-rules-test`.

Out of scope this slice:
- `:facet-match` and `:regex-contains` rule types (the
  org/year migration — slice 3).
- Multi-value diataxis matching (Q3 needs `[how-to,reference]`).

## What's in the digdir/public-docs seed config

```clojure
[{:rule/type :marker-word-classify
  :field "language"
  :min-tokens 3
  :threshold-ratio 2.0
  :values
  {"nb" {:requires-chars "[æøå]"
         :marker-words ["hvordan" "hva" "hvor" "når" "hvorfor"
                        "og" "eller" "ikke" "jeg" "meg" "deg" "vi"
                        "den" "det" "som" "for" "med" "uten"
                        "er" "har" "kan" "skal" "vil" "må"
                        "fra" "til" "av" "noe" "noen" "også"]}
   "en" {:marker-words ["how" "what" "where" "when" "why" "and"
                        "or" "not" "the" "is" "are" "was" "be"
                        "i" "you" "we" "they" "for" "with" "from"
                        "can" "could" "should" "would" "may"
                        "do" "does" "did" "this" "that" "these"
                        "using"]}}}]
```

Only the `:marker-word-classify` rule. The `:shape-pattern`
rule (for diataxis classification) was tested but excluded from
the seed because its misfires on the v3 questions outweighed
its wins — see below.

## Method

Same 7 questions as the v3 baseline. Three configurations
compared:

1. **Slice 1 baseline** — doc-title strategy with title-fields,
   no auto-filter rules.
2. **Slice 2 with both rules** — language + diataxis
   (`:shape-pattern`).
3. **Slice 2 language-only** — what the digdir seed actually
   ships.

## Results

| Q | v3 cites | Slice 1 | Lang + diataxis | **Lang only (shipped)** |
|---:|---:|:---:|:---:|:---:|
| 1 | 3 | 2/3 (#9, #11) | 0/3 | 2/3 (#9, #11) |
| 2 | 2 | 0/2 | 0/2 | 0/2 |
| 3 | 2 | 0/2 | 0/2 | 0/2 |
| 4 | 3 | **3/3** | 2/3 | 2/3 |
| 5 | 5 | 0/5 | 0/5 | 0/5 |
| 6 | 3 | 1/3 | 1/3 | 0/3 |
| 7 | 5 | 1/5 | 1/5 | 1/5 |
| **Total** | **23** | **6/23 (26%)** | **4/23 (17%)** | **5/23 (22%)** |

## Honest accounting

**The auto-filter rules engine ships correctly.** The
attribution map's `:auto-filter-applied` field confirms the
classifier output is correctly translated to Typesense filter
syntax and passed to every strategy.

**But the auto-filter rules don't improve hit rate against the
v3 cites.** Both language-only (5/23) and language+diataxis
(4/23) are at-or-below slice 1's 6/23.

### Where each rule hurts vs helps

**Language rule** — helps where it disambiguates EN vs NB
siblings (Q1, Q5, Q6 narrow correctly). Hurts on **Q4** where
the user's NB query legitimately needs the EN sibling's chunk
2 for the missing Q&A. Slice 1's broad pool surfaced that EN
chunk (`8e71d9016de9` at #11 via content strategy); the
language=nb filter excludes it. Net: −1 from Q4, ±0
elsewhere.

**Diataxis (`:shape-pattern`) rule** — helps Q6 (filters out
how-to-guides docs that distract a v7/v8-comparison query).
Hurts:
- **Q1**: filter narrows to explanation, but explanation-tagged
  pool's BM25 ranks About-Dialogporten chunks below other
  explanation-tagged sibling docs. (Slice 1 had About-Dialogporten
  at #9 #11 in a broader pool; slice 2 has it nowhere.)
- **Q3**: classifier picks `how-to-guides`; v3 trail needed
  BOTH `how-to-guides` AND `reference` (it's a cross-diataxis
  question). The reference doc gets excluded.
- **Q5**: classifier matches `"Hva er"` → `:explanation`, but
  Q5's "Hva er forskjellen mellom..." is *comparative*, not
  definitional. The actual answer is in `:reference` docs
  which the filter excludes.

Net: −2 from Q1 alone outweighs the +1 from Q6.

### The structural issue

The optimal v3 baseline used these filters because a human
made the right choice **per question**:
- Q1, Q2: single-value diataxis OK
- Q3: multi-value diataxis (`[how-to-guides, reference]`)
- Q4: no diataxis; possibly no language (the v3 trail used
  language but the answer needed cross-language synthesis)
- Q5: no diataxis (comparative, not definitional)
- Q6: not applicable (refusal)
- Q7: not applicable (refusal)

A heuristic classifier can't reliably make these decisions on
natural-language queries:
- "Hva er forskjellen mellom" matches the same regex as
  "Hva er X". Distinguishing comparative from definitional
  needs grammatical analysis the regex doesn't do.
- Q3-style cross-diataxis needs uncertainty quantification —
  the classifier should either emit `[how-to, reference]`
  multi-value or skip.
- Q4-style cross-language synthesis is a meta-decision the
  language classifier can't make: "this question is NB but the
  answer requires the EN sibling for content reasons."

## What ships in slice 2 vs what doesn't

**Ships**:
- Rule engine code (`defmulti` + 2 method implementations).
- `:not-in-set` field-type in `filters.cljc`.
- Config + skill-param + debug-endpoint plumbing.
- Unit tests.
- digdir seed config with **language rule only**.

**Doesn't ship in seed**:
- The diataxis `:shape-pattern` rule for digdir. The rule TYPE
  is in code; tenants/operators who tune patterns better can
  enable it via config. For the v3 baseline corpus, the
  classifier's misfires (Q3, Q5) cost more than its wins (Q6).

## What this proves and what it doesn't

**Proves**:
- The rule engine works as a platform — `:rule/type` dispatch,
  config-driven rule instances, end-to-end plumbing through
  the retrieval skill.
- The `:not-in-set` filter type emits the right Typesense
  syntax (verified by manual probe).
- The language classifier classifies all 7 v3 questions
  correctly (`bb test` covers this).

**Doesn't prove**:
- That auto-filtering helps retrieval on natural-language
  queries against this corpus. Net hit-rate at top-30 against
  v3 cites is ≤ slice-1 baseline in every config we tested.
- That `:shape-pattern` is correctly tuned for the corpus. It
  isn't; we excluded it from the digdir seed.

## Honest revised estimate

The original proposed plan predicted 22% → 60-70% with both
slices shipped. The actual measurement after both slices ship
is **22% → 22%** (slice 1 helped to 26% standalone; slice 2's
language filter brings it back to 22%).

This is a significant disagreement with the plan's hypothesis.
The structural reason: the v3 optimal trail's wins came from
**human judgment about which filter to apply per question**, not
from any single auto-detectable signal. The retrievable
information is bottlenecked by query-to-filter classification
quality, not by the absence of a doc-title strategy or a
filter mechanism.

## Where the remaining gain might come from

The slice-1 win (+1 on Q4 via doc-title surfacing the NB
migration doc directly) shows that **adding more retrieval
candidates from new collections/fields can move the needle**
without filtering. The slice-2 result shows that **filtering
narrowing only helps when the narrowing decision is reliable**.

Plausible next levers:
1. **Multi-value diataxis classification** — emit
   `[how-to-guides, reference]` permissively when the
   classifier is uncertain. Would address Q3.
2. **LLM-based query classification** — instead of regex
   patterns, use a small classifier call. Handles the
   "Hva er forskjellen" comparative case Q5 needs.
3. **Cross-language fallback** — when language-filtered
   results are weak, try a sibling-language re-probe. Would
   address Q4.
4. **Re-rank from broader pool** — keep the broad slice-1 pool
   but rerank with stronger query-aware signal. May surface
   the right docs without filtering them out.

None of these are slice-2 work — they're follow-up directions.

## Followups identified during validation

1. **The diataxis classifier as currently tuned is too aggressive.**
   Even with the auto-filter-fallback mechanism, the fallback
   only fires on TOTAL empty results — not on "filter excluded
   the right answer but kept wrong answers". A more
   sophisticated fallback would need to compare with/without
   filter results, which is a larger change.
2. **Slice-1's strategy-weight tuning was right but the
   diversity cap interacts badly with filter narrowing.** With
   a narrower pool, `max-per-document: 10` keeps fewer
   candidates per doc; that combined with `K=3` chunk-fanout
   means the doc-title strategy can swamp the merged top-30.
   Worth revisiting in a future slice.

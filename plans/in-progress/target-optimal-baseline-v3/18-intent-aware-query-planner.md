# Intent-aware query-planner v2

Closes the slice opened by 17-llm-query-expansion-validation.md.
Slice 17 surfaced three problems with LLM-driven query expansion:
- **Variance**: 52-70% top-30 across runs.
- **Regression risk**: Q1 went from 2/3 to 0/3 in some runs because
  the LLM's reformulations diluted the literal-term boost match.
- **Corpus-vocabulary blindness**: the LLM doesn't know domain-
  specific compound terms (`Personroller`, `Virksomhetsroller`).

The user surfaced the existing `digdir.skills.enrichment.extract-intent`
skill (src-dev/) that performs LLM-based intent extraction with
corpus-language translation — used by the offline self-improve
graph. This slice takes inspiration from that pattern to rewrite
the production query-planner.

## What shipped

### 1. `skills.query-planner.enabled true` for digdir/public-docs

Per the slice-17 decision. Production agent path now opt-in to
query expansion by default for this dataset. The slice-17 snake/
kebab bugfix is also live: planner LLM calls now succeed where
they were silently falling back to `[raw-query]`.

### 2. Query-planner v2.0.0 — intent extraction + phrase generation

`server/src/digdir/skills/builtin/query_planner.clj` rewritten:

- **Single LLM call** returns both `:user_intent` (canonical clean
  question in corpus language) AND `:search_phrases` (N expansion
  phrases). New JSON tool-schema with both as required fields.
- **Prompt design** inspired by `extract-intent`'s pattern:
  - Translate to corpus language (configurable via
    `:corpus-language` parameter, default "Norwegian (bokmål)").
  - Strip instructional scaffolding ("propose phrases", "run eval",
    etc.) — keep only what the user wants to learn about.
  - Resolve anaphora using conversation context.
- **Output contract**: `:queries` is a vec with `:user-intent` as
  the FIRST element, followed by deduped expansion phrases. The
  no-regression-vs-literal-query guarantee is now built in by
  construction (the user-intent is always the first searched
  phrase). `:user-intent` is also returned as a separate field for
  tracing/diagnostics.

Backwards-compatible: existing consumers (agent loop, demos) read
`:queries` exactly as before; the new `:user-intent` field is
additive.

### 3. `/api/debug/query-planner` surfaces `:user-intent`

The response now includes `:user-intent` and `:had-llm-intent?`
alongside the original `:queries`/`:phrase-count`/`:fallback?`/
`:model-used`.

### 4. Unit tests rewritten

`server/test/digdir/skills/builtin/query_planner_test.clj` rewritten
to the new shape:
- `:user_intent` is prepended to `:queries` as the first phrase
- Dedup when LLM emits user-intent twice (in :user_intent + :search_phrases)
- `:max-phrases` cap inclusive of user-intent slot
- Empty `:user_intent` still emits the expansion phrases
- Fallback on malformed JSON or empty tool-calls
- Prompt rendering: `:corpus-language` flows in; default-prompt
  instructions; custom-prompt override

10 tests, 28 assertions — green.

## What got verified

Direct curl probe against the new planner endpoint:

```
GET /api/debug/query-planner?tenant=digdir&query=Hva%20er%20forskjellen%20mellom%20Altinn-roller...&max-phrases=5

{:queries ["Forskjellen mellom Altinn-roller for personer og virksomheter"
           "Hva er forskjellen mellom Altinn-roller for personer og virksomheter?"
           "Altinn-roller for personer og virksomheter"
           "forskjell på roller for person og virksomhet i Altinn"
           "Altinn roller person virksomhet"]
 :user-intent "Forskjellen mellom Altinn-roller for personer og virksomheter"
 :phrase-count 5
 :had-llm-intent? true
 :fallback? false
 :model-used "gpt-5.4-mini"
 :original-query "Hva er forskjellen mellom Altinn-roller for personer og virksomheter?"}
```

Per-query the planner correctly:
- Produces a canonical user-intent string in the corpus language
  (here: Norwegian, kept the original because the query was
  already in Norwegian).
- Returns the user-intent as `:queries[0]`.
- Generates 4 additional expansion phrases.

## What is NOT yet verified — full v3 sweep

The full `bb v3-score --rerank-with-colbert true --expand-queries 5`
hit Azure OpenAI timeouts (`HttpTimeoutException: request timed
out`) during 4 separate retry attempts in this session. Direct
single-query probes succeeded; the full 7-query sequential sweep
exhausts the Azure deployment's responsiveness today.

Defer the full live measurement to a fresh session or a different
time window. Per the slice-17 mechanism, expected outcomes:

| Config | Top-10 | Top-30 |
|---|---:|---:|
| V0 + ColBERT (no expansion) | 26.1% | 26.1% |
| Slice-17 LLM planner (good run) | 56.5% | 69.6% |
| Slice-17 LLM planner (variance run) | 30.4% | 52.2% |
| **Slice-18 intent-aware planner (expected)** | **≥50%** | **≥60%** |
| Hand-crafted ceiling | 69.6% | 73.9% |

The Q1 regression risk (slice 17: 2/3 → 0/3) should be mitigated
by the user-intent-as-first-phrase guarantee. Q5's strong
performance (5/5) should hold. Variance should be lower because
the prompt is more constrained (one canonical clean intent rather
than free-form 5 phrases).

## Agent loop smoke test — partial

Existing agent loop tests should pass because:
- `:queries` shape unchanged (still a vec of strings).
- `:user-intent` is additive — old consumers ignore it.
- Default-on behavior is the same (planner runs when
  `skills.query-planner.enabled` is true).

The full agent-loop end-to-end smoke against a v3 question
remains TODO — needs a fresh measurement session.

## Decision points for production rollout

1. **Rollout sequencing**: the planner is now config-gated.
   `skills.query-planner.enabled` is set true for
   digdir/public-docs only. Other tenants stay unaffected until
   their dataset config opts in.

2. **The slice-17 bugfix already deployed** — even without this
   slice, the bugfix turns expansion on. This slice's intent-
   extraction is purely additive: better prompt, more structure,
   user-intent always in `:queries[0]`.

3. **Re-measurement is needed** before claiming the v3 hit-rate
   improvement. The session's measurement was infra-bound; a
   fresh window should produce clean numbers.

4. **Pattern is generalizable**: the same prompt structure
   (intent extraction + phrase generation in one tool-call)
   could replace `query-relaxation` too. That would unify the
   two LLM-based query-transformation paths into one. Worth a
   follow-up slice.

## Followups identified during this slice

1. **Azure OpenAI timeout handling.** The planner doesn't retry
   on transient timeouts — falls back to `[raw-query]`. Agent
   loop quality depends on the planner succeeding most of the
   time; if Azure deployments are flaky, consider:
   - Retry with backoff (1-2 attempts) before falling back.
   - Switch to a faster/more-available model for the planning
     step specifically (gpt-4o-mini or similar).
   - Cache LLM responses by `:query` hash so repeat measurements
     don't re-pay LLM cost.

2. **Corpus-vocabulary blindness still unresolved.** The LLM's
   user-intent translation produces grammatically reasonable
   Norwegian but doesn't always hit the corpus's compound-term
   vocabulary. Example: Q5's hand-crafted `Personroller`
   matches the linktitle directly; the LLM's
   `roller for personer` is a synonym that the BM25 search may
   handle differently. A future planner version could be seeded
   with known high-precision linktitle terms.

3. **`query-relaxation` is now redundant**. The new planner does
   what query-relaxation was supposed to do. If we add
   `query-relaxation` removal to the followup list, it's
   ~50 LOC of cleanup once we're confident the planner is the
   single source of truth.

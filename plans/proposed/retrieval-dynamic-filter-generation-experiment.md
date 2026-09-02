# Dynamic filter generation — experiment design (slice 3)

## Goal

Decide empirically how filter narrowing should work in the retrieval
skill: static heuristic, LLM-classify-per-rule, query-planner-emits-
filters, or hybrid. The slice-2 finding was that static heuristic
rules didn't move top-30 hit rate against the v3 baseline (22% vs
26% without filtering). Three plausible dynamic alternatives exist;
this plan implements all of them and compares them through the
existing sweep harness.

Builds on:
- `plans/proposed/retrieval-configurable-fields-rules-plan.md`
  (rule engine architecture, shipped in slice 2)
- `plans/in-progress/target-optimal-baseline-v3/10-auto-filter-rules-validation.md`
  (the slice-2 finding that motivates this)
- `plans/proposed/multi-variable-sweep-experiment-plan.md`
  (sweep infrastructure; this slice adds one dimension to it)

## The five sweep levels

| Level | Mechanism | Per-query LLM calls (beyond baseline) |
|---|---|---|
| V0 control | Slice-1 doc-title strategy only; `:auto-filter-rules []` | 0 |
| V1 static | Slice-2 heuristic: `:marker-word-classify` language rule | 0 |
| V2 llm-classify | New `:llm-classify` rule type; one LLM call per rule instance | +N (N = rule instances) |
| V3 planner-emit | Extend `query-planner` skill to emit `{:phrases :filters}` JSON | +0 (uses existing planner call) |
| V4 hybrid | V1 static rule first; if it returns nil, fall back to V2 LLM call | 0..+N (conditional) |

Variants V0 and V1 are zero-code additions to the sweep (config only).
V2, V3, V4 require new code.

## Implementation: V2 — `:llm-classify` rule type

Adds a `defmethod detect-by-rule :llm-classify` to
`digdir.rag.auto-filter-rules`.

### Rule shape

```clojure
{:rule/type :llm-classify
 :field "diataxis"
 :universe ["explanation" "how-to-guides" "reference" "tutorials"]
 :allow-multi-value true                    ; Q3 needs [how-to, reference]
 :allow-none true                           ; Q5 needs to skip when comparative
 :mode :strict                              ; or :permissive (emit complement)
 :model "anthropic/claude-haiku-4-5"        ; cheap-fast tier
 :prompt-template "<see below>"}
```

### Prompt template (V2)

```
You classify user questions for retrieval filtering.

The retrieval corpus tags each document with a value of the field "{{field}}".
The allowed values are: {{universe-joined}}.

Decide which value(s) the answer to the user's question is most likely
tagged with.

Rules:
- {{#allow-multi-value}}Return multiple values when the question spans
  categories.{{/allow-multi-value}}
- {{#allow-none}}Return an empty array when the question doesn't
  clearly map to any single category, when the question is comparative
  ("what is the difference between..."), or when filtering by this
  field would risk excluding the answer.{{/allow-none}}
- Never invent values not in the allowed list.

Question: {{query}}

Respond with ONLY a JSON array of selected values, e.g.:
- ["explanation"]
- ["how-to-guides", "reference"]
- []
```

The prompt is deliberately conservative: "return [] when unsure" is
the cue that addresses Q5's comparative misfire and Q3's cross-
category case.

### Output parsing

Use the existing `litellm-clj` + `response_format :json_object`
plumbing (seen in slice-1 commits `e2c0dd7`, `9398105`). Strict
parse; on malformed output, treat as empty array (no filter contribution).

### Caching

Anthropic prompt caching: cache the prompt preamble (everything before
`Question:`). Across 2 rules × N questions × M repeats, the preamble
hits the cache aggressively. Bring in the `claude-api` skill's caching
patterns.

### LOC estimate

~150 lines including the defmethod, prompt rendering helper, output
parser, and unit tests with a stubbed LLM client.

## Implementation: V3 — extend `query-planner` to emit filters

Modifies `digdir.skills.builtin.query-planner` to take an optional
filter-fields schema and emit a structured payload.

### Skill parameter additions

```clojure
:builtin/query-planner
{:enabled true
 :emit-filters? true
 :filter-fields-schema
 [{:field "language" :universe ["nb" "en"] :allow-multi-value false :allow-none true}
  {:field "diataxis" :universe ["explanation" "how-to-guides" "reference" "tutorials"]
   :allow-multi-value true :allow-none true}]}
```

### Prompt extension (V3)

Existing query-planner prompt generates phrases. New extension:

```
You also classify the query for retrieval filtering.

For each field in this schema:
{{filter-fields-schema}}

Decide which value(s) the answer is most likely tagged with, following
the same conservative rule as before (empty array when unsure or
comparative).

Respond with ONLY a JSON object:
{
  "phrases": ["phrase 1", "phrase 2", ...],
  "filters": {
    "<field-name>": ["<value>", ...] or []
  }
}
```

### Where the filters flow

`query-planner` already returns to the agent. The retrieval skill needs
to read `(:filters query-planner-output)` and convert into `:filter-by`
field-specs (matching the shape that `filter-map->typesense-filter`
expects). This is one new helper:

```clojure
(defn planner-filters->filter-by [planner-filters]
  {:fields (vec (for [[field values] planner-filters
                      :when (seq values)]
                  {:type :multiselect
                   :field (name field)
                   :selected-options (set (map str values))
                   :value-type :string}))})
```

The retrieval skill's existing `merge-filter-by` already handles
combining explicit + auto-detected; planner-emit slots in as a third
source.

### LOC estimate

~150 lines: query-planner prompt change, output schema validation,
helper to convert to filter-by, integration with merge-filter-by,
tests.

## Implementation: V4 — hybrid orchestration

V4 is V1 with V2 as the fallback. The orchestration is small once
both V1 and V2 exist.

### Approach: meta-rule type

A new rule type `:static-then-llm` that wraps two child rules:

```clojure
{:rule/type :static-then-llm
 :primary <static-rule-spec>
 :fallback <llm-classify-rule-spec>}
```

`detect-by-rule :static-then-llm` runs the primary; if it returns nil
(no confident classification), invokes the fallback. If primary returns
a result, the LLM call is never made — keeping V4 cheaper than V2 in
expectation.

### Alternative considered

"Always run both; LLM overrides static." Costlier (always pays LLM)
and harder to interpret. Rejected unless V2 evidence suggests static
output is actively misleading the LLM.

### LOC estimate

~50 lines.

## Sweep matrix

```clojure
{:configs
 [{:id "V0-control-no-filter-rules"
   :skill-graph-id :builtin/agent-rag-graph-bundled
   :skill-params
   #:builtin{:retrieval {:title-fields ["linktitle" "frontmatter_title"]
                         :doc-title-chunk-fanout 3
                         :auto-filter-rules []
                         :strategy-weights {:content 1.0 :doc-title 0.8
                                            :phrase 0.7 :metadata 0.2}}
             :rerank {:top-k 30 :enabled false}}}

  {:id "V1-static-language-only"
   :skill-graph-id :builtin/agent-rag-graph-bundled
   :skill-params
   #:builtin{:retrieval {:title-fields ["linktitle" "frontmatter_title"]
                         :doc-title-chunk-fanout 3
                         :auto-filter-rules [<the slice-2 language rule>]
                         :strategy-weights {...}}
             :rerank {...}}}

  {:id "V2-llm-classify-language-and-diataxis"
   :skill-params
   #:builtin{:retrieval {:title-fields ["linktitle" "frontmatter_title"]
                         :doc-title-chunk-fanout 3
                         :auto-filter-rules
                         [{:rule/type :llm-classify
                           :field "language"
                           :universe ["nb" "en"]
                           :allow-multi-value false
                           :allow-none true
                           :model "anthropic/claude-haiku-4-5"
                           :prompt-template "<V2 prompt>"}
                          {:rule/type :llm-classify
                           :field "diataxis"
                           :universe ["explanation" "how-to-guides"
                                      "reference" "tutorials"]
                           :allow-multi-value true
                           :allow-none true
                           :model "anthropic/claude-haiku-4-5"
                           :prompt-template "<V2 prompt>"}]}}}

  {:id "V3-planner-emit"
   :skill-params
   #:builtin{:retrieval {:title-fields ["linktitle" "frontmatter_title"]
                         :doc-title-chunk-fanout 3
                         :auto-filter-rules []}            ; bypass engine
             :query-planner {:emit-filters? true
                             :filter-fields-schema [...]}}}

  {:id "V4-hybrid-static-then-llm"
   :skill-params
   #:builtin{:retrieval {:title-fields ["linktitle" "frontmatter_title"]
                         :doc-title-chunk-fanout 3
                         :auto-filter-rules
                         [{:rule/type :static-then-llm
                           :primary <slice-2 marker-word lang rule>
                           :fallback <V2 llm-classify lang rule>}
                          {:rule/type :static-then-llm
                           :primary <regex shape-pattern diataxis>
                           :fallback <V2 llm-classify diataxis>}]}}}]

 :n-questions 7        ; the v3-baseline set
 :repeats 3            ; account for LLM variability
 :execution-scope {:tenant "digdir"
                   :dataset-config-key "public-docs"
                   :agent-id "builtin/agent-rag-agent"}}
```

7 questions × 3 repeats × 5 configs = **105 runs**.

## Scoring rubric

### Primary: top-30 hit rate against v3 cites

Per question, count `(intersect retrieved-chunk-ids v3-cited-chunk-ids)`
within the top-30 returned chunks. Aggregate across questions and
repeats.

The v3 cite set is fixed and version-controlled in
`plans/in-progress/target-optimal-baseline-v3/0[1-7]-*.md`. The sweep
runner already extracts chunk-ids from results; the scoring rubric is
a small addition to `digdir.sweep.runner` or a post-hoc analysis
script.

### Secondary: latency

Per config, p50 and p95 wall-clock per retrieve. The sweep runner
already records per-run timings.

### Secondary: LLM cost

Per config, count LLM calls per retrieve. V0/V1 = baseline (already-
existing query-relax + query-planner). V2 = baseline + (rule-count).
V3 = baseline (planner extension is free). V4 = baseline + 0..N
(conditional).

Cost in dollars: tokens × price. Cache hit rate matters for V2 — we
want to verify the preamble caches.

### Validation gate

V0 must reproduce the **26% top-30 hit rate** from
`plans/in-progress/target-optimal-baseline-v3/09-doc-title-strategy-validation.md`.
If V0's sweep run lands materially different (e.g. < 22% or > 32%),
the sweep harness scoring has drifted from the manual measurement and
we stop and reconcile before trusting the V2/V3/V4 numbers.

## What we'd learn — risk × outcome matrix

| Outcome | What it means |
|---|---|
| V2 ≈ V1 ≈ V0 | Filter generation isn't the bottleneck. Look elsewhere (rerank, query relaxation, chunking). |
| V2 > V1 but V2 < V0 | LLM classification is better than heuristic but filtering still net-hurts. Confirms the "filters exclude legitimately needed cross-category sources" hypothesis. |
| V2 > V0 | LLM classification overcomes the heuristic's brittleness; filtering becomes net-positive. Big win. Probably ship V2 or V3 as default. |
| V3 ≈ V2 | The query-planner integration captures the same signal as standalone llm-classify. Prefer V3 (cheaper). |
| V3 < V2 | Cramming filter classification into the planner's prompt confuses the planner. Keep V2 as a separate call. |
| V4 ≈ V2 with lower cost | Hybrid wins on cost-quality. Ship V4. |
| V4 < V2 | The static-rule failure modes aren't recoverable by LLM fallback. Static rules are actively misleading. |
| Q4 still regresses across V1–V4 | Cross-language synthesis is a deeper problem than filter classification can solve. Needs a different mechanism (cross-language re-probe). |
| Q5 fixed by V2/V3 but not V1 | Confirms the LLM picks up "comparative ≠ definitional" where the regex doesn't. |

Every outcome is informative.

## Implementation order

1. **Write a `score-against-v3-cites` step** in the sweep runner (or
   as a post-hoc script). Wire it to read the v3 cite set from the
   plans/ markdown files. ~50 LOC. *Validation gate prerequisite.*

2. **V2 first.** Smallest functional add to the existing rule engine.
   Once it works, V4 is trivial.

3. **V3 second.** Touches the query-planner skill, which is more
   independent of the rule engine.

4. **V4 third.** Composes V1 and V2 in a meta-rule type.

5. **Run the sweep.** 105 runs; should finish in ~30 min if LLM calls
   are cached well, longer if not.

6. **Report.** Write
   `plans/in-progress/target-optimal-baseline-v3/11-dynamic-filter-sweep.md`
   with the same shape as slice-1 and slice-2 validation docs.

7. **Decide.** Based on the outcome, either ship the winning variant
   to the digdir/public-docs default config, or document why none
   ships.

## Out of scope

- Adding more rule types (`:embedding-classify`, etc.). The four
  variants above span the design space sufficiently.
- Replacing the rule engine with a single per-query planner call.
  V3 is close to this; if it wins decisively, a future slice can
  collapse the engine to "just the planner" for tenants that don't
  want the heuristic tier.
- Cross-language sibling fallback. Worth its own experiment; not
  this one.
- Production rollout. The sweep is the gate; rollout is a follow-up
  PR if a variant wins.

## Open questions

1. **Model choice for V2.** `claude-haiku-4-5` for cost; should we
   also sweep `claude-sonnet-4-6` to bound the quality ceiling? Adds
   variants but cheap to add to the matrix.

2. **One LLM call per rule vs one combined call.** V2 as drafted is
   per-rule (modular); V3 is one call covering all fields. Should V2
   also be one-call-many-fields? Likely yes for cost; deferred to a
   follow-up if V2 looks promising.

3. **Repeats with LLM nondeterminism.** Temperature=0 by default;
   we'll verify the sweep harness pins it. If it doesn't, three
   repeats may show meaningful variance and we'd need more.

4. **Should V0 disable rerank to isolate retrieval?** The slice-1/2
   measurements were rerank-off (the bb ts-retrieve default). The
   sweep harness defaults to rerank-on. Pinning rerank-off in the
   matrix makes the comparison clean against the prior measurements;
   pinning rerank-on tests the production-shape effect. Suggest **two
   sweeps**: matrix-A with rerank-off (clean comparison), matrix-B
   with rerank-on (production representativeness). 210 runs total.

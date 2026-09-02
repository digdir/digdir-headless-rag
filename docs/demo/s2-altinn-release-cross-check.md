# S2 — Altinn Release-Notes Cross-Check

**Demo scenario from** [`plans/ideas/altinn-docs-skill-demo-plan.md`](../../plans/ideas/altinn-docs-skill-demo-plan.md)

A single agent built on a custom skill graph that takes an Altinn 3 release-notes list as input and emits a structured per-page TODO list of edits the docs need. The agent composes four built-in skills (entity-extraction, multi-retrieval, rerank, plus a structured synthesis stage) and two user-defined skills (a pure-data bridge and a tool-forcing JSON synthesis). It exercises customization flavors 1 (persona), 2 (custom skill graph), and 3 (new skills) at once, and it produced a concrete, generalizable finding about LLM constraint enforcement along the way.

## What was built

All in [`server/src/digdir/demo/altinn_release_notes.clj`](../../server/src/digdir/demo/altinn_release_notes.clj):

- **`:demo/entities->queries` skill** — a pure-data adapter: walks `:entities` (entity-extraction's structured output, vec of `{:text :type :confidence :context}`) and projects each `:text` into a query string. No LLM, no services. Optional `:include-types` filter, optional dedupe. Exists because entity-extraction outputs maps but multi-retrieval consumes strings; a tiny user-defined adapter beats forking either built-in.
- **`:demo/release-todo-synthesis` skill** — structured synthesis: takes the release-notes input + reranked context-docs, calls Azure OpenAI with tool-forcing on an `emitReleaseTodos` JSON schema, post-processes the result, and emits both a rendered markdown response and a typed `:todo-list` map. The synthesis stage IS the structured tool, mirroring S7's `:demo/propose-outline` pattern.
- **`:demo/release-cross-check` skill graph** — `entity-extraction → entities->queries → multi-retrieval → rerank → release-todo-synthesis`.
- **One agent** `demo/altinn-release-cross-check` — `:default-skill-graph "demo/release-cross-check"`, locked to `{:tenant "digdir" :dataset-config-key "public-docs"}`, `:guardrails {:answer-style :research :citations-required true}`.

Wiring mirrors S7: [`skills/init.clj`](../../server/src/digdir/skills/init.clj) calls `register!` at boot; [`config/db.clj`](../../server/src/digdir/config/db.clj) `init-config-db!` calls `seed-agents!` at dump-import time; the dev-only seed step in [`server/src-dev/dev.cljc`](../../server/src-dev/dev.cljc) (also added during S2-A) re-seeds on every dev boot so demo namespaces appear in the playground after a plain restart.

## The test prompt

5 synthetic Altinn-3-flavored release-note items, deliberately mixed:

```
Altinn 3 — release v3.42 notes:
1. Maskinporten JWT exchange now requires a new `altinn:apps:read`
   scope claim for app-side token exchange.
2. Dialogporten dialog API: deprecate the old /v0 endpoints; consumers
   should migrate to /v1 (no breaking response-shape changes).
3. Authentication service: tokens issued via ID-porten now carry an
   explicit `acr` claim distinguishing high vs. substantial assurance.
4. Altinn Studio CLI: rename `altinn-app deploy` to `altinn-app publish`;
   the old name still works but emits a deprecation warning.
5. Outbound webhook signatures now use HMAC-SHA384 (previously HMAC-SHA256).

For each item, identify the existing doc pages on docs.altinn.studio
that need to be updated. Flag any item that has no existing coverage.
```

Items 1–3 reference concepts present in the current corpus (Maskinporten, Dialogporten, ID-porten — all surfaced by S7's retrieval). Item 4 is plausibly covered. Item 5 (webhook signing) is deliberately outside the corpus to exercise the "uncovered" branch.

## The pipeline run

Single run against `digdir/public-docs`, ~62 documents in `website_documents_ab897fbdedfa` (same corpus and same limitation as S7). Final timings from the graph-runner trace:

```
extract       :builtin/entity-extraction    5010 ms   → 12 entities across 5 types
to-queries    :demo/entities->queries         21 ms   → 12 query strings
retrieve      :builtin/multi-retrieval      5671 ms   → 40 chunks (12 queries × avg 48 hits)
rerank        :builtin/rerank                290 ms   → 10 context-docs
synthesize    :demo/release-todo-synthesis  5583 ms   → 3 todos + 2 uncovered + 2 notes
                                          ~16 sec total
```

Final structured output (after post-processing, see [Iteration 3](#iteration-3-todo--uncovered_items)):

```clojure
{:todos
 [{:page_url "/nb/authorization/reference/authentication/"
   :release_item "Maskinporten JWT exchange now requires a new `altinn:apps:read` scope claim for app-side token exchange."
   :predicted_edit "Update the JWT exchange documentation to state that app-side exchanges from Maskinporten must include the new `altinn:apps:read` scope claim..."
   :confidence "medium"}
  {:page_url "/nb/authorization/reference/authentication/"
   :release_item "Authentication service: tokens issued via ID-porten now carry an explicit `acr` claim..."
   :predicted_edit "Add a note to the authentication/token-exchange docs explaining that ID-porten-issued tokens now include an explicit `acr` claim..."
   :confidence "medium"}
  {:page_url "/nb/dialogporten/user-guides/authenticating/"
   :release_item "Dialogporten dialog API: deprecate the old /v0 endpoints..."
   :predicted_edit "Update the Dialogporten authentication/user guide or linked API reference to mark /v0 endpoints deprecated..."
   :confidence "medium"}]

 :uncovered_items
 ["Altinn Studio CLI: rename `altinn-app deploy` to `altinn-app publish`..."
  "Outbound webhook signatures now use HMAC-SHA384 (previously HMAC-SHA256)."]

 :notes
 ["The retrieved passages are concentrated on authentication/authorization and Dialogporten; there is no direct prior art for the CLI or webhook-signature items."
  "The release-note item about Dialogporten v0→v1 migration likely belongs on a Dialogporten API/reference page, but only an authentication/user-guide page was retrieved, so the exact target page is uncertain."]

 :enforced-moves
 {:reason :item-also-in-uncovered
  :moved-release-items
  ["Altinn Studio CLI: rename `altinn-app deploy` to `altinn-app publish`..."
   "Outbound webhook signatures now use HMAC-SHA384 (previously HMAC-SHA256)."]}}
```

Items 1–3 land in `todos` with real URLs and medium confidence. Items 4–5 land cleanly in `uncovered_items`. `enforced-moves` records what the post-processor stripped — see Iteration 3.

## The three iterations

S2-A reached the output above through three iterations, each producing a finding worth keeping.

### Iteration 1: graph-runner traces

Custom skill graphs had no on-disk record of execution — only `:builtin/agent`'s ReAct loop wrote trace files. When the first S2 run failed to produce useful output (and at one point seemed to fail to appear in the playground at all), there was nothing to diagnose from.

**Fix:** Added [`server/src/digdir/skills/graph/trace.clj`](../../server/src/digdir/skills/graph/trace.clj) and hooked it into `digdir.skills.graph.runner/run-graph`. Every graph run now writes `server/logs/graph-trace-<graph-id>-<timestamp>.txt` capturing: graph id, tenant/dataset, per-step skill+status+duration+input-refs+output content, and any error. The failure path writes a partial trace before throwing so step errors are visible without re-running.

**Sub-iteration:** initial implementation truncated each output value at 600 chars to keep traces small. That hid exactly what we needed to debug — the synthesis response, the structured tool output, the model's actual reasoning. Replaced with full-content rendering: strings raw, maps and vectors via `clojure.pprint`, no char limits. Trace went from 8 KB to 149 KB. Worth every byte during development.

### Iteration 2: page_url surfacing

The model emitted `page_url: "58c4de742356"` for every TODO — chunk-ids, not URLs. Cause: my `format-context-docs` rendered each passage as `[N] source=<chunk-id>` because `:metadata :source` was the only easy-to-reach identifier. The actual document URL lived under a collection-named key inside each chunk (e.g. `:website_documents_ab897fbdedfa :url`), invisible to the LLM.

**Fix:** Walk the chunk for any nested map with a `:url` key (a small helper handles the collection-name unpredictability), then render each passage header as `[N] page=<URL>  chunk=<chunk-id>`. Reinforced the same point in three places: the passage header, the prompt rules, and the JSON schema description for `page_url`. Re-ran — TODOs cited real `/nb/authorization/reference/authentication/`-style URLs.

### Iteration 3: TODO ⊕ uncovered_items

After Iteration 2, the model still emitted items 4 and 5 in **both** `todos` AND `uncovered_items`. Three rounds of prompt-engineering reinforcement to enforce a mutual-exclusion (XOR) constraint:

1. **Add an explicit rule** in the prompt: "Each release-note item MUST land in EXACTLY ONE of `todos` or `uncovered_items`. Never both." → Model continued to double-emit, with self-contradicting "low confidence" TODOs.
2. **Strengthen the schema** by removing `"low"` from the confidence enum, forcing the model to either upgrade or move. → Model upgraded items 4/5 to `"medium"` and kept them in `todos`, now with a hallucinated `/nb/altinn-studio/apps/cli/` URL.
3. **Tighten further** with explicit prohibition of fabrication and "decide first, then route" instructions. → Model dropped the fabricated URL but routed both items to `/nb/authorization/reference/authentication/` and left them in both arrays, with rationales like "The retrieved passages do not show any CLI documentation, but the authentication page is the only Altinn Apps/Platform page in scope" — the model openly admitting the TODO didn't belong.

**Fix:** A 10-line post-processor in code:

```clojure
(defn- enforce-todo-xor-uncovered
  [{:keys [todos uncovered_items] :as parsed}]
  (let [uncovered-set (set (or uncovered_items []))
        {kept-todos true moved-todos false}
        (group-by #(not (contains? uncovered-set (:release_item %))) (or todos []))]
    (cond-> (assoc parsed :todos (vec kept-todos))
      (seq moved-todos)
      (assoc :enforced-moves
             {:reason :item-also-in-uncovered
              :moved-release-items (mapv :release_item moved-todos)}))))
```

The trace records `:enforced-moves` so the LLM-vs-validator delta stays visible: when the model double-emits, the post-processor strips the dupe and the operator can see what was overridden.

## The generalizable finding

**Tool-forcing JSON gives you shape. Post-processing gives you constraints. Prompts alone won't enforce structural rules.**

Three layers of prompt reinforcement — rule, schema description, schema enum constraint — still let the model bend a simple XOR. Each round of prompting just shifted *how* the model violated it. Ten lines of code enforcing the same constraint after the fact didn't bend at all, and exposed exactly what the model had tried to do.

When a downstream consumer depends on a structural invariant, validate it in code. The prompt's job is to get the shape right; the validator's job is to keep promises the model can't.

## What this proves about the skill system

S2-A exercises the same three customization flavors as S7, but with the emphasis on a fully-bespoke skill graph rather than persona-plus-optional-tool:

| Flavor | Mechanism | Where it shows up in S2 |
|---|---|---|
| 1. Persona | Custom `:instructions` on the agent record | `demo/altinn-release-cross-check` carries release-notes-author framing |
| 2. Custom skill graph | Register a new graph via `templates/register-skill-graph!` | `:demo/release-cross-check` composes 4 built-in + 2 user-defined steps |
| 3. New skill | Register a new skill via `skills/register-skill!` | `:demo/entities->queries` (data adapter), `:demo/release-todo-synthesis` (structured synthesis) |

Two additional observations the build surfaced:

- **User-defined adapter skills are first-class.** `:demo/entities->queries` exists only to bridge two built-ins whose I/O shapes don't line up. It's 10 lines, no services, no LLM, no special status — and that's the point. The skill system doesn't force you to fork built-ins or invent middleware; you write a small skill and wire it in.
- **The graph runner had a contract gap with built-in skills before S7, and S2-A confirmed the gap closure landed correctly.** All four built-in skills in this graph (entity-extraction, multi-retrieval, rerank, plus the underlying registry/skills/services machinery) worked end-to-end as graph steps on first try after the S7 fixes — see [`plans/to-be-fixed/graph-runner-builtin-skill-gaps.md`](../../plans/to-be-fixed/graph-runner-builtin-skill-gaps.md). S2 is the regression test for those fixes.

## Costs and limitations

- **Corpus limitation.** Same as S7: the live Typesense collection holds digdir-flavored content, not docs.altinn.studio, so `page_url`s lean toward `/nb/authorization/reference/authentication/` and `/nb/dialogporten/user-guides/authenticating/`. Items that need an Altinn-Studio-CLI or webhook page would map to real URLs only after running the Altinn-docs materialization pipeline. The current state still demonstrates the agent's behavior cleanly — `uncovered_items` correctly catches what the corpus doesn't cover.
- **No real fan-out.** The plan's original S2 description called for per-entity rerank and per-page summarization. The graph runner is a flat sequential DAG today, so the structured-synthesis stage handles all the per-item reasoning in a single LLM call. Real fan-out depends on a `:foreach` step type in the runner — that's S2-B, queued.
- **One-iteration synthesis.** No iterative refinement, no fact-checking loop. Sufficient for the demo's purpose; a production version would likely chain a verification step.

## What this scenario didn't need

S2-A did not need any modifications to built-in code paths. Iteration-1's graph-runner trace is built-in infrastructure (every future graph benefits) but is not required for the demo to function. Iterations 2 and 3 are entirely inside the demo namespace. The skill system handed back exactly the composition surface promised, and the only places we had to write code were the places the scenario actually differs from a generic RAG pipeline.

## S2-B: foreach-based variant

S2-A's pipeline collapsed the plan's per-page summarization fan-out into a single structured-synthesis call. The graph runner was a flat sequential DAG with no map/foreach step type, so "summarize each reranked page in its own LLM call" had no natural expression. S2-B closes that gap.

### Runner extension

A `:foreach` step type was added to the graph runner — discriminated from regular steps by the presence of a `:foreach` field. Step shape:

```clojure
{:id :summarize-per-page
 :foreach {:over [:rerank :context-docs]   ; ref to a collection
           :as :page                       ; binds each element as :$page
           :as-index :idx}                 ; (optional) :$idx for position
 :do {:skill :builtin/summarization        ; inner step, regular-shaped minus :id
      :inputs {:content :$page}
      :parameters {:style :technical :max-length 60}}
 :collect-as :per-page-summaries           ; output key for the result vector
 :on-error :default}                       ; :fail (default) | :skip | :default
```

Implementation in [`server/src/digdir/skills/graph/`](../../server/src/digdir/skills/graph/):

- **`schema.clj`** — new `ForeachStep` schema and a `:multi`-dispatched `GraphStep` (the existing single-skill step shape is unchanged). `extract-input-refs` now pulls refs from both `:foreach/:over` and the inner `:do/:inputs`, while iteration-scope vars (`:$item`/`:$idx` and their user-renamed variants) are filtered out so they don't fail forward-reference validation.
- **`runner.clj`** — `execute-foreach-step` runs sequentially, merging a per-iteration scope into graph-inputs so `:$item`/`:$idx` resolve through the standard `resolve-input-ref` path. Inner-step failures honour `:on-error`: `:fail` aborts the whole foreach with a typed error result, `:skip` drops failed iterations from the collected vector, `:default` inserts an empty outputs map in the failed slot to keep index alignment.
- **`trace.clj`** — foreach steps render with a `[foreach]` header line, the inner skill's `[skill]`, an `[iterations N]` block, and per-iteration `idx duration status` lines.

Seven unit tests in [`server/test/digdir/skills/graph/runner_test.clj`](../../server/test/digdir/skills/graph/runner_test.clj) cover: happy-path collection, `:$idx` resolution, all three `:on-error` modes, empty `:over`, and ref-extraction.

### V2 demo graph

The existing scenario was reused with one new step inserted between rerank and synthesis. New graph `:demo/release-cross-check-v2` (alongside v1, both registered):

```
:extract             → :builtin/entity-extraction
:to-queries          → :demo/entities->queries
:retrieve            → :builtin/multi-retrieval
:rerank              → :builtin/rerank
:summarize-per-page  → :foreach over [:rerank :context-docs]
                        inner: :builtin/summarization (technical, 60-word max)
                        :collect-as :per-page-summaries
                        :on-error :default
:synthesize          → :demo/release-todo-synthesis
                        :inputs {:query :$user-query
                                 :context-docs [:rerank :context-docs]
                                 :summaries [:summarize-per-page :per-page-summaries]}
```

`:demo/release-todo-synthesis` was extended to accept an optional `:summaries` input (index-aligned with `:context-docs`). When present, each summary is inlined into its passage header in the prompt — passages now include both raw chunk text AND a short technical summary, so the synthesizer has page-level orientation alongside the chunk-level detail.

A second agent `demo/altinn-release-cross-check-v2` exposes the v2 graph in the playground. V1 stays available unchanged.

### V1 vs V2 on the same input

Same release-notes test prompt, same corpus. Step timings from a single v2 run:

```
extract             :builtin/entity-extraction       5419 ms   → 12 entities
to-queries          :demo/entities->queries            57 ms   → 12 query strings
retrieve            :builtin/multi-retrieval         6423 ms   → 40 chunks
rerank              :builtin/rerank                   331 ms   → 5 context-docs
summarize-per-page  :foreach over :rerank.context-docs
                    :builtin/summarization           8436 ms   → 5 page summaries
                      [0] ok 1207ms
                      [1] ok 2718ms
                      [2] ok 1063ms
                      [3] ok 1399ms
                      [4] ok 2048ms
synthesize          :demo/release-todo-synthesis    4303 ms   → 4 todos + 2 uncovered
                                                  ~24.9 sec total
```

Comparison vs S2-A's v1:

| Dimension | V1 (flat) | V2 (foreach) |
|---|---|---|
| Total wall-clock | ~16 s | ~25 s |
| LLM calls | 2 (entity-extract + synthesize) | 7 (entity-extract + 5 summaries + synthesize) |
| TODOs emitted | 3 | 4 — release-item 1 mapped to BOTH the auth concept page AND the Studio integration guide |
| Uncovered items | 2 | 2 (same items 4 & 5 — CLI rename and webhook signing) |
| `:enforced-status-fixes` fired? | yes (post-processor moved 2 items) | no |
| Confidence distribution | medium × 3 | high × 1 + medium × 3 |
| Visible synthesis context | reranked chunk text only | reranked chunk text + per-page technical summary |

The interesting differences aren't the timings — they're the **synthesis quality**:

- **V1 emitted 3 TODOs but the post-processor had to fix 2 of them** (items the model double-routed into both `todos` and `uncovered_items`). V2 emitted 4 cleanly with no post-processor intervention.
- **V2 found a multi-page mapping** for release-item 1: the auth-concept page AND the Studio integration guide. V1 collapsed it to the auth-concept page alone. The summaries gave the synthesizer enough page-level orientation to distinguish "this page is about Maskinporten generally" from "this page is the Studio Maskinporten integration guide" and route the same release-note item to both.
- **V2's "high" confidence** for release-item 1's primary TODO is the only "high" emitted across either run. The summaries' explicit mention of "scope verification during exchange" gave the synthesizer evidence to commit. V1's three TODOs were all medium-confidence.

### Generalizable observations (foreach)

**1. The per-iteration LLM call has a steady-state cost.** Five summarization iterations took 1.0–2.7s each (median 1.4s), sequential. With parallelism off, foreach over N items adds approximately `N × per-iteration-cost` seconds. That's the cost of fan-out, and it's exactly what `:foreach` is meant to make explicit. A future `:parallelism N` flag could batch these.

**2. Summaries reduce synthesis cost AND post-processor work, not just add LLM cost.** V2's synthesis call was 4.3s vs V1's 5.6s, even though V2 has more context (chunks + summaries). The summaries appear to make the synthesizer's reasoning more direct — fewer tokens spent reconciling unfamiliar chunk text. And the post-processor didn't have to fix anything, because the model had clearer page-level identity to work with.

**3. `:on-error :default` matters more than expected.** Without it, a single flaky summarization (which happens — Azure occasionally rate-limits or 5xx's a request) would abort the entire downstream synthesis. With `:default`, a single bad iteration leaves an empty `{}` in the slot and the synthesis proceeds with whatever summaries it does have. The trace shows which slot was empty, so the operator can spot-check. Cheap insurance against the LLM-call-is-the-network-edge problem.

**4. Discriminated step shape was the right call.** The existing `:skill`/`:inputs` step shape is unchanged. Every existing graph still validates as before. Only the `extract-input-refs` walker needed to learn about the foreach inputs — and the change was bounded enough that all prior runner unit tests stayed green without modification.

### What v2 didn't need

No changes to any existing synthesis skill behaviour for v1. No changes to the agent record format. No changes to the graph-runner's outer execution loop (the foreach step is one branch inside the existing step dispatch). The runner's contract with built-in skills is unchanged — `:builtin/summarization` runs identically whether invoked as a regular step or inside a foreach.

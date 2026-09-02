# Self-Improve as a Skill Graph — Design Plan

**Status:** proposed
**Related:** [`react-loop-as-graph-plan.md`](./react-loop-as-graph-plan.md) (the generic agent-loop migration; this plan is narrower and complementary, not a replacement)
**Motivates:** validating the skill-graph thesis — that structural composition is the right control mechanism for LLM non-determinism — against a concrete, already-failing case.

## Goal

Build `:digdir.demo/self-improve-graph` as a skill graph that does the same job as the existing `:digdir.demo/self-improve-agent` (ReAct), with the LLM constrained to the slots where its judgment is actually needed and the structure decided by edges, not prompt suggestions. Run the two side-by-side in the playground on the same fixture and use the difference as evidence.

## The failure this plan addresses

Two failure modes have surfaced in the ReAct self-improve agent and have not been suppressed by two iterations of prompt-tightening:

1. **Compositional drift.** The LLM sometimes skips `propose_questions_for_chunk` and composes the four questions inline as arguments to `apply_enrichments`. The work happens, but provenance is dishonest — auto-stamped `:agent-composed` instead of carrying the real model + prompt-hash + timestamp from the proposer skill.

2. **Terminal denial.** Even on fully successful runs (gate PASS, all counters populated, no errors), the LLM's final content message sometimes says "I can't actually run `apply_enrichments` or `run_eval_delta` from this chat." A face-saving training prior firing in spite of an explicit system-prompt instruction to transcribe tool results.

Both failures share one root: **the LLM is free to choose the shape of each turn** — which tools to call, in what order, and what the terminal message looks like. The system prompt is a polite suggestion against that freedom; tightening it further has flat returns. This is the artifact the ReAct version was meant to surface.

## Why a skill graph dissolves both modes

A graph encodes the *shape* of work as edges between nodes, not as instructions inside a prompt. Inside a node the LLM remains generative (picking worst chunks, writing four questions, interpreting a gate result). Between nodes, the structure is non-negotiable.

| Failure | How the graph kills it |
|---|---|
| Skip `propose_questions_for_chunk` | Impossible — propose's output IS apply's input. There is no apply-without-propose edge. |
| Hallucinated terminal denial | Impossible — there is no LLM-authored final turn. `compose-report` derives the final response from workspace state. |

The LLM keeps the two judgment calls where its freedom is actually valuable: **which chunks to enrich** and **whether to keep or revert each enrichment based on the gate**. Everything else the graph decides.

## Architecture

```clojure
;; Outer graph: :digdir.demo/self-improve-graph
{:graph-id :digdir.demo/self-improve-graph
 :inputs [:user-query :tenant :dataset-config-key :tenant-config-key
          :runtime-config-key :max-chunks :gate-mode]
 :outputs [:report :iterations-summary]
 :steps
 [{:id :analyze
   :skill :builtin/enrichment-analyze-corpus
   :inputs {:tenant :$tenant
            :tenant-config-key :$tenant-config-key
            :dataset-config-key :$dataset-config-key
            :max-chunks :$max-chunks}}              ; → :chunk-ids [<id> ...]

  {:id :per-chunk
   :foreach {:over [:analyze :chunk-ids]
             :as :chunk-id}
   :do {:sub-graph {:graph-id :digdir.demo/enrich-one-chunk
                    :inputs {:chunk-id :$chunk-id
                             :tenant :$tenant
                             :tenant-config-key :$tenant-config-key
                             :dataset-config-key :$dataset-config-key
                             :runtime-config-key :$runtime-config-key
                             :gate-mode :$gate-mode}}}
   :collect-as :chunk-outcomes}                      ; → vec of {:chunk-id ... :decision :keep|:revert :eval ...}

  {:id :report
   :skill :builtin/enrichment-compose-report
   :inputs {:analysis [:analyze :analysis]
            :outcomes [:per-chunk :chunk-outcomes]}}] ; → :report (final user-facing text)
}

;; Inner sub-graph: :digdir.demo/enrich-one-chunk
{:graph-id :digdir.demo/enrich-one-chunk
 :inputs [:chunk-id :tenant :tenant-config-key
          :dataset-config-key :runtime-config-key :gate-mode]
 :outputs [:chunk-id :decision :eval :proposal]
 :steps
 [{:id :propose
   :skill :builtin/enrichment-propose-questions      ; LLM node — emits 4 Q's + real provenance
   :inputs {:chunk-id :$chunk-id
            :tenant :$tenant
            :tenant-config-key :$tenant-config-key}}

  {:id :apply
   :skill :builtin/enrichment-apply-questions        ; deterministic — writes Typesense rows
   :inputs {:chunk-id :$chunk-id
            :questions [:propose :questions]
            :provenance [:propose :provenance]
            :tenant :$tenant
            :tenant-config-key :$tenant-config-key}}

  {:id :eval
   :skill :builtin/enrichment-eval-suite             ; deterministic — runs benchmark
   :inputs {:tenant :$tenant
            :dataset-config-key :$dataset-config-key
            :tenant-config-key :$tenant-config-key
            :runtime-config-key :$runtime-config-key
            :agent-id "digdir.demo/self-improve-graph"
            :fail-on-gate? false}}

  {:id :decide
   :select {:on [:eval :summary :gate-pass]}
   :branches {:true  {:do {:skill :builtin/enrichment-mark-keep
                           :inputs {:chunk-id :$chunk-id
                                    :proposal [:propose :proposal]
                                    :eval [:eval :summary]}}}
              :false {:do {:skill :builtin/enrichment-revert-chunk
                           :inputs {:chunk-id :$chunk-id
                                    :tenant :$tenant
                                    :tenant-config-key :$tenant-config-key
                                    :eval [:eval :summary]}}}
              :default {:do {:skill :builtin/enrichment-mark-keep
                             :inputs {:chunk-id :$chunk-id
                                      :proposal [:propose :proposal]
                                      :eval [:eval :summary]}}}}}]}
```

### Where the LLM still gets to think

- **`:analyze`** — given corpus stats and worst-N retrieval misses, pick which chunk-ids deserve attention. LLM-driven node with a fixed-shape output schema (`{:chunk-ids [...] :analysis "..."}`).
- **`:propose`** — generate four diverse hypothetical questions for one chunk. LLM-driven, already exists. Output schema fixed.
- **`:decide`** — implicit in the gate-pass boolean; we could also add an LLM judgment node here later if we want a softer keep/revert rule (e.g., "regressed by ≤1 but improved comprehension; keep"). Initial version uses the gate verdict directly.

### Where the LLM is locked out

- Order of operations within a chunk (propose→apply→eval is structural).
- Whether to skip a step (`:foreach` runs every chunk; sub-graph runs every step).
- Final report wording (deterministic concatenation from workspace state).

## What's already built vs. what's new

### Already exists

| Component | Location | Status |
|---|---|---|
| Graph runner with `:foreach`, `:loop`, `:sub-graph`, `:select`, `:dispatch-by-name` | `server/src/digdir/skills/graph/{schema,runner,trace}.clj` | ✓ |
| `:builtin/enrichment-propose-questions` | `server/src-dev/digdir/skills/enrichment/propose_questions.clj` | ✓ |
| `:builtin/enrichment-apply-questions` | `server/src-dev/digdir/skills/enrichment/apply_questions.clj` | ✓ |
| `:builtin/enrichment-eval-suite` | `server/src-dev/digdir/skills/enrichment/eval_delta.clj` | ✓ |
| `templates/register-skill-graph!` registration | `server/src/digdir/skills/templates/core.clj` | ✓ |
| Per-skill drawer panels in observability | `server/src/digdir/playground/ui/observability/live_next.cljc` | ✓ |
| Typesense rows inspector for `apply` | `server/src/digdir/playground/ui/observability/enrichment_inspector.clj` | ✓ |

### To build

| Component | What it is | Estimate |
|---|---|---|
| `:builtin/enrichment-analyze-corpus` skill | Extract today's `analyze_corpus` tool body into a standalone skill. LLM-driven; emits `{:chunk-ids [...] :analysis "..."}`. | ~120 LOC + tests |
| `:builtin/enrichment-mark-keep` skill | Deterministic. Tags chunk-id as kept in workspace. No-op against Typesense (rows already written by `apply`). | ~30 LOC |
| `:builtin/enrichment-revert-chunk` skill | Deletes the enrichment rows for this chunk-id from Typesense. Symmetric to `apply`. | ~60 LOC + tests |
| `:builtin/enrichment-compose-report` skill | Walks `{:analysis :outcomes}` and emits a deterministic Markdown report: which chunks were tried, gate verdicts, kept/reverted summary. | ~80 LOC + tests |
| Two graph definitions (outer + inner) | EDN-shaped Clojure maps, registered at ns-load via `register-skill-graph!`. | ~50 LOC |
| Init wiring | Require new ns in `digdir.skills.init` / dev seed | ~5 LOC |
| Playground agent picker | New entry in the agent-id dropdown alongside the existing `:digdir.demo/self-improve-agent` | ~10 LOC |
| Observability tweaks | Most panels already work; verify the `:select` step renders cleanly in the drawer; map `:enrichment-mark-keep` / `:enrichment-revert-chunk` to event-kinds | ~40 LOC |

Total: ~400 LOC + tests. Probably 1–2 days of focused work plus a smoke-and-iterate session.

## Migration order

1. **Skills first** — write `analyze-corpus`, `mark-keep`, `revert-chunk`, `compose-report`, each with focused tests. The graph can't be registered until its constituent skills exist.
2. **Inner sub-graph** — define and register `:digdir.demo/enrich-one-chunk`. Smoke it directly with a hand-picked `chunk-id` from the playground REPL.
3. **Outer graph** — define and register `:digdir.demo/self-improve-graph`. Smoke with `max-chunks: 1` against the same fixture the ReAct agent uses.
4. **Playground wiring** — surface in the agent picker. Run side-by-side.
5. **Observability check** — confirm the drawer panels render correctly for graph-originated events. The existing panels key on `:result-summary` shape, not on tool-vs-skill provenance, so most should "just work."

## Comparison experiment

Once both agents are runnable from the playground:

1. Same query: `"Hva er Altinn 3 lansert?"`
2. Same fixture: `test/fixtures/agent/altinn3_lansert_stability.edn`
3. Same starting Typesense state (empty enrichment collection)
4. Capture traces for both
5. Tabulate:
   - Did `propose` run for every applied chunk? (ReAct: not always; Graph: structurally yes)
   - Final response: factual or self-contradicting? (ReAct: variable; Graph: deterministic-by-construction)
   - Wall-clock per chunk
   - Gate-pass rate
   - Provenance honesty (% of rows carrying real `:model` vs `:agent-composed`)

This becomes the validation evidence for the skill-graph thesis. Capture it in `plans/in-progress/self-improvement-agent-plan.md` as a Phase C.5 (or Phase D-precursor) result block.

## What the ReAct agent becomes

`:digdir.demo/self-improve-agent` **stays registered**. It does not get fixed. Its role is now:

- **Negative control** — keeps demonstrating freeform failure modes alongside the graph's structural correctness.
- **Study artifact** — if a future model is robust enough that the failures disappear without graph-level structure, that's interesting evidence too.
- **Prompt-engineering lab** — anyone who wants to try harder system-prompts can do it against this agent without affecting the graph.

We don't tighten its prompt and we don't add propose-enforcement. The whole point is that those mitigations are workarounds; the graph version is the cure.

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| `:analyze-corpus` LLM picks bad chunks | medium | low | Same risk as today's tool; isolated to one node; fixable with prompt iteration on that node alone |
| `:revert-chunk` removes wrong rows | low | high | Filter by `chunk_id:=` AND `prompt_hash:=` from the proposal; idempotent delete; unit-tested before wiring |
| Graph eval cost per chunk dominates wall-clock | high | medium | `:foreach` runs sequentially today (parallelization plan separately tracked); `max-chunks` defaults small (3); acceptable for an experiment |
| Decoupling propose↔apply provenance breaks if propose retries | low | low | Provenance is part of `:propose`'s output and threaded into `:apply`'s input by edge; retries re-emit a fresh provenance map |
| Observability drawer doesn't render graph events as cleanly as agent events | medium | low | Most panels already key on result-summary shape; small per-skill mapping additions if needed |
| Existing react-loop-as-graph plan and this plan diverge | medium | low | Different scope (this is one demo agent; that one is the generic loop). No code overlap. |

## Non-goals

- **Don't migrate the generic ReAct loop.** That's `react-loop-as-graph-plan.md`, multi-week, infrastructure-level. This plan is one demo agent.
- **Don't reuse the same agent id.** Two distinct agent-ids in the playground: `:digdir.demo/self-improve-agent` (ReAct) and `:digdir.demo/self-improve-graph` (graph). The user picks which to run.
- **Don't delete the ReAct tools.** `analyze_corpus`, `propose_questions_for_chunk`, `apply_enrichments`, `run_eval_delta` stay registered as tools the ReAct agent can call. The graph uses the underlying skills directly, not via the tool wrappers.
- **Don't try to express LLM-driven keep/revert in v1.** Start with the deterministic gate-pass-boolean branch. If we want a softer rule, add it later as a third LLM node.
- **Don't try to parallelize `:foreach`.** Skill-graph parallelization is tracked separately. Sequential per-chunk is fine for the experiment.

## Open questions

1. **Should `:analyze` see retrieval-failure cases from the eval suite, or just stable corpus stats?** Today's `analyze_corpus` tool does the former (it can see prior eval misses). Recommend: keep that capability — the LLM node accepts an optional `:prior-misses` input populated from a recent eval run.

2. **How does the graph handle a `chunk-id` whose `:propose` step errors?** Recommend: `:foreach :on-error :continue` so one bad chunk doesn't kill the whole pass. The chunk-outcome carries `{:error ...}` instead of `{:decision ...}`.

3. **Should `:compose-report` produce JSON or Markdown?** Recommend: Markdown for the user-visible final message, with a separate `:report-structured` output for downstream tooling.

4. **Where does the playground render the "final report"?** Today the ReAct agent's final content message goes into the chat as the assistant turn. For the graph, the `:report` output becomes the chat's assistant message; the graph's per-chunk outcomes become drawer events (already true if the per-chunk events emit standard skill-execution traces).

5. **Should we keep the per-chunk LLM-judgment escape hatch (a soft keep/revert decision) as a future extension?** Yes — design `:decide` with `:select :on [:eval :summary :gate-pass]` initially, but add an `:decision-policy` graph input that can swap in an LLM node when needed.

## Recommended next step

Start with the four new skills (in this order, each with tests):

1. `:builtin/enrichment-revert-chunk` — needs to be sound before anyone trusts it
2. `:builtin/enrichment-mark-keep` — trivial, useful for completeness
3. `:builtin/enrichment-compose-report` — deterministic, easy to unit-test
4. `:builtin/enrichment-analyze-corpus` — carve out from the existing tool

Then the inner sub-graph, then the outer graph, then the playground picker. Smoke run with `max-chunks=1`, compare side-by-side with ReAct on the same fixture, write up the comparison.

If the comparison shows what we expect (zero compositional drift in the graph, zero terminal denial), the skill-graph thesis has its first concrete validation case — and we can use the same pattern when designing the Phase D enrichments (verified phrases, fact assertions, KG enrichments).

# ReAct Loop As Graph Composition — Design Plan

**Status:** proposed
**Depends on:** [`:foreach` step (S2-B, shipped)](../ideas/altinn-docs-skill-demo-plan.md), [skill-graph parallelization runtime plan](skill-graph-parallelization-runtime-plan.md) (informational)
**Motivates:** unification of agent execution path with custom skill graphs; one trace format; demo scenarios can use the same composition primitives the agent uses.

## Goal

Re-implement `:builtin/agent`'s internal ReAct loop ([`server/src/digdir/skills/builtin/agent/loop.clj`](../../server/src/digdir/skills/builtin/agent/loop.clj)) as a composition of graph-runner primitives rather than a bespoke imperative loop. After this work, an agent run and a custom skill-graph run produce traces with the same shape, execute through the same dispatch path, and surface the same per-step observability — without changing the agent's user-visible behaviour.

## Executive summary

`:builtin/agent` today is a 900-line imperative loop inside a single registered skill. Its execution path (workspace atom, per-iteration tool dispatch, sufficiency gate, response validation, phase switching, exhaustion fallback) is disjoint from the graph runner's. That has three concrete costs:

1. **Two trace formats.** Agent runs write `agent-trace-<ts>.txt` from `digdir.skills.builtin.agent.core`; custom skill graphs write `graph-trace-<id>-<ts>.txt` from `digdir.skills.graph.trace`. The S7 narrative and S2-A narrative both surface workarounds because the structures differ.
2. **Two extensibility points.** A demo scenario that wants to add a step is straightforward inside a custom skill graph (S2-A, S5, S2-B-v2). The same scenario inside the ReAct toolkit means modifying `tools/agent-tool-definitions` + `tools/execute-tool-call` (S7's "third customization flavor"). Two different surfaces with different ergonomics.
3. **Two reasoning models.** S2-B established that fan-out via `:foreach` can improve synthesis quality, not just decompose work. The agent loop currently can't take advantage of this — its iteration shape is fixed.

The plan is a **staged migration**, not a big-bang rewrite. Each phase lands independently, each is testable end-to-end against `:builtin/agent-rag`'s existing eval suite, and the last phase is optional (we may stop earlier if the cost/benefit flattens out).

## Scope

### In scope

- Identifying the runner primitives needed beyond `:foreach`.
- Designing each new primitive's step shape and validation rules.
- Mapping the current agent semantics onto the proposed primitives.
- A 5-phase migration with clear stop-points after each phase.
- Risk register and open questions.

### Out of scope

- Performance work (parallel iteration, batching tool calls). Tracked separately under [skill-graph parallelization](skill-graph-parallelization-runtime-plan.md).
- Changing any agent-visible behaviour. Sufficiency-gate semantics, exhaustion paths, range-read hints, clarification flows — all preserved verbatim.
- Removing `digdir.skills.builtin.agent` as a registered skill from the agent registry. It stays registered; its `:execute` just becomes a graph invocation.

## Current state

`:builtin/agent` is registered as a single skill whose `:execute` calls `agentic-loop`. Inside `agentic-loop`, eight concerns are interleaved:

1. **Message-thread accumulation.** `messages` is the canonical OpenAI chat history. Each iteration appends an assistant message + N tool-result messages.
2. **Per-turn tool dispatch.** `mapv` over `tool_calls` from the LLM response, each mapping to `tools/execute-tool-call`. Each tool is itself a thin wrapper around a sub-skill (search → retrieval, read_chunks → typesense range fetch, rerank_results → `:builtin/rerank`, generate_response → `:builtin/synthesis`).
3. **Workspace state mutation.** `swap!` on `!workspace` for iteration-history, sufficiency-decisions, stage-timings, read-signals, response-validations, and ~10 other keys.
4. **Sufficiency gate.** A separate LLM call that returns `{:status :sufficient|:insufficient :suggested-strategy :read-more|:finalize|:re-search|:ask-clarification|...}`. Result is injected as a system message in the next iteration.
5. **Response validation.** A second sufficiency-style call fired only when `generate_response` was the last tool call. Validates the draft answer against the evidence.
6. **Range-read hint injection.** Workspace-derived hints appended to `read_chunks` tool results when the read came up short.
7. **Shortcut paths.** When sufficiency says `:finalize` and no `generate_response` has fired this turn, skip the next LLM reasoning turn and call synthesis directly.
8. **Phase tracking and exhaustion.** `:default`/`:finalize`/`:clarify` toggles which tools the LLM sees; max-iterations triggers a compact-window fallback LLM call.

Of these, **only #2 and #4 are naturally graph-like** today. The rest depend on threaded mutable state, dynamic dispatch on LLM output, and conditional early termination — none of which the runner expresses.

## Proposed primitives

Five primitives, ordered by independence (earlier ones don't depend on later ones).

### 1. `:sub-graph` step

A step that runs another graph and returns its outputs. Different from `:foreach` (which runs a single inner skill per element); `:sub-graph` runs a whole graph once.

```clojure
{:id :one-iteration
 :sub-graph {:graph-id :builtin/agent-iteration
             :inputs {:messages [:prev-iteration :messages]
                      :workspace [:prev-iteration :workspace]}}
 :collect-as :iteration-result}
```

**Rationale:** the agent's per-iteration sequence (`llm-call → tool-dispatch → sufficiency-gate → maybe-validate-response → update-workspace`) is a small graph in its own right. Treating it as a sub-graph keeps the per-iteration logic composable while keeping the outer loop simple.

**Validation:** the referenced `:graph-id` must be registered. The sub-graph's declared `:inputs` must be supplied; its declared `:outputs` are the step's output map.

### 2. `:loop` step

Repeat an inner step (regular or sub-graph) until a break condition is met or a max-iteration cap is reached.

```clojure
{:id :react
 :loop {:max-iterations 10
        :until-output :finalized?      ; iteration is "done" when this output is truthy
        :iteration-as :iter            ; (optional) binds :$iter as the previous-iteration's outputs
        :iteration-index-as :i}        ; (optional) binds :$i as the current iteration number
 :do <single skill OR sub-graph>
 :collect-as :iterations
 :on-error :fail}                      ; :fail | :continue (treat error iteration as :finalized? true)
```

Differences from `:foreach`:

- `:foreach` operates over a **known finite collection**. `:loop` operates until a **break condition**.
- `:foreach` cannot reference the previous iteration. `:loop` exposes the previous iteration's full outputs via `:$iter`, so each iteration can read prior workspace state.
- `:loop` has `:max-iterations` as a hard cap; `:foreach`'s length is the collection's length.

**Rationale:** the ReAct loop's outer structure IS a `:loop`. Today's `loop`/`recur` in `agentic-loop` is the shape we want to express.

**Output:** like `:foreach`, the collected output is a vector of per-iteration outputs maps. The final value of `:iterations` is the full iteration trace; the **last** iteration's outputs map is also surfaced at the step's top level (so downstream steps can reference `[:react :response]` for the finalized answer without indexing into the vector).

**Validation:** `:until-output` must be a keyword pointing at an output key of the inner step. `:max-iterations` is required (no unbounded loops). Forward-reference rules apply — the inner step can reference `:$iter` (previous iteration's outputs scope) and outer graph inputs, but not later steps.

### 3. `:select` step

Choose one of several inner steps based on a condition. Conceptually a `cond` expression as a graph primitive.

```clojure
{:id :next-turn
 :select {:on [:gate :status]}
 :branches {:sufficient {:do <synthesize-step>}
            :insufficient {:do <continue-reasoning-step>}
            :default {:do <continue-reasoning-step>}}}
```

**Rationale:** the agent's phase switching (`:default`/`:finalize`/`:clarify`), the shortcut from sufficiency to synthesis, and the response-validation retry path are all `cond`-shaped. Without `:select`, every per-iteration sub-graph has to handle every branch unconditionally and then have the synthesis step decide whether to emit (or no-op).

**Validation:** the `:on` ref must resolve to a comparable value (keyword or string). `:branches` must contain a `:default`. Each branch's `:do` is a regular step or sub-graph.

**Output:** the selected branch's output map. Trace records which branch fired.

### 4. Workspace state (read-write graph variables)

The hardest primitive. Today's `!workspace` is an atom mutated across iterations; in a graph, that's per-iteration state that needs to thread explicitly.

Two options:

**Option A: Explicit state input/output.** Each iteration's inner step takes `:workspace-in` as input and emits `:workspace-out` as output. The `:loop` step special-cases workspace: `:workspace-in` for iteration N is `:workspace-out` from iteration N-1; the initial iteration gets the graph's `:$initial-workspace` input. Pure-data workspace; no mutation.

```clojure
{:id :one-iteration
 :sub-graph {:graph-id :builtin/agent-iteration
             :inputs {:messages [:prev-iteration :messages]
                      :workspace-in [:prev-iteration :workspace-out]
                      :tools :$tools}}}
```

Pros: pure, traceable. Cons: every step in the inner graph that touches workspace state has to plumb the workspace map through its inputs and outputs even if it only reads/writes a single key. Lots of boilerplate.

**Option B: Step-level state scope.** Declare a `:state` map at the graph level. Steps can declare `:reads-state [:keys]` and `:writes-state {:key fn}`. The runner threads state across steps without it being part of the input/output map shape.

```clojure
;; Graph-level
{:state-keys [:iteration-history :sufficiency-decisions :stage-timings ...]}
;; Per-step
{:id :tool-dispatch
 :skill :builtin/agent-tool-call
 :inputs {...}
 :reads-state #{:current-iteration}
 :writes-state {:iteration-history conj-tool-results
                :stage-timings into}}
```

Pros: less plumbing. Cons: new mechanism, less explicit, harder to reason about state flow.

**Recommendation: Option A.** Pure data flow is consistent with the rest of the graph runner. The boilerplate cost is real but bounded — `:builtin/agent-iteration` is one sub-graph; once the sub-graph plumbs workspace once, it's done.

### 5. Dynamic-dispatch step (`:dispatch-by-name`)

Per-iteration tool calls are dynamic — the LLM picks the tool name at runtime. Today this is `tools/execute-tool-call` doing string-matched dispatch over a registry of tool handlers.

```clojure
{:id :execute-tools
 :foreach {:over [:llm :tool-calls]}
 :do {:dispatch-by-name {:name [:$item :tool-name]
                         :args [:$item :args]
                         :registry :$tool-registry}}
 :collect-as :tool-results}
```

**Rationale:** the agent's tool registry IS already a dispatch map. The graph step that fans out tool calls needs to: (a) loop over LLM-emitted tool_calls (`:foreach` handles this), (b) dispatch each by string name to one of N registered handlers. That's `:dispatch-by-name`.

**Validation:** `:registry` ref must resolve to a map of `name → skill-id`. Each `:name` value resolved at runtime must appear in `:registry`. An unrecognised name is an error (vs `:foreach :on-error :default` for transient failures).

**Output:** the dispatched skill's output map. The trace records which skill-id was dispatched per iteration.

**Alternative considered:** make every tool a registered skill and have the LLM emit `skill-id`. Cleaner long-term but blows up the tool-name surface for the LLM (tools are LLM-facing UX, not internal API). Defer.

## How the ReAct loop maps onto these primitives

```clojure
;; Outer graph: agent-rag
{:id :builtin/agent-rag
 :inputs [:user-query :tools :max-iterations :system-prompt ...]
 :outputs [:response :trace]
 :steps
 [{:id :setup
   :skill :builtin/agent-setup            ; builds initial messages + initial workspace
   :inputs {:query :$user-query
            :system-prompt :$system-prompt}}
  {:id :react
   :loop {:max-iterations :$max-iterations
          :until-output :finalized?
          :iteration-as :iter}
   :do {:sub-graph {:graph-id :builtin/agent-iteration
                    :inputs {:messages-in [:$iter :messages-out]
                             :workspace-in [:$iter :workspace-out]
                             :tools :$tools
                             :phase [:$iter :next-phase]}}}
   :collect-as :iterations}
  {:id :finalize
   :skill :builtin/agent-finalize         ; pulls response from last iter or runs fallback
   :inputs {:iterations [:react :iterations]
            :exhausted? [:react :exhausted?]}}]}

;; Inner graph: agent-iteration
{:id :builtin/agent-iteration
 :inputs [:messages-in :workspace-in :tools :phase]
 :outputs [:messages-out :workspace-out :next-phase :finalized?]
 :steps
 [{:id :llm
   :skill :builtin/agent-llm-call
   :inputs {:messages :$messages-in
            :tools :$tools
            :phase :$phase}}
  {:id :tools
   :foreach {:over [:llm :tool-calls]
             :as :tool-call}
   :do {:skill :builtin/agent-tool-call
        :inputs {:tool-name [:$tool-call :name]
                 :args [:$tool-call :args]
                 :workspace [:llm :workspace-after-llm]}}
   :collect-as :tool-results
   :on-error :default}
  {:id :merge-workspace
   :skill :builtin/agent-merge-tool-results
   :inputs {:workspace [:llm :workspace-after-llm]
            :tool-results [:tools :tool-results]}}
  {:id :gate
   :skill :builtin/agent-sufficiency-gate
   :inputs {:workspace [:merge-workspace :workspace]
            :query :$user-query}}
  {:id :route
   :select {:on [:gate :suggested-strategy]}
   :branches {:finalize {:do {:skill :builtin/agent-finalize-this-turn
                              :inputs {:workspace [:merge-workspace :workspace]}}}
              :clarify {:do {:skill :builtin/agent-clarify
                             :inputs {:gate [:gate :decision]}}}
              :default {:do {:skill :builtin/agent-continue
                             :inputs {:messages [:llm :messages]
                                      :tool-results [:tools :tool-results]
                                      :gate-hint [:gate :hint]}}}}}]}
```

Every primitive above maps to a recognizable concern in `agentic-loop`. The imperative state machine becomes a flat list of sub-skills + composition primitives.

## Migration: five phases

### Phase 2.0 — Trace unification (lightweight, ship first)

**Goal:** agent-trace files and graph-trace files share a format, so downstream tooling (and human readers) can consume both.

- Refactor `digdir.skills.builtin.agent.core/write-trace-file!` to delegate to `digdir.skills.graph.trace/format-trace`, mapping the agent's iteration-history onto a synthesized "graph with one step per iteration" structure.
- No behavioural change. Trace format becomes a documented contract.
- Cost: ~1 day. Risk: low. Reverts cleanly.

Stop point: trace consumers already win. Decide whether to continue.

### Phase 2.1 — Lift tool-call dispatch to a skill

**Goal:** each tool dispatch is a standard skill execution, not a thin imperative wrapper.

- Register a `:builtin/agent-tool-call` skill whose `:execute` is today's `tools/execute-tool-call` body.
- Replace the inline `mapv` over `tool_calls` in `agentic-loop` with calls to `skills/execute-skill :builtin/agent-tool-call`.
- Per-tool stage-timings unchanged; just emitted by the standard skill path.
- Cost: ~1 day. Risk: low (small surface area).

Stop point: tool-call observability now matches every other skill. Decide whether to continue.

### Phase 2.2 — Add `:sub-graph` primitive to the runner

**Goal:** runner can execute a graph as a single step.

- Schema: new `SubGraphStep` variant with `:sub-graph {:graph-id :inputs}`. Discriminated like `:foreach`.
- Runner: `execute-sub-graph-step` resolves the graph by id from the registry, builds child inputs from the parent's resolved inputs, runs `run-graph`, returns the sub-graph's `:outputs` as the step's outputs.
- Validation: the sub-graph's declared inputs must be wired by the parent; cycles between parent and sub-graph are detected.
- Trace: sub-graph traces nest under their parent's trace (the step's `[outputs]` includes the child trace's compact summary; the full child trace is written as a separate file linked by id).
- Cost: ~2–3 days. Risk: medium — graph composition adds a real new surface, especially for trace rendering.

Stop point: custom skill-graph demos can already reuse a sub-graph (e.g., a shared retrieval+rerank sub-pipeline). Decide whether to continue.

### Phase 2.3 — Add `:loop` primitive to the runner

**Goal:** runner can express bounded iteration with a break condition.

- Schema: `LoopStep` with `:loop {:max-iterations :until-output :iteration-as}`. Inner step is regular OR sub-graph.
- Runner: `execute-loop-step` runs the inner step in a Clojure `loop`, threading the previous iteration's outputs as `:$iter`. Break on `:until-output` truthy or `:max-iterations` exhausted. Records `:exhausted?` in the step's metadata.
- Trace: per-iteration block with timing + the configured `:until-output` value at each step.
- Cost: ~2 days. Risk: medium — the break-condition contract needs careful spec'ing (when is `:until-output` evaluated? before or after the iteration's effects are visible?).

Stop point: `:foreach` + `:loop` + `:sub-graph` cover the runner-side primitives. Decide whether to actually rewrite the agent loop, or hold this primitive set for future scenarios (e.g., a loop-until-budget-spent demo) and keep the agent imperative.

### Phase 2.4 — Workspace as pure-data threading + `:select` + `:dispatch-by-name`

**Goal:** the inner per-iteration graph can fully express the agent's per-turn logic.

- Workspace becomes part of the per-iteration sub-graph's inputs and outputs (Option A above). The current atom-mutating code in `digdir.skills.builtin.agent.workspace` becomes a set of pure functions that take and return workspace maps.
- `:select` step type with a `:on` condition + named `:branches` + required `:default`.
- `:dispatch-by-name` step type that takes a name + args + a registry map and runs the matching skill.
- Cost: ~5–7 days. Risk: high — workspace plumbing touches many call sites; pure-data refactor is a real lift; `:dispatch-by-name` is a new dynamic-dispatch surface that needs careful schema validation.

Stop point: every agent semantic is now expressible in the graph. `:builtin/agent`'s `:execute` is a `run-graph` invocation.

### Phase 2.5 — Cut `:builtin/agent` over to the graph implementation

**Goal:** the imperative `agentic-loop` is gone; `:builtin/agent`'s `:execute` calls `run-graph :builtin/agent-rag`.

- Register the inner agent-iteration graph and the outer agent-rag graph at `digdir.skills.init`.
- `:execute` of `:builtin/agent` becomes `(run-graph (registry/get-graph :builtin/agent-rag) inputs opts)`.
- Eval suite must pass with zero behavioural drift.
- Cost: ~3–5 days, mostly running eval suites and chasing edge cases.
- Risk: high — this is the cutover. Every subtle ordering nuance in `agentic-loop` (shortcut paths, hint-injection order, exhaustion handling) has to be preserved.

Stop point: done. Agent and custom graphs run through one path.

## Non-goals

- **Don't change agent semantics.** The eval suite is the contract. Any behavioural difference is a bug, even if the graph-shaped version is "more elegant."
- **Don't merge the sufficiency gate and response validation into a single primitive.** They serve different purposes (pre-generate gating vs post-generate validation), and a single `:gate` step would obscure that.
- **Don't promise parallel iteration of the ReAct loop.** Each iteration depends on the previous one; the loop is inherently sequential. Parallelism is for `:foreach` over independent items, not for `:loop`.
- **Don't optimize.** This is a refactor for observability and composability, not for performance. If it makes the agent slower, that's an acceptable trade-off as long as wall-clock per iteration doesn't regress materially.

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Workspace pure-data refactor introduces subtle ordering bugs | high | high | Phase 2.4 lands behind a feature flag; eval suite gates the cutover |
| `:loop`'s break-condition contract is ambiguous (pre/post-iteration eval) | medium | medium | Spec'd before implementation; runner unit tests cover both readings |
| Sub-graph traces nest poorly; humans can't read them | medium | low | Trace renders compact summary inline + full sub-graph trace as separate linked file (Phase 2.2) |
| `:dispatch-by-name` is a generic primitive but agent is the only consumer | low | low | OK — primitives can be agent-only until a second consumer shows up |
| Performance regression on agent runs | medium | medium | Benchmark suite before/after each phase; if regressed >10%, profile and decide whether to revert |
| Migration takes longer than estimated | high | low | Each phase is independent and ships its own value; can stop after 2.0/2.1 if budget runs short |

## Open questions

1. **Should `:until-output` be evaluated before or after each iteration?** Two readings: "evaluate the condition first, then if true skip the iteration" (pre-test, semantics like `while`) vs "run the iteration, then check" (post-test, semantics like `do-while`). The agent loop is post-test — sufficiency is evaluated after the iteration runs. Recommend: post-test by default, with optional `:while-output` for pre-test.

2. **How does `:dispatch-by-name` interact with `:required-services` on the dispatched skill?** Each dispatched skill has its own services contract. The runner's existing `:required-services` check happens at step-execution time — that should "just work" but needs validation in tests.

3. **Should the per-iteration sub-graph have its own trace file or nest inline?** Argument for nesting: the agent's iterations are tightly coupled. Argument for separate files: ten iterations × multi-step sub-graph = unreadable single file. Recommend: configurable per-graph, default nest-inline up to 5 iterations, separate-file above that.

4. **Does `:builtin/agent` keep its current registered shape after Phase 2.5?** Yes — it stays a registered skill with the same id and metadata. Its `:execute` just delegates to `run-graph`. The graph is registered alongside under a different id (`:builtin/agent-rag-graph` or similar).

5. **What about agent skills that override the system prompt?** S7's authoring agent customizes `:instructions`, which become the system message. That's a graph input — no change. The custom prompt flows in as `:$system-prompt` exactly as today.

6. **The `:condition` field on regular steps already exists for skip-this-step logic.** Should `:select` replace it, or coexist? Recommend: keep `:condition` for the simple "skip if absent/falsy" case; `:select` for explicit multi-branch dispatch. They're not the same.

## What this plan deliberately doesn't try to answer

- **What if we don't migrate?** If trace unification (Phase 2.0) lands and that's "enough," the rest is technical debt rather than functional debt. Acceptable outcome.
- **Could the agent register its imperative loop as a custom step type?** Yes — `:builtin/agent-iteration` could be a single step that internally runs the imperative loop. That's essentially Phase 2.0 + 2.1. The full graph-shaped version (2.4 / 2.5) is an aspirational endpoint, not a requirement.
- **Does this prevent ever changing the ReAct semantics?** No — the graph representation is a refactor target. Semantic changes (e.g., adding tool-call batching, reordering the gate, etc.) can land before or after, treated independently.

## Recommended next step

Ship Phase 2.0 (trace unification). It's small, reversible, and immediately useful — the next time someone reads an agent trace next to a custom skill-graph trace, they don't have to mentally translate. Decide whether to continue based on the resulting trace-format experience.

Phase 2.1 (tool-call as a skill) is the natural follow-up if 2.0 lands cleanly: another low-risk step, and the per-tool observability win is real.

Stop after 2.1 unless a concrete scenario forces 2.2+. The runner primitives (`:sub-graph`, `:loop`, `:select`, `:dispatch-by-name`) are useful in their own right, but each has a real implementation cost and a real maintenance surface. Wait for a second consumer before paying it.

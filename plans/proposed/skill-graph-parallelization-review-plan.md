# Skill Graph Parallelization Review And Recommendation Plan

## Goal

Assess whether the skill graph and execution infrastructure should support parallel execution, evaluate how much the current implementation would benefit, and recommend a staged path forward that improves latency without introducing nondeterministic behavior or unsafe shared-state concurrency.

## Executive Assessment

Parallel execution is worth pursuing, but only with a narrower and more disciplined scope than "make the graph engine concurrent."

The codebase already contains most of the architectural signals that parallel execution was expected eventually:

- `server/src/digdir/skills/graph/schema.clj` models graphs as DAGs with explicit step references.
- `server/src/digdir/skills/graph/optimizer.clj` already computes execution levels, parallelization factor, and critical path.
- `server/src/digdir/skills/graph/runner.clj` still executes the graph strictly sequentially in topological order.

That means the concept is not speculative. The graph abstraction already assumes dependency-aware orchestration. The current gap is that execution has not caught up with the optimizer model.

However, there is an equally important constraint: the main built-in latency-sensitive path, `:builtin/agent-rag`, is currently a single graph step that hides all of its internal work inside the agent loop. Graph-level parallelism by itself will therefore not materially accelerate the default agent flow unless either:

- the agent is decomposed into multiple graph-visible steps, or
- some of the agent's internal tool and workspace model is redesigned for safe parallel execution.

So the right conclusion is:

1. Graph-level parallel execution is a sound architectural improvement.
2. It should be implemented first for independent DAG branches and side-branch work.
3. It should not be sold internally as the primary fix for current agent latency unless the agent path is also restructured.

## Concept Review

### What parallel execution can help

Parallel execution is a good fit when the graph contains sibling steps whose inputs are already resolved and whose outputs are not needed by one another. In this model, latency is reduced by collapsing the wall-clock time of a level toward the slowest step in that level instead of the sum of all step durations.

This is particularly attractive for:

- parallel retrieval branches
- leaf summarization or classification branches
- dual-path retrieval strategies such as filtered plus broad-recall search
- fan-out evidence gathering where results are merged later
- independent enrichment steps whose outputs are only consumed by a later synthesis or verification step

### What parallel execution does not automatically help

Parallelism does not improve latency if the graph is already a linear chain or if the work is dominated by a single opaque step.

That applies to much of the current built-in graph inventory:

- `simple-qa`: `plan -> retrieve -> rerank -> generate`
- `research-assistant`: `plan -> retrieve -> rerank -> generate`
- `retrieve-only`: `plan -> retrieve -> rerank`
- `fact-checker`: `retrieve -> rerank -> verify`
- `agent-rag`: one `:builtin/agent` step

These shapes are almost entirely serial. Even a perfect parallel runner would have little to do on these graphs as written.

### The summarize intuition

The intuition that `summarize` may be a good parallel candidate is correct in principle.

If a summarization step:

- consumes input that is already available,
- does not gate later retrieval or ranking decisions, and
- only contributes a side output or a later mergeable artifact,

then it is a strong candidate for parallel execution.

But that is mostly a future opportunity rather than a large immediate win in the current built-in graphs. The built-in templates do not currently include `:builtin/summarization`, even though the graph builder exposes summarization as an available skill. So summary parallelism is realistic for generated graphs or future templates, but not yet a major production path.

## Current Implementation Review

### 1. Graph semantics already support parallel planning

`server/src/digdir/skills/graph/schema.clj` enforces a valid DAG:

- inputs can come from graph inputs or prior step outputs
- forward references are rejected
- cycles are rejected

This is a strong foundation for concurrency because dependency resolution is already explicit and validated.

### 2. The optimizer already computes execution levels

`server/src/digdir/skills/graph/optimizer.clj` includes:

- `compute-execution-levels`
- `get-parallelization-factor`
- `compute-critical-path`
- `create-execution-plan`

This is the clearest sign that the intended execution model was always broader than strict sequential topological traversal. The runner does not need a new theory of graph parallelism. It mostly needs to start using the dependency analysis that already exists.

### 3. The runner remains sequential

`server/src/digdir/skills/graph/runner.clj` currently:

- topologically sorts the graph
- resolves inputs from completed prior steps
- executes exactly one step at a time
- appends ordered progress events
- records step and stage timings after each sequential step

This gives predictable behavior and simple failure semantics, but it also means the runtime currently ignores any parallel structure the optimizer can identify.

`run-graph-async` does not change that. It wraps the synchronous run in a Missionary task, but the graph itself still executes one step after another.

### 4. Skill execution is step-local and synchronous

`server/src/digdir/rag/skills/core.clj` executes one skill at a time and records `:duration-ms` for successful results.

This is useful because:

- the per-skill execution boundary is already clear
- stage timing data can survive a parallel runner

But it also means there is no existing cross-step scheduling, cancellation, or concurrency-limiting layer.

### 5. The built-in graph inventory is mostly linear

`server/src/digdir/skills/templates/builtin.clj` is the strongest argument against overselling graph parallelization as an immediate latency fix.

The built-in templates are almost all chains, not DAGs with useful breadth. `:builtin/agent-rag` is especially important: it collapses the dominant orchestration path into one graph step, so graph-level concurrency cannot see inside it.

### 6. There is at least one concrete "parallelism gap" today

`server/src/digdir/skills/builtin/multi_retrieval.clj` describes itself as executing multiple queries in parallel, but the implementation currently uses `mapv` over the query list and runs each query sequentially.

This matters for two reasons:

- it is a concrete place where the current system's concurrency story and implementation diverge
- it is likely a higher-ROI early target than a full runner rewrite, because the skill already has fan-out semantics

### 7. The agent path is not a safe first concurrency target

The agent loop and workspace code show pervasive shared mutable state:

- `server/src/digdir/skills/builtin/agent/workspace.clj`
- `server/src/digdir/skills/builtin/agent/tools.clj`
- `server/src/digdir/skills/builtin/agent/loop.clj`

The workspace stores and updates:

- chunks
- search history
- read history
- budget usage
- reranked chunks
- context docs
- sufficiency decisions
- stage timings
- generated responses and citations

Multiple tool calls racing against this shared workspace would make ordering, budgeting, and trace interpretation substantially harder. The current design is intentionally stateful and stepwise. That is not compatible with casual parallel tool execution.

## Expected Benefits By Area

### High-confidence opportunities

#### Parallel same-level graph execution

If a graph contains independent sibling steps, a bounded level-based runner should produce real wall-clock savings with relatively contained risk.

#### True multi-retrieval fan-out

If multiple queries are intended to execute independently, making `:builtin/multi-retrieval` genuinely concurrent is a direct and conceptually clean optimization.

#### Side-branch summarization and enrichment

Independent summarize or extract steps that are not on the critical path can be overlapped with retrieval or post-processing work.

### Lower-confidence or lower-impact opportunities

#### Existing built-in graphs without redesign

Because the built-in graphs are mostly chains, simply switching the runner to a parallel scheduler may yield little improvement unless the graph definitions themselves become broader.

#### Agent-internal tool parallelism

This could eventually matter, but it currently has the worst risk profile because it crosses tool execution, mutable workspace state, sufficiency gating, and budgeting.

## Constraints And Risks

### Determinism

The current runner naturally emits ordered progress events. `server/test/digdir/skills/graph/runner_test.clj` already asserts ordered lifecycle events.

In a parallel runner, event emission order becomes a design choice rather than a free property. If that is not handled deliberately, debugging and tests will become noisy and brittle.

Recommended rule:

- preserve deterministic declaration order for planning and result presentation
- allow actual execution overlap underneath
- explicitly define how progress events are ordered within and across execution levels

### Error semantics

The current `:on-error` behavior is simple in a sequential loop:

- `:fail` aborts
- `:default` substitutes empty success output
- `:skip` allows continuation

With parallel siblings, this gets harder. If one sibling fails while another is already in flight, the runtime must define whether it:

- waits for in-flight siblings to finish,
- cancels siblings,
- or lets level execution complete before applying error policy

This should be decided before implementation, not discovered through edge cases.

### Shared resource pressure

Parallel execution can worsen p95 instead of improving it if it overloads:

- LLM backends
- Typesense
- rerank services
- dataset-specific downstream APIs

So any runner parallelism should be:

- bounded
- configurable
- measurable in traces and metrics

### Output merge semantics

When multiple siblings finish at different times, the runtime still needs deterministic final maps for:

- `:step-results`
- `:step-timings`
- `:stage-timings`
- final graph outputs

The cleanest rule is to keep logical ordering based on execution level and graph declaration order, regardless of completion timing.

### Cancellation and budgeting

The current execution path has no mature shared cancellation or budget arbitration model across concurrent siblings.

That is manageable for a first runner version if we keep the model simple:

- bounded concurrency
- level-by-level scheduling
- no speculative cross-level execution
- conservative failure handling

### Mismatch with the current latency hotspot

This is the most important strategic caveat.

If the real latency pain is mostly in the agent loop's search/read/rerank/synthesis flow, graph-level parallelism alone will not solve it because the default agent graph hides those stages in one `:builtin/agent` step.

That does not make graph parallelization a bad idea. It means the investment should be framed honestly:

- good platform improvement
- useful for future DAG breadth
- not by itself the main fix for agent latency

## Recommendation

Proceed, but in phases and with narrow scope.

The recommended direction is:

1. implement bounded, dependency-aware parallel execution in the graph runner
2. keep merge semantics deterministic
3. leave agent-internal execution sequential for now
4. target concrete fan-out wins such as `multi-retrieval`
5. redesign graph shapes where we actually want parallelism to matter

This is more credible than trying to parallelize the deepest shared-state path first.

## Proposed Rollout

### Phase 0: Measure actual available parallelism

Before changing execution semantics broadly, inspect the registered graphs and generated graphs to quantify:

- execution levels per graph
- parallelization factor
- critical path length
- percentage of live runs that would benefit materially

Use `optimizer/create-execution-plan` to produce this inventory.

This phase is important because it will likely confirm that:

- the built-in graphs are mostly serial
- agent-rag offers no graph-level parallelism
- the biggest wins require either broader graphs or skill-internal fan-out

### Phase 1: Parallelize the obvious fan-out skill first

Make `server/src/digdir/skills/builtin/multi_retrieval.clj` genuinely concurrent with:

- bounded concurrency
- deterministic merged ordering
- timing and attribution preserved per query
- explicit fallback on partial query failure

This is a good first milestone because the semantics already imply independent execution.

### Phase 2: Add a bounded parallel graph runner

Extend `server/src/digdir/skills/graph/runner.clj` to execute one dependency level at a time, with sibling steps within a level allowed to run concurrently.

Design requirements:

- concurrency limit in config
- deterministic result and timing ordering
- explicit level-level progress events
- well-defined error policy for sibling failures
- compatibility mode or feature flag for rollback

The first version should stay simple:

- no speculative execution
- no cross-level pipelining
- no special-case priority scheduling

### Phase 3: Make the graphs worth parallelizing

Add or revise graph shapes that expose independent branches, for example:

- filtered retrieval branch plus unfiltered recall branch
- summarize side-branch alongside retrieval or verification
- parallel evidence gathering from multiple query families
- parallel chunk enrichment before a final merge/synthesis step

Without this phase, the runner upgrade will mostly improve future capability rather than current latency.

### Phase 4: Add observability for concurrency behavior

The recent stage timing work should be extended to cover:

- level duration
- queued versus running sibling counts
- concurrency cap used
- cancellation or short-circuit decisions
- per-level failure/default/skip summary

This is necessary to prove that parallel execution is helping rather than just increasing backend pressure.

### Phase 5: Re-evaluate the agent architecture separately

Only after the graph runner and graph shapes are in place should we consider whether the agent path should be:

- decomposed into graph-visible stages, or
- selectively refactored for safe internal concurrency

This should be a separate design effort. The current agent workspace model is too stateful to treat as a casual follow-on to graph parallelism.

## Concrete Near-Term Recommendations

### Recommendation 1

Do not start by parallelizing `:builtin/agent` tool calls.

The shared workspace, budget accounting, sufficiency gating, and trace model make that the highest-risk place to introduce concurrency.

### Recommendation 2

Implement true concurrency in `:builtin/multi-retrieval` before or alongside a graph runner upgrade.

This is likely the most direct near-term latency win with the lowest semantic ambiguity.

### Recommendation 3

Add a parallel runner behind a feature flag and default it off initially.

This keeps rollout reversible while tests, traces, and backend load behavior mature.

### Recommendation 4

Adopt deterministic ordering rules up front.

Specifically:

- step result maps should respect graph declaration order
- stage timings should be reported in level order, then declaration order
- progress events should include enough data to explain overlap without becoming nondeterministic noise

### Recommendation 5

Treat graph redesign as part of the performance work, not as a separate optional enhancement.

If the graphs remain linear, the infrastructure work will be correct but underutilized.

### Recommendation 6

Use the new timing data to decide whether a later agent redesign is justified.

If traces continue to show that the main latency is inside agent-internal rerank and synthesis stages, that is the signal to consider decomposing the agent path rather than assuming graph-level parallelism is sufficient.

## Suggested Acceptance Criteria

The parallelization effort should not be considered successful unless it demonstrates all of the following:

- measurable end-to-end latency improvement on graphs with real sibling breadth
- no material regression in backend error rates or p95/p99 stability
- deterministic trace and progress semantics
- clear failure behavior for sibling steps
- preserved output correctness versus sequential execution
- evidence that the affected production paths actually expose useful parallelism

## Final Recommendation

Parallel graph execution is a good infrastructure investment, but it should be pursued as a bounded DAG scheduler project, not as a blanket concurrency push across the whole skill system.

The practical order is:

1. quantify available graph breadth
2. fix obvious fan-out gaps such as `multi-retrieval`
3. add bounded parallel execution by dependency level
4. redesign graphs to expose parallel work
5. only then revisit whether the agent path should be broken open or refactored

That path matches the codebase as it exists today, addresses the main technical risks directly, and avoids spending concurrency complexity where the current architecture cannot safely absorb it.

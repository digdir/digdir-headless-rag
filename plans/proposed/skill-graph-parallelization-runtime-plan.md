# Skill Graph Parallelization And Runtime Observability Plan

## Goal

Complete the skill graph execution architecture so graph-visible steps can run in parallel when dependencies allow, and add the runtime statistics needed to understand, tune, and safely expand that parallelism over time.

This track is the platform track. It is about non-opaque parallelization between graph-visible skills, not internal optimization inside a single skill.

## Executive Summary

The graph architecture already anticipates parallelism, but the runtime does not yet execute on that model.

Today:

- `server/src/digdir/skills/graph/schema.clj` validates DAG semantics
- `server/src/digdir/skills/graph/optimizer.clj` computes execution levels, critical path, and parallelization factor
- `server/src/digdir/skills/graph/runner.clj` still executes one step at a time

So this track is not about inventing a new abstraction. It is about finishing the existing one.

The strategic caveat remains important:

- the current built-in graphs are mostly linear
- `:builtin/agent-rag` is a single opaque step

That means graph-level parallelization is a necessary infrastructure improvement, but not by itself a guaranteed fix for current agent latency. The runtime work still matters because it enables:

- real DAG concurrency for future graphs
- safe non-opaque branch parallelism
- measurable scheduling behavior
- evidence-based graph design improvements

## Scope

### In scope

- dependency-aware parallel execution for graph-visible steps
- bounded concurrency by execution level
- deterministic result ordering and trace ordering
- runtime-level concurrency and critical-path statistics
- error semantics for parallel sibling execution
- rollout controls and feature flags

### Out of scope

- arbitrary concurrent execution inside opaque skills
- agent-internal workspace concurrency
- decomposition of `:builtin/agent` as part of this initial runtime implementation

## Current State

### The schema supports a valid DAG model

`server/src/digdir/skills/graph/schema.clj` already enforces:

- explicit input references
- rejection of forward references
- rejection of cycles

This means the runner can safely assume dependency information is valid.

### The optimizer already knows the parallel structure

`server/src/digdir/skills/graph/optimizer.clj` already provides:

- `compute-execution-levels`
- `get-parallelization-factor`
- `compute-critical-path`
- `create-execution-plan`

This is the correct foundation for a level-based scheduler.

### The runner ignores the optimizer's parallel model

`server/src/digdir/skills/graph/runner.clj` currently:

- topologically sorts
- executes one step
- updates state
- moves to the next step

This gives deterministic behavior but leaves all graph-visible parallelism unused.

### The built-in graph inventory is mostly serial

`server/src/digdir/skills/templates/builtin.clj` mostly defines linear chains:

- plan -> retrieve -> rerank -> generate
- retrieve -> rerank -> verify
- one-step `agent-rag`

This means the first value of this track is platform readiness and support for broader future graph shapes, not immediate dramatic wins on every built-in graph.

## Architecture Direction

The target model should be a bounded, level-based graph scheduler.

That means:

- compute execution levels from graph dependencies
- execute ready sibling steps in a level concurrently
- wait for the level to finish
- merge results deterministically
- continue to the next level

This is intentionally conservative.

The first implementation should not do:

- speculative execution
- cross-level pipelining
- dynamic work stealing
- backend-aware adaptive scheduling

Those can come later if needed.

## Key Requirements

### 1. Deterministic merge semantics

Even when sibling steps complete in arbitrary order, the runner should present:

- `:step-results`
- `:step-timings`
- `:stage-timings`
- final outputs

in a stable logical order.

Recommended logical order:

- execution level order
- graph declaration order within a level

### 2. Deterministic progress semantics

Current tests in `server/test/digdir/skills/graph/runner_test.clj` expect ordered lifecycle events.

Parallel execution should not make progress reporting chaotic. The event model should be extended explicitly rather than letting concurrent completion timing dictate user-visible ordering.

Recommended approach:

- emit a level-start event
- emit step-start events in declaration order
- allow step-complete metadata to include actual timestamps
- emit a level-complete summary after all siblings finish

### 3. Bounded concurrency

Concurrency must be limited and configurable. The scheduler should not simply run every sibling step at once.

Required controls:

- maximum sibling concurrency
- optional per-skill concurrency caps
- feature flag for parallel runner enablement

### 4. Explicit parallel failure policy

Parallel siblings require an explicit policy for:

- `:fail`
- `:default`
- `:skip`

Recommended first policy:

- let in-flight siblings in the current level complete
- collect results deterministically
- then apply failure/default semantics to decide whether the graph continues

This is simpler and safer than trying to cancel mid-level work in the first version.

## Runtime Statistics

This track should produce enough runtime data to improve the scheduler over time.

Minimum statistics:

- total graph duration
- per-level duration
- number of levels
- max theoretical parallelization factor
- realized parallelization factor
- concurrency cap used
- steps per level
- time spent on critical path estimate
- non-critical parallel work estimate
- per-skill duration summary
- sibling failure/default/skip counts

Useful derived metrics:

- estimated sequential duration
- realized wall-clock speedup
- idle time inside levels
- critical-path share of total duration

## Observability Surface

The new runtime statistics should appear in at least three places:

### Graph execution metadata

Returned directly from the runner, alongside the existing:

- `:step-timings`
- `:stage-timings`

### Playground diagnostics

The Playground should expose:

- level breakdown
- concurrency usage
- critical path summary
- speedup estimate

### Trace and progress events

The progress/event stream should make it possible to reconstruct:

- which steps ran together
- how long the level took
- whether concurrency was capped
- whether failures happened in parallel siblings

## Recommended Implementation Order

### Phase 1: Add execution-plan metadata to graph runs

Before changing execution behavior, make the runner compute and return plan metadata from `optimizer/create-execution-plan`:

- levels
- critical path
- parallelization factor

This is useful immediately and gives a baseline for later comparisons.

### Phase 2: Add level-based scheduler behind a feature flag

Introduce a parallel execution mode in `server/src/digdir/skills/graph/runner.clj` that:

- groups steps by execution level
- runs siblings concurrently with a cap
- preserves deterministic ordering in outputs

Sequential mode should remain available for rollback and comparison.

### Phase 3: Extend event and timing model

Add:

- level-start and level-complete events
- level timing summaries
- realized concurrency stats

This phase should happen together with or immediately after the scheduler change.

### Phase 4: Update tests for deterministic parallel semantics

Expand `server/test/digdir/skills/graph/runner_test.clj` to cover:

- level execution
- deterministic merge ordering
- sibling failure/default handling
- bounded concurrency behavior
- stable progress semantics

The tests should assert logical ordering, not fragile wall-clock ordering.

### Phase 5: Expose runtime statistics in the Playground

Update the diagnostics UI so parallel graph behavior is visible during development and tuning.

### Phase 6: Redesign selected graphs to exploit the scheduler

Once the runner is ready, introduce or revise graph templates that actually contain useful breadth, such as:

- filtered retrieval branch plus broad retrieval branch
- summarization or extraction side-branches
- parallel evidence gathering branches
- separate enrichment branches merged before synthesis

Without this phase, the scheduler will be technically correct but underutilized.

## Rollout Strategy

### Stage 1

Ship plan metadata and runtime statistics without changing execution semantics.

### Stage 2

Enable parallel runner in a controlled environment behind a feature flag.

### Stage 3

Benchmark representative graphs:

- sequential versus parallel wall-clock duration
- p50/p95
- backend pressure
- correctness of outputs
- progress and trace usability

### Stage 4

Enable parallel execution for selected graphs with actual sibling breadth.

### Stage 5

Use runtime data to decide where graph redesign is worth the complexity.

## Strategic Caveat

This platform track should be pursued with the correct expectation:

- it enables non-opaque parallelization
- it improves the architecture materially
- it will not automatically accelerate the default one-step `:builtin/agent-rag` graph

If the main latency hotspot remains inside the agent loop, that must be addressed through Track 1 optimizations or a later explicit agent redesign.

## Success Criteria

This track is successful if it delivers:

- correct parallel execution for graph-visible sibling steps
- deterministic outputs and progress semantics
- rich runtime statistics for tuning
- stable backend behavior under bounded concurrency
- measurable speedup on graphs that actually expose sibling breadth

## Final Recommendation

Treat this as a runtime and observability completion project, not as a speculative research effort.

The first milestone should be plan metadata and execution statistics. The second should be a bounded level-based scheduler behind a flag. Only after that should graph redesign work begin to exploit the scheduler more broadly.

# Non-Blocking Status Summarization Plan

## Goal

Ensure user-facing status summarization and other ephemeral live-progress narration never hold the core retrieval, rerank, synthesis, or agent-control sequence on the critical path.

This plan is deliberately narrower than the broader opaque-parallelism track. It treats status summarization as optional presentation work and optimizes it accordingly.

## Executive Summary

Yes, this tighter plan makes sense.

The current code suggests that most live status information is not part of the answer path:

- `server/src/digdir/skills/events.cljc` defines canonical progress events and lightweight `:result-summary` payloads.
- `server/src/digdir/playground/chat_session.cljc` projects those events into a live summary for the UI.
- `server/src/digdir/playground/diagnostics.cljc` normalizes tool-call results into compact human-readable summaries.
- `server/src/digdir/skills/builtin/summarization.clj` exists as a separate LLM summarization skill, but it is not currently part of the active agent tool set.

So the immediate design goal should not be "make summaries influence later reasoning." The immediate design goal should be:

- keep the core work moving
- make status summarization best-effort
- allow status work to run in parallel or behind the main path
- degrade cleanly to existing raw stage labels and tool-result text

In other words, this is a decoupling and scheduling plan for optional status work, not a reasoning-quality plan.

## Problem Statement

If user-facing status summarization becomes richer or more expensive, it can accidentally become a blocker for the core execution path unless we explicitly separate the two concerns.

The risk is architectural rather than purely current-state latency:

- today much of the live summary path is cheap formatting
- future status synthesis may involve more processing or even LLM work
- once status generation is treated as mandatory inline work, it can slow down the answer path for no answer-quality gain

That is exactly the kind of work that should be made non-blocking early.

## Current State

### 1. Progress events already separate execution from presentation

`server/src/digdir/skills/events.cljc` defines the canonical event stream for:

- stage changes
- tool calls
- tool results
- warnings
- agent iteration lifecycle

This is a good base because it already gives us a natural snapshot boundary for status synthesis.

### 2. Live summary is currently projection-heavy, not reasoning-heavy

`server/src/digdir/playground/chat_session.cljc` computes a UI-facing summary from the latest event and stage state.

`server/src/digdir/playground/diagnostics.cljc` derives compact tool narratives such as:

- searched N phrasings
- reranked N candidates
- generated candidate answer

This means much of the current behavior is already compatible with a non-blocking model.

### 3. Summarization exists, but is not on the active agent tool path

`server/src/digdir/skills/builtin/summarization.clj` is a real LLM-backed skill, but it is not currently exposed as a standard agent tool in `server/src/digdir/skills/builtin/agent/tools.clj`.

That means the current "status summary" concept should not be conflated with LLM summarization. The tighter plan should target the actual presentation path first.

### 4. There is at least one related example of optional summarization

`server/src/digdir/rag/synthesis.clj` includes `simplify-convo-topic`, which creates a short summary of the user's query for renaming the conversation topic.

That is another example of work that is useful but not part of the core answer path. It belongs in the same architectural category: optional summarization that must not block answer generation.

## Architecture Direction

Treat status summarization as an asynchronous sidecar fed by execution snapshots.

The sidecar should:

- consume canonical progress events or stable event snapshots
- produce a best-effort live summary artifact
- never block the main execution loop
- be safely droppable if newer execution state arrives

The main execution path should always be able to proceed with no status synthesizer result at all.

## Design Principles

### 1. Core path first

Retrieval, reading, reranking, synthesis, and control decisions must not wait for status summarization.

### 2. Status synthesis is optional

If the status sidecar is slow, fails, or is skipped, the system should fall back to:

- existing stage labels
- latest tool-call summary
- latest tool-result text

### 3. Snapshot-based only

Status summarization should run from an immutable snapshot of event or tool-result state. It must not participate in shared workspace mutation or orchestration decisions.

### 4. Last-write-wins semantics

If status generation lags behind and newer execution state exists, stale status work should be discarded rather than merged awkwardly.

### 5. Bounded effort

Status synthesis should be throttled and capped. It should not run on every micro-event without control.

## Proposed Model

### Execution path

The main path continues to emit canonical events exactly as it does today.

Examples:

- `:agent/tool-call`
- `:agent/tool-result`
- `:stage/started`
- `:stage/completed`

### Status sidecar

A separate best-effort status component:

- subscribes to execution events
- periodically snapshots recent state
- synthesizes a compact live summary
- publishes it to the UI/session state if still current

### Fallback path

If the sidecar does nothing, the UI still renders from the existing event-derived summaries already supported in:

- `server/src/digdir/playground/chat_session.cljc`
- `server/src/digdir/playground/diagnostics.cljc`

## Required Changes

### 1. Define a distinct status-summarization artifact

Introduce a stable representation for optional live-status output, separate from the core response and separate from raw execution events.

Suggested fields:

- source snapshot id or version
- generated-at timestamp
- current stage
- short summary text
- optional tool narrative
- stale? flag
- generation duration
- fallback-used? flag

### 2. Add a status snapshot boundary

The sidecar should not read moving mutable state directly while execution is ongoing. It should consume a stable snapshot, likely derived from:

- recent canonical execution events
- current stage metadata
- latest tool result summaries

This snapshot should be small and presentation-oriented.

### 3. Add non-blocking scheduling for status work

Status synthesis should run outside the critical path with:

- bounded concurrency, likely 1
- debounce or coalescing behavior
- cancellation or stale-drop semantics

Recommended first rule:

- only one status generation job may run at a time
- if a newer snapshot arrives, the older pending result is dropped on completion if stale

### 4. Make publication best-effort

Publishing a new status summary must never fail the core request.

If status generation errors:

- record telemetry
- preserve the existing fallback summary path
- continue core execution unchanged

### 5. Keep existing textual summaries as fallback

Do not replace the existing simple event-derived labels first.

They are the resilience layer if richer status synthesis is delayed, disabled, or stale.

### 6. Add explicit UI precedence rules

The UI should know which source wins:

1. fresh synthesized status summary
2. explicit event-derived tool/status summary
3. current stage label
4. generic "Working"

This avoids ambiguous status rendering.

## Scheduling Strategy

### Option A: Pure formatting sidecar

If status synthesis remains deterministic text formatting, run it asynchronously but keep it very cheap.

This is the safest first version.

### Option B: LLM-backed status synthesis sidecar

If richer live summaries are later desired, they should still run as sidecar work with:

- strict token budget
- rate limiting
- aggressive stale-drop behavior
- hard fallback to existing raw summaries

This should not be the first implementation unless there is a clear UX need.

## Observability

Track status-sidecar behavior independently from the core run.

Minimum metrics:

- status jobs started
- status jobs completed
- status jobs dropped as stale
- status job duration
- status fallback count
- status generation error count
- lag from snapshot creation to publication

Trace visibility should clearly distinguish:

- core execution timings
- optional status-sidecar timings

## Recommended Implementation Order

### Phase 1: Formalize the separation

Document in code and traces that status summarization is optional presentation work, not part of the answer path.

### Phase 2: Add snapshot and sidecar interfaces

Create:

- a compact status snapshot shape
- a compact status artifact shape
- a sidecar execution hook or helper

### Phase 3: Publish synthesized status non-blockingly

Update the chat/session projection path so synthesized status can be published opportunistically without affecting core request completion.

### Phase 4: Add stale-drop and fallback semantics

Ensure that:

- stale sidecar results are ignored
- fallback to current event-derived summaries is automatic

### Phase 5: Measure real value

Before adding any richer status generation, measure:

- whether users benefit from synthesized status over current summaries
- whether sidecar lag or drop rates are acceptable

## Rollout Strategy

### Stage 1

Introduce the sidecar abstraction with existing lightweight summary generation only.

### Stage 2

Run it in shadow mode:

- generate sidecar summaries
- do not rely on them for primary UI rendering
- compare freshness and usefulness against existing event-derived summaries

### Stage 3

If the sidecar proves useful and stable, allow it to override the fallback summary when fresh.

### Stage 4

Only then consider richer synthesis or LLM-backed status generation.

## Non-Goals

This plan does not aim to:

- improve answer quality
- feed summaries into rerank or synthesis
- change sufficiency decisions
- parallelize agent orchestration itself

It is only about keeping optional presentation work off the core critical path.

## Success Criteria

This plan is successful if:

- the core work sequence never waits on status summarization
- the UI still shows reasonable live progress when sidecar summaries are absent
- stale status jobs are safely ignored
- failures in status work do not affect core execution
- the resulting behavior is simpler to reason about than inline optional summarization

## Final Recommendation

Proceed with a separate non-blocking status-summarization track.

This is the right response if current summarization is superficial and ephemeral. Optional status work should be treated like any other presentation sidecar: useful when available, irrelevant to correctness, and never allowed to hold the main execution path.

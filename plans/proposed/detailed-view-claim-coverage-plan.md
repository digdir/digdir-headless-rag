# Detailed View Claim Coverage Plan

Status: Proposed on 2026-04-18.

## Goal

Add `:claim-coverage` details to the next-gen Playground Detailed view in [`server/src/digdir/playground/ui/observability/live_next.cljc`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/playground/ui/observability/live_next.cljc), with an intentionally narrow first scope:

- support the post-run Detailed response view
- reuse diagnostics already emitted by the agent
- render claim coverage in a compact, inspectable panel near the existing sufficiency UI

This plan is deliberately not a live-streaming observability plan. Live parity is covered below as deferred work because it requires additional execution-state plumbing that does not exist today.

## Executive Summary

This is practical and should be a small UI-focused change if scoped to the post-run Detailed view.

The important finding is that the backend already computes and returns the required data:

- the agent workspace accumulates `:claim-coverage` as reads succeed
- final agent outputs include `:claim-coverage`
- Playground diagnostics normalization preserves `:claim-coverage` even though it does not currently compact it
- the next-gen Detailed view already consumes sibling diagnostics such as `:open-evidence-gaps`, `:read-evaluations`, and `:sufficiency-decisions`

The main missing piece is in the view-model layer of `live_next.cljc`: the post-run `diagnostics->view-model` function does not currently thread `:claim-coverage` or `:evidence-plan` through to the rendering layer.

Recommended implementation shape:

1. Add `:claim-coverage` and `:evidence-plan` to the post-run view-model.
2. Introduce a compact `ClaimCoveragePanel` in `live_next.cljc`.
3. Render coverage rows by joining `:claim-coverage` against `[:evidence-plan :required-claims]` so the UI shows human-meaningful claim text, kind, and criticality rather than only claim ids.
4. Keep live Detailed view unchanged for now.

## Scope Decision

### In scope

- Post-run Detailed response view only.
- `live_next.cljc` rendering and view-model changes.
- Optional diagnostics compaction hardening for `:claim-coverage` if needed to stabilize the UI contract.
- Tests covering diagnostics normalization if compaction is added.

### Out of scope for this plan

- Live Detailed streaming support.
- New agent progress events.
- Any change to claim extraction, evidence planning, or read-signal semantics.
- Changes to the classic Detailed view.

## Current State

### 1. Claim coverage is already accumulated in agent workspace state

[`server/src/digdir/skills/builtin/agent/workspace.clj`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/builtin/agent/workspace.clj) merges support into `:claim-coverage` during `record-read-evaluation!`.

Relevant behavior:

- support is keyed by `:claim-id`
- `:support-level` is monotonic, with `:explicit` winning over later weaker support
- `:chunk-ids` accumulate over time

Current effective shape:

```clojure
{:claim-id :topic-match
 :support-level :explicit
 :chunk-ids ["c1" "c2"]}
```

### 2. Final agent results already expose claim coverage

[`server/src/digdir/skills/builtin/agent/core.clj`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/builtin/agent/core.clj) includes `:claim-coverage` in both trace output and success results.

That means post-run Playground diagnostics already have access to the data without any new backend feature work.

### 3. Diagnostics normalization compacts related structures but does not explicitly compact claim coverage

[`server/src/digdir/playground/diagnostics.cljc`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/playground/diagnostics.cljc) already compacts:

- `:evidence-plan`
- `:read-evaluations`
- `:last-read-signal`
- `:sufficiency-decisions`
- `:response-validations`

It does not currently transform `:claim-coverage`; however, it also does not remove it. So the raw map currently survives normalization and is available to the UI.

This is enough for a first implementation, but it is slightly softer than the surrounding contract because the UI is relying on passthrough rather than an explicit compacted shape.

### 4. The next-gen Detailed view is already structured around a normalized view-model

[`server/src/digdir/playground/ui/observability/live_next.cljc`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/playground/ui/observability/live_next.cljc) has two entry paths:

- `stream-view->view-model` for live execution
- `diagnostics->view-model` for post-run rendering

Today the post-run view-model already carries:

- `:latest-sufficiency`
- `:cumulative-sufficiency-decisions`
- `:shadow-sufficiency-decisions`
- `:response-validations`
- `:open-evidence-gaps`
- per-iteration events and read-signal detail

But it does not yet carry:

- `:claim-coverage`
- `:evidence-plan`

So there is currently no rendering path for claim coverage in the Detailed shell.

### 5. Live Detailed view does not currently have claim coverage available

[`server/src/digdir/playground/chat_session.cljc`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/playground/chat_session.cljc) builds the execution stream view from:

- live trace
- live stage timings
- live thinking
- aggregate tool/result counters

It does not include `:claim-coverage` or `:evidence-plan` in the live execution state. Therefore a live Detailed claim-coverage panel cannot be implemented as a pure UI tweak.

## Desired Outcome

After this work, the post-run Detailed view should show a compact summary of claim coverage that answers:

- which required claims were covered
- what support level each covered claim reached
- which chunks contributed support
- which required claims remain uncovered
- which claims are critical

The panel should fit the existing visual language of `live_next.cljc`:

- compact header row
- expandable details
- color/status cues matching the existing sufficiency and read-signal panels
- low-noise summaries first, detailed claim rows on demand

## Proposed UI Model

### Panel placement

Render claim coverage in `NextDetailedShell` immediately after `SufficiencyDigest` and before the iteration tabs.

Reasoning:

- it is run-level state, not iteration-local state
- it is closely related to sufficiency
- users should see evidence state before diving into per-iteration traces

### Panel inputs

The panel should accept:

- `:claim-coverage`
- `:evidence-plan`
- `:open-evidence-gaps`

### Derived display model

Build a small derived sequence by joining coverage against `[:evidence-plan :required-claims]`.

Suggested display row shape:

```clojure
{:claim-id :topic-match
 :text "topic"
 :kind :fact
 :critical? true
 :covered? true
 :support-level :explicit
 :chunk-ids ["c1" "c2"]}
```

Also include rows for required claims not present in `:claim-coverage`, so the panel can show missing coverage explicitly rather than only displaying covered claims.

### Summary/header behavior

Suggested headline values:

- `N / M claims covered`
- `K critical covered`
- `G open gaps`

Panel status suggestion:

- `:sufficient` when all critical required claims are covered and there are no open evidence gaps
- `:warning` when some required claims remain uncovered
- `:error` only if contradictions are later added to this panel scope

For the first implementation, it is acceptable to base status only on coverage plus open gaps and leave contradictions to the existing read-signal surfaces.

## Implementation Steps

### 1. Thread claim coverage through the post-run view-model

Update `diagnostics->view-model` in [`live_next.cljc`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/playground/ui/observability/live_next.cljc) to include:

- `:claim-coverage`
- `:evidence-plan`

Suggested fields:

```clojure
:claim-coverage (or (:claim-coverage diagnostics) {})
:evidence-plan (:evidence-plan diagnostics)
```

Do not add them to `stream-view->view-model` in this task.

### 2. Add small helpers for claim coverage display

In `live_next.cljc`, add private helpers to:

- extract `:required-claims`
- join required claims with coverage
- count covered critical claims
- format support-level labels

Keep these helpers local to the file unless multiple namespaces immediately need them.

### 3. Implement a compact `ClaimCoveragePanel`

Add a new Electric component in `live_next.cljc` with the same structural pattern used by:

- `SufficiencyDigest`
- `ReadSignalPanel`

Recommended behavior:

- collapsed by default if there is no claim/evidence-plan data
- expanded/collapsed with the same simple local atom pattern used elsewhere in the file
- header shows high-level coverage counts
- expanded body shows one row per required claim

Recommended row content:

- claim text when present, otherwise claim id
- claim id in monospace if useful
- `critical` marker when applicable
- support badge for `:explicit` or `:partial`
- chunk count and optionally the chunk ids in expanded detail

### 4. Decide whether to harden diagnostics normalization now

Preferred approach:

- add explicit compaction for `:claim-coverage` in [`diagnostics.cljc`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/playground/diagnostics.cljc)

Reasoning:

- it makes the UI contract deliberate rather than incidental
- it aligns `:claim-coverage` with the neighboring compacted evidence fields
- it reduces risk of future backend shape drift leaking into the UI

Suggested compacted shape per entry:

```clojure
{:claim-id ...
 :support-level ...
 :chunk-ids [...]}
```

If this is done, also ensure the map remains keyed by `claim-id`.

### 5. Add or update tests

At minimum:

- diagnostics normalization test proving `:claim-coverage` survives in the expected compacted form if compaction is added
- UI-adjacent pure helper tests if helper logic becomes non-trivial

Good candidate test locations:

- [`server/test/digdir/playground/diagnostics_test.clj`](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/playground/diagnostics_test.clj)

If the panel logic stays mostly presentational and helper functions are simple, manual UI verification may be sufficient for `live_next.cljc` itself.

## Detailed Code Change List

### File 1: `server/src/digdir/playground/ui/observability/live_next.cljc`

Required changes:

- extend `diagnostics->view-model`
- add claim-coverage helper fns
- add `ClaimCoveragePanel`
- render the panel from `NextDetailedShell`

Recommended insertion point:

- after `SufficiencyDigest`
- before iteration tabs/body

### File 2: `server/src/digdir/playground/diagnostics.cljc`

Optional but recommended:

- add `compact-claim-coverage`
- invoke it from `normalize-diagnostics`

### File 3: `server/test/digdir/playground/diagnostics_test.clj`

If diagnostics compaction is added:

- add a focused test covering claim coverage normalization

## Validation Plan

### Automated checks

Run the smallest relevant test surface first:

1. `clojure -M:test -m cognitect.test-runner -n digdir.playground.diagnostics-test`

If namespace-specific invocation differs in this repo, use the existing project test runner equivalent.

### Manual verification

Use a Playground run that produces:

- a non-empty `:evidence-plan`
- at least one covered claim
- at least one open evidence gap

Verify in the Detailed view that:

1. The new claim coverage panel appears in the post-run Detailed response.
2. Covered claims show support level and chunk count.
3. Uncovered required claims are still visible.
4. Critical claims are visually distinguishable.
5. The existing sufficiency and iteration UIs are unaffected.

### Regression checks

Confirm that:

- runs with no `:claim-coverage` still render cleanly
- runs with `:claim-coverage` but no `:evidence-plan` degrade gracefully by showing claim ids only
- live Detailed view behavior is unchanged

## Risks and Tradeoffs

### 1. Claim ids may be too opaque without evidence-plan text

This is why the panel should prefer joining against `:required-claims` rather than displaying raw coverage entries only.

### 2. UI contract is slightly soft if diagnostics compaction is skipped

Using passthrough `:claim-coverage` is acceptable for a first patch, but explicit compaction is cleaner and safer.

### 3. Coverage is accumulated across reads, not grouped by iteration

That is appropriate for the planned run-level panel, but it means the panel should not imply that each claim was first resolved in the currently selected iteration.

### 4. Live parity will tempt scope creep

Do not expand this task into live streaming support unless explicitly requested. Live support needs new execution-state plumbing and possibly new progress-event semantics.

## Deferred Follow-Up: Live Detailed Support

If live Detailed claim-coverage visibility is later required, create a separate task to:

1. emit `:claim-coverage`, `:evidence-plan`, and optionally `:open-evidence-gaps` into live execution state
2. thread those fields through `execution-stream-view` in [`chat_session.cljc`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/playground/chat_session.cljc)
3. extend `stream-view->view-model` in [`live_next.cljc`](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/playground/ui/observability/live_next.cljc)
4. decide update cadence and stale-state semantics for partially accumulated coverage during a running turn

This is intentionally deferred because it is no longer a self-contained UI change.

## Acceptance Criteria

This plan is complete when all of the following are true:

1. Post-run Detailed view shows a claim coverage panel when claim/evidence data is present.
2. The panel clearly distinguishes covered vs uncovered required claims.
3. Covered claims show support level and chunk count or chunk ids.
4. The implementation does not alter live Detailed behavior.
5. Diagnostics normalization either explicitly compacts `:claim-coverage` or the implementation documents why passthrough is acceptable.

# Skills Event DRY Implementation

## Goal

Reduce duplication in how the skills system emits, translates, and projects execution events while preserving current Playground behavior.

## Scope

- skill graph runner progress events
- agent skill progress events
- Playground execution event normalization and storage
- shared stage metadata used by live status projection

## Constraints

- Do not preserve the current Playground execution event contract by default.
- Prefer replacing the current event shape if that materially simplifies the codebase.
- Keep the refactor incremental so tests can verify behavior after each step.
- Favor shared constructors and translators over broad rewrites.

## Implementation Steps

### 1. Create shared event utility namespace

Status: Completed

Tasks:

- Add a namespace for common event helpers used by skills and Playground.
- Move the duplicated safe progress-emission helper there.
- Add canonical constructors for raw progress payloads where they reduce repetition.
- Add unit tests for safe emission and basic payload shaping.

Verification:

- graph runner and agent can depend on the same helper without behavior change
- tests cover callback failure swallowing and payload passthrough

Progress:

- Added `digdir.skills.events` as the initial shared execution event namespace.
- Moved the duplicated safe progress callback helper into the shared namespace.
- Added canonical constructors for graph, step, and agent progress payloads.
- Added focused tests for callback forwarding, error swallowing, and payload construction.

### 2. Refactor skill emitters to use shared constructors

Status: Completed

Tasks:

- Update the graph runner to call shared constructors for `:step/*` and `:graph/*` events.
- Update the agent skill to call shared constructors for `:agent/*` events.
- Remove the duplicate local `emit-progress!` helpers.

Verification:

- existing runner and agent tests still pass
- emitted payloads remain stable for current consumers

Progress:

- Updated the graph runner to use shared graph and step event constructors.
- Updated the agent skill to use shared `:agent/*` event constructors.
- Removed the duplicate local `emit-progress!` implementations from both namespaces.

### 3. Centralize raw-progress to Playground-event translation

Status: Completed

Tasks:

- Replace the current ad hoc Playground event shape with a canonical execution event model shared by skills and Playground.
- Eliminate the separate raw-progress to Playground-event adapter where possible.
- Handle currently ignored raw events like `:step/skipped`, `:step/defaulted`, and `:graph/completed` as first-class execution events.

Verification:

- skills pipeline execution still updates `:events` correctly
- focused tests cover canonical step, tool, warning, graph, and failure events

Progress:

- Added `normalize-execution-event` so execution logs are canonicalized onto `:event` instead of mixing `:type` and `:event`.
- Extracted the skills-progress to Playground translation into `digdir.skills.events/progress->execution-events`.
- Updated Playground execution storage to normalize events as they are appended.
- Replaced active-path direct `{:type ...}` Playground event emission with shared canonical constructors.
- Updated `chat-session` to read both canonical `:event` and legacy `:type` during the transition.
- Preserved runner-only lifecycle events like `:step/skipped`, `:step/defaulted`, and `:graph/completed` in the canonical execution stream instead of dropping them.
- Verified the migrated flow with focused tests across event, runner, agent, and chat-session coverage.

### 4. Centralize stage metadata

Status: Completed

Tasks:

- Move stage labels and any remaining step-to-stage presentation mapping into shared metadata.
- Reuse the same stage metadata in event emission and live-view projection.
- Minimize repeated `name`-based fallback logic.

Verification:

- live status labels remain unchanged for classic, skills, and agentic flows
- no duplicate stage label tables remain in active code paths

Progress:

- Moved stage labels and flow metadata into `digdir.skills.events`.
- Reused shared stage labels in progress translation rather than ad hoc `name` fallback labels.
- Removed duplicate stage label and flow tables from `chat-session` and reused the shared metadata for live-view projection.

### 5. Reduce duplicate event projection logic

Status: Completed

Tasks:

- Review whether counts and summaries should come from replayed reducer state or from a pure projection of the event stream.
- Extract common counting/summary helpers instead of maintaining parallel logic.
- Keep the smallest viable change that reduces drift risk.

Verification:

- chat-session integration tests still pass
- stage, tool, warning, and summary fields remain stable

Progress:

- Extracted shared event-text helpers in `chat-session` so timeline entries and live summaries derive from one event-to-text mapping.
- Centralized stage-label fallback logic used by reducer progress updates and live-view projection.
- Deferred larger reducer-versus-replay architectural changes because the helper extraction removed the highest-risk duplication without widening the refactor.

### 6. Update documentation and plan status

Status: Completed

Tasks:

- Update this file after each completed implementation step.
- Record any intentionally deferred DRY opportunities.
- Summarize test coverage and residual follow-up work.

Verification:

- plan file reflects actual repo state at the end of the work

Progress:

- Updated the checklist as each implementation step landed.
- Recorded the focused verification commands used during the refactor.
- Deferred only the larger reducer-versus-replay architecture decision as optional follow-up work beyond this DRY cleanup slice.

## Progress Log

- 2026-03-16: Created implementation checklist and began Step 1.
- 2026-03-16: Revised direction after a second analysis pass. The current Playground execution event contract is now considered disposable if replacing it reduces adapter code and duplicated event semantics.
- 2026-03-16: Completed Step 1 and Step 2 by introducing `digdir.skills.events` and migrating graph runner and agent progress emission onto shared constructors.
- 2026-03-16: Began Step 3 by normalizing execution events onto `:event`, centralizing progress translation in `digdir.skills.events`, and updating Playground/chat-session code to consume the canonical key while remaining backward-compatible with legacy fixtures.
- 2026-03-16: Completed Step 3 by migrating active Playground execution-event emission onto shared constructors and storing normalized canonical events.
- 2026-03-16: Completed Step 4 by moving stage labels and flow metadata into `digdir.skills.events` and reusing them from `chat-session`.
- 2026-03-16: Verified the current refactor slice with `clojure -M:test -n digdir.skills.events-test -n digdir.playground.chat-session-integration-test -n digdir.skills.graph.runner-test -n digdir.skills.builtin.agent-test` and `clojure -M:clj-kondo --lint src/digdir/playground/core.cljc src/digdir/playground/chat_session.cljc src/digdir/skills/events.cljc` with warnings only.
- 2026-03-16: Completed Step 5 by consolidating event-to-text projection logic in `chat-session` and revalidating `digdir.playground.chat-session-integration-test` plus `digdir.skills.events-test`.
- 2026-03-16: Completed Step 6 by bringing this checklist up to date and explicitly deferring only the larger reducer-versus-replay design question.

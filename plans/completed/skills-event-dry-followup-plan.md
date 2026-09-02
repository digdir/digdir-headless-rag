# Skills Event DRY Follow-up Plan

## Goal

Remove the remaining duplication around execution-event projection, chat-session state defaults, and legacy event compatibility after the first event-model consolidation pass.

## Remaining DRY Opportunities

### 1. Reducer state vs replayed live-view projection

Current state:

- `chat_session` still maintains incremental state via `apply-event` and reducer helpers in [chat_session.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/playground/chat_session.cljc).
- `execution-events->live-view` separately scans the stored event log and recomputes `:current-stage`, `:current-stage-label`, tool counts, warning counts, timeline, and summary from scratch in [chat_session.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/playground/chat_session.cljc).

Why this is still duplicative:

- stage and count information exists both in reducer-maintained state and in replayed event projections
- future event additions must be taught to two state-derivation paths
- drift is still possible even though the text-label duplication was reduced

Preferred direction:

- Pick one source of truth for live execution state.
- The cleaner option is replay-first for the streaming execution view: store canonical events, then derive progress/timeline/counts from projection helpers only.

### 2. Chat-session default/reset data duplication

Current state:

- `initial-state` still embeds a full literal progress map while `initial-progress` defines the same shape separately in [chat_session.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/playground/chat_session.cljc).
- `start-request` also manually resets multiple top-level collections and flags.

Why this is still duplicative:

- the initial state shape is split across multiple literals
- future additions to `:progress`, `:flags`, or resettable collections need synchronized edits

Preferred direction:

- Introduce small constructors like `initial-flags`, `resettable-session-state`, or build `initial-state` from `initial-progress`.
- Make `start-request` reuse the same reset helpers instead of reconstructing the same shape inline.

### 3. Legacy `:type` compatibility layer

Current state:

- runtime code normalizes onto `:event`, but `chat_session` still accepts `:type` via `event-kind` and tests still use legacy `{:type ...}` fixtures in [chat_session_integration_test.clj](/Users/bdbrodie/dev/digdir/rag/server/test/digdir/playground/chat_session_integration_test.clj).
- the `Event Contract` docstring in [chat_session.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/playground/chat_session.cljc) is stale relative to the canonical event model and newer event kinds.

Why this is still duplicative:

- two event keys are supported through the same code paths
- code and tests still describe different contracts

Preferred direction:

- Migrate tests and fixtures to canonical `:event`.
- Remove `event-kind` fallback once no active path needs `:type`.
- Replace the hand-maintained docstring contract with a shorter canonical reference or point it at `digdir.skills.events`.

### 4. Remaining hand-built execution event payloads

Current state:

- `digdir.skills.events/progress->execution-events` still constructs several canonical execution-event maps inline for `:step/skipped`, `:step/defaulted`, `:graph/completed`, `:agent/iteration-started`, `:agent/turn-completed`, and `:agent/finalized` in [events.cljc](/Users/bdbrodie/dev/digdir/rag/server/src/digdir/skills/events.cljc).

Why this is still duplicative:

- canonical event shapes exist, but some are constructors and some are inline map literals
- any shape change requires editing both event consumers and translation logic

Preferred direction:

- Add execution-event constructors for the remaining canonical event kinds and reuse them from `progress->execution-events`.

## Proposed Implementation Order

### 1. Finish canonical event cleanup

- migrate `chat_session` tests from `:type` to `:event`
- update or replace the stale `Event Contract` comment
- remove `event-kind` fallback if tests and runtime no longer rely on `:type`

Why first:

- it narrows the active contract before deeper cleanup

### 2. Remove the remaining inline execution-event payloads

- add constructors in `digdir.skills.events` for skipped/defaulted/graph and agent execution events
- replace inline maps in `progress->execution-events`

Why second:

- small, low-risk cleanup that tightens the canonical event layer before projection refactors

### 3. Consolidate chat-session defaults and reset helpers

- extract `initial-flags` and a reset helper for per-request session fields
- make `initial-state` and `start-request` share those helpers

Why third:

- local cleanup with clear DRY benefit and low behavioral risk

### 4. Implement replay-first live-state derivation

Decision:

- replay-first is the chosen direction for execution streaming state
- reducer state may remain for message/session shaping, but execution progress, counts, timeline, and summary should derive from the canonical event log

Why this direction:

- canonical execution events are now the cleanest shared artifact
- adding new event kinds is simpler when there is one projection path for live status
- replayed projections are easier to debug because they are reproducible from the stored event log

Scope for the first slice:

- keep reducer behavior needed for messages and assistant response assembly
- remove duplicate progress/count derivation where `execution-stream-view` can reuse one projection result

Status:

- Completed

## Verification

- `clojure -M:test -n digdir.playground.chat-session-integration-test -n digdir.skills.events-test`
- broader regression pass after the reducer/projection decision:
  `clojure -M:test -n digdir.skills.graph.runner-test -n digdir.skills.builtin.agent-test -n digdir.playground.chat-session-integration-test -n digdir.skills.events-test`
- `clojure -M:clj-kondo --lint src/digdir/playground/chat_session.cljc src/digdir/skills/events.cljc`

## Recommendation

The highest-value next slice is:

1. remove legacy `:type` compatibility
2. add constructors for the remaining inline canonical event payloads
3. then implement replay-first with a narrower code surface

That sequence reduces contract ambiguity before touching the remaining architectural cleanup.

## Progress Log

- 2026-03-16: Confirmed replay-first as the chosen direction for execution streaming state.
- 2026-03-16: Completed canonical event cleanup by migrating `chat_session` tests and fixtures from `:type` to `:event`, simplifying the `chat_session` event contract comment, and removing the legacy `:type` fallback from active code.
- 2026-03-16: Completed constructor cleanup by replacing the remaining inline execution-event payloads in `digdir.skills.events/progress->execution-events` with shared constructors.
- 2026-03-16: Completed the chat-session defaults/reset cleanup by introducing shared initial/reset helpers for progress, flags, and per-request transient state.
- 2026-03-16: Completed the replay-first live-state slice by recording canonical events in `chat_session`, projecting progress/stage/tool/warning state from the event log, and leaving the reducer focused on message/session shaping.
- 2026-03-16: Tightened the replay-first boundary by moving timeline/summary and other projected observability fields fully into `execution-events->live-view`, updating tests to assert event-derived views instead of reducer internals, and adding lightweight canonical execution-event validation in `digdir.skills.events`.
- 2026-03-16: Verified the cleanup with `clojure -M:test -n digdir.playground.chat-session-integration-test -n digdir.skills.events-test -n digdir.skills.graph.runner-test -n digdir.skills.builtin.agent-test` and `clojure -M:clj-kondo --lint src/digdir/playground/chat_session.cljc src/digdir/skills/events.cljc`.

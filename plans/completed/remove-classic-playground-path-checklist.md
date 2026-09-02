# Remove Classic Playground Path Checklist

## Goal

Make the Playground runtime skills-only by removing the remaining classic execution path, its UI toggle, and the classic-specific event/test scaffolding.

## Scope

- Remove unused single-turn classic execution entrypoints
- Remove the classic-vs-skills runtime branch in Playground chat
- Remove the `use-skills` UI/config toggle
- Remove classic-only event flow metadata, fixtures, and tests
- Update docs/comments that still describe classic as supported

## Checklist

### 1. Remove Dead Classic Executor

- [x] Delete `execute-playground-pipeline` from `server/src/digdir/playground/core.cljc`
- [x] Delete any helper functions that become unused after classic removal
- [x] Verify there are no remaining repo call sites for the single-turn classic executor

### 2. Make Playground Chat Skills-Only

- [x] Remove the `:use-skills` branch from `execute-playground-chat-pipeline`
- [x] Always call `execute-skills-pipeline`
- [x] Remove `:use-skills` from resolved runtime context
- [x] Keep message persistence and diagnostics behavior unchanged

### 3. Remove Classic Mode From Playground UI

- [x] Remove `:use-skills` from `default-chat-config`
- [x] Remove the `use-skills` checkbox/toggle from the Playground config panel
- [x] Remove any UI branches that only exist to support disabling skills

### 4. Simplify Event Flow Modeling

- [x] Remove `:classic` flow metadata from `server/src/digdir/skills/events.cljc`
- [x] Update event projection/live status code to assume skills/agentic flows only
- [x] Remove classic-only fallback comments or labels where misleading

### 5. Update Tests And Fixtures

- [x] Replace branch-oriented tests with unconditional skills execution assertions
- [x] Remove classic live-status fixture coverage
- [x] Update tests that assert `:use-skills` in runtime context
- [x] Run focused Playground and API test coverage

### 6. Update Docs And Comments

- [x] Remove comments that describe the skills path as experimental or alternative
- [ ] Remove docs that describe classic as a supported Playground runtime
- [x] Leave lower-level `digdir.rag.core` retrieval/rerank primitives alone for now

## Verification

- `execute-playground-chat-pipeline` has no classic branch
- Playground UI has no classic/skills mode switch
- Live status still renders for skills and agentic executions
- Focused Playground tests pass
- No repo references remain to the removed single-turn classic executor

## Notes

- The broad architecture/docs cleanup is intentionally incomplete in this pass. Code, UI, fixtures, and focused tests now treat Playground runtime execution as skills-only.

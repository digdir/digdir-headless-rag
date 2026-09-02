# Async First-Turn Persistence Plan

## Goal

Make the first message in a new Playground conversation non-blocking by returning `{:execution-id ... :conversation-id ... :user-msg-id ...}` immediately, before any Datahike persistence or config-resolution latency is incurred on the request path.

This plan targets the current first-turn bottleneck in `execute-playground-chat-pipeline`, where the server blocks on:

- conversation creation
- first user-message persistence
- config resolution before those writes complete

The intent is to preserve correctness while moving those operations off the critical path.

## Executive Summary

The key design move is to generate stable IDs up front:

- `execution-id`
- `conversation-id`
- `user-msg-id`

Once those are available, the server can:

- seed in-memory execution state immediately
- return those IDs to the UI immediately
- perform conversation creation and first-turn persistence asynchronously
- start skill execution only after request persistence succeeds

For a brand new conversation, the initial persistence should be a single queued transaction that creates the conversation and attaches the first user message atomically.

That avoids a partial first-turn state where the conversation exists but the user message does not.

## Problem Statement

The current first-turn path still blocks on synchronous DB work before the UI sees any state transition.

In `server/src/digdir/playground/core.cljc`:

- `create-playground-conversation` is called synchronously when `conversation-id` is absent
- `transact-playground-user-msg` is called synchronously for the first user turn
- only after both complete does the function return the result map consumed by the UI

In `server/src/digdir/playground/ui.cljc`, the submit flow waits on that server return value before updating:

- `:conversation-id`
- `:execution-id`

That is why the first submit appears inert for several seconds.

## Current State

### 1. New conversation creation is synchronous

`server/src/digdir/data/db.cljc`

- `create-playground-conversation`

This function both generates the conversation ID and transacts it immediately.

That is incompatible with an immediate-return submission path because the caller cannot know the ID until after the DB roundtrip completes.

### 2. First user-message persistence is synchronous

`server/src/digdir/data/db.cljc`

- `transact-playground-user-msg`

This function generates `message/id` internally and transacts immediately.

That means the first-turn submit path cannot produce a stable `user-msg-id` up front.

### 3. Assistant persistence already has the right shape

`server/src/digdir/data/db.cljc`

- `transact-playground-assistant-msg-tx-data`
- `queue-playground-assistant-msg!`

This is already split into:

- tx-data generation
- async queue submission

The first-turn path should follow the same general pattern.

### 4. The execution state atom already supports immediate in-memory state

`server/src/digdir/playground/core.cljc`

- `!playground-executions`
- `update-execution!`
- `emit-execution-event!`

This gives us a place to publish immediate submit state before DB persistence begins.

### 5. The UI already expects an immediate result envelope

`server/src/digdir/playground/ui.cljc`

- `PlaygroundChatEffects`

The submit path already assumes that `execute-playground-chat-pipeline` returns:

- `:conversation-id`
- `:execution-id`

So the server-side refactor can improve responsiveness without changing the client/server contract.

## Architecture Direction

### 1. Separate ID generation from persistence

The server should generate:

- `execution-id`
- `conversation-id`
- `user-msg-id`

before any DB or config lookup that can block.

That allows the submit API to become immediate even for the first turn in a new conversation.

### 2. Split submission into two phases

#### Phase A: Immediate Submit

Responsibilities:

- validate required input
- generate IDs
- seed in-memory execution state
- schedule asynchronous work
- return IDs immediately

This phase must not perform Datahike writes.

#### Phase B: Async Workflow

Responsibilities:

- resolve runtime config
- resolve dataset config
- queue initial request persistence
- wait for persistence success
- run skill graph
- queue assistant persistence
- finalize execution

This phase may block internally, because it is no longer on the request-return path.

### 3. Make first-turn persistence atomic

For a new conversation, the first persistence should be one queued transaction containing:

- conversation entity creation
- user message creation
- `:conversation/messages` link to the new user message

This avoids split-brain startup states.

### 4. Preserve ordered write semantics

The initial conversation+user-message transaction must be queued before assistant-message persistence.

That guarantees that assistant response persistence cannot outrun request persistence.

## Proposed Data-Layer Changes

### 1. Add tx-data builders with caller-supplied IDs

In `server/src/digdir/data/db.cljc`, introduce pure builders.

#### `playground-conversation-tx-data`

Inputs:

- `convo-id`
- `agent-id`
- optional `:user-id`
- optional `:tenant`
- optional `:dataset-config-key`
- optional `:skill-graph-id`
- optional timestamp

Output:

- one conversation entity tx map

#### `playground-user-msg-tx-data`

Inputs:

- `msg-id`
- `convo-id`
- `text`
- `config`
- `parent-msg-id`
- `branch-index`
- optional timestamp

Output:

- one tx-data vector that appends the user message to the conversation

### 2. Add a first-turn composite builder

#### `playground-initial-turn-tx-data`

Inputs:

- `convo-id`
- `user-msg-id`
- `agent-id`
- `user-id`
- `tenant`
- `dataset-config-key`
- `skill-graph-id`
- `query`
- `config`
- optional timestamps

Output:

- one tx-data vector that creates the conversation and first user turn atomically

### 3. Add async queue helpers for request persistence

#### `queue-playground-initial-turn!`

For new conversations.

Should:

- build composite tx-data
- enqueue it through `digdir.data.background-worker/queue-transact!`
- support success and error callbacks
- return at least:
  - `:conversation-id`
  - `:message/id`
  - `:queued?`

#### `queue-playground-user-msg!`

For existing conversations.

Should:

- build user-message tx-data only
- enqueue it
- support success and error callbacks

### 4. Keep synchronous helpers for compatibility

Existing helpers can remain:

- `create-playground-conversation`
- `transact-playground-user-msg`

But the new first-turn path should stop depending on them.

## Proposed Core Pipeline Refactor

### 1. Generate IDs before any slow work

In `server/src/digdir/playground/core.cljc`, `execute-playground-chat-pipeline` should generate:

- `execution-id`
- `actual-convo-id`
- `user-msg-id`

immediately.

For a new conversation:

- `actual-convo-id` should be a new `nano-id`

For an existing conversation:

- `actual-convo-id` should stay equal to the caller-supplied `conversation-id`

### 2. Seed execution state immediately

Before async work starts, write an initial entry to `!playground-executions`.

Recommended initial shape:

- `:status :running`
- `:stage :queued`
- `:conversation-id actual-convo-id`
- `:user-msg-id user-msg-id`
- `:query query`
- `:tenant effective-tenant` if already known cheaply
- `:dataset-config-key requested dataset-config-key`
- `:agent-id effective-agent-id`
- `:parent-msg-id`
- `:branch-index`
- `:started-at`

This makes the execution visible to the UI immediately.

### 3. Return immediately

`execute-playground-chat-pipeline` should return:

- `:execution-id`
- `:conversation-id`
- `:user-msg-id`

before:

- config resolution
- conversation persistence
- user-message persistence
- skill execution

### 4. Move config resolution into the async phase

After return, a background `future` or equivalent should:

- resolve runtime config
- resolve dataset config
- derive canonical dataset/runtime values

This removes config resolution from the first-turn submit latency.

### 5. Queue request persistence before skill execution

After config resolution succeeds:

- for a new conversation, call `queue-playground-initial-turn!`
- for an existing conversation, call `queue-playground-user-msg!`

Only on success callback should the system proceed to skill execution.

### 6. Run skill execution only after request persistence succeeds

This is the clean sequencing boundary:

- request durability first
- answer generation second

That preserves the invariant that every assistant response belongs to a persisted user turn.

### 7. Keep assistant persistence behavior aligned with the new model

The current assistant path already:

- queues persistence
- waits for success before marking the execution complete

That pattern should remain.

## First-Turn Context Handling

### 1. New conversations do not need DB-backed lineage

For a brand new conversation:

- there is no parent lineage
- there is no prior message history

So `all-messages` can be built directly in memory as:

- empty prior context
- current user query appended

That means skill execution does not need the first user message to have landed in Datahike before inputs are constructed.

### 2. Existing conversations still need lineage lookup

For follow-up turns on an existing conversation, if `parent-msg-id` is present:

- the current lineage lookup can stay
- it can happen in the async phase, after immediate return

That keeps follow-up submits responsive too.

## Correctness and Failure Semantics

### 1. If config resolution fails

Behavior:

- execution becomes `:error`
- emit `request-failed`
- do not enqueue request persistence
- do not start skill execution

### 2. If request persistence enqueue fails

Behavior:

- execution becomes `:error`
- emit `request-failed`
- do not start skill execution

### 3. If request persistence transact fails

Behavior:

- execution becomes `:error`
- emit `request-failed`
- do not start skill execution

### 4. If request persistence succeeds but skill execution fails

Behavior:

- persisted user turn remains durable
- execution becomes `:error`
- emit `request-failed`

### 5. If assistant persistence fails

Behavior:

- keep current error behavior
- execution becomes `:error` after the model work succeeded but the response could not be persisted

## Recommended Execution Stages

Add explicit execution stages to make the lifecycle visible and debuggable:

- `:queued`
- `:resolving-config`
- `:persisting-request`
- `:running`
- `:persisting-response`
- `:complete`
- `:error`

These are more informative than overloading `:init` and `:complete`.

## UI Considerations

### 1. Immediate server return will fix the main symptom

As soon as the UI receives:

- `:conversation-id`
- `:execution-id`

it can switch into the active conversation view immediately.

### 2. The DB-backed thread may still lag behind for the first user message

Because the message list is fetched from `fetch-conversation-tree`, the first user turn may not appear until request persistence completes.

That is acceptable for option 1 if the main requirement is non-blocking submit.

### 3. Optional companion improvement

If needed, the UI can later render a provisional first user message from execution state while the DB catches up.

That is a separate UX enhancement, not required for the core option-1 refactor.

## Implementation Order

### Step 1. Add pure tx-data builders in `db.cljc`

Introduce:

- `playground-conversation-tx-data`
- `playground-user-msg-tx-data`
- `playground-initial-turn-tx-data`

### Step 2. Add queued request-persistence helpers in `db.cljc`

Introduce:

- `queue-playground-initial-turn!`
- `queue-playground-user-msg!`

### Step 3. Refactor `execute-playground-chat-pipeline`

Make it:

- generate IDs up front
- initialize execution state up front
- return IDs immediately
- move config resolution and persistence into async execution

### Step 4. Gate skill execution on request persistence success

This is the core correctness boundary.

### Step 5. Refine execution stages and error events

Ensure the UI can distinguish:

- waiting to persist request
- waiting on model execution
- waiting to persist response

### Step 6. Add optional provisional first-turn rendering later

Only if the UX still feels too inert after immediate return.

## Verification Plan

### 1. First submit returns immediately

Measure or log time from submit to receipt of:

- `conversation-id`
- `execution-id`

This should no longer depend on Datahike roundtrip time.

### 2. New conversation path performs no synchronous `d/transact` before return

Confirm the new-conversation submit path no longer calls:

- `create-playground-conversation`
- `transact-playground-user-msg`

before returning.

### 3. Initial request persistence remains atomic

For a new conversation, verify that:

- conversation entity
- first user message
- message link

arrive together in one transaction.

### 4. Skill execution never starts if request persistence fails

Inject failure and verify:

- execution becomes `:error`
- no assistant persistence is attempted

### 5. Ordering remains correct

Verify that assistant persistence cannot succeed before initial request persistence.

### 6. Existing follow-up turns remain correct

Confirm that:

- follow-up messages still attach to the correct conversation
- branching via `parent-msg-id` and `branch-index` still works

## Open Decisions

### 1. When to resolve canonical dataset identity

Question:

Should the system persist the user-selected dataset key immediately, or wait until canonical runtime/dataset resolution completes?

Tradeoff:

- persisting selected values reduces latency
- persisting canonical values preserves current semantics

Recommendation:

Start by resolving config asynchronously before request persistence so semantics stay stable, then optimize further if needed.

### 2. Whether to use one queue for request and response writes

Current recommendation:

- yes, keep one ordered worker queue

That keeps transactional ordering simple.

## Success Criteria

This plan is successful when:

- the first submit in a new conversation produces immediate UI state transition
- no synchronous Datahike write remains on the submit return path
- the first user turn is persisted before any assistant response is generated
- assistant completion still means durable assistant persistence
- failures in request persistence stop downstream execution cleanly

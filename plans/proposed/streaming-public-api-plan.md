# Streaming Public API Plan (SSE, events + token deltas)

> **Status: SUPERSEDED by `mcp-server-plan.md` Phase 4.** The MCP server's
> `tools/call` method now returns `notifications/progress` over a
> Streamable HTTP SSE response when the client supplies a `progressToken`
> in `_meta`. See `server/src/digdir/mcp/streaming.clj` and
> `server/src/digdir/mcp/transport.clj`. Per-token deltas via streaming
> `call-llm` remain a follow-up — the transport/event mapping already
> handles `:response/chunk` once the agent loop emits them.

## Goal

Add Server-Sent Events streaming to the public API. Clients opt in with `"stream": true` in the request body and receive a live stream of structured execution events (tool calls, tool results, iteration boundaries, sufficiency decisions) plus per-token deltas of the generated response. First-pass scope: `POST /api/rag` and `POST /api/skill-graphs/:id/execute` (and the body-form variant). Blocking behavior is unchanged when `stream` is absent or false.

## Executive Summary

The internal event system needed for streaming already exists. `digdir.skills.events` defines a rich event vocabulary — iteration started, thinking, tool-call, tool-result, turn-completed, finalized — and `progress-fn` is threaded from `skills-api/execute` through `run-skill-graph` into `agentic-loop`. The Playground already consumes those events live through Electric. Three things are missing:

1. An HTTP transport that exposes the stream to external clients.
2. Per-token deltas from the agent loop's LLM call (the OpenAI wrapper supports streaming but the loop currently calls the blocking path).
3. A small event schema addition plus wiring changes in the handler.

The transport uses `ring.core.protocols/StreamableResponseBody` so the handler stays synchronous from Jetty's POV — no Ring async 3-arity plumbing, no middleware changes. Auth runs blocking before the stream opens, so 401s remain plain JSON. A bounded queue bridges the skill executor thread and the SSE writer; client disconnect cancels the executor Future.

Phased rollout keeps the risky LLM-streaming assembly separate from the transport: Phase 1 ships structured events only; Phase 2 adds token deltas; Phase 3 extends to the skill-graph execute endpoints.

## Problem Statement

`POST /api/rag` (`server/src/digdir/api/routes/handlers.clj:108-237`) is fully synchronous: the handler calls `skills-api/run-skill-graph`, blocks for the entire agent loop, then serializes one JSON response. Users see a multi-second blank wait; there is no visibility into tool calls or partial output.

## Current State

### 1. The public API is fully blocking

`server/src/digdir/api/routes/handlers.clj`

- `api-rag-handler` (lines 108-237) parses the body, resolves auth/context, persists the user message, calls `skills-api/run-skill-graph`, persists the assistant message, and returns a single JSON body.
- `cheshire.core/generate-string` is called inline once at line 220.

### 2. Internal event infrastructure is already in place

`server/src/digdir/skills/events.cljc`

- `emit-progress!` (lines 58-67) — callback-based event emitter.
- Rich event vocabulary (lines 69-216): `request-started`, `response-chunk`, `response-finalized`, `stage-started/completed`, `step-started/completed/failed`, `agent-iteration-started`, `agent-thinking`, `agent-tool-call`, `agent-tool-result`, `agent-turn-completed`, `agent-exhausted`, `agent-finalized`, `warning`, errors.
- `progress-fn` is threaded from `skills-api/execute` → `run-skill-graph` → `execute-agent` → `agentic-loop` (`server/src/digdir/skills/builtin/agent/loop.clj:483`).

### 3. The OpenAI client supports streaming but the loop does not use it

`server/src/digdir/llm/openai.cljc`

- `stream-chat-completion` (line 18) uses `:stream true` + `:on-next`, but is a one-off that transacts to Datahike — not reusable as-is.

`server/src/digdir/skills/builtin/agent/loop.clj`

- `call-llm` (lines 82-106) always calls the blocking path.

### 4. HTTP framework and middleware

- Ring + Reitit + Jetty. Routes in `server/src/digdir/api/routes/endpoints.clj` (wiring at 44-66, Malli schemas around 224-245 for `rag-body-parameters` and 292 for `skill-graph-execution-body-parameters`).
- `wrap-api-key-auth` (`endpoints.clj:660-702`) runs before the handler — streaming can safely assume the caller is authenticated.
- Jetty `GzipHandler` is wired in `server/src/digdir/api/http.clj`. Gzip buffers; SSE must be excluded.

## Design

### Design decisions (confirmed with user)

- **Transport**: Server-Sent Events (`text/event-stream`).
- **Granularity**: Structured events + per-token deltas.
- **Opt-in**: `"stream": true` on the existing request body; same URL and auth.
- **Scope (first pass)**: `POST /api/rag` and `POST /api/skill-graphs/:id/execute` / `POST /api/skill-graphs/execute`. The `/api/conversations/*` routes only CRUD rows — skip. Single-skill `/api/skills/:id/execute` has almost no intermediate events — skip.

### Architecture

1. **Handler stays synchronous from Jetty's POV** by returning a body that implements `ring.core.protocols/StreamableResponseBody`. The worker thread writes SSE frames from inside `write-body-to-stream`; no Ring async, no Reitit changes.

2. **Two threads per streaming request**: the Jetty worker (drains a bounded queue, writes frames) and a dedicated skill executor thread (runs `run-skill-graph`, offers events onto the queue). Bridge is a `LinkedBlockingQueue` (capacity 1024). `progress-fn` offers with a 100ms timeout and drops on overflow with a WARN log — token deltas are the only realistic overflow source and dropping newest preserves prefix coherence.

3. **Events + token deltas** flow through the existing `progress-fn`. A new `events/agent-response-delta` constructor carries `{:iteration :delta}`. The SSE handler filters the event stream to a public-safe allowlist before serialization, so internal events stay available to Playground/logging without leaking into the public contract.

4. **Cancellation**: client disconnect (IOException on write) flips a cancel atom and calls `(.cancel future true)`. `InterruptedException` aborts the agent loop. The in-flight LLM HTTP call cancels via hato's interrupt support.

### SSE wire format

Each event = one frame:

```
event: <name>
data: <compact-json>

```

Event name is kebab with `/` → `.` (so `:agent/tool-call` becomes `agent.tool-call`, a valid SSE name). Payload is the full event map as compact JSON.

**Public allowlist** (other events stay internal):

`stream.started`, `request.started`, `stage.started`, `stage.completed`, `agent.iteration-started`, `agent.thinking`, `agent.tool-call`, `agent.tool-result`, `agent.turn-completed`, `agent.response-delta`, `agent.finalized`, `agent.exhausted`, `agent.error`, `stream.completed`, `stream.error`.

**Suppressed**: `step.*`, `graph.completed`, `warning.raised`, `response.chunk`, `response.finalized` (noisy, leak internal graph structure, or duplicate agent-level events).

The final `stream.completed` frame carries the **same payload** the blocking endpoint returns today (`{answer, insufficient-context, conversation-id, model, chunks-used}` or `{status: "needs_clarification", ...}`), so clients have one canonical "final result" event and don't need to reassemble the answer from deltas.

### Error semantics

| Failure | HTTP status | Shape |
|---|---|---|
| Auth failure | 401 blocking JSON (auth runs before stream opens) | `{error}` |
| Malli rejects body (bad `stream` field, missing `query`) | 400 blocking JSON | `{error, details}` |
| Pre-execution `ex-info` before `sse/stream-handler` is called | 4xx blocking JSON | `{error}` |
| Skill throws after stream opens | 200 with in-band `stream.error` then `stream.completed outcome: "error"` | see wire format |
| Client disconnect | no frames, executor interrupted | — |

Once headers are sent, HTTP status is locked at 200. Clients MUST inspect `stream.completed.outcome` to detect mid-stream failure.

### Heartbeats

Every 15s on a shared `ScheduledExecutorService` (defonce) a `: ping\n\n` comment frame is written so nginx/ALB proxies don't close idle streams. Cancelled in the body's `finally`.

### Response headers

```
Content-Type: text/event-stream; charset=utf-8
Cache-Control: no-cache, no-transform
X-Accel-Buffering: no
```

The last one defeats nginx response buffering. `text/event-stream` is also added to Jetty's `GzipHandler` exclusion list.

## File-by-File Changes

### Created

- **`server/src/digdir/api/sse.clj`** — transport module.
  - `event->frame` — `event: <name>\ndata: <json>\n\n` using Cheshire compact JSON (encoded JSON strings can't contain raw newlines, so no data-line splitting needed).
  - `heartbeat-frame` — `: ping\n\n` on a shared `ScheduledExecutorService`.
  - `sse-body` — `deftype` implementing `ring.core.protocols/StreamableResponseBody`, closing over `{:queue :cancel!-atom :heartbeat-ms}`. Polls the queue with a 15s timeout; nil → heartbeat; `::end` sentinel → exit; event → write + flush. `IOException`/`EofException` → set cancel atom, exit. `finally` cancels heartbeat.
  - `stream-handler` — creates queue, cancel-atom, submits the work fn to a bound `ExecutorService`, returns the Ring response map with streaming headers.
  - `public-event?` — allowlist predicate applied before queuing.

- **`server/test/digdir/api/rag_streaming_test.clj`** — integration + unit tests (see Verification).

### Modified

- **`server/src/digdir/skills/events.cljc`** — add constructors and schema entries:
  - `agent-response-delta` → `{:event :agent/response-delta :iteration i :delta s}`.
  - `stream-started` → `{:event :stream/started :request-id r}`.
  - `stream-completed` → `{:event :stream/completed :outcome :ok|:error|:cancelled ...}` (carries the final response payload on `:ok`).
  - `stream-error` → `{:event :stream/error :code k :message s}`.

- **`server/src/digdir/llm/openai.cljc`** — add a reusable `streaming-chat-completion`:
  - Accepts `params, {:keys [on-content-delta]}`; returns the assembled `{:choices [{:message {...} :finish_reason ...}]}` shape that `call-llm` callers already expect.
  - Internally calls `wkok.openai-clojure.api/create-chat-completion` with `:stream true :on-next`. Per chunk: `delta.content` → `on-content-delta`; `delta.tool_calls` → merge into accumulator indexed by `index` (OpenAI splits tool_call arguments across many chunks; first has the name, later ones append `arguments` fragments).
  - On `finish_reason` non-nil, assemble and return.
  - Leave the existing `stream-chat-completion` (Datahike-persisting) alone.

- **`server/src/digdir/skills/builtin/agent/loop.clj`**:
  - Change `call-llm` (lines 82-106) to accept `progress-fn` and `iteration`; branch to `streaming-chat-completion` when `progress-fn` is non-nil, emitting `agent-response-delta` per content chunk.
  - Add cancellation check at the top of the loop body (around line 492): `(when (Thread/interrupted) (throw (InterruptedException. "agent loop cancelled")))`. Covers non-LLM busywork; the LLM call itself respects interrupt via hato.

- **`server/src/digdir/api/routes/endpoints.clj`** — add `[:stream {:optional true} boolean?]` to `rag-body-parameters` (~line 224) and `skill-graph-execution-body-parameters` (~line 292). Default false.

- **`server/src/digdir/api/routes/handlers.clj`** — refactor `api-rag-handler` (lines 108-237):
  - Extract lines 113-163 (validation, context resolution, history fetch, skill-params assembly) into `prepare-rag-execution ring-req params`.
  - Extract lines 167-211 (diagnostics, persist assistant msg + used data, simplify topic, format response) into `finalize-rag-execution result prep`.
  - Handler: run `prepare`; if `(:stream params)`, call `sse/stream-handler` with a work fn that runs `run-skill-graph` + `finalize` and enqueues `stream-completed`; else run the blocking path. Both paths share all DB writes and response formatting — no drift.

- **`server/src/digdir/api/http.clj`** — exclude `text/event-stream` from gzip. Call `.setExcludedMimeTypes` on the `GzipHandler` in `add-gzip-handler!`.

- **`server/docs/api/openapi.yaml`** — add `stream` to `RagRequest` and to the skill-graph-execute request; add a second response media type `text/event-stream` with a schema describing the event sequence.

- **`server/docs/api/endpoints/rag.md`** — new "Streaming" section with a `curl -N` example and a sample event trace.

## Phasing

1. **Phase 1 — SSE scaffold + structured events only.** Ship `sse.clj`, new stream events, the allowlist, the `stream` flag on `/api/rag`, handler refactor, gzip exclusion, docs, tests. `call-llm` stays blocking. Transport is proven end-to-end; users see tool calls and iterations live. De-risked MVP.

2. **Phase 2 — Token deltas from the agent loop LLM.** Add `streaming-chat-completion`, modify `call-llm`, emit `agent/response-delta`. Unit-test tool-call chunk assembly against recorded fixtures.

3. **Phase 3 — Extend to `/api/skill-graphs/:id/execute` and `/api/skill-graphs/execute`.** Mechanical: same `stream` flag, same SSE module, same handler refactor pattern.

If the `generate_response` tool dispatches to a sub-skill that makes its own LLM call (unverified), a later phase could plumb `progress-fn` into that sub-skill for per-token output from generation itself. Worth confirming against `server/src/digdir/skills/builtin/agent/tools.clj` before scoping.

## Verification

Run from the project root:

```
bb lint
bb test
cd server && clojure -M:test -n digdir.api.rag-streaming-test
```

Tests in `server/test/digdir/api/rag_streaming_test.clj`:

- **Blocking path unchanged** — `POST /api/rag` without `stream` returns JSON 200; response shape byte-equal to current.
- **Streaming happy path** — `stream: true` with a stubbed skill graph; parse SSE frames, assert `stream.started` first, `stream.completed` last with `outcome: "ok"`, ≥1 `agent.*` frame between.
- **Token delta assembly (Phase 2)** — stub OpenAI streaming with a recorded chunk sequence that splits a tool_call across 6 chunks; assert the assembled message matches the known-good blocking response.
- **Client disconnect** — open HTTP connection, send streaming request, close socket mid-stream. Assert the executor future is interrupted within 1s (expose a test-only `!active-futures` atom in `sse.clj`).
- **Auth failure with `stream: true`** — invalid API key returns 401 JSON, not SSE.
- **Malformed `stream` field** — `"stream": "yes"` returns 400 JSON (Malli rejection).
- **Skill throws mid-stream** — stub `run-skill-graph` to throw after two events; assert `stream.error` frame, `stream.completed outcome: "error"`, HTTP 200.
- **SSE framer unit tests** — JSON containing newlines, unicode, empty payload.

Manual smoke:

```bash
curl -N -X POST https://localhost:.../api/rag \
  -H 'x-api-key: …' -H 'content-type: application/json' \
  -d '{"query":"What is X?","stream":true}'
```

Should stream events in real time, ending with a single `stream.completed` frame carrying the full answer payload.

Before merging Phase 1, confirm against a staging deploy that gzip exclusion is wired correctly — an accidentally-gzipped SSE stream appears as a single blob after the request finishes, not as progressive frames.

## Unknowns to Confirm During Implementation

1. **Gzip + SSE today** — confirm Jetty's `GzipHandler` compresses the test stream before shipping the exclusion (likely yes; cheap to verify).
2. **`wkok.openai-clojure.api/create-chat-completion :stream true`** — verify it blocks the caller until the stream completes (`:on-next` called synchronously). Existing usage in `openai.cljc:18` suggests yes; confirm via the library.
3. **hato + thread interrupt** — REPL test that interrupting a thread promptly aborts the in-flight HTTP call. If not, add a second cancellation mechanism (e.g. a per-request `HttpClient` we can close).
4. **Reitit coercion + streaming body** — confirm response coercion passes `StreamableResponseBody` through untouched. `/api/rag` has no `:responses` schema declared, so almost certainly yes, but worth a quick check.
5. **`generate_response` sub-skill dispatch path** — only relevant if we later extend token streaming into the generation sub-skill. Check `server/src/digdir/skills/builtin/agent/tools.clj`.

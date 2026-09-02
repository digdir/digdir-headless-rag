# MCP Server: Playground-Canonical Runtime, Deprecating Custom RAG API

> **Status: Scope complete on `harmonize-runtime`; delivery work
> remaining.** All four named phases plus Phase 4b (per-paragraph
> token streaming) and all six follow-ups have shipped on the branch.
> Filed to `plans/completed/` because there's no further design or
> implementation work — only validation and merge (see *Next Steps*
> at the bottom of this file).
>
> Implementation commits in order: Phase 0 (multiple commits ending
> `094f4d0`), Phase 1 `d7241a0`, Phase 3 `d066e4f` + fix `3efc147`,
> Phase 4 `84b6d67`, Phase 4b `354dca6`, Follow-ups `d19765f`
> (cancellation + diagnostics snapshot test), `c43f359` (real-client
> conformance + scope-resolution fix), `0af63bc` (public docs),
> `6e5b0e7` (OpenWebUI + Playwright E2E harness).

## Goal

Replace the custom RAG/retrieve HTTP endpoints (`/api/rag`, `/api/retrieve`, `/api/skill-graphs/*/execute`) with a generic Model Context Protocol (MCP) server that exposes each `(agent × allowed-skill-graph)` pair as a tool. The Playground's invocation path becomes the canonical runtime — it is the most battle-tested and configuration-driven of the existing call sites — and both the Playground UI and the MCP server call it through a shared `invoke-rag` function. Custom endpoints stay as thin deprecated shims during the transition; retrieval-only is deprecated with no MCP replacement.

## Executive Summary

Today four call sites of `skills-api/run-skill-graph` (the Playground plus three API routes) reconstruct inputs, opts, and output interpretation independently. The Playground's path is the richest and most correct: it resolves runtime config end-to-end via `cfg/get-runtime-skill-config-v2-with-trace`, handles `:report`-emitting agents through a multi-key output fallback, threads `progress-fn` events through the live UI, and consistently passes `:agent-id` into ambient context. The API paths have silent gaps — most notably, `/api/rag` returns an empty body for any graph that emits `:report` instead of `:response`.

Rather than harmonize the API and Playground piecewise, we make the Playground's invocation core *the* runtime contract. Phase 0 discovery confirmed there are no live API clients, so we prune rather than deprecate:

1. **Phase 0.** Prune the custom API surfaces; cut the skill-graph registry to the three preserved tools (`:builtin/fact-checker`, `:builtin/agent-rag-graph-bundled`, `:builtin/agent-rag-graph-faithful`); rename `:demo/*` and `:digdir.demo/*` graphs as `:docs/*`; introduce `:builtin/docs-agent` to own them; add explicit `:input-schema` to kept skill-graph registrations.
2. **Phase 1.** Lift the Playground's core call into `digdir.skills.invoke/invoke-rag`. Use the Playground's output-fallback semantics (handles `:report`-emitting agents). This becomes the single in-process runtime that both the Playground UI and the MCP server call.
3. **Phase 3.** Build the MCP server. For each enabled agent in the principal's `:agent-refs`, emit one tool per skill-graph in `:agent/allowed-skill-graphs`. Tool invocation maps to `invoke-rag`. Conversations are server-owned: tool inputs accept an optional `conversation-id`; the server persists turns and returns the id. Includes a per-API-key rate-limit middleware (gap surfaced by Phase 0 discovery).
4. **Phase 4.** Stream via MCP progress notifications. This subsumes most of `streaming-public-api-plan.md`.

Phase 2 (deprecation polish) and Phase 5 (eventual retirement) from the earlier draft are gone — Phase 0 already deletes the custom routes outright. Server-owned conversations and `:agent-id`-keyed tool routing align with how API keys already model `:agent-refs`, `:dataset-scopes`, and `:skill-graphs` — the existing scope model maps mechanically to MCP tool allowlists, so we keep our API-key authentication behind the MCP transport rather than adopting MCP-native OAuth.

## Problem Statement

The team has been bitten repeatedly by behavioral deltas between the custom API endpoints and the Playground. Product has been waiting for the right time to replace the custom API with a generic surface; that time is now. MCP is the right standard: it has tool listing, structured invocation, progress notifications, and per-tool input/output schemas — the exact primitives we have been incrementally inventing.

## Current State

### Four call sites of `run-skill-graph`, four divergent envelopes

| # | Entry point | Path |
|---|---|---|
| 1 | `POST /api/rag` (full RAG, persisted) | `server/src/digdir/api/routes/handlers.clj:167` |
| 2 | `POST /api/retrieve` (retrieval-only) | `server/src/digdir/api/routes/handlers.clj:293` |
| 3 | `POST /api/skill-graphs/:id/execute` (generic) | `server/src/digdir/api/routes/endpoints.clj:589` |
| 4 | **Playground chat** | `server/src/digdir/playground/core.cljc:453` |

The Playground's path is the canonical one for three reasons:

1. **Output extraction.** It pulls the response with the fallback chain `:response → :verification → :report → "Retrieved and reranked N chunks."` (`core.cljc:486-501`). Graph variants that emit `:report` (self-improve agents) render correctly. The API path takes `(:response outputs)` only (`handlers.clj:175`) and silently returns an empty body for those graphs.

2. **Runtime config resolution.** The Playground threads `:tenant`, `:dataset-config-key`, `:runtime-config-key`, `:agent-id` end-to-end via `cfg/get-runtime-skill-config-v2-with-trace` (`core.cljc:702`), surfacing `:runtime-config-traces` for observability. The API uses `api-ctx/resolve-request-execution-context!` which is correct but less observable.

3. **Event consumption.** The Playground passes `:progress-fn` into `run-skill-graph` and consumes the full event stream into `!playground-executions` for live UI updates. The API drops events on the floor.

The one place a shared core already exists is `api-util/build-rag-skill-params`. The doc-comment at `core.cljc:301-305` cites a prior incident where rerank skill defaults diverged because the two surfaces built skill-params independently. That lesson is applied there but not propagated to inputs, opts envelope, or output extraction.

### Generic skill-graph endpoints are dormant

`POST /api/skill-graphs/:id/execute` and `POST /api/skill-graphs/execute` have **zero non-test callers in-repo**. They are wired up, authorized, and schema-validated, but:

- Not in `server/docs/api/openapi.yaml` (only `/api/rag` and `/api/retrieve` are).
- `server/docs/api/README.md` links to a `skill-graphs.md` page that does not exist.
- The Playground does not use them — it calls `execute-playground-chat-pipeline` in-process.

They are deprecated alongside `/api/rag` and `/api/retrieve` in this plan with no special treatment.

### Agents are already modeled to fit MCP tools

`server/src/digdir/agents/db.clj` defines agents with `:agent/id`, `:agent/name`, `:agent/description`, `:agent/default-skill-graph`, `:agent/allowed-skill-graphs`, `:agent/allowed-dataset-scopes`, `:agent/enabled?`. Agent ids are **globally unique** (agents are platform-level entities, configured per tenant via runtime config and dataset scopes), so tool names need no tenant prefix.

Each agent surfaces as a *family* of MCP tools — one sub-tool per skill-graph in `:agent/allowed-skill-graphs`:

- `:agent/id` × `:agent/allowed-skill-graphs` → MCP tool `name` as `<agent-id>__<skill-graph-name>`, where `skill-graph-name` is `(name skill-graph-id)` (so `:builtin/agent-rag` → `agent-rag`).
- `:agent/description` combined with the skill-graph's role → MCP tool `description`.
- The skill-graph's declared input schema → MCP tool `inputSchema`.
- `:agent/default-skill-graph` → surfaced in `_meta.default?` on the corresponding tool, for client UX.
- `:agent/allowed-dataset-scopes` → enforced server-side on invocation.
- `:agent/enabled?` → tool visibility (all sub-tools for a disabled agent are hidden).

API keys already carry `:agent-refs`, `:dataset-scopes`, `:skill-graphs` — the existing scope model is the tool allowlist for free. A principal's visible tools are `(agents matching :agent-refs) × (skill-graphs in each agent's :allowed-skill-graphs ∩ key's :skill-graphs allowlist if present)`.

## Design

### The canonical runtime: `digdir.skills.invoke/invoke-rag`

A new namespace, lifted from the Playground's path:

```clojure
(defn invoke-rag
  "Canonical RAG skill-graph invocation. Shared by Playground UI, deprecated
   custom API endpoints, and the MCP server.

   Inputs:
     :user-query                — required
     :conversation-history      — vec of {:role :text}, default []
     :claim                     — optional; defaults to :user-query
     :collections               — {:docs-collection :chunks-collection :phrases-collection}
     :skill-graph-id            — kw, e.g. :builtin/agent-rag
     :skill-params              — built via api-util/build-rag-skill-params
     :execution-scope           — {:tenant :dataset-config-key :dataset-ref :agent-id}
     :model                     — optional explicit override (nil = honor per-skill config)
     :progress-fn               — optional event sink (MCP server uses this for notifications)
     :client                    — optional api-key client id
     :runtime-config-key        — optional

   Returns (Playground's shape, normalized):
     {:status            :complete | :needs-clarification | :error
      :response          string                — :response → :verification → :report fallback
      :insufficient?     boolean
      :clarification     nil | {:question :options :context-summary}
      :chunks            vec
      :queries           vec
      :search-attribution map
      :diagnostics       map                   — execution-metadata, agent-trace, search-history,
                                                 typesense-startup-diagnostics, etc.
      :raw-result        map                   — passthrough for callers that need internal detail
      :error             nil | {:error-type :error-message}}")
```

This function owns the contract: input shape, opts envelope (one call to `api-ctx/assoc-execution-scope`), output extraction with the `:response → :verification → :report` fallback, error normalization. Persistence, transport, topic simplification, and surface-specific diagnostics shaping stay in the outer wrappers.

### MCP server

**Namespace.** `digdir.mcp` (new). Sub-namespaces: `digdir.mcp.transport`, `digdir.mcp.tools`, `digdir.mcp.session`.

**Transport.** Streamable HTTP (the post-2025-03 MCP transport) over a single endpoint, e.g. `POST /mcp`. stdio is out of scope for the first pass — it can be added later for local developer ergonomics.

**Auth.** `Authorization: Bearer <api-key>`, validated by the existing `wrap-api-key-auth` middleware. The MCP server runs behind the same auth as the deprecated endpoints. Native MCP OAuth is a future option, not Phase 3.

**Tool listing (`tools/list`).** Enumerate `(agent × allowed-skill-graph)` pairs the principal can use:

1. Resolve principal from API key.
2. Pull all agents matching `:agent-refs` on the key (and on the bound access policy, if any).
3. Intersect with `:agent/enabled? = true`.
4. For each agent, emit one tool *per skill-graph* in `:agent/allowed-skill-graphs` (intersected with the key's `:skill-graphs` allowlist if non-empty):
   ```
   {:name        "<agent-id>__<skill-graph-name>"   ;; agent-ids globally unique
    :description "<agent/description> — <skill-graph role>"
    :inputSchema <JSON Schema for this skill-graph's inputs>
    :_meta       {:agent-id <id>
                  :skill-graph <fully-qualified kw>
                  :default? <true if matches :agent/default-skill-graph>}}
   ```
   `skill-graph-name = (name skill-graph-id)` — sub-tools for the same agent come from a curated allowed-list so collisions on the short name are not expected, but we validate uniqueness at listing time and fall back to the fully-qualified name with a normalized separator if needed.

**Tool invocation (`tools/call`).** Each tool maps to one `invoke-rag` call:

1. Parse `<agent-id>__<skill-graph-name>` from the tool name. Look up the agent; resolve the fully-qualified skill-graph kw from the parsed short name against `:agent/allowed-skill-graphs`.
2. Authorize: agent is in the calling key's `:agent-refs`, skill-graph is in `:agent/allowed-skill-graphs`, dataset access checks against `:agent/allowed-dataset-scopes` ∩ key's `:dataset-scopes`.
3. Build `execution-scope` from agent's tenant + dataset-config-key + agent-id.
4. Build `skill-params` via `api-util/build-rag-skill-params`, pulling defaults from runtime config and applying overrides from tool args.
5. Resolve conversation: if `conversation_id` provided, load history; else create a new conversation. Persist the user turn.
6. Call `invoke-rag` with the resolved skill-graph and a `progress-fn` that emits MCP `notifications/progress` for each skill event.
7. Persist the assistant turn (including the resolved response, citations, diagnostics).
8. Run `rag/simplify-convo-topic` for new conversations.
9. Return MCP tool result: text content block (the response), structured content blocks for citations / chunks, and `_meta` carrying `conversation_id`, `skill_graph` (which sub-tool ran), `insufficient?`, `clarification` if any, and a *minimal* diagnostics payload (timings, model used). The full Playground-rich diagnostics map stays Playground-internal.

**Tool input schema (per agent).** Base shape, extended per agent's skill-graph:

```json
{
  "type": "object",
  "properties": {
    "query":           {"type": "string"},
    "conversation_id": {"type": "string", "description": "Omit to start a new conversation."},
    "model":           {"type": "string", "description": "Optional override."},
    "overrides":       {"type": "object", "description": "Per-call skill-param overrides."}
  },
  "required": ["query"]
}
```

Per-skill-graph extensions (e.g. retrieval-only agents may expose `top_k`, `filter`) are pulled from the skill-graph's input schema. This requires a small inventory: see Risks.

**Progress notifications.** The MCP spec defines `notifications/progress` with a `progressToken` supplied by the client in the `_meta` of `tools/call`. We map `digdir.skills.events` to progress notifications one-to-one:

- `:request/started`, `:stage/started`, `:stage/completed`, `:step/skipped`, `:step/defaulted`, `:tool/call`, `:tool/result`, `:agent/iteration-started`, `:agent/turn-completed`, `:warning/raised` → `notifications/progress` with `total`/`progress`/`message` fields plus a structured `_meta`.
- `:response/chunk` (token delta) → `notifications/progress` with the delta in `message` and the cumulative content in `_meta.partial`.
- `:response/finalized` and `:request/failed` complete the call.

This subsumes the bespoke SSE design in `streaming-public-api-plan.md`.

### Server-owned conversations

The MCP server persists conversations through the same Datahike store the API uses. Reuse `db/transact-user-msg` / `db/transact-assistant-msg` / `rag/simplify-convo-topic`. The Playground's branching model stays Playground-internal — MCP conversations are linear. If a client wants branching, they call `tools/call` without `conversation_id` to start a fresh thread.

This means the MCP server is *not* stateless. That is a real choice — we want it server-owned for the same reasons the API is today (it lets us add observability, audit, rate-limit per conversation, and avoid clients having to ship long histories every call).

### Deprecation of `/api/rag` and `/api/retrieve`

After Phase 1, both endpoints become thin shims over `invoke-rag`. After Phase 2:

- OpenAPI spec marks the paths `deprecated: true`.
- Responses carry `Deprecation: true` and (once a sunset date is decided) `Sunset: <date>` per RFC 9745.
- `server/docs/api/endpoints/rag.md` and `retrieve.md` add a deprecation banner pointing to the MCP migration guide for `/api/rag`. **`/api/retrieve` has no MCP replacement** — retrieval-only is being deprecated wholesale. Clients who need it should either migrate to a full RAG agent (which performs retrieval as a step) or stay on the deprecated endpoint until sunset.

`/api/skill-graphs/*/execute` and `/api/skill-graphs/execute` are deprecated with the same mechanism. Given they have no known clients, they may simply be removed once Phase 3 lands.

The conversations CRUD endpoints (`/api/conversations/*`) stay — they are useful regardless of execution surface, and MCP doesn't replace them.

## Phases

### Phase 0 — Discovery and pruning ✅ SHIPPED

Discovery is complete (see `mcp-server-phase0-findings.md`). The three closed-in-repo streams (`assoc-execution-scope` idempotency, skill-graph inventory, conversation lifecycle) plus the operator-confirmed traffic finding (no live clients) collapse the plan: we no longer need a deprecation window, so Phase 0 absorbs what was Phase 2 (deprecation polish) and Phase 5 (eventual retirement). It is now a pruning + restructuring phase.

**Execution steps (sequenced; each commit independently green):**

1. **Flip the Playground default** from `"builtin/agent-rag"` to `"builtin/agent-rag-graph-bundled"` (`server/src/digdir/playground/core.cljc:442` and `:739`). Prerequisite for step 5.
2. **Rename `:demo/*` → `:docs/*`** in `server/src/digdir/demo/altinn_*.clj` and all references.
3. **Rename `:digdir.demo/*` → `:docs/*`** in `server/src-dev/digdir/demo/self_improve_*.clj` and tests under `server/test/digdir/demo/`.
4. **Add `:builtin/docs-agent`** registration; its `:agent/allowed-skill-graphs` covers the renamed `:docs/*` set. Description anchored to the docs-curation use case.
5. **Drop the four unused `:builtin/*` registrations** (`simple-qa`, `research-assistant`, `retrieve-only`, `agent-rag`) from `server/src/digdir/skills/templates/builtin.clj`. The inner `:builtin/agent-iteration-*` sub-graphs stay (they back the preserved agent graphs).
6. **Add `:input-schema` to `make-skill-graph`** and declare schemas on the three preserved `:builtin/*` graphs and all renamed `:docs/*` graphs. Schemas use Malli (codebase precedent).
7. **Hard-delete API surfaces.** `api-rag-handler` and `api-retrieve-handler` from `handlers.clj`; `execute-skill-graph-handler` and `execute-graph-handler` from `endpoints.clj`; route wiring; `openapi.yaml` paths; `server/docs/api/endpoints/rag.md` and `retrieve.md`; corresponding tests in `routes_test.clj`. Remove now-orphan helpers (`build-rag-skill-params`, `build-retrieval-skill-params`) from `api/util.clj` if no other callers remain.
8. **`bb lint && bb test`** clean.

**Acceptance.** Skill-graph registry is exactly the kept set plus inner sub-graphs and docs graphs. `:builtin/docs-agent` exists in the agents registry. Custom HTTP endpoints are gone. `bb test` passes. The Playground continues to work end-to-end (smoke test against the new default agent).

### Phase 1 — Extract `invoke-rag` from the Playground ✅ SHIPPED (`d7241a0`)

With the API endpoints pruned in Phase 0, Phase 1 reduces to a Playground-internal refactor:

- Create `server/src/digdir/skills/invoke.clj` with `invoke-rag` as specified in the *Design* section. The function is the canonical RAG invocation; the Playground becomes its first caller, the MCP server (Phase 3) becomes the second.
- Switch Playground (`execute-skill-graph` at `core.cljc:312-659`) to delegate to `invoke-rag`. The Playground wrapper keeps progress-fn closures over `!playground-executions`, the rich diagnostics shaping, and persistence helpers. Result map shape from `invoke-rag` is *exactly* what the Playground was already producing — no behavior change.
- Snapshot test the Playground's diagnostics map shape before refactoring; assert it after. Regression net.

**Acceptance.** Playground UI behavior is unchanged. `invoke-rag` exists as a reusable entry point.

### Phase 3 — MCP server scaffolding ✅ SHIPPED (`d066e4f`, fix `3efc147`)

- `digdir.mcp.transport` — Streamable HTTP transport, JSON-RPC framing, session management.
- `digdir.mcp.tools` — tool listing from agents DB; tool invocation calling `invoke-rag`; result formatting.
- New route: `POST /api/mcp` mounted inside the existing `/api/*` tree so it inherits `wrap-api-key-auth`.
- Per-API-key rate limiter (`digdir.api.rate-limit-api`) applied to `/api/mcp`.
- Per-agent input schemas synthesized from each skill graph's Malli `:input-schema`. No mapping table.
- Conversation persistence wired through existing `db/transact-*` helpers.
- Note: a dedicated `digdir.mcp.session` namespace was not needed — session/principal binding is carried on the ring request map by `wrap-api-key-auth`.

**Acceptance.** Verified end-to-end via a direct ring smoke test against the live api-router with a real API key + the seeded built-in agents: `initialize` returns proper capabilities, `tools/list` returns the cross-product of `(agent × allowed-skill-graph)` with `[DEFAULT]` markers and JSON-Schema inputs, `tools/call` envelope errors map correctly. A literal `mcp-inspector` run is captured as a follow-up — see *Follow-ups* §1.

### Phase 4 — Streaming via MCP progress ✅ SHIPPED (`84b6d67`)

- Mapped `digdir.skills.events` events to `notifications/progress` in `digdir.mcp.streaming/event->progress-params`. `:step/*` and `:graph/completed` deliberately suppressed; the rest of stage/agent/tool/warning/response-chunk events surface as monotonic-progress frames.
- SSE response body via `ring.core.protocols/StreamableResponseBody`; bounded `LinkedBlockingQueue` bridges the executor and writer threads; 15 s heartbeats; Jetty's `GzipHandler` now excludes `text/event-stream`.
- Transport branches: a `tools/call` request that includes `_meta.progressToken` opens the SSE stream; otherwise the plain-JSON response path is unchanged.
- `streaming-public-api-plan.md` marked superseded.

### Phase 4b — Per-paragraph token streaming ✅ SHIPPED (`354dca6`)

The per-token branch the original Phase 4 deferred. Per-token MCP notifications were rejected as too chatty (~1000 frames per long answer) and low-value for our `:report`-emitting graphs.

- `digdir.llm.openai/streaming-chat-completion` — drains the wkok SSE channel synchronously, fires `:on-content-delta` per content fragment, accumulates `tool_calls` deltas by `:index`, returns the same shape as the blocking path.
- `digdir.skills.builtin.agent.streaming/make-chunker` — paragraph-or-250ms chunker. Flushes on `\n\n` boundaries or when buffered content has aged past `timeout-ms`, whichever comes first.
- `digdir.skills.builtin.agent.loop/call-llm` — opts-arity that routes through the streaming helper when `:progress-fn` is supplied; otherwise the legacy blocking path is unchanged.
- Result: ~5–20 paragraph-bounded `:response/chunk` events for a typical answer, surfacing on the MCP SSE stream via the existing event mapping.

**Acceptance.** End-to-end smoke test (paragraph chunker → `:response/chunk` → `notifications/progress`) confirmed: 19 token deltas → 3 paragraph-bounded progress frames with monotonic progress counters. A literal `mcp-inspector` run remains a follow-up — see *Follow-ups* §1.

## Follow-ups

All six items below are now shipped on `harmonize-runtime`.

### 1. Verify conformance with a real MCP client ✅ SHIPPED (`c43f359`)

The plan named `mcp-inspector` as the acceptance test for Phases 3 and 4. We verified shape via direct ring calls but never literally ran an MCP client. Run `mcp-inspector` (or any other off-the-shelf MCP client) against `POST /api/mcp` with a valid API key and confirm:

- `initialize` negotiation succeeds, `tools/list` enumerates the seeded built-in agents' tools.
- `tools/call` returns a complete result with `_meta.conversation_id`.
- `tools/call` with `_meta.progressToken` opens an SSE stream, emits `notifications/progress` per stage/iteration, and ends with the JSON-RPC result frame.

If the inspector flags any envelope or transport-level deviation, fix in `digdir.mcp.transport` / `digdir.mcp.streaming`. Time-boxed: half a day.

**Outcome:** `server/scripts/mcp_conformance_check.clj` boots the full middleware stack on an ephemeral Jetty and runs seven JSON-RPC checks end-to-end (unauthenticated → 401, initialize, tools/list, ping, notifications/initialized, unknown method, streaming tools/call). All pass. The run surfaced a real bug — built-in agents seeded with empty `:allowed-dataset-scopes` couldn't resolve a scope — fixed in the same commit so empty agent scopes now fall through to API-key scopes / explicit args / env defaults. Findings doc: `server/scripts/conformance-finding-2026-05-21.md`.

### 2. Public docs — `server/docs/api/mcp.md` ✅ SHIPPED (`0af63bc`)

Phase 0 deleted `server/docs/api/endpoints/rag.md` and `retrieve.md`. Nothing replaced them, so external integrators have no documentation. Write a single `server/docs/api/mcp.md` covering:

- Authentication (`X-API-Key` header, same as deprecated endpoints).
- Methods supported (`initialize`, `tools/list`, `tools/call`, `notifications/initialized`, `ping`).
- Tool naming convention (`<agent-id>__<skill-graph-name>`) and how to discover available tools.
- Conversation lifecycle (`conversation_id` in tool args / response `_meta`).
- Streaming opt-in (`_meta.progressToken` on `tools/call`).
- A `curl -N` example for each: blocking call and streaming call.
- Per-API-key rate limit defaults and 429 response shape.

Also update `server/docs/api/openapi.yaml`: add the `/api/mcp` POST endpoint with the JSON-RPC envelope as the request/response schema. Streaming branch is not OpenAPI-expressible — link out to the markdown instead.

**Outcome:** `server/docs/api/endpoints/mcp.md` covers auth, methods, tool naming, the unified inputSchema, scope resolution (including the fallback fix from §1), conversation lifecycle, blocking vs SSE streaming, token-delta granularity, heartbeats, client disconnect, rate limits, curl examples, and how to run the conformance check. `server/docs/api/README.md` updated; `openapi.yaml` gained `/api/mcp` plus minimal JsonRpcRequest/Response schemas.

### 3. Plan housekeeping ✅ SHIPPED

This file moved from `plans/proposed/` to `plans/completed/` (the existing convention — the repo has had `plans/completed/` all along; that wasn't visible when the follow-up was written). `mcp-server-phase0-findings.md` came along with it.

### 4. Client-disconnect cancellation ✅ SHIPPED (`d19765f`)

When an MCP client drops mid-stream today, the agent loop keeps running, the LLM call completes, and the bounded queue eventually drops events on overflow. The work is wasted but otherwise harmless. For production traffic the right thing is to interrupt:

- The `SseBody/write-body-to-stream` loop already catches `IOException`/`EOFException` and sets `cancel-atom` to true. Extend the streaming executor's work fn to cancel the in-flight `Future` and interrupt the work thread when `cancel-atom` flips.
- The agent loop's `(when (Thread/interrupted) ...)` check at the top of each iteration aborts the loop; the in-flight LLM call cancels via hato's interrupt support.

Design lifted from `streaming-public-api-plan.md` §3. Roughly 1 day's work including a test that simulates a client disconnect.

**Outcome:** `digdir.mcp.transport/streaming-tools-call` captures the worker `Future` and watches `cancel?` — SseBody's writer flips it on IOException/EOFException from the client, the watcher calls `Future.cancel(true)`, the worker thread is interrupted. The agent loop checks `Thread/isInterrupted` at the top of each iteration and raises `InterruptedException`. Two tests: SseBody-disconnect → Future cancelled, pre-interrupted thread → loop throws.

### 5. Playground diagnostics snapshot test ✅ SHIPPED (`d19765f`)

Phase 1's risk list called for a snapshot test of the Playground's diagnostics map shape as the regression net for the `execute-skill-graph` refactor. We relied on the existing playground tests instead (11 passing). Worth adding the snapshot as defensive coverage — captures the full UI-visible map shape rather than the structural assertions the existing tests rely on. Half a day.

**Outcome:** `digdir.playground.core-test/execute-skill-graph-diagnostics-shape-snapshot` asserts the full 35-key UI-visible diagnostics map keyset is preserved. A future invoke-rag refactor that drops a key fails the test with the dropped name in the failure message.

### 6. End-to-end UI testing via OpenWebUI + Playwright ✅ SHIPPED (`6e5b0e7`)

Goal: a Playwright suite that drives a real browser session against an OpenWebUI instance configured to talk to our MCP server, sends queries to an MCP-exposed agent, and asserts on tool invocation + streaming response behavior. The harness gives us:

- Real MCP-client behavior across the wire (covers Follow-up §1 by side effect).
- A regression net that exercises auth, tool resolution, conversation persistence, and SSE streaming in the same flow real users hit.
- A reusable lane for testing future MCP additions (new tools, schemas, streaming changes) without writing per-feature integration tests.

Approach:

- **Bring up OpenWebUI** in a Docker Compose service alongside the dev server. OpenWebUI does not speak Streamable-HTTP MCP natively; it bridges through [MCPO](https://github.com/open-webui/mcpo), an MCP-to-OpenAPI proxy. The compose stack mounts: `digdir-rag-server` (our existing dev server), `mcpo` (configured with our API key + the `/api/mcp` URL), and `open-webui` (configured to use MCPO as a tool source).
- **Seed a deterministic dataset** with a small known corpus so the agent answers are predictable. Use the existing config DB seeding helpers; assert on chunk IDs in the response, not the exact wording.
- **Playwright test plan**:
  1. Open OpenWebUI, log in (or use a pre-configured token), pick the MCP-exposed agent from the tools list.
  2. Send a query that requires retrieval; assert the tool-call event appears in the UI, the streamed response renders paragraph by paragraph, and the final answer references the seeded chunks.
  3. Send a follow-up turn in the same conversation; assert `conversation_id` is reused (the assistant should reference the prior turn).
  4. Negative: revoke the API key behind the scenes; next query returns a visible 401 in OpenWebUI's UI rather than hanging.
- **CI integration**: gate behind a non-default `bb e2e` task so it doesn't run on every PR (slow + requires Docker). Run it on a nightly job and on PRs that touch `digdir.mcp.*` or `digdir.skills.invoke`.

Scope: 2–3 days for the initial setup (Compose stack, seed script, one happy-path test). Add tests opportunistically after that.

New files this would add:

- `server/docker-compose.e2e.yml` — compose stack for the three services.
- `server/e2e/mcpo-config.json` — MCPO config pointing at `/api/mcp`.
- `server/e2e/playwright.config.ts`, `server/e2e/tests/*.spec.ts` — Playwright project.
- `server/e2e/seed.clj` — Clojure script that seeds the deterministic dataset.
- `bb.edn` — new `:e2e` task that wires `docker compose up -d`, the seed script, and `npx playwright test`.

Risks: OpenWebUI updates may break the UI selectors Playwright relies on; pin a known-good image tag. MCPO's MCP support has been moving fast — confirm it handles Streamable HTTP + SSE before committing.

**Outcome:** `server/e2e/` contains the full harness — compose stack for digdir-rag + MCPO + OpenWebUI, MCPO config wired at `/api/mcp`, idempotent `seed.clj` that bootstraps agents and writes the API key into `mcpo/.env`, Playwright project with two specs (stack health check + happy-path tool invocation gated on `AZURE_OPENAI_API_KEY`). Five new `bb e2e:*` tasks (`up`, `down`, `seed`, `install-browsers`, `test`). The OpenWebUI / MCPO image versions are pinned. See `server/e2e/README.md` for usage and CI gating notes.

## Next Steps

**Goal:** validate the OpenWebUI + Playwright harness against a live stack, then merge the full MCP-server line of work to main. Light schema cleanup rides along.

The branch carries 12 commits and a full E2E harness that no one has booted yet. The risk we want to retire before review is "does MCPO actually speak Streamable-HTTP MCP against our endpoint?" — everything below that question has unit-test or conformance-script coverage. After that's confirmed, the work is ready to ship.

Ordered by ROI:

### N1. Boot the E2E stack end-to-end ⏳

The compose file resolves cleanly and `seed.clj` writes a valid API key, but no human has run `bb e2e:up && bb e2e:seed && bb e2e:test` start-to-finish. If MCPO's `streamable_http` transport trips on anything in our JSON-RPC framing or SSE handling, finding that out *before* this lands saves a debug cycle later.

Acceptance:
- `bb e2e:up` brings all three services healthy.
- `bb e2e:test stack-up` passes (health checks against dev server, MCPO `/openapi.json` discovers our tools, Open WebUI responds with the right page title).
- If the happy-path spec (`10-tool-invocation.spec.ts`) is run with `AZURE_OPENAI_API_KEY` set, the agent responds and the assistant bubble renders.

Time-boxed: ~1 hour. If MCPO is broken on our wire shape, file as a separate follow-up rather than blocking the PR — the rest of the harness (compose stack, seed, Playwright scaffold, doc) is still useful.

### N2. Open the PR ⏳

12 commits is a lot of value on a local branch. The natural PR boundary is "everything after the Phase 0 commits" — the `harmonize-runtime` branch already had Phase 0 landed before this session started, so the PR captures Phases 1, 3, 4, 4b plus all six follow-ups.

PR body should call out:
- Hard-deletes that already shipped (Phase 0) — link the merged commits.
- New surface: `POST /api/mcp` and the docs at `server/docs/api/endpoints/mcp.md`.
- New tests: 1311 / 4694 / 0 failures, including 35 new tests this session.
- Conformance script (`server/scripts/mcp_conformance_check.clj`) as the reviewer's smoke test.
- E2E harness (gated behind `bb e2e:*`, not part of `bb test`).
- The one bug the smoke test surfaced + its fix (`3efc147`, `c43f359`).

Time-boxed: ~15 minutes once N1 has confirmed the harness works.

### N3. Drop the stale `RagRequest` schema 🧹

Phase 0 deleted `/api/rag` but `server/docs/api/openapi.yaml` still defines a `RagRequest` schema (~line 876) with no path referencing it. Remove it in the same PR so reviewers don't get confused. ~5 minutes.

### N4. Recorded-LLM Playwright happy path ⏳ (deferred)

`10-tool-invocation.spec.ts` `skip()`-s itself without `AZURE_OPENAI_API_KEY`. Recording the SSE streams from a real LLM call and replaying them via an in-test HTTP fixture (nock-style) would let CI exercise the happy path without credentials. Nontrivial — the LLM stream interleaves with tool calls and tool results, and the recorded shape needs to track the agent loop's state machine.

Defer until N1/N2/N3 are merged and we've seen whether the E2E suite earns its keep. If it does, this is the highest-value next chunk of work. If the suite drifts into bit-rot, deleting it is fine too.

## Non-Goals

- **MCP-native OAuth flows.** Keep API-key bearer auth behind the MCP transport. OAuth can come later if we onboard customers who need it.
- **stdio MCP transport.** Streamable HTTP only for first ship. stdio is a future ergonomic add-on.
- **Replacing the Playground UI.** The Playground keeps calling `invoke-rag` directly in-process. It does not become an MCP client.
- **Branching conversations over MCP.** Linear only; branching stays a Playground-internal concept.
- **Generic, agent-agnostic skill-graph tools.** Tools are scoped to `(agent × allowed-skill-graph)`. Exposing skill-graphs *without an agent binding* (the dormant `/api/skill-graphs/*/execute` model) is not in scope — there's no demand and it bypasses the agent's dataset and policy scoping.
- **Retrieval-only as an MCP tool.** Retrieval-only is deprecated and removed in Phase 0. No MCP `retrieve` tool; clients use a full RAG agent.
- **Deprecation-window machinery.** Operator confirmed no live API clients; Phase 0 hard-deletes the custom routes rather than soft-deprecating them. No `Deprecation`/`Sunset` headers, no `MIGRATION.md`.

## Risks

Closed during implementation:
- ✅ **Playground diagnostics map shape** — preserved via the `:raw-result` passthrough in `invoke-rag`. The Playground's `execute-skill-graph` reads outputs from `:diagnostics.outputs` and the 11 existing playground tests pass unchanged. A dedicated snapshot test is still listed as a follow-up for defensive coverage (Follow-ups §5).
- ✅ **Application-level rate-limiting gap** — `digdir.api.rate-limit-api/wrap-api-rate-limit` (per-API-key sliding-window) shipped with Phase 3, applied to `/api/mcp`.
- ✅ **`streaming-public-api-plan.md` overlap** — marked superseded at the head of that file when Phase 4 landed; the per-paragraph chunker in Phase 4b reused the cancellation design.
- ✅ **Auth gap for tool listing** — `wrap-api-key-auth` populates the request map with `:api-key/agent-refs` and `:api-key/skill-graphs`; both `tools/list` and `tools/call` read those keys directly. No middleware change was needed.
- ✅ **String vs keyword skill-graph ids** — surfaced by the live smoke test, fixed in `3efc147` with a regression test in `mcp.tools-test`.

Closed in Phase 0 discovery:
- ✅ `assoc-execution-scope` idempotency (verified safe).
- ✅ Per-agent input schema generation (explicit `:input-schema` declared on registrations; no manual mapping table).
- ✅ Conversation cleanup story (no TTL for first ship; mirrors current API).

Still open (tracked under *Follow-ups*):
- Client-disconnect cancellation (Follow-ups §4).
- OpenWebUI/MCPO Streamable-HTTP support (Follow-ups §6) — confirm before standing up the E2E stack.

## File Map

**Phase 0 — Pruning and restructuring**

- Edited: `server/src/digdir/playground/core.cljc` — flip default skill-graph
- Edited: `server/src/digdir/demo/altinn_*.clj` — rename `:demo/*` → `:docs/*`
- Edited: `server/src-dev/digdir/demo/self_improve_*.clj` — rename `:digdir.demo/*` → `:docs/*`
- Edited: `server/test/digdir/demo/*_test.clj` — match renames
- Edited: `server/src/digdir/skills/templates/builtin.clj` — drop 4 unused registrations
- Edited: `server/src/digdir/skills/templates/core.clj` — `make-skill-graph` accepts `:input-schema`
- Edited: kept skill-graph definitions — declare `:input-schema` (Malli)
- New: `:builtin/docs-agent` registration (location TBD — likely a new `server/src/digdir/agents/builtin.clj` or seed file)
- Deleted: `api-rag-handler`, `api-retrieve-handler` from `server/src/digdir/api/routes/handlers.clj`
- Deleted: `execute-skill-graph-handler`, `execute-graph-handler` from `server/src/digdir/api/routes/endpoints.clj`
- Edited: `server/src/digdir/api/routes/endpoints.clj` / `routes.clj` — remove route wiring
- Deleted: `server/docs/api/openapi.yaml` paths for the removed endpoints
- Deleted: `server/docs/api/endpoints/rag.md`, `retrieve.md`
- Edited: `server/test/digdir/api/routes_test.clj` — remove tests for removed handlers
- Possibly deleted: `build-rag-skill-params`, `build-retrieval-skill-params` from `server/src/digdir/api/util.clj` if orphaned

**Phase 1 — `invoke-rag` extraction**

- New: `server/src/digdir/skills/invoke.clj`
- New: `server/test/digdir/skills/invoke_test.clj`
- Edited: `server/src/digdir/playground/core.cljc` — delegate to `invoke-rag`

**Phase 3 — MCP server**

- New: `server/src/digdir/mcp/transport.clj`
- New: `server/src/digdir/mcp/session.clj`
- New: `server/src/digdir/mcp/tools.clj`
- New: `server/src/digdir/api/rate_limit_api.clj` (or extend `rate_limit.clj`) — per-API-key rate limiter
- Edited: `server/src/digdir/api/routes.clj` — mount `POST /mcp`

**Phase 4 — Streaming**

- Edited: `plans/proposed/streaming-public-api-plan.md` — superseded note

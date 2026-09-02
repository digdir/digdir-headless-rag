# OpenAI-compatible /v1 surface — agents as models

Expose each agent (or agent × allowed-skill-graph pair) as an OpenAI
model so any OpenAI-compatible client — Open WebUI, cursor, continue,
LibreChat, raw `curl` — can talk to a digdir agent without an
intermediate orchestrator LLM.

## Why

The MCP surface already exposes agents as **tools**. That fits the
orchestrator pattern: an outer LLM decides when to call a tool and how
to format the result. But it forces a 2x latency and cost per turn
(outer LLM + inner agent LLM) and means Open WebUI users see an empty
"Select a model" dropdown until they wire a separate LLM.

Agents already have an LLM internally. Exposing them as **models**
collapses the chain: client → agent → answer. One hop, no outer LLM
required.

## Scope

### In

1. `GET /v1/models` returns a list of agent × allowed-skill-graph pairs
   in OpenAI's `{data: [{id, object: "model", owned_by, ...}]}` shape.
   IDs mirror MCP tool names so a user can reason about both surfaces
   the same way (`builtin/agent-rag-agent__agent-rag-graph-bundled`).

2. `POST /v1/chat/completions` accepts the OpenAI request shape
   `{model, messages, stream}`. Required behavior:
   - Resolve `model` → (agent-id, skill-graph-id). 404 with OpenAI-shape
     error if unknown.
   - Concatenate `messages` into the agent's conversation-history
     format: every message except the last user message becomes the
     history; the last user message becomes `user-query`.
   - Invoke the agent's skill graph via the existing `invoke/invoke-rag`
     entrypoint (the same one the MCP transport calls).
   - Return OpenAI's `chat.completion` shape for non-streaming, or
     SSE-encoded `chat.completion.chunk` events for `stream: true`.
   - Authenticate via existing API-key middleware, but also accept
     `Authorization: Bearer <key>` (what OpenAI clients send).

3. Auto-wire docker-compose.host.yml so Open WebUI sees the endpoint at
   boot via `OPENAI_API_BASE_URLS` + `OPENAI_API_KEYS`. The dropdown
   populates without manual settings clicks.

### Out

- Token usage accounting. We return `{prompt_tokens: 0, completion_tokens: 0}`
  for now — the agent loop's internal usage data is opaque to this
  layer. Wire real accounting later.
- Tool calls in the request. OpenAI clients can pass `tools` /
  `tool_choice`; we ignore those for now since the agent loop does its
  own tool-calling internally.
- Function-calling response shape. We never emit
  `{message: {tool_calls: [...]}}`. The agent always answers as
  assistant content.
- `/v1/embeddings`, `/v1/completions` (the legacy text-completion
  endpoint), `/v1/images`, etc.
- Streaming per-token deltas FROM the agent loop. For Phase 1 the
  streaming path emits one or two big chunks (intermediate
  observability events + the final answer). Per-token deltas need the
  agent loop's `call-llm` to emit `:response/chunk` events, which is a
  separate follow-up.

## Architecture

```
client ──HTTP──▶ /v1/models             (lists agents)
       ──HTTP──▶ /v1/chat/completions   (single turn)
                       │
                       ▼
              digdir.api.routes.openai-compat
              (resolves model → (agent, skill-graph), packs messages,
               calls invoke-rag, formats response)
                       │
                       ▼
              digdir.skills.invoke/invoke-rag
              (the same agent-runner the MCP transport uses)
```

`openai-compat` is a thin adapter — no agent-side logic duplicated. It
reuses `digdir.mcp.tools` helpers (visible-agents,
visible-skill-graphs-for-agent, resolve-agent, pick-dataset-scope) for
authorization and skill-graph resolution.

## Phased implementation

### Phase 1 — non-streaming chat + model listing

1. New ns `digdir.api.routes.endpoints.openai_compat`.
   - `list-models-handler`. Walks `(mcp-tools/list-tools principal)` and
     re-shapes each entry as `{id, object: "model", created,
     owned_by: "digdir-rag", _agent_id, _skill_graph_id}`. The leading
     underscores on metadata keys keep them out of the standard OpenAI
     shape but available for debug.
   - `chat-completions-handler`. Body: `{model, messages, stream?}`.
     Decompose messages into `(history, user-query)`. Resolve model
     via `parse-tool-name`. Call `invoke/invoke-rag` with the agent's
     execution scope. Format the response as a `chat.completion`.

2. Auth: extend `wrap-api-key-auth` to fall back from `X-API-Key` to
   `Authorization: Bearer <key>`. Both headers should work; Bearer is
   what OpenAI clients (and OWUI) send.

3. Routes added to `api-routes` in `endpoints.clj` under `/v1/`. Same
   auth middleware as `/api/mcp`.

4. Unit tests:
   - `list-models` returns the same number of entries as `list-tools`.
   - `chat-completions` with unknown model → 404 in OpenAI shape.
   - `chat-completions` with valid model + messages → 200 with
     `choices[0].message.content` populated from a mocked
     `invoke-rag`.
   - Bearer auth accepted; X-API-Key still accepted.

### Phase 2 — streaming chat-completion chunks

5. `chat-completions-handler` honors `stream: true`. Streams
   `data: {choices: [{delta: {content: "..."}}]}\n\n` chunks followed
   by `data: [DONE]\n\n`. Uses ring's `StreamableResponseBody` so
   Jetty doesn't buffer.

6. For Phase 1 of streaming: emit one delta chunk with the full
   response body, then `[DONE]`. Working baseline for OWUI's rendering.

7. For Phase 2 of streaming (separate slice): wire
   `progress-fn` events from the agent loop into deltas. Currently
   blocked on the agent loop emitting `:response/chunk` events from
   `call-llm` (per
   `plans/completed/streaming-public-api-plan.md`'s "Status:
   SUPERSEDED" note).

### Phase 3 — OWUI auto-discovery

8. Update `server/e2e/docker-compose.host.yml`'s `open-webui` service:
   ```
   OPENAI_API_BASE_URLS: http://host.docker.internal:8181/v1
   OPENAI_API_KEYS: ${E2E_API_KEY}
   ```
   On boot, OWUI fetches `/v1/models` and populates the dropdown.

9. Verify by hand: `bb e2e:host:down && bb e2e:host:up` → browse to
   localhost:3030 → "Select a model" → agents appear → pick one →
   chat → response.

## Done definition

- `curl -H "Authorization: Bearer $E2E_API_KEY" http://localhost:8181/v1/models`
  returns the same agents that `bb dev`'s MCP /tools/list does.
- `curl -X POST -H "Authorization: Bearer $E2E_API_KEY"
  -H "Content-Type: application/json"
  -d '{"model":"builtin/agent-rag-agent__agent-rag-graph-bundled",
       "messages":[{"role":"user","content":"Når ble Altinn 3 lansert?"}]}'
  http://localhost:8181/v1/chat/completions`
  returns a 200 with the agent's answer in OpenAI shape.
- Open WebUI's dropdown populates from `OPENAI_API_BASE_URLS`. Chat
  with the agent end-to-end works.
- Tests pass; existing `/api/mcp` tests unaffected.

## Followups (deferred 2026-05-28)

After the first OWUI multi-turn smoke test, four loose ends are tracked
for a later pass — all currently degrade UX but none blocks the
agent-as-model flow.

1. **Per-token streaming deltas.** The streaming path emits the agent's
   full response as one content delta because `agent/loop.clj`'s
   `call-llm` uses the blocking OpenAI path. Wiring `:response/chunk`
   events into the SSE writer would give OWUI a typewriter-style render.

2. **Token usage.** `usage.{prompt,completion,total}_tokens` are zero
   in every response. The data is on
   `(get-in result [:diagnostics :outputs :workspace-final :stage-timings])`
   — sum `:usage` across entries (filter on `:agent-llm` or take all
   non-nil-usage stages for true cost accounting). Phase deferred until
   the surface stabilizes.

3. **Orphan citation markers (the `[7]` case).** The agent's response
   text sometimes carries `[N]` references that didn't make it into
   the citation-index (likely workspace-trimmed before synthesis ran).
   `synthesis/renumber-citations` preserves the original markers when
   no remap exists, so OWUI sees them as plain text. Fix candidates:
   - Strip orphans in openai-compat before send (3-line regex,
     local).
   - Tighten `synthesis/renumber-citations` to also strip orphan
     markers when an index has no entry. Cleaner; shared module.

4. **Clickable inline citations.** OWUI 0.6.18 renders inline source
   chips as non-clickable when the URL is relative; the bottom-of-
   message source panel stays clickable either way. Today the chunks
   carry `:url` like `nb/dialogporten/index.md` (Hugo source-tree path).
   Fix: add a runtime config knob
   `skills.retrieval.citation-base-url` per dataset (e.g.
   `https://docs.altinn.studio`) plus a `index.md`-strip normalization
   in openai-compat's `build-citations`. With cache-invalidation
   already shipped (commits fd708d8..8977b1c) the toggle takes effect
   without a bb dev restart. ~30 LOC.

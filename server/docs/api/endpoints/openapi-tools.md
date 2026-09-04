# OpenAPI Tool Server (`/api/tools`)

Every `(agent, mode)` pair, rendered as an **OpenAPI 3.1 document** so any
client that consumes an "OpenAPI tool server" can tool-call a digdir agent.
[Open WebUI](https://github.com/open-webui/open-webui)'s external-tools feature
is the one this targets.

| | |
|---|---|
| Spec | `GET /api/tools/openapi.json` |
| Invoke | `POST /api/tools/call/{tool-name}` |
| Auth | `X-API-Key: <key>` or `Authorization: Bearer <key>` |

## Which surface do I want?

Three surfaces expose the same agents. They differ in **what the agent is**,
not in what it can do:

| Surface | The agent is | Reach for it when |
|---|---|---|
| [`/api/mcp`](./mcp.md) | an MCP **tool** | your client speaks MCP `2026-07-28` |
| [`/v1`](./openai-compat.md) | a **model** | you want the agent to *be* the conversation |
| **this** | an OpenAPI **tool** | you want some *other* model to call the agent mid-answer |

All three enumerate the same `(agent, mode)` axis, all three filter by API key,
and all three return the identifier you then call with.

## Why this exists at all

`/api/mcp` already advertises these tools, so a bridge from MCP to OpenAPI
should be all that is needed — and one exists: [MCPO](https://github.com/open-webui/mcpo).

**It cannot reach this server.** MCPO opens with `initialize`, and MCP
`2026-07-28` has no handshake, so it gets `400` / `-32022` and its client
crashes with an empty tool list. Open WebUI's own native MCP client fails
identically; both are built on the v1-era Python MCP SDK. This is client lag
rather than a defect here — the SDK's **v2** reference client negotiates
`2026-07-28` against this server and round-trips `tools/call` fine.

Bumping MCPO's SDK does not fix it, and MCPO has had no commit since
2026-02-27. So rather than depend on an unmaintained proxy to translate our own
tools, we serve the OpenAPI directly. The measurements are in
[`server/e2e/README.md`](../../../e2e/README.md).

**This is not a second source of truth.** The spec is generated from
`digdir.mcp.tools/list-tools` and every call goes through
`digdir.mcp.tools/invoke-tool` — the same two functions `/api/mcp` uses. A tool
cannot appear on one surface and not the other, and the request schema of a
tool here is its MCP `inputSchema`, verbatim.

## `GET /api/tools/openapi.json`

Returns an OpenAPI 3.1 document with one `POST /api/tools/call/{tool-name}`
operation per tool the calling key can reach.

```bash
curl -s https://admin.kunnskap.digdir.cloud/api/tools/openapi.json \
  -H "X-API-Key: $DIGDIR_API_KEY"
```

```json
{
  "openapi": "3.1.0",
  "info": { "title": "digdir-rag", "version": "1.0", "description": "…" },
  "servers": [{ "url": "https://admin.kunnskap.digdir.cloud" }],
  "paths": {
    "/api/tools/call/builtin.agent-rag-agent__agent-rag-graph-faithful": {
      "post": {
        "operationId": "builtin.agent-rag-agent__agent-rag-graph-faithful",
        "summary": "Agentic RAG Agent — Faithful",
        "description": "…",
        "requestBody": { "required": true, "content": { "application/json": { "schema": { "…": "the tool's MCP inputSchema" } } } }
      }
    }
  }
}
```

Three properties of this document are load-bearing rather than cosmetic:

- **`operationId` is the tool name.** Open WebUI hands it to the model as the
  function name *and* matches the model's call back to a route by scanning for
  it. Anything else would rename the agent on one surface and not the other.
- **Paths are absolute from the server root.** A client executes a tool as
  `configured-base-url + path`; a relative path silently produces a URL with no
  `/api/tools` segment.
- **The document is filtered per API key.** It lists only what that key can
  reach, so it is not a public catalogue and must not be cached across keys.
  A key that can reach nothing gets `"paths": {}` — a valid answer, not an
  error.

## `POST /api/tools/call/{tool-name}`

The body is the tool's arguments, as advertised by that tool's schema. It is
deliberately not validated against a fixed schema: arguments are per-tool and
open-ended, and this API's coercion strips fields it does not know about
(#174).

```bash
curl -s -X POST \
  "https://admin.kunnskap.digdir.cloud/api/tools/call/builtin.agent-rag-agent__agent-rag-graph-faithful" \
  -H "X-API-Key: $DIGDIR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"query": "Hva er Altinn?"}'
```

```json
{
  "answer": "Altinn er …",
  "status": "ok",
  "conversation_id": "…",
  "sources": [{ "uri": "https://…", "name": "Document title" }],
  "chunks": [ … ],
  "queries": [ … ]
}
```

`answer` leads because this payload is read by a model rather than by code, and
the first field is the one that survives truncation. Pass `conversation_id`
back on the next call to continue the conversation — the same handle
`tools/call` returns.

### A recoverable failure is a `200`

**`status` is what tells you a call failed, not the HTTP status.**

```json
{ "answer": "No dataset scope available. …", "status": "error",
  "error": { "code": "no_dataset_scope", "message": "…" } }
```

That looks wrong for an HTTP API and is deliberate. Open WebUI turns any status
`>= 400` into an opaque exception string, which reaches the model as text it
cannot act on. `no_dataset_scope` and `missing_query` are exactly the errors a
model *can* recover from — the first carries the most actionable message in
this codebase — so they come back as a readable result. `/api/mcp` returns the
same two as `isError: true` results rather than JSON-RPC errors, for the same
reason.

Statuses are reserved for failures no retry can fix:

| Status | Codes |
|---|---|
| `401` | missing or invalid API key |
| `403` | `agent_not_authorized`, `mode_not_authorized`, `agent_disabled` |
| `404` | `invalid_tool_name`, `agent_not_found`, `mode_not_allowed` |
| `429` | per-API-key rate limit (120 req / 60 s by default) |
| `500` | an unexpected server failure |

> **A fresh install hits a `500` here**, with `Dataset ref does not resolve to a
> canonical dataset runtime node`. That is the empty-corpus wall every surface
> in this repo meets before a dataset is materialised, reported as an internal
> error rather than as a missing prerequisite (#434) — not something specific to
> this endpoint.

## Wiring Open WebUI to it

Admin Settings → External Tools, or declaratively:

```yaml
TOOL_SERVER_CONNECTIONS: >-
  [{"url":"http://digdir-rag:8080",
    "path":"/api/tools/openapi.json",
    "type":"openapi",
    "auth_type":"bearer",
    "key":"<your api key>",
    "config":{"enable":true},
    "info":{"id":"digdir-rag","name":"digdir-rag"}}]
```

**`config.enable` is not optional.** Open WebUI's `get_tool_servers_data` skips
any connection without it, silently — the symptom is an empty tool list with
nothing in the logs. A successful connection logs
`Initialized 1 tool server(s)` at boot.

`docker-compose.newcomer.yml` ships this wiring; see
[`docs/onboarding.md` §4b](../../../../docs/onboarding.md#4b-see-it-answer-in-a-chat-ui--open-webui).

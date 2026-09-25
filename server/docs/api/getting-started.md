# Getting Started with the Digdir RAG API

This guide will help you make your first API request and understand the basics of working
with the Digdir RAG API.

The query surface is `POST /api/mcp` — a [Model Context Protocol](https://modelcontextprotocol.io)
server speaking JSON-RPC 2.0 over Streamable HTTP. It replaced the legacy `/api/rag` and
`/api/retrieve` endpoints, which were removed in Phase 0 of the MCP server migration
(`server/src/digdir/api/routes/handlers.clj:4-6`). See [endpoints/mcp.md](./endpoints/mcp.md)
for the full contract; this guide walks through a first request.

## Prerequisites

Before you begin, you'll need:

1. **An API Key** - Contact your administrator to obtain one, or mint one yourself through
   the Operator Console API if you have operator (JWT) access — see
   [Minting an API Key](#minting-an-api-key) below.
2. **An HTTP client** - cURL, Postman, or any HTTP library in your preferred language

Use the canonical `digdir/public-docs` example scope when testing a new client.

## Minting an API Key

If you're an operator (logged in via the Operator Console, `auth-token` cookie), create a
key scoped to the dataset you want to query:

```bash
curl -X POST https://rag.digdir.cloud/console-api/api-keys \
  -H "Content-Type: application/json" \
  -H "X-User-Email: user@example.com" \
  --cookie "auth-token=YOUR_JWT_TOKEN" \
  -d '{
    "name": "Getting Started Key",
    "dataset-scopes": [
      { "tenant": "digdir", "dataset-config-key": "public-docs" }
    ]
  }'
```

The response includes the plaintext key **once** — store it securely:

```json
{
  "api-key-id": "key_abc123",
  "api-key": "rag_a1b2c3d4e5f6789012345678901234567890123456789012345678901234",
  "name": "Getting Started Key"
}
```

See [Authentication](./authentication.md) and [API Keys](./endpoints/api-keys.md) for the
full key-management contract.

## Your First Request

Every MCP call is a JSON-RPC 2.0 envelope POSTed to `/api/mcp`, authenticated with the
`X-API-Key` header.

**Three request-metadata headers are also required**, and a request missing any of them is
rejected with `400` and JSON-RPC `-32020` before it reaches an agent:

| Header | Value | Required for |
|--------|-------|--------------|
| `MCP-Protocol-Version` | `2026-07-28` | every request |
| `Mcp-Method` | the `method` from your body | every request |
| `Mcp-Name` | the `params.name` from your body | `tools/call` |

They exist so an intermediary can route on the header while the server executes on the
body — which is why a header that *disagrees* with the body is rejected too, rather than
ignored. Full rules in [endpoints/mcp.md](./endpoints/mcp.md#what-a-client-must-send).

### Step 1 — Discover available tools

Each `(agent × allowed skill-graph)` pair is exposed as one MCP tool. List the ones your
key can use:

```bash
curl -sS -X POST https://rag.digdir.cloud/api/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: rag_your_api_key_here" \
  -H "MCP-Protocol-Version: 2026-07-28" \
  -H "Mcp-Method: tools/list" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq
```

This returns a `result.tools` array. The built-in RAG agent's tool is named
`builtin.agent-rag-agent__agent-rag-graph-bundled`.

### Step 2 — Call a tool

```bash
curl -sS -X POST https://rag.digdir.cloud/api/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: rag_your_api_key_here" \
  -H "MCP-Protocol-Version: 2026-07-28" \
  -H "Mcp-Method: tools/call" \
  -H "Mcp-Name: builtin.agent-rag-agent__agent-rag-graph-bundled" \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/call",
    "params": {
      "name": "builtin.agent-rag-agent__agent-rag-graph-bundled",
      "arguments": {
        "query": "Hva er Altinn?",
        "tenant": "digdir",
        "dataset_config_key": "public-docs"
      }
    }
  }'
```

### Using JavaScript (Node)

> **Server-side only.** Your API key is a long-lived credential scoped to datasets and
> agents, so it must never be shipped to a browser — and a browser cannot call this API
> directly in any case
> ([why, with the measurements](./authentication.md#the-api-key-is-server-side-only)).
> A web frontend calls **your** backend, and your backend calls this API. The pattern
> below is what belongs in that backend; for a runnable version of it, including the
> streaming case, see [`examples/browser-proxy/`](./examples/browser-proxy/README.md).

```javascript
const TOOL = 'builtin.agent-rag-agent__agent-rag-graph-bundled';

const response = await fetch('https://rag.digdir.cloud/api/mcp', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-API-Key': process.env.DIGDIR_API_KEY,
    'MCP-Protocol-Version': '2026-07-28',
    'Mcp-Method': 'tools/call',
    'Mcp-Name': TOOL
  },
  body: JSON.stringify({
    jsonrpc: '2.0',
    id: 1,
    method: 'tools/call',
    params: {
      name: TOOL,
      arguments: {
        'query': 'Hva er Altinn?',
        tenant: 'digdir',
        dataset_config_key: 'public-docs'
      }
    }
  })
});

const data = await response.json();
console.log(data.result.content[0].text);
```

### Using Python (requests)

```python
import os
import requests

TOOL = 'builtin.agent-rag-agent__agent-rag-graph-bundled'

response = requests.post(
    'https://rag.digdir.cloud/api/mcp',
    headers={
        'Content-Type': 'application/json',
        'X-API-Key': os.environ['DIGDIR_API_KEY'],
        'MCP-Protocol-Version': '2026-07-28',
        'Mcp-Method': 'tools/call',
        'Mcp-Name': TOOL,
    },
    json={
        'jsonrpc': '2.0',
        'id': 1,
        'method': 'tools/call',
        'params': {
            'name': TOOL,
            'arguments': {
                'query': 'Hva er Altinn?',
                'tenant': 'digdir',
                'dataset_config_key': 'public-docs'
            }
        }
    }
)

data = response.json()
print(data['result']['content'][0]['text'])
```

## Understanding the Response

A blocking (non-streaming) `tools/call` returns:

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [
      { "type": "text", "text": "Altinn er en digital plattform..." }
    ],
    "_meta": {
      "conversation_id": "convo-abc",
      "status": "complete"
    }
  }
}
```

| Field | Description |
|-------|-------------|
| `result.content[0].text` | The LLM-generated answer to your question |
| `result._meta.conversation_id` | Unique ID for this conversation (use for follow-ups) |
| `result._meta.status` | `"complete"` on success |

Set `params._meta.progressToken` on the request to switch to SSE streaming instead — see
[endpoints/mcp.md](./endpoints/mcp.md#blocking-vs-streaming) for frame shapes and token
deltas.

## Continuing a Conversation

To continue an existing conversation, pass the `conversation_id` from the previous response
as `arguments.conversation_id`:

```bash
curl -sS -X POST https://rag.digdir.cloud/api/mcp \
  -H "Content-Type: application/json" \
  -H "X-API-Key: rag_your_api_key_here" \
  -H "MCP-Protocol-Version: 2026-07-28" \
  -H "Mcp-Method: tools/call" \
  -H "Mcp-Name: builtin.agent-rag-agent__agent-rag-graph-bundled" \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
      "name": "builtin.agent-rag-agent__agent-rag-graph-bundled",
      "arguments": {
        "query": "Kan du forklare mer?",
        "tenant": "digdir",
        "dataset_config_key": "public-docs",
        "conversation_id": "convo-abc"
      }
    }
  }'
```

The API will use the conversation history to provide contextually relevant answers. MCP
conversations are server-managed and linear (no branching), and show up in the admin
Playground sidebar.

## Handling Errors

Transport-level failures return standard HTTP status codes:

| Status | Meaning |
|--------|---------|
| 401 | Unauthorized (invalid or missing API key) |
| 429 | Rate limited — 120 requests per 60s window per key by default |

```json
{
  "error": "Invalid or missing API key"
}
```

Protocol-level failures (bad method, bad params, tool errors) return a JSON-RPC error
object inside a `200` response instead of an HTTP error status:

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "error": { "code": -32601, "message": "Method not found", "data": { "method": "bogus" } }
}
```

## `tools/call` Arguments Reference

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `query` | string | yes | The user's question. `user-query` is accepted as an alias. |
| `conversation-history` | array | no | Vector of `{:role :text}` maps. Server-managed conversations fill this in automatically. |
| `model` | string | no | Explicit override. Leave unset to honor the skill-graph's runtime config. |
| `temperature` | number | no | Overrides the runtime default when present. |
| `tenant` | string | no | Scope override — required if the calling key/agent needs disambiguation. |
| `dataset_config_key` | string | no | Scope override — required if the calling key/agent needs disambiguation. |
| `conversation_id` | string | no | Reuse an existing conversation; omit to start a new one. |
| `overrides` | object | no | `retrieve-filter-by`, `retrieve-auto-filter` and `retrieve-top-k`; any other key is refused. See `endpoints/mcp.md`. |

See [endpoints/mcp.md](./endpoints/mcp.md#scope-resolution) for how `tenant` /
`dataset_config_key` are resolved against the API key's dataset scopes when omitted.

## Next Steps

- [Authentication](./authentication.md) - Learn about API key management
- [MCP Endpoint](./endpoints/mcp.md) - Full reference for `/api/mcp`
- [Client Smoke Flow](./examples/client-smoke-flow.md) - End-to-end contract check for a real client
- [Examples](./examples/curl-examples.md) - More cURL examples

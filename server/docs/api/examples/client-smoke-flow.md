# Client Smoke Flow

Use this flow to validate a real client application against the current contract.

The canonical test scope is:

- tenant: `digdir`
- dataset-config-key: `public-docs`
- runtime-config-key: `default`

## 1. Create Or Confirm An API Key

The key should be scoped like this:

```json
{
  "name": "Public Docs Integration",
  "dataset-scopes": [
    {
      "tenant": "digdir",
      "dataset-config-key": "public-docs"
    }
  ],
  "allowed-config-keys": [
    {
      "root": "dataset",
      "tenant": "digdir",
      "dataset-config-key": "public-docs"
    },
    {
      "root": "runtime",
      "tenant": "digdir",
      "runtime-config-key": "default"
    }
  ]
}
```

## 2. Verify Public Dataset Discovery

```bash
curl -X GET "$RAG_BASE_URL/api/datasets" \
  -H "X-API-Key: $RAG_API_KEY"
```

Expected result:

- one visible dataset with `id = public-docs`

## 3. Verify Dataset Detail

```bash
curl -X GET "$RAG_BASE_URL/api/datasets/public-docs" \
  -H "X-API-Key: $RAG_API_KEY"
```

Expected result:

- `dataset.id = public-docs`
- no pipeline collection in the response body

## 4. Verify Tool Discovery

```bash
curl -sS -X POST "$RAG_BASE_URL/api/mcp" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $RAG_API_KEY" \
  -H "MCP-Protocol-Version: 2026-07-28" \
  -H "Mcp-Method: tools/list" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

Expected result:

- `result.tools` array present
- includes a tool named `builtin.agent-rag-agent__agent-rag-graph-bundled`

## 5. Verify `tools/call` (blocking)

```bash
curl -sS -X POST "$RAG_BASE_URL/api/mcp" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $RAG_API_KEY" \
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

Expected result:

- `result.content[0].text` present (the generated answer)
- `result._meta.conversation_id` present
- `result._meta.status = "complete"`

## 6. Verify Conversation Continuation

Repeat the previous request with the returned `_meta.conversation_id` passed as
`conversation_id`:

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "builtin.agent-rag-agent__agent-rag-graph-bundled",
    "arguments": {
      "query": "Kan du forklare mer?",
      "tenant": "digdir",
      "dataset_config_key": "public-docs",
      "conversation_id": "<returned conversation_id>"
    }
  }
}
```

Expected result:

- request succeeds
- the same `conversation_id` remains valid and carries conversation history

See [MCP Endpoint](../endpoints/mcp.md) for the SSE streaming variant
(`_meta.progressToken`) and the full method/argument reference.

## Contract Invariants

If a client still sends any of these, it is not on the current contract:

- `environment`
- `pipeline`
- `config-key`
- `tenant-config-key`
- public `/api/datasets/:dataset-id/pipelines...` routes

And if a client sends an `/api/mcp` request **without** `MCP-Protocol-Version` and
`Mcp-Method` — plus `Mcp-Name` on `tools/call` — it is not on the current contract either.
That is the check this flow exists to make, so it is worth running against a real client
rather than reading: a missing header fails with `400` and `-32020` before the agent is
reached, which looks nothing like a retrieval problem.

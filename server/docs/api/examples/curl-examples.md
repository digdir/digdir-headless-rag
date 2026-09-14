# cURL Examples

Command-line examples for both the Public API and the internal Operator Console APIs.

## Setup

```bash
export RAG_BASE_URL="https://rag.digdir.cloud"
export RAG_API_KEY="rag_your_api_key_here"
export RAG_JWT_COOKIE="auth-token=YOUR_JWT_TOKEN"
```

## Public API

> **Every `/api/mcp` request needs three request-metadata headers** —
> `MCP-Protocol-Version`, `Mcp-Method`, and `Mcp-Name` on `tools/call`. A request missing
> any of them is rejected with `400` and JSON-RPC `-32020`. The MCP examples below carry
> them; see [endpoints/mcp.md](../endpoints/mcp.md#what-a-client-must-send) for the rules.

### List Visible Datasets

```bash
curl -X GET "$RAG_BASE_URL/api/datasets" \
  -H "X-API-Key: $RAG_API_KEY"
```

### Get One Dataset

```bash
curl -X GET "$RAG_BASE_URL/api/datasets/public-docs" \
  -H "X-API-Key: $RAG_API_KEY"
```

### MCP — List Available Tools

```bash
curl -sS -X POST "$RAG_BASE_URL/api/mcp" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $RAG_API_KEY" \
  -H "MCP-Protocol-Version: 2026-07-28" \
  -H "Mcp-Method: tools/list" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq
```

### MCP — Call A Tool (blocking)

```bash
curl -sS -X POST "$RAG_BASE_URL/api/mcp" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $RAG_API_KEY" \
  -H "MCP-Protocol-Version: 2026-07-28" \
  -H "Mcp-Method: tools/call" \
  -H "Mcp-Name: builtin.agent-rag-agent__agent-rag-graph-bundled" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
      "name": "builtin.agent-rag-agent__agent-rag-graph-bundled",
      "arguments": {
        "query": "Hva er Altinn?",
        "tenant": "digdir",
        "dataset_config_key": "public-docs"
      }
    }
  }' | jq
```

See [MCP Endpoint](../endpoints/mcp.md) for the streaming (SSE) variant and the full
method/argument reference.

### List Conversations

```bash
curl -X GET "$RAG_BASE_URL/api/conversations?page_size=10&page_index=0" \
  -H "X-API-Key: $RAG_API_KEY" \
  -H "X-User-Id: customer-user-123"
```

## Operator Console APIs

These endpoints are internal and JWT-authenticated.

### List Datasets

```bash
curl -X GET "$RAG_BASE_URL/console-api/datasets" \
  --cookie "$RAG_JWT_COOKIE"
```

### Create Dataset

```bash
curl -X POST "$RAG_BASE_URL/console-api/datasets" \
  -H "Content-Type: application/json" \
  -H "X-User-Email: user@example.com" \
  --cookie "$RAG_JWT_COOKIE" \
  -d '{
    "name": "Public Docs",
    "description": "Shared docs"
  }'
```

### Create Pipeline Under Dataset

```bash
curl -X POST "$RAG_BASE_URL/console-api/datasets/ds_123/pipelines" \
  -H "Content-Type: application/json" \
  -H "X-User-Email: user@example.com" \
  --cookie "$RAG_JWT_COOKIE" \
  -d '{
    "tenant": "digdir",
    "dataset-config-key": "public-docs",
    "pipeline-name": "assistant",
    "properties": {
      "name": "Assistant",
      "sourceType": "website"
    }
  }'
```

### Get Pipeline Detail

```bash
curl -X GET "$RAG_BASE_URL/console-api/datasets/ds_123/pipelines/assistant?tenant=digdir&dataset-config-key=public-docs" \
  --cookie "$RAG_JWT_COOKIE"
```

### Execute Pipeline

```bash
curl -X POST "$RAG_BASE_URL/console-api/datasets/ds_123/pipelines/assistant/execute?tenant=digdir&dataset-config-key=public-docs" \
  -H "X-User-Email: user@example.com" \
  --cookie "$RAG_JWT_COOKIE"
```

### List Pipeline Executions

```bash
curl -X GET "$RAG_BASE_URL/console-api/datasets/ds_123/pipelines/assistant/executions?tenant=digdir&dataset-config-key=public-docs" \
  --cookie "$RAG_JWT_COOKIE"
```

### Create API Key

```bash
curl -X POST "$RAG_BASE_URL/console-api/api-keys" \
  -H "Content-Type: application/json" \
  -H "X-User-Email: user@example.com" \
  --cookie "$RAG_JWT_COOKIE" \
  -d '{
    "name": "Public Docs Integration",
    "dataset-scopes": [
      {"tenant": "digdir", "dataset-config-key": "public-docs"}
    ],
    "allowed-config-keys": [
      {"root": "dataset", "tenant": "digdir", "dataset-config-key": "public-docs"},
      {"root": "runtime", "tenant": "digdir", "runtime-config-key": "default"}
    ]
  }'
```

## Response Processing

### Pretty Print JSON

```bash
curl -s -X GET "$RAG_BASE_URL/api/datasets" \
  -H "X-API-Key: $RAG_API_KEY" | jq .
```

### Extract the First Dataset ID

```bash
DATASET_ID=$(curl -s -X GET "$RAG_BASE_URL/api/datasets" \
  -H "X-API-Key: $RAG_API_KEY" | jq -r '.datasets[0].id')

echo "$DATASET_ID"
```

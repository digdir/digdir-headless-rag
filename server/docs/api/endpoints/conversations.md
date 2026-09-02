# Conversations Endpoints

Manage conversation history for RAG queries.

`X-User-Id` is a caller-supplied external identifier used only for public API conversation scoping. It is treated as opaque data and is unrelated to Playground/internal user accounts.

## Authentication

All endpoints require API key in `X-API-Key` header.

---

## List Conversations

`GET /api/conversations`

Get a paginated list of conversations.

### Query Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `page_size` | integer | 50 | Conversations per page (1–100) |
| `page_index` | integer | 0 | Page number (0-indexed) |
| `tags` | string | none | Optional comma-separated tags; conversations must include all listed tags |

When `tags` is supplied, the list only returns conversations that include every tag in the filter.

> **The query parameters are snake_case and the response fields are camelCase.**
> You send `page_size` and read back `pageSize`. That asymmetry is real rather
> than a typo here, and it is asserted by a test.

### Headers

| Header | Required | Description |
|--------|----------|-------------|
| `X-API-Key` | Yes | Your API key |
| `X-User-Id` | Yes | Opaque external caller identifier used to scope conversations |

### Example Request

```bash
curl -X GET "https://admin.kunnskap.digdir.cloud/api/conversations?page_size=10&page_index=0&tags=alpha,beta" \
  -H "X-API-Key: rag_your_api_key" \
  -H "X-User-Id: customer-user-123"
```

### Success Response (200)

```json
{
  "conversations": [
    {
      "id": "dPPIA0UWuF4JPMGBUDbjD",
      "topic": "Spørsmål om Altinn",
      "agentId": "builtin/agent-rag",
      "userId": "customer-user-123",
      "tags": ["alpha", "beta"],
      "created": 1704067200
    },
    {
      "id": "xyz789abc",
      "topic": "Universell utforming",
      "agentId": "builtin/agent-rag",
      "userId": "customer-user-123",
      "tags": ["beta"],
      "created": 1704053600
    }
  ],
  "total": 42,
  "pageSize": 10,
  "pageIndex": 0
}
```

---

## Create Conversation

`POST /api/conversations`

Create a new conversation.

### Headers

| Header | Required | Description |
|--------|----------|-------------|
| `Content-Type` | Yes | Must be `application/json` |
| `X-API-Key` | Yes | Your API key |
| `X-User-Id` | Yes | Opaque external caller identifier used to scope conversations |

### Body Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `title` | string | No | Conversation title |
| `filterValue` | object | No | Filter configuration |
| `tags` | array<string> | No | Conversation tags |
| `agent-id` | string | Sometimes | Which agent the conversation belongs to. Required when the key does not resolve to exactly one agent — see below. `agentId` is accepted as an alias. |

### Example Request

```bash
curl -X POST https://admin.kunnskap.digdir.cloud/api/conversations \
  -H "Content-Type: application/json" \
  -H "X-API-Key: rag_your_api_key" \
  -H "X-User-Id: customer-user-123" \
  -d '{
    "title": "My New Conversation",
    "tags": ["alpha", "beta"]
  }'
```

### Success Response (201)

The conversation is **wrapped** in a `conversation` key.

```json
{
  "conversation": {
    "id": "newConvoId123",
    "topic": "My New Conversation",
    "agentId": "builtin/agent-rag",
    "userId": "customer-user-123",
    "tags": ["alpha", "beta"],
    "created": 1704067200
  }
}
```

> **This endpoint needs to know WHICH agent, and it can only guess when there is
> exactly one candidate.** That is a defaulting rule, not an authorization one:
> an empty `agent-refs` grants **every** agent (see
> [api-keys.md](api-keys.md)), so a key with no grants is authorized here — it
> just leaves the server with many candidates and no basis to choose.
>
> Pass **`agent-id`** and it always works, whatever the key grants. Measured:
>
> | key grants | no `agent-id` | with `agent-id` |
> |---|---|---|
> | none (= all agents) | `401 "API key missing agent grants"` | **`201`, for any agent named** |
> | exactly one | `201`, defaults to it | `201` |
> | more than one | `400 "…specify agent-id"` | `201` if granted, `403` if not |
>
> **The `401` in the first row is a poor description of that state** — the key
> is authorized, and the same key succeeds the moment `agent-id` is supplied.
> Whether it should become the same `400` as the multi-grant row is open in
> [#349](https://github.com/itonomi/digdir-headless-rag/issues/349); do not
> depend on the status code for that case.

---

## Get Conversation

`GET /api/conversations/:id`

Get a specific conversation with all its messages.

### Query Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `include_diagnostics` | boolean | `false` | When `true`, include normalized message diagnostics for messages that have stored diagnostics |

### Path Parameters

| Parameter | Description |
|-----------|-------------|
| `id` | Conversation ID |

### Example Request

```bash
curl -X GET "https://admin.kunnskap.digdir.cloud/api/conversations/dPPIA0UWuF4JPMGBUDbjD?include_diagnostics=true" \
  -H "X-API-Key: rag_your_api_key" \
  -H "X-User-Id: customer-user-123"
```

### Success Response (200)

```json
{
  "conversation": {
    "id": "dPPIA0UWuF4JPMGBUDbjD",
    "topic": "Spørsmål om Altinn",
    "agentId": "builtin/agent-rag",
    "userId": "customer-user-123",
    "tags": ["alpha", "beta"],
    "created": 1704067200
  },
  "messages": [
    {
      "id": "msg_001",
      "text": "Hva er Altinn?",
      "role": "user",
      "tags": ["alpha"],
      "created": 1704067200
    },
    {
      "id": "msg_002",
      "text": "Altinn er en digital plattform...",
      "role": "assistant",
      "tags": [],
      "created": 1704067205,
      "diagnostics": {
        "status": "needs_clarification",
        "run-summary": {
          "search-passes": 1,
          "read-operations": 1,
          "backend-issue-count": 0,
          "sufficiency-count": 0
        }
      },
      "chunks": [
        {
          "chunk-id": "chunk_abc123",
          "doc-title": "Om Altinn",
          "content-markdown": "..."
        }
      ]
    },
    {
      "id": "msg_003",
      "text": "Kan du forklare mer?",
      "role": "user",
      "created": 1704067300
    },
    {
      "id": "msg_004",
      "text": "Altinn tilbyr flere tjenester...",
      "role": "assistant",
      "created": 1704067305
    }
  ]
}
```

`diagnostics` is optional and only appears when:

- the request includes `include_diagnostics=true`
- the message has stored diagnostics

Older conversations may not include diagnostics.

### Error Response (404)

```json
{
  "error": "Conversation not found"
}
```

---

## Update Conversation

`PUT /api/conversations/:id`

Update a conversation (e.g., rename).

### Path Parameters

| Parameter | Description |
|-----------|-------------|
| `id` | Conversation ID |

### Body Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `title` | string | No | New conversation title |
| `tags` | array<string> | No | Replace the conversation tag set |

### Example Request

```bash
curl -X PUT https://admin.kunnskap.digdir.cloud/api/conversations/dPPIA0UWuF4JPMGBUDbjD \
  -H "Content-Type: application/json" \
  -H "X-API-Key: rag_your_api_key" \
  -H "X-User-Id: customer-user-123" \
  -d '{
    "title": "Updated Title",
    "tags": ["alpha", "gamma"]
  }'
```

### Success Response (200)

Wrapped in a `conversation` key, like `POST`.

```json
{
  "conversation": {
    "id": "dPPIA0UWuF4JPMGBUDbjD",
    "topic": "Updated Title",
    "agentId": "builtin/agent-rag",
    "userId": "customer-user-123",
    "tags": ["alpha", "gamma"],
    "created": 1704067200
  }
}
```

---

## Delete Conversation

`DELETE /api/conversations/:id`

Delete a conversation and all its messages.

### Path Parameters

| Parameter | Description |
|-----------|-------------|
| `id` | Conversation ID |

### Example Request

```bash
curl -X DELETE https://admin.kunnskap.digdir.cloud/api/conversations/dPPIA0UWuF4JPMGBUDbjD \
  -H "X-API-Key: rag_your_api_key" \
  -H "X-User-Id: customer-user-123"
```

### Success Response (200)

```json
{
  "success": true
}
```

### Error Response (404)

```json
{
  "error": "Conversation not found"
}
```

---

## Data Model

### Conversation Object

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Unique conversation identifier |
| `topic` | string | Conversation title/topic |
| `agentId` | string | Agent the conversation belongs to |
| `userId` | string | Exact `X-User-Id` value supplied by the API caller |
| `tags` | array<string> | Conversation tags |
| `created` | integer | Unix timestamp of creation |

### Message Object

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Unique message identifier |
| `text` | string | Message content |
| `role` | string | `"user"` or `"assistant"` |
| `tags` | array<string> | Message tags |
| `created` | integer | Unix timestamp |
| `chunks` | array | (Assistant messages only) Source chunks used |

---

## Notes

- Response field names are **camelCase** (`agentId`, `userId`, `pageSize`, `pageIndex`), while request query parameters are snake_case (`page_size`, `page_index`). Both are asserted against the running handlers by `digdir.api.doc-fidelity-smoke-test`
- `POST` and `PUT` wrap the conversation in a `conversation` key; `DELETE` returns `{"success": true}`
- Conversations are automatically created when using `POST /api/mcp` `tools/call` without a `conversation_id`
- `X-User-Id` is required for all public conversation endpoints; there is no unscoped list-all mode
- A conversation can only be fetched, updated, or deleted by the same external `X-User-Id` that owns it
- The `topic` is auto-generated from the first question if not provided
- Deleting a conversation removes all associated messages and chunk references
- Messages are ordered chronologically (oldest first)

## Related Endpoints

- [MCP](./mcp.md) - Create conversations via `tools/call`

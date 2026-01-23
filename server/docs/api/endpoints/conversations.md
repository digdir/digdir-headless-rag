# Conversations Endpoints

Manage conversation history for RAG queries.

## Authentication

All endpoints require API key in `X-API-Key` header.

---

## List Conversations

`GET /api/conversations`

Get a paginated list of conversations.

### Query Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `page_size` | integer | 50 | Conversations per page |
| `page_index` | integer | 0 | Page number (0-indexed) |

### Headers

| Header | Required | Description |
|--------|----------|-------------|
| `X-API-Key` | Yes | Your API key |
| `X-User-Email` | No | Filter by user email |

### Example Request

```bash
curl -X GET "https://admin.kunnskap.digdir.cloud/api/conversations?page_size=10&page_index=0" \
  -H "X-API-Key: rag_your_api_key"
```

### Success Response (200)

```json
{
  "conversations": [
    {
      "id": "dPPIA0UWuF4JPMGBUDbjD",
      "topic": "Spørsmål om Altinn",
      "entity-id": "entity-123",
      "user-id": "user@example.com",
      "created": 1704067200
    },
    {
      "id": "xyz789abc",
      "topic": "Universell utforming",
      "entity-id": "entity-123",
      "user-id": "user@example.com",
      "created": 1704053600
    }
  ],
  "total": 42,
  "page_size": 10,
  "page_index": 0
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
| `X-User-Email` | Yes | User email for the conversation |

### Body Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `title` | string | No | Conversation title |
| `filterValue` | object | No | Filter configuration |

### Example Request

```bash
curl -X POST https://admin.kunnskap.digdir.cloud/api/conversations \
  -H "Content-Type: application/json" \
  -H "X-API-Key: rag_your_api_key" \
  -H "X-User-Email: user@example.com" \
  -d '{
    "title": "My New Conversation"
  }'
```

### Success Response (201)

```json
{
  "id": "newConvoId123",
  "topic": "My New Conversation",
  "entity-id": "entity-123",
  "user-id": "user@example.com",
  "created": 1704067200
}
```

---

## Get Conversation

`GET /api/conversations/:id`

Get a specific conversation with all its messages.

### Path Parameters

| Parameter | Description |
|-----------|-------------|
| `id` | Conversation ID |

### Example Request

```bash
curl -X GET https://admin.kunnskap.digdir.cloud/api/conversations/dPPIA0UWuF4JPMGBUDbjD \
  -H "X-API-Key: rag_your_api_key"
```

### Success Response (200)

```json
{
  "conversation": {
    "id": "dPPIA0UWuF4JPMGBUDbjD",
    "topic": "Spørsmål om Altinn",
    "entity-id": "entity-123",
    "user-id": "user@example.com",
    "created": 1704067200
  },
  "messages": [
    {
      "id": "msg_001",
      "text": "Hva er Altinn?",
      "role": "user",
      "created": 1704067200
    },
    {
      "id": "msg_002",
      "text": "Altinn er en digital plattform...",
      "role": "assistant",
      "created": 1704067205,
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

### Example Request

```bash
curl -X PUT https://admin.kunnskap.digdir.cloud/api/conversations/dPPIA0UWuF4JPMGBUDbjD \
  -H "Content-Type: application/json" \
  -H "X-API-Key: rag_your_api_key" \
  -d '{
    "title": "Updated Title"
  }'
```

### Success Response (200)

```json
{
  "id": "dPPIA0UWuF4JPMGBUDbjD",
  "topic": "Updated Title",
  "entity-id": "entity-123",
  "user-id": "user@example.com",
  "created": 1704067200
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
  -H "X-API-Key: rag_your_api_key"
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
| `entity-id` | string | Entity the conversation belongs to |
| `user-id` | string | User who created the conversation |
| `created` | integer | Unix timestamp of creation |

### Message Object

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Unique message identifier |
| `text` | string | Message content |
| `role` | string | `"user"` or `"assistant"` |
| `created` | integer | Unix timestamp |
| `chunks` | array | (Assistant messages only) Source chunks used |

---

## Notes

- Conversations are automatically created when using `/api/rag` without a `conversation-id`
- The `topic` is auto-generated from the first question if not provided
- Deleting a conversation removes all associated messages and chunk references
- Messages are ordered chronologically (oldest first)

## Related Endpoints

- [RAG](./rag.md) - Create conversations via RAG queries

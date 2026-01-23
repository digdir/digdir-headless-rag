# RAG Endpoint

`POST /api/rag`

Execute a full RAG (Retrieval-Augmented Generation) pipeline: retrieve relevant documents and generate an answer using an LLM.

## Authentication

Requires API key in `X-API-Key` header.

## Request

### Headers

| Header | Required | Description |
|--------|----------|-------------|
| `Content-Type` | Yes | Must be `application/json` |
| `X-API-Key` | Yes | Your API key |

### Body Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `query` | string | Yes | - | The question to ask |
| `conversation-id` | string | No | auto-generated | Continue an existing conversation |
| `model` | string | No | `gpt-4o-2024-11-20` | LLM model to use |
| `rerank-top-k` | integer | No | entity config | Maximum chunks to rerank |
| `rerank-max-chunk-length` | integer | No | entity config | Max length per chunk for reranking |
| `rerank-max-length` | integer | No | entity config | Max total length for reranking |
| `context-top-k` | integer | No | entity config | Maximum chunks for context |
| `context-max-chunk-length` | integer | No | entity config | Max length per context chunk |
| `max-context-length` | integer | No | entity config | Maximum total context length |

### Example Request

```bash
curl -X POST https://admin.kunnskap.digdir.cloud/api/rag \
  -H "Content-Type: application/json" \
  -H "X-API-Key: rag_your_api_key" \
  -d '{
    "query": "Hva er Altinn?",
    "context-top-k": 5
  }'
```

## Response

### Success Response (200)

```json
{
  "answer": "Altinn er en digital plattform som tilbyr offentlige tjenester i Norge. Plattformen gjør det mulig for innbyggere og næringsliv å kommunisere med offentlige etater, levere skjemaer, og få tilgang til ulike offentlige tjenester digitalt.",
  "conversation-id": "dPPIA0UWuF4JPMGBUDbjD",
  "model": "gpt-4o-2024-11-20",
  "chunks-used": [
    {
      "chunk-id": "chunk_abc123",
      "doc-title": "Om Altinn - Digitaliseringsdirektoratet",
      "doc-num": "doc_001",
      "content-markdown": "# Om Altinn\n\nAltinn er Norges viktigste digitale plattform..."
    },
    {
      "chunk-id": "chunk_def456",
      "doc-title": "Altinn-samarbeidet",
      "doc-num": "doc_002",
      "content-markdown": "## Altinn-samarbeidet\n\nAltinn drives av et samarbeid..."
    }
  ]
}
```

### Response Fields

| Field | Type | Description |
|-------|------|-------------|
| `answer` | string | The LLM-generated answer to your question |
| `conversation-id` | string | Unique ID for this conversation |
| `model` | string | The LLM model that generated the answer |
| `chunks-used` | array | Source documents used to generate the answer |
| `chunks-used[].chunk-id` | string | Unique chunk identifier |
| `chunks-used[].doc-title` | string | Title of the source document |
| `chunks-used[].doc-num` | string | Document number |
| `chunks-used[].content-markdown` | string | Chunk content in markdown format |

### Error Responses

**400 Bad Request** - Missing required field
```json
{
  "error": "Missing required field: query"
}
```

**401 Unauthorized** - Invalid API key
```json
{
  "error": "Invalid or missing API key"
}
```

**404 Not Found** - Entity not found
```json
{
  "error": "Entity not found: entity-123"
}
```

**500 Internal Server Error**
```json
{
  "error": "Internal server error"
}
```

## Use Cases

### Simple Question

```json
{
  "query": "Hva er kravene for universell utforming?"
}
```

### Continuing a Conversation

Use the `conversation-id` from a previous response to continue the conversation with context:

```json
{
  "query": "Kan du gi meg mer detaljer om dette?",
  "conversation-id": "dPPIA0UWuF4JPMGBUDbjD"
}
```

### Custom Model and Parameters

```json
{
  "query": "Forklar WCAG 2.1 kravene",
  "model": "gpt-4o-2024-11-20",
  "context-top-k": 10,
  "max-context-length": 8000
}
```

## Pipeline Details

The RAG pipeline executes these steps:

1. **Query Relaxation** - LLM expands the query into multiple search phrases
2. **Multi-Strategy Search** - Searches phrases, metadata, and content in parallel
3. **Result Merging** - Combines and deduplicates search results
4. **Chunk Retrieval** - Fetches full content for matched chunks
5. **Reranking** - ColBERT reranks chunks by relevance
6. **Generation** - LLM generates answer using top chunks as context
7. **Persistence** - Saves conversation and chunks to database

## Notes

- Conversation history is stored and can be retrieved via `/api/conversations/:id`
- The model uses entity-specific prompts for query relaxation and generation
- Chunk content may be truncated based on context length parameters
- The answer language matches the configured entity (typically Norwegian)

## Related Endpoints

- [Retrieve](./retrieve.md) - Get chunks without LLM generation
- [Conversations](./conversations.md) - Manage conversation history

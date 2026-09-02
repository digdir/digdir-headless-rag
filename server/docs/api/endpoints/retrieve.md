# Retrieve Endpoint

`POST /api/retrieve`

Retrieve relevant documents without LLM generation. Use this when you want to handle generation yourself or just need the source documents.

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
| `query` | string | Yes | - | The search query |
| `top_k` | integer | No | 10 | Number of chunks to return |
| `include_query_expansion` | boolean | No | true | Use LLM to expand the query |
| `filter` | object | No | - | Filter by document metadata |

### Filter Object

```json
{
  "filter": {
    "fields": [
      {
        "field": "owner_short",
        "selected_options": ["Digdir", "NAV"],
        "value_type": "string"
      }
    ]
  }
}
```

| Field | Type | Description |
|-------|------|-------------|
| `fields` | array | Array of filter conditions |
| `fields[].field` | string | Metadata field name to filter on |
| `fields[].selected_options` | array | Values to match (OR logic) |
| `fields[].value_type` | string | `"string"` or `"integer"` |

### Example Request

```bash
curl -X POST https://admin.kunnskap.digdir.cloud/api/retrieve \
  -H "Content-Type: application/json" \
  -H "X-API-Key: rag_your_api_key" \
  -d '{
    "query": "Hva er Altinn?",
    "top_k": 5
  }'
```

## Response

### Success Response (200)

```json
{
  "chunks": [
    {
      "chunk_id": "chunk_abc123",
      "content_markdown": "# Om Altinn\n\nAltinn er Norges viktigste digitale plattform for offentlige tjenester...",
      "metadata": "### Om Altinn\n#### Digitale tjenester",
      "metadata_raw": "{\"Header 1\": \"Om Altinn\", \"Header 2\": \"Digitale tjenester\"}",
      "document": {
        "doc_num": "doc_001",
        "title": "Om Altinn - Digitaliseringsdirektoratet",
        "url": "https://www.digdir.no/altinn/om-altinn"
      },
      "relevance": {
        "rerank_position": 1,
        "original_position": 3,
        "normalized_score": 0.95,
        "search_types": ["phrase", "metadata"],
        "hit_count": 2
      }
    },
    {
      "chunk_id": "chunk_def456",
      "content_markdown": "## Altinn-samarbeidet\n\nAltinn drives av et samarbeid mellom flere offentlige etater...",
      "metadata": "### Altinn-samarbeidet",
      "metadata_raw": "{\"Header 1\": \"Altinn-samarbeidet\"}",
      "document": {
        "doc_num": "doc_002",
        "title": "Altinn-samarbeidet",
        "url": "https://www.altinn.no/om-altinn"
      },
      "relevance": {
        "rerank_position": 2,
        "original_position": 1,
        "normalized_score": 0.89,
        "search_types": ["phrase", "content"],
        "hit_count": 2
      }
    }
  ],
  "query_expansion": {
    "enabled": true,
    "expanded_queries": [
      "Altinn",
      "hva er Altinn",
      "Altinn plattform",
      "Altinn offentlige tjenester"
    ]
  },
  "search_stats": {
    "phrase": 12,
    "metadata": 5,
    "content": 8,
    "merged": 20
  }
}
```

### Response Fields

| Field | Type | Description |
|-------|------|-------------|
| `chunks` | array | Ranked list of relevant chunks |
| `chunks[].chunk_id` | string | Unique chunk identifier |
| `chunks[].content_markdown` | string | Full chunk content in markdown |
| `chunks[].metadata` | string | Formatted metadata headers |
| `chunks[].metadata_raw` | string | Raw metadata as JSON string |
| `chunks[].document` | object | Source document information |
| `chunks[].document.doc_num` | string | Document number |
| `chunks[].document.title` | string | Document title |
| `chunks[].document.url` | string | Document URL |
| `chunks[].relevance` | object | Relevance scoring information |
| `chunks[].relevance.rerank_position` | integer | Position after ColBERT reranking (1-indexed) |
| `chunks[].relevance.original_position` | integer | Position before reranking |
| `chunks[].relevance.normalized_score` | number | Relevance score (0-1) |
| `chunks[].relevance.search_types` | array | Search methods that found this chunk |
| `chunks[].relevance.hit_count` | integer | Number of search methods that matched |
| `query_expansion` | object | Query expansion information |
| `query_expansion.enabled` | boolean | Whether expansion was used |
| `query_expansion.expanded_queries` | array | The expanded search queries |
| `search_stats` | object | Search statistics |
| `search_stats.phrase` | integer | Chunks from phrase search |
| `search_stats.metadata` | integer | Chunks from metadata search |
| `search_stats.content` | integer | Chunks from content search |
| `search_stats.merged` | integer | Total unique chunks |

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

## Use Cases

### Simple Retrieval

```json
{
  "query": "universell utforming krav"
}
```

### Fast Retrieval (No Query Expansion)

Skip the LLM query expansion for faster responses:

```json
{
  "query": "WCAG 2.1",
  "include_query_expansion": false,
  "top_k": 10
}
```

### Filtered Retrieval

Filter by document owner:

```json
{
  "query": "tilgjengelighet",
  "top_k": 5,
  "filter": {
    "fields": [
      {
        "field": "owner_short",
        "selected_options": ["Digdir"],
        "value_type": "string"
      }
    ]
  }
}
```

## Search Strategy

The endpoint uses three parallel search strategies:

1. **Phrase Search** - Matches pre-generated search phrases (with embeddings)
2. **Metadata Search** - Full-text search on document metadata fields
3. **Content Search** - Full-text search on chunk content

Results are:
- Merged and deduplicated
- Scored with normalized relevance (0-1)
- Reranked using ColBERT for better ordering
- Limited to `top_k` results

## When to Use Retrieve vs RAG

| Use Case | Endpoint |
|----------|----------|
| Need a generated answer | `/api/rag` |
| Building custom UI with sources | `/api/retrieve` |
| Using your own LLM | `/api/retrieve` |
| Debugging retrieval quality | `/api/retrieve` |
| Lower latency (no generation) | `/api/retrieve` |
| Need conversation history | `/api/rag` |

## Notes

- This endpoint is stateless - no data is persisted
- Query expansion adds ~200-500ms latency but improves recall
- The `search_types` field helps debug which search strategy found each chunk
- Results are ordered by rerank position (best match first)

## Related Endpoints

- [RAG](./rag.md) - Full RAG with LLM generation

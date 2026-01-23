# cURL Examples

Command-line examples for all Digdir RAG API endpoints.

## Setup

Set your API key as an environment variable:

```bash
export RAG_API_KEY="rag_your_api_key_here"
export RAG_BASE_URL="https://admin.kunnskap.digdir.cloud"
```

---

## RAG Endpoint

### Simple Query

```bash
curl -X POST "$RAG_BASE_URL/api/rag" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $RAG_API_KEY" \
  -d '{
    "query": "Hva er Altinn?"
  }'
```

### With Custom Parameters

```bash
curl -X POST "$RAG_BASE_URL/api/rag" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $RAG_API_KEY" \
  -d '{
    "query": "Hva er kravene for universell utforming?",
    "model": "gpt-4o-2024-11-20",
    "context-top-k": 5,
    "rerank-top-k": 20
  }'
```

### Continue Conversation

```bash
curl -X POST "$RAG_BASE_URL/api/rag" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $RAG_API_KEY" \
  -d '{
    "query": "Kan du forklare mer om dette?",
    "conversation-id": "dPPIA0UWuF4JPMGBUDbjD"
  }'
```

---

## Retrieve Endpoint

### Simple Retrieval

```bash
curl -X POST "$RAG_BASE_URL/api/retrieve" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $RAG_API_KEY" \
  -d '{
    "query": "Hva er Altinn?",
    "top_k": 5
  }'
```

### Without Query Expansion (Faster)

```bash
curl -X POST "$RAG_BASE_URL/api/retrieve" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $RAG_API_KEY" \
  -d '{
    "query": "WCAG 2.1",
    "top_k": 10,
    "include_query_expansion": false
  }'
```

### With Metadata Filter

```bash
curl -X POST "$RAG_BASE_URL/api/retrieve" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $RAG_API_KEY" \
  -d '{
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
  }'
```

---

## Conversations

### List Conversations

```bash
curl -X GET "$RAG_BASE_URL/api/conversations?page_size=10&page_index=0" \
  -H "X-API-Key: $RAG_API_KEY"
```

### List Conversations for Specific User

```bash
curl -X GET "$RAG_BASE_URL/api/conversations" \
  -H "X-API-Key: $RAG_API_KEY" \
  -H "X-User-Email: user@example.com"
```

### Create Conversation

```bash
curl -X POST "$RAG_BASE_URL/api/conversations" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $RAG_API_KEY" \
  -H "X-User-Email: user@example.com" \
  -d '{
    "title": "My New Conversation"
  }'
```

### Get Conversation with Messages

```bash
curl -X GET "$RAG_BASE_URL/api/conversations/dPPIA0UWuF4JPMGBUDbjD" \
  -H "X-API-Key: $RAG_API_KEY"
```

### Update Conversation Title

```bash
curl -X PUT "$RAG_BASE_URL/api/conversations/dPPIA0UWuF4JPMGBUDbjD" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $RAG_API_KEY" \
  -d '{
    "title": "Updated Title"
  }'
```

### Delete Conversation

```bash
curl -X DELETE "$RAG_BASE_URL/api/conversations/dPPIA0UWuF4JPMGBUDbjD" \
  -H "X-API-Key: $RAG_API_KEY"
```

---

## API Keys (Admin)

These endpoints require JWT authentication. First, obtain a JWT token by logging into the admin interface.

### List Your API Keys

```bash
curl -X GET "$RAG_BASE_URL/api/keys" \
  --cookie "auth-token=YOUR_JWT_TOKEN"
```

### Create New API Key

```bash
curl -X POST "$RAG_BASE_URL/api/keys" \
  -H "Content-Type: application/json" \
  --cookie "auth-token=YOUR_JWT_TOKEN" \
  -d '{
    "name": "My Integration Key",
    "entity-id": "entity-123"
  }'
```

### Revoke API Key

```bash
curl -X POST "$RAG_BASE_URL/api/keys/key_abc123/revoke" \
  --cookie "auth-token=YOUR_JWT_TOKEN"
```

---

## Response Processing

### Pretty Print JSON

Add `| jq .` to format JSON output:

```bash
curl -X POST "$RAG_BASE_URL/api/rag" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $RAG_API_KEY" \
  -d '{"query": "Hva er Altinn?"}' | jq .
```

### Extract Just the Answer

```bash
curl -s -X POST "$RAG_BASE_URL/api/rag" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $RAG_API_KEY" \
  -d '{"query": "Hva er Altinn?"}' | jq -r '.answer'
```

### Get Conversation ID for Follow-up

```bash
CONVO_ID=$(curl -s -X POST "$RAG_BASE_URL/api/rag" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $RAG_API_KEY" \
  -d '{"query": "Hva er Altinn?"}' | jq -r '.["conversation-id"]')

echo "Conversation ID: $CONVO_ID"

# Follow-up question
curl -X POST "$RAG_BASE_URL/api/rag" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $RAG_API_KEY" \
  -d "{\"query\": \"Fortell mer\", \"conversation-id\": \"$CONVO_ID\"}"
```

---

## Error Handling

### Check HTTP Status Code

```bash
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "$RAG_BASE_URL/api/rag" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $RAG_API_KEY" \
  -d '{"query": "test"}')

echo "Status: $HTTP_CODE"
```

### Verbose Output for Debugging

```bash
curl -v -X POST "$RAG_BASE_URL/api/rag" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $RAG_API_KEY" \
  -d '{"query": "Hva er Altinn?"}'
```

---

## Scripting Examples

### Batch Query Script

```bash
#!/bin/bash

QUERIES=(
  "Hva er Altinn?"
  "Hva er universell utforming?"
  "Forklar WCAG 2.1"
)

for query in "${QUERIES[@]}"; do
  echo "Query: $query"
  curl -s -X POST "$RAG_BASE_URL/api/rag" \
    -H "Content-Type: application/json" \
    -H "X-API-Key: $RAG_API_KEY" \
    -d "{\"query\": \"$query\"}" | jq -r '.answer'
  echo "---"
done
```

### Test API Key Validity

```bash
#!/bin/bash

response=$(curl -s -w "\n%{http_code}" -X POST "$RAG_BASE_URL/api/rag" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $RAG_API_KEY" \
  -d '{"query": "test"}')

http_code=$(echo "$response" | tail -n1)

if [ "$http_code" == "401" ]; then
  echo "ERROR: Invalid API key"
  exit 1
elif [ "$http_code" == "200" ]; then
  echo "OK: API key is valid"
else
  echo "WARNING: Unexpected status code: $http_code"
fi
```

# Digdir RAG API Documentation

This documentation covers the Digdir RAG (Retrieval-Augmented Generation) API, which provides endpoints for querying documents, managing conversations, and administering API keys.

## Quick Links

| Document | Description |
|----------|-------------|
| [Getting Started](./getting-started.md) | Quick start guide for new developers |
| [Authentication](./authentication.md) | API key and JWT authentication details |
| [OpenAPI Spec](./openapi.yaml) | Machine-readable API specification |

## Endpoints

### RAG & Retrieval
| Endpoint | Method | Description |
|----------|--------|-------------|
| [`/api/rag`](./endpoints/rag.md) | POST | Full RAG query with LLM-generated answers |
| [`/api/retrieve`](./endpoints/retrieve.md) | POST | Retrieval-only (ranked chunks without LLM) |

### Conversation Management
| Endpoint | Method | Description |
|----------|--------|-------------|
| [`/api/conversations`](./endpoints/conversations.md) | GET | List conversations (paginated) |
| [`/api/conversations`](./endpoints/conversations.md) | POST | Create new conversation |
| [`/api/conversations/:id`](./endpoints/conversations.md) | GET | Get conversation with messages |
| [`/api/conversations/:id`](./endpoints/conversations.md) | PUT | Update conversation |
| [`/api/conversations/:id`](./endpoints/conversations.md) | DELETE | Delete conversation |

### API Key Management (Admin)
| Endpoint | Method | Description |
|----------|--------|-------------|
| [`/api/keys`](./endpoints/api-keys.md) | GET | List your API keys |
| [`/api/keys`](./endpoints/api-keys.md) | POST | Create new API key |
| [`/api/keys/:key-id/revoke`](./endpoints/api-keys.md) | POST | Revoke an API key |

## Examples

- [Postman Collection](./examples/postman-collection.json) - Import directly into Postman
- [cURL Examples](./examples/curl-examples.md) - Command-line examples for all endpoints

## Authentication Overview

The API uses two authentication methods:

1. **API Key** (`X-API-Key` header) - For RAG queries and conversation management
2. **JWT Token** (cookie-based) - For API key management (admin interface)

See [Authentication](./authentication.md) for details.

## Base URLs

| Environment | URL |
|-------------|-----|
| Production | `https://admin.kunnskap.digdir.cloud` |
| Staging | `https://admin.staging.kunnskap.digdir.cloud` |

## Response Format

All API responses are JSON. Successful responses return the requested data. Error responses follow this format:

```json
{
  "error": "Description of what went wrong"
}
```

## Rate Limiting

- Authentication endpoints: 10 requests per 15 minutes per IP
- API endpoints: No rate limiting (subject to change)

## Support

For API issues or questions, contact the Digdir team or open an issue in the repository.

# Authentication

The Digdir RAG API uses two authentication methods depending on the endpoint.

## Authentication Methods

| Method | Used For | How to Obtain |
|--------|----------|---------------|
| API Key | RAG queries, conversations | Admin creates via web UI |
| JWT Token | API key management | Email login to admin interface |

## API Key Authentication

Most API endpoints use API key authentication via the `X-API-Key` header.

### Header Format

```
X-API-Key: rag_your_api_key_here
```

### API Key Format

API keys follow this format:
- Prefix: `rag_`
- Body: 64 hexadecimal characters (256-bit entropy)
- Example: `rag_a1b2c3d4e5f6...` (64 hex chars)

### Using the API Key

**cURL:**
```bash
curl -X POST https://admin.kunnskap.digdir.cloud/api/rag \
  -H "Content-Type: application/json" \
  -H "X-API-Key: rag_your_api_key_here" \
  -d '{"query": "Your question"}'
```

**JavaScript:**
```javascript
fetch('/api/rag', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'X-API-Key': 'rag_your_api_key_here'
  },
  body: JSON.stringify({ query: 'Your question' })
});
```

**Python:**
```python
import requests

requests.post(
    'https://admin.kunnskap.digdir.cloud/api/rag',
    headers={'X-API-Key': 'rag_your_api_key_here'},
    json={'query': 'Your question'}
)
```

### Endpoints Requiring API Key

| Endpoint | Method |
|----------|--------|
| `/api/rag` | POST |
| `/api/retrieve` | POST |
| `/api/conversations` | GET, POST |
| `/api/conversations/:id` | GET, PUT, DELETE |

### Error Responses

**Missing API Key (401):**
```json
{
  "error": "Invalid or missing API key"
}
```

**Invalid API Key (401):**
```json
{
  "error": "Invalid or missing API key"
}
```

**Revoked API Key (401):**
```json
{
  "error": "Invalid or missing API key"
}
```

## JWT Authentication (Admin)

API key management endpoints (`/api/keys/*`) use JWT authentication for administrators.

### How It Works

1. User logs in via the admin web interface (`/auth`)
2. After successful authentication, a JWT token is stored in an HTTP-only cookie
3. The cookie is automatically included in subsequent requests
4. The JWT contains user identity and permissions

### Endpoints Requiring JWT

| Endpoint | Method | Permission |
|----------|--------|------------|
| `/api/keys` | GET | Authenticated user |
| `/api/keys` | POST | Authenticated user |
| `/api/keys/:key-id/revoke` | POST | Owner of the key |

### Error Responses

**Not Authenticated (302 Redirect):**
- Unauthenticated requests to admin endpoints redirect to `/auth`

**Not Authorized (403):**
```json
{
  "error": "Not authorized to revoke this API key"
}
```

## API Key Management

### Creating an API Key

1. Log in to the admin interface
2. Navigate to API Keys section
3. Click "Create New Key"
4. Provide a name and select the entity
5. **Important**: Copy the key immediately - it won't be shown again

Or via API (requires JWT auth):
```bash
curl -X POST /api/keys \
  -H "Content-Type: application/json" \
  --cookie "auth-token=your_jwt_token" \
  -d '{
    "name": "My Integration Key",
    "entity-id": "entity-123"
  }'
```

### Revoking an API Key

```bash
curl -X POST /api/keys/key-id-here/revoke \
  --cookie "auth-token=your_jwt_token"
```

Revoked keys:
- Immediately stop working
- Cannot be unrevoked
- Remain in the system for audit purposes

### Listing Your API Keys

```bash
curl /api/keys \
  --cookie "auth-token=your_jwt_token"
```

Response includes:
- Key ID (not the key itself)
- Name
- Entity ID
- Created date
- Last used date
- Revoked status

## Rate Limiting

### Authentication Endpoints

Rate limiting applies to login/authentication endpoints:

| Limit | Window | Action |
|-------|--------|--------|
| 10 failed attempts | 15 minutes | IP blocked |

**Rate Limited Response (429):**
```json
{
  "error": "Too many requests. Please try again later."
}
```

### API Endpoints

Currently, RAG and conversation endpoints are not rate limited. This may change in the future.

## Security Best Practices

### API Keys

1. **Never commit keys to version control** - Use environment variables
2. **Rotate keys periodically** - Create new keys and revoke old ones
3. **Use separate keys per environment** - Don't share keys between dev/staging/prod
4. **Monitor usage** - Check last-used dates in the admin interface
5. **Revoke unused keys** - Remove keys that are no longer needed

### Environment Variables

Store your API key in environment variables:

```bash
# .env file (don't commit this!)
RAG_API_KEY=rag_your_api_key_here
```

```javascript
// JavaScript
const apiKey = process.env.RAG_API_KEY;
```

```python
# Python
import os
api_key = os.environ['RAG_API_KEY']
```

### HTTPS Only

Always use HTTPS when making API requests. The API does not accept unencrypted HTTP connections in production.

## Entity Scoping

Each API key is scoped to a specific **entity** (organization/project). The entity determines:

- Which document collections are searched
- RAG configuration (prompts, model settings)
- Context window parameters

You cannot query documents from other entities with your API key.

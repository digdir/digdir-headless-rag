# API Keys Endpoints

Manage API keys for accessing the RAG API. These endpoints require JWT authentication (admin login).

## Authentication

All endpoints require JWT authentication via the `auth-token` cookie, obtained by logging into the admin interface.

---

## List API Keys

`GET /api/keys`

List all API keys belonging to the authenticated user.

### Example Request

```bash
curl -X GET https://admin.kunnskap.digdir.cloud/api/keys \
  --cookie "auth-token=your_jwt_token"
```

### Success Response (200)

```json
{
  "api-keys": [
    {
      "id": "key_abc123",
      "name": "Production Integration",
      "entity-id": "entity-123",
      "created": 1704067200,
      "last-used": 1704153600,
      "revoked": false
    },
    {
      "id": "key_def456",
      "name": "Development Testing",
      "entity-id": "entity-123",
      "created": 1703980800,
      "last-used": 1704067200,
      "revoked": false
    },
    {
      "id": "key_old789",
      "name": "Old Key",
      "entity-id": "entity-123",
      "created": 1701388800,
      "last-used": 1702598400,
      "revoked": true
    }
  ]
}
```

### Response Fields

| Field | Type | Description |
|-------|------|-------------|
| `api-keys` | array | List of API key objects |
| `api-keys[].id` | string | Key identifier (not the actual key) |
| `api-keys[].name` | string | Human-readable name |
| `api-keys[].entity-id` | string | Entity the key is scoped to |
| `api-keys[].created` | integer | Unix timestamp of creation |
| `api-keys[].last-used` | integer | Unix timestamp of last use |
| `api-keys[].revoked` | boolean | Whether the key has been revoked |

---

## Create API Key

`POST /api/keys`

Create a new API key. The actual key value is only returned once in this response.

### Body Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | Yes | Human-readable name for the key |
| `entity-id` | string | Yes | Entity ID to scope the key to |

### Example Request

```bash
curl -X POST https://admin.kunnskap.digdir.cloud/api/keys \
  -H "Content-Type: application/json" \
  --cookie "auth-token=your_jwt_token" \
  -d '{
    "name": "My New Integration Key",
    "entity-id": "entity-123"
  }'
```

### Success Response (201)

```json
{
  "api-key-id": "key_new123",
  "api-key": "rag_a1b2c3d4e5f6789012345678901234567890123456789012345678901234",
  "name": "My New Integration Key",
  "entity-id": "entity-123",
  "warning": "This is the only time the API key will be shown. Please store it securely."
}
```

### Response Fields

| Field | Type | Description |
|-------|------|-------------|
| `api-key-id` | string | Key identifier for management |
| `api-key` | string | **The actual API key - store this securely!** |
| `name` | string | The name you provided |
| `entity-id` | string | Entity the key is scoped to |
| `warning` | string | Reminder to store the key |

### Error Responses

**400 Bad Request** - Missing required field
```json
{
  "error": "Missing required field: name"
}
```

**400 Bad Request** - Missing entity-id
```json
{
  "error": "Missing required field: entity-id"
}
```

**404 Not Found** - Invalid entity
```json
{
  "error": "Entity not found: invalid-entity"
}
```

---

## Revoke API Key

`POST /api/keys/:key-id/revoke`

Revoke an API key. This action is permanent and cannot be undone.

### Path Parameters

| Parameter | Description |
|-----------|-------------|
| `key-id` | API key identifier |

### Example Request

```bash
curl -X POST https://admin.kunnskap.digdir.cloud/api/keys/key_abc123/revoke \
  --cookie "auth-token=your_jwt_token"
```

### Success Response (200)

```json
{
  "success": true
}
```

### Error Responses

**403 Forbidden** - Not authorized
```json
{
  "error": "Not authorized to revoke this API key"
}
```

**404 Not Found** - Key not found
```json
{
  "error": "API key not found"
}
```

---

## API Key Format

API keys follow this format:

```
rag_<64 hexadecimal characters>
```

- **Prefix**: `rag_` identifies the key type
- **Body**: 64 hex characters (256-bit entropy)
- **Example**: `rag_a1b2c3d4e5f6789012345678901234567890123456789012345678901234`

---

## Best Practices

### Security

1. **Store keys securely** - Use environment variables or secret managers
2. **Never commit keys** - Add API keys to `.gitignore`
3. **Use separate keys** - Different keys for dev/staging/prod
4. **Rotate regularly** - Create new keys and revoke old ones periodically

### Naming Convention

Use descriptive names that identify:
- The application or service using the key
- The environment (if applicable)
- The purpose

Examples:
- `Production - Main Website`
- `Staging - API Testing`
- `CI/CD Pipeline`
- `Developer - John's Local`

### Monitoring

Check the admin interface regularly for:
- Unused keys (no `last-used` date)
- Keys that haven't been used recently
- Unexpected usage patterns

---

## Notes

- API keys are scoped to a single entity (organization/project)
- Revoked keys cannot be un-revoked - create a new key instead
- The actual key value is only shown once at creation time
- Keys remain in the system after revocation for audit purposes
- Only the key owner can revoke their own keys

## Related

- [Authentication](../authentication.md) - How to use API keys
- [Getting Started](../getting-started.md) - First steps with the API

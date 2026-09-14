# API Key Endpoints

Manage Public API keys through the JWT-authenticated Operator Console API.

## Authentication

All endpoints require the `auth-token` cookie.

`POST`, `PUT`, and revoke operations also require `X-User-Email`.

## List API Keys

`GET /console-api/api-keys`

### Success Response

```json
{
  "api-keys": [
    {
      "id": "key_abc123",
      "name": "Production Integration",
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
        }
      ],
      "created": 1704067200,
      "last-used": 1704153600,
      "revoked": false
    }
  ]
}
```

## Create API Key

`POST /console-api/api-keys`

### Required Body Fields

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Human-readable key name |

Also required: **one of `dataset-scopes` or `policy-id`**. A key with neither
is rejected — it would grant access to nothing.

### Optional Body Fields

| Field | Type | Description |
|-------|------|-------------|
| `dataset-scopes` | array | Dataset scopes in `{tenant, dataset-config-key}` form |
| `policy-id` | string | Access policy to inherit grants from, instead of listing `dataset-scopes` |
| `allowed-config-keys` | array | Root-scoped config access grants |
| `agent-refs` | array | Explicit agent IDs. **Empty or omitted means UNRESTRICTED — every agent** (see below) |
| `modes` | array | Namespaced mode ids the key may invoke, e.g. `builtin/agent-rag-graph-bundled` |
| `client-id` | string | Optional caller/client identifier stored with the key |

### An empty grant list means UNRESTRICTED, not none

**`agent-refs`, `modes`/`skill-graphs`, `dataset-scopes` and
`allowed-config-keys` all grant EVERYTHING when left empty.** A grant list
narrows access; it does not confer it. A key created with no `agent-refs` can
list and call **every** agent.

This is deliberate (#349) and it is the rule the runtime has always applied on
both `/api/mcp` and `/api/conversations` — but it is the opposite of what most
readers assume from an authorization field, so it is stated here rather than
left to be inferred. The admin console said "None" for such a key until #349;
it now says *"Unrestricted — all agents"*.

**To restrict a key, list what it may reach.** There is no value of
`agent-refs` that means "nothing".

Two field names are **rejected with a `400`** rather than ignored, because
sending either used to return `201` with the request quietly half-applied:

| Field | Why |
|-------|-----|
| `skill-graphs` | Renamed to `modes` (see the public-identifiers ADR). Send `modes`. |
| `scopes` | Never settable on this endpoint. A new key gets the default `query` scope. |

### Example Request

```bash
curl -X POST https://rag.digdir.cloud/console-api/api-keys \
  -H "Content-Type: application/json" \
  -H "X-User-Email: user@example.com" \
  --cookie "auth-token=your_jwt_token" \
  -d '{
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
  }'
```

### Success Response

```json
{
  "api-key-id": "key_new123",
  "api-key": "rag_a1b2c3d4e5f6789012345678901234567890123456789012345678901234",
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
  ],
  "warning": "This is the only time the API key will be shown. Please store it securely."
}
```

### Common Errors

Missing name:

```json
{
  "error": "Missing required field: name"
}
```

Missing dataset scopes:

```json
{
  "error": "Missing required field: dataset-scopes"
}
```

Invalid dataset scope:

```json
{
  "error": "Dataset not found for dataset-scope: {:tenant \"digdir\", :dataset-config-key \"missing\"}"
}
```

## Replace Allowed Config Keys

`PUT /console-api/api-keys/:key-id/allowed-config-keys`

### Example Request

```bash
curl -X PUT https://rag.digdir.cloud/console-api/api-keys/key_abc123/allowed-config-keys \
  -H "Content-Type: application/json" \
  -H "X-User-Email: user@example.com" \
  --cookie "auth-token=your_jwt_token" \
  -d '{
    "allowed-config-keys": [
      {
        "root": "platform",
        "tenant": "digdir",
        "platform-config-key": "default"
      },
      {
        "root": "runtime",
        "tenant": "digdir",
        "runtime-config-key": "default"
      },
      {
        "root": "dataset",
        "tenant": "digdir",
        "dataset-config-key": "public-docs"
      }
    ]
  }'
```

### Success Response

```json
{
  "api-key-id": "key_abc123",
  "name": "Production Integration",
  "allowed-config-keys": [
    {
      "root": "platform",
      "tenant": "digdir",
      "platform-config-key": "default"
    },
    {
      "root": "runtime",
      "tenant": "digdir",
      "runtime-config-key": "default"
    },
    {
      "root": "dataset",
      "tenant": "digdir",
      "dataset-config-key": "public-docs"
    }
  ]
}
```

## Revoke API Key

`POST /console-api/api-keys/:key-id/revoke`

### Success Response

```json
{
  "success": true
}
```

## Notes

- API keys are scoped to dataset scopes, not pipelines.
- Allowed config keys are root-specific and use `platform-config-key`, `runtime-config-key`, or `dataset-config-key`.
- Revocation is permanent.
- The plaintext API key is shown only once at creation time.
- The database stores a SHA-256 digest of the 256-bit random credential, plus
  its non-secret prefix and final four characters for identification. Existing
  plaintext rows are migrated automatically at startup.
- List and detail responses never contain either plaintext or the lookup
  digest. They expose only `key-prefix` and `key-last-four`.

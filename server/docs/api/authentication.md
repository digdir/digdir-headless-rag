# Authentication

The Digdir RAG system exposes two separate HTTP surfaces with different authentication models.

## Authentication Methods

| Method | Used For | How to Obtain |
|--------|----------|---------------|
| API Key | Public API: RAG, retrieval, conversations, read-only dataset state | Create via the Operator Console |
| JWT Token | Internal Operator Console APIs | Log in through the Operator Console |

## The API key is server-side only

**Your API key must never reach a browser, and this API is built so that it
cannot get there by accident.** It is a long-lived credential scoped to datasets
and agents; anyone who reads it out of your page's JavaScript has that scope
until the key is revoked.

A web frontend therefore calls **your** backend, and your backend calls this
API. There is a runnable reference for that shape, including the streaming
case, in [`examples/browser-proxy/`](./examples/browser-proxy/README.md).

### Why a browser cannot call this API directly

Not a gap to be worked around — two independent closures, measured against a
live server:

| Request | Status | `Access-Control-*` headers |
| --- | --- | --- |
| `OPTIONS /api/mcp`, no key — **every real browser preflight** | `401` | none |
| `OPTIONS /api/mcp` with a key — which no browser ever sends | `200` | none |
| `POST /api/mcp` with a key, succeeding normally | `200` | none |

The first row is the one that decides it. A CORS preflight carries **no
credentials by specification** — the browser sends it before, and separately
from, your authenticated request — so on a surface that authenticates every
request the preflight can only ever be rejected. And even the rows that succeed
carry no `Access-Control-*` headers, so a browser would refuse to hand the
response to your code regardless.

### Why we will not "just add CORS"

Making the browser path work would take two changes: exempt `OPTIONS` from
authentication, and return permissive CORS headers. Together those publish
"put your long-lived server-side key in a browser" as the sanctioned pattern,
and every integrator who followed it would ship their key to every visitor.

So the fix that makes it *work* and the fix that makes it *safe* are the same
one, and it is not a header: **the key never reaches the browser.**

## Public API Key Authentication

Public endpoints accept the same API key in **either** of two headers. They are
equivalent — the same middleware validates both, on every public endpoint.

### Header Format

```text
X-API-Key: rag_your_api_key_here
```

```text
Authorization: Bearer rag_your_api_key_here
```

Use `Authorization: Bearer` when your client already speaks OpenAI — the OpenAI
SDKs send it automatically, which is what makes the
[`/v1` surface](./endpoints/openai-compat.md) work with no digdir-specific
code. Use `X-API-Key` otherwise; it is the platform's own convention.

Conversation-scoped public endpoints also require `X-User-Id`, an opaque external caller identifier that is unrelated to Playground/internal user accounts.

### Endpoints Requiring API Keys

| Endpoint | Method |
|----------|--------|
| `/api/mcp` | POST |
| `/v1/models` | GET |
| `/v1/chat/completions` | POST |
| `/api/datasets` | GET |
| `/api/datasets/:dataset-id` | GET |
| `/api/conversations` | GET, POST |
| `/api/conversations/:id` | GET, PUT, DELETE |

### Public API Scope Model

API keys are scoped to one or more dataset scopes.

Dataset scopes use this shape:

```json
{
  "tenant": "digdir",
  "dataset-config-key": "public-docs"
}
```

**A `/v1` call needs a scope from somewhere.** `/api/mcp` callers pass `tenant`
and `dataset_config_key` per call; on
[`/v1/chat/completions`](./endpoints/openai-compat.md) the same two fields are
accepted at the top level of the request body as a digdir extension, but a
stock OpenAI client sends neither. So either give the key its dataset scopes,
or rely on the server's `TENANT` / `DATASET_CONFIG_KEY` defaults. A key with no
scopes, calling with no explicit pair, gets `400` with code
`no_dataset_scope`.

**The key's dataset scopes are a ceiling, not a default.** When a key has
scopes, the per-call `tenant` / `dataset_config_key` fields *select among them*
— they cannot reach a dataset the key was not granted. Naming one that is
outside the grant is refused with `dataset_not_authorized` (`403` on `/v1`),
whether or not that dataset exists. The same is true of an agent's own
`allowed-dataset-scopes`: it can only narrow what a key may reach, never widen
it.

### Public API Example

```bash
curl -X GET https://admin.kunnskap.digdir.cloud/api/datasets \
  -H "X-API-Key: rag_your_api_key_here"
```

The same call with the Bearer form:

```bash
curl -X GET https://admin.kunnskap.digdir.cloud/api/datasets \
  -H "Authorization: Bearer rag_your_api_key_here"
```

### Error Responses

**Missing or invalid API key (401):**

On `/api/*`:

```json
{
  "error": "Invalid or missing API key"
}
```

On `/v1/*`:

```json
{
  "error": {
    "message": "Invalid or missing API key",
    "type": "invalid_request_error",
    "code": "invalid_api_key"
  }
}
```

**The shape follows the surface, not the layer.** Authentication is rejected by
shared middleware for both, but the middleware renders the body in the calling
surface's own error shape — a string for the platform API, OpenAI's object for
`/v1`, so an OpenAI SDK's error handling works unchanged on a 401.

Clients should still branch on the **status code** rather than the body shape.
That advice has not changed and is not the reason this section was rewritten.

## JWT Authentication for Operator Console APIs

The Operator Console APIs are internal. They use JWT authentication via the `auth-token` cookie and are not part of the public multi-tenant API surface.

### How It Works

1. A user logs in through the Operator Console
2. After successful authentication, a JWT token is stored in an HTTP-only cookie
3. That cookie is sent automatically on later Operator Console requests
4. The JWT carries user identity and permissions

### Endpoints Requiring JWT

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/console-api/api-keys` | GET, POST | API key management |
| `/console-api/api-keys/:key-id/allowed-config-keys` | PUT | Replace allowed config keys |
| `/console-api/api-keys/:key-id/revoke` | POST | Revoke key |
| `/console-api/users` | GET, POST | User management |
| `/console-api/users/:id` | GET, DELETE | User detail and deletion |
| `/console-api/users/:id/permissions` | PUT | User permissions |
| `/console-api/permissions` | GET | Permission listing |
| `/console-api/conversations` | GET | Operator conversation listing |
| `/console-api/datasets` | GET, POST | Dataset lifecycle |
| `/console-api/datasets/:dataset-id` | GET | Dataset detail |
| `/console-api/datasets/:dataset-id/pipelines` | GET, POST | Child pipeline lifecycle |
| `/console-api/datasets/:dataset-id/pipelines/:pipeline-id` | GET, PUT, DELETE | Child pipeline detail and mutation |
| `/console-api/datasets/:dataset-id/pipelines/:pipeline-id/execute` | POST | Pipeline execution |
| `/console-api/datasets/:dataset-id/pipelines/:pipeline-id/executions` | GET | Pipeline execution history |

### Operator Console Mutation Headers

Some Operator Console mutation routes also require `X-User-Email`:

- `POST /console-api/datasets`
- `POST /console-api/datasets/:dataset-id/pipelines`
- `PUT /console-api/datasets/:dataset-id/pipelines/:pipeline-id`
- `DELETE /console-api/datasets/:dataset-id/pipelines/:pipeline-id`
- `POST /console-api/datasets/:dataset-id/pipelines/:pipeline-id/execute`

### Operator Console Example

```bash
curl -X GET https://admin.kunnskap.digdir.cloud/console-api/datasets \
  --cookie "auth-token=YOUR_JWT_TOKEN"
```

### JWT Failure Behavior

- Unauthenticated requests are redirected to `/auth`
- Unauthorized requests return `403` with a JSON error body where applicable

## API Key Management Notes

API keys are created through the Operator Console surface, not the Public API.

Example:

```bash
curl -X POST https://admin.kunnskap.digdir.cloud/console-api/api-keys \
  -H "Content-Type: application/json" \
  --cookie "auth-token=YOUR_JWT_TOKEN" \
  -d '{
    "name": "My Integration Key",
    "dataset-scopes": [{"tenant": "digdir", "dataset-config-key": "public-docs"}]
  }'
```

Revoked keys:

- stop working immediately
- cannot be unrevoked
- remain in the system for audit purposes

## Security Best Practices

### API Keys

1. Never commit keys to version control.
2. Rotate keys periodically.
3. Use separate keys per dataset scope where possible.
4. Monitor usage and revoke unused keys.

### HTTPS Only

Always use HTTPS when making requests. Production does not accept unencrypted HTTP.

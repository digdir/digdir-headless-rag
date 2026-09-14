# Digdir RAG API Documentation

The product exposes two HTTP surfaces:

- the **Public API**, authenticated with `X-API-Key` or `Authorization: Bearer`
- the **Operator Console API**, authenticated with the `auth-token` cookie

**Already have an OpenAI client?** Point it at `/v1` and skip the rest of this
page — see [Fastest path](#fastest-path-openai-compatible-v1) below.

**Building a web frontend?** The API key is **server-side only** and a browser
cannot call this API directly. Your frontend calls your backend; your backend
calls this API. See
[the API key is server-side only](./authentication.md#the-api-key-is-server-side-only)
for why, and [`examples/browser-proxy/`](./examples/browser-proxy/README.md)
for a runnable backend that does it, streaming included.

The contract is dataset-first:

- public callers select datasets
- public callers do not read or mutate materialization pipelines
- Operator Console callers manage datasets and child materialization pipelines

## Quick Links

| Document | Description |
|----------|-------------|
| [Getting Started](./getting-started.md) | Quick start guide |
| [Authentication](./authentication.md) | Auth and header requirements |
| [OpenAI-Compatible API](./endpoints/openai-compat.md) | `/v1` — use any OpenAI client |
| [OpenAPI Tool Server](./endpoints/openapi-tools.md) | `/api/tools` — let another model tool-call an agent |
| [Datasets](./endpoints/datasets.md) | Public dataset endpoints |
| [API Keys](./endpoints/api-keys.md) | Operator API key management |
| [Pipelines](./endpoints/pipelines.md) | Operator dataset/materialization endpoints |
| [MCP protocol version policy](./endpoints/mcp.md#protocol-version-and-compatibility) | Which MCP revision we implement, why, and the trigger to move |
| [Considered divergences](./considered-divergences.md) | Where we differ from a neighbouring standard, and whether we chose it or inherited it |
| [Browser / BFF reference](./examples/browser-proxy/README.md) | Calling from a web frontend — the key is server-side only |
| [OpenAPI Spec](./openapi.yaml) | Machine-readable contract |

## Public API

> Retrieval and RAG invocation moved to the MCP server. The legacy
> `/api/rag`, `/api/retrieve`, and `/api/skill-graphs/*/execute`
> endpoints were removed; new integrations should use `/api/mcp`
> (Model Context Protocol over Streamable HTTP).
>
> **Discovery is `tools/list` and `GET /v1/models`.** Both enumerate the same
> `(agent, mode)` axis, filtered to what your API key can reach, and both return
> the identifier you then call with. The separate `/api/skills` and `/api/modes`
> listing endpoints were removed as a redundant second discovery surface. The
> public wire calls that second axis a *mode*, per
> [the public-identifiers ADR](../../../decisions/public-identifiers.md).

| Endpoint | Method | Description |
|----------|--------|-------------|
| [`/api/mcp`](./endpoints/mcp.md) | POST | Model Context Protocol — `tools/list`, `tools/call`, with optional SSE streaming |
| [`/v1/models`](./endpoints/openai-compat.md) | GET | OpenAI-compatible — one model per agent × skill graph |
| [`/v1/chat/completions`](./endpoints/openai-compat.md) | POST | OpenAI-compatible chat, blocking or SSE streaming |
| [`/api/tools/openapi.json`](./endpoints/openapi-tools.md) | GET | OpenAPI document for the tools this key can reach |
| [`/api/tools/call/:tool-name`](./endpoints/openapi-tools.md) | POST | Invoke one agent tool |
| [`/api/datasets`](./endpoints/datasets.md) | GET | List visible datasets |
| [`/api/datasets/:dataset-id`](./endpoints/datasets.md) | GET | Get one visible dataset |
| [`/api/conversations`](./endpoints/conversations.md) | GET, POST | List or create conversations |
| [`/api/conversations/:id`](./endpoints/conversations.md) | GET, PUT, DELETE | Manage one conversation |

## Operator Console API

| Endpoint | Method | Description |
|----------|--------|-------------|
| [`/console-api/api-keys`](./endpoints/api-keys.md) | GET, POST | List or create API keys |
| [`/console-api/api-keys/:key-id/allowed-config-keys`](./endpoints/api-keys.md) | PUT | Replace allowed config keys |
| [`/console-api/api-keys/:key-id/revoke`](./endpoints/api-keys.md) | POST | Revoke an API key |
| [`/console-api/datasets`](./endpoints/pipelines.md) | GET, POST | List or create datasets |
| [`/console-api/datasets/:dataset-id`](./endpoints/pipelines.md) | GET | Get one dataset |
| [`/console-api/datasets/:dataset-id/pipelines`](./endpoints/pipelines.md) | GET, POST | List or create materialization pipelines |
| [`/console-api/datasets/:dataset-id/pipelines/:pipeline-id`](./endpoints/pipelines.md) | GET, PUT, DELETE | Manage one materialization pipeline |
| [`/console-api/datasets/:dataset-id/pipelines/:pipeline-id/execute`](./endpoints/pipelines.md) | POST | Execute a materialization pipeline |
| [`/console-api/datasets/:dataset-id/pipelines/:pipeline-id/executions`](./endpoints/pipelines.md) | GET | List execution history |

## Fastest path: OpenAI-compatible `/v1`

Every agent is exposed as an OpenAI **model**, so any OpenAI client — Open
WebUI, cursor, continue, the `openai` SDKs, plain `curl` — works by changing
two settings: the base URL to `<base>/v1`, and the API key.

**One precondition.** An agent has to know which dataset to search, and a stock
OpenAI request body has no field for it. Supply it one of three ways: give the
API key **dataset scopes** (set when the key is created), send `tenant` and
`dataset_config_key` as top-level fields in the request body (a digdir
extension — the only per-call option), or set `TENANT` and `DATASET_CONFIG_KEY`
in the server environment. With none of them, chat calls fail with
`no_dataset_scope`. Where a key has dataset scopes, the per-call fields select
*within* them rather than overriding them — naming a dataset outside the grant
is refused with `dataset_not_authorized`. See
[Authentication](./authentication.md#public-api-scope-model).

Then, list the models:

```bash
curl -sS https://rag.digdir.cloud/v1/models \
  -H "Authorization: Bearer $DIGDIR_API_KEY"
```

and chat with one, using an `id` from that response:

```bash
curl -sS https://rag.digdir.cloud/v1/chat/completions \
  -H "Authorization: Bearer $DIGDIR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "builtin.agent-rag-agent__agent-rag-graph-bundled",
    "messages": [{"role": "user", "content": "Hva er Dialogporten?"}]
  }'
```

```json
{
  "id": "chatcmpl-GchQMEUfrQ2MRhlUxtNVS",
  "object": "chat.completion",
  "model": "builtin.agent-rag-agent__agent-rag-graph-bundled",
  "choices": [{"index": 0,
               "message": {"role": "assistant", "content": "…grounded answer…"},
               "finish_reason": "stop"}]
}
```

Add `"stream": true` for SSE. Full reference, including error codes and what
`/v1` deliberately leaves out: [OpenAI-Compatible API](./endpoints/openai-compat.md).

Need per-call dataset selection, citations, or progress events? Use
[`/api/mcp`](./endpoints/mcp.md) instead.

## Examples

- [Postman Collection](./examples/postman-collection.json)
- [cURL Examples](./examples/curl-examples.md)
- [Client Smoke Flow](./examples/client-smoke-flow.md)

## Base URLs

| Environment | URL |
|-------------|-----|
| Production | `https://rag.digdir.cloud` |
| Test | `https://test.rag.digdir.cloud` |

## Error Format

```json
{
  "error": "Description of what went wrong"
}
```

# OpenAI-Compatible API (`/v1`)

Point any OpenAI client at this server and talk to a digdir agent. No SDK, no
protocol work: each agent is exposed as a **model**, so Open WebUI, cursor,
continue, the `openai` Python/JS packages and plain `curl` all work unchanged.

If you already have a client pointed at some other OpenAI-compatible endpoint,
this is the fastest way in — change the base URL and the key.

| | |
|---|---|
| Base URL | `https://rag.digdir.cloud/v1` |
| Auth | `Authorization: Bearer <your-api-key>` (or `X-API-Key`) |
| Endpoints | `GET /v1/models`, `POST /v1/chat/completions` |

For the full protocol surface — tool listing, progress streaming, per-call
tenant and dataset arguments — use [`/api/mcp`](./mcp.md) instead. `/v1` is the
compatibility shim: less control, far less setup.

## Before your first call: not from a browser

**The OpenAI JS SDK will let you do this from a browser, and you must not.**
It ships a `dangerouslyAllowBrowser` flag precisely for people who ask, and
setting it here would put your long-lived, dataset-scoped digdir key into every
visitor's devtools. The flag suppresses the SDK's own warning; it does not make
the key safe, and this API sends no CORS headers, so the call fails anyway —
after you have already shipped the key.

Run the SDK server-side and have your frontend call your own backend. See
[the API key is server-side only](../authentication.md#the-api-key-is-server-side-only)
for the measurements, and
[`examples/browser-proxy/`](../examples/browser-proxy/README.md) for a runnable
backend that does it.

## Before your first call: the agent needs a dataset scope

**This is the one thing that will stop you.** An agent has to know which dataset
to search, and a stock OpenAI request body carries no such field. There are
three ways to supply it:

1. **The API key carries dataset scopes** — set when the key is created, see
   [Authentication](../authentication.md#public-api-scope-model). Best for a
   client you cannot modify: nothing in the request changes.
2. **`tenant` and `dataset_config_key` at the top level of the request body** —
   a digdir extension to the OpenAI payload, and the only per-call option. Both
   are required together; either alone is ignored.
3. **`TENANT` and `DATASET_CONFIG_KEY` in the server environment** — a default
   for keys that carry no scopes.

A key with scopes narrows what is allowed; an explicit pair in the body picks
among them; the environment fills in when neither says otherwise.

Without any of the three, every `/v1/chat/completions` call fails:

```json
{
  "error": {
    "message": "No dataset scope available. The agent does not restrict scopes; pass `tenant` and `dataset_config_key` in tool arguments, attach scopes to the API key, or set TENANT / DATASET_CONFIG_KEY env vars.",
    "type": "invalid_request_error",
    "code": "no_dataset_scope"
  }
}
```

The message mentions "tool arguments" because it is shared with the MCP path;
on `/v1` those are the top-level body fields in option 2.

## `GET /v1/models`

Lists one model per **(agent × allowed skill graph)** pair your key can see —
the same surface MCP exposes as tools.

```bash
curl -sS https://rag.digdir.cloud/v1/models \
  -H "Authorization: Bearer $DIGDIR_API_KEY"
```

```json
{
  "object": "list",
  "data": [
    {
      "id": "builtin.agent-rag-agent__agent-rag-graph-bundled",
      "object": "model",
      "created": 1787315223,
      "owned_by": "digdir-rag",
      "description": "General-purpose agentic retrieval assistant. — Agentic RAG (graph, bundled inner) …",
      "_agent_id": "builtin/agent-rag-agent",
      "_mode": "agent-rag-graph-bundled",
      "_default": true
    }
  ]
}
```

### Model ids

```text
<agent-id>__<skill-graph-short-name>
```

Two underscores separate the halves. `_default: true` marks the agent's default
mode — a good first choice. The `_`-prefixed fields are digdir extensions;
OpenAI clients ignore unknown fields, so they are safe to send.

`_mode` is the mode's short name, unqualified — the half after `__` in the model
id. It was `_skill_graph` until the [public-identifiers ADR](../../../../decisions/public-identifiers.md)
took the word *skill* off the wire (#122); the field renamed and this example did
not, which is what `digdir.api.doc-fidelity-smoke-test` now catches.

## `POST /v1/chat/completions`

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
  "created": 1787315306,
  "model": "builtin.agent-rag-agent__agent-rag-graph-bundled",
  "choices": [
    {
      "index": 0,
      "message": {"role": "assistant", "content": "…grounded answer…"},
      "finish_reason": "stop"
    }
  ],
  "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}
}
```

### Request fields

| Field | Required | Notes |
|---|---|---|
| `model` | yes | An id from `/v1/models` |
| `messages` | yes | Standard OpenAI roles. The last `user` message is the query; earlier turns become conversation history |
| `stream` | no | `true` opens an SSE stream — see below |
| `tenant` | no | digdir extension — with `dataset_config_key`, selects the dataset scope for this call |
| `dataset_config_key` | no | digdir extension — see above; both fields are required together |

Sending extension fields from a typed SDK: the OpenAI Python client takes
`extra_body={"tenant": "...", "dataset_config_key": "..."}`. For clients you
cannot modify, use a scoped key instead.

Sampling parameters (`temperature`, `top_p`, `max_tokens`, …) are accepted and
**ignored**: generation settings come from the agent's own configuration, not
from the caller.

### Streaming

`"stream": true` returns `text/event-stream` with the usual OpenAI frame
sequence: a role-intro delta, the content, a `finish_reason: "stop"` delta, then
`data: [DONE]`.

One deviation worth knowing: the content currently arrives as **a single delta**
rather than token by token. The agent runs to completion first. Clients render
it correctly — it simply appears all at once rather than typing out.

### Citations

When the agent produced grounded references, the response carries two extra
top-level fields — both digdir extensions, both ignored by clients that do not
know them:

```json
{
  "choices": [{"...": "..."}],
  "citations": ["https://example.no/a", "https://example.no/b"],
  "sources": [
    {"source": {"name": "Document title", "url": "https://example.no/a"},
     "document": ["the cited passage"]}
  ]
}
```

`citations` is the Perplexity-style list of URLs; `sources` is Open WebUI's
richer native shape, which renders as clickable references. In a stream both
arrive in their own frame **before** the content, so a client has the metadata
ready when the text lands.

### Errors

Errors use OpenAI's envelope, `{"error": {"message", "type", "code"}}`, so a
client's normal error handling works:

| `code` | HTTP | Meaning |
|---|---|---|
| `missing_model` | 400 | No `model` in the body |
| `missing_messages` | 400 | `messages` empty |
| `invalid_messages` | 400 | No usable `user` message |
| `no_dataset_scope` | 400 | See the scope section above |
| `dataset_not_authorized` | 403 | Your key was not granted the dataset you named |
| `model_not_found` | 404 | Unknown agent or skill graph |
| `agent_not_found` | 404 | Agent id does not resolve |
| `agent_disabled` | 403 | Agent exists but is disabled |
| `agent_not_authorized` | 403 | Your key may not use this agent |
| `skill_graph_not_authorized` | 403 | Your key may not use this graph |
| `invoke_failed` | 500 | The agent errored while answering |

**A missing or invalid API key is rejected by the shared auth middleware**, before
`/v1` routing is reached — but the middleware renders the body in the calling
surface's shape, so on `/v1` you still get OpenAI's envelope:

```json
{
  "error": {
    "message": "Invalid or missing API key",
    "type": "invalid_request_error",
    "code": "invalid_api_key"
  }
}
```

`error.message` resolves, and an OpenAI SDK's own error handling works on a 401
exactly as it does on the codes above. The same 401 on `/api/*` returns the
platform's simpler `{"error": "<string>"}` — the shape follows the surface, not
the layer that produced it.

> This paragraph previously said the opposite: that `error` was a bare string on
> `/v1` and that a client reading `error.message` would get `undefined`. That was
> true until `130ee8a`, which made the 401 render per surface for precisely that
> reason — and updated the handler and two test namespaces without touching this
> page. Recorded rather than quietly replaced, because a warning that outlives
> the defect it warns about costs a reader the compatibility it was protecting.

### Rate limiting

`/v1/chat/completions` is rate limited per API key, 120 requests per 60 seconds
by default, shared with `/api/mcp`. Over the limit returns `429`.
`/v1/models` is not rate limited.

## Client configuration

Anything that speaks OpenAI needs two settings:

| Setting | Value |
|---|---|
| Base URL / API base | `https://rag.digdir.cloud/v1` |
| API key | your digdir API key |

```python
from openai import OpenAI

client = OpenAI(
    base_url="https://rag.digdir.cloud/v1",
    api_key="rag_your_api_key_here",
)

print(client.chat.completions.create(
    model="builtin.agent-rag-agent__agent-rag-graph-bundled",
    messages=[{"role": "user", "content": "Hva er Dialogporten?"}],
).choices[0].message.content)
```

The SDK sends `Authorization: Bearer …` on its own — that is why this works
without any digdir-specific code.

## What `/v1` does not do

- **No per-call overrides beyond dataset scope.** `tenant` and
  `dataset_config_key` are honoured; skill-params and other tuning are not —
  those come from the agent's own configuration, the same values the MCP path
  resolves. [`/api/mcp`](./mcp.md) is the surface with a full argument channel.
- **No token accounting.** `usage` is present for compatibility and reports
  zeros.
- **No tool calling.** The agent uses its own tools internally; it does not
  expose OpenAI function calling to the caller.

## See also

- [Authentication](../authentication.md) — key headers and scopes
- [MCP endpoint](./mcp.md) — the full-fidelity surface
- [OpenAPI spec](../openapi.yaml) — machine-readable contract

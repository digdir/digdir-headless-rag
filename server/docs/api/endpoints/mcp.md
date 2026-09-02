# MCP Server

`POST /api/mcp` exposes the agentic RAG runtime as a [Model Context
Protocol](https://modelcontextprotocol.io) server over Streamable HTTP.
A single endpoint speaks JSON-RPC 2.0 and supports both blocking
request/response and Server-Sent-Events (SSE) streaming.

The server implements revision **`2026-07-28`** only — there is no
`initialize` handshake and no session. See
[Protocol version and compatibility](#protocol-version-and-compatibility)
for what a client must send.

This replaced the old `/api/rag`, `/api/retrieve`, and
`/api/skill-graphs/*/execute` routes that were retired in the MCP
migration. New integrations should use MCP; the dormant retrieval-only
surface is not coming back.


## Protocol version and compatibility

**This server implements MCP revision `2026-07-28`, and only that revision.**
Every request declares its own protocol version; there is no handshake, no
session, and no standalone SSE stream.

| | |
|---|---|
| Revision implemented | `2026-07-28` |
| Era | **Modern** — per-request metadata, stateless |
| Previous revisions | `2025-11-25`, `2025-03-26` — **not** accepted |
| Policy | **Track the latest.** We do not pin. |

This reverses the position recorded in #124/#141, which pinned us to
`2025-03-26` and documented a trigger to move later. It also reverses the
standards analysis's own recommendation (#108 §7.2) against adopting
`2026-07-28` before release. Both reversals are deliberate (#146), and the
reasoning is below rather than in a commit message, because a reversal without
its reasoning is indistinguishable from drift.

**Why move now rather than later.** Staying legacy meant *keeping the dual-era
fallback correct*, and we were not getting it right: #139 recorded that we
answered an unknown modern method with HTTP `200` where the spec's fallback
rule needs a `4xx`, so two real clients worked only because they were more
lenient than the spec required. A modern-only server has **no fallback path to
get wrong**. The move deletes that bug class instead of adding a fix we would
then have to keep correct. We were also better positioned than the analysis
assumed: the conversation-handle pattern we had already chosen is the one
`2026-07-28` now recommends, so we were behind on revision, not on design.

### What a client must send

Every POST to `/api/mcp` **must** carry the request-metadata headers, mirrored
from the body. A request that omits or contradicts them is rejected with
`400` and JSON-RPC `-32020` (`HeaderMismatch`) — the mirroring exists so that
intermediaries can route on the header while the server executes on the body,
so a disagreement between the two is a vulnerability, not a convenience.

| Header | Mirrors | Required for |
|---|---|---|
| `MCP-Protocol-Version` | `params._meta["io.modelcontextprotocol/protocolVersion"]` | every request |
| `Mcp-Method` | `method` | every request |
| `Mcp-Name` | `params.name` | `tools/call` |

`Mcp-Name` values outside plain ASCII arrive base64-wrapped as
`=?base64?<b64>?=`; the server decodes before comparing.

Requesting any other version returns `400` with `-32022`
(`UnsupportedProtocolVersionError`) and the list of versions we support, so a
client can retry without guessing.

### Client compatibility — measured on a production artifact

The whole case for a modern-only server rests on real clients being modern or
dual-era. That was **tested, not inferred**: both clients below were driven
against a booted production uberjar through a logging proxy, and the column
that matters is *why*, not *whether*.

| Client | Version | Opening move on the wire | Result | Why |
|---|---|---|---|---|
| Claude Code | 2.1.238 | `server/discover` with `MCP-Protocol-Version: 2026-07-28`, then `tools/list`, then `tools/call` | **works** | Modern-era client. It never attempts `initialize`, so no fallback is involved — it works because both ends speak the same revision |
| MCP Inspector | 2.3.0 | `initialize`, no version header | **fails** | Legacy-only client. It has no fall-forward mechanism, so it cannot recover — this is the cost of the move, not a bug |

This is a snapshot of two clients on one date, not a guarantee about the
ecosystem. Re-run it before each release — see
[Conformance check](#conformance-check).

**The risk inverted when we moved.** Before: dual-era and legacy clients
worked, modern-only clients failed. Now: modern and dual-era clients work,
**legacy-only clients fail.** That is the deliberate trade.

Because a legacy-only client has no way to recover, the error it receives is
the only diagnostic its user will ever see. So `initialize` is not answered
with a bare "missing header" — it is answered with the versions we speak:

```json
{"jsonrpc":"2.0","id":0,"error":{
  "code":-32022,
  "message":"This server implements MCP 2026-07-28 only, which has no `initialize` handshake. Send requests directly with an MCP-Protocol-Version header instead.",
  "data":{"supported":["2026-07-28"],"requested":"initialize (handshake-based revision)"}}}
```

### Caching hints are required — and `tools/list` is `private`

Results with `resultType: "complete"` from `server/discover` and `tools/list`
**must** carry `ttlMs` and `cacheScope`. This is not cosmetic: omitting them
made Claude Code 2.1.238 reject `tools/list` outright, retry four times, and
report the server as having **no tools at all** — while every response was
HTTP `200` with a body that looked correct.

| Operation | `ttlMs` | `cacheScope` | Why |
|---|---|---|---|
| `server/discover` | 1 h | `public` | Identity, capabilities and supported versions are identical for every caller |
| `tools/list` | 60 s | **`private`** | **The tool list is filtered per API key.** A `public` result MAY be shared across authorization contexts *even from an authenticated endpoint* — so `public` here would let a cache serve one key's tool list to the holder of another |

The short `tools/list` TTL is deliberate: we advertise no `listChanged`
notifications, so TTL is the client's only invalidation signal, and an
operator enabling or disabling an agent changes this list.

### When we would pin instead of track

The policy is to track the latest revision. We would only pin if:

1. **A revision breaks a client our users actually bring**, and the fix is not
   available to them — the client table above is the tripwire.
2. **A revision requires a capability we cannot implement** on this transport.
3. **Revisions start arriving faster than we can verify them** against real
   clients, at which point pinning to a verified revision beats tracking an
   unverified one.

Absent one of those, a new revision is adopted and re-verified with the
procedure in [Conformance check](#conformance-check) plus a real client run.

## Resources and prompts

MCP has three primitives. This server implements **tools**. v0.1 ships without
`resources` and `prompts`, and that is a boundary rather than a gap — the
reasoning is in [considered divergences](../considered-divergences.md#no-resources-or-prompts-in-v01--a-boundary-not-a-gap).

The half that costs nothing is already here: cited source documents come back as
`resource_link` content blocks in the `tools/call` result, one per document, so a
client can follow a citation without this server declaring a `resources`
capability. A document with no public URL still gets a stable `digdir://doc/<n>`
uri so the citation can be shown and deduplicated rather than silently dropped.

## Authentication

Standard `X-API-Key` header — same key format as the rest of the
public API.

```
X-API-Key: rag_<64-hex-string>
```

No additional scope is required to open an MCP connection; per-tool
authorization is enforced on `tools/call` against the API key's
`:agent-refs`, `:skill-graphs`, and `:dataset-scopes`.

**Each of those grant lists means UNRESTRICTED when empty** (#349). A key with
no `:agent-refs` sees every agent's tools on `tools/list` and may call them —
measured, not inferred. A grant list narrows access rather than conferring it,
so there is no value that means "no agents".

## JSON-RPC envelope

Requests and responses follow JSON-RPC 2.0:

```json
{ "jsonrpc": "2.0",
  "id": <number-or-string>,
  "method": "<method-name>",
  "params": { ... } }
```

Notifications carry no `id` and receive no response body (HTTP `202 Accepted`).

## Methods

| Method             | Description |
|--------------------|-------------|
| `server/discover`  | **Mandatory in this revision.** Returns `supportedVersions`, `capabilities`, `serverInfo` and `instructions` in one round trip, so a client never has to probe to find out what we are. |
| `tools/list`       | Enumerates the `(agent, mode)` tools the calling API key can reach. Cacheable — `private`. |
| `tools/call`       | Invokes a tool. Supports both blocking (JSON response) and streaming (SSE) modes. |
| `ping`             | Liveness check. Returns `{}`. |

There is **no `initialize`** and no `notifications/initialized`: this revision
has no handshake. A request for `initialize` is answered with `400` and
`-32022` naming the versions we support (see
[Protocol version and compatibility](#protocol-version-and-compatibility)).

Unknown methods return **HTTP `404`** with JSON-RPC error code `-32601`
("Method not found") and the method name in `error.data.method`. The status
matters as much as the code: a JSON-RPC error inside a non-2xx response is what
lets a dual-era client tell a modern server from a legacy one. Returning `200`
here is the defect recorded in #139, which this revision removes.

`GET` and `DELETE` on the endpoint return `405`; both belonged to the
pre-`2026-07-28` shape (the standalone SSE stream and session termination).
A JSON-RPC notification is answered with `202 Accepted` and no body.

## Tool naming

Tool names follow the pattern:

```
<agent-id>__<skill-graph-short-name>
```

For example, `builtin.fact-checker-agent__fact-checker`. The
double-underscore separator is reserved; agent ids must not contain it.

Each agent surfaces one tool *per* skill graph in its
`:allowed-skill-graphs` list. The tool corresponding to the agent's
`:default-skill-graph` carries `_meta.default = true` so clients can
preselect a reasonable default in their UI.

## `tools/call` arguments

All preserved skill graphs share a single input schema (Malli source in
`digdir.skills.templates.core/agent-tool-input-schema`):

| Field                    | Type     | Required | Notes                                                                      |
|--------------------------|----------|----------|----------------------------------------------------------------------------|
| `query`                  | string   | yes      | The user's question. `user-query` is accepted as an alias.                  |
| `conversation-history`   | array    | no       | Vector of `{:role :text}` maps. Server-managed conversations fill this in. |
| `model`                  | string   | no       | Explicit override. Leave unset to honor the skill-graph's runtime config.  |
| `temperature`            | number   | no       | Same as `model` — overrides the runtime default when present.              |
| `claim`                  | string   | no       | Only read by `:builtin/fact-checker`; defaults to `query`.                  |
| `tenant`                 | string   | no       | Scope override (see below).                                                |
| `dataset_config_key`     | string   | no       | Scope override (see below).                                                |
| `conversation_id`        | string   | no       | Reuse an existing conversation; omit to start a new one.                   |
| `overrides`              | object   | no       | Per-call skill-param overrides (top-k, prompts, ...).                      |

### Scope resolution

`tools/call` needs a `(tenant, dataset-config-key)` pair to load the
dataset and resolve the skill graph. Resolution order:

1. If the agent's `:allowed-dataset-scopes` is non-empty, scope must
   intersect with the API key's `:dataset-scopes`. Explicit `tenant` /
   `dataset_config_key` in tool arguments filter that intersection.
2. If the agent has no scope restriction (the seeded built-in agents
   today), priority is: explicit args > API-key first scope > env
   defaults (`TENANT` / `DATASET_CONFIG_KEY`).

If no scope can be resolved, the call fails with an actionable error
naming the three knobs.

## What a `tools/call` result contains

Three channels, and a client can use any of them independently.

### `content` — for a reader

An array of blocks. The first is the answer as `type: "text"`. Cited source
documents follow as `resource_link` blocks, one per document rather than one per
chunk, ordered by first appearance so the best-ranked document leads. A document
with no public URL still gets a stable `digdir://doc/<n>` uri, so a citation can
be shown and deduplicated rather than silently dropped.

### `structuredContent` — for a program

Every tool advertises an `outputSchema` on `tools/list`, and `structuredContent`
is the object that conforms to it. This is where a client should read from when
it is building a UI rather than rendering prose:

| Field | Type | Notes |
|---|---|---|
| `conversation_id` | string | **The only required field.** Pass it back to continue the thread |
| `chunks` | array | Retrieved source chunks the answer drew on — each with `chunk_id`, `doc_num`, `chunk_index`, `content_length`, `total_chunks`, `title`, `url`, `metadata` |
| `queries` | array<string> | The search queries that were actually run |
| `search_attribution` | object | Which retrieval strategy contributed each hit |
| `clarification` | object | Present when the agent needs a clarifying answer before it can proceed |

Read the schema from `tools/list` rather than from this table if the two ever
disagree — the advertisement is generated from the same source the server
answers with, and this table is not.

### `_meta` — for the transport

`conversation_id` and `status` (`"complete"`, `"needs-clarification"`, or
`"error"`). `conversation_id` is duplicated here and in `structuredContent`
deliberately, so a client that reads only one of the two still gets the handle.

---

## Errors — which channel carries what

MCP has two error channels and they answer different questions. The rule
below was decided in #303, after a `tools/call` whose agent died at its first
LLM call came back `isError: false` with the failure text sitting in the
answer slot — a dead call that any caller branching on `isError`, and anything
counting successes, would have read as a good answer.

| Channel | Means | Example |
|---|---|---|
| JSON-RPC `error` + non-2xx | The request never became a tool call: malformed, unauthorized, or naming something that does not exist. | `-32601` unknown method, `-32020` header/body mismatch, `-32022` unsupported version, `-32602` unknown tool or agent |
| Result with `isError: true` | The call dispatched and the **tool** failed. The message is in `content` so it reaches the model, which can act on it (#117). | The agent could not reach its LLM; the dataset scope resolved but the graph failed |
| Result with `isError: false` | The tool ran **and produced an answer** — including an answer that says the evidence was insufficient. | A normal response; a `needs-clarification` result |

**An agent that could not produce an answer is `isError: true`.** It does not
matter which layer noticed: an exception escaping the graph and the agent's own
error terminal are the same event to a caller, and before #303 they returned
opposite flags depending only on where the exception was caught. "The tool call
succeeded, and the agent's answer is that it could not answer" is *not* the
reading here — an agent that never reached its model has no answer to report.

`_meta.status` mirrors this: `"error"` beside `isError: true`, `"complete"` or
`"needs-clarification"` otherwise. The failure text stays in `content` either
way; `isError` is what says whether to trust it as an answer.

## Server-managed conversations

Pass `conversation_id` to continue a thread; omit it and the server
creates a new conversation and returns the id on `_meta.conversation_id`.
Both user and assistant turns are persisted through the same Datahike
helpers the rest of the API uses, so MCP conversations show up in the
admin Playground sidebar.

MCP conversations are linear — branching stays a Playground-internal
concept.

## Blocking vs streaming

A `tools/call` request opens an SSE response when `_meta.progressToken`
is set. Otherwise the response is plain JSON.

```json
{ "jsonrpc": "2.0", "id": 1, "method": "tools/call",
  "params": {
    "name": "builtin.agent-rag-agent__agent-rag-graph-bundled",
    "arguments": { "query": "What is X?" },
    "_meta": { "progressToken": "client-supplied-id" }   // streams when set
  }}
```

### SSE streaming details

Response headers:

```
Content-Type: text/event-stream; charset=utf-8
Cache-Control: no-cache, no-transform
X-Accel-Buffering: no
```

Each frame is a single `data: <json>\n\n` block. Frames are either
`notifications/progress` for intermediate events or the final JSON-RPC
result/error frame:

```
data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":"client-id","progress":3,"message":"Iteration 1 of 5","_meta":{"event":"agent/iteration-started","iteration":1,"max-iterations":5}}}

data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":"client-id","progress":7,"message":"Here is the first paragraph.\n\n","_meta":{"event":"response/chunk","delta":"Here is the first paragraph.\n\n"}}}

data: {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Here is the first paragraph.\n\n..."}],"_meta":{"conversation_id":"convo-abc","status":"complete"}}}
```

Notification payload `_meta.event` carries the original skill event
name (`agent/iteration-started`, `tool/call`, `tool/result`,
`stage/started`, `response/chunk`, ...). The final result frame is the
same shape a blocking call returns.

### Token deltas

When the agent loop's LLM streams tokens, they're flushed as
`response/chunk` events at paragraph boundaries (`\n\n`) or after 250 ms
of buffered content, whichever comes first. Typical answers produce
~5–20 chunks instead of the ~1000 a per-token approach would.

### Heartbeats

Idle streams emit a `: ping\n\n` SSE comment frame every 15 s so
intermediate proxies don't close the connection.

### Client disconnect

If the client drops mid-stream, the server detects the broken pipe,
cancels the worker future, and interrupts the agent loop at its next
iteration boundary so work isn't wasted on a response that won't be
read.

## Rate limiting

`POST /api/mcp` is rate-limited per API key. Defaults: 120 requests per
60-second window. Exceeding the limit returns HTTP 429 with:

```json
{ "error": { "code": "rate_limited",
             "message": "Too many requests",
             "limit": 120,
             "window_ms": 60000 } }
```

## Curl examples

Every request needs the [request-metadata headers](#what-a-client-must-send).
Omitting them returns `400` with `-32020`, so these are the minimum that works.

Discovering the server:

```bash
curl -sS -X POST https://admin.kunnskap.digdir.cloud/api/mcp \
  -H "X-API-Key: $RAG_API_KEY" \
  -H "Content-Type: application/json" \
  -H "MCP-Protocol-Version: 2026-07-28" \
  -H "Mcp-Method: server/discover" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "server/discover",
    "params": {"_meta": {"io.modelcontextprotocol/protocolVersion": "2026-07-28"}}
  }' | jq
```

Blocking `tools/list`:

```bash
curl -sS -X POST https://admin.kunnskap.digdir.cloud/api/mcp \
  -H "X-API-Key: $RAG_API_KEY" \
  -H "Content-Type: application/json" \
  -H "MCP-Protocol-Version: 2026-07-28" \
  -H "Mcp-Method: tools/list" \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/list",
    "params": {"_meta": {"io.modelcontextprotocol/protocolVersion": "2026-07-28"}}
  }' | jq
```

Streaming `tools/call`. Note `Mcp-Name` — required for this method, and it
must equal `params.name` or the request is rejected:

```bash
curl -N -X POST https://admin.kunnskap.digdir.cloud/api/mcp \
  -H "X-API-Key: $RAG_API_KEY" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: 2026-07-28" \
  -H "Mcp-Method: tools/call" \
  -H "Mcp-Name: builtin.agent-rag-agent__agent-rag-graph-bundled" \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
      "name": "builtin.agent-rag-agent__agent-rag-graph-bundled",
      "arguments": { "query": "Hva er Digdir?" },
      "_meta": {
        "io.modelcontextprotocol/protocolVersion": "2026-07-28",
        "progressToken": "demo-1"
      }
    }
  }'
```

## Conformance check

The script at `server/scripts/mcp_conformance_check.clj` runs twelve checks
against a live ephemeral Jetty server: every method above, plus the rejection
paths this revision requires — header/body mismatch, unsupported version,
missing `Mcp-Method`, `GET`/`DELETE` → `405`, and a legacy `initialize` being
told which versions we speak. Run it after any change under `digdir.mcp.*`:

```bash
cd server
clojure -M -e '(load-file "scripts/mcp_conformance_check.clj") (mcp-conformance-check/-main)'
```

**It is not sufficient on its own.** A conformance suite is written from its
author's reading of the spec, so it cannot catch a misreading — the suite and
the bug have the same author. During #146 this script passed 11/11 while the
server was unusable: Claude Code 2.1.238 saw *zero* tools, because results were
missing the required `ttlMs`/`cacheScope` caching hints and the client rejected
them. Every response was HTTP `200` with a body that looked correct.

So before a release, also **drive a real client** and read the bytes:

```bash
# point a real MCP client at a booted server through a logging proxy,
# then check the CLIENT's own diagnostics, not just the server's
~/.claude/debug/   # Claude Code logs its MCP validation failures here
npx @modelcontextprotocol/inspector --cli <url> --transport http --method tools/list
```

When the server will not tell you what is wrong, the consumer's diagnostics
will.

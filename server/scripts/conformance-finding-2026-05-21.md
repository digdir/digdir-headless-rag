# MCP Conformance Check — 2026-05-21

> **Frozen record of one run on 2026-05-21. Superseded by #146.** The server
> has since moved to MCP `2026-07-28`, modern-era only: the `initialize`
> negotiation in check 2 no longer exists, and `2025-03-26` is no longer
> accepted. Kept as the measurement it was, not as a description of the
> server today.

Wire-protocol verification of `POST /api/mcp` against a live Jetty server
booted with the full production middleware stack. Run via:

```
cd server
clojure -M -m mcp-conformance-check
```

## Passed checks

1. **401 on unauthenticated request** — `wrap-api-key-auth` rejects before
   the MCP handler runs; response body is `{"error":"Invalid or missing
   API key"}`.
2. **`initialize` negotiation** — server returns `protocolVersion`
   `2025-03-26`, `capabilities.tools.listChanged=false`,
   `serverInfo {name:"digdir-rag", version:"1.0"}`.
   *(Superseded by #146 — the server speaks `2026-07-28` only and has no
   `initialize`; see the banner at the top.)*
3. **`tools/list`** — returns the cross-product of the 2 seeded built-in
   agents (`fact-checker-agent`, `agent-rag-agent`) × their allowed
   skill graphs = 3 tools. The `_meta.default` marker is set on each
   agent's default skill graph. Each tool carries a JSON-Schema
   `inputSchema` synthesized from the kept graph's Malli `:input-schema`.
4. **`ping`** — returns `{}` with status 200.
5. **`notifications/initialized`** — JSON-RPC notifications carry no `id`
   and must NOT receive a response body. Server returns HTTP 204.
6. **Unknown method** — returns JSON-RPC error code `-32601` ("Method not
   found") with the unknown method name in `error.data.method`.
7. **`tools/call` streaming** — supplying `_meta.progressToken` flips
   the response to `Content-Type: text/event-stream; charset=utf-8`,
   `Cache-Control: no-cache, no-transform`, `X-Accel-Buffering: no`.
   SSE frames are JSON-RPC notifications/responses, one per `data: …\n\n`
   block.

## Finding the smoke test surfaced (fixed)

**Empty `:allowed-dataset-scopes` on seeded built-in agents previously
left `tools/call` unable to resolve a dataset scope.**

The fact-checker and agent-rag agents are seeded with
`:allowed-dataset-scopes []`, meaning "no scope restriction". The
original `pick-dataset-scope` treated this as "no scope matches" and
returned a `no_dataset_scope` error, making the seeded agents
unusable via MCP.

**Fix:** `pick-dataset-scope` now resolves in two modes:

1. If the agent declares scopes, intersect with API-key scopes and any
   `tenant` / `dataset_config_key` filter from tool arguments. First
   match wins.
2. If the agent has no scope restriction, accept (in priority order)
   the scope passed in tool arguments, the API key's first scope, or
   the `TENANT` / `DATASET_CONFIG_KEY` env defaults the deleted
   `/api/rag` handler used.

Either way, the error message is now actionable: if no candidate is
found, it tells the caller exactly which three knobs to turn.

Two regression tests cover this:
- `invoke-tool-falls-back-when-agent-has-empty-scopes` — API-key scope
  is picked when the agent has no restriction.
- `invoke-tool-accepts-explicit-scope-args-on-unscoped-agent` —
  `tenant`/`dataset_config_key` in tool args override the API-key scope
  for unrestricted agents.

Re-running the conformance check now reaches the dataset-loader inside
`invoke-rag` (where the smoke-test stub dataset reasonably fails to
resolve). Step 7's SSE response carries that downstream error as a
proper JSON-RPC frame, confirming the streaming path is intact.

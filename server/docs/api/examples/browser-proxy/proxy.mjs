// A minimal backend-for-frontend that lets a browser use this API without ever
// seeing the API key. Copy it, change what you need, own the result.
//
// ---------------------------------------------------------------------------
// WHY THIS FILE EXISTS
// ---------------------------------------------------------------------------
// This API sends no CORS headers and requires authentication on every request.
// A browser preflight carries no credentials by specification, so it arrives
// unauthenticated and is rejected — measured, on a live server:
//
//   OPTIONS /api/mcp  (no key, i.e. every real preflight)  -> 401, 0 Access-Control-*
//   OPTIONS /api/mcp  (with a key, which no browser sends) -> 200, 0 Access-Control-*
//   POST    /api/mcp  (with a key, succeeds)               -> 200, 0 Access-Control-*
//
// So the browser path is closed twice over: the preflight cannot authenticate,
// and nothing carries the headers a browser needs to accept the response. That
// is deliberate. Opening it would mean exempting OPTIONS from auth AND adding
// permissive CORS, which together would publish "put your long-lived
// server-side key in a browser" as the sanctioned pattern. It is not.
//
// The browser talks to THIS process. This process holds the key.
//
// ---------------------------------------------------------------------------
// WHAT THIS IS NOT
// ---------------------------------------------------------------------------
// A reference, not a product, and not maintained as one. It deliberately does
// NOT do any of the following, all of which you must add before deploying:
//
//   - Authentication or sessions. Every caller is anonymous and equal. As
//     written, anyone who can reach this port can spend your API quota.
//   - Rate limiting, quotas, or abuse controls of any kind.
//   - Error mapping. Upstream failures are passed through roughly; the status
//     codes and bodies your frontend sees are not a designed contract.
//   - Input validation beyond a length cap, or any prompt-injection defence.
//   - Logging, tracing, metrics, or redaction of what users typed.
//   - TLS. Terminate it in front of this process.
//   - Retries, timeouts tuned for your traffic, or backpressure handling.
//
// ---------------------------------------------------------------------------
// RUN IT
// ---------------------------------------------------------------------------
//   export DIGDIR_API_KEY=<your key>          # never hard-code it
//   export DIGDIR_API_BASE=http://localhost:8081
//   node proxy.mjs                            # listens on 8787
//
// Node 18+ (uses built-in fetch). No dependencies, on purpose: you can read
// the whole thing before you trust it.

import http from 'node:http';

const PORT = Number(process.env.PORT ?? 8787);
const API_BASE = process.env.DIGDIR_API_BASE ?? 'http://localhost:8081';
const API_KEY = process.env.DIGDIR_API_KEY;

// The tool is pinned HERE, server-side, and is not something the browser gets
// to choose. Your key is scoped to particular agents and datasets; letting the
// browser name the tool would hand it the whole of that scope. The browser
// sends a question, not a target.
const TOOL = process.env.DIGDIR_TOOL ?? 'builtin.agent-rag-agent__agent-rag-graph-bundled';
const TENANT = process.env.DIGDIR_TENANT ?? 'digdir';
const DATASET_CONFIG_KEY = process.env.DIGDIR_DATASET_CONFIG_KEY ?? 'public-docs';

const PROTOCOL_VERSION = '2026-07-28';
const MAX_QUERY_CHARS = 4000;

if (!API_KEY) {
  console.error('DIGDIR_API_KEY is not set. Refusing to start: a proxy with no key');
  console.error('would forward unauthenticated requests and fail one layer further on.');
  process.exit(1);
}

/** Headers for the upstream call. The key lives only in here. */
function upstreamHeaders(method) {
  return {
    'Content-Type': 'application/json',
    'X-API-Key': API_KEY,
    'MCP-Protocol-Version': PROTOCOL_VERSION,
    'Mcp-Method': method,
    'Mcp-Name': TOOL,
  };
}

/** The JSON-RPC body. `progressToken` is what makes the response stream. */
function toolCallBody({ query, conversationId, streaming }) {
  const meta = { 'io.modelcontextprotocol/protocolVersion': PROTOCOL_VERSION };
  if (streaming) meta.progressToken = `bff-${Date.now()}`;
  const args = { 'user-query': query, tenant: TENANT, dataset_config_key: DATASET_CONFIG_KEY };
  if (conversationId) args.conversation_id = conversationId;
  return JSON.stringify({
    jsonrpc: '2.0',
    id: 1,
    method: 'tools/call',
    params: { name: TOOL, arguments: args, _meta: meta },
  });
}

async function readJsonBody(req) {
  const chunks = [];
  let bytes = 0;
  for await (const c of req) {
    bytes += c.length;
    if (bytes > 64 * 1024) throw new Error('request body too large');
    chunks.push(c);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
}

function badRequest(res, message) {
  res.writeHead(400, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ error: message }));
}

/** Blocking call: one request, one JSON answer. */
async function handleChat(req, res) {
  let body;
  try {
    body = await readJsonBody(req);
  } catch {
    return badRequest(res, 'invalid JSON body');
  }
  const query = String(body.query ?? '').slice(0, MAX_QUERY_CHARS);
  if (!query) return badRequest(res, 'missing "query"');

  const upstream = await fetch(`${API_BASE}/api/mcp`, {
    method: 'POST',
    headers: upstreamHeaders('tools/call'),
    body: toolCallBody({ query, conversationId: body.conversation_id, streaming: false }),
  });

  const text = await upstream.text();
  // Pass the upstream status through rather than inventing one. Mapping these
  // to something your frontend wants is one of the things this file does not do.
  res.writeHead(upstream.status, { 'Content-Type': 'application/json' });
  res.end(text);
}

/**
 * Streaming call. THIS is the part that is easy to get wrong, and the failure
 * is silent: a proxy that buffers still delivers every byte, so the client sees
 * a correct answer that simply arrived late, and looks slow rather than broken.
 *
 * Four things keep it incremental, and all four matter:
 *   1. Write each upstream chunk the moment it arrives. Never accumulate and
 *      never await the whole body (`await upstream.text()` is the classic bug).
 *   2. flushHeaders() so the browser can open the stream before the first byte.
 *   3. setNoDelay(true) — without it Nagle holds small frames back.
 *   4. X-Accel-Buffering: no — nginx and several PaaS proxies buffer
 *      text/event-stream by default and will re-introduce the bug outside
 *      this process. Do not remove it because "it works locally".
 *
 * Do not put compression middleware in front of this route: gzip buffers to
 * fill its window and undoes all of the above.
 */
async function handleChatStream(req, res) {
  let body;
  try {
    body = await readJsonBody(req);
  } catch {
    return badRequest(res, 'invalid JSON body');
  }
  const query = String(body.query ?? '').slice(0, MAX_QUERY_CHARS);
  if (!query) return badRequest(res, 'missing "query"');

  // If the browser goes away, stop the upstream work instead of leaking an
  // in-flight agent run. The server honours the disconnect.
  const abort = new AbortController();
  res.on('close', () => abort.abort());

  let upstream;
  try {
    upstream = await fetch(`${API_BASE}/api/mcp`, {
      method: 'POST',
      headers: { ...upstreamHeaders('tools/call'), Accept: 'text/event-stream' },
      body: toolCallBody({ query, conversationId: body.conversation_id, streaming: true }),
      signal: abort.signal,
    });
  } catch (e) {
    if (!res.headersSent) badRequest(res, `upstream unreachable: ${e.message}`);
    return;
  }

  if (!upstream.ok || !upstream.body) {
    const text = await upstream.text().catch(() => '');
    res.writeHead(upstream.status, { 'Content-Type': 'application/json' });
    return res.end(text || JSON.stringify({ error: 'upstream error' }));
  }

  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache, no-transform',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no',
  });
  res.flushHeaders();
  res.socket?.setNoDelay(true);

  const reader = upstream.body.getReader();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      res.write(value); // straight through, no accumulation
    }
  } catch {
    // Client hung up, or upstream died mid-stream. Either way the socket is
    // going; there is nothing useful to say on it.
  } finally {
    res.end();
  }
}

const server = http.createServer((req, res) => {
  if (req.method === 'POST' && req.url === '/api/chat') return handleChat(req, res);
  if (req.method === 'POST' && req.url === '/api/chat/stream') return handleChatStream(req, res);
  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ error: 'not found' }));
});

server.listen(PORT, () => {
  console.log(`reference BFF on http://localhost:${PORT}  ->  ${API_BASE}`);
  console.log(`tool pinned server-side: ${TOOL}`);
  console.log('the API key is held by this process and is never sent to the browser');
});

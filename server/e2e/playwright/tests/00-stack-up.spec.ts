import { test, expect, request } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Stack health checks. These pass without any LLM credentials —
 * everything they hit is below the LLM layer.
 *
 * WHAT THIS FILE IS FOR: it is the regression net for the three surfaces we
 * actually ship, and they answer different questions:
 *
 *   - `/api/mcp` spoken by a MODERN client (server/discover + tools/list).
 *     This is the one that would have caught the 2026-08-21 revision move
 *     from the direction that matters — us breaking our own contract.
 *   - `/v1/models`, agents as MODELS — what a newcomer meets in
 *     docker-compose.newcomer.yml.
 *   - `/api/tools/openapi.json`, agents as TOOLS — the surface that replaced
 *     MCPO. The MCPO assertions that used to live here are gone with the
 *     container; see ../../README.md.
 */

const DEV_SERVER = process.env.DEV_SERVER_URL ?? 'http://localhost:8080';
const OPENWEBUI  = process.env.OPENWEBUI_URL  ?? 'http://localhost:3030';

const MCP_REVISION = '2026-07-28';

/**
 * The API key the stack seeded at boot. `server/e2e/.env` is the single
 * definition of this stack — digdir-rag reads it via env_file and Open WebUI
 * gets it through the compose file — so read it from there rather than
 * duplicating the literal. A copy would drift silently and the tests would
 * fail as 401s that look like an auth bug.
 */
function e2eApiKey(): string {
  if (process.env.E2E_API_KEY) return process.env.E2E_API_KEY;
  const envPath = join(__dirname, '..', '..', '.env');
  const line = readFileSync(envPath, 'utf8')
    .split('\n')
    .find((l) => l.startsWith('E2E_API_KEY='));
  if (!line) {
    throw new Error(
      `No E2E_API_KEY in the environment and none found in ${envPath}. ` +
        'Run `cp server/e2e/.env.example server/e2e/.env` first.',
    );
  }
  return line.slice('E2E_API_KEY='.length).trim();
}

/**
 * A modern-era MCP request. Every POST must mirror its body metadata into
 * headers or the server rejects it with 400 / -32020 (HeaderMismatch), so
 * the mirroring is part of what these tests assert by construction: get it
 * wrong here and the request fails.
 */
function mcpRequest(method: string, extraHeaders: Record<string, string> = {}) {
  return {
    headers: {
      'Content-Type': 'application/json',
      'X-API-Key': e2eApiKey(),
      'MCP-Protocol-Version': MCP_REVISION,
      'Mcp-Method': method,
      ...extraHeaders,
    },
    data: JSON.stringify({
      jsonrpc: '2.0',
      id: 1,
      method,
      params: { _meta: { 'io.modelcontextprotocol/protocolVersion': MCP_REVISION } },
    }),
  };
}

test.describe('e2e stack health', () => {
  test('dev server health endpoint responds', async () => {
    const ctx = await request.newContext();
    const r = await ctx.get(`${DEV_SERVER}/up`);
    expect(r.status()).toBe(200);
    expect(await r.text()).toBe('ok');
  });

  test('MCP endpoint rejects unauthenticated request with 401', async () => {
    const ctx = await request.newContext();
    const r = await ctx.post(`${DEV_SERVER}/api/mcp`, {
      headers: { 'Content-Type': 'application/json' },
      data: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize' }),
    });
    expect(r.status()).toBe(401);
  });

  test('MCP server/discover advertises the revision we implement', async () => {
    const ctx = await request.newContext();
    const r = await ctx.post(`${DEV_SERVER}/api/mcp`, mcpRequest('server/discover'));
    expect(r.status()).toBe(200);
    const body = await r.json();
    expect(body.error, 'server/discover must not return a JSON-RPC error').toBeUndefined();

    const result = body.result ?? {};
    expect(result.supportedVersions).toContain(MCP_REVISION);
    expect(result.capabilities?.tools, 'the tools capability must be advertised').toBeDefined();

    // Not cosmetic: omitting these made Claude Code 2.1.238 report the server
    // as having no tools at all, while every response was a 200 that looked
    // correct. See ../../docs/api/endpoints/mcp.md, "Caching hints are required".
    expect(result.resultType).toBe('complete');
    expect(typeof result.ttlMs, 'a complete result must carry ttlMs').toBe('number');
    expect(result.cacheScope).toBe('public');
  });

  test('MCP tools/list returns tools to a modern client', async () => {
    const ctx = await request.newContext();
    const r = await ctx.post(`${DEV_SERVER}/api/mcp`, mcpRequest('tools/list'));
    expect(r.status()).toBe(200);
    const body = await r.json();
    expect(body.error, 'tools/list must not return a JSON-RPC error').toBeUndefined();

    const tools = body.result?.tools ?? [];
    expect(tools.length, 'the seeded agents must surface as tools').toBeGreaterThan(0);
    expect(JSON.stringify(tools)).toMatch(/agent-rag-agent|fact-checker-agent/);

    // `private`, not `public`: the tool list is filtered per API key, and a
    // public result MAY be shared across authorization contexts even from an
    // authenticated endpoint. Getting this wrong leaks one key's tool list.
    expect(body.result?.cacheScope).toBe('private');
  });

  test('/v1/models lists the agents Open WebUI attaches to', async () => {
    // This is the surface docker-compose.newcomer.yml wires Open WebUI to.
    // If it empties, a newcomer gets a chat UI with a blank model dropdown.
    const ctx = await request.newContext();
    const r = await ctx.get(`${DEV_SERVER}/v1/models`, {
      headers: { Authorization: `Bearer ${e2eApiKey()}` },
    });
    expect(r.status()).toBe(200);
    const models = (await r.json()).data ?? [];
    expect(models.length, '/v1/models must advertise at least one agent').toBeGreaterThan(0);
    expect(JSON.stringify(models)).toMatch(/agent-rag-agent|fact-checker-agent/);
  });

  test('the OpenAPI tool document advertises our tools', async () => {
    // This is the surface that REPLACED MCPO. Open WebUI reads it as an
    // "external tool server", so it is what makes our agents tool-callable
    // from someone else's chat — the thing the MCP client was meant to buy.
    const ctx = await request.newContext();
    const r = await ctx.get(`${DEV_SERVER}/api/tools/openapi.json`, {
      headers: { Authorization: `Bearer ${e2eApiKey()}` },
    });
    expect(r.status()).toBe(200);
    const spec = await r.json();
    const paths = Object.keys(spec.paths ?? {});
    expect(paths.length, 'the tool document must advertise at least one tool').toBeGreaterThan(0);

    // Open WebUI executes a tool as `connection-url + route-path`, so a path
    // that is not absolute from the root silently produces a URL with no
    // /api/tools segment and every call 404s.
    expect(paths.every((p) => p.startsWith('/api/tools/call/'))).toBe(true);

    // operationId is the function name handed to the model AND the key Open
    // WebUI matches a call back to a route by. If it drifts from the tool
    // name, the same agent has two names depending on the surface.
    for (const p of paths) {
      expect(spec.paths[p].post.operationId).toBe(p.replace('/api/tools/call/', ''));
    }
    expect(JSON.stringify(paths)).toMatch(/agent-rag-agent|fact-checker-agent/);
  });

  test('an unknown tool is a 404, not a 500', async () => {
    const ctx = await request.newContext();
    const r = await ctx.post(`${DEV_SERVER}/api/tools/call/no-such-agent__no-such-mode`, {
      headers: { Authorization: `Bearer ${e2eApiKey()}`, 'Content-Type': 'application/json' },
      data: JSON.stringify({ query: 'hei' }),
    });
    expect(r.status()).toBe(404);
  });

  test('the tool document requires authentication', async () => {
    const ctx = await request.newContext();
    const r = await ctx.get(`${DEV_SERVER}/api/tools/openapi.json`);
    expect(r.status()).toBe(401);
  });

  test('Open WebUI is reachable', async ({ page }) => {
    await page.goto(OPENWEBUI);
    // Open WebUI's title contains "Open WebUI" on every recent build.
    await expect(page).toHaveTitle(/Open WebUI/i, { timeout: 20_000 });
  });
});

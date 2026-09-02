import { test, expect, request } from '@playwright/test';

/**
 * Stack health checks. These pass without any LLM credentials —
 * everything they hit is below the LLM layer.
 *
 * If `bb e2e:up` finished cleanly, all three services should be
 * reachable and MCPO should have discovered the MCP tools from the
 * dev server.
 */

const DEV_SERVER = process.env.DEV_SERVER_URL ?? 'http://localhost:8080';
const MCPO_URL   = process.env.MCPO_URL       ?? 'http://localhost:8765';
const OPENWEBUI  = process.env.OPENWEBUI_URL  ?? 'http://localhost:3030';

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

  test('MCPO discovers our MCP tools via OpenAPI', async () => {
    const ctx = await request.newContext();
    // MCPO mounts each MCP server under its own path. The root
    // /openapi.json is a directory page; the actual tool paths live
    // under /<server-name>/openapi.json (server-name is the key
    // from mcpo/config.json.template — "digdir-rag" in our case).
    const r = await ctx.get(`${MCPO_URL}/digdir-rag/openapi.json`);
    expect(r.status()).toBe(200);
    const spec = await r.json();
    const paths = Object.keys(spec.paths ?? {});
    expect(paths.length, 'MCPO must surface at least one tool path').toBeGreaterThan(0);
    const allOps = JSON.stringify(spec);
    expect(allOps).toMatch(/agent-rag-agent|fact-checker-agent/);
  });

  test('Open WebUI is reachable', async ({ page }) => {
    await page.goto(OPENWEBUI);
    // Open WebUI's title contains "Open WebUI" on every recent build.
    await expect(page).toHaveTitle(/Open WebUI/i, { timeout: 20_000 });
  });
});

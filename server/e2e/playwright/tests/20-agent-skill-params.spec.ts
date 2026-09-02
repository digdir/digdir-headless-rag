import { test, expect, request, APIRequestContext } from '@playwright/test';

/**
 * Layer-C E2E: agent-scoped skill-params.
 *
 * Proves that the `:agent/skill-params` field on an agent flows through
 * `build-rag-skill-params` end-to-end in a live dev-server container.
 *
 * Strategy: hit `/api/debug/agent-resolution?agent-id=<id>` (a debug
 * endpoint that synchronously runs the agent → merge resolution with
 * an empty dataset-config and empty per-call params). This intentionally
 * sidesteps Typesense + the LLM loop because we want to verify the
 * *plumbing*, not the retrieval. The full happy-path through Open WebUI
 * is in `10-tool-invocation.spec.ts` and is gated on AZURE_OPENAI creds.
 *
 * Both fixture agents (`e2e/altinn-docs-default` and `e2e/altinn-docs-tuned`)
 * are seeded by `digdir.e2e.seed/seed-e2e-fixture-agents!` at boot.
 */

const DEV_SERVER     = process.env.DEV_SERVER_URL    ?? 'http://localhost:8080';
const DEBUG_API_KEY  = process.env.RAG_DEBUG_API_KEY ?? 'e2e-debug-key';

async function readEdn(ctx: APIRequestContext, path: string) {
  const r = await ctx.get(`${DEV_SERVER}${path}`, {
    headers: { 'X-Debug-Api-Key': DEBUG_API_KEY },
  });
  const body = await r.text();
  // EDN parsing is far simpler than the full grammar — we only need to
  // pull a few values out for assertions, and the handler returns small,
  // deterministic shapes. Read as text and grep with a regex; if you need
  // richer inspection, dump it to the test output.
  return { status: r.status(), body };
}

async function fetchResolution(ctx: APIRequestContext, agentId: string) {
  return readEdn(ctx, `/api/debug/agent-resolution?agent-id=${encodeURIComponent(agentId)}`);
}

test.describe('agent-scoped skill-params via /api/debug/agent-resolution', () => {
  test('default agent has empty :agent-skill-params and only hardcoded defaults resolved', async () => {
    const ctx = await request.newContext();
    const { status, body } = await fetchResolution(ctx, 'e2e/altinn-docs-default');
    expect(status, body).toBe(200);
    expect(body).toContain(':agent-id "e2e/altinn-docs-default"');
    expect(body).toContain(':found? true');
    expect(body).toContain(':enabled? true');
    // No agent overrides → :agent-skill-params is an empty map.
    expect(body).toMatch(/:agent-skill-params \{\}/);
    // Hardcoded floor from build-skill-params-from-config kicks in:
    // retrieve-top-k 100 + query-aware-boost true.
    expect(body).toMatch(/:retrieve-top-k 100/);
    expect(body).toMatch(/:query-aware-boost true/);
    // No phrase-only weights — the merge of {} agent over {} dataset
    // leaves strategy-weights unset.
    expect(body).not.toMatch(/:phrase 1\.0/);
  });

  test('tuned agent surfaces phrase-only weights, caps, retrieve-top-k=100, rerank top-k=20', async () => {
    const ctx = await request.newContext();
    const { status, body } = await fetchResolution(ctx, 'e2e/altinn-docs-tuned');
    expect(status, body).toBe(200);
    expect(body).toContain(':agent-id "e2e/altinn-docs-tuned"');
    expect(body).toContain(':found? true');
    // The agent's raw :skill-params must echo back exactly.
    expect(body).toMatch(/:phrase 1\.0/);
    expect(body).toMatch(/:content 0\.0/);
    expect(body).toMatch(/:metadata 0\.0/);
    // Strategy contribution caps from the Round-5 winner.
    expect(body).toMatch(/:strategy-contribution-caps \{:phrase 5/);
    // Rerank top-k override survives the merge.
    expect(body).toMatch(/:builtin\/rerank \{:top-k 20\}/);
    // retrieve-top-k 100 should appear in both :agent-skill-params AND
    // :resolved-skill-params (the agent layer pins it; the floor also
    // happens to be 100, which is fine — the merge is consistent).
    expect(body).toMatch(/:retrieve-top-k 100/);
  });

  test('switching the agent-id query param produces measurably different resolved skill-params', async () => {
    const ctx = await request.newContext();
    const def = await fetchResolution(ctx, 'e2e/altinn-docs-default');
    const tuned = await fetchResolution(ctx, 'e2e/altinn-docs-tuned');
    expect(def.status).toBe(200);
    expect(tuned.status).toBe(200);
    // Strong signal that the agent-id arg actually selected different
    // bodies — without skill-params on the agent, the response is a
    // strict prefix of the tuned response in the merge fields we care
    // about.
    expect(def.body).not.toEqual(tuned.body);
    expect(tuned.body).toMatch(/:phrase 1\.0/);
    expect(def.body).not.toMatch(/:phrase 1\.0/);
  });

  test('unknown agent-id returns 404', async () => {
    const ctx = await request.newContext();
    const { status, body } = await fetchResolution(ctx, 'e2e/never-seeded');
    expect(status).toBe(404);
    expect(body).toContain(':found? false');
  });

  test('endpoint refuses requests without X-Debug-Api-Key', async () => {
    const ctx = await request.newContext();
    const r = await ctx.get(`${DEV_SERVER}/api/debug/agent-resolution?agent-id=e2e/altinn-docs-tuned`);
    // wrap-debug-api-key-auth returns 401 when the header is absent
    // and a key is configured (it is in the e2e stack).
    expect(r.status()).toBe(401);
  });
});

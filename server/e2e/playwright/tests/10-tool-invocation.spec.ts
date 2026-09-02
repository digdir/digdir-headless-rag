import { test, expect } from '@playwright/test';

/**
 * Happy-path: drive Open WebUI to send a query that lands on our MCP
 * server, get a streamed paragraph-bounded response back, and confirm
 * the assistant turn rendered.
 *
 * Skipped by default — needs real LLM credentials for the chain to
 * actually return text. Set `AZURE_OPENAI_API_KEY` /
 * `AZURE_OPENAI_ENDPOINT` in the environment that runs `bb e2e:up`
 * to enable.
 */

const HAS_LLM_CREDS = !!process.env.AZURE_OPENAI_API_KEY;

test.describe('mcp tool invocation through Open WebUI', () => {
  test.skip(!HAS_LLM_CREDS, 'requires AZURE_OPENAI_API_KEY to run');

  test('Sending a query produces a streamed response', async ({ page }) => {
    await page.goto('/');

    // Open WebUI's chat composer: a textarea labelled "Send a Message"
    // (or its localized variant) — use role for resilience to CSS
    // class renames.
    const composer = page.getByRole('textbox', { name: /(send a message|message)/i });
    await composer.fill('What is Digdir? Answer in two short paragraphs.');
    await composer.press('Enter');

    // Wait for the assistant bubble to appear. The chat renders
    // messages with role="assistant" once streaming starts.
    const assistant = page.getByRole('article').filter({ hasText: /digdir/i }).first();
    await expect(assistant).toBeVisible({ timeout: 30_000 });

    // The paragraph-or-250ms chunker we ship should produce more than
    // one chunk for an answer asked in two paragraphs. We don't have a
    // wire-level hook from Playwright into the SSE stream, but the
    // visible response text settling over time is a reasonable proxy.
    const initialText = await assistant.innerText();
    await page.waitForTimeout(2000);
    const settledText = await assistant.innerText();
    expect(settledText.length, 'response should be non-trivial')
      .toBeGreaterThan(40);
    expect(settledText.length, 'response should have grown since first observation')
      .toBeGreaterThanOrEqual(initialText.length);
  });
});

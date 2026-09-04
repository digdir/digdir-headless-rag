import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright config for the digdir-rag E2E suite.
 *
 * Targets the local docker-compose stack: Open WebUI on :3030 and the dev
 * server on :8080 (the MCPO container is gone — see ../README.md). Reuse the
 * existing compose stack
 * (`bb e2e:up`) — Playwright does NOT start the services itself,
 * because Docker startup is too slow to lump into every test run.
 */
export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',

  use: {
    baseURL: process.env.OPENWEBUI_URL ?? 'http://localhost:3030',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});

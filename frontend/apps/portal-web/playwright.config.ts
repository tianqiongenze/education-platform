import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright config for portal-web E2E tests.
 *
 * Tests live in ./e2e and run against the Vite dev server (port 3004). The
 * dev server proxies `/api/*` to the BFF (bff-java) on localhost:8090; when
 * the BFF is unavailable, specs mock the API responses with `page.route()`.
 *
 * Tests run sequentially (workers: 1) because the homepage/catalog share the
 * React Query cache and parallel runs would race on the mocked responses.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [['html', { open: 'never' }], ['list']],
  timeout: 60_000,
  expect: {
    timeout: 10_000,
  },
  use: {
    baseURL: 'http://localhost:3004',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'pnpm dev',
    url: 'http://localhost:3004',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
});

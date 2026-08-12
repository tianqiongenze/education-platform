import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright config for admin-web E2E tests.
 *
 * Tests live in ./e2e and run against the Vite dev server (port 3000).
 * The dev server proxies `/api/v1/*` to sp03-business (8083) and
 * sp05-itbackend (8085), so tests can hit the real backend services.
 *
 * Tests run sequentially (workers: 1, fullyParallel: false) because the
 * suite shares backend state across scenarios and parallel runs would
 * produce flaky ordering bugs.
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
    baseURL: 'http://localhost:3000',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: {
    command: 'pnpm dev',
    url: 'http://localhost:3000',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
});

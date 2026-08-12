import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { resolve } from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
      '@ep/api-client': resolve(__dirname, '../../packages/api-client/src'),
      '@ep/query': resolve(__dirname, '../../packages/query/src'),
      '@ep/shared': resolve(__dirname, '../../packages/shared/src'),
      '@ep/ui-kit': resolve(__dirname, '../../packages/ui-kit/src'),
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    exclude: [
      '**/node_modules/**',
      '**/dist/**',
      // Playwright E2E specs live in ./e2e and are run via `pnpm e2e`,
      // not through vitest. Exclude them so `vitest run` only collects
      // unit/component tests under ./src.
      'e2e/**',
    ],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'lcov'],
      thresholds: { lines: 70, functions: 70, branches: 65 },
    },
  },
});

import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';

export default defineConfig({
  plugins: [react()],
  test: {
    exclude: ['node_modules', 'dist', 'e2e/**'],
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.test.{ts,tsx}'],
    // D-137: Hard coverage gate — frontend micro-apps must maintain minimum
    // coverage. Thresholds are intentionally conservative (60/50) to match
    // the Java 50% and Go 60% gates while accounting for UI testing overhead.
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html'],
      thresholds: {
        lines: 60,
        functions: 60,
        branches: 50,
        statements: 60,
      },
    },
  },
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
});

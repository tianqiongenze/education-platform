import { defineConfig } from 'vite';
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
  server: {
    port: 3004,
    proxy: {
      // portal-web routes all backend traffic through the BFF (bff-java) on
      // localhost:8090. The BFF fans out to sp03-business / sp05-itbackend.
      '/api': {
        target: 'http://localhost:8090',
        changeOrigin: true,
      },
    },
  },
  build: {
    target: 'es2022',
    sourcemap: true,
    rollupOptions: {
      output: {
        manualChunks: {
          'react-vendor': ['react', 'react-dom', 'react-router-dom'],
          'antd-vendor': ['antd', '@ant-design/icons'],
          'query-vendor': ['@tanstack/react-query'],
        },
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test-setup.ts',
  },
});

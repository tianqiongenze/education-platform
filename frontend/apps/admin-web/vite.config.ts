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
    port: 3000,
    proxy: {
      // System A: admin-web calls sp03-business and sp05-itbackend directly
      // (NOT through BFF). Route by path prefix to the correct backend.
      // Vite matches the longest prefix first, so /api/v1/users wins over
      // /api below.
      '/api/v1/users': { target: 'http://localhost:8083', changeOrigin: true },
      '/api/v1/courses': { target: 'http://localhost:8083', changeOrigin: true },
      '/api/v1/orders': { target: 'http://localhost:8083', changeOrigin: true },
      '/api/v1/assignments': { target: 'http://localhost:8085', changeOrigin: true },
      '/api/v1/submissions': { target: 'http://localhost:8085', changeOrigin: true },
      '/api/v1/grading': { target: 'http://localhost:8085', changeOrigin: true },
      // Fallback: BFF for System B paths like /api/users, /api/courses, /api/user/profile
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
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
});

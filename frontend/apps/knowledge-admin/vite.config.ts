import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve, normalize, dirname } from 'path';

/**
 * Redirect the ui-kit Taro platform adapter to the web adapter.
 * See ADR-D217.  Full rationale in apps/admin-web/vite.config.ts.
 */
function redirectTaroToWeb(uiKitSrcDir: string): Plugin {
  const taroNoExt = normalize(resolve(uiKitSrcDir, 'platform/taro')).replace(/\.(ts|tsx|js|jsx)$/, '');
  const webPath = resolve(uiKitSrcDir, 'platform/web.ts');
  return {
    name: 'redirect-taro-to-web',
    enforce: 'pre',
    resolveId(source, importer) {
      if (!importer) return null;
      const resolved = normalize(resolve(dirname(importer), source)).replace(/\.(ts|tsx|js|jsx)$/, '');
      if (resolved === taroNoExt) return webPath;
      return null;
    },
  };
}

export default defineConfig({
  plugins: [
    react(),
    redirectTaroToWeb(resolve(__dirname, '../../packages/ui-kit/src')),
  ],
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
    port: 3002,
    proxy: {
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
});

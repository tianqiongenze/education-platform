import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve, normalize, dirname } from 'path';

/**
 * Redirect the ui-kit Taro platform adapter (`platform/taro.ts`) to the web
 * adapter (`platform/web.ts`) during Vite/Rollup builds.
 *
 * `platform/index.ts` contains `import * as taro from './taro'`.  Rollup
 * statically analyses every imported module — including unused namespace
 * imports — and `taro.ts` re-exports from `@tarojs/components` whose Stencil
 * H5 build has incomplete named exports under pnpm dual-instance resolution.
 *
 * A plain `resolve.alias` does not work because the specifier `'./taro'` is
 * relative and already resolved to an absolute path before the alias plugin
 * runs.  This plugin intercepts at the `resolveId` phase (`enforce: 'pre'`).
 *
 * See ADR-D217.
 */
function redirectTaroToWeb(uiKitSrcDir: string): Plugin {
  const taroNoExt = normalize(resolve(uiKitSrcDir, 'platform/taro')).replace(/\.(ts|tsx|js|jsx)$/, '');
  const webPath = resolve(uiKitSrcDir, 'platform/web.ts');
  return {
    name: 'redirect-taro-to-web',
    enforce: 'pre',
    resolveId(source, importer) {
      if (!importer) return null;
      // `resolve(importer, source)` treats `importer` as a file path, so for a
      // relative import like './taro' we must resolve against the importer's
      // directory — not the importer file itself.
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

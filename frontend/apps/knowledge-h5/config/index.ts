import { defineConfig, type UserConfigExport } from '@tarojs/cli';
import devConfig from './dev';
import prodConfig from './prod';

export default defineConfig(async (merge) => {
  const base: UserConfigExport = {
    projectName: 'knowledge-h5',
    date: '2026-08-05',
    designWidth: 750,
    deviceRatio: { 640: 2.34 / 2, 750: 1, 828: 1.81 / 2 },
    sourceRoot: 'src',
    outputRoot: 'dist',
    plugins: [],
    defineConstants: {},
    copy: { patterns: [], options: {} },
    framework: 'react',
    compiler: 'webpack5',
    cache: { enable: false },
    mini: {},
    h5: {
      publicPath: '/',
      staticDirectory: 'static',
      output: {
        filename: 'js/[name].[hash:8].js',
        chunkFilename: 'js/[name].[chunkhash:8].js',
      },
      router: { mode: 'browser' },
      devServer: {
        port: 3003,
        proxy: {
          '/api': {
            target: 'http://localhost:8090',
            changeOrigin: true,
          },
        },
      },
    },
  };
  return merge(
    base,
    process.env.NODE_ENV === 'development' ? devConfig : prodConfig,
  );
});

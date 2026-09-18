import { defineConfig, type UserConfigExport } from '@tarojs/cli';
import { resolve } from 'path';
import devConfig from './dev';
import prodConfig from './prod';

export default defineConfig(async (merge) => {
  const base: UserConfigExport = {
    projectName: 'student-mp',
    date: '2026-08-04',
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
    // Taro's Config.getConfigWithNamed() cherry-picks known config keys and
    // merges initialConfig.h5 / initialConfig.mini — it does NOT transfer
    // top-level `compile` or `webpackChain`. So we put them inside BOTH h5
    // and mini to ensure they survive to the webpack runner. See ADR-D217.
    mini: {
      compile: {
        include: [resolve(__dirname, '../../../packages')],
      },
      webpackChain(chain: any) {
        chain.resolve.extensions
          .prepend('.js')
          .prepend('.jsx');
        // Support @/ path alias (tsconfig paths)
        chain.resolve.alias
          .set('@', require('path').resolve(__dirname, '../src'));
        chain.module
          .rule('pkg-ts')
          .test(/\.(ts|tsx)$/)
          .include.add(resolve(__dirname, '../../../packages'))
          .end()
          .use('babel-loader')
          .loader('babel-loader')
          .options({
            presets: [
              ['babel-preset-taro', { framework: 'react', ts: true }],
            ],
          });
      },
      postcss: {
        pxtransform: { enable: true, config: {} },
        cssModules: {
          enable: false,
          config: {
            namingPattern: 'module',
            generateScopedName: '[name]__[local]___[hash:base64:5]',
          },
        },
      },
    },
    h5: {
      compile: {
        include: [resolve(__dirname, '../../../packages')],
      },
      webpackChain(chain: any) {
        chain.resolve.extensions
          .prepend('.js')
          .prepend('.jsx');
        // Support @/ path alias (tsconfig paths)
        chain.resolve.alias
          .set('@', require('path').resolve(__dirname, '../src'));
        chain.module
          .rule('pkg-ts')
          .test(/\.(ts|tsx)$/)
          .include.add(resolve(__dirname, '../../../packages'))
          .end()
          .use('babel-loader')
          .loader('babel-loader')
          .options({
            presets: [
              ['babel-preset-taro', { framework: 'react', ts: true }],
            ],
          });
      },
    },
  };
  return merge(
    base,
    process.env.NODE_ENV === 'development' ? devConfig : prodConfig,
  );
});

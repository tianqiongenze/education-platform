import type { UserConfigExport } from '@tarojs/cli';

export default {
  mini: {},
  h5: {
    /**
     * C-12: 海外 H5 不得加载国内 CDN 资源
     * 生产环境 publicPath 使用同域路径，禁止外部 CDN
     */
    publicPath: '/',
    output: {
      filename: 'js/[name].[hash:8].js',
      chunkFilename: 'js/[name].[chunkhash:8].js',
    },
  },
} satisfies UserConfigExport;

import type { UserConfigExport } from '@tarojs/cli';

export default {
  mini: {},
  h5: {
    publicPath: '/',
    output: {
      filename: 'js/[name].[hash:8].js',
      chunkFilename: 'js/[name].[chunkhash:8].js',
    },
  },
} satisfies UserConfigExport;

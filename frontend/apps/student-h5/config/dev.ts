import type { UserConfigExport } from '@tarojs/cli';

export default {
  logger: {
    quiet: false,
    stats: true,
  },
  mini: {},
  h5: {
    devServer: {
      port: 3001,
    },
  },
} satisfies UserConfigExport;

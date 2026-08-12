import { createClient, createTaroFetcher } from '@ep/api-client';
import { STORAGE_KEYS } from '@ep/shared';
import Taro from '@tarojs/taro';

const taroFetcher = createTaroFetcher(
  (opts: Record<string, unknown>) =>
    Taro.request(
      opts as unknown as Parameters<typeof Taro.request>[0],
    ) as Promise<{
      statusCode: number;
      data: unknown;
      header?: Record<string, string>;
    }>,
);

const taroTokenStorage = {
  getAccessToken: (): string | null => {
    try {
      return Taro.getStorageSync(STORAGE_KEYS.ACCESS_TOKEN) || null;
    } catch {
      return null;
    }
  },
  getRefreshToken: (): string | null => {
    try {
      return Taro.getStorageSync(STORAGE_KEYS.REFRESH_TOKEN) || null;
    } catch {
      return null;
    }
  },
  setAccessToken: (token: string) => {
    Taro.setStorageSync(STORAGE_KEYS.ACCESS_TOKEN, token);
  },
  setRefreshToken: (token: string) => {
    Taro.setStorageSync(STORAGE_KEYS.REFRESH_TOKEN, token);
  },
  clear: () => {
    Taro.removeStorageSync(STORAGE_KEYS.ACCESS_TOKEN);
    Taro.removeStorageSync(STORAGE_KEYS.REFRESH_TOKEN);
  },
};

export const apiClient = createClient({
  fetcher: taroFetcher,
  tokenStorage: taroTokenStorage,
  apiBaseUrl: '/api',
});

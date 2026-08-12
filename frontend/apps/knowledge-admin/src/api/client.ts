import { createClient, createBrowserFetcher, type ApiClient, type TokenStorage } from '@ep/api-client';
import { STORAGE_KEYS } from '@ep/shared';

const tokenStorage: TokenStorage = {
  getAccessToken: () => localStorage.getItem(STORAGE_KEYS.ACCESS_TOKEN),
  setAccessToken: (token) => localStorage.setItem(STORAGE_KEYS.ACCESS_TOKEN, token),
  getRefreshToken: () => localStorage.getItem(STORAGE_KEYS.REFRESH_TOKEN),
  setRefreshToken: (token) => localStorage.setItem(STORAGE_KEYS.REFRESH_TOKEN, token),
  clear: () => {
    localStorage.removeItem(STORAGE_KEYS.ACCESS_TOKEN);
    localStorage.removeItem(STORAGE_KEYS.REFRESH_TOKEN);
  },
};

export const apiClient: ApiClient = createClient({
  apiBaseUrl: import.meta.env.VITE_API_BASE_URL || '/api',
  fetcher: createBrowserFetcher(),
  tokenStorage,
});

export type { ApiClient };

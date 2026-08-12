import { createClient, createTaroFetcher } from '@ep/api-client';
import { STORAGE_KEYS } from '@ep/shared';
import Taro from '@tarojs/taro';
import type {
  SearchResult,
  RagChatResponse,
  CommunityPost,
  LearningPath,
  UserProfile,
} from '../types';

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
  getAccessToken: (): string | null =>
    Taro.getStorageSync(STORAGE_KEYS.ACCESS_TOKEN) || null,
  getRefreshToken: (): string | null =>
    Taro.getStorageSync(STORAGE_KEYS.REFRESH_TOKEN) || null,
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

const apiClient = createClient({
  fetcher: taroFetcher,
  tokenStorage: taroTokenStorage,
  apiBaseUrl: '/api',
});

const BASE = '/api/v1/knowledge';

export async function search(query: string): Promise<SearchResult> {
  return apiClient.post<SearchResult>(`${BASE}/search`, { query });
}

export async function ragChat(
  query: string,
  sessionId?: string,
): Promise<RagChatResponse> {
  return apiClient.post<RagChatResponse>(`${BASE}/rag`, { query, sessionId });
}

export async function listRecommendations(): Promise<{ items: LearningPath[] }> {
  return apiClient.get<{ items: LearningPath[] }>(`${BASE}/recommendations`);
}

export async function listCommunityPosts(): Promise<{ items: CommunityPost[] }> {
  return apiClient.get<{ items: CommunityPost[] }>(`${BASE}/community/posts`);
}

export async function getProfile(): Promise<UserProfile> {
  return apiClient.get<UserProfile>(`${BASE}/profile`);
}

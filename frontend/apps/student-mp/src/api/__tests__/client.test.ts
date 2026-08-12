import { vi, describe, it, expect, beforeEach } from 'vitest';

const taroMock = {
  getStorageSync: vi.fn(() => null),
  setStorageSync: vi.fn(),
  removeStorageSync: vi.fn(),
  request: vi.fn(),
};

vi.mock('@tarojs/taro', () => ({
  default: taroMock,
  ...taroMock,
}));

vi.mock('@ep/shared', () => ({
  STORAGE_KEYS: {
    ACCESS_TOKEN: 'ep_access_token',
    REFRESH_TOKEN: 'ep_refresh_token',
    CELL_ID: 'ep_cell_id',
  },
}));

// createClient/createTaroFetcher come from @ep/api-client which we don't need to
// fully exercise here; import the tokenStorage indirectly by re-importing client.
// We mock the api-client factory so the test focuses on tokenStorage behavior.
vi.mock('@ep/api-client', () => ({
  createClient: vi.fn((config) => ({ __config: config })),
  createTaroFetcher: vi.fn(() => vi.fn()),
}));

describe('api client tokenStorage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.resetModules();
  });

  it('reads access token via Taro.getStorageSync', async () => {
    taroMock.getStorageSync.mockImplementation(((key: string) =>
      key === 'ep_access_token' ? 'token-abc' : null) as typeof taroMock.getStorageSync);

    const { apiClient } = await import('../client');
    const config = (apiClient as unknown as { __config: { tokenStorage: { getAccessToken: () => string | null } } }).__config;
    expect(config.tokenStorage.getAccessToken()).toBe('token-abc');
    expect(taroMock.getStorageSync).toHaveBeenCalledWith('ep_access_token');
  });

  it('writes access token via Taro.setStorageSync', async () => {
    const { apiClient } = await import('../client');
    const config = (apiClient as unknown as { __config: { tokenStorage: { setAccessToken: (t: string) => void } } }).__config;
    config.tokenStorage.setAccessToken('new-token');
    expect(taroMock.setStorageSync).toHaveBeenCalledWith('ep_access_token', 'new-token');
  });

  it('clears both tokens', async () => {
    const { apiClient } = await import('../client');
    const config = (apiClient as unknown as { __config: { tokenStorage: { clear: () => void } } }).__config;
    config.tokenStorage.clear();
    expect(taroMock.removeStorageSync).toHaveBeenCalledWith('ep_access_token');
    expect(taroMock.removeStorageSync).toHaveBeenCalledWith('ep_refresh_token');
  });

  it('returns null when storage throws', async () => {
    taroMock.getStorageSync.mockImplementation(() => {
      throw new Error('storage error');
    });
    const { apiClient } = await import('../client');
    const config = (apiClient as unknown as { __config: { tokenStorage: { getAccessToken: () => string | null; getRefreshToken: () => string | null } } }).__config;
    expect(config.tokenStorage.getAccessToken()).toBeNull();
    expect(config.tokenStorage.getRefreshToken()).toBeNull();
  });
});

import { vi, describe, it, expect, beforeEach } from 'vitest';

const { taroMock, apiClientMock } = vi.hoisted(() => ({
  taroMock: {
    login: vi.fn(),
    setStorageSync: vi.fn(),
    getStorageSync: vi.fn(() => null),
    removeStorageSync: vi.fn(),
    showToast: vi.fn(),
    getUserProfile: vi.fn(),
    request: vi.fn(),
    requirePrivacyAuthorize: vi.fn(),
  },
  apiClientMock: {
    auth: {
      wxLogin: vi.fn(),
    },
  },
}));

vi.mock('@tarojs/taro', () => ({
  default: taroMock,
  ...taroMock,
}));

vi.mock('@ep/shared', () => ({
  STORAGE_KEYS: {
    ACCESS_TOKEN: 'ep_access_token',
    REFRESH_TOKEN: 'ep_refresh_token',
  },
  API_PATHS: {
    AUTH: { WX_LOGIN: '/api/auth/wx-login' },
  },
}));

vi.mock('../../api/client', () => ({
  apiClient: apiClientMock,
}));

import { wxLogin } from '../wx-login';

describe('wxLogin', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('stores tokens and returns true on success', async () => {
    taroMock.login.mockResolvedValue({ code: 'wx-code-123' });
    apiClientMock.auth.wxLogin.mockResolvedValue({
      accessToken: 'at',
      refreshToken: 'rt',
    });

    const result = await wxLogin();

    expect(taroMock.login).toHaveBeenCalled();
    expect(apiClientMock.auth.wxLogin).toHaveBeenCalledWith('wx-code-123');
    expect(taroMock.setStorageSync).toHaveBeenCalledWith('ep_access_token', 'at');
    expect(taroMock.setStorageSync).toHaveBeenCalledWith('ep_refresh_token', 'rt');
    expect(result).toBe(true);
  });

  it('returns false when login code is missing', async () => {
    taroMock.login.mockResolvedValue({ code: '' });

    const result = await wxLogin();

    expect(result).toBe(false);
    expect(taroMock.showToast).toHaveBeenCalledWith(
      expect.objectContaining({ title: '微信登录失败' }),
    );
  });

  it('returns false and shows toast on API error', async () => {
    taroMock.login.mockResolvedValue({ code: 'good-code' });
    apiClientMock.auth.wxLogin.mockRejectedValue(new Error('network error'));

    const result = await wxLogin();

    expect(result).toBe(false);
    expect(taroMock.showToast).toHaveBeenCalledWith(
      expect.objectContaining({ title: '登录失败，请重试' }),
    );
    expect(taroMock.setStorageSync).not.toHaveBeenCalled();
  });
});

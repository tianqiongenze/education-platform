import Taro from '@tarojs/taro';
import { apiClient } from '../api/client';
import { STORAGE_KEYS } from '@ep/shared';

/**
 * WeChat mini-program login flow:
 * 1. wx.login() → get temporary code
 * 2. POST /api/auth/wx-login { code } → backend exchanges for openid/session_key, returns JWT
 * 3. Store tokens in local storage
 */
export async function wxLogin(): Promise<boolean> {
  try {
    const { code } = await Taro.login();
    if (!code) {
      Taro.showToast({ title: '微信登录失败', icon: 'error' });
      return false;
    }

    const result = await apiClient.auth.wxLogin(code);
    Taro.setStorageSync(STORAGE_KEYS.ACCESS_TOKEN, result.accessToken);
    Taro.setStorageSync(STORAGE_KEYS.REFRESH_TOKEN, result.refreshToken);
    return true;
  } catch {
    Taro.showToast({ title: '登录失败，请重试', icon: 'error' });
    return false;
  }
}

/**
 * Get WeChat user profile (requires user tap button).
 * Uses wx.getUserProfile for MP base library >= 2.10.4.
 */
export async function wxGetUserProfile(): Promise<{
  nickname: string;
  avatar: string;
} | null> {
  try {
    const res = await Taro.getUserProfile({
      desc: '用于完善个人资料',
    });
    return {
      nickname: res.userInfo.nickName,
      avatar: res.userInfo.avatarUrl,
    };
  } catch {
    return null;
  }
}

/**
 * Check privacy agreement (C-11 compliance).
 * For base library >= 2.32.3, wx.requirePrivacyAuthorize must be called
 * before accessing any privacy-relevant API.
 */
export async function ensurePrivacyAuthorized(): Promise<boolean> {
  try {
    if (typeof Taro.requirePrivacyAuthorize === 'function') {
      await (Taro as unknown as { requirePrivacyAuthorize: () => Promise<void> }).requirePrivacyAuthorize();
    }
    return true;
  } catch {
    return false;
  }
}

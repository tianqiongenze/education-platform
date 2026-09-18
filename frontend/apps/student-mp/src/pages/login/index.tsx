import { useState } from 'react';
import { View, Text, Input, Button } from '@tarojs/components';
import Taro from '@tarojs/taro';
import { wxLogin } from '../../utils/wx-login';
import { useLogin } from '@ep/query';
import { apiClient } from '../../api/client';
import { STORAGE_KEYS } from '@ep/shared';
import './index.scss';

export default function Login() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const loginMutation = useLogin(apiClient);

  const handleWxLogin = async () => {
    if (loading) return;
    setLoading(true);
    try {
      await wxLogin();
      Taro.reLaunch({ url: '/pages/index/index' });
    } catch (err) {
      Taro.showToast({ title: (err as Error).message, icon: 'none' });
    } finally {
      setLoading(false);
    }
  };

  const handlePasswordLogin = async () => {
    if (loading) return;
    if (!username || !password) {
      Taro.showToast({ title: '请输入账号和密码', icon: 'none' });
      return;
    }
    setLoading(true);
    try {
      const res = await loginMutation.mutateAsync({ username, password });
      Taro.setStorageSync(STORAGE_KEYS.ACCESS_TOKEN, res.accessToken);
      Taro.setStorageSync(STORAGE_KEYS.REFRESH_TOKEN, res.refreshToken);
      Taro.reLaunch({ url: '/pages/index/index' });
    } catch (err) {
      Taro.showToast({ title: (err as Error).message, icon: 'none' });
    } finally {
      setLoading(false);
    }
  };

  return (
    <View className="login ep-page">
      <View className="login__brand">
        <Text className="login__title">Education Platform</Text>
        <Text className="login__subtitle">学生端</Text>
      </View>

      <View className="login__wx">
        <Button
          className="login__wx-btn"
          loading={loading}
          disabled={loading}
          onClick={handleWxLogin}
        >
          微信一键登录
        </Button>
      </View>

      <View className="login__divider">
        <View className="login__divider-line" />
        <Text className="login__divider-text">或使用账号登录</Text>
        <View className="login__divider-line" />
      </View>

      <View className="login__form">
        <Input
          className="login__input"
          placeholder="请输入账号"
          value={username}
          onInput={(e) => setUsername(e.detail.value)}
        />
        <Input
          className="login__input"
          placeholder="请输入密码"
          password
          value={password}
          onInput={(e) => setPassword(e.detail.value)}
        />
        <Button
          className="login__submit"
          loading={loading}
          disabled={loading}
          onClick={handlePasswordLogin}
        >
          登录
        </Button>
      </View>
    </View>
  );
}

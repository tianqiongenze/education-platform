import { View, Text, Input, Button, Checkbox } from '@tarojs/components';
import { useState } from 'react';
import Taro from '@tarojs/taro';
import { useLogin } from '@ep/query';
import { apiClient } from '../../api/client';
import { STORAGE_KEYS } from '@ep/shared';
import './index.scss';

export default function LoginPage() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [agree, setAgree] = useState(false);
  const loginMutation = useLogin(apiClient);

  const handleLogin = async () => {
    if (!username.trim()) {
      Taro.showToast({ title: '请输入用户名', icon: 'none' });
      return;
    }
    if (password.length < 8) {
      Taro.showToast({ title: '密码至少8位', icon: 'none' });
      return;
    }
    if (!agree) {
      Taro.showToast({ title: '请同意用户协议', icon: 'none' });
      return;
    }

    try {
      const result = await loginMutation.mutateAsync({ username, password });
      Taro.setStorageSync(STORAGE_KEYS.ACCESS_TOKEN, result.accessToken);
      Taro.setStorageSync(STORAGE_KEYS.REFRESH_TOKEN, result.refreshToken);
      Taro.showToast({ title: '登录成功', icon: 'success' });
      setTimeout(() => {
        Taro.switchTab({ url: '/pages/index/index' });
      }, 800);
    } catch {
      Taro.showToast({ title: '登录失败，请检查凭据', icon: 'none' });
    }
  };

  return (
    <View className="login-page">
      <View className="login-header">
        <Text className="login-title">欢迎回来</Text>
        <Text className="login-subtitle">登录继续您的学习之旅</Text>
      </View>

      <View className="login-form">
        <View className="form-item">
          <Text className="form-label">用户名</Text>
          <Input
            className="form-input"
            placeholder="请输入用户名"
            value={username}
            onInput={(e) => setUsername(e.detail.value)}
          />
        </View>
        <View className="form-item">
          <Text className="form-label">密码</Text>
          <Input
            className="form-input"
            password
            placeholder="请输入密码"
            value={password}
            onInput={(e) => setPassword(e.detail.value)}
          />
        </View>
        <View className="form-agreement">
          <Checkbox
            value="agree"
            checked={agree}
            onChange={(e) => setAgree(e.detail.value.includes('agree'))}
          >
            <Text className="agreement-text">我已阅读并同意</Text>
          </Checkbox>
          <Text className="agreement-link">《用户协议》《隐私政策》</Text>
        </View>

        <Button
          type="primary"
          className="login-btn"
          loading={loginMutation.isPending}
          onClick={handleLogin}
        >
          登录
        </Button>

        <View className="login-footer">
          <Text className="footer-text">还没有账号？</Text>
          <Text className="footer-link">立即注册</Text>
        </View>
      </View>
    </View>
  );
}

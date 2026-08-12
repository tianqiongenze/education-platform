import { View, Text, ScrollView, Button } from '@tarojs/components';
import Taro from '@tarojs/taro';
import { useUserProfile, useEnrollments } from '@ep/query';
import { apiClient } from '../../api/client';
import { STORAGE_KEYS } from '@ep/shared';
import './index.scss';

export default function ProfilePage() {
  const { data: profile, isLoading } = useUserProfile(apiClient);
  const { data: enrollments } = useEnrollments(apiClient);

  const handleLogout = () => {
    Taro.removeStorageSync(STORAGE_KEYS.ACCESS_TOKEN);
    Taro.removeStorageSync(STORAGE_KEYS.REFRESH_TOKEN);
    Taro.reLaunch({ url: '/pages/login/index' });
  };

  const handleLogin = () => {
    Taro.navigateTo({ url: '/pages/login/index' });
  };

  if (isLoading) {
    return (
      <View className="profile-page">
        <Text className="loading-text">加载中...</Text>
      </View>
    );
  }

  const isLoggedIn = !!profile;

  const profileData = profile ?? { nickname: '', username: '', email: '' };

  return (
    <ScrollView scrollY className="profile-page">
      <View className="profile-header">
        <View className="avatar">
          <Text className="avatar-text">
            {isLoggedIn ? (profileData.nickname || profileData.username)[0]?.toUpperCase() : '?'}
          </Text>
        </View>
        <Text className="profile-name">
          {isLoggedIn ? profileData.nickname || profileData.username : '未登录'}
        </Text>
        <Text className="profile-email">
          {isLoggedIn ? profileData.email ?? '' : '请登录以查看个人信息'}
        </Text>
      </View>

      {isLoggedIn && (
        <View className="profile-section">
          <View className="stat-row">
            <View className="stat-item">
              <Text className="stat-value">{enrollments?.length ?? 0}</Text>
              <Text className="stat-label">已报名课程</Text>
            </View>
            <View className="stat-item">
              <Text className="stat-value">0</Text>
              <Text className="stat-label">已完成课程</Text>
            </View>
            <View className="stat-item">
              <Text className="stat-value">0</Text>
              <Text className="stat-label">学习时长(h)</Text>
            </View>
          </View>
        </View>
      )}

      <View className="profile-menu">
        <View className="menu-item">
          <Text className="menu-label">我的课程</Text>
          <Text className="menu-arrow">›</Text>
        </View>
        <View className="menu-item">
          <Text className="menu-label">我的作业</Text>
          <Text className="menu-arrow">›</Text>
        </View>
        <View className="menu-item">
          <Text className="menu-label">学习记录</Text>
          <Text className="menu-arrow">›</Text>
        </View>
        <View className="menu-item">
          <Text className="menu-label">设置</Text>
          <Text className="menu-arrow">›</Text>
        </View>
        <View className="menu-item">
          <Text className="menu-label">帮助与反馈</Text>
          <Text className="menu-arrow">›</Text>
        </View>
        <View className="menu-item">
          <Text className="menu-label">关于我们</Text>
          <Text className="menu-arrow">›</Text>
        </View>
      </View>

      <View className="profile-actions">
        {isLoggedIn ? (
          <Button className="logout-btn" onClick={handleLogout}>
            退出登录
          </Button>
        ) : (
          <Button type="primary" className="login-btn" onClick={handleLogin}>
            去登录
          </Button>
        )}
      </View>
    </ScrollView>
  );
}

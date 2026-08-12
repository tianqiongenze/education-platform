import { View, Text, ScrollView } from '@tarojs/components';
import { useEffect, useState } from 'react';
import { getProfile } from '../../api';
import type { UserProfile } from '../../types';
import './index.scss';

export default function ProfilePage() {
  const [profile, setProfile] = useState<UserProfile | null>(null);

  useEffect(() => {
    getProfile().then(setProfile).catch(() => setProfile(null));
  }, []);

  return (
    <ScrollView scrollY className="profile-page">
      <View className="profile-header">
        <View className="avatar">
          <Text className="avatar-text">
            {profile?.nickname?.charAt(0) ?? 'U'}
          </Text>
        </View>
        <Text className="nickname">{profile?.nickname ?? '未登录'}</Text>
      </View>

      <View className="stats">
        <View className="stat-item">
          <Text className="stat-value">{profile?.learningMinutes ?? 0}</Text>
          <Text className="stat-label">学习(分钟)</Text>
        </View>
        <View className="stat-item">
          <Text className="stat-value">{profile?.contributionScore ?? 0}</Text>
          <Text className="stat-label">贡献度</Text>
        </View>
        <View className="stat-item">
          <Text className="stat-value">{profile?.favorites ?? 0}</Text>
          <Text className="stat-label">收藏</Text>
        </View>
      </View>

      <View className="menu">
        <View className="menu-item">
          <Text>我的收藏</Text>
        </View>
        <View className="menu-item">
          <Text>学习记录</Text>
        </View>
        <View className="menu-item">
          <Text>我的提问</Text>
        </View>
      </View>
    </ScrollView>
  );
}

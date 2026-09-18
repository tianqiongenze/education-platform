import { View, Text, Image } from '@tarojs/components';
import Taro from '@tarojs/taro';
import { useUserProfile } from '@ep/query';
import { Loading, Empty } from '@ep/ui-kit';
import { apiClient } from '../../api/client';
import { STORAGE_KEYS } from '@ep/shared';
import './index.scss';

const ROLE_LABELS: Record<string, string> = {
  student: '学生',
  teacher: '教师',
  admin: '管理员',
  super_admin: '超级管理员',
};

const MENU = [
  { key: 'courses', label: '我的课程', path: '/pages/course/index' },
  { key: 'assignments', label: '我的作业', path: '/subpackages/assignment/pages/detail/index' },
  { key: 'exams', label: '我的考试', path: '/subpackages/quiz/pages/exam/index' },
  { key: 'settings', label: '设置', path: '' },
];

export default function Profile() {
  const { data: profile, isLoading } = useUserProfile(apiClient);

  const handleLogout = () => {
    Taro.removeStorageSync(STORAGE_KEYS.ACCESS_TOKEN);
    Taro.removeStorageSync(STORAGE_KEYS.REFRESH_TOKEN);
    Taro.reLaunch({ url: '/pages/login/index' });
  };

  const handleMenu = (path: string) => {
    if (!path) {
      Taro.showToast({ title: '敬请期待', icon: 'none' });
      return;
    }
    Taro.navigateTo({ url: path });
  };

  if (isLoading) {
    return (
      <View className="profile ep-page">
        <Loading />
      </View>
    );
  }

  if (!profile) {
    return (
      <View className="profile ep-page">
        <Empty description="请先登录" />
        <View className="profile__login-btn" onClick={() => Taro.reLaunch({ url: '/pages/login/index' })}>
          <Text>去登录</Text>
        </View>
      </View>
    );
  }

  return (
    <View className="profile ep-page">
      <View className="profile__header">
        <Image className="profile__avatar" src={profile.avatar || 'https://placehold.co/120/1890ff/ffffff?text=U'} />
        <View className="profile__user">
          <Text className="profile__nickname">{profile.nickname || profile.username}</Text>
          <Text className="profile__role">{ROLE_LABELS[profile.role] ?? profile.role}</Text>
        </View>
      </View>

      <View className="profile__stats">
        <View className="profile__stat">
          <Text className="profile__stat-num">{profile.enrolledCourseCount}</Text>
          <Text className="profile__stat-label">报名课程</Text>
        </View>
        <View className="profile__stat">
          <Text className="profile__stat-num">{profile.completedLessonCount}</Text>
          <Text className="profile__stat-label">完成课时</Text>
        </View>
        <View className="profile__stat">
          <Text className="profile__stat-num">{profile.certificateCount}</Text>
          <Text className="profile__stat-label">证书</Text>
        </View>
      </View>

      <View className="profile__menu">
        {MENU.map((m) => (
          <View key={m.key} className="profile__menu-item" onClick={() => handleMenu(m.path)}>
            <Text className="profile__menu-label">{m.label}</Text>
            <Text className="profile__menu-arrow">›</Text>
          </View>
        ))}
        <View className="profile__menu-item profile__menu-item--danger" onClick={handleLogout}>
          <Text className="profile__menu-label">退出登录</Text>
        </View>
      </View>
    </View>
  );
}

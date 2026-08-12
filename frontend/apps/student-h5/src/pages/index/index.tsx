import { View, Text, ScrollView } from '@tarojs/components';
import Taro from '@tarojs/taro';
import { useCourseList } from '@ep/query';
import { apiClient } from '../../api/client';
import './index.scss';

export default function IndexPage() {
  const { data, isLoading, isError } = useCourseList(apiClient, {
    page: 1,
    pageSize: 10,
  });

  const handleCourseTap = (courseId: string) => {
    Taro.navigateTo({ url: `/pages/course-detail/index?id=${courseId}` });
  };

  return (
    <ScrollView scrollY className="index-page">
      <View className="hero">
        <Text className="hero-title">在线学习平台</Text>
        <Text className="hero-subtitle">优质课程，随时随地学习</Text>
      </View>

      <View className="section">
        <Text className="section-title">推荐课程</Text>
        {isLoading && <Text className="loading-text">加载中...</Text>}
        {isError && <Text className="error-text">加载失败，请稍后重试</Text>}
        {data?.items.map((course) => (
          <View
            key={course.id}
            className="course-card"
            onClick={() => handleCourseTap(course.id)}
          >
            <View className="course-card__body">
              <Text className="course-card__title">{course.title}</Text>
              <Text className="course-card__teacher">{course.teacherName}</Text>
              <View className="course-card__meta">
                <Text className="course-card__rating">★ {course.rating.toFixed(1)}</Text>
                <Text className="course-card__count">{course.enrollmentCount} 人报名</Text>
              </View>
            </View>
          </View>
        ))}
        {data && data.items.length === 0 && (
          <Text className="empty-text">暂无课程</Text>
        )}
      </View>
    </ScrollView>
  );
}

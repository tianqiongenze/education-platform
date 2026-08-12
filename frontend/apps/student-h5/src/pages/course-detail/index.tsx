import { View, Text, ScrollView, Button } from '@tarojs/components';
import Taro, { useRouter } from '@tarojs/taro';
import { useCourseDetail, useEnrollCourse } from '@ep/query';
import { apiClient } from '../../api/client';
import './index.scss';

export default function CourseDetailPage() {
  const router = useRouter();
  const courseId = router.params.id ?? '';
  const { data: course, isLoading, isError } = useCourseDetail(apiClient, courseId);
  const enrollMutation = useEnrollCourse(apiClient);

  const handleEnroll = async () => {
    try {
      await enrollMutation.mutateAsync(courseId);
      Taro.showToast({ title: '报名成功', icon: 'success' });
    } catch {
      Taro.showToast({ title: '报名失败', icon: 'error' });
    }
  };

  const handleStartLearning = () => {
    Taro.navigateTo({ url: `/pages/learning/index?id=${courseId}` });
  };

  if (isLoading) {
    return (
      <View className="detail-page">
        <Text className="loading-text">加载中...</Text>
      </View>
    );
  }

  if (isError || !course) {
    return (
      <View className="detail-page">
        <Text className="error-text">课程加载失败</Text>
      </View>
    );
  }

  return (
    <ScrollView scrollY className="detail-page">
      <View className="detail-header">
        <Text className="detail-title">{course.title}</Text>
        <Text className="detail-teacher">讲师: {course.teacherName}</Text>
        <View className="detail-meta">
          <Text className="detail-rating">★ {course.rating.toFixed(1)}</Text>
          <Text className="detail-count">{course.enrollmentCount} 人报名</Text>
        </View>
      </View>

      <View className="detail-section">
        <Text className="section-title">课程简介</Text>
        <Text className="section-body">{course.description}</Text>
      </View>

      {course.chapters && course.chapters.length > 0 && (
        <View className="detail-section">
          <Text className="section-title">课程章节</Text>
          {course.chapters.map((ch, idx) => (
            <View key={ch.id} className="chapter-item">
              <Text className="chapter-title">
                第{idx + 1}章 {ch.title}
              </Text>
              <Text className="chapter-lessons">{ch.lessons?.length ?? 0} 课时</Text>
            </View>
          ))}
        </View>
      )}

      <View className="detail-actions">
        <Button
          type="primary"
          loading={enrollMutation.isPending}
          onClick={handleEnroll}
          className="action-btn"
        >
          立即报名
        </Button>
        <Button onClick={handleStartLearning} className="action-btn">
          开始学习
        </Button>
      </View>
    </ScrollView>
  );
}

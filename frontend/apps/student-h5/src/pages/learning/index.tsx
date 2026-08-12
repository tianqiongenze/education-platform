import { View, Text, ScrollView, Video } from '@tarojs/components';
import { useRouter } from '@tarojs/taro';
import { useState } from 'react';
import { useCourseDetail } from '@ep/query';
import { apiClient } from '../../api/client';
import './index.scss';

interface LessonItem {
  id: string;
  title: string;
  type: string;
  videoUrl?: string;
  duration?: number;
}

export default function LearningPage() {
  const router = useRouter();
  const courseId = router.params.id ?? '';
  const { data: course, isLoading } = useCourseDetail(apiClient, courseId);
  const [currentLesson, setCurrentLesson] = useState<LessonItem | null>(null);

  const allLessons: LessonItem[] =
    course?.chapters?.flatMap((ch) =>
      (ch.lessons ?? []).map((l) => ({
        id: l.id,
        title: l.title,
        type: l.type,
        videoUrl: l.resourceUrl ?? undefined,
        duration: l.duration,
      })) as LessonItem[],
    ) ?? [];

  const activeLesson = currentLesson ?? allLessons[0] ?? null;

  if (isLoading) {
    return (
      <View className="learning-page">
        <Text className="loading-text">加载中...</Text>
      </View>
    );
  }

  return (
    <View className="learning-page">
      <View className="video-area">
        {activeLesson?.videoUrl ? (
          <Video
            src={activeLesson.videoUrl}
            className="video-player"
            controls
            autoplay
          />
        ) : (
          <View className="video-placeholder">
            <Text className="placeholder-text">暂无视频</Text>
          </View>
        )}
        <View className="video-title">
          <Text className="current-lesson-title">{activeLesson?.title ?? '请选择课时'}</Text>
        </View>
      </View>

      <ScrollView scrollY className="lesson-list">
        <Text className="list-title">课程目录</Text>
        {allLessons.map((lesson, idx) => (
          <View
            key={lesson.id}
            className={`lesson-item ${activeLesson?.id === lesson.id ? 'lesson-item--active' : ''}`}
            onClick={() => setCurrentLesson(lesson)}
          >
            <Text className="lesson-index">{idx + 1}</Text>
            <View className="lesson-info">
              <Text className="lesson-title">{lesson.title}</Text>
              <Text className="lesson-type">{lesson.type}</Text>
            </View>
            {lesson.duration && (
              <Text className="lesson-duration">{Math.ceil(lesson.duration / 60)}分钟</Text>
            )}
          </View>
        ))}
        {allLessons.length === 0 && (
          <Text className="empty-text">暂无课时内容</Text>
        )}
      </ScrollView>
    </View>
  );
}

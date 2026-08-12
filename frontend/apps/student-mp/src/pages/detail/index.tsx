import { View, Text, Button } from '@tarojs/components';
import { useRouter } from '@tarojs/taro';
import { useCourseDetail, useEnrollCourse } from '@ep/query';
import { apiClient } from '../../api/client';
import './index.scss';

export default function DetailPage() {
  const router = useRouter();
  const id = router.params.id ?? '';
  const { data: course, isLoading } = useCourseDetail(apiClient, id);
  const enroll = useEnrollCourse(apiClient);

  if (isLoading) return <View className="page"><Text className="hint">加载中...</Text></View>;
  if (!course) return <View className="page"><Text className="hint">加载失败</Text></View>;

  return (
    <View className="page">
      <View className="header">
        <Text className="title">{course.title}</Text>
        <Text className="teacher">讲师: {course.teacherName}</Text>
        <View className="meta">
          <Text className="rating">★ {course.rating.toFixed(1)}</Text>
          <Text className="count">{course.enrollmentCount}人报名</Text>
        </View>
      </View>
      <View className="section">
        <Text className="section-title">课程简介</Text>
        <Text className="section-body">{course.description}</Text>
      </View>
      {course.chapters && course.chapters.length > 0 && (
        <View className="section">
          <Text className="section-title">章节</Text>
          {course.chapters.map((ch,i)=>(
            <View key={ch.id} className="chapter">
              <Text className="chapter-title">第{i+1}章 {ch.title}</Text>
              <Text className="chapter-count">{ch.lessons?.length ?? 0}课时</Text>
            </View>
          ))}
        </View>
      )}
      <View className="actions">
        <Button type="primary" loading={enroll.isPending}
          onClick={async()=>{ try{ await enroll.mutateAsync(id); }catch{} }}>
          立即报名
        </Button>
      </View>
    </View>
  );
}

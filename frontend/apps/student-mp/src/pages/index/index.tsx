import { View, Text, ScrollView } from '@tarojs/components';
import Taro from '@tarojs/taro';
import { useCourseList } from '@ep/query';
import { apiClient } from '../../api/client';
import './index.scss';

export default function IndexPage() {
  const { data, isLoading } = useCourseList(apiClient, { page: 1, pageSize: 10 });

  const goDetail = (id: string) => Taro.navigateTo({ url: `/pages/detail/index?id=${id}` });

  return (
    <ScrollView scrollY className="index-page">
      <View className="banner">
        <Text className="banner-title">EP学习</Text>
        <Text className="banner-desc">优质课程，随时随地</Text>
      </View>
      <View className="section">
        <Text className="section-title">推荐课程</Text>
        {isLoading && <Text className="hint">加载中...</Text>}
        {data?.items?.map((c) => (
          <View key={c.id} className="card" onClick={() => goDetail(c.id)}>
            <Text className="card-title">{c.title}</Text>
            <Text className="card-teacher">{c.teacherName}</Text>
            <View className="card-meta">
              <Text className="card-rating">★ {c.rating.toFixed(1)}</Text>
              <Text className="card-count">{c.enrollmentCount}人</Text>
            </View>
          </View>
        ))}
        {data && (data.items?.length ?? 0) === 0 && <Text className="hint">暂无课程</Text>}
      </View>
    </ScrollView>
  );
}

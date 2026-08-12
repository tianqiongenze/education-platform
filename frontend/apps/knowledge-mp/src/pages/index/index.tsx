import { View, Text, ScrollView } from '@tarojs/components';
import Taro from '@tarojs/taro';
import { useEffect, useState } from 'react';
import { listRecommendations } from '../../api';
import type { LearningPath } from '../../types';
import './index.scss';

export default function IndexPage() {
  const [paths, setPaths] = useState<LearningPath[]>([]);

  useEffect(() => {
    listRecommendations()
      .then((res) => setPaths(res.items))
      .catch(() => setPaths([]));
  }, []);

  const goSearch = () => Taro.switchTab({ url: '/pages/search/index' });
  const goChat = () => Taro.switchTab({ url: '/pages/chat/index' });

  return (
    <ScrollView scrollY className="index-page">
      <View className="hero">
        <Text className="hero-title">知识中台</Text>
        <Text className="hero-subtitle">智能问答 · 知识搜索 · 学习路径</Text>
      </View>

      <View className="quick-entry">
        <View className="entry-item" onClick={goSearch}>
          <Text className="entry-icon">🔍</Text>
          <Text className="entry-label">知识搜索</Text>
        </View>
        <View className="entry-item" onClick={goChat}>
          <Text className="entry-icon">🤖</Text>
          <Text className="entry-label">AI 问答</Text>
        </View>
      </View>

      <View className="section">
        <Text className="section-title">推荐学习路径</Text>
        {paths.length === 0 && <Text className="empty-text">暂无推荐</Text>}
        {paths.map((p) => (
          <View key={p.id} className="path-card">
            <Text className="path-title">{p.title}</Text>
            <Text className="path-desc">{p.description}</Text>
            <Text className="path-meta">预计 {p.estimatedMinutes} 分钟</Text>
          </View>
        ))}
      </View>
    </ScrollView>
  );
}

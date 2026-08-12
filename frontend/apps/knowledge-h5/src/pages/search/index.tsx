import { View, Text, Input, ScrollView } from '@tarojs/components';
import { useState } from 'react';
import { search } from '../../api';
import type { SearchHit } from '../../types';
import './index.scss';

const MAX_HISTORY = 10;

export default function SearchPage() {
  const [keyword, setKeyword] = useState('');
  const [history, setHistory] = useState<string[]>([]);
  const [hits, setHits] = useState<SearchHit[]>([]);
  const [loading, setLoading] = useState(false);

  const handleSearch = async () => {
    const q = keyword.trim();
    if (!q) return;
    setHistory((prev) =>
      [q, ...prev.filter((h) => h !== q)].slice(0, MAX_HISTORY),
    );
    setLoading(true);
    try {
      const res = await search(q);
      setHits(res.hits);
    } catch {
      setHits([]);
    } finally {
      setLoading(false);
    }
  };

  return (
    <View className="search-page">
      <View className="search-bar">
        <Input
          className="search-input"
          placeholder="搜索知识..."
          value={keyword}
          onInput={(e) => setKeyword(e.detail.value)}
          onConfirm={handleSearch}
        />
      </View>

      {history.length > 0 && hits.length === 0 && (
        <View className="history">
          <Text className="history-title">搜索历史</Text>
          {history.map((h) => (
            <Text
              key={h}
              className="history-tag"
              onClick={() => {
                setKeyword(h);
              }}
            >
              {h}
            </Text>
          ))}
        </View>
      )}

      <ScrollView scrollY className="results">
        {loading && <Text className="loading-text">加载中...</Text>}
        {!loading && hits.length === 0 && (
          <Text className="empty-text">输入关键词开始搜索</Text>
        )}
        {hits.map((hit) => (
          <View key={hit.id} className="result-card">
            <Text className="result-title">{hit.title}</Text>
            <Text className="result-snippet">{hit.snippet}</Text>
            <Text className="result-score">相关度 {hit.score.toFixed(2)}</Text>
          </View>
        ))}
      </ScrollView>
    </View>
  );
}

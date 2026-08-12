import { View, Text, ScrollView, Input } from '@tarojs/components';
import { useState } from 'react';
import Taro from '@tarojs/taro';
import { useCourseList } from '@ep/query';
import { apiClient } from '../../api/client';
import './index.scss';

export default function CoursePage() {
  const [kw, setKw] = useState('');
  const [search, setSearch] = useState('');
  const { data, isLoading } = useCourseList(apiClient, { page:1, pageSize:20, ...(search ? { keyword: search } : {}) });
  const goDetail = (id: string) => Taro.navigateTo({ url: `/pages/detail/index?id=${id}` });

  return (
    <ScrollView scrollY className="course-page">
      <View className="search-bar">
        <Input className="search-input" placeholder="搜索课程" value={kw}
          onInput={(e)=>setKw(e.detail.value)} onConfirm={()=>setSearch(kw)} />
      </View>
      {isLoading && <Text className="hint">加载中...</Text>}
      {data?.items?.map((c)=>(
        <View key={c.id} className="card" onClick={()=>goDetail(c.id)}>
          <Text className="card-title">{c.title}</Text>
          <Text className="card-teacher">{c.teacherName}</Text>
        </View>
      ))}
      {data && (data.items?.length ?? 0)===0 && <Text className="hint">暂无课程</Text>}
    </ScrollView>
  );
}

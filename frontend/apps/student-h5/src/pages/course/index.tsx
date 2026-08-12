import { View, Text, ScrollView, Input } from '@tarojs/components';
import { useState } from 'react';
import Taro from '@tarojs/taro';
import { useCourseList } from '@ep/query';
import { apiClient } from '../../api/client';
import './index.scss';

export default function CoursePage() {
  const [keyword, setKeyword] = useState('');
  const [search, setSearch] = useState('');
  const [page] = useState(1);
  const { data, isLoading } = useCourseList(apiClient, {
    page,
    pageSize: 20,
    ...(search ? { keyword: search } : {}),
  });

  const handleSearch = () => setSearch(keyword);

  const handleCourseTap = (courseId: string) => {
    Taro.navigateTo({ url: `/pages/course-detail/index?id=${courseId}` });
  };

  return (
    <ScrollView scrollY className="course-page">
      <View className="search-bar">
        <Input
          className="search-input"
          placeholder="搜索课程"
          value={keyword}
          onInput={(e) => setKeyword(e.detail.value)}
          onConfirm={handleSearch}
        />
      </View>
      <View className="course-list">
        {isLoading && <Text className="loading-text">加载中...</Text>}
        {data?.items.map((course) => (
          <View
            key={course.id}
            className="course-item"
            onClick={() => handleCourseTap(course.id)}
          >
            <View className="course-item__info">
              <Text className="course-item__title">{course.title}</Text>
              <Text className="course-item__teacher">讲师: {course.teacherName}</Text>
              <View className="course-item__meta">
                <Text className="course-item__rating">★ {course.rating.toFixed(1)}</Text>
                <Text className="course-item__count">{course.enrollmentCount} 人</Text>
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

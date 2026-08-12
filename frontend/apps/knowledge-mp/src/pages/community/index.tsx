import { View, Text, ScrollView } from '@tarojs/components';
import { useEffect, useState } from 'react';
import { listCommunityPosts } from '../../api';
import type { CommunityPost } from '../../types';
import './index.scss';

export default function CommunityPage() {
  const [posts, setPosts] = useState<CommunityPost[]>([]);

  useEffect(() => {
    listCommunityPosts()
      .then((res) => setPosts(res.items))
      .catch(() => setPosts([]));
  }, []);

  return (
    <ScrollView scrollY className="community-page">
      {posts.length === 0 && <Text className="empty-text">暂无帖子</Text>}
      {posts.map((post) => (
        <View key={post.id} className="post-card">
          <Text className="post-title">{post.title}</Text>
          <Text className="post-content">{post.content}</Text>
          <View className="post-footer">
            <Text className="post-author">{post.authorName}</Text>
            <Text className="post-meta">👍 {post.upvotes} · 💬 {post.answers}</Text>
          </View>
        </View>
      ))}
    </ScrollView>
  );
}

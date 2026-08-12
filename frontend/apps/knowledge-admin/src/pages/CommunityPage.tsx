import { useState, useEffect } from 'react';
import { Card, Table, Button, Tag, Space, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { listCommunityPosts, reviewCommunityPost } from '../api/knowledge';
import type { CommunityPost } from '../types/knowledge';

const STATUS_MAP: Record<string, { label: string; color: string }> = {
  pending: { label: '待审核', color: 'orange' },
  approved: { label: '已通过', color: 'green' },
  rejected: { label: '已拒绝', color: 'red' },
};

export default function CommunityPage() {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<{ items: CommunityPost[]; total: number }>({
    items: [],
    total: 0,
  });

  const load = () => {
    setLoading(true);
    listCommunityPosts()
      .then(setData)
      .catch(() => setData({ items: [], total: 0 }))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
  }, []);

  const handleReview = async (postId: string, action: 'approve' | 'reject') => {
    try {
      await reviewCommunityPost({ postId, action });
      void message.success(action === 'approve' ? '已通过' : '已拒绝');
      load();
    } catch {
      void message.error('操作失败');
    }
  };

  const columns: ColumnsType<CommunityPost> = [
    { title: '标题', dataIndex: 'title', key: 'title' },
    { title: '作者', dataIndex: 'authorName', key: 'authorName', width: 120 },
    { title: '点赞', dataIndex: 'upvotes', key: 'upvotes', width: 80 },
    { title: '回答', dataIndex: 'answers', key: 'answers', width: 80 },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (s: string) => (
        <Tag color={STATUS_MAP[s]?.color ?? 'default'}>
          {STATUS_MAP[s]?.label ?? s}
        </Tag>
      ),
    },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 180 },
    {
      title: '操作',
      key: 'action',
      width: 160,
      render: (_, record) =>
        record.status === 'pending' ? (
          <Space>
            <Button type="link" onClick={() => handleReview(record.id, 'approve')}>
              通过
            </Button>
            <Button type="link" danger onClick={() => handleReview(record.id, 'reject')}>
              拒绝
            </Button>
          </Space>
        ) : (
          '-'
        ),
    },
  ];

  return (
    <Card title="问答社区">
      <Table<CommunityPost>
        rowKey="id"
        columns={columns}
        dataSource={data.items}
        loading={loading}
        pagination={{ pageSize: 10, total: data.total }}
      />
    </Card>
  );
}

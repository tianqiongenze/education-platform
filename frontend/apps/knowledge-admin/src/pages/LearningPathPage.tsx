import { useState, useEffect } from 'react';
import { Card, Table, Button, Space } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { listLearningPaths } from '../api/knowledge';
import type { LearningPath } from '../types/knowledge';

export default function LearningPathPage() {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<{ items: LearningPath[]; total: number }>({
    items: [],
    total: 0,
  });

  useEffect(() => {
    setLoading(true);
    listLearningPaths()
      .then(setData)
      .catch(() => setData({ items: [], total: 0 }))
      .finally(() => setLoading(false));
  }, []);

  const columns: ColumnsType<LearningPath> = [
    { title: '标题', dataIndex: 'title', key: 'title' },
    { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
    {
      title: '文档数',
      key: 'docCount',
      width: 100,
      render: (_, record) => record.documentIds.length,
    },
    {
      title: '预计时长(分钟)',
      dataIndex: 'estimatedMinutes',
      key: 'estimatedMinutes',
      width: 140,
    },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 180 },
  ];

  return (
    <Card
      title="学习路径管理"
      extra={
        <Space>
          <Button type="primary">新建学习路径</Button>
        </Space>
      }
    >
      <Table<LearningPath>
        rowKey="id"
        columns={columns}
        dataSource={data.items}
        loading={loading}
        pagination={{ pageSize: 10, total: data.total }}
      />
    </Card>
  );
}

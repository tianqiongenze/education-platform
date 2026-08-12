import { useState, useEffect } from 'react';
import { Table, Input, Button, Space, Tag, Card } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { SearchOutlined } from '@ant-design/icons';
import { listKnowledgeBases } from '../api/knowledge';
import type { KnowledgeBase, KnowledgeBaseCategory } from '../types/knowledge';

const CATEGORY_LABEL: Record<KnowledgeBaseCategory, string> = {
  technical: '技术',
  product: '产品',
  service: '客服',
};

const CATEGORY_COLOR: Record<KnowledgeBaseCategory, string> = {
  technical: 'blue',
  product: 'green',
  service: 'orange',
};

export default function KnowledgeBasePage() {
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<{ items: KnowledgeBase[]; total: number }>({
    items: [],
    total: 0,
  });

  useEffect(() => {
    setLoading(true);
    listKnowledgeBases({ keyword, page, pageSize })
      .then(setData)
      .catch(() => setData({ items: [], total: 0 }))
      .finally(() => setLoading(false));
  }, [keyword, page, pageSize]);

  const columns: ColumnsType<KnowledgeBase> = [
    { title: '名称', dataIndex: 'name', key: 'name' },
    {
      title: '分类',
      dataIndex: 'category',
      key: 'category',
      render: (cat: KnowledgeBaseCategory) => (
        <Tag color={CATEGORY_COLOR[cat]}>{CATEGORY_LABEL[cat]}</Tag>
      ),
    },
    { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
    { title: '文档数', dataIndex: 'documentCount', key: 'documentCount', width: 100 },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 180 },
  ];

  return (
    <Card title="知识库管理">
      <Space style={{ marginBottom: 16 }}>
        <Input
          placeholder="搜索知识库"
          prefix={<SearchOutlined />}
          value={keyword}
          onChange={(e) => {
            setKeyword(e.target.value);
            setPage(1);
          }}
          style={{ width: 240 }}
        />
        <Button type="primary">新建知识库</Button>
      </Space>
      <Table<KnowledgeBase>
        rowKey="id"
        columns={columns}
        dataSource={data.items}
        loading={loading}
        pagination={{
          current: page,
          pageSize,
          total: data.total,
          onChange: (p, ps) => {
            setPage(p);
            setPageSize(ps);
          },
        }}
      />
    </Card>
  );
}

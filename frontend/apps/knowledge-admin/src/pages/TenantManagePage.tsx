import { useState, useEffect } from 'react';
import { Card, Table, InputNumber, Button, Tag, message, Space } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { listTenants, updateQuota } from '../api/knowledge';
import type { Tenant, TenantQuota } from '../types/knowledge';

export default function TenantManagePage() {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<{ items: Tenant[]; total: number }>({
    items: [],
    total: 0,
  });
  const [editing, setEditing] = useState<Record<string, TenantQuota>>({});

  const load = () => {
    setLoading(true);
    listTenants()
      .then(setData)
      .catch(() => setData({ items: [], total: 0 }))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
  }, []);

  const handleSave = async (tenant: Tenant) => {
    const quota = editing[tenant.id];
    if (!quota) return;
    try {
      await updateQuota(tenant.id, quota);
      void message.success('配额已更新');
      load();
    } catch {
      void message.error('更新失败');
    }
  };

  const columns: ColumnsType<Tenant> = [
    { title: '名称', dataIndex: 'name', key: 'name' },
    { title: '编码', dataIndex: 'code', key: 'code', width: 120 },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (s: string) => (
        <Tag color={s === 'active' ? 'green' : 'default'}>
          {s === 'active' ? '活跃' : '已停用'}
        </Tag>
      ),
    },
    {
      title: '文档配额',
      key: 'documents',
      width: 140,
      render: (_, record) => (
        <InputNumber
          defaultValue={record.quota.documents}
          onChange={(v) =>
            setEditing((prev) => ({
              ...prev,
              [record.id]: {
                ...record.quota,
                ...prev[record.id],
                documents: v ?? record.quota.documents,
              },
            }))
          }
        />
      ),
    },
    {
      title: '查询配额',
      key: 'queries',
      width: 140,
      render: (_, record) => (
        <InputNumber
          defaultValue={record.quota.queries}
          onChange={(v) =>
            setEditing((prev) => ({
              ...prev,
              [record.id]: {
                ...record.quota,
                ...prev[record.id],
                queries: v ?? record.quota.queries,
              },
            }))
          }
        />
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 100,
      render: (_, record) => (
        <Button type="link" onClick={() => handleSave(record)}>
          保存
        </Button>
      ),
    },
  ];

  return (
    <Card title="租户管理">
      <Space style={{ marginBottom: 16 }}>
        <Button type="primary">新建租户</Button>
      </Space>
      <Table<Tenant>
        rowKey="id"
        columns={columns}
        dataSource={data.items}
        loading={loading}
        pagination={{ pageSize: 10, total: data.total }}
      />
    </Card>
  );
}

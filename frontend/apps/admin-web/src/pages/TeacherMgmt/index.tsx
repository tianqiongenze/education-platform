import { useEffect, useState } from 'react';
import { Spin, Table, Typography, App as AntApp } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { apiClient } from '../../api/client';
import type { Pagination, User } from '@ep/shared';

interface TeacherRow {
  id: string;
  username: string;
  nickname: string;
  courseCount: number;
  studentCount: number;
  rating: number;
}

export default function TeacherMgmtPage() {
  const { message } = AntApp.useApp();
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<Pagination<TeacherRow> | null>(null);
  const [page, setPage] = useState(1);
  const pageSize = 10;

  const fetchData = async (p: number) => {
    setLoading(true);
    try {
      const res = await apiClient.get<Pagination<User>>('/api/user/teachers', {
        page: p,
        pageSize,
      });
      setData({
        ...res,
        items: res.items.map((u) => ({
          id: u.id,
          username: u.username,
          nickname: u.nickname,
          courseCount: 0,
          studentCount: 0,
          rating: 0,
        })),
      });
    } catch (err) {
      const msg = err instanceof Error ? err.message : '加载失败';
      message.error(msg);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData(page);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page]);

  const columns: ColumnsType<TeacherRow> = [
    { title: '用户名', dataIndex: 'username', key: 'username' },
    { title: '昵称', dataIndex: 'nickname', key: 'nickname' },
    { title: '课程数', dataIndex: 'courseCount', key: 'courseCount' },
    { title: '学生数', dataIndex: 'studentCount', key: 'studentCount' },
    {
      title: '评分',
      dataIndex: 'rating',
      key: 'rating',
      render: (v: number) => v.toFixed(1),
    },
  ];

  return (
    <Spin spinning={loading}>
      <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
        共 {data?.total ?? 0} 条记录
      </Typography.Text>
      <Table
        rowKey="id"
        columns={columns}
        dataSource={data?.items}
        pagination={{
          current: page,
          pageSize,
          total: data?.total ?? 0,
          onChange: (p) => setPage(p),
          showSizeChanger: false,
        }}
      />
    </Spin>
  );
}

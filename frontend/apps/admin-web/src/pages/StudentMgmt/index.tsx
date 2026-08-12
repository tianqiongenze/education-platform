import { useEffect, useState } from 'react';
import { Button, Input, Space, Spin, Table, Typography, App as AntApp } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { SearchOutlined } from '@ant-design/icons';
import { apiClient } from '../../api/client';
import type { Pagination, User } from '@ep/shared';

interface StudentRow {
  id: string;
  username: string;
  nickname: string;
  email: string | null;
  enrolledCourseCount: number;
  createdAt: string;
}

export default function StudentMgmtPage() {
  const { message } = AntApp.useApp();
  const [keyword, setKeyword] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<Pagination<StudentRow> | null>(null);
  const pageSize = 10;

  const fetchData = async (p: number, kw: string) => {
    setLoading(true);
    try {
      const res = await apiClient.get<Pagination<User>>('/api/user/students', {
        page: p,
        pageSize,
        keyword: kw || undefined,
      });
      setData({
        ...res,
        items: res.items.map((u) => ({
          id: u.id,
          username: u.username,
          nickname: u.nickname,
          email: u.email,
          enrolledCourseCount: 0,
          createdAt: u.createdAt,
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
    fetchData(page, search);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, search]);

  const handleSearch = () => {
    setSearch(keyword);
    setPage(1);
  };

  const columns: ColumnsType<StudentRow> = [
    { title: '用户名', dataIndex: 'username', key: 'username' },
    { title: '昵称', dataIndex: 'nickname', key: 'nickname' },
    { title: '邮箱', dataIndex: 'email', key: 'email' },
    { title: '已报课程', dataIndex: 'enrolledCourseCount', key: 'enrolledCourseCount' },
    {
      title: '注册时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (v: string) => new Date(v).toLocaleDateString('zh-CN'),
    },
  ];

  return (
    <Spin spinning={loading}>
      <Space style={{ marginBottom: 16 }}>
        <Input
          placeholder="搜索用户名/昵称"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onPressEnter={handleSearch}
          prefix={<SearchOutlined />}
          style={{ width: 240 }}
        />
        <Button onClick={handleSearch}>搜索</Button>
      </Space>
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

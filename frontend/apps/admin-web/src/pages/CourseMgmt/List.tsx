import { useState, type ReactNode } from 'react';
import {
  Button,
  Input,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
  App as AntApp,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useCourseList } from '@ep/query';
import { apiClient } from '../../api/client';
import type { Course, CourseStatus } from '@ep/shared';

const STATUS_COLOR: Record<CourseStatus, string> = {
  draft: 'default',
  published: 'green',
  archived: 'gray',
};

const STATUS_LABEL: Record<CourseStatus, string> = {
  draft: '草稿',
  published: '已发布',
  archived: '已归档',
};

export default function CourseListPage() {
  const navigate = useNavigate();
  const { message } = AntApp.useApp();
  const [keyword, setKeyword] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const pageSize = 10;

  const { data, isLoading, isError, error } = useCourseList(apiClient, {
    page,
    pageSize,
    ...(search ? { keyword: search } : {}),
  });

  if (isError) {
    const msg = error instanceof Error ? error.message : '加载失败';
    message.error(msg);
  }

  const columns: ColumnsType<Course> = [
    {
      title: '课程标题',
      dataIndex: 'title',
      key: 'title',
      render: (text: string, record) => (
        <a onClick={() => navigate(`/courses/${record.id}`)}>{text}</a>
      ),
    },
    { title: '讲师', dataIndex: 'teacherName', key: 'teacherName' },
    { title: '报名人数', dataIndex: 'enrollmentCount', key: 'enrollmentCount' },
    {
      title: '评分',
      dataIndex: 'rating',
      key: 'rating',
      render: (v: number) => v.toFixed(1),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (status: CourseStatus) => (
        <Tag color={STATUS_COLOR[status]}>{STATUS_LABEL[status]}</Tag>
      ),
    },
    {
      title: '操作',
      key: 'actions',
      render: (_: ReactNode, record) => (
        <Button type="link" onClick={() => navigate(`/courses/${record.id}`)}>
          查看
        </Button>
      ),
    },
  ];

  const handleSearch = () => {
    setSearch(keyword);
    setPage(1);
  };

  return (
    <Spin spinning={isLoading}>
      <Space style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }}>
        <Input
          placeholder="搜索课程标题"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onPressEnter={handleSearch}
          style={{ width: 240 }}
          prefix={<SearchOutlined />}
        />
        <Space>
          <Button onClick={handleSearch}>搜索</Button>
          <Button type="primary" icon={<PlusOutlined />}>
            创建课程
          </Button>
        </Space>
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

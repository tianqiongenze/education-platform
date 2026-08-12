import { useMemo, useState } from 'react';
import {
  Button,
  Input,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
  App as AntApp,
  Popconfirm,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  listCourses,
  searchCourses,
  publishCourse,
  archiveCourse,
  ApiError,
} from '../../api/business';
import type { Course, CourseStatus } from '../../types/business';

const STATUS_COLOR: Record<CourseStatus, string> = {
  0: 'default',
  1: 'green',
  2: 'gray',
};

const STATUS_LABEL: Record<CourseStatus, string> = {
  0: '草稿',
  1: '已发布',
  2: '已归档',
};

const QUERY_KEY = ['business', 'courses'] as const;

function formatDateTime(v: number | null | undefined): string {
  if (v === null || v === undefined) return '-';
  try {
    return new Date(v).toLocaleString('zh-CN');
  } catch {
    return String(v);
  }
}

export default function CourseListPage() {
  const navigate = useNavigate();
  const { message } = AntApp.useApp();
  const qc = useQueryClient();

  const [searchInput, setSearchInput] = useState('');
  const [committedKeyword, setCommittedKeyword] = useState('');
  const [page, setPage] = useState(1);
  const pageSize = 10;

  const keyword = useMemo(() => committedKeyword.trim(), [committedKeyword]);

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['business', 'courses', 'list', keyword || 'all'] as const,
    queryFn: async (): Promise<Course[]> => {
      if (keyword) {
        return searchCourses(keyword);
      }
      return listCourses(1, 100);
    },
  });

  const publishMutation = useMutation({
    mutationFn: (id: number) => publishCourse(id),
    onSuccess: (_data, id) => {
      message.success(`课程 #${id} 已发布`);
      qc.invalidateQueries({ queryKey: QUERY_KEY });
    },
    onError: (err) => {
      const msg = err instanceof ApiError ? err.message : '发布失败';
      message.error(msg);
    },
  });

  const archiveMutation = useMutation({
    mutationFn: (id: number) => archiveCourse(id),
    onSuccess: (_data, id) => {
      message.success(`课程 #${id} 已归档`);
      qc.invalidateQueries({ queryKey: QUERY_KEY });
    },
    onError: (err) => {
      const msg = err instanceof ApiError ? err.message : '归档失败';
      message.error(msg);
    },
  });

  if (isError) {
    const msg = error instanceof ApiError ? error.message : '加载失败';
    message.error(msg);
  }

  // Client-side pagination since the backend returns a flat list.
  const total = data?.length ?? 0;
  const pagedData = useMemo(() => {
    const start = (page - 1) * pageSize;
    return (data ?? []).slice(start, start + pageSize);
  }, [data, page, pageSize]);

  const handleSearch = () => {
    setCommittedKeyword(searchInput.trim());
    setPage(1);
  };

  const columns: ColumnsType<Course> = [
    {
      title: '课程 ID',
      dataIndex: 'courseId',
      key: 'courseId',
      width: 90,
      render: (courseId: number) => (
        <a onClick={() => navigate(`/business/courses/${courseId}`)}>{courseId}</a>
      ),
    },
    { title: '标题', dataIndex: 'title', key: 'title' },
    {
      title: '教师 ID',
      dataIndex: 'teacherId',
      key: 'teacherId',
      width: 100,
    },
    {
      title: '价格',
      dataIndex: 'price',
      key: 'price',
      width: 100,
      render: (v: string | null) => (v ? `¥${v}` : '-'),
    },
    {
      title: '学生数',
      dataIndex: 'studentCount',
      key: 'studentCount',
      width: 90,
      render: (v: number | null) => v ?? 0,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (status: CourseStatus) => (
        <Tag color={STATUS_COLOR[status]}>{STATUS_LABEL[status]}</Tag>
      ),
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (v: number | null) => formatDateTime(v),
    },
    {
      title: '操作',
      key: 'actions',
      width: 240,
      render: (_: unknown, record: Course) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            onClick={() => navigate(`/business/courses/${record.courseId}`)}
          >
            查看
          </Button>
          {record.status === 0 && (
            <Popconfirm
              title="确认发布该课程？"
              onConfirm={() => publishMutation.mutate(record.courseId)}
              okText="发布"
              cancelText="取消"
            >
              <Button type="link" size="small" loading={publishMutation.isPending}>
                发布
              </Button>
            </Popconfirm>
          )}
          {record.status === 1 && (
            <Popconfirm
              title="确认归档该课程？归档后学生将无法访问。"
              onConfirm={() => archiveMutation.mutate(record.courseId)}
              okText="归档"
              cancelText="取消"
            >
              <Button type="link" size="small" danger loading={archiveMutation.isPending}>
                归档
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <Spin spinning={isLoading}>
      <Space
        style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }}
      >
        <Input
          placeholder="按关键词搜索课程"
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
          onPressEnter={handleSearch}
          style={{ width: 240 }}
          prefix={<SearchOutlined />}
          allowClear
        />
        <Space>
          <Button onClick={handleSearch}>搜索</Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => navigate('/business/courses/create')}
          >
            创建课程
          </Button>
        </Space>
      </Space>

      <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
        共 {total} 条记录
      </Typography.Text>

      <Table
        rowKey="courseId"
        columns={columns}
        dataSource={pagedData}
        locale={{ emptyText: '暂无数据' }}
        pagination={{
          current: page,
          pageSize,
          total,
          onChange: (p) => setPage(p),
          showSizeChanger: false,
        }}
      />
    </Spin>
  );
}

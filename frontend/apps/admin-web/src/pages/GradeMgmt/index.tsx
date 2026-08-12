import { useState } from 'react';
import { Button, Input, Select, Space, Spin, Table, Tag, Typography, App as AntApp } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { SearchOutlined, ReloadOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../../api/client';

interface GradeRecord {
  id: string;
  studentName: string;
  courseTitle: string;
  assignmentTitle: string;
  score: number;
  maxScore: number;
  status: 'graded' | 'pending' | 'late';
  submittedAt: string;
  gradedAt: string | null;
}

interface GradeListResponse {
  items: GradeRecord[];
  total: number;
}

const STATUS_COLOR: Record<GradeRecord['status'], string> = {
  graded: 'green',
  pending: 'orange',
  late: 'red',
};

const STATUS_LABEL: Record<GradeRecord['status'], string> = {
  graded: '已评分',
  pending: '待评分',
  late: '迟交',
};

export default function GradeMgmtPage() {
  const { message } = AntApp.useApp();
  const [keyword, setKeyword] = useState('');
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<GradeRecord['status'] | undefined>(undefined);
  const [page, setPage] = useState(1);
  const pageSize = 10;

  const { data, isLoading, isError, error, refetch } = useQuery<GradeListResponse>({
    queryKey: ['grades', page, pageSize, search, statusFilter],
    queryFn: () =>
      apiClient.get<GradeListResponse>('/api/grade/list', {
        page,
        pageSize,
        keyword: search || undefined,
        status: statusFilter,
      }),
  });

  if (isError) {
    const msg = error instanceof Error ? error.message : '加载失败';
    message.error(msg);
  }

  const columns: ColumnsType<GradeRecord> = [
    { title: '学生', dataIndex: 'studentName', key: 'studentName' },
    { title: '课程', dataIndex: 'courseTitle', key: 'courseTitle' },
    { title: '作业', dataIndex: 'assignmentTitle', key: 'assignmentTitle' },
    {
      title: '分数',
      key: 'score',
      render: (_, r) => (
        <Typography.Text strong={r.status === 'graded'}>
          {r.status === 'graded' ? `${r.score}/${r.maxScore}` : '-'}
        </Typography.Text>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (s: GradeRecord['status']) => <Tag color={STATUS_COLOR[s]}>{STATUS_LABEL[s]}</Tag>,
    },
    {
      title: '提交时间',
      dataIndex: 'submittedAt',
      key: 'submittedAt',
      render: (v: string) => new Date(v).toLocaleString('zh-CN'),
    },
    {
      title: '评分时间',
      dataIndex: 'gradedAt',
      key: 'gradedAt',
      render: (v: string | null) => (v ? new Date(v).toLocaleString('zh-CN') : '-'),
    },
  ];

  const handleSearch = () => {
    setSearch(keyword);
    setPage(1);
  };

  return (
    <Spin spinning={isLoading}>
      <Space style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }}>
        <Space>
          <Input
            placeholder="搜索学生/课程"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onPressEnter={handleSearch}
            style={{ width: 200 }}
            prefix={<SearchOutlined />}
          />
          <Select
            allowClear
            placeholder="状态筛选"
            style={{ width: 120 }}
            value={statusFilter}
            onChange={(v) => {
              setStatusFilter(v);
              setPage(1);
            }}
            options={[
              { label: '已评分', value: 'graded' },
              { label: '待评分', value: 'pending' },
              { label: '迟交', value: 'late' },
            ]}
          />
          <Button onClick={handleSearch}>搜索</Button>
        </Space>
        <Button icon={<ReloadOutlined />} onClick={() => refetch()}>刷新</Button>
      </Space>
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

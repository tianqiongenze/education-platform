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
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { SearchOutlined } from '@ant-design/icons';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { listSubmissions, ApiError } from '../../api/it-backend';
import type { Submission, SubmissionStatus } from '../../types/it-backend';

const STATUS_COLOR: Record<SubmissionStatus, string> = {
  SUBMITTED: 'blue',
  IN_REVIEW: 'orange',
  GRADED: 'green',
  RETURNED: 'red',
  LATE: 'gray',
};

const STATUS_LABEL: Record<SubmissionStatus, string> = {
  SUBMITTED: '已提交',
  IN_REVIEW: '评审中',
  GRADED: '已评分',
  RETURNED: '已退回',
  LATE: '迟交',
};

function formatDateTime(v: string | null | undefined): string {
  if (!v) return '-';
  try {
    return new Date(v).toLocaleString('zh-CN');
  } catch {
    return String(v);
  }
}

export default function SubmissionListPage() {
  const navigate = useNavigate();
  const { message } = AntApp.useApp();
  const [searchParams] = useSearchParams();

  const initialAssignmentId = searchParams.get('assignmentId') ?? '';
  const [searchInput, setSearchInput] = useState(initialAssignmentId);
  const [committedAssignmentId, setCommittedAssignmentId] = useState(initialAssignmentId);
  const [page, setPage] = useState(1);
  const pageSize = 10;

  const assignmentIdNum = useMemo(() => {
    const trimmed = committedAssignmentId.trim();
    if (!trimmed) return undefined;
    const n = Number(trimmed);
    return Number.isFinite(n) && n > 0 ? n : undefined;
  }, [committedAssignmentId]);

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['it-backend', 'submissions', 'list', assignmentIdNum ?? 'all'] as const,
    queryFn: async (): Promise<Submission[]> => {
      if (assignmentIdNum === undefined) return [];
      return listSubmissions(assignmentIdNum);
    },
  });

  if (isError) {
    const msg = error instanceof ApiError ? error.message : '加载失败';
    message.error(msg);
  }

  const total = data?.length ?? 0;
  const pagedData = useMemo(() => {
    const start = (page - 1) * pageSize;
    return (data ?? []).slice(start, start + pageSize);
  }, [data, page, pageSize]);

  const handleSearch = () => {
    setCommittedAssignmentId(searchInput.trim());
    setPage(1);
  };

  const columns: ColumnsType<Submission> = [
    { title: 'ID', dataIndex: 'id', key: 'id', width: 80 },
    {
      title: '学生 ID',
      dataIndex: 'studentId',
      key: 'studentId',
      width: 100,
      render: (studentId: number) => `学生 #${studentId}`,
    },
    {
      title: '作业 ID',
      dataIndex: 'assignmentId',
      key: 'assignmentId',
      width: 100,
      render: (assignmentId: number) => (
        <a onClick={() => navigate(`/it-backend/assignments/${assignmentId}`)}>
          #{assignmentId}
        </a>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (status: SubmissionStatus) => (
        <Tag color={STATUS_COLOR[status]}>{STATUS_LABEL[status]}</Tag>
      ),
    },
    {
      title: '分数',
      dataIndex: 'score',
      key: 'score',
      width: 90,
      render: (score: number | null) => (score === null || score === undefined ? '-' : score),
    },
    {
      title: '提交时间',
      dataIndex: 'submittedAt',
      key: 'submittedAt',
      width: 180,
      render: (v: string) => formatDateTime(v),
    },
    {
      title: '评分时间',
      dataIndex: 'gradedAt',
      key: 'gradedAt',
      width: 180,
      render: (v: string | null) => formatDateTime(v),
    },
    {
      title: '操作',
      key: 'actions',
      width: 200,
      render: (_: unknown, record: Submission) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            onClick={() => navigate(`/it-backend/grading/${record.id}`)}
          >
            查看
          </Button>
          {(record.status === 'SUBMITTED' || record.status === 'IN_REVIEW') && (
            <Button
              type="link"
              size="small"
              onClick={() => navigate(`/it-backend/grading/${record.id}?action=grade`)}
            >
              评分
            </Button>
          )}
        </Space>
      ),
    },
  ];

  return (
    <Spin spinning={isLoading}>
      <Space style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }}>
        <Input
          placeholder="按作业 ID 筛选"
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
          onPressEnter={handleSearch}
          style={{ width: 240 }}
          prefix={<SearchOutlined />}
          allowClear
        />
        <Button onClick={handleSearch}>搜索</Button>
      </Space>

      <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
        {assignmentIdNum === undefined
          ? '请输入作业 ID 后点击搜索，加载该作业下的提交列表'
          : `共 ${total} 条提交`}
      </Typography.Text>

      <Table
        rowKey="id"
        columns={columns}
        dataSource={pagedData}
        locale={{ emptyText: assignmentIdNum === undefined ? '请输入作业 ID' : '暂无数据' }}
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

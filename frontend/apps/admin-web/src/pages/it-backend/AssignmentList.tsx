import { useMemo, useState } from 'react';
import {
  Button,
  Input,
  Modal,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
  App as AntApp,
  Popconfirm,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { PlusOutlined, SearchOutlined, EyeOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  listAssignments,
  publishAssignment,
  closeAssignment,
  ApiError,
} from '../../api/it-backend';
import type { Assignment, AssignmentStatus } from '../../types/it-backend';

const STATUS_COLOR: Record<AssignmentStatus, string> = {
  DRAFT: 'default',
  PUBLISHED: 'green',
  CLOSED: 'red',
  ARCHIVED: 'gray',
};

const STATUS_LABEL: Record<AssignmentStatus, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  CLOSED: '已关闭',
  ARCHIVED: '已归档',
};

const QUERY_KEY = ['it-backend', 'assignments'] as const;

interface CreateModalState {
  open: boolean;
  courseId: string;
}

export default function AssignmentListPage() {
  const navigate = useNavigate();
  const { message } = AntApp.useApp();
  const qc = useQueryClient();

  const [searchInput, setSearchInput] = useState('');
  const [committedCourseId, setCommittedCourseId] = useState<string>('');
  const [page, setPage] = useState(1);
  const pageSize = 10;

  const [createModal, setCreateModal] = useState<CreateModalState>({ open: false, courseId: '' });

  const courseIdNum = useMemo(() => {
    const trimmed = committedCourseId.trim();
    if (!trimmed) return undefined;
    const n = Number(trimmed);
    return Number.isFinite(n) && n > 0 ? n : undefined;
  }, [committedCourseId]);

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['it-backend', 'assignments', 'list', courseIdNum ?? 'all', page, pageSize] as const,
    queryFn: async (): Promise<Assignment[]> => {
      if (courseIdNum === undefined) {
        // No courseId filter: backend requires ?courseId, so we return an empty list
        // and prompt the user to enter a course ID.
        return [];
      }
      return listAssignments(courseIdNum);
    },
  });

  const publishMutation = useMutation({
    mutationFn: (id: number) => publishAssignment(id),
    onSuccess: (assignment) => {
      message.success(`作业 #${assignment.id} 已发布`);
      qc.invalidateQueries({ queryKey: QUERY_KEY });
    },
    onError: (err) => {
      const msg = err instanceof ApiError ? err.message : '发布失败';
      message.error(msg);
    },
  });

  const closeMutation = useMutation({
    mutationFn: (id: number) => closeAssignment(id),
    onSuccess: (assignment) => {
      message.success(`作业 #${assignment.id} 已关闭`);
      qc.invalidateQueries({ queryKey: QUERY_KEY });
    },
    onError: (err) => {
      const msg = err instanceof ApiError ? err.message : '关闭失败';
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
    setCommittedCourseId(searchInput.trim());
    setPage(1);
  };

  const columns: ColumnsType<Assignment> = [
    {
      title: 'ID',
      dataIndex: 'id',
      key: 'id',
      width: 80,
    },
    {
      title: '标题',
      dataIndex: 'title',
      key: 'title',
      render: (text: string, record) => (
        <a onClick={() => navigate(`/it-backend/assignments/${record.id}`)}>{text}</a>
      ),
    },
    {
      title: '课程',
      dataIndex: 'courseId',
      key: 'courseId',
      width: 100,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (status: AssignmentStatus) => (
        <Tag color={STATUS_COLOR[status]}>{STATUS_LABEL[status]}</Tag>
      ),
    },
    {
      title: '截止时间',
      dataIndex: 'deadline',
      key: 'deadline',
      width: 180,
      render: (v: string) => (v ? new Date(v).toLocaleString('zh-CN') : '-'),
    },
    {
      title: '满分',
      dataIndex: 'maxScore',
      key: 'maxScore',
      width: 90,
    },
    {
      title: '操作',
      key: 'actions',
      width: 220,
      render: (_: unknown, record) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => navigate(`/it-backend/assignments/${record.id}`)}
          >
            查看
          </Button>
          {record.status === 'DRAFT' && (
            <Popconfirm
              title="确认发布该作业？"
              onConfirm={() => publishMutation.mutate(record.id)}
              okText="发布"
              cancelText="取消"
            >
              <Button type="link" size="small" loading={publishMutation.isPending}>
                发布
              </Button>
            </Popconfirm>
          )}
          {record.status === 'PUBLISHED' && (
            <Popconfirm
              title="确认关闭该作业？关闭后学生将无法提交。"
              onConfirm={() => closeMutation.mutate(record.id)}
              okText="关闭"
              cancelText="取消"
            >
              <Button type="link" size="small" danger loading={closeMutation.isPending}>
                关闭
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
          placeholder="按课程 ID 搜索"
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
            onClick={() => navigate('/it-backend/assignments/create')}
          >
            创建作业
          </Button>
        </Space>
      </Space>

      <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
        {courseIdNum === undefined
          ? '请输入课程 ID 后点击搜索，加载该课程下的作业列表'
          : `共 ${total} 条记录`}
      </Typography.Text>

      <Table
        rowKey="id"
        columns={columns}
        dataSource={pagedData}
        locale={{ emptyText: courseIdNum === undefined ? '请输入课程 ID' : '暂无数据' }}
        pagination={{
          current: page,
          pageSize,
          total,
          onChange: (p) => setPage(p),
          showSizeChanger: false,
        }}
      />

      <Modal
        title="创建作业"
        open={createModal.open}
        onCancel={() => setCreateModal((s) => ({ ...s, open: false }))}
        footer={null}
        destroyOnClose
      >
        <Typography.Paragraph>
          前往创建作业页面，填写标题、课程、截止时间等信息。
        </Typography.Paragraph>
        <Space>
          <Button
            type="primary"
            onClick={() => {
              setCreateModal((s) => ({ ...s, open: false }));
              navigate('/it-backend/assignments/create');
            }}
          >
            前往创建
          </Button>
          <Button onClick={() => setCreateModal((s) => ({ ...s, open: false }))}>
            取消
          </Button>
        </Space>
      </Modal>
    </Spin>
  );
}

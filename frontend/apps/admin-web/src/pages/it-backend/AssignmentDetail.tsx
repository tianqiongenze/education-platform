import { useMemo, useState } from 'react';
import {
  Button,
  Card,
  Descriptions,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Typography,
  App as AntApp,
  Popconfirm,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getAssignment,
  listSubmissions,
  findGradingBySubmission,
  startGrading,
  ApiError,
} from '../../api/it-backend';
import type {
  Assignment,
  AssignmentStatus,
  Submission,
  SubmissionStatus,
  GradePipeline,
  GradePipelineStatus,
} from '../../types/it-backend';

const ASSIGNMENT_STATUS_COLOR: Record<AssignmentStatus, string> = {
  DRAFT: 'default',
  PUBLISHED: 'green',
  CLOSED: 'red',
  ARCHIVED: 'gray',
};

const SUBMISSION_STATUS_COLOR: Record<SubmissionStatus, string> = {
  SUBMITTED: 'blue',
  IN_REVIEW: 'orange',
  GRADED: 'green',
  RETURNED: 'red',
  LATE: 'gray',
};

const PIPELINE_STATUS_COLOR: Record<GradePipelineStatus, string> = {
  INITIATED: 'default',
  RUNNING: 'processing',
  COMPLETED: 'green',
  FAILED: 'red',
  CANCELLED: 'gray',
};

const SUBMISSION_STATUS_LABEL: Record<SubmissionStatus, string> = {
  SUBMITTED: '已提交',
  IN_REVIEW: '评审中',
  GRADED: '已评分',
  RETURNED: '已退回',
  LATE: '迟交',
};

const PIPELINE_STATUS_LABEL: Record<GradePipelineStatus, string> = {
  INITIATED: '已发起',
  RUNNING: '运行中',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
};

function formatDateTime(v: string | null | undefined): string {
  if (!v) return '-';
  try {
    return new Date(v).toLocaleString('zh-CN');
  } catch {
    return String(v);
  }
}

interface PipelineRow {
  key: string;
  submissionId: number;
  pipelineId: number;
  status: GradePipelineStatus;
  totalScore: number | null;
  startedAt: string | null;
  completedAt: string | null;
}

export default function AssignmentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { message } = AntApp.useApp();
  const qc = useQueryClient();

  const assignmentId = useMemo(() => {
    const n = Number(id);
    return Number.isFinite(n) && n > 0 ? n : undefined;
  }, [id]);

  const [activeTab, setActiveTab] = useState('info');

  const {
    data: assignment,
    isLoading: assignmentLoading,
    isError: assignmentError,
    error: assignmentErr,
  } = useQuery({
    queryKey: ['it-backend', 'assignments', 'detail', assignmentId] as const,
    queryFn: async (): Promise<Assignment> => {
      if (assignmentId === undefined) {
        throw new Error('无效的作业 ID');
      }
      return getAssignment(assignmentId);
    },
    enabled: assignmentId !== undefined,
  });

  const {
    data: submissions,
    isLoading: submissionsLoading,
    isError: submissionsError,
    error: submissionsErr,
  } = useQuery({
    queryKey: ['it-backend', 'submissions', 'list', assignmentId] as const,
    queryFn: async (): Promise<Submission[]> => {
      if (assignmentId === undefined) return [];
      return listSubmissions(assignmentId);
    },
    enabled: assignmentId !== undefined && activeTab === 'submissions',
  });

  // Fetch grading pipelines for all submissions (only when grading tab is active).
  const pipelineQueries = useQuery({
    queryKey: ['it-backend', 'grading', 'by-assignment', assignmentId] as const,
    queryFn: async (): Promise<PipelineRow[]> => {
      if (assignmentId === undefined) return [];
      const subs = await listSubmissions(assignmentId);
      const results = await Promise.allSettled(
        subs.map((s) => findGradingBySubmission(s.id)),
      );
      const rows: PipelineRow[] = [];
      results.forEach((r, idx) => {
        const sub = subs[idx];
        if (r.status === 'fulfilled' && r.value && sub) {
          const p: GradePipeline = r.value;
          rows.push({
            key: String(p.id),
            submissionId: sub.id,
            pipelineId: p.id,
            status: p.status,
            totalScore: p.totalScore,
            startedAt: p.startedAt,
            completedAt: p.completedAt,
          });
        }
      });
      return rows;
    },
    enabled: assignmentId !== undefined && activeTab === 'grading',
    refetchInterval: (query) => {
      const data = query.state.data;
      if (data && data.some((r) => r.status === 'RUNNING' || r.status === 'INITIATED')) {
        return 3000;
      }
      return false;
    },
  });

  const startGradingMutation = useMutation({
    mutationFn: (submissionId: number) =>
      startGrading({
        submissionId,
        idempotencyKey: `grade-${submissionId}-${Date.now()}`,
      }),
    onSuccess: (pipeline) => {
      message.success(`评分流水线 #${pipeline.id} 已启动`);
      qc.invalidateQueries({ queryKey: ['it-backend', 'grading', 'by-assignment'] });
      setActiveTab('grading');
    },
    onError: (err) => {
      const msg = err instanceof ApiError ? err.message : '启动评分失败';
      message.error(msg);
    },
  });

  if (assignmentError) {
    const msg = assignmentErr instanceof ApiError ? assignmentErr.message : '加载作业失败';
    message.error(msg);
  }
  if (submissionsError) {
    const msg = submissionsErr instanceof ApiError ? submissionsErr.message : '加载提交列表失败';
    message.error(msg);
  }

  if (assignmentLoading) {
    return (
      <div className="ep-page-loading">
        <Spin size="large" />
      </div>
    );
  }

  if (!assignment) {
    return (
      <div>
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/it-backend/assignments')}
          style={{ marginBottom: 16 }}
        >
          返回列表
        </Button>
        <Typography.Text>作业不存在</Typography.Text>
      </div>
    );
  }

  const submissionColumns: ColumnsType<Submission> = [
    {
      title: 'ID',
      dataIndex: 'id',
      key: 'id',
      width: 80,
      render: (submissionId: number) => (
        <a onClick={() => navigate(`/it-backend/submissions?assignmentId=${assignment.id}&focus=${submissionId}`)}>
          {submissionId}
        </a>
      ),
    },
    {
      title: '学生 ID',
      dataIndex: 'studentId',
      key: 'studentId',
      width: 100,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (status: SubmissionStatus) => (
        <Tag color={SUBMISSION_STATUS_COLOR[status]}>
          {SUBMISSION_STATUS_LABEL[status]}
        </Tag>
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
      title: '操作',
      key: 'actions',
      width: 240,
      render: (_: unknown, record: Submission) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            onClick={() => navigate(`/it-backend/submissions?assignmentId=${assignment.id}&focus=${record.id}`)}
          >
            查看
          </Button>
          {(record.status === 'SUBMITTED' || record.status === 'IN_REVIEW') && (
            <>
              <Button
                type="link"
                size="small"
                onClick={() => navigate(`/it-backend/grading/${record.id}`)}
              >
                评分流水线
              </Button>
              <Popconfirm
                title="启动评分流水线？"
                onConfirm={() => startGradingMutation.mutate(record.id)}
                okText="启动"
                cancelText="取消"
              >
                <Button type="link" size="small" loading={startGradingMutation.isPending}>
                  启动评分
                </Button>
              </Popconfirm>
            </>
          )}
        </Space>
      ),
    },
  ];

  const pipelineColumns: ColumnsType<PipelineRow> = [
    { title: '流水线 ID', dataIndex: 'pipelineId', key: 'pipelineId', width: 100 },
    { title: '提交 ID', dataIndex: 'submissionId', key: 'submissionId', width: 100 },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (status: GradePipelineStatus) => (
        <Tag color={PIPELINE_STATUS_COLOR[status]}>{PIPELINE_STATUS_LABEL[status]}</Tag>
      ),
    },
    {
      title: '总分',
      dataIndex: 'totalScore',
      key: 'totalScore',
      width: 90,
      render: (v: number | null) => (v === null || v === undefined ? '-' : v),
    },
    {
      title: '开始时间',
      dataIndex: 'startedAt',
      key: 'startedAt',
      width: 180,
      render: (v: string | null) => formatDateTime(v),
    },
    {
      title: '完成时间',
      dataIndex: 'completedAt',
      key: 'completedAt',
      width: 180,
      render: (v: string | null) => formatDateTime(v),
    },
    {
      title: '操作',
      key: 'actions',
      width: 140,
      render: (_: unknown, record: PipelineRow) => (
        <Button
          type="link"
          size="small"
          onClick={() => navigate(`/it-backend/grading/${record.submissionId}`)}
        >
          查看流水线
        </Button>
      ),
    },
  ];

  return (
    <Spin spinning={submissionsLoading || pipelineQueries.isLoading}>
      <Button
        icon={<ArrowLeftOutlined />}
        onClick={() => navigate('/it-backend/assignments')}
        style={{ marginBottom: 16 }}
      >
        返回列表
      </Button>

      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={[
          {
            key: 'info',
            label: '作业信息',
            children: (
              <Card>
                <Descriptions column={2} bordered>
                  <Descriptions.Item label="ID">{assignment.id}</Descriptions.Item>
                  <Descriptions.Item label="标题">{assignment.title}</Descriptions.Item>
                  <Descriptions.Item label="课程 ID">{assignment.courseId}</Descriptions.Item>
                  <Descriptions.Item label="教师 ID">
                    {assignment.teacherId ?? '-'}
                  </Descriptions.Item>
                  <Descriptions.Item label="状态">
                    <Tag color={ASSIGNMENT_STATUS_COLOR[assignment.status]}>
                      {assignment.status}
                    </Tag>
                  </Descriptions.Item>
                  <Descriptions.Item label="满分">{assignment.maxScore}</Descriptions.Item>
                  <Descriptions.Item label="截止时间">
                    {formatDateTime(assignment.deadline)}
                  </Descriptions.Item>
                  <Descriptions.Item label="创建时间">
                    {formatDateTime(assignment.createdAt)}
                  </Descriptions.Item>
                  <Descriptions.Item label="更新时间">
                    {formatDateTime(assignment.updatedAt)}
                  </Descriptions.Item>
                  <Descriptions.Item label="描述" span={2}>
                    {assignment.description || '-'}
                  </Descriptions.Item>
                </Descriptions>
              </Card>
            ),
          },
          {
            key: 'submissions',
            label: `提交列表${submissions ? ` (${submissions.length})` : ''}`,
            children: (
              <Card>
                <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
                  共 {submissions?.length ?? 0} 条提交
                </Typography.Text>
                <Table
                  rowKey="id"
                  columns={submissionColumns}
                  dataSource={submissions}
                  pagination={{ pageSize: 10, showSizeChanger: false }}
                />
              </Card>
            ),
          },
          {
            key: 'grading',
            label: '评分流水线',
            children: (
              <Card>
                <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
                  共 {pipelineQueries.data?.length ?? 0} 条流水线
                </Typography.Text>
                <Table
                  rowKey="pipelineId"
                  columns={pipelineColumns}
                  dataSource={pipelineQueries.data}
                  pagination={{ pageSize: 10, showSizeChanger: false }}
                />
              </Card>
            ),
          },
        ]}
      />
    </Spin>
  );
}

import { useMemo } from 'react';
import {
  Button,
  Card,
  Col,
  Descriptions,
  Row,
  Space,
  Spin,
  Tag,
  Timeline,
  Typography,
  App as AntApp,
  Popconfirm,
} from 'antd';
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  LoadingOutlined,
  MinusCircleOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  findGradingBySubmission,
  getGradingPipeline,
  startGrading,
  ApiError,
} from '../../api/it-backend';
import type {
  GradePipeline,
  GradePipelineStatus,
  GradeStage,
  GradeStageStatus,
} from '../../types/it-backend';

const PIPELINE_STATUS_COLOR: Record<GradePipelineStatus, string> = {
  INITIATED: 'default',
  RUNNING: 'processing',
  COMPLETED: 'green',
  FAILED: 'red',
  CANCELLED: 'gray',
};

const PIPELINE_STATUS_LABEL: Record<GradePipelineStatus, string> = {
  INITIATED: '已发起',
  RUNNING: '运行中',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
};

const STAGE_STATUS_COLOR: Record<GradeStageStatus, string> = {
  PENDING: 'default',
  RUNNING: 'processing',
  PASSED: 'green',
  FAILED: 'red',
  SKIPPED: 'gray',
};

const STAGE_STATUS_LABEL: Record<GradeStageStatus, string> = {
  PENDING: '等待中',
  RUNNING: '运行中',
  PASSED: '通过',
  FAILED: '失败',
  SKIPPED: '跳过',
};

const STAGE_ORDER = ['auto_check', 'plagiarism_check', 'manual_review'] as const;
const STAGE_TITLE: Record<string, string> = {
  auto_check: '自动检查',
  plagiarism_check: '查重检测',
  manual_review: '人工复审',
};

function formatDateTime(v: string | null | undefined): string {
  if (!v) return '-';
  try {
    return new Date(v).toLocaleString('zh-CN');
  } catch {
    return String(v);
  }
}

function stageIcon(status: GradeStageStatus | undefined) {
  switch (status) {
    case 'PASSED':
      return <CheckCircleOutlined style={{ color: '#52c41a' }} />;
    case 'FAILED':
      return <CloseCircleOutlined style={{ color: '#ff4d4f' }} />;
    case 'RUNNING':
      return <LoadingOutlined style={{ color: '#1677ff' }} />;
    case 'SKIPPED':
      return <MinusCircleOutlined style={{ color: '#bfbfbf' }} />;
    case 'PENDING':
    default:
      return <ClockCircleOutlined style={{ color: '#8c8c8c' }} />;
  }
}

export default function GradingPipelinePage() {
  const navigate = useNavigate();
  const { message } = AntApp.useApp();
  const qc = useQueryClient();
  const { submissionId: pathParamId } = useParams<{ submissionId: string }>();

  // Route: /it-backend/grading/:submissionId
  const submissionIdStr = pathParamId ?? '';

  const submissionId = useMemo(() => {
    const n = Number(submissionIdStr);
    return Number.isFinite(n) && n > 0 ? n : undefined;
  }, [submissionIdStr]);

  // First, find the pipeline for the submission. The backend returns the
  // pipeline directly from GET /api/v1/grading?submissionId={id}.
  const findBySubmissionQuery = useQuery({
    queryKey: ['it-backend', 'grading', 'by-submission', submissionId] as const,
    queryFn: async (): Promise<GradePipeline | null> => {
      if (submissionId === undefined) return null;
      try {
        return await findGradingBySubmission(submissionId);
      } catch (err) {
        // 404 means no pipeline yet; treat as "not started".
        if (err instanceof ApiError && err.isNotFound()) {
          return null;
        }
        throw err;
      }
    },
    enabled: submissionId !== undefined,
  });

  const pipelineId = findBySubmissionQuery.data?.id;

  // Once we have a pipeline ID, poll its full state (with stages) every 3s while RUNNING.
  const pipelineQuery = useQuery({
    queryKey: ['it-backend', 'grading', 'detail', pipelineId] as const,
    queryFn: async (): Promise<GradePipeline> => {
      if (pipelineId === undefined) {
        throw new Error('无流水线 ID');
      }
      return getGradingPipeline(pipelineId);
    },
    enabled: pipelineId !== undefined,
    refetchInterval: (query) => {
      const data = query.state.data;
      if (data && (data.status === 'RUNNING' || data.status === 'INITIATED')) {
        return 3000;
      }
      return false;
    },
  });

  const startMutation = useMutation({
    mutationFn: () => {
      if (submissionId === undefined) {
        throw new Error('无效的提交 ID');
      }
      return startGrading({
        submissionId,
        idempotencyKey: `grade-${submissionId}-${Date.now()}`,
      });
    },
    onSuccess: (pipeline: GradePipeline) => {
      message.success(`评分流水线 #${pipeline.id} 已启动`);
      qc.invalidateQueries({ queryKey: ['it-backend', 'grading', 'by-submission', submissionId] });
    },
    onError: (err) => {
      const msg = err instanceof ApiError ? err.message : '启动评分失败';
      message.error(msg);
    },
  });

  const pipeline: GradePipeline | null | undefined =
    pipelineQuery.data ?? findBySubmissionQuery.data;
  const isLoading = findBySubmissionQuery.isLoading || pipelineQuery.isLoading;

  // Build timeline items: ensure the three canonical stages are always shown
  // in order, even if the backend has not yet created all of them.
  const timelineItems = useMemo(() => {
    const stages: GradeStage[] = pipeline?.stages ?? [];
    const byName = new Map<string, GradeStage>();
    for (const s of stages) {
      byName.set(s.stageName, s);
    }
    return STAGE_ORDER.map((name) => {
      const stage = byName.get(name);
      return {
        key: name,
        title: STAGE_TITLE[name] ?? name,
        stage,
      };
    });
  }, [pipeline]);

  const isRunning = pipeline?.status === 'RUNNING' || pipeline?.status === 'INITIATED';

  return (
    <Spin spinning={isLoading || startMutation.isPending}>
      <Button
        icon={<ArrowLeftOutlined />}
        onClick={() => navigate(-1)}
        style={{ marginBottom: 16 }}
      >
        返回
      </Button>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={16}>
          <Card
            title={
              <Space>
                <span>评分流水线</span>
                {pipeline && (
                  <Tag color={PIPELINE_STATUS_COLOR[pipeline.status]}>
                    {PIPELINE_STATUS_LABEL[pipeline.status]}
                  </Tag>
                )}
              </Space>
            }
            extra={
              !pipeline ? (
                <Popconfirm
                  title="启动评分流水线？"
                  onConfirm={() => startMutation.mutate()}
                  okText="启动"
                  cancelText="取消"
                >
                  <Button type="primary" loading={startMutation.isPending}>
                    启动评分
                  </Button>
                </Popconfirm>
              ) : null
            }
          >
            {!pipeline ? (
              <Typography.Text type="secondary">
                该提交尚未启动评分流水线。点击右上角「启动评分」开始三阶段评分流程。
              </Typography.Text>
            ) : (
              <>
                <Descriptions column={2} size="small" style={{ marginBottom: 24 }}>
                  <Descriptions.Item label="流水线 ID">{pipeline.id}</Descriptions.Item>
                  <Descriptions.Item label="提交 ID">
                    {pipeline.submissionId}
                  </Descriptions.Item>
                  <Descriptions.Item label="评分人 ID">
                    {pipeline.gradedBy ?? '-'}
                  </Descriptions.Item>
                  <Descriptions.Item label="总分">
                    {pipeline.totalScore === null || pipeline.totalScore === undefined
                      ? '-'
                      : pipeline.totalScore}
                  </Descriptions.Item>
                  <Descriptions.Item label="开始时间">
                    {formatDateTime(pipeline.startedAt)}
                  </Descriptions.Item>
                  <Descriptions.Item label="完成时间">
                    {formatDateTime(pipeline.completedAt)}
                  </Descriptions.Item>
                </Descriptions>

                <Typography.Title level={5}>评分阶段</Typography.Title>
                <Timeline
                  items={timelineItems.map((item) => ({
                    dot: stageIcon(item.stage?.status),
                    children: (
                      <Card
                        size="small"
                        type="inner"
                        title={
                          <Space>
                            <span>{item.title}</span>
                            {item.stage ? (
                              <Tag color={STAGE_STATUS_COLOR[item.stage.status]}>
                                {STAGE_STATUS_LABEL[item.stage.status]}
                              </Tag>
                            ) : (
                              <Tag>未创建</Tag>
                            )}
                          </Space>
                        }
                        style={{ marginBottom: 8 }}
                      >
                        {item.stage ? (
                          <Descriptions column={1} size="small">
                            <Descriptions.Item label="阶段分数">
                              {item.stage.stageScore === null ||
                              item.stage.stageScore === undefined
                                ? '-'
                                : item.stage.stageScore}
                            </Descriptions.Item>
                            <Descriptions.Item label="结果">
                              {item.stage.stageResult || '-'}
                            </Descriptions.Item>
                            <Descriptions.Item label="开始时间">
                              {formatDateTime(item.stage.startedAt)}
                            </Descriptions.Item>
                            <Descriptions.Item label="完成时间">
                              {formatDateTime(item.stage.completedAt)}
                            </Descriptions.Item>
                            {item.stage.errorMessage && (
                              <Descriptions.Item label="错误信息">
                                <Typography.Text type="danger">
                                  {item.stage.errorMessage}
                                </Typography.Text>
                              </Descriptions.Item>
                            )}
                          </Descriptions>
                        ) : (
                          <Typography.Text type="secondary">
                            {isRunning ? '排队等待中...' : '尚未执行'}
                          </Typography.Text>
                        )}
                      </Card>
                    ),
                  }))}
                />
              </>
            )}
          </Card>
        </Col>

        <Col xs={24} lg={8}>
          <Card title="流水线说明">
            <Typography.Paragraph>
              评分流水线包含三个阶段，依次执行：
            </Typography.Paragraph>
            <Timeline
              items={[
                {
                  children: (
                    <Typography.Text strong>1. 自动检查 (auto_check)</Typography.Text>
                  ),
                },
                {
                  children: (
                    <Typography.Text strong>2. 查重检测 (plagiarism_check)</Typography.Text>
                  ),
                },
                {
                  children: (
                    <Typography.Text strong>3. 人工复审 (manual_review)</Typography.Text>
                  ),
                },
              ]}
            />
            <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>
              {isRunning
                ? '流水线运行中，每 3 秒自动刷新状态...'
                : '流水线未运行，状态已停止自动刷新。'}
            </Typography.Paragraph>
          </Card>
        </Col>
      </Row>
    </Spin>
  );
}

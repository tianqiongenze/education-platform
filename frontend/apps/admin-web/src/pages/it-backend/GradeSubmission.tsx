import { useMemo } from 'react';
import {
  Button,
  Card,
  Col,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Row,
  Space,
  Spin,
  Tag,
  Typography,
  App as AntApp,
} from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useNavigate, useSearchParams, useParams } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getSubmission, gradeSubmission, ApiError } from '../../api/it-backend';
import type { Submission, SubmissionStatus } from '../../types/it-backend';

const { TextArea } = Input;

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

interface GradeFormValues {
  score: number;
  feedback?: string;
}

export default function GradeSubmissionPage() {
  const navigate = useNavigate();
  const { message } = AntApp.useApp();
  const qc = useQueryClient();
  const [searchParams] = useSearchParams();
  const { submissionId: pathSubmissionId } = useParams<{ submissionId: string }>();
  const [form] = Form.useForm<GradeFormValues>();

  // The route is /it-backend/grading/:submissionId/grade, so submissionId is in params.
  // We also support ?focus= for compatibility.
  const submissionIdStr = searchParams.get('focus') ?? pathSubmissionId ?? '';

  const submissionId = useMemo(() => {
    const n = Number(submissionIdStr);
    return Number.isFinite(n) && n > 0 ? n : undefined;
  }, [submissionIdStr]);

  const { data: submission, isLoading } = useQuery({
    queryKey: ['it-backend', 'submissions', 'detail', submissionId] as const,
    queryFn: async (): Promise<Submission> => {
      if (submissionId === undefined) {
        throw new Error('无效的提交 ID');
      }
      return getSubmission(submissionId);
    },
    enabled: submissionId !== undefined,
  });

  const gradeMutation = useMutation({
    mutationFn: (values: GradeFormValues) => {
      if (submissionId === undefined) {
        throw new Error('无效的提交 ID');
      }
      return gradeSubmission(submissionId, {
        score: values.score,
        feedback: values.feedback ?? undefined,
        idempotencyKey: `grade-${submissionId}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      });
    },
    onSuccess: (updated: Submission) => {
      message.success(`提交 #${updated.id} 评分成功，分数：${updated.score}`);
      qc.invalidateQueries({ queryKey: ['it-backend', 'submissions'] });
      qc.invalidateQueries({ queryKey: ['it-backend', 'grading'] });
      navigate(-1);
    },
    onError: (err) => {
      const msg = err instanceof ApiError ? err.message : '评分失败';
      message.error(msg);
    },
  });

  const canGrade =
    submission !== undefined &&
    (submission.status === 'SUBMITTED' || submission.status === 'IN_REVIEW');

  const onFinish = (values: GradeFormValues) => {
    gradeMutation.mutate(values);
  };

  if (isLoading) {
    return (
      <div className="ep-page-loading">
        <Spin size="large" />
      </div>
    );
  }

  if (!submission) {
    return (
      <div>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(-1)} style={{ marginBottom: 16 }}>
          返回
        </Button>
        <Typography.Text>提交不存在</Typography.Text>
      </div>
    );
  }

  return (
    <Spin spinning={gradeMutation.isPending}>
      <Button
        icon={<ArrowLeftOutlined />}
        onClick={() => navigate(-1)}
        style={{ marginBottom: 16 }}
      >
        返回
      </Button>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={14}>
          <Card title="提交内容">
            <Descriptions column={2} size="small" style={{ marginBottom: 16 }}>
              <Descriptions.Item label="提交 ID">{submission.id}</Descriptions.Item>
              <Descriptions.Item label="作业 ID">
                <a onClick={() => navigate(`/it-backend/assignments/${submission.assignmentId}`)}>
                  #{submission.assignmentId}
                </a>
              </Descriptions.Item>
              <Descriptions.Item label="学生 ID">{submission.studentId}</Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={STATUS_COLOR[submission.status]}>
                  {STATUS_LABEL[submission.status]}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="分数">
                {submission.score === null || submission.score === undefined
                  ? '-'
                  : submission.score}
              </Descriptions.Item>
              <Descriptions.Item label="提交时间">
                {formatDateTime(submission.submittedAt)}
              </Descriptions.Item>
            </Descriptions>

            <Typography.Title level={5}>作业内容</Typography.Title>
            <Card
              type="inner"
              style={{ marginBottom: 16, background: '#fafafa' }}
              bodyStyle={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}
            >
              {submission.content || '（无内容）'}
            </Card>

            {submission.feedback && (
              <>
                <Typography.Title level={5}>历史反馈</Typography.Title>
                <Card type="inner" style={{ background: '#fafafa' }}>
                  {submission.feedback}
                </Card>
              </>
            )}
          </Card>
        </Col>

        <Col xs={24} lg={10}>
          <Card title="评分">
            {!canGrade && (
              <Typography.Paragraph type="warning">
                当前提交状态为「{STATUS_LABEL[submission.status]}」，无法评分。
                仅「已提交」或「评审中」状态的提交可以评分。
              </Typography.Paragraph>
            )}
            <Form<GradeFormValues>
              form={form}
              layout="vertical"
              onFinish={onFinish}
              initialValues={{ score: submission.score ?? 0 }}
              disabled={!canGrade}
            >
              <Form.Item
                name="score"
                label="分数（0-100）"
                rules={[
                  { required: true, message: '请输入分数' },
                  {
                    type: 'number',
                    min: 0,
                    max: 100,
                    message: '分数需在 0-100 之间',
                  },
                ]}
              >
                <InputNumber style={{ width: '100%' }} min={0} max={100} precision={0} />
              </Form.Item>

              <Form.Item name="feedback" label="反馈">
                <TextArea
                  placeholder="给学生的反馈意见（可选）"
                  autoSize={{ minRows: 4, maxRows: 10 }}
                  maxLength={2000}
                />
              </Form.Item>

              <Form.Item>
                <Space>
                  <Button
                    type="primary"
                    htmlType="submit"
                    loading={gradeMutation.isPending}
                    disabled={!canGrade}
                  >
                    提交评分
                  </Button>
                  <Button onClick={() => form.resetFields()} disabled={!canGrade}>
                    重置
                  </Button>
                </Space>
              </Form.Item>
            </Form>
          </Card>
        </Col>
      </Row>
    </Spin>
  );
}

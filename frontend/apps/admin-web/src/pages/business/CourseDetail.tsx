import { useMemo } from 'react';
import {
  Button,
  Card,
  Col,
  Descriptions,
  Row,
  Space,
  Spin,
  Statistic,
  Tag,
  Typography,
  App as AntApp,
  Popconfirm,
} from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getCourseById,
  publishCourse,
  archiveCourse,
  ApiError,
} from '../../api/business';
import { courseSchema } from '../../types/business';
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

function formatDateTime(v: number | null | undefined): string {
  if (v === null || v === undefined) return '-';
  try {
    return new Date(v).toLocaleString('zh-CN');
  } catch {
    return String(v);
  }
}

/**
 * SP-03 Course Detail page.
 *
 * Fetches a single course via `getCourseById`, validates the response shape
 * with the `courseSchema` Zod schema, and renders course metadata plus
 * lifecycle actions (publish a DRAFT course, archive a PUBLISHED course).
 */
export default function CourseDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { message } = AntApp.useApp();
  const qc = useQueryClient();

  const courseId = useMemo(() => {
    const n = Number(id);
    return Number.isFinite(n) && n > 0 ? n : undefined;
  }, [id]);

  const { data: course, isLoading, isError, error } = useQuery({
    queryKey: ['business', 'courses', 'detail', courseId] as const,
    queryFn: async (): Promise<Course> => {
      if (courseId === undefined) {
        throw new Error('无效的课程 ID');
      }
      const raw = await getCourseById(courseId);
      const parsed = courseSchema.safeParse(raw);
      if (!parsed.success) {
        console.warn('[Zod] course response validation failed', parsed.error.issues);
        return raw;
      }
      return parsed.data;
    },
    enabled: courseId !== undefined,
  });

  const publishMutation = useMutation({
    mutationFn: (id: number) => publishCourse(id),
    onSuccess: (_data, id) => {
      message.success(`课程 #${id} 已发布`);
      qc.invalidateQueries({ queryKey: ['business', 'courses'] });
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
      qc.invalidateQueries({ queryKey: ['business', 'courses'] });
    },
    onError: (err) => {
      const msg = err instanceof ApiError ? err.message : '归档失败';
      message.error(msg);
    },
  });

  if (isError) {
    const msg = error instanceof ApiError ? error.message : '加载课程失败';
    message.error(msg);
  }

  if (isLoading) {
    return (
      <div className="ep-page-loading">
        <Spin size="large" />
      </div>
    );
  }

  if (!course) {
    return (
      <div>
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/business/courses')}
          style={{ marginBottom: 16 }}
        >
          返回列表
        </Button>
        <Typography.Text>课程不存在</Typography.Text>
      </div>
    );
  }

  return (
    <div>
      <Button
        icon={<ArrowLeftOutlined />}
        onClick={() => navigate('/business/courses')}
        style={{ marginBottom: 16 }}
      >
        返回列表
      </Button>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={16}>
          <Card
            title={
              <Space>
                <span>课程详情</span>
                <Tag color={STATUS_COLOR[course.status]}>
                  {STATUS_LABEL[course.status]}
                </Tag>
              </Space>
            }
          >
            <Descriptions column={2} bordered>
              <Descriptions.Item label="课程 ID">{course.courseId}</Descriptions.Item>
              <Descriptions.Item label="标题">{course.title}</Descriptions.Item>
              <Descriptions.Item label="教师 ID">{course.teacherId}</Descriptions.Item>
              <Descriptions.Item label="价格">
                {course.price ? `¥${course.price}` : '-'}
              </Descriptions.Item>
              <Descriptions.Item label="分类">
                {course.category ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="难度">
                {course.difficulty ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="学生数">
                {course.studentCount ?? 0}
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={STATUS_COLOR[course.status]}>
                  {STATUS_LABEL[course.status]}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="创建时间">
                {formatDateTime(course.createdAt)}
              </Descriptions.Item>
              <Descriptions.Item label="更新时间">
                {formatDateTime(course.updatedAt)}
              </Descriptions.Item>
              <Descriptions.Item label="描述" span={2}>
                {course.description ?? '-'}
              </Descriptions.Item>
            </Descriptions>

            <Space style={{ marginTop: 16 }}>
              {course.status === 0 && (
                <Popconfirm
                  title="确认发布该课程？"
                  onConfirm={() => publishMutation.mutate(course.courseId)}
                  okText="发布"
                  cancelText="取消"
                >
                  <Button type="primary" loading={publishMutation.isPending}>
                    发布课程
                  </Button>
                </Popconfirm>
              )}
              {course.status === 1 && (
                <Popconfirm
                  title="确认归档该课程？归档后学生将无法访问。"
                  onConfirm={() => archiveMutation.mutate(course.courseId)}
                  okText="归档"
                  cancelText="取消"
                >
                  <Button danger loading={archiveMutation.isPending}>
                    归档课程
                  </Button>
                </Popconfirm>
              )}
            </Space>
          </Card>
        </Col>

        <Col xs={24} lg={8}>
          <Card title="课程统计">
            <Row gutter={16}>
              <Col span={12}>
                <Statistic
                  title="学生数"
                  value={course.studentCount ?? 0}
                />
              </Col>
              <Col span={12}>
                <Statistic
                  title="价格"
                  value={course.price ?? '0'}
                  prefix="¥"
                />
              </Col>
            </Row>
          </Card>
        </Col>
      </Row>
    </div>
  );
}

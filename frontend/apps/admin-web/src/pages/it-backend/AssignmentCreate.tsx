import { useState } from 'react';
import {
  Button,
  Card,
  Col,
  Form,
  Input,
  InputNumber,
  Row,
  Space,
  DatePicker,
  App as AntApp,
} from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import dayjs, { type Dayjs } from 'dayjs';
import { createAssignment, ApiError } from '../../api/it-backend';
import type { AssignmentCommand } from '../../types/it-backend';

const { TextArea } = Input;

interface FormValues {
  title: string;
  description?: string;
  courseId: number;
  teacherId?: number;
  maxScore?: number;
  deadline: Dayjs;
}

export default function AssignmentCreatePage() {
  const navigate = useNavigate();
  const { message } = AntApp.useApp();
  const [form] = Form.useForm<FormValues>();
  const [submitting, setSubmitting] = useState(false);

  const mutation = useMutation({
    mutationFn: (cmd: AssignmentCommand) => createAssignment(cmd),
  });

  const onFinish = async (values: FormValues) => {
    setSubmitting(true);
    try {
      const cmd: AssignmentCommand = {
        title: values.title,
        description: values.description,
        courseId: values.courseId,
        teacherId: values.teacherId,
        maxScore: values.maxScore,
        deadline: values.deadline.toISOString(),
      };
      const assignment = await mutation.mutateAsync(cmd);
      message.success(`作业 #${assignment.id} 创建成功`);
      navigate('/it-backend/assignments');
    } catch (err) {
      const msg = err instanceof ApiError ? err.message : '创建失败';
      message.error(msg);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div>
      <Button
        icon={<ArrowLeftOutlined />}
        onClick={() => navigate('/it-backend/assignments')}
        style={{ marginBottom: 16 }}
      >
        返回列表
      </Button>

      <Card title="创建作业">
        <Form<FormValues>
          form={form}
          layout="vertical"
          onFinish={onFinish}
          initialValues={{ maxScore: 100 }}
          style={{ maxWidth: 640 }}
        >
          <Form.Item
            name="title"
            label="标题"
            rules={[
              { required: true, message: '请输入作业标题' },
              { whitespace: true, message: '标题不能为空白字符' },
            ]}
          >
            <Input placeholder="请输入作业标题" maxLength={200} />
          </Form.Item>

          <Form.Item name="description" label="描述">
            <TextArea
              placeholder="作业要求、说明等（可选）"
              autoSize={{ minRows: 3, maxRows: 8 }}
              maxLength={2000}
            />
          </Form.Item>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="courseId"
                label="课程 ID"
                rules={[
                  { required: true, message: '请输入课程 ID' },
                  {
                    type: 'number',
                    min: 1,
                    message: '课程 ID 必须为正整数',
                  },
                ]}
              >
                <InputNumber
                  style={{ width: '100%' }}
                  placeholder="如 1001"
                  min={1}
                  precision={0}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="teacherId"
                label="教师 ID"
                rules={[
                  {
                    type: 'number',
                    min: 1,
                    message: '教师 ID 必须为正整数',
                  },
                ]}
              >
                <InputNumber
                  style={{ width: '100%' }}
                  placeholder="如 2001（可选）"
                  min={1}
                  precision={0}
                />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="maxScore"
                label="满分分值"
                rules={[
                  { required: true, message: '请输入满分分值' },
                  {
                    type: 'number',
                    min: 1,
                    max: 1000,
                    message: '满分分值需在 1-1000 之间',
                  },
                ]}
              >
                <InputNumber style={{ width: '100%' }} min={1} max={1000} precision={0} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="deadline"
                label="截止时间"
                rules={[
                  { required: true, message: '请选择截止时间' },
                  {
                    validator: (_, value: Dayjs) => {
                      if (!value) return Promise.resolve();
                      if (value.isAfter(dayjs())) {
                        return Promise.resolve();
                      }
                      return Promise.reject(new Error('截止时间必须晚于当前时间'));
                    },
                  },
                ]}
              >
                <DatePicker
                  showTime
                  style={{ width: '100%' }}
                  placeholder="选择截止时间"
                />
              </Form.Item>
            </Col>
          </Row>

          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" loading={submitting}>
                创建作业
              </Button>
              <Button
                onClick={() => {
                  form.resetFields();
                }}
              >
                重置
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
}

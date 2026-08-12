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
  App as AntApp,
} from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { createCourse, ApiError } from '../../api/business';
import type { CreateCourseCmd } from '../../types/business';

const { TextArea } = Input;

interface FormValues {
  title: string;
  description?: string;
  teacherId: number;
  category?: number;
  difficulty?: number;
  price?: string;
}

export default function CourseCreatePage() {
  const navigate = useNavigate();
  const { message } = AntApp.useApp();
  const [form] = Form.useForm<FormValues>();
  const [submitting, setSubmitting] = useState(false);

  const mutation = useMutation({
    mutationFn: (cmd: CreateCourseCmd) => createCourse(cmd),
  });

  const onFinish = async (values: FormValues) => {
    setSubmitting(true);
    try {
      const cmd: CreateCourseCmd = {
        title: values.title,
        description: values.description ?? null,
        teacherId: values.teacherId,
        category: values.category ?? null,
        difficulty: values.difficulty ?? null,
        price: values.price ?? null,
      };
      const course = await mutation.mutateAsync(cmd);
      message.success(`课程 #${course.courseId} 创建成功`);
      navigate('/business/courses');
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
        onClick={() => navigate('/business/courses')}
        style={{ marginBottom: 16 }}
      >
        返回列表
      </Button>

      <Card title="创建课程">
        <Form<FormValues>
          form={form}
          layout="vertical"
          onFinish={onFinish}
          style={{ maxWidth: 640 }}
        >
          <Form.Item
            name="title"
            label="课程标题"
            rules={[
              { required: true, message: '请输入课程标题' },
              { whitespace: true, message: '标题不能为空白字符' },
            ]}
          >
            <Input placeholder="请输入课程标题" maxLength={200} />
          </Form.Item>

          <Form.Item name="description" label="课程描述">
            <TextArea
              placeholder="课程简介、大纲等（可选）"
              autoSize={{ minRows: 3, maxRows: 8 }}
              maxLength={2000}
            />
          </Form.Item>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="teacherId"
                label="教师 ID"
                rules={[
                  { required: true, message: '请输入教师 ID' },
                  {
                    type: 'number',
                    min: 1,
                    message: '教师 ID 必须为正整数',
                  },
                ]}
              >
                <InputNumber
                  style={{ width: '100%' }}
                  placeholder="如 2001"
                  min={1}
                  precision={0}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="price" label="价格（元）">
                <Input placeholder="如 99.00" />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="category"
                label="分类"
                rules={[
                  {
                    type: 'number',
                    min: 0,
                    message: '分类必须为非负整数',
                  },
                ]}
              >
                <InputNumber
                  style={{ width: '100%' }}
                  placeholder="如 1（可选）"
                  min={0}
                  precision={0}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="difficulty"
                label="难度"
                rules={[
                  {
                    type: 'number',
                    min: 0,
                    message: '难度必须为非负整数',
                  },
                ]}
              >
                <InputNumber
                  style={{ width: '100%' }}
                  placeholder="如 1（可选）"
                  min={0}
                  precision={0}
                />
              </Form.Item>
            </Col>
          </Row>

          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" loading={submitting}>
                创建课程
              </Button>
              <Button onClick={() => form.resetFields()}>重置</Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
}

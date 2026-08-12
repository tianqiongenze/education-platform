import { useState } from 'react';
import {
  Button,
  Card,
  Col,
  Form,
  Input,
  Row,
  Space,
  App as AntApp,
} from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { createUser, ApiError } from '../../api/business';
import type { CreateUserCmd } from '../../types/business';

interface FormValues {
  username: string;
  phone: string;
  email: string;
  password: string;
  nickname: string;
}

export default function UserCreatePage() {
  const navigate = useNavigate();
  const { message } = AntApp.useApp();
  const [form] = Form.useForm<FormValues>();
  const [submitting, setSubmitting] = useState(false);

  const mutation = useMutation({
    mutationFn: (cmd: CreateUserCmd) => createUser(cmd),
  });

  const onFinish = async (values: FormValues) => {
    setSubmitting(true);
    try {
      const cmd: CreateUserCmd = {
        username: values.username,
        phone: values.phone,
        email: values.email,
        password: values.password,
        nickname: values.nickname,
      };
      const user = await mutation.mutateAsync(cmd);
      message.success(`用户 #${user.userId} 创建成功`);
      navigate('/business/users');
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
        onClick={() => navigate('/business/users')}
        style={{ marginBottom: 16 }}
      >
        返回列表
      </Button>

      <Card title="创建用户">
        <Form<FormValues>
          form={form}
          layout="vertical"
          onFinish={onFinish}
          style={{ maxWidth: 640 }}
        >
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="username"
                label="用户名"
                rules={[
                  { required: true, message: '请输入用户名' },
                  { whitespace: true, message: '用户名不能为空白字符' },
                ]}
              >
                <Input placeholder="请输入用户名" maxLength={64} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="nickname"
                label="昵称"
                rules={[{ whitespace: true, message: '昵称不能为空白字符' }]}
              >
                <Input placeholder="如 张三（可选）" maxLength={64} />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="phone"
                label="手机号"
                rules={[
                  { required: true, message: '请输入手机号' },
                  {
                    pattern: /^1[3-9]\d{9}$/,
                    message: '请输入有效的手机号',
                  },
                ]}
              >
                <Input placeholder="如 13800138000" maxLength={11} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="email"
                label="邮箱"
                rules={[
                  { required: true, message: '请输入邮箱' },
                  { type: 'email', message: '请输入有效的邮箱地址' },
                ]}
              >
                <Input placeholder="如 user@example.com" maxLength={128} />
              </Form.Item>
            </Col>
          </Row>

          <Form.Item
            name="password"
            label="密码"
            rules={[
              { required: true, message: '请输入密码' },
              { min: 6, message: '密码至少 6 位' },
            ]}
          >
            <Input.Password placeholder="请输入密码" maxLength={64} />
          </Form.Item>

          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit" loading={submitting}>
                创建用户
              </Button>
              <Button onClick={() => form.resetFields()}>重置</Button>
            </Space>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
}

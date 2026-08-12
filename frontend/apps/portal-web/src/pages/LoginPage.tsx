import { Button, Card, Form, Input, Typography, message } from 'antd';
import { Link, useNavigate } from 'react-router-dom';
import { useLogin } from '@ep/query';
import { apiClient } from '../api/client';
import type { LoginInput } from '@ep/query';

interface LoginForm extends LoginInput {}

export default function LoginPage() {
  const navigate = useNavigate();
  const loginMutation = useLogin(apiClient);

  const onFinish = async (values: LoginForm) => {
    try {
      await loginMutation.mutateAsync(values);
      message.success('登录成功');
      // Redirect: role-based routing is handled by the downstream apps.
      // Portal sends the user to the admin portal by default; students
      // typically enter through student-h5 directly.
      navigate('/');
    } catch (err) {
      const msg = err instanceof Error ? err.message : '登录失败，请重试';
      message.error(msg);
    }
  };

  return (
    <div
      style={{
        minHeight: 'calc(100vh - 64px - 200px)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: '#f5f5f5',
        padding: 24,
      }}
    >
      <Card style={{ width: 400 }}>
        <Typography.Title level={3} style={{ textAlign: 'center', marginBottom: 8 }}>
          智教云统一登录
        </Typography.Title>
        <Typography.Paragraph type="secondary" style={{ textAlign: 'center', marginBottom: 24 }}>
          登录后可访问全部事业群
        </Typography.Paragraph>

        <Form<LoginForm>
          name="portal-login"
          layout="vertical"
          onFinish={onFinish}
          autoComplete="off"
        >
          <Form.Item
            label="用户名"
            name="username"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input placeholder="请输入用户名" size="large" />
          </Form.Item>
          <Form.Item
            label="密码"
            name="password"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password placeholder="请输入密码" size="large" />
          </Form.Item>
          <Form.Item style={{ marginBottom: 12 }}>
            <Button
              type="primary"
              htmlType="submit"
              size="large"
              block
              loading={loginMutation.isPending}
            >
              登录
            </Button>
          </Form.Item>
        </Form>

        <div style={{ textAlign: 'center' }}>
          <Link to="/">
            <Button type="link">返回首页</Button>
          </Link>
        </div>
      </Card>
    </div>
  );
}

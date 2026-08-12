import { useState } from 'react';
import { Card, Form, Input, Button, Typography, App as AntApp } from 'antd';
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useLogin } from '@ep/query';
import { STORAGE_KEYS } from '@ep/shared';
import { apiClient } from '../../api/client';
import { useUserStore } from '../../stores/UserContext';
import type { User } from '@ep/shared';

const { Title } = Typography;

interface LoginFormValues {
  username: string;
  password: string;
}

export default function LoginPage() {
  const navigate = useNavigate();
  const { message } = AntApp.useApp();
  const { setUser } = useUserStore();
  const loginMutation = useLogin(apiClient);
  const [submitting, setSubmitting] = useState(false);

  const onFinish = async (values: LoginFormValues) => {
    setSubmitting(true);
    try {
      const res = await loginMutation.mutateAsync(values);
      localStorage.setItem(STORAGE_KEYS.ACCESS_TOKEN, res.accessToken);
      localStorage.setItem(STORAGE_KEYS.REFRESH_TOKEN, res.refreshToken);
      const profile = await apiClient.get<User>('/api/user/profile');
      setUser(profile);
      message.success('登录成功');
      navigate('/dashboard');
    } catch (err) {
      const msg = err instanceof Error ? err.message : '登录失败';
      message.error(msg);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: '#f0f2f5',
      }}
    >
      <Card style={{ width: 400, boxShadow: '0 2px 8px rgba(0,0,0,0.08)' }}>
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <Title level={3} style={{ marginBottom: 0 }}>
            EP Admin
          </Title>
          <Typography.Text type="secondary">教育平台管理后台</Typography.Text>
        </div>
        <Form<LoginFormValues>
          name="login"
          layout="vertical"
          onFinish={onFinish}
          autoComplete="off"
        >
          <Form.Item
            label="用户名"
            name="username"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input prefix={<UserOutlined />} placeholder="用户名" />
          </Form.Item>
          <Form.Item
            label="密码"
            name="password"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password prefix={<LockOutlined />} placeholder="密码" />
          </Form.Item>
          <Form.Item>
            <Button
              type="primary"
              htmlType="submit"
              block
              loading={submitting || loginMutation.isPending}
            >
              登录
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
}

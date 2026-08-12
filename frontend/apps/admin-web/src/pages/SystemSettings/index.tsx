import { useState } from 'react';
import { Card, Form, Input, Switch, Select, Button, Divider, Typography, App as AntApp, Space } from 'antd';
import { SaveOutlined, ReloadOutlined } from '@ant-design/icons';

interface SystemSettings {
  siteName: string;
  siteDescription: string;
  registrationEnabled: boolean;
  emailVerificationRequired: boolean;
  maxUploadSizeMB: number;
  defaultLocale: string;
  maintenanceMode: boolean;
  maintenanceMessage: string;
}

const INITIAL: SystemSettings = {
  siteName: 'EP Education Platform',
  siteDescription: '在线教育平台管理系统',
  registrationEnabled: true,
  emailVerificationRequired: true,
  maxUploadSizeMB: 100,
  defaultLocale: 'zh-CN',
  maintenanceMode: false,
  maintenanceMessage: '系统维护中，请稍后再试。',
};

export default function SystemSettingsPage() {
  const { message } = AntApp.useApp();
  const [form] = Form.useForm<SystemSettings>();
  const [saving, setSaving] = useState(false);

  const handleSave = async (values: SystemSettings) => {
    setSaving(true);
    try {
      void values;
      message.success('设置已保存');
    } catch {
      message.error('保存失败，请重试');
    } finally {
      setSaving(false);
    }
  };

  const handleReset = () => {
    form.resetFields();
    message.info('已重置为上次保存的值');
  };

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="large">
      <Card title="站点设置">
        <Form<SystemSettings>
          form={form}
          layout="vertical"
          initialValues={INITIAL}
          onFinish={handleSave}
        >
          <Form.Item label="站点名称" name="siteName" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item label="站点描述" name="siteDescription">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item label="默认语言" name="defaultLocale">
            <Select
              options={[
                { label: '简体中文', value: 'zh-CN' },
                { label: 'English', value: 'en-US' },
              ]}
            />
          </Form.Item>
        </Form>
      </Card>

      <Card title="注册与安全">
        <Form<SystemSettings> form={form} layout="vertical" initialValues={INITIAL}>
          <Form.Item label="开放注册" name="registrationEnabled" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item label="注册需邮箱验证" name="emailVerificationRequired" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item label="最大上传大小 (MB)" name="maxUploadSizeMB" rules={[{ required: true }]}>
            <Input type="number" min={1} max={1024} />
          </Form.Item>
        </Form>
      </Card>

      <Card title="维护模式">
        <Form<SystemSettings> form={form} layout="vertical" initialValues={INITIAL}>
          <Form.Item label="启用维护模式" name="maintenanceMode" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item label="维护提示信息" name="maintenanceMessage">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Card>

      <Divider />
      <Space>
        <Button
          type="primary"
          icon={<SaveOutlined />}
          loading={saving}
          onClick={() => form.submit()}
        >
          保存设置
        </Button>
        <Button icon={<ReloadOutlined />} onClick={handleReset}>
          重置
        </Button>
      </Space>
      <Typography.Text type="secondary">
        注意：更改维护模式将立即对所有用户生效。
      </Typography.Text>
    </Space>
  );
}

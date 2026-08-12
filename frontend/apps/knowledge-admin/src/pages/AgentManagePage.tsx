import { useState, useEffect } from 'react';
import {
  Card,
  Table,
  Button,
  Modal,
  Form,
  Input,
  Select,
  InputNumber,
  Tag,
  Space,
  Tabs,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { PlusOutlined } from '@ant-design/icons';
import { listAgents, createAgent, agentChat } from '../api/knowledge';
import { z } from 'zod';
import type { Agent, CreateAgentInput, AgentChatMessage } from '../types/knowledge';

const createAgentSchema = z.object({
  name: z.string().min(1, '请输入名称'),
  description: z.string().min(1, '请输入描述'),
  knowledgeBaseIds: z.array(z.string()).min(1, '至少选择一个知识库'),
  model: z.string().min(1, '请选择模型'),
  temperature: z.number().min(0).max(2),
});

export default function AgentManagePage() {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<{ items: Agent[]; total: number }>({
    items: [],
    total: 0,
  });
  const [modalOpen, setModalOpen] = useState(false);
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);
  const [chatMessages, setChatMessages] = useState<Record<string, AgentChatMessage[]>>({});
  const [chatInput, setChatInput] = useState('');
  const [activeAgent, setActiveAgent] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    listAgents()
      .then(setData)
      .catch(() => setData({ items: [], total: 0 }))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
  }, []);

  const handleCreate = async () => {
    try {
      const values = await form.validateFields();
      createAgentSchema.parse(values as CreateAgentInput);
      setSubmitting(true);
      await createAgent(values as CreateAgentInput);
      setModalOpen(false);
      form.resetFields();
      load();
    } catch (err) {
      if (err instanceof z.ZodError) {
        void form.setFields([{ name: 'name', errors: [err.issues[0]?.message ?? ''] }]);
      }
    } finally {
      setSubmitting(false);
    }
  };

  const handleChat = async () => {
    if (!activeAgent || !chatInput.trim()) return;
    const msgs = chatMessages[activeAgent] ?? [];
    setChatMessages({
      ...chatMessages,
      [activeAgent]: [...msgs, { role: 'user', content: chatInput }],
    });
    const input = chatInput;
    setChatInput('');
    try {
      const res = await agentChat({ agentId: activeAgent, message: input });
      setChatMessages((prev) => ({
        ...prev,
        [activeAgent]: [
          ...(prev[activeAgent] ?? []),
          { role: 'assistant', content: res.answer },
        ],
      }));
    } catch {
      setChatMessages((prev) => ({
        ...prev,
        [activeAgent]: [
          ...(prev[activeAgent] ?? []),
          { role: 'assistant', content: '对话失败，请重试。' },
        ],
      }));
    }
  };

  const columns: ColumnsType<Agent> = [
    { title: '名称', dataIndex: 'name', key: 'name' },
    { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
    { title: '模型', dataIndex: 'model', key: 'model', width: 140 },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (s: string) => (
        <Tag color={s === 'enabled' ? 'green' : 'default'}>
          {s === 'enabled' ? '启用' : '禁用'}
        </Tag>
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 120,
      render: (_, record) => (
        <Button type="link" onClick={() => setActiveAgent(record.id)}>
          会话测试
        </Button>
      ),
    },
  ];

  return (
    <Card
      title="智能体管理"
      extra={
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>
          新建智能体
        </Button>
      }
    >
      <Tabs
        items={[
          {
            key: 'list',
            label: 'Agent 列表',
            children: (
              <Table<Agent>
                rowKey="id"
                columns={columns}
                dataSource={data.items}
                loading={loading}
                pagination={{ pageSize: 10 }}
              />
            ),
          },
          {
            key: 'chat',
            label: '会话测试',
            children: activeAgent ? (
              <div style={{ maxWidth: 720 }}>
                {(chatMessages[activeAgent] ?? []).map((m, i) => (
                  <div
                    key={i}
                    style={{
                      textAlign: m.role === 'user' ? 'right' : 'left',
                      margin: '8px 0',
                    }}
                  >
                    <Tag color={m.role === 'user' ? 'blue' : 'green'}>
                      {m.role === 'user' ? '我' : 'AI'}
                    </Tag>
                    <div>{m.content}</div>
                  </div>
                ))}
                <Space.Compact style={{ width: '100%', marginTop: 12 }}>
                  <Input
                    placeholder="提问..."
                    value={chatInput}
                    onChange={(e) => setChatInput(e.target.value)}
                    onPressEnter={handleChat}
                  />
                  <Button type="primary" onClick={handleChat}>
                    发送
                  </Button>
                </Space.Compact>
              </div>
            ) : (
              <div>请从列表中点击"会话测试"选择一个 Agent。</div>
            ),
          },
        ]}
      />

      <Modal
        title="新建智能体"
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={handleCreate}
        confirmLoading={submitting}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="name" label="名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label="描述" rules={[{ required: true }]}>
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="knowledgeBaseIds" label="知识库" rules={[{ required: true }]}>
            <Select mode="multiple" placeholder="选择知识库" />
          </Form.Item>
          <Form.Item name="model" label="模型" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'gpt-4o', label: 'GPT-4o' },
                { value: 'gpt-4o-mini', label: 'GPT-4o mini' },
                { value: 'qwen-max', label: '通义千问 Max' },
              ]}
            />
          </Form.Item>
          <Form.Item name="temperature" label="Temperature" initialValue={0.7}>
            <InputNumber min={0} max={2} step={0.1} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}

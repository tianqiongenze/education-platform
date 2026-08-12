import { useState } from 'react';
import { Card, Upload, Form, Select, Input, Button, message, Space } from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import { uploadDocument } from '../api/knowledge';
import { z } from 'zod';
import type { DocumentFormat, KnowledgeBaseCategory } from '../types/knowledge';

const { Dragger } = Upload;

const uploadSchema = z.object({
  knowledgeBaseId: z.string().min(1, '请选择知识库'),
  title: z.string().min(1, '请输入文档标题'),
  format: z.enum(['pdf', 'docx', 'markdown', 'html', 'txt']),
  category: z.enum(['technical', 'product', 'service']),
  tags: z.array(z.string()).default([]),
});

export default function DocumentUploadPage() {
  const [form] = Form.useForm();
  const [file, setFile] = useState<File | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const handleUpload = async () => {
    try {
      const values = await form.validateFields();
      if (!file) {
        void message.warning('请选择文件');
        return;
      }
      uploadSchema.parse(values);
      setSubmitting(true);
      await uploadDocument({ ...values, file });
      void message.success('文档上传成功');
      form.resetFields();
      setFile(null);
    } catch (err) {
      if (err instanceof z.ZodError) {
        void message.error(err.issues[0]?.message ?? '校验失败');
      } else {
        void message.error('上传失败');
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card title="文档上传">
      <Form form={form} layout="vertical" style={{ maxWidth: 600 }}>
        <Form.Item name="knowledgeBaseId" label="知识库" rules={[{ required: true }]}>
          <Select placeholder="选择知识库" />
        </Form.Item>
        <Form.Item name="title" label="文档标题" rules={[{ required: true }]}>
          <Input placeholder="请输入文档标题" />
        </Form.Item>
        <Form.Item name="format" label="格式" rules={[{ required: true }]}>
          <Select<DocumentFormat>
            placeholder="选择格式"
            options={[
              { value: 'pdf', label: 'PDF' },
              { value: 'docx', label: 'DOCX' },
              { value: 'markdown', label: 'Markdown' },
              { value: 'html', label: 'HTML' },
              { value: 'txt', label: 'TXT' },
            ]}
          />
        </Form.Item>
        <Form.Item name="category" label="分类" rules={[{ required: true }]}>
          <Select<KnowledgeBaseCategory>
            placeholder="选择分类"
            options={[
              { value: 'technical', label: '技术' },
              { value: 'product', label: '产品' },
              { value: 'service', label: '客服' },
            ]}
          />
        </Form.Item>
        <Form.Item label="文件">
          <Dragger
            beforeUpload={(f) => {
              setFile(f);
              return false;
            }}
            maxCount={1}
          >
            <p className="ant-upload-drag-icon">
              <InboxOutlined />
            </p>
            <p className="ant-upload-text">点击或拖拽文件到此区域上传</p>
          </Dragger>
        </Form.Item>
        <Space>
          <Button type="primary" loading={submitting} onClick={handleUpload}>
            上传
          </Button>
        </Space>
      </Form>
    </Card>
  );
}

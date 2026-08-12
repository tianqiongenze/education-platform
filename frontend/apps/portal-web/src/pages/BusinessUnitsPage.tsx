import { Button, Card, Col, Row, Typography } from 'antd';
import {
  AppstoreOutlined,
  CodeOutlined,
  BookOutlined,
  ArrowRightOutlined,
} from '@ant-design/icons';
import type { ReactNode } from 'react';

interface BusinessUnit {
  key: string;
  title: string;
  desc: string;
  icon: ReactNode;
  /** Manage-side entry (admin). */
  adminLabel: string;
  adminUrl: string;
  /** Learning-side entry (student). */
  learnLabel: string;
  learnUrl: string;
}

function envUrl(key: keyof ImportMetaEnv, fallback: string): string {
  const value = import.meta.env[key];
  return typeof value === 'string' && value ? value : fallback;
}

export default function BusinessUnitsPage() {
  const units: BusinessUnit[] = [
    {
      key: 'business',
      title: '业务中台',
      desc: '统一用户、课程、订单、支付管理后台，面向运营与教务人员。',
      icon: <AppstoreOutlined style={{ fontSize: 40, color: '#1677ff' }} />,
      adminLabel: '进入管理',
      adminUrl: envUrl('VITE_ADMIN_WEB_URL', '/admin'),
      learnLabel: '进入学习',
      learnUrl: envUrl('VITE_STUDENT_H5_URL', '/h5'),
    },
    {
      key: 'it',
      title: 'IT 培训',
      desc: '编程训练场、在线判题 (OJ)、作业与评测，面向 IT 技能提升。',
      icon: <CodeOutlined style={{ fontSize: 40, color: '#722ed1' }} />,
      adminLabel: '进入管理',
      adminUrl: envUrl('VITE_ADMIN_WEB_URL', '/admin'),
      learnLabel: '开始训练',
      learnUrl: envUrl('VITE_STUDENT_H5_URL', '/h5'),
    },
    {
      key: 'knowledge',
      title: '知识中台',
      desc: '知识库内容生产、审核与多端分发，沉淀企业知识资产。',
      icon: <BookOutlined style={{ fontSize: 40, color: '#13c2c2' }} />,
      adminLabel: '进入管理',
      adminUrl: envUrl('VITE_KNOWLEDGE_ADMIN_URL', '/knowledge-admin'),
      learnLabel: '进入学习',
      learnUrl: envUrl('VITE_KNOWLEDGE_H5_URL', '/knowledge-h5'),
    },
  ];

  return (
    <div className="portal-section">
      <Typography.Title level={2} className="portal-section-title">
        事业群入口
      </Typography.Title>
      <Typography.Paragraph className="portal-section-subtitle">
        选择对应事业群，直达管理系统或学习端
      </Typography.Paragraph>

      <Row gutter={[24, 24]}>
        {units.map((u) => (
          <Col xs={24} md={8} key={u.key}>
            <Card hoverable style={{ height: '100%' }}>
              {u.icon}
              <Typography.Title level={3} style={{ marginTop: 16 }}>
                {u.title}
              </Typography.Title>
              <Typography.Paragraph type="secondary">{u.desc}</Typography.Paragraph>
              <div style={{ display: 'flex', gap: 12, marginTop: 16 }}>
                <a href={u.adminUrl}>
                  <Button type="primary">
                    {u.adminLabel} <ArrowRightOutlined />
                  </Button>
                </a>
                <a href={u.learnUrl}>
                  <Button>
                    {u.learnLabel} <ArrowRightOutlined />
                  </Button>
                </a>
              </div>
            </Card>
          </Col>
        ))}
      </Row>
    </div>
  );
}

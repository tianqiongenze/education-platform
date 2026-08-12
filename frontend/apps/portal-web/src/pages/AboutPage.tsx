import { Card, Col, Row, Typography } from 'antd';
import {
  RocketOutlined,
  SafetyCertificateOutlined,
  CloudServerOutlined,
  TeamOutlined,
} from '@ant-design/icons';

const FEATURES = [
  {
    icon: <RocketOutlined style={{ fontSize: 32, color: '#1677ff' }} />,
    title: '高性能架构',
    desc: '多单元异地多活、TiDB 分布式数据库、K8s 弹性伸缩，支撑高并发在线学习。',
  },
  {
    icon: <SafetyCertificateOutlined style={{ fontSize: 32, color: '#52c41a' }} />,
    title: '安全合规',
    desc: '统一鉴权、JWT 刷新、限流熔断与全链路可观测，保障平台与数据安全。',
  },
  {
    icon: <CloudServerOutlined style={{ fontSize: 32, color: '#722ed1' }} />,
    title: '云原生部署',
    desc: 'GitOps (ArgoCD) + Karmada 多集群调度，灰度发布与一键回滚。',
  },
  {
    icon: <TeamOutlined style={{ fontSize: 32, color: '#13c2c2' }} />,
    title: '全场景覆盖',
    desc: 'PC、H5、小程序、管理后台、知识中台，多端统一学习体验。',
  },
];

export default function AboutPage() {
  return (
    <div className="portal-section">
      <Typography.Title level={2} className="portal-section-title">
        关于智教云
      </Typography.Title>
      <Typography.Paragraph className="portal-section-subtitle">
        让优质教育触手可及
      </Typography.Paragraph>

      <Typography.Paragraph style={{ maxWidth: 800, margin: '0 auto 48px', fontSize: 16 }}>
        智教云是一个面向企业与个人的全场景在线教育平台，整合公开课、IT 培训、知识中台等业务，
        提供从内容生产、分发到学习评测的一站式解决方案。我们致力于用云原生与 AI 技术，
        打造稳定、安全、可扩展的在线教育基础设施。
      </Typography.Paragraph>

      <Typography.Title level={3} style={{ textAlign: 'center', marginBottom: 32 }}>
        平台特性
      </Typography.Title>
      <Row gutter={[24, 24]}>
        {FEATURES.map((f) => (
          <Col xs={24} sm={12} md={6} key={f.title}>
            <Card hoverable style={{ height: '100%' }}>
              {f.icon}
              <Typography.Title level={4} style={{ marginTop: 16 }}>
                {f.title}
              </Typography.Title>
              <Typography.Paragraph type="secondary">{f.desc}</Typography.Paragraph>
            </Card>
          </Col>
        ))}
      </Row>

      <Typography.Title level={3} style={{ textAlign: 'center', marginTop: 64, marginBottom: 24 }}>
        技术亮点
      </Typography.Title>
      <Row gutter={[24, 16]} justify="center">
        {[
          'React 19 + TypeScript',
          'NestJS / Spring Boot Dubbo',
          'TiDB 分布式数据库',
          'K8s + Karmada 多集群',
          'ArgoCD GitOps',
          'OpenTelemetry 全链路追踪',
        ].map((tech) => (
          <Col key={tech}>
            <Card size="small">{tech}</Card>
          </Col>
        ))}
      </Row>
    </div>
  );
}

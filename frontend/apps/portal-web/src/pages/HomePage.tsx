import { Button, Card, Col, Row, Statistic, Tag, Typography, Space } from 'antd';
import { Link } from 'react-router-dom';
import {
  AppstoreOutlined,
  CodeOutlined,
  BookOutlined,
  ArrowRightOutlined,
} from '@ant-design/icons';
import { useCourseList } from '@ep/query';
import { apiClient } from '../api/client';
import { MOCK_COURSES } from '../data/mockCourses';

const FEATURED_COURSES = MOCK_COURSES.slice(0, 4);

const BUSINESS_UNITS = [
  {
    key: 'business',
    title: '业务中台',
    desc: '订单、支付、用户、课程管理一体化后台 (admin-web)。',
    icon: <AppstoreOutlined style={{ fontSize: 32, color: '#1677ff' }} />,
  },
  {
    key: 'it',
    title: 'IT 培训',
    desc: 'IT 后端能力、编程训练与作业评测 (sp05-itbackend)。',
    icon: <CodeOutlined style={{ fontSize: 32, color: '#722ed1' }} />,
  },
  {
    key: 'knowledge',
    title: '知识中台',
    desc: '知识库、内容管理与分发 (knowledge-admin)。',
    icon: <BookOutlined style={{ fontSize: 32, color: '#13c2c2' }} />,
  },
];

export default function HomePage() {
  // Try the real API; fall back to static featured courses when empty/error.
  const { data, isLoading, isError } = useCourseList(apiClient, { page: 1, pageSize: 4 });
  const liveCourses = data?.items ?? [];
  const courses = liveCourses.length > 0 ? liveCourses : FEATURED_COURSES;

  return (
    <div>
      {/* Hero */}
      <section className="portal-hero" data-testid="hero-section">
        <h1>智教云 — 全场景在线教育平台</h1>
        <p>公开课统一平台 · 事业群导航 · 一站式学习入口</p>
        <div>
          <Link to="/courses">
            <Button type="default" size="large" data-testid="hero-courses-btn">
              浏览课程
            </Button>
          </Link>
          <Link to="/login">
            <Button type="primary" size="large" ghost data-testid="hero-login-btn">
              立即登录
            </Button>
          </Link>
        </div>
      </section>

      {/* Stats */}
      <section className="portal-section portal-stats">
        <Row gutter={[48, 24]} justify="center">
          <Col xs={12} md={6}>
            <Statistic title="优质课程" value={1000} suffix="+" />
          </Col>
          <Col xs={12} md={6}>
            <Statistic title="注册学员" value={50000} suffix="+" />
          </Col>
          <Col xs={12} md={6}>
            <Statistic title="资深讲师" value={100} suffix="+" />
          </Col>
          <Col xs={12} md={6}>
            <Statistic title="企业客户" value={200} suffix="+" />
          </Col>
        </Row>
      </section>

      {/* Featured courses */}
      <section className="portal-section">
        <Typography.Title level={2} className="portal-section-title">
          精选课程
        </Typography.Title>
        <Typography.Paragraph className="portal-section-subtitle">
          {isLoading
            ? '正在加载…'
            : isError
              ? '正在展示推荐课程'
              : '热门公开课，从入门到精通'}
        </Typography.Paragraph>
        <Row gutter={[24, 24]}>
          {courses.map((course) => (
            <Col xs={24} sm={12} md={6} key={course.id}>
              <Link to={`/courses/${course.id}`}>
                <Card
                  className="portal-course-card"
                  hoverable
                  cover={
                    <img
                      alt={course.title}
                      src={course.coverImage}
                      style={{ height: 160, objectFit: 'cover' }}
                    />
                  }
                >
                  <Card.Meta
                    title={course.title}
                    description={
                      <Typography.Paragraph ellipsis={{ rows: 2 }} style={{ marginBottom: 8 }}>
                        {course.description}
                      </Typography.Paragraph>
                    }
                  />
                  <Space size={[4, 4]} wrap>
                    {course.tags.slice(0, 2).map((tag) => (
                      <Tag key={tag}>{tag}</Tag>
                    ))}
                  </Space>
                </Card>
              </Link>
            </Col>
          ))}
        </Row>
        <div style={{ textAlign: 'center', marginTop: 32 }}>
          <Link to="/courses">
            <Button type="link" size="large">
              查看全部课程 <ArrowRightOutlined />
            </Button>
          </Link>
        </div>
      </section>

      {/* Business units */}
      <section className="portal-section portal-stats">
        <Typography.Title level={2} className="portal-section-title">
          事业群入口
        </Typography.Title>
        <Typography.Paragraph className="portal-section-subtitle">
          统一导航，直达各业务系统
        </Typography.Paragraph>
        <Row gutter={[24, 24]}>
          {BUSINESS_UNITS.map((bu) => (
            <Col xs={24} md={8} key={bu.key}>
              <Card hoverable>
                {bu.icon}
                <Typography.Title level={4} style={{ marginTop: 16 }}>
                  {bu.title}
                </Typography.Title>
                <Typography.Paragraph type="secondary">{bu.desc}</Typography.Paragraph>
                <Link to="/bu">
                  <Button type="link" style={{ padding: 0 }}>
                    进入 <ArrowRightOutlined />
                  </Button>
                </Link>
              </Card>
            </Col>
          ))}
        </Row>
      </section>
    </div>
  );
}

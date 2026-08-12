import { Button, Col, List, Row, Space, Statistic, Tag, Typography } from 'antd';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { StarFilled, TeamOutlined, PlayCircleOutlined } from '@ant-design/icons';
import { useCourseDetail } from '@ep/query';
import { apiClient } from '../api/client';
import { MOCK_COURSES } from '../data/mockCourses';

export default function CourseDetailPage() {
  const { courseId = '' } = useParams();
  const navigate = useNavigate();

  const { data: course, isLoading } = useCourseDetail(apiClient, courseId);
  const resolved = course ?? MOCK_COURSES.find((c) => c.id === courseId) ?? MOCK_COURSES[0];

  if (!resolved) {
    return (
      <div className="portal-section">
        <Typography.Title level={3}>课程未找到</Typography.Title>
        <Link to="/courses">返回课程目录</Link>
      </div>
    );
  }

  // Flatten chapters/lessons for the preview list.
  const lessons = resolved.chapters.flatMap((ch) => ch.lessons);

  return (
    <div className="portal-section">
      <Row gutter={[48, 24]}>
        <Col xs={24} md={14}>
          <img
            alt={resolved.title}
            src={resolved.coverImage}
            style={{ width: '100%', maxHeight: 360, objectFit: 'cover', borderRadius: 8 }}
          />
        </Col>
        <Col xs={24} md={10}>
          <Typography.Title level={2}>{resolved.title}</Typography.Title>
          <Space size={[8, 8]} wrap style={{ marginBottom: 16 }}>
            {resolved.tags.map((tag) => (
              <Tag key={tag}>{tag}</Tag>
            ))}
          </Space>
          <Typography.Paragraph type="secondary">{resolved.description}</Typography.Paragraph>

          <Row gutter={32} style={{ margin: '16px 0' }}>
            <Col>
              <Statistic
                title="评分"
                value={resolved.rating}
                prefix={<StarFilled style={{ color: '#fadb14' }} />}
              />
            </Col>
            <Col>
              <Statistic
                title="报名人数"
                value={resolved.enrollmentCount}
                prefix={<TeamOutlined />}
              />
            </Col>
          </Row>

          <Typography.Paragraph>
            <Typography.Text strong>讲师：</Typography.Text> {resolved.teacherName}
          </Typography.Paragraph>

          <Button
            type="primary"
            size="large"
            block
            onClick={() => navigate('/login')}
          >
            立即报名
          </Button>
          <Typography.Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
            报名需登录，未注册用户将跳转到登录页。
          </Typography.Text>
        </Col>
      </Row>

      <Typography.Title level={3} style={{ marginTop: 48 }}>
        课程章节
      </Typography.Title>
      {isLoading && <Typography.Paragraph>正在加载章节…</Typography.Paragraph>}
      {!isLoading && lessons.length === 0 && (
        <Typography.Paragraph type="secondary">
          章节内容即将上线，敬请期待。
        </Typography.Paragraph>
      )}
      <List
        bordered
        dataSource={lessons}
        renderItem={(lesson, idx) => (
          <List.Item>
            <Space>
              <PlayCircleOutlined />
              <Typography.Text>
                第 {idx + 1} 节：{lesson.title}
              </Typography.Text>
              {lesson.isFree && <Tag color="green">免费试看</Tag>}
            </Space>
          </List.Item>
        )}
      />
    </div>
  );
}

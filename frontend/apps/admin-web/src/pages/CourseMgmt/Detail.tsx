import { Button, Card, Col, Descriptions, Image, List, Row, Spin, Statistic, Tag, App as AntApp } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useCourseDetail } from '@ep/query';
import { apiClient } from '../../api/client';
import type { CourseChapter, LessonType } from '@ep/shared';

const LESSON_TYPE_COLOR: Record<LessonType, string> = {
  video: 'blue',
  live: 'red',
  coding: 'purple',
  quiz: 'orange',
  reading: 'default',
};

export default function CourseDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { message } = AntApp.useApp();
  const { data: course, isLoading, isError, error } = useCourseDetail(apiClient, id ?? '');

  if (isError) {
    const msg = error instanceof Error ? error.message : '加载失败';
    message.error(msg);
  }

  if (isLoading) {
    return (
      <div className="ep-page-loading">
        <Spin size="large" />
      </div>
    );
  }

  if (!course) {
    return <div>课程不存在</div>;
  }

  return (
    <Spin spinning={isLoading}>
      <Button
        icon={<ArrowLeftOutlined />}
        onClick={() => navigate('/courses')}
        style={{ marginBottom: 16 }}
      >
        返回列表
      </Button>
      <Row gutter={[16, 16]}>
        <Col xs={24} lg={16}>
          <Card title={course.title}>
            {course.coverImage ? (
              <Image
                src={course.coverImage}
                alt={course.title}
                width="100%"
                style={{ maxHeight: 240, objectFit: 'cover', borderRadius: 8 }}
              />
            ) : null}
            <p style={{ marginTop: 16 }}>{course.description}</p>
            <Descriptions column={2} style={{ marginTop: 16 }}>
              <Descriptions.Item label="讲师">{course.teacherName}</Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag>{course.status}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="价格">
                {course.price > 0 ? `¥${(course.price / 100).toFixed(2)}` : '免费'}
              </Descriptions.Item>
              <Descriptions.Item label="评分">{course.rating.toFixed(1)}</Descriptions.Item>
            </Descriptions>
          </Card>
        </Col>
        <Col xs={24} lg={8}>
          <Card title="报名统计">
            <Row gutter={16}>
              <Col span={12}>
                <Statistic title="报名人数" value={course.enrollmentCount} />
              </Col>
              <Col span={12}>
                <Statistic title="章节数" value={course.chapters.length} />
              </Col>
            </Row>
          </Card>
        </Col>
      </Row>
      <Card title="章节列表" style={{ marginTop: 16 }}>
        <List<CourseChapter>
          dataSource={course.chapters}
          renderItem={(chapter) => (
            <List.Item>
              <List.Item.Meta
                title={`第 ${chapter.order} 章 · ${chapter.title}`}
                description={
                  <List
                    size="small"
                    dataSource={chapter.lessons}
                    renderItem={(lesson) => (
                      <List.Item>
                        <Tag color={LESSON_TYPE_COLOR[lesson.type]}>{lesson.type}</Tag>
                        <span style={{ marginLeft: 8 }}>{lesson.title}</span>
                        {lesson.isFree && <Tag color="green">免费</Tag>}
                      </List.Item>
                    )}
                  />
                }
              />
            </List.Item>
          )}
        />
      </Card>
    </Spin>
  );
}

import { useMemo, useState } from 'react';
import { Card, Col, Empty, Input, Pagination, Row, Segmented, Space, Tag, Typography } from 'antd';
import { Link } from 'react-router-dom';
import { useCourseList } from '@ep/query';
import { apiClient } from '../api/client';
import { COURSE_CATEGORIES, MOCK_COURSES } from '../data/mockCourses';

const PAGE_SIZE = 8;

export default function CourseCatalogPage() {
  const [keyword, setKeyword] = useState('');
  const [category, setCategory] = useState<string>('全部');
  const [page, setPage] = useState(1);

  // Live query; we keep the tag/keyword in sync with the local filters.
  // Build params conditionally to satisfy exactOptionalPropertyTypes.
  const params: { page: number; pageSize: number; keyword?: string; tag?: string } = {
    page,
    pageSize: PAGE_SIZE,
  };
  if (keyword) params.keyword = keyword;
  if (category !== '全部') params.tag = category;
  const { data, isLoading } = useCourseList(apiClient, params);

  const liveItems = data?.items ?? [];
  const usingMock = liveItems.length === 0;

  // Fallback: filter mock data locally so the page is always functional.
  const filteredMock = useMemo(() => {
    let list = MOCK_COURSES;
    if (category !== '全部') {
      list = list.filter((c) => c.tags.includes(category));
    }
    if (keyword.trim()) {
      const kw = keyword.trim().toLowerCase();
      list = list.filter(
        (c) =>
          c.title.toLowerCase().includes(kw) || c.description.toLowerCase().includes(kw),
      );
    }
    return list;
  }, [category, keyword]);

  const displayed = usingMock
    ? filteredMock.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE)
    : liveItems;

  const total = usingMock ? filteredMock.length : data?.total ?? 0;

  return (
    <div className="portal-section">
      <Typography.Title level={2} className="portal-section-title">
        公开课目录
      </Typography.Title>
      <Typography.Paragraph className="portal-section-subtitle">
        浏览全部公开课程，按分类与关键词筛选
      </Typography.Paragraph>

      <Row gutter={[16, 16]} style={{ marginBottom: 24 }} justify="center">
        <Col xs={24} md={12}>
          <Input.Search
            placeholder="搜索课程标题或关键词"
            allowClear
            enterButton
            size="large"
            onSearch={(v) => {
              setKeyword(v);
              setPage(1);
            }}
          />
        </Col>
      </Row>

      <div style={{ textAlign: 'center', marginBottom: 32 }}>
        <Segmented
          options={COURSE_CATEGORIES as unknown as string[]}
          value={category}
          onChange={(val) => {
            setCategory(String(val));
            setPage(1);
          }}
        />
      </div>

      {displayed.length === 0 && !isLoading ? (
        <Empty description="暂无课程" />
      ) : (
        <Row gutter={[24, 24]}>
          {displayed.map((course) => (
            <Col xs={24} sm={12} md={8} lg={6} key={course.id}>
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
                    {course.tags.slice(0, 3).map((tag) => (
                      <Tag key={tag}>{tag}</Tag>
                    ))}
                  </Space>
                  <Typography.Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
                    {course.teacherName} · {course.enrollmentCount} 人已学
                  </Typography.Text>
                </Card>
              </Link>
            </Col>
          ))}
        </Row>
      )}

      <div style={{ textAlign: 'center', marginTop: 40 }}>
        <Pagination
          current={page}
          pageSize={PAGE_SIZE}
          total={total}
          onChange={setPage}
          showSizeChanger={false}
        />
      </div>
    </div>
  );
}

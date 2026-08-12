import { Card, Col, Row, Spin, Statistic } from 'antd';
import {
  UserOutlined,
  BookOutlined,
  VideoCameraOutlined,
  FileTextOutlined,
} from '@ant-design/icons';
import { useCourseList, useLiveRooms, useAssignmentList } from '@ep/query';
import { apiClient } from '../../api/client';
import type { ReactNode } from 'react';

interface StatCardProps {
  title: string;
  value: number;
  icon: ReactNode;
  loading: boolean;
}

function StatCard({ title, value, icon, loading }: StatCardProps) {
  return (
    <Card>
      <Spin spinning={loading}>
        <Statistic title={title} value={value} prefix={icon} />
      </Spin>
    </Card>
  );
}

export default function DashboardPage() {
  const coursesQuery = useCourseList(apiClient, { page: 1, pageSize: 1 });
  const liveQuery = useLiveRooms(apiClient, { page: 1, pageSize: 100 });
  const assignmentsQuery = useAssignmentList(apiClient, undefined, { page: 1, pageSize: 100 });

  const loading = coursesQuery.isLoading || liveQuery.isLoading || assignmentsQuery.isLoading;

  const totalCourses = coursesQuery.data?.total ?? 0;
  const activeLiveRooms =
    liveQuery.data?.items.filter((r) => r.status === 'live').length ?? 0;
  const pendingSubmissions =
    assignmentsQuery.data?.items.filter((a) => a.status === 'published').length ?? 0;

  return (
    <Spin spinning={loading && !coursesQuery.data}>
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <StatCard
            title="学生总数"
            value={0}
            icon={<UserOutlined />}
            loading={false}
          />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard
            title="课程总数"
            value={totalCourses}
            icon={<BookOutlined />}
            loading={coursesQuery.isLoading}
          />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard
            title="活跃直播"
            value={activeLiveRooms}
            icon={<VideoCameraOutlined />}
            loading={liveQuery.isLoading}
          />
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <StatCard
            title="待处理作业"
            value={pendingSubmissions}
            icon={<FileTextOutlined />}
            loading={assignmentsQuery.isLoading}
          />
        </Col>
      </Row>
      <Card title="概览" style={{ marginTop: 16 }}>
        <p>欢迎使用 EP Admin 管理后台。请从左侧菜单选择功能模块。</p>
      </Card>
    </Spin>
  );
}

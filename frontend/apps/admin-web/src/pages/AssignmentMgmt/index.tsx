import { useState } from 'react';
import { Select, Space, Spin, Table, Tag, Typography, App as AntApp } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useAssignmentList, useCourseList } from '@ep/query';
import { apiClient } from '../../api/client';
import type { Assignment, AssignmentStatus } from '@ep/shared';

const STATUS_COLOR: Record<AssignmentStatus, string> = {
  draft: 'default',
  published: 'green',
  closed: 'gray',
};

const STATUS_LABEL: Record<AssignmentStatus, string> = {
  draft: '草稿',
  published: '已发布',
  closed: '已关闭',
};

export default function AssignmentMgmtPage() {
  const { message } = AntApp.useApp();
  const [courseId, setCourseId] = useState<string | undefined>(undefined);
  const [page, setPage] = useState(1);
  const pageSize = 10;

  const coursesQuery = useCourseList(apiClient, { page: 1, pageSize: 100 });
  const { data, isLoading, isError, error } = useAssignmentList(apiClient, courseId, {
    page,
    pageSize,
  });

  if (isError) {
    const msg = error instanceof Error ? error.message : '加载失败';
    message.error(msg);
  }

  const columns: ColumnsType<Assignment> = [
    { title: '作业标题', dataIndex: 'title', key: 'title' },
    {
      title: '课程',
      dataIndex: 'courseId',
      key: 'courseId',
      render: (cid: string) =>
        coursesQuery.data?.items.find((c) => c.id === cid)?.title ?? cid,
    },
    {
      title: '截止日期',
      dataIndex: 'dueDate',
      key: 'dueDate',
      render: (v: string) => new Date(v).toLocaleString('zh-CN'),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (status: AssignmentStatus) => (
        <Tag color={STATUS_COLOR[status]}>{STATUS_LABEL[status]}</Tag>
      ),
    },
    { title: '满分', dataIndex: 'maxScore', key: 'maxScore' },
  ];

  return (
    <Spin spinning={isLoading}>
      <Space style={{ marginBottom: 16 }}>
        <Typography.Text>按课程筛选：</Typography.Text>
        <Select
          allowClear
          placeholder="全部课程"
          style={{ width: 240 }}
          loading={coursesQuery.isLoading}
          value={courseId}
          onChange={(v) => {
            setCourseId(v);
            setPage(1);
          }}
          options={coursesQuery.data?.items.map((c) => ({ label: c.title, value: c.id })) ?? []}
        />
      </Space>
      <Table
        rowKey="id"
        columns={columns}
        dataSource={data?.items}
        pagination={{
          current: page,
          pageSize,
          total: data?.total ?? 0,
          onChange: (p) => setPage(p),
          showSizeChanger: false,
        }}
      />
    </Spin>
  );
}

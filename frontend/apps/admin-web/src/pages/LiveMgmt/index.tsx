import { useState } from 'react';
import { Spin, Table, Tag, App as AntApp } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useLiveRooms } from '@ep/query';
import { apiClient } from '../../api/client';
import type { LiveRoom, LiveRoomStatus } from '@ep/shared';

const STATUS_COLOR: Record<LiveRoomStatus, string> = {
  scheduled: 'blue',
  live: 'red',
  ended: 'gray',
  cancelled: 'default',
};

const STATUS_LABEL: Record<LiveRoomStatus, string> = {
  scheduled: '已排期',
  live: '直播中',
  ended: '已结束',
  cancelled: '已取消',
};

export default function LiveMgmtPage() {
  const { message } = AntApp.useApp();
  const [page, setPage] = useState(1);
  const pageSize = 10;
  const { data, isLoading, isError, error } = useLiveRooms(apiClient, { page, pageSize });

  if (isError) {
    const msg = error instanceof Error ? error.message : '加载失败';
    message.error(msg);
  }

  const columns: ColumnsType<LiveRoom> = [
    { title: '直播标题', dataIndex: 'title', key: 'title' },
    { title: '讲师', dataIndex: 'teacherName', key: 'teacherName' },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (status: LiveRoomStatus) => (
        <Tag color={STATUS_COLOR[status]}>{STATUS_LABEL[status]}</Tag>
      ),
    },
    {
      title: '计划开始',
      dataIndex: 'scheduledStart',
      key: 'scheduledStart',
      render: (v: string) => new Date(v).toLocaleString('zh-CN'),
    },
    { title: '参与人数', dataIndex: 'participantCount', key: 'participantCount' },
  ];

  return (
    <Spin spinning={isLoading}>
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

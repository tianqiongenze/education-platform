import { useMemo, useState } from 'react';
import {
  Button,
  Input,
  Modal,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
  App as AntApp,
  Popconfirm,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { EyeOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  listUserOrders,
  payOrder,
  refundOrder,
  ApiError,
} from '../../api/business';
import type { Order, OrderStatus } from '../../types/business';

const STATUS_COLOR: Record<OrderStatus, string> = {
  0: 'default',
  1: 'green',
  2: 'orange',
  3: 'red',
};

const STATUS_LABEL: Record<OrderStatus, string> = {
  0: '待支付',
  1: '已支付',
  2: '已退款',
  3: '已取消',
};

const QUERY_KEY = ['business', 'orders'] as const;

function formatDateTime(v: number | null | undefined): string {
  if (v === null || v === undefined) return '-';
  try {
    return new Date(v).toLocaleString('zh-CN');
  } catch {
    return String(v);
  }
}

interface PayModalState {
  open: boolean;
  orderId: number | null;
  payMethod: string;
}

export default function OrderListPage() {
  const navigate = useNavigate();
  const { message } = AntApp.useApp();
  const qc = useQueryClient();

  const [searchInput, setSearchInput] = useState('');
  const [committedUserId, setCommittedUserId] = useState('');
  const [page, setPage] = useState(1);
  const pageSize = 10;

  const [payModal, setPayModal] = useState<PayModalState>({
    open: false,
    orderId: null,
    payMethod: '',
  });

  const userIdNum = useMemo(() => {
    const trimmed = committedUserId.trim();
    if (!trimmed) return undefined;
    const n = Number(trimmed);
    return Number.isFinite(n) && n > 0 ? n : undefined;
  }, [committedUserId]);

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['business', 'orders', 'list', userIdNum ?? 'all'] as const,
    queryFn: async (): Promise<Order[]> => {
      if (userIdNum === undefined) return [];
      return listUserOrders(userIdNum, 1, 100);
    },
  });

  const payMutation = useMutation({
    mutationFn: ({ id, payMethod }: { id: number; payMethod: string }) =>
      payOrder(id, payMethod),
    onSuccess: (_data, vars) => {
      message.success(`订单 #${vars.id} 支付成功`);
      qc.invalidateQueries({ queryKey: QUERY_KEY });
      setPayModal((s) => ({ ...s, open: false, orderId: null, payMethod: '' }));
    },
    onError: (err) => {
      const msg = err instanceof ApiError ? err.message : '支付失败';
      message.error(msg);
    },
  });

  const refundMutation = useMutation({
    mutationFn: (id: number) => refundOrder(id, '管理员退款'),
    onSuccess: (_data, id) => {
      message.success(`订单 #${id} 已退款`);
      qc.invalidateQueries({ queryKey: QUERY_KEY });
    },
    onError: (err) => {
      const msg = err instanceof ApiError ? err.message : '退款失败';
      message.error(msg);
    },
  });

  if (isError) {
    const msg = error instanceof ApiError ? error.message : '加载失败';
    message.error(msg);
  }

  const total = data?.length ?? 0;
  const pagedData = useMemo(() => {
    const start = (page - 1) * pageSize;
    return (data ?? []).slice(start, start + pageSize);
  }, [data, page, pageSize]);

  const handleSearch = () => {
    setCommittedUserId(searchInput.trim());
    setPage(1);
  };

  const columns: ColumnsType<Order> = [
    {
      title: '订单 ID',
      dataIndex: 'orderId',
      key: 'orderId',
      width: 90,
      render: (orderId: number) => (
        <a onClick={() => navigate(`/business/orders/${orderId}`)}>{orderId}</a>
      ),
    },
    { title: '用户 ID', dataIndex: 'userId', key: 'userId', width: 100 },
    { title: '课程 ID', dataIndex: 'courseId', key: 'courseId', width: 100 },
    {
      title: '金额',
      dataIndex: 'amount',
      key: 'amount',
      width: 110,
      render: (v: string | null) => (v ? `¥${v}` : '-'),
    },
    {
      title: '支付方式',
      dataIndex: 'payMethod',
      key: 'payMethod',
      width: 110,
      render: (v: string | null) => v ?? '-',
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (status: OrderStatus) => (
        <Tag color={STATUS_COLOR[status]}>{STATUS_LABEL[status]}</Tag>
      ),
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (v: number | null) => formatDateTime(v),
    },
    {
      title: '操作',
      key: 'actions',
      width: 260,
      render: (_: unknown, record: Order) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => navigate(`/business/orders/${record.orderId}`)}
          >
            查看
          </Button>
          {record.status === 0 && (
            <Button
              type="link"
              size="small"
              onClick={() =>
                setPayModal({ open: true, orderId: record.orderId, payMethod: '' })
              }
            >
              支付
            </Button>
          )}
          {record.status === 1 && (
            <Popconfirm
              title="确认退款该订单？"
              onConfirm={() => refundMutation.mutate(record.orderId)}
              okText="退款"
              cancelText="取消"
            >
              <Button type="link" size="small" danger loading={refundMutation.isPending}>
                退款
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <Spin spinning={isLoading}>
      <Space
        style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }}
      >
        <Input
          placeholder="按用户 ID 筛选"
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
          onPressEnter={handleSearch}
          style={{ width: 240 }}
          allowClear
        />
        <Button onClick={handleSearch}>搜索</Button>
      </Space>

      <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
        {userIdNum === undefined
          ? '请输入用户 ID 后点击搜索，加载该用户的订单列表'
          : `共 ${total} 条订单`}
      </Typography.Text>

      <Table
        rowKey="orderId"
        columns={columns}
        dataSource={pagedData}
        locale={{ emptyText: userIdNum === undefined ? '请输入用户 ID' : '暂无数据' }}
        pagination={{
          current: page,
          pageSize,
          total,
          onChange: (p) => setPage(p),
          showSizeChanger: false,
        }}
      />

      <Modal
        title={`支付订单 #${payModal.orderId ?? ''}`}
        open={payModal.open}
        onCancel={() =>
          setPayModal((s) => ({ ...s, open: false, orderId: null, payMethod: '' }))
        }
        onOk={() => {
          if (payModal.orderId === null) return;
          const payMethod = payModal.payMethod.trim();
          if (!payMethod) {
            message.warning('请输入支付方式');
            return;
          }
          payMutation.mutate({ id: payModal.orderId, payMethod });
        }}
        confirmLoading={payMutation.isPending}
        okText="确认支付"
        cancelText="取消"
        destroyOnClose
      >
        <Input
          placeholder="如 ALIPAY / WECHAT"
          value={payModal.payMethod}
          onChange={(e) =>
            setPayModal((s) => ({ ...s, payMethod: e.target.value }))
          }
          maxLength={32}
        />
      </Modal>
    </Spin>
  );
}

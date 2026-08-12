import { useMemo } from 'react';
import {
  Button,
  Card,
  Col,
  Descriptions,
  Row,
  Space,
  Spin,
  Tag,
  Timeline,
  Typography,
  App as AntApp,
} from 'antd';
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  LoadingOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { getOrderById, ApiError } from '../../api/business';
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

function formatDateTime(v: number | null | undefined): string {
  if (v === null || v === undefined) return '-';
  try {
    return new Date(v).toLocaleString('zh-CN');
  } catch {
    return String(v);
  }
}

/** Build a status timeline from the order's lifecycle timestamps. */
function buildTimeline(order: Order) {
  const items: Array<{
    key: string;
    color: string;
    icon?: React.ReactNode;
    label: string;
    time: string;
  }> = [];

  items.push({
    key: 'created',
    color: 'blue',
    icon: <ClockCircleOutlined />,
    label: '订单创建',
    time: formatDateTime(order.createdAt),
  });

  if (order.status === 0) {
    items.push({
      key: 'pending',
      color: 'gray',
      icon: <LoadingOutlined />,
      label: '等待支付',
      time: '-',
    });
  }

  if (order.status === 1 || order.status === 2) {
    items.push({
      key: 'paid',
      color: 'green',
      icon: <CheckCircleOutlined />,
      label: `已支付${order.payMethod ? `（${order.payMethod}）` : ''}`,
      time: formatDateTime(order.updatedAt),
    });
  }

  if (order.status === 2) {
    items.push({
      key: 'refunded',
      color: 'orange',
      icon: <CloseCircleOutlined />,
      label: '已退款',
      time: formatDateTime(order.updatedAt),
    });
  }

  if (order.status === 3) {
    items.push({
      key: 'cancelled',
      color: 'red',
      icon: <CloseCircleOutlined />,
      label: '已取消',
      time: formatDateTime(order.updatedAt),
    });
  }

  return items;
}

export default function OrderDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { message } = AntApp.useApp();

  const orderId = useMemo(() => {
    const n = Number(id);
    return Number.isFinite(n) && n > 0 ? n : undefined;
  }, [id]);

  const { data: order, isLoading, isError, error } = useQuery({
    queryKey: ['business', 'orders', 'detail', orderId] as const,
    queryFn: async (): Promise<Order> => {
      if (orderId === undefined) {
        throw new Error('无效的订单 ID');
      }
      return getOrderById(orderId);
    },
    enabled: orderId !== undefined,
  });

  if (isError) {
    const msg = error instanceof ApiError ? error.message : '加载订单失败';
    message.error(msg);
  }

  if (isLoading) {
    return (
      <div className="ep-page-loading">
        <Spin size="large" />
      </div>
    );
  }

  if (!order) {
    return (
      <div>
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/business/orders')}
          style={{ marginBottom: 16 }}
        >
          返回列表
        </Button>
        <Typography.Text>订单不存在</Typography.Text>
      </div>
    );
  }

  const timelineItems = buildTimeline(order);

  return (
    <div>
      <Button
        icon={<ArrowLeftOutlined />}
        onClick={() => navigate('/business/orders')}
        style={{ marginBottom: 16 }}
      >
        返回列表
      </Button>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={16}>
          <Card
            title={
              <Space>
                <span>订单详情</span>
                <Tag color={STATUS_COLOR[order.status]}>
                  {STATUS_LABEL[order.status]}
                </Tag>
              </Space>
            }
          >
            <Descriptions column={2} bordered>
              <Descriptions.Item label="订单 ID">{order.orderId}</Descriptions.Item>
              <Descriptions.Item label="用户 ID">{order.userId}</Descriptions.Item>
              <Descriptions.Item label="课程 ID">{order.courseId}</Descriptions.Item>
              <Descriptions.Item label="金额">
                {order.amount ? `¥${order.amount}` : '-'}
              </Descriptions.Item>
              <Descriptions.Item label="支付方式">
                {order.payMethod ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="幂等键">
                {order.idempotencyKey ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="创建时间">
                {formatDateTime(order.createdAt)}
              </Descriptions.Item>
              <Descriptions.Item label="更新时间">
                {formatDateTime(order.updatedAt)}
              </Descriptions.Item>
            </Descriptions>
          </Card>
        </Col>

        <Col xs={24} lg={8}>
          <Card title="订单状态时间线">
            <Timeline
              items={timelineItems.map((item) => ({
                key: item.key,
                color: item.color,
                dot: item.icon,
                children: (
                  <Space direction="vertical" size={0}>
                    <Typography.Text strong>{item.label}</Typography.Text>
                    <Typography.Text type="secondary">{item.time}</Typography.Text>
                  </Space>
                ),
              }))}
            />
          </Card>
        </Col>
      </Row>
    </div>
  );
}

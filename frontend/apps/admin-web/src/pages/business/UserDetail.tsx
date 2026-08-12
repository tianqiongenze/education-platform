import { useMemo, useState } from 'react';
import {
  Button,
  Card,
  Col,
  Descriptions,
  Modal,
  Input,
  Row,
  Space,
  Spin,
  Tag,
  Timeline,
  Typography,
  App as AntApp,
  Popconfirm,
} from 'antd';
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  LoadingOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { freezeUser, unfreezeUser, getUserById, ApiError } from '../../api/business';
import { userSchema } from '../../types/business';
import type { User, UserStatus } from '../../types/business';

const STATUS_COLOR: Record<UserStatus, string> = {
  0: 'default',
  1: 'green',
  2: 'red',
};

const STATUS_LABEL: Record<UserStatus, string> = {
  0: '已禁用',
  1: '正常',
  2: '已冻结',
};

function formatDateTime(v: number | null | undefined): string {
  if (v === null || v === undefined) return '-';
  try {
    return new Date(v).toLocaleString('zh-CN');
  } catch {
    return String(v);
  }
}

interface FreezeModalState {
  open: boolean;
  reason: string;
}

/**
 * SP-03 User Detail page.
 *
 * Fetches a single user via `getUserById`, validates the response shape with
 * the `userSchema` Zod schema, and renders profile information plus a status
 * timeline. Active users can be frozen (with a reason modal) and frozen users
 * can be unfrozen — both re-use the same mutations as the list page.
 */
export default function UserDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { message } = AntApp.useApp();
  const qc = useQueryClient();

  const userId = useMemo(() => {
    const n = Number(id);
    return Number.isFinite(n) && n > 0 ? n : undefined;
  }, [id]);

  const [freezeModal, setFreezeModal] = useState<FreezeModalState>({
    open: false,
    reason: '',
  });

  const { data: user, isLoading, isError, error } = useQuery({
    queryKey: ['business', 'users', 'detail', userId] as const,
    queryFn: async (): Promise<User> => {
      if (userId === undefined) {
        throw new Error('无效的用户 ID');
      }
      const raw = await getUserById(userId);
      const parsed = userSchema.safeParse(raw);
      if (!parsed.success) {
        // Surface validation issues in dev; in prod the API client already
        // logs them. We still return the raw data so the page can render.
        console.warn('[Zod] user response validation failed', parsed.error.issues);
        return raw;
      }
      return parsed.data;
    },
    enabled: userId !== undefined,
  });

  const freezeMutation = useMutation({
    mutationFn: ({ id, reason }: { id: number; reason: string }) =>
      freezeUser(id, reason),
    onSuccess: (_data, vars) => {
      message.success(`用户 #${vars.id} 已冻结`);
      qc.invalidateQueries({ queryKey: ['business', 'users'] });
      setFreezeModal({ open: false, reason: '' });
    },
    onError: (err) => {
      const msg = err instanceof ApiError ? err.message : '冻结失败';
      message.error(msg);
    },
  });

  const unfreezeMutation = useMutation({
    mutationFn: (id: number) => unfreezeUser(id),
    onSuccess: (_data, id) => {
      message.success(`用户 #${id} 已解冻`);
      qc.invalidateQueries({ queryKey: ['business', 'users'] });
    },
    onError: (err) => {
      const msg = err instanceof ApiError ? err.message : '解冻失败';
      message.error(msg);
    },
  });

  if (isError) {
    const msg = error instanceof ApiError ? error.message : '加载用户失败';
    message.error(msg);
  }

  if (isLoading) {
    return (
      <div className="ep-page-loading">
        <Spin size="large" />
      </div>
    );
  }

  if (!user) {
    return (
      <div>
        <Button
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/business/users')}
          style={{ marginBottom: 16 }}
        >
          返回列表
        </Button>
        <Typography.Text>用户不存在</Typography.Text>
      </div>
    );
  }

  const timelineItems = buildUserTimeline(user);

  return (
    <div>
      <Button
        icon={<ArrowLeftOutlined />}
        onClick={() => navigate('/business/users')}
        style={{ marginBottom: 16 }}
      >
        返回列表
      </Button>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={16}>
          <Card
            title={
              <Space>
                <span>用户详情</span>
                <Tag color={STATUS_COLOR[user.status]}>
                  {STATUS_LABEL[user.status]}
                </Tag>
              </Space>
            }
          >
            <Descriptions column={2} bordered>
              <Descriptions.Item label="用户 ID">{user.userId}</Descriptions.Item>
              <Descriptions.Item label="用户名">{user.username}</Descriptions.Item>
              <Descriptions.Item label="昵称">{user.nickname ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="手机号">{user.phone}</Descriptions.Item>
              <Descriptions.Item label="邮箱">{user.email ?? '-'}</Descriptions.Item>
              <Descriptions.Item label="头像">
                {user.avatar ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="租户 ID">
                {user.tenantId ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={STATUS_COLOR[user.status]}>
                  {STATUS_LABEL[user.status]}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="创建时间">
                {formatDateTime(user.createdAt)}
              </Descriptions.Item>
              <Descriptions.Item label="更新时间">
                {formatDateTime(user.updatedAt)}
              </Descriptions.Item>
            </Descriptions>

            <Space style={{ marginTop: 16 }}>
              {user.status === 1 && (
                <Button
                  danger
                  onClick={() => setFreezeModal({ open: true, reason: '' })}
                >
                  冻结用户
                </Button>
              )}
              {user.status === 2 && (
                <Popconfirm
                  title="确认解冻该用户？"
                  onConfirm={() => unfreezeMutation.mutate(user.userId)}
                  okText="解冻"
                  cancelText="取消"
                >
                  <Button
                    type="primary"
                    loading={unfreezeMutation.isPending}
                  >
                    解冻用户
                  </Button>
                </Popconfirm>
              )}
            </Space>
          </Card>
        </Col>

        <Col xs={24} lg={8}>
          <Card title="账户状态时间线">
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

      <Modal
        title={`冻结用户 #${user.userId}`}
        open={freezeModal.open}
        onCancel={() => setFreezeModal({ open: false, reason: '' })}
        onOk={() => {
          const reason = freezeModal.reason.trim();
          if (!reason) {
            message.warning('请输入冻结原因');
            return;
          }
          freezeMutation.mutate({ id: user.userId, reason });
        }}
        confirmLoading={freezeMutation.isPending}
        okText="确认冻结"
        cancelText="取消"
        destroyOnClose
      >
        <Input.TextArea
          placeholder="请输入冻结原因"
          value={freezeModal.reason}
          onChange={(e) => setFreezeModal({ open: true, reason: e.target.value })}
          autoSize={{ minRows: 3, maxRows: 6 }}
          maxLength={500}
        />
      </Modal>
    </div>
  );
}

/** Build a status timeline from the user's lifecycle timestamps. */
function buildUserTimeline(user: User) {
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
    label: '账户创建',
    time: formatDateTime(user.createdAt),
  });

  if (user.status === 1) {
    items.push({
      key: 'active',
      color: 'green',
      icon: <CheckCircleOutlined />,
      label: '正常使用',
      time: formatDateTime(user.updatedAt),
    });
  }

  if (user.status === 2) {
    items.push({
      key: 'frozen',
      color: 'red',
      icon: <CloseCircleOutlined />,
      label: '已冻结',
      time: formatDateTime(user.updatedAt),
    });
  }

  if (user.status === 0) {
    items.push({
      key: 'disabled',
      color: 'gray',
      icon: <LoadingOutlined />,
      label: '已禁用',
      time: formatDateTime(user.updatedAt),
    });
  }

  return items;
}

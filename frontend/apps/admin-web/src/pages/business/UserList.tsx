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
import { PlusOutlined, EyeOutlined, SearchOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  listUsers,
  freezeUser,
  unfreezeUser,
  getUserByPhone,
  ApiError,
} from '../../api/business';
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

const QUERY_KEY = ['business', 'users'] as const;

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
  userId: number | null;
  reason: string;
}

export default function UserListPage() {
  const navigate = useNavigate();
  const { message } = AntApp.useApp();
  const qc = useQueryClient();

  const [page, setPage] = useState(1);
  const pageSize = 10;

  const [searchInput, setSearchInput] = useState('');
  const [committedPhone, setCommittedPhone] = useState('');

  const [freezeModal, setFreezeModal] = useState<FreezeModalState>({
    open: false,
    userId: null,
    reason: '',
  });

  const phone = useMemo(() => committedPhone.trim(), [committedPhone]);

  // When a phone search is committed, we hit GET /api/v1/users/phone/{phone}
  // which returns a single user (or 404). Otherwise we list all users.
  const phoneQuery = useQuery({
    queryKey: ['business', 'users', 'phone', phone] as const,
    queryFn: async (): Promise<User | null> => {
      if (!phone) return null;
      try {
        return await getUserByPhone(phone);
      } catch (err) {
        // 404 means "no user with this phone" — render an empty result rather
        // than surfacing an error toast.
        if (err instanceof ApiError && err.isNotFound()) return null;
        throw err;
      }
    },
    enabled: phone.length > 0,
  });

  const listQuery = useQuery({
    queryKey: ['business', 'users', 'list'] as const,
    queryFn: async (): Promise<User[]> => listUsers(),
    enabled: phone.length === 0,
  });

  // Merge the two query states into a single data array for the table.
  const data: User[] = useMemo(() => {
    if (phone) {
      return phoneQuery.data ? [phoneQuery.data] : [];
    }
    return listQuery.data ?? [];
  }, [phone, phoneQuery.data, listQuery.data]);

  const isLoading = phone ? phoneQuery.isLoading : listQuery.isLoading;
  const isError = phone ? phoneQuery.isError : listQuery.isError;
  const error = phone ? phoneQuery.error : listQuery.error;

  const freezeMutation = useMutation({
    mutationFn: ({ id, reason }: { id: number; reason: string }) =>
      freezeUser(id, reason),
    onSuccess: (_data, vars) => {
      message.success(`用户 #${vars.id} 已冻结`);
      qc.invalidateQueries({ queryKey: QUERY_KEY });
      setFreezeModal((s) => ({ ...s, open: false, userId: null, reason: '' }));
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
      qc.invalidateQueries({ queryKey: QUERY_KEY });
    },
    onError: (err) => {
      const msg = err instanceof ApiError ? err.message : '解冻失败';
      message.error(msg);
    },
  });

  if (isError) {
    const msg = error instanceof ApiError ? error.message : '加载失败';
    message.error(msg);
  }

  // Client-side pagination since the backend returns a flat list.
  const total = data?.length ?? 0;
  const pagedData = useMemo(() => {
    const start = (page - 1) * pageSize;
    return (data ?? []).slice(start, start + pageSize);
  }, [data, page, pageSize]);

  const columns: ColumnsType<User> = [
    {
      title: '用户 ID',
      dataIndex: 'userId',
      key: 'userId',
      width: 90,
      render: (userId: number) => (
        <a onClick={() => navigate(`/business/users/${userId}`)}>{userId}</a>
      ),
    },
    { title: '用户名', dataIndex: 'username', key: 'username', width: 140 },
    {
      title: '昵称',
      dataIndex: 'nickname',
      key: 'nickname',
      width: 140,
      render: (v: string | null) => v ?? '-',
    },
    {
      title: '手机号',
      dataIndex: 'phone',
      key: 'phone',
      width: 150,
    },
    {
      title: '邮箱',
      dataIndex: 'email',
      key: 'email',
      width: 200,
      render: (v: string | null) => v ?? '-',
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (status: UserStatus) => (
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
      width: 240,
      render: (_: unknown, record: User) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => navigate(`/business/users/${record.userId}`)}
          >
            查看
          </Button>
          {record.status === 1 && (
            <Button
              type="link"
              size="small"
              onClick={() =>
                setFreezeModal({ open: true, userId: record.userId, reason: '' })
              }
            >
              冻结
            </Button>
          )}
          {record.status === 2 && (
            <Popconfirm
              title="确认解冻该用户？"
              onConfirm={() => unfreezeMutation.mutate(record.userId)}
              okText="解冻"
              cancelText="取消"
            >
              <Button
                type="link"
                size="small"
                loading={unfreezeMutation.isPending}
              >
                解冻
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
          placeholder="按手机号搜索用户"
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
          onPressEnter={() => {
            setCommittedPhone(searchInput.trim());
            setPage(1);
          }}
          style={{ width: 240 }}
          prefix={<SearchOutlined />}
          allowClear
        />
        <Space>
          <Button
            onClick={() => {
              setCommittedPhone(searchInput.trim());
              setPage(1);
            }}
          >
            搜索
          </Button>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => navigate('/business/users/create')}
          >
            创建用户
          </Button>
        </Space>
      </Space>

      <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 8 }}>
        共 {total} 条记录
      </Typography.Text>

      <Table
        rowKey="userId"
        columns={columns}
        dataSource={pagedData}
        locale={{ emptyText: '暂无数据' }}
        pagination={{
          current: page,
          pageSize,
          total,
          onChange: (p) => setPage(p),
          showSizeChanger: false,
        }}
      />

      <Modal
        title={`冻结用户 #${freezeModal.userId ?? ''}`}
        open={freezeModal.open}
        onCancel={() =>
          setFreezeModal((s) => ({ ...s, open: false, userId: null, reason: '' }))
        }
        onOk={() => {
          if (freezeModal.userId === null) return;
          const reason = freezeModal.reason.trim();
          if (!reason) {
            message.warning('请输入冻结原因');
            return;
          }
          freezeMutation.mutate({ id: freezeModal.userId, reason });
        }}
        confirmLoading={freezeMutation.isPending}
        okText="确认冻结"
        cancelText="取消"
        destroyOnClose
      >
        <Input.TextArea
          placeholder="请输入冻结原因"
          value={freezeModal.reason}
          onChange={(e) =>
            setFreezeModal((s) => ({ ...s, reason: e.target.value }))
          }
          autoSize={{ minRows: 3, maxRows: 6 }}
          maxLength={500}
        />
      </Modal>
    </Spin>
  );
}

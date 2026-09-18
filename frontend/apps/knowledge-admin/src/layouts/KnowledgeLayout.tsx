import { Layout, Menu, Avatar, Dropdown, type MenuProps } from 'antd';
import {
  DatabaseOutlined,
  FileTextOutlined,
  SearchOutlined,
  RobotOutlined,
  MessageOutlined,
  TeamOutlined,
  NodeIndexOutlined,
  UserOutlined,
  LogoutOutlined,
} from '@ant-design/icons';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { ThemeToggle } from '@ep/ui-kit';
import type { ReactNode } from 'react';

const { Header, Sider, Content } = Layout;

interface MenuItemConfig {
  key: string;
  icon: ReactNode;
  label: string;
}

const MENU_ITEMS: MenuItemConfig[] = [
  { key: '/knowledge-bases', icon: <DatabaseOutlined />, label: '知识库管理' },
  { key: '/documents', icon: <FileTextOutlined />, label: '文档上传' },
  { key: '/retrieval', icon: <SearchOutlined />, label: '智能检索' },
  { key: '/agents', icon: <RobotOutlined />, label: '智能体管理' },
  { key: '/community', icon: <MessageOutlined />, label: '问答社区' },
  { key: '/tenants', icon: <TeamOutlined />, label: '租户管理' },
  { key: '/learning-paths', icon: <NodeIndexOutlined />, label: '学习路径' },
];

export default function KnowledgeLayout() {
  const navigate = useNavigate();
  const location = useLocation();

  const handleMenuClick: MenuProps['onClick'] = ({ key }) => {
    navigate(key);
  };

  const userMenuItems: MenuProps['items'] = [
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: '退出登录',
      onClick: () => {
        localStorage.removeItem('ep_access_token');
        navigate('/login');
      },
    },
  ];

  const selectedKey =
    MENU_ITEMS.find((item) => location.pathname.startsWith(item.key))?.key ??
    '/knowledge-bases';

  return (
    <Layout style={{ minHeight: '100%' }}>
      <Sider collapsible breakpoint="lg">
        <div
          style={{
            height: '48px',
            margin: '16px',
            color: '#fff',
            fontSize: '18px',
            fontWeight: 700,
            textAlign: 'center',
            lineHeight: '48px',
          }}
        >
          知识中台
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          items={MENU_ITEMS.map((item) => ({
            key: item.key,
            icon: item.icon,
            label: item.label,
          }))}
          onClick={handleMenuClick}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            display: 'flex',
            justifyContent: 'flex-end',
            alignItems: 'center',
            gap: 16,
            padding: '0 24px',
            // Token-driven so the chrome flips with the persisted theme.
            background: 'var(--ep-color-bg-card, #fff)',
          }}
        >
          <ThemeToggle />
          <Dropdown menu={{ items: userMenuItems }} placement="bottomRight">
            <div style={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 8 }}>
              <Avatar icon={<UserOutlined />} />
              <span>知识管理员</span>
            </div>
          </Dropdown>
        </Header>
        <Content
          style={{
            margin: '24px',
            padding: '24px',
            background: 'var(--ep-color-bg-card, #fff)',
            borderRadius: 8,
          }}
        >
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}

import { Layout, Menu, Avatar, Dropdown, type MenuProps } from 'antd';
import {
  DashboardOutlined,
  BookOutlined,
  FileTextOutlined,
  UserOutlined,
  TeamOutlined,
  VideoCameraOutlined,
  CodeOutlined,
  TrophyOutlined,
  SettingOutlined,
  LogoutOutlined,
  AppstoreOutlined,
  ApartmentOutlined,
  ProfileOutlined,
  ShoppingCartOutlined,
} from '@ant-design/icons';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useMemo, useState, type ReactNode } from 'react';
import { ThemeToggle } from '@ep/ui-kit';
import { useUserStore } from '../stores/UserContext';

const { Header, Sider, Content } = Layout;

interface MenuItemConfig {
  key: string;
  icon: ReactNode;
  label: string;
}

const MENU_ITEMS: MenuItemConfig[] = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: 'Dashboard' },
  { key: '/courses', icon: <BookOutlined />, label: 'Courses' },
  { key: '/assignments', icon: <FileTextOutlined />, label: 'Assignments' },
  { key: '/students', icon: <UserOutlined />, label: 'Students' },
  { key: '/teachers', icon: <TeamOutlined />, label: 'Teachers' },
  { key: '/live', icon: <VideoCameraOutlined />, label: 'Live' },
  { key: '/coding', icon: <CodeOutlined />, label: 'Coding' },
  { key: '/grades', icon: <TrophyOutlined />, label: 'Grades' },
  { key: '/settings', icon: <SettingOutlined />, label: 'Settings' },
];

/**
 * Sub-menu groups keyed by their parent group key. The first segment of each
 * route (e.g. `/business`, `/it-backend`) determines which group it belongs to.
 *
 * AntD Menu uses the `children` field to render a collapsible sub-menu.
 */
const MENU_GROUPS: { key: string; label: string; icon: ReactNode; children: MenuItemConfig[] }[] = [
  {
    key: '/it-backend',
    label: 'IT 后端 BU',
    icon: <AppstoreOutlined />,
    children: [
      { key: '/it-backend/assignments', icon: <ProfileOutlined />, label: '作业管理' },
      { key: '/it-backend/submissions', icon: <FileTextOutlined />, label: '提交管理' },
    ],
  },
  {
    key: '/business',
    label: '业务中台',
    icon: <ApartmentOutlined />,
    children: [
      { key: '/business/users', icon: <UserOutlined />, label: '用户管理' },
      { key: '/business/courses', icon: <BookOutlined />, label: '课程管理' },
      { key: '/business/orders', icon: <ShoppingCartOutlined />, label: '订单管理' },
    ],
  },
];

/** Flatten the group children so we can run `startsWith` matching for selection. */
const ALL_LEAF_ITEMS: MenuItemConfig[] = [
  ...MENU_ITEMS,
  ...MENU_GROUPS.flatMap((g) => g.children),
];

export default function AdminLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout } = useUserStore();

  const handleMenuClick: MenuProps['onClick'] = ({ key }) => {
    navigate(key);
  };

  const userMenuItems: MenuProps['items'] = [
    {
      key: 'logout',
      icon: <LogoutOutlined />,
      label: '退出登录',
      onClick: () => {
        logout();
        navigate('/login');
      },
    },
  ];

  const selectedKey =
    ALL_LEAF_ITEMS.find((item) => location.pathname.startsWith(item.key))?.key ?? '/dashboard';

  /**
   * Determine which sub-menu groups should be expanded based on the current
   * path. AntD's `openKeys` controls which sub-menus are visually expanded.
   *
   * We seed the open keys from the current path on mount, then let the user
   * expand/collapse groups manually via `onOpenChange`.
   */
  const initialOpenKeys = MENU_GROUPS.filter((g) =>
    location.pathname.startsWith(g.key),
  ).map((g) => g.key);
  const [openKeys, setOpenKeys] = useState<string[]>(initialOpenKeys);

  /**
   * Build the AntD Menu items list, interleaving flat items with collapsible
   * groups. The flat `MENU_ITEMS` come first, followed by a divider-less list
   * of grouped sub-menus.
   */
  const menuItems = useMemo<NonNullable<MenuProps['items']>>(
    () => [
      ...MENU_ITEMS.map((item) => ({
        key: item.key,
        icon: item.icon,
        label: item.label,
      })),
      ...MENU_GROUPS.map((group) => ({
        key: group.key,
        icon: group.icon,
        label: group.label,
        children: group.children.map((child) => ({
          key: child.key,
          icon: child.icon,
          label: child.label,
        })),
      })),
    ],
    [],
  );

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
          EP Admin
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          openKeys={openKeys}
          onOpenChange={(keys) => setOpenKeys(keys as string[])}
          items={menuItems}
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
            // Token-driven so the chrome flips with the persisted theme
            // (light: card white, dark: gray-10) instead of hardcoded #fff.
            background: 'var(--ep-color-bg-card, #fff)',
          }}
        >
          <ThemeToggle />
          <Dropdown menu={{ items: userMenuItems }} placement="bottomRight">
            <div style={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 8 }}>
              <Avatar src={user?.avatar} icon={<UserOutlined />} />
              <span>{user?.nickname ?? '未登录'}</span>
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

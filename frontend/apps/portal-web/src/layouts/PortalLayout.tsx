import { Layout, Menu, Button, Typography, Space } from 'antd';
import { Outlet, Link, useNavigate, useLocation } from 'react-router-dom';
import { useMemo } from 'react';

const { Header, Content, Footer } = Layout;

interface NavItem {
  key: string;
  label: string;
}

const NAV_ITEMS: NavItem[] = [
  { key: '/', label: '首页' },
  { key: '/courses', label: '课程' },
  { key: '/bu', label: '事业群' },
  { key: '/about', label: '关于' },
];

export default function PortalLayout() {
  const navigate = useNavigate();
  const location = useLocation();

  // Highlight the top-level section the current route belongs to.
  const selectedKey = useMemo(() => {
    const path = location.pathname;
    const match = NAV_ITEMS.find((item) => item.key !== '/' && path.startsWith(item.key));
    return match ? match.key : path === '/' ? '/' : '';
  }, [location.pathname]);

  const menuItems = NAV_ITEMS.map((item) => ({
    key: item.key,
    label: <Link to={item.key}>{item.label}</Link>,
  }));

  return (
    <Layout style={{ minHeight: '100vh', background: '#ffffff' }}>
      <Header
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          background: '#ffffff',
          borderBottom: '1px solid #f0f0f0',
          padding: '0 48px',
          position: 'sticky',
          top: 0,
          zIndex: 10,
        }}
      >
        <Space size="large" align="center">
          <Link
            to="/"
            style={{
              fontSize: 20,
              fontWeight: 700,
              color: '#1677ff',
              whiteSpace: 'nowrap',
            }}
          >
            智教云
          </Link>
          <Menu
            mode="horizontal"
            selectedKeys={selectedKey ? [selectedKey] : []}
            items={menuItems}
            style={{ borderBottom: 'none', minWidth: 360 }}
          />
        </Space>
        <Space>
          <Button type="text" onClick={() => navigate('/login')}>
            登录
          </Button>
          <Button type="primary" onClick={() => navigate('/login')}>
            免费注册
          </Button>
        </Space>
      </Header>

      <Content style={{ background: '#ffffff' }}>
        <Outlet />
      </Content>

      <Footer className="portal-footer">
        <div style={{ maxWidth: 1200, margin: '0 auto' }}>
          <Typography.Title level={4} style={{ color: '#ffffff' }}>
            智教云
          </Typography.Title>
          <Typography.Paragraph>
            全场景在线教育平台 · 公开课统一入口 · 事业群导航
          </Typography.Paragraph>
          <Space size="large" wrap>
            <Link to="/courses">课程中心</Link>
            <Link to="/bu">事业群</Link>
            <Link to="/about">关于我们</Link>
            <Link to="/login">登录</Link>
          </Space>
          <Typography.Paragraph style={{ marginTop: 16, marginBottom: 0 }}>
            © {new Date().getFullYear()} 智教云 · Education Platform. All rights reserved.
          </Typography.Paragraph>
        </div>
      </Footer>
    </Layout>
  );
}

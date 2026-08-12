import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import KnowledgeLayout from '../layouts/KnowledgeLayout';

// ErrorBoundary from @ep/ui-kit imports @tarojs/components (View), which is
// not available in this Web app's test environment. Provide a minimal mock.
vi.mock('@ep/ui-kit', () => ({
  ErrorBoundary: ({ children }: { children: ReactNode }) => children,
}));

function renderLayout(path = '/knowledge-bases') {
  return render(
    <ConfigProvider locale={zhCN}>
      <MemoryRouter initialEntries={[path]}>
        <KnowledgeLayout />
      </MemoryRouter>
    </ConfigProvider>,
  );
}

describe('knowledge-admin KnowledgeLayout', () => {
  it('renders the sider menu with knowledge base management entry', () => {
    renderLayout();
    expect(screen.getByText('知识库管理')).toBeTruthy();
    expect(screen.getByText('知识中台')).toBeTruthy();
  });

  it('renders all seven menu items', () => {
    renderLayout();
    expect(screen.getByText('文档上传')).toBeTruthy();
    expect(screen.getByText('智能检索')).toBeTruthy();
    expect(screen.getByText('智能体管理')).toBeTruthy();
    expect(screen.getByText('问答社区')).toBeTruthy();
    expect(screen.getByText('租户管理')).toBeTruthy();
    expect(screen.getByText('学习路径')).toBeTruthy();
  });

  it('renders without crash', () => {
    const { container } = renderLayout();
    expect(container).toBeTruthy();
  });
});

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import type { ReactNode } from 'react';
import PortalLayout from './PortalLayout';

function renderLayout(initialPath = '/') {
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <ConfigProvider locale={zhCN}>
        <MemoryRouter initialEntries={[initialPath]}>{children}</MemoryRouter>
      </ConfigProvider>
    );
  }
  return render(<PortalLayout />, { wrapper: Wrapper });
}

describe('PortalLayout', () => {
  it('renders the brand logo and primary nav links', () => {
    renderLayout('/');

    // Multiple elements contain "智教云" (header brand + footer title);
    // verify at least one exists.
    const brandElements = screen.getAllByText('智教云');
    expect(brandElements.length).toBeGreaterThan(0);
    // Nav links — footer also contains links with overlapping names, so use
    // getAllByRole and assert at least one link points to each target.
    const homeLinks = screen.getAllByRole('link', { name: '首页' });
    expect(homeLinks.some((el) => el.getAttribute('href') === '/')).toBe(true);

    const courseLinks = screen.getAllByRole('link', { name: '课程' });
    expect(courseLinks.some((el) => el.getAttribute('href') === '/courses')).toBe(true);

    const buLinks = screen.getAllByRole('link', { name: '事业群' });
    expect(buLinks.some((el) => el.getAttribute('href') === '/bu')).toBe(true);

    const aboutLinks = screen.getAllByRole('link', { name: '关于' });
    expect(aboutLinks.some((el) => el.getAttribute('href') === '/about')).toBe(true);
  });

  it('renders login buttons in the header', () => {
    renderLayout('/');
    const loginButtons = screen.getAllByRole('button', { name: /登录/ });
    expect(loginButtons.length).toBeGreaterThan(0);
  });

  it('renders footer with copyright', () => {
    renderLayout('/');
    expect(screen.getByText(/All rights reserved/)).toBeInTheDocument();
  });
});

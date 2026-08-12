import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { ConfigProvider, App as AntApp } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { AuthGuard } from '../routes/AuthGuard';
import { UserProvider } from '../stores/UserContext';
import type { ReactNode } from 'react';
import type { User } from '@ep/shared';

const adminUser: User = {
  id: 'u1',
  username: 'admin',
  nickname: '管理员',
  avatar: '',
  role: 'admin',
  cellId: 'cell-1',
  email: 'admin@example.com',
  phone: null,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

const teacherUser: User = {
  ...adminUser,
  username: 'teacher',
  role: 'teacher',
};

function renderAt(path: string, user: User | null) {
  // Wrapper that injects the user into the UserProvider on mount.
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <ConfigProvider locale={zhCN}>
        <AntApp>
          <UserProvider initialUser={user}>{children}</UserProvider>
        </AntApp>
      </ConfigProvider>
    );
  }

  return render(
    <Wrapper>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/login" element={<div data-testid="login-page">Login</div>} />
          <Route path="/403" element={<div data-testid="forbidden-page">Forbidden</div>} />
          <Route
            path="/*"
            element={
              <AuthGuard>
                <div data-testid="protected-content">Protected</div>
              </AuthGuard>
            }
          />
        </Routes>
      </MemoryRouter>
    </Wrapper>,
  );
}

describe('AuthGuard', () => {
  it('redirects to /login when no user', () => {
    renderAt('/dashboard', null);
    expect(screen.getByTestId('login-page')).toBeDefined();
    expect(screen.queryByTestId('protected-content')).toBeNull();
  });

  it('renders protected content when user is present', () => {
    renderAt('/dashboard', adminUser);
    expect(screen.getByTestId('protected-content')).toBeDefined();
  });

  it('redirects to /403 when role is insufficient', () => {
    renderAt('/settings', teacherUser);
    expect(screen.getByTestId('forbidden-page')).toBeDefined();
    expect(screen.queryByTestId('protected-content')).toBeNull();
  });
});

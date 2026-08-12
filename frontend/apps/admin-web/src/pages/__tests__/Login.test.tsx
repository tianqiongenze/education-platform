import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ConfigProvider, App as AntApp } from 'antd';
import zhCN from 'antd/locale/zh_CN';

const { loginMutationMock } = vi.hoisted(() => ({
  loginMutationMock: {
    mutateAsync: vi.fn(async () => ({
      accessToken: 'a',
      refreshToken: 'r',
      expiresIn: 3600,
    })),
    isPending: false,
  },
}));

vi.mock('@ep/query', () => ({
  useLogin: vi.fn(() => loginMutationMock),
}));

vi.mock('../../api/client', () => ({
  apiClient: {
    auth: { login: vi.fn() },
    get: vi.fn(async () => ({
      id: 'u1',
      username: 'admin',
      nickname: '管理员',
      avatar: '',
      role: 'admin',
      cellId: 'cell-1',
      email: null,
      phone: null,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    })),
  },
}));

import LoginPage from '../Login';
import { useLogin } from '@ep/query';
import { UserProvider } from '../../stores/UserContext';

function renderPage() {
  return render(
    <ConfigProvider locale={zhCN}>
      <AntApp>
        <MemoryRouter>
          <UserProvider>
            <LoginPage />
          </UserProvider>
        </MemoryRouter>
      </AntApp>
    </ConfigProvider>,
  );
}

describe('LoginPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders username and password fields and submit button', () => {
    renderPage();
    expect(screen.getByText('EP Admin')).toBeDefined();
    expect(screen.getByPlaceholderText('用户名')).toBeDefined();
    expect(screen.getByPlaceholderText('密码')).toBeDefined();
    expect(screen.getByRole('button', { name: /登\s*录/ })).toBeDefined();
  });

  it('shows validation error when submitting empty form', async () => {
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: /登\s*录/ }));
    await waitFor(() => {
      expect(screen.getByText('请输入用户名')).toBeDefined();
      expect(screen.getByText('请输入密码')).toBeDefined();
    });
  });

  it('calls login mutation on submit with valid values', async () => {
    const mutateAsync = vi.fn(async () => ({
      accessToken: 'a',
      refreshToken: 'r',
      expiresIn: 3600,
    }));
    vi.mocked(useLogin).mockReturnValue({ mutateAsync, isPending: false } as never);

    const { container } = renderPage();

    // AntD Form uses rc-field-form which doesn't react to fireEvent.change.
    // We must set the value via the native input setter and dispatch an input event.
    const inputs = container.querySelectorAll('input');
    const usernameInput = inputs[0]!;
    const passwordInput = inputs[1]!;

    const nativeInputSetter = Object.getOwnPropertyDescriptor(
      window.HTMLInputElement.prototype,
      'value',
    )?.set;

    nativeInputSetter?.call(usernameInput, 'admin');
    usernameInput.dispatchEvent(new Event('input', { bubbles: true }));

    nativeInputSetter?.call(passwordInput, 'password123');
    passwordInput.dispatchEvent(new Event('input', { bubbles: true }));

    // Wait for AntD form to register the field values
    await new Promise((r) => setTimeout(r, 50));

    fireEvent.click(screen.getByRole('button', { name: /登\s*录/ }));

    await waitFor(
      () => {
        expect(mutateAsync).toHaveBeenCalledWith({
          username: 'admin',
          password: 'password123',
        });
      },
      { timeout: 3000 },
    );
  });
});

import { describe, it, expect, vi } from 'vitest';

vi.mock('@tarojs/taro', () => ({
  useLaunch: vi.fn(),
  switchTab: vi.fn(),
  navigateTo: vi.fn(),
  getStorageSync: vi.fn(() => null),
  setStorageSync: vi.fn(),
  removeStorageSync: vi.fn(),
  showToast: vi.fn(),
  request: vi.fn(),
  default: { navigateTo: vi.fn(), switchTab: vi.fn(), request: vi.fn() },
}));

vi.mock('@ep/query', () => ({
  QueryProvider: ({ children }: { children: React.ReactNode }) => children,
}));

vi.mock('@ep/ui-kit', () => ({
  ErrorBoundary: ({ children }: { children: React.ReactNode }) => children,
}));

describe('knowledge-mp App', () => {
  it('should be importable without crash', async () => {
    const mod = await import('../app');
    expect(mod.default).toBeDefined();
  });
});

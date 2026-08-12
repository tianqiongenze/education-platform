import { createElement } from 'react';
import { vi, describe, it, expect, beforeEach } from 'vitest';
import { render } from '@testing-library/react';

vi.mock('@tarojs/components', () => ({
  View: ({ children, className }: any) => createElement('div', { className }, children),
  Text: ({ children, className }: any) => createElement('span', { className }, children),
  Image: (props: any) => createElement('img', props),
  Swiper: ({ children }: any) => createElement('div', null, children),
  SwiperItem: ({ children }: any) => createElement('div', null, children),
  Input: (props: any) => createElement('input', props),
  Textarea: (props: any) => createElement('textarea', props),
  ScrollView: ({ children, className }: any) => createElement('div', { className }, children),
  LivePlayer: (props: any) => createElement('div', props),
}));

const { taroMock } = vi.hoisted(() => ({
  taroMock: {
    useLaunch: vi.fn(),
    useDidShow: vi.fn(),
    usePullDownRefresh: vi.fn(),
    useReachBottom: vi.fn(),
    useRouter: vi.fn(() => ({ params: {} })),
    navigateTo: vi.fn(),
    redirectTo: vi.fn(),
    reLaunch: vi.fn(),
    switchTab: vi.fn(),
    showToast: vi.fn(),
    getStorageSync: vi.fn(() => null),
    setStorageSync: vi.fn(),
    removeStorageSync: vi.fn(),
    request: vi.fn(),
    login: vi.fn(),
    chooseImage: vi.fn(),
    stopPullDownRefresh: vi.fn(),
    ENV_TYPE: { WEAPP: 'WEAPP', H5: 'H5' },
  },
}));

vi.mock('@tarojs/taro', () => ({
  default: taroMock,
  ...taroMock,
}));

vi.mock('@ep/query', () => ({
  useCourseList: vi.fn(() => ({
    data: {
      items: [
        {
          id: 'c1',
          title: '测试课程',
          teacherName: '老师',
          coverImage: '',
          enrollmentCount: 10,
          rating: 4.5,
        },
      ],
      total: 1,
    },
    isLoading: false,
  })),
  useLiveRooms: vi.fn(() => ({
    data: { items: [] },
    isLoading: false,
  })),
  useUserProfile: vi.fn(() => ({ data: undefined, isLoading: false })),
  useSubmitAssignment: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
  useLogin: vi.fn(() => ({ mutateAsync: vi.fn() })),
}));

vi.mock('@ep/ui-kit', () => ({
  Loading: () => createElement('div', { 'data-testid': 'loading' }),
  Empty: ({ description }: any) => createElement('div', null, description),
  ErrorBoundary: ({ children }: any) => createElement('div', null, children),
  VideoPlayer: () => createElement('div', { 'data-testid': 'video' }),
}));

vi.mock('@ep/shared', () => ({
  formatNumber: (n: number) => String(n),
  formatDate: (s: string) => s,
  STORAGE_KEYS: { ACCESS_TOKEN: 'a', REFRESH_TOKEN: 'r' },
}));

vi.mock('@/api/client', () => ({
  apiClient: {},
}));

vi.mock('../../api/client', () => ({
  apiClient: {},
}));

import IndexPage from '../index/index';

describe('Home page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders recommended courses', () => {
    const { container } = render(createElement(IndexPage));
    expect(container).toBeDefined();
    expect(container.textContent).toContain('测试课程');
  });

  it('renders section titles', () => {
    const { container } = render(createElement(IndexPage));
    expect(container.textContent).toContain('推荐课程');
  });
});

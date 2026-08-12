import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import type { ReactNode } from 'react';

// Mock @ep/query so we don't hit the network in unit tests.
vi.mock('@ep/query', () => ({
  useCourseList: vi.fn(),
  QueryProvider: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

import { useCourseList } from '@ep/query';
import HomePage from './HomePage';

const mockedUseCourseList = vi.mocked(useCourseList);

function renderHome() {
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <ConfigProvider locale={zhCN}>
        <MemoryRouter>{children}</MemoryRouter>
      </ConfigProvider>
    );
  }
  return render(<HomePage />, { wrapper: Wrapper });
}

describe('HomePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders the hero section with heading and CTA buttons', () => {
    mockedUseCourseList.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: false,
    } as unknown as ReturnType<typeof useCourseList>);

    renderHome();

    expect(screen.getByTestId('hero-section')).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { level: 1, name: /全场景在线教育平台/ }),
    ).toBeInTheDocument();
    expect(screen.getByTestId('hero-courses-btn')).toHaveTextContent('浏览课程');
    expect(screen.getByTestId('hero-login-btn')).toHaveTextContent('立即登录');
  });

  it('renders course cards from the API when available', () => {
    mockedUseCourseList.mockReturnValue({
      data: {
        items: [
          {
            id: 'c1',
            title: '测试课程 A',
            description: '一门测试课程',
            coverImage: 'https://example.com/a.png',
            status: 'published',
            teacherId: 't1',
            teacherName: '张老师',
            price: 0,
            enrollmentCount: 100,
            rating: 4.5,
            tags: ['测试'],
            chapters: [],
            createdAt: '',
            updatedAt: '',
          },
        ],
        total: 1,
        page: 1,
        pageSize: 4,
        hasMore: false,
      },
      isLoading: false,
      isError: false,
    } as unknown as ReturnType<typeof useCourseList>);

    renderHome();

    expect(screen.getByText('测试课程 A')).toBeInTheDocument();
  });

  it('links to the catalog via the "查看全部课程" link', () => {
    mockedUseCourseList.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
    } as unknown as ReturnType<typeof useCourseList>);

    renderHome();

    const catalogLink = screen.getByRole('link', { name: /查看全部课程/ });
    expect(catalogLink).toHaveAttribute('href', '/courses');
  });
});

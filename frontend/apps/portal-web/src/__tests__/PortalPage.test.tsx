import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import type { ReactNode } from 'react';

// Mock the course list fetcher so the landing page renders deterministic
// content without hitting the network.
vi.mock('@ep/query', () => ({
  useCourseList: vi.fn(),
  QueryProvider: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

import { useCourseList } from '@ep/query';
import App from '../App';

const mockedUseCourseList = vi.mocked(useCourseList);

// App does not own a Router (main.tsx wraps it in BrowserRouter), so the test
// must supply one. We default to the "/" route to exercise the landing page.
function renderApp(initialPath = '/') {
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <ConfigProvider locale={zhCN}>
        <MemoryRouter initialEntries={[initialPath]}>{children}</MemoryRouter>
      </ConfigProvider>
    );
  }
  return render(<App />, { wrapper: Wrapper });
}

describe('PortalPage (landing page)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Default: no live course data, not loading. HomePage will fall back to
    // static featured courses.
    mockedUseCourseList.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: false,
    } as unknown as ReturnType<typeof useCourseList>);
  });

  describe('rendering', () => {
    it('renders the hero section with brand, heading and CTAs', () => {
      renderApp();

      const hero = screen.getByTestId('hero-section');
      expect(hero).toBeInTheDocument();
      expect(
        within(hero).getByRole('heading', { level: 1, name: /全场景在线教育平台/ }),
      ).toBeInTheDocument();
      expect(within(hero).getByTestId('hero-courses-btn')).toHaveTextContent('浏览课程');
      expect(within(hero).getByTestId('hero-login-btn')).toHaveTextContent('立即登录');
    });

    it('renders the brand logo and top-level navigation', () => {
      renderApp();

      // Brand appears in both header and footer.
      const brand = screen.getAllByText('智教云');
      expect(brand.length).toBeGreaterThan(0);

      // Nav targets exist somewhere on the page.
      const courseLinks = screen.getAllByRole('link', { name: '课程' });
      expect(courseLinks.some((el) => el.getAttribute('href') === '/courses')).toBe(true);
      const aboutLinks = screen.getAllByRole('link', { name: '关于' });
      expect(aboutLinks.some((el) => el.getAttribute('href') === '/about')).toBe(true);
    });

    it('renders the business-unit entry cards section', () => {
      renderApp();

      // The landing page exposes a section linking to each business unit.
      expect(screen.getByText('事业群入口')).toBeInTheDocument();
      expect(screen.getByText('业务中台')).toBeInTheDocument();
      expect(screen.getByText('IT 培训')).toBeInTheDocument();
      expect(screen.getByText('知识中台')).toBeInTheDocument();
    });
  });

  describe('navigation', () => {
    it('hero CTAs link to /courses and /login', () => {
      renderApp();

      // The "浏览课程" button is wrapped in a Link to /courses.
      const coursesLink = screen.getByTestId('hero-courses-btn').closest('a');
      expect(coursesLink).toHaveAttribute('href', '/courses');
      // The "立即登录" button is wrapped in a Link to /login.
      const loginLink = screen.getByTestId('hero-login-btn').closest('a');
      expect(loginLink).toHaveAttribute('href', '/login');
    });

    it('exposes a "查看全部课程" link to the catalog', () => {
      renderApp();

      const catalogLink = screen.getByRole('link', { name: /查看全部课程/ });
      expect(catalogLink).toHaveAttribute('href', '/courses');
    });

    it('renders login/register buttons in the header', () => {
      renderApp();

      const loginButtons = screen.getAllByRole('button', { name: /登录/ });
      expect(loginButtons.length).toBeGreaterThan(0);
      expect(screen.getByRole('button', { name: /免费注册/ })).toBeInTheDocument();
    });
  });

  describe('data display', () => {
    it('renders featured course cards when the API returns items', () => {
      mockedUseCourseList.mockReturnValue({
        data: {
          items: [
            {
              id: 'c1',
              title: 'React 入门',
              description: '从零开始学 React',
              coverImage: 'https://example.com/a.png',
              status: 'published',
              teacherId: 't1',
              teacherName: '张老师',
              price: 0,
              enrollmentCount: 100,
              rating: 4.5,
              tags: ['前端', '入门'],
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

      renderApp();

      expect(screen.getByText('React 入门')).toBeInTheDocument();
    });

    it('shows the copyright line in the footer with the current year', () => {
      renderApp();

      expect(screen.getByText(/All rights reserved/)).toBeInTheDocument();
      expect(screen.getByText(new RegExp(String(new Date().getFullYear())))).toBeInTheDocument();
    });
  });
});

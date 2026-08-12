import { createElement } from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

// Taro components are not available in the jsdom Web build; map them to plain
// DOM elements so the page tree renders in tests.
vi.mock('@tarojs/components', () => ({
  View: ({ children, className, onClick }: any) =>
    createElement('div', { className, onClick }, children),
  Text: ({ children, className }: any) =>
    createElement('span', { className }, children),
  Image: (props: any) => createElement('img', props),
  ScrollView: ({ children, className }: any) =>
    createElement('div', { className }, children),
  Input: (props: any) => createElement('input', props),
}));

// vi.hoisted() ensures the mock object exists when the hoisted vi.mock
// factory runs (factories run before any top-level code in this file).
const { taroMock } = vi.hoisted(() => ({
  taroMock: {
    useLaunch: vi.fn(),
    navigateTo: vi.fn(),
    switchTab: vi.fn(),
    getStorageSync: vi.fn(() => null),
    setStorageSync: vi.fn(),
    removeStorageSync: vi.fn(),
    showToast: vi.fn(),
    request: vi.fn(),
  },
}));

vi.mock('@tarojs/taro', () => ({
  default: taroMock,
  ...taroMock,
}));

vi.mock('@ep/query', () => ({
  QueryProvider: ({ children }: { children: React.ReactNode }) => children,
  useCourseList: vi.fn(),
}));

vi.mock('@ep/ui-kit', () => ({
  ErrorBoundary: ({ children }: { children: React.ReactNode }) =>
    createElement('div', null, children),
}));

import App from '../app';
import IndexPage from '../pages/index/index';
import { useCourseList } from '@ep/query';

const mockedUseCourseList = vi.mocked(useCourseList);

describe('student-h5 App', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('render', () => {
    it('should be importable and render children without crash', () => {
      const { container } = render(
        <App>
          <div data-testid="child">child content</div>
        </App>,
      );
      expect(container).toBeTruthy();
      expect(screen.getByTestId('child')).toBeDefined();
    });

    it('renders the hero section of the index page', () => {
      mockedUseCourseList.mockReturnValue({
        data: undefined,
        isLoading: false,
        isError: false,
      } as any);

      render(
        <App>
          <IndexPage />
        </App>,
      );

      expect(screen.getByText('在线学习平台')).toBeTruthy();
      expect(screen.getByText('优质课程，随时随地学习')).toBeTruthy();
    });
  });

  describe('data display', () => {
    it('renders course cards from the fetched course list', () => {
      mockedUseCourseList.mockReturnValue({
        data: {
          items: [
            {
              id: 'c1',
              title: '测试课程',
              teacherName: '王老师',
              rating: 4.5,
              enrollmentCount: 120,
            },
          ],
        },
        isLoading: false,
        isError: false,
      } as any);

      render(
        <App>
          <IndexPage />
        </App>,
      );

      expect(screen.getByText('测试课程')).toBeTruthy();
      expect(screen.getByText('王老师')).toBeTruthy();
      expect(screen.getByText(/120 人报名/)).toBeTruthy();
    });

    it('shows loading text while fetching', () => {
      mockedUseCourseList.mockReturnValue({
        data: undefined,
        isLoading: true,
        isError: false,
      } as any);

      render(
        <App>
          <IndexPage />
        </App>,
      );

      expect(screen.getByText('加载中...')).toBeTruthy();
    });

    it('shows empty text when no courses are returned', () => {
      mockedUseCourseList.mockReturnValue({
        data: { items: [] },
        isLoading: false,
        isError: false,
      } as any);

      render(
        <App>
          <IndexPage />
        </App>,
      );

      expect(screen.getByText('暂无课程')).toBeTruthy();
    });
  });

  describe('navigation', () => {
    it('navigates to course detail when a course card is tapped', () => {
      mockedUseCourseList.mockReturnValue({
        data: {
          items: [
            {
              id: 'c1',
              title: '测试课程',
              teacherName: '王老师',
              rating: 4.5,
              enrollmentCount: 1,
            },
          ],
        },
        isLoading: false,
        isError: false,
      } as any);

      render(
        <App>
          <IndexPage />
        </App>,
      );

      // The course card is the element wrapping the course title.
      const card = screen.getByText('测试课程').closest('div')!;
      fireEvent.click(card);

      expect(taroMock.navigateTo).toHaveBeenCalledWith({
        url: '/pages/course-detail/index?id=c1',
      });
    });
  });
});

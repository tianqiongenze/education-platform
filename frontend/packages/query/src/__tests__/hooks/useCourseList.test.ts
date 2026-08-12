import { describe, it, expect, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { useCourseList } from '../../hooks/useCourseList';
import type { Pagination, Course } from '@ep/shared';

interface MockApiClient {
  get: ReturnType<typeof vi.fn>;
}

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children);
}

describe('useCourseList', () => {
  it('returns data from client.get', async () => {
    const mockData: Pagination<Course> = {
      items: [
        {
          id: 'c1',
          title: 'Course 1',
          description: 'desc',
          coverImage: '',
          status: 'published',
          teacherId: 't1',
          teacherName: 'Teacher',
          price: 100,
          enrollmentCount: 0,
          rating: 0,
          tags: [],
          chapters: [],
          createdAt: '',
          updatedAt: '',
        },
      ],
      total: 1,
      page: 1,
      pageSize: 20,
      hasMore: false,
    };

    const client: MockApiClient = {
      get: vi.fn().mockResolvedValue(mockData),
    };

    const { result } = renderHook(
      () => useCourseList(client as unknown as Parameters<typeof useCourseList>[0], { page: 1 }),
      { wrapper: createWrapper() },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(client.get).toHaveBeenCalledWith('/api/course/list', { page: 1 });
    expect(result.current.data).toEqual(mockData);
  });

  it('uses the correct query key', async () => {
    const client: MockApiClient = {
      get: vi.fn().mockResolvedValue({ items: [], total: 0, page: 1, pageSize: 20, hasMore: false }),
    };

    const { result } = renderHook(
      () =>
        useCourseList(client as unknown as Parameters<typeof useCourseList>[0], {
          page: 2,
          pageSize: 10,
          keyword: 'math',
        }),
      { wrapper: createWrapper() },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(client.get).toHaveBeenCalledWith('/api/course/list', {
      page: 2,
      pageSize: 10,
      keyword: 'math',
    });
  });

  it('surfaces errors', async () => {
    const client: MockApiClient = {
      get: vi.fn().mockRejectedValue(new Error('network')),
    };

    const { result } = renderHook(
      () => useCourseList(client as unknown as Parameters<typeof useCourseList>[0]),
      { wrapper: createWrapper() },
    );

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error).toBeDefined();
  });
});

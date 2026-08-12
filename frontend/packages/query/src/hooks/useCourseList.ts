import { useQuery, keepPreviousData } from '@tanstack/react-query';
import { queryKeys } from '../keys';
import type { ApiClient } from '@ep/api-client';
import type { Pagination, Course } from '@ep/shared';

interface UseCourseListParams {
  page?: number;
  pageSize?: number;
  keyword?: string;
  tag?: string;
}

export function useCourseList(client: ApiClient, params: UseCourseListParams = {}) {
  return useQuery({
    queryKey: queryKeys.courses.list(params),
    queryFn: async (): Promise<Pagination<Course>> => {
      return client.get<Pagination<Course>>('/api/course/list', params as Record<string, unknown>);
    },
    placeholderData: keepPreviousData,
  });
}

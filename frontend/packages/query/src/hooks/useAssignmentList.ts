import { useQuery, keepPreviousData } from '@tanstack/react-query';
import { queryKeys } from '../keys';
import type { ApiClient } from '@ep/api-client';
import type { Pagination, Assignment } from '@ep/shared';

interface UseAssignmentListParams {
  page?: number;
  pageSize?: number;
}

export function useAssignmentList(
  client: ApiClient,
  courseId?: string,
  params: UseAssignmentListParams = {},
) {
  return useQuery({
    queryKey: queryKeys.assignments.list(courseId, params),
    queryFn: async (): Promise<Pagination<Assignment>> => {
      const query: Record<string, unknown> = { ...params };
      if (courseId) query.courseId = courseId;
      return client.get<Pagination<Assignment>>('/api/assignment/list', query);
    },
    placeholderData: keepPreviousData,
  });
}

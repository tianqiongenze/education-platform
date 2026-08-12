import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '../keys';
import type { ApiClient } from '@ep/api-client';
import type { Course } from '@ep/shared';

export function useCourseDetail(client: ApiClient, courseId: string) {
  return useQuery({
    queryKey: queryKeys.courses.detail(courseId),
    queryFn: async (): Promise<Course> => {
      return client.get<Course>(`/api/course/${courseId}`);
    },
    enabled: Boolean(courseId),
  });
}

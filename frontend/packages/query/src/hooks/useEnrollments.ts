import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '../keys';
import type { ApiClient } from '@ep/api-client';

export function useEnrollments(client: ApiClient) {
  return useQuery({
    queryKey: queryKeys.user.enrollments(),
    queryFn: async (): Promise<string[]> => {
      return client.get<string[]>('/api/user/enrollments');
    },
  });
}

import { useQuery } from '@tanstack/react-query';
import { queryKeys } from '../keys';
import type { ApiClient } from '@ep/api-client';
import type { UserProfile } from '@ep/shared';

export function useUserProfile(client: ApiClient) {
  return useQuery({
    queryKey: queryKeys.user.profile(),
    queryFn: async (): Promise<UserProfile> => {
      return client.get<UserProfile>('/api/user/profile');
    },
  });
}

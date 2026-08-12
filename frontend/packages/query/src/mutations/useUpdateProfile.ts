import { useMutation, useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '../keys';
import type { ApiClient } from '@ep/api-client';
import type { UserProfile } from '@ep/shared';

export type UpdateProfileInput = Partial<
  Pick<UserProfile, 'nickname' | 'avatar' | 'bio' | 'email' | 'phone'>
>;

export function useUpdateProfile(client: ApiClient) {
  const qc = useQueryClient();

  return useMutation({
    mutationFn: async (input: UpdateProfileInput): Promise<unknown> => {
      return client.put('/api/user/profile', input);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: queryKeys.user.profile() });
    },
  });
}

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '../keys';
import type { ApiClient } from '@ep/api-client';

export function useEnrollCourse(client: ApiClient) {
  const qc = useQueryClient();

  return useMutation({
    mutationFn: async (courseId: string): Promise<void> => {
      await client.post('/api/course/enroll', { courseId });
    },

    onMutate: async (courseId: string) => {
      await qc.cancelQueries({ queryKey: queryKeys.user.enrollments() });
      const prev = qc.getQueryData<string[]>(queryKeys.user.enrollments());
      qc.setQueryData<string[]>(queryKeys.user.enrollments(), (old = []) => [
        ...old,
        courseId,
      ]);
      return prev !== undefined ? { prev } : {};
    },

    onError: (_err: unknown, _courseId: string, ctx: { prev?: string[] } | undefined) => {
      if (ctx?.prev) {
        qc.setQueryData(queryKeys.user.enrollments(), ctx.prev);
      }
    },

    onSettled: () => {
      qc.invalidateQueries({ queryKey: queryKeys.user.enrollments() });
    },
  });
}

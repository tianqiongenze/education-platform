import { useMutation, useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '../keys';
import type { ApiClient } from '@ep/api-client';

export interface SubmitAssignmentInput {
  assignmentId: string;
  content: string;
  attachments?: string[];
}

export function useSubmitAssignment(client: ApiClient) {
  const qc = useQueryClient();

  return useMutation({
    mutationFn: async (input: SubmitAssignmentInput): Promise<unknown> => {
      return client.post(`/api/assignment/${input.assignmentId}/submit`, {
        content: input.content,
        attachments: input.attachments ?? [],
      });
    },
    onSuccess: (_data, input) => {
      qc.invalidateQueries({ queryKey: queryKeys.assignments.detail(input.assignmentId) });
    },
  });
}

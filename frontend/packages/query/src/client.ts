import { QueryClient } from '@tanstack/react-query';
import { ApiError } from '@ep/api-client';

/**
 * Duck-type an error as an API error carrying an HTTP status code.
 * Accepts ApiError instances and any error object with a numeric `status` property.
 */
function getErrorStatus(error: unknown): number | undefined {
  if (error instanceof ApiError) return error.status;
  if (
    typeof error === 'object' &&
    error !== null &&
    'status' in error &&
    typeof (error as { status: unknown }).status === 'number'
  ) {
    return (error as { status: number }).status;
  }
  return undefined;
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 5 * 60 * 1000, // 5 min
        gcTime: 30 * 60 * 1000, // 30 min
        retry: (failureCount: number, error: unknown) => {
          const status = getErrorStatus(error);
          if (status !== undefined) {
            if (status >= 400 && status < 500) {
              return status === 401 || status === 429;
            }
          }
          return failureCount < 3;
        },
        retryDelay: (attempt: number) => Math.min(1000 * 2 ** attempt, 32000),
        refetchOnWindowFocus: true,
        refetchOnReconnect: true,
      },
      mutations: {
        retry: 1,
        retryDelay: 1000,
      },
    },
  });
}

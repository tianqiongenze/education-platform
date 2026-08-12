import { useQuery, keepPreviousData } from '@tanstack/react-query';
import { queryKeys } from '../keys';
import type { ApiClient } from '@ep/api-client';
import type { Pagination, LiveRoom } from '@ep/shared';

interface UseLiveRoomsParams {
  page?: number;
  pageSize?: number;
}

export function useLiveRooms(client: ApiClient, params: UseLiveRoomsParams = {}) {
  return useQuery({
    queryKey: queryKeys.live.rooms(params),
    queryFn: async (): Promise<Pagination<LiveRoom>> => {
      return client.get<Pagination<LiveRoom>>('/api/live/rooms', params as Record<string, unknown>);
    },
    placeholderData: keepPreviousData,
  });
}

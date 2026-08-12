export const queryKeys = {
  courses: {
    all: ['courses'] as const,
    list: (params?: { page?: number; pageSize?: number; keyword?: string; tag?: string }) =>
      ['courses', 'list', params ?? {}] as const,
    detail: (id: string) => ['courses', 'detail', id] as const,
  },
  user: {
    profile: () => ['user', 'profile'] as const,
    enrollments: () => ['user', 'enrollments'] as const,
  },
  assignments: {
    list: (courseId?: string, params?: { page?: number; pageSize?: number }) =>
      ['assignments', 'list', courseId ?? 'all', params ?? {}] as const,
    detail: (id: string) => ['assignments', 'detail', id] as const,
  },
  live: {
    rooms: (params?: { page?: number; pageSize?: number }) =>
      ['live', 'rooms', params ?? {}] as const,
    room: (id: string) => ['live', 'room', id] as const,
  },
} as const;

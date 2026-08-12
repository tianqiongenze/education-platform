export const API_PATHS = {
  AUTH: {
    LOGIN: '/api/auth/login',
    REFRESH: '/api/auth/refresh',
    LOGOUT: '/api/auth/logout',
    WX_LOGIN: '/api/auth/wx-login',
  },
  USER: '/api/user',
  COURSE: '/api/course',
  ASSIGNMENT: '/api/assignment',
  LIVE: '/api/live',
  AI: '/api/ai',
} as const;

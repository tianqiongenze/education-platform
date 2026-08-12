import type { ClientConfig, TokenResponse } from './types';

const APP_VERSION = '1.0.0';

export function generateTraceId(): string {
  try {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID();
    }
  } catch {
    // crypto may be unavailable in some environments
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

export interface AuthInterceptor {
  (url: string, init: RequestInit): Promise<RequestInit>;
  refresh(): Promise<boolean>;
}

export function createAuthInterceptor(config: ClientConfig): AuthInterceptor {
  let refreshing: Promise<boolean> | null = null;

  async function inject(_url: string, init: RequestInit): Promise<RequestInit> {
    const headers = new Headers(init.headers ?? undefined);
    const token = config.tokenStorage.getAccessToken();
    if (token) {
      headers.set('Authorization', `Bearer ${token}`);
    }
    const cellId = config.getCellId?.();
    if (cellId) {
      headers.set('X-Cell-Routing', cellId);
    }
    headers.set('X-Trace-Id', generateTraceId());
    headers.set('X-Client-Version', APP_VERSION);
    return { ...init, headers };
  }

  async function refresh(): Promise<boolean> {
    // Deduplicate concurrent refresh calls: all callers share one promise.
    if (refreshing) {
      return refreshing;
    }

    refreshing = doRefresh(config);
    try {
      const result = await refreshing;
      return result;
    } finally {
      refreshing = null;
    }
  }

  return Object.assign(inject, { refresh });
}

async function doRefresh(config: ClientConfig): Promise<boolean> {
  const refreshToken = config.tokenStorage.getRefreshToken();
  if (!refreshToken) {
    return false;
  }

  const refreshUrl = config.refreshUrl ?? '/api/auth/refresh';

  try {
    const res = await config.fetcher(`${config.apiBaseUrl}${refreshUrl}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    });

    const tokens = res as TokenResponse;
    if (!tokens || typeof tokens.accessToken !== 'string' || typeof tokens.refreshToken !== 'string') {
      config.tokenStorage.clear();
      return false;
    }
    config.tokenStorage.setAccessToken(tokens.accessToken);
    config.tokenStorage.setRefreshToken(tokens.refreshToken);
    return true;
  } catch {
    config.tokenStorage.clear();
    return false;
  }
}

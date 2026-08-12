import { ApiError } from './error';
import { createAuthInterceptor } from './auth';
import type { AuthInterceptor } from './auth';
import { createValidationInterceptor } from './validate';
import type { ClientConfig, TokenResponse } from './types';

export interface ApiClient {
  get<T>(path: string, params?: Record<string, unknown>): Promise<T>;
  post<T>(path: string, body?: unknown): Promise<T>;
  put<T>(path: string, body?: unknown): Promise<T>;
  delete<T>(path: string): Promise<T>;
  auth: {
    login(username: string, password: string): Promise<TokenResponse>;
    wxLogin(code: string): Promise<TokenResponse>;
    refresh(): Promise<boolean>;
  };
}

export function createClient(config: ClientConfig): ApiClient {
  const authInterceptor: AuthInterceptor = createAuthInterceptor(config);
  const validationInterceptor = createValidationInterceptor(config);

  function buildUrl(path: string, params?: Record<string, unknown>): string {
    const fullUrl = path.startsWith('http') ? path : `${config.apiBaseUrl}${path}`;
    if (!params) return fullUrl;
    const searchParams = new URLSearchParams();
    for (const [key, value] of Object.entries(params)) {
      if (value === undefined || value === null) continue;
      searchParams.append(key, String(value));
    }
    const qs = searchParams.toString();
    if (!qs) return fullUrl;
    return fullUrl.includes('?') ? `${fullUrl}&${qs}` : `${fullUrl}?${qs}`;
  }

  async function request<T>(path: string, init: RequestInit): Promise<T> {
    const url = path.startsWith('http') ? path : `${config.apiBaseUrl}${path}`;
    const authedInit = await authInterceptor(url, init);

    let result: unknown;
    try {
      result = await config.fetcher(url, authedInit);
    } catch (err) {
      if (err instanceof ApiError && err.isUnauthorized()) {
        const refreshed = await authInterceptor.refresh();
        if (refreshed) {
          const reAuthedInit = await authInterceptor(url, init);
          result = await config.fetcher(url, reAuthedInit);
        } else {
          throw err;
        }
      } else {
        throw err;
      }
    }

    return validationInterceptor(url, result) as T;
  }

  return {
    get<T>(path: string, params?: Record<string, unknown>): Promise<T> {
      const url = buildUrl(path, params);
      return request<T>(url, { method: 'GET' });
    },
    post<T>(path: string, body?: unknown): Promise<T> {
      const init: RequestInit = { method: 'POST' };
      if (body !== undefined) {
        init.body = JSON.stringify(body);
        init.headers = { 'Content-Type': 'application/json' };
      }
      return request<T>(path, init);
    },
    put<T>(path: string, body?: unknown): Promise<T> {
      const init: RequestInit = { method: 'PUT' };
      if (body !== undefined) {
        init.body = JSON.stringify(body);
        init.headers = { 'Content-Type': 'application/json' };
      }
      return request<T>(path, init);
    },
    delete<T>(path: string): Promise<T> {
      return request<T>(path, { method: 'DELETE' });
    },
    auth: {
      async login(username: string, password: string): Promise<TokenResponse> {
        return request<TokenResponse>('/api/auth/login', {
          method: 'POST',
          body: JSON.stringify({ username, password }),
          headers: { 'Content-Type': 'application/json' },
        });
      },
      async wxLogin(code: string): Promise<TokenResponse> {
        return request<TokenResponse>('/api/auth/wx-login', {
          method: 'POST',
          body: JSON.stringify({ code }),
          headers: { 'Content-Type': 'application/json' },
        });
      },
      refresh(): Promise<boolean> {
        return authInterceptor.refresh();
      },
    },
  };
}


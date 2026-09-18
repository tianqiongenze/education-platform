import { ApiError } from './error';
import { createAuthInterceptor } from './auth';
import type { AuthInterceptor } from './auth';
import { createValidationInterceptor } from './validate';
import { retry } from '@ep/shared';
import type { ClientConfig, TokenResponse, RequestOptions } from './types';

/**
 * Build a RequestInit whose optional properties conform to the stricter
 * `exactOptionalPropertyTypes` setting. Under that flag, passing
 * `{ signal: undefined }` to `fetch` is rejected because `RequestInit.signal`
 * is typed `AbortSignal | null` (not `AbortSignal | undefined`). This helper
 * drops keys whose value is `undefined` so the call site stays ergonomic.
 */
function sanitizeInit(init: RequestInit): RequestInit {
  const result: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(init)) {
    if (value !== undefined) result[key] = value;
  }
  return result as RequestInit;
}

/** Convert an `AbortSignal | undefined` to `AbortSignal | null` for fetch. */
function signalOrNull(signal: AbortSignal | undefined): AbortSignal | null {
  return signal ?? null;
}

export interface ApiClient {
  get<T>(path: string, params?: Record<string, unknown>, options?: RequestOptions): Promise<T>;
  post<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T>;
  put<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T>;
  delete<T>(path: string, options?: RequestOptions): Promise<T>;
  auth: {
    login(username: string, password: string): Promise<TokenResponse>;
    wxLogin(code: string): Promise<TokenResponse>;
    refresh(): Promise<boolean>;
  };
}

interface RequestController {
  signal: AbortSignal | undefined;
  clear(): void;
}

/**
 * Combine an (optional) external AbortSignal and an (optional) timeout into a
 * single per-request controller. `clear()` detaches listeners and the timer so
 * the request never leaks handles.
 */
function createRequestController(
  options: RequestOptions | undefined,
  config: ClientConfig
): RequestController {
  const external = options?.signal;
  const timeoutMs = options?.timeoutMs ?? config.timeoutMs ?? 0;
  if (!external && !(timeoutMs > 0)) {
    return { signal: undefined, clear: () => {} };
  }
  const controller = new AbortController();
  const onExternalAbort = () => controller.abort();
  external?.addEventListener('abort', onExternalAbort);
  const timer =
    timeoutMs > 0 ? setTimeout(() => controller.abort(), timeoutMs) : undefined;
  return {
    signal: controller.signal,
    clear() {
      if (timer !== undefined) clearTimeout(timer);
      external?.removeEventListener('abort', onExternalAbort);
    },
  };
}

function isAborted(signal: AbortSignal | undefined): boolean {
  return signal?.aborted === true;
}

/**
 * Whether a failed GET is worth an automatic retry: only network-level
 * failures (the fetcher rejected without an HTTP status). HTTP error statuses
 * are server answers and are surfaced immediately; aborted requests are never
 * retried.
 */
function isRetryableGetError(err: unknown, signal: AbortSignal | undefined): boolean {
  if (isAborted(signal)) return false;
  if (err instanceof ApiError) return false;
  if (err instanceof Error && (err.name === 'AbortError' || err.name === 'TimeoutError')) {
    return false;
  }
  return true;
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

  async function request<T>(
    path: string,
    init: RequestInit,
    options?: RequestOptions
  ): Promise<T> {
    const url = path.startsWith('http') ? path : `${config.apiBaseUrl}${path}`;
    const method = (init.method ?? 'GET').toUpperCase();
    const controller = createRequestController(options, config);
    const autoRetry =
      method === 'GET' && (options?.retry ?? config.getAutoRetry ?? true);

    const attempt = async (): Promise<unknown> => {
      const authedInit = await authInterceptor(url, {
        ...init,
        signal: signalOrNull(controller.signal),
      });
      try {
        return await config.fetcher(url, sanitizeInit(authedInit));
      } catch (err) {
        if (err instanceof ApiError && err.isUnauthorized()) {
          const refreshed = await authInterceptor.refresh();
          if (refreshed) {
            const reAuthedInit = await authInterceptor(url, {
              ...init,
              signal: signalOrNull(controller.signal),
            });
            return config.fetcher(url, sanitizeInit(reAuthedInit));
          }
        }
        throw err;
      }
    };

    let result: unknown;
    try {
      if (autoRetry) {
        try {
          result = await attempt();
        } catch (err) {
          if (!isRetryableGetError(err, controller.signal)) throw err;
          // Reuse the shared exponential-backoff helper for the single retry
          // (maxRetries: 0 => exactly one more attempt, small jittered delay).
          result = await retry(attempt, { maxRetries: 0, baseDelay: 100, maxDelay: 150 });
        }
      } else {
        result = await attempt();
      }
    } finally {
      controller.clear();
    }

    return validationInterceptor(url, result) as T;
  }

  return {
    get<T>(path: string, params?: Record<string, unknown>, options?: RequestOptions): Promise<T> {
      const url = buildUrl(path, params);
      return request<T>(url, { method: 'GET' }, options);
    },
    post<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T> {
      const init: RequestInit = { method: 'POST' };
      if (body !== undefined) {
        init.body = JSON.stringify(body);
        init.headers = { 'Content-Type': 'application/json' };
      }
      return request<T>(path, init, options);
    },
    put<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T> {
      const init: RequestInit = { method: 'PUT' };
      if (body !== undefined) {
        init.body = JSON.stringify(body);
        init.headers = { 'Content-Type': 'application/json' };
      }
      return request<T>(path, init, options);
    },
    delete<T>(path: string, options?: RequestOptions): Promise<T> {
      return request<T>(path, { method: 'DELETE' }, options);
    },
    auth: {
      async login(username: string, password: string): Promise<TokenResponse> {
        return request<TokenResponse>(
          '/api/auth/login',
          {
            method: 'POST',
            body: JSON.stringify({ username, password }),
            headers: { 'Content-Type': 'application/json' },
          },
          { retry: false }
        );
      },
      async wxLogin(code: string): Promise<TokenResponse> {
        return request<TokenResponse>(
          '/api/auth/wx-login',
          {
            method: 'POST',
            body: JSON.stringify({ code }),
            headers: { 'Content-Type': 'application/json' },
          },
          { retry: false }
        );
      },
      refresh(): Promise<boolean> {
        return authInterceptor.refresh();
      },
    },
  };
}

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createClient } from '../client';
import { ApiError } from '../error';
import type { ClientConfig, Fetcher, TokenStorage } from '../types';

function makeStorage(): TokenStorage {
  const store: { access?: string; refresh?: string } = {};
  return {
    getAccessToken: () => store.access ?? null,
    setAccessToken: (t) => { store.access = t; },
    getRefreshToken: () => store.refresh ?? null,
    setRefreshToken: (t) => { store.refresh = t; },
    clear: () => { delete store.access; delete store.refresh; },
  };
}

function makeConfig(overrides: Partial<ClientConfig> = {}): ClientConfig {
  return {
    apiBaseUrl: 'http://localhost:8080',
    fetcher: vi.fn() as unknown as Fetcher,
    tokenStorage: makeStorage(),
    zodValidate: false,
    ...overrides,
  };
}

describe('createClient', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  describe('get', () => {
    it('sends GET with query params', async () => {
      const fetcher = vi.fn().mockResolvedValue({ items: [] });
      const config = makeConfig({ fetcher });
      const client = createClient(config);

      await client.get('/api/course/list', { page: 2, pageSize: 10, keyword: 'math' });

      expect(fetcher).toHaveBeenCalledTimes(1);
      const [url, init] = fetcher.mock.calls[0] as [string, RequestInit];
      expect(url).toBe('http://localhost:8080/api/course/list?page=2&pageSize=10&keyword=math');
      expect(init.method).toBe('GET');
      // Query params are baked into the URL.
      expect(url).toContain('page=2');
      expect(url).toContain('pageSize=10');
      expect(url).toContain('keyword=math');
    });

    it('omits undefined/null params', async () => {
      const fetcher = vi.fn().mockResolvedValue({ items: [] });
      const config = makeConfig({ fetcher });
      const client = createClient(config);

      await client.get('/api/course/list', { page: 1, keyword: undefined, tag: null });

      const [url] = fetcher.mock.calls[0] as [string, RequestInit];
      expect(url).not.toContain('keyword');
      expect(url).not.toContain('tag');
      expect(url).toContain('page=1');
    });

    it('returns parsed response', async () => {
      const fetcher = vi.fn().mockResolvedValue({ id: 'c1', title: 'Course 1' });
      const config = makeConfig({ fetcher });
      const client = createClient(config);

      const result = await client.get('/api/course/c1');
      expect(result).toEqual({ id: 'c1', title: 'Course 1' });
    });
  });

  describe('post', () => {
    it('sends POST with JSON body and Content-Type', async () => {
      const fetcher = vi.fn().mockResolvedValue({ ok: true });
      const config = makeConfig({ fetcher });
      const client = createClient(config);

      await client.post('/api/course/c1/enroll', { userId: 'u1' });

      const [url, init] = fetcher.mock.calls[0] as [string, RequestInit];
      expect(url).toBe('http://localhost:8080/api/course/c1/enroll');
      expect(init.method).toBe('POST');
      expect(init.body).toBe(JSON.stringify({ userId: 'u1' }));
      const headers = new Headers(init.headers);
      expect(headers.get('Content-Type')).toBe('application/json');
    });

    it('sends POST without body when body is undefined', async () => {
      const fetcher = vi.fn().mockResolvedValue({ ok: true });
      const config = makeConfig({ fetcher });
      const client = createClient(config);

      await client.post('/api/course/c1/enroll');

      const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
      expect(init.body).toBeUndefined();
    });
  });

  describe('put / delete', () => {
    it('sends PUT with body', async () => {
      const fetcher = vi.fn().mockResolvedValue({ ok: true });
      const config = makeConfig({ fetcher });
      const client = createClient(config);

      await client.put('/api/user/profile', { nickname: 'new' });

      const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
      expect(init.method).toBe('PUT');
      expect(init.body).toBe(JSON.stringify({ nickname: 'new' }));
    });

    it('sends DELETE', async () => {
      const fetcher = vi.fn().mockResolvedValue({ ok: true });
      const config = makeConfig({ fetcher });
      const client = createClient(config);

      await client.delete('/api/course/c1');

      const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
      expect(init.method).toBe('DELETE');
    });
  });

  describe('401 refresh + retry', () => {
    it('on 401, refreshes token and retries the request once', async () => {
      const fetcher = vi.fn();
      // call 1: reject 401 (original request)
      // call 2: resolve tokens (refresh)
      // call 3: resolve data (retry with new token)
      fetcher
        .mockRejectedValueOnce(new ApiError(401, 'expired'))
        .mockResolvedValueOnce({ accessToken: 'new-access', refreshToken: 'new-refresh', expiresIn: 3600 })
        .mockResolvedValueOnce({ data: 'retried' });

      const config = makeConfig({ fetcher });
      config.tokenStorage.setRefreshToken('old-refresh');
      const client = createClient(config);

      const result = await client.get('/api/user/profile');

      expect(result).toEqual({ data: 'retried' });
      expect(fetcher).toHaveBeenCalledTimes(3);
      expect(config.tokenStorage.getAccessToken()).toBe('new-access');
      // The retry must carry the new token.
      const retryInit = fetcher.mock.calls[2]?.[1] as RequestInit;
      const retryHeaders = new Headers(retryInit.headers);
      expect(retryHeaders.get('Authorization')).toBe('Bearer new-access');
    });

    it('on 401 when refresh fails, propagates the 401 error', async () => {
      const fetcher = vi.fn()
        .mockRejectedValueOnce(new ApiError(401, 'expired'))
        .mockRejectedValueOnce(new ApiError(401, 'refresh failed'));

      const config = makeConfig({ fetcher });
      config.tokenStorage.setRefreshToken('old-refresh');
      const client = createClient(config);

      await expect(client.get('/api/user/profile')).rejects.toMatchObject({
        name: 'ApiError',
        status: 401,
      });
      // Original call + refresh attempt.
      expect(fetcher).toHaveBeenCalledTimes(2);
      // Storage cleared on refresh failure.
      expect(config.tokenStorage.getRefreshToken()).toBeNull();
    });

    it('on 401 with no refresh token, does not retry and propagates error', async () => {
      const fetcher = vi.fn().mockRejectedValueOnce(new ApiError(401, 'expired'));
      const config = makeConfig({ fetcher });
      const client = createClient(config);

      await expect(client.get('/api/user/profile')).rejects.toMatchObject({ status: 401 });
      expect(fetcher).toHaveBeenCalledTimes(1);
    });
  });

  describe('error propagation', () => {
    it('propagates non-401 ApiError without retry', async () => {
      const fetcher = vi.fn().mockRejectedValueOnce(new ApiError(403, 'forbidden', 't1'));
      const config = makeConfig({ fetcher });
      const client = createClient(config);

      await expect(client.get('/api/user/profile')).rejects.toMatchObject({
        status: 403,
        message: 'forbidden',
        traceId: 't1',
      });
      expect(fetcher).toHaveBeenCalledTimes(1);
    });

    it('retries idempotent GET once on network errors, then propagates', async () => {
      const fetchMock = vi
        .fn<Fetcher>()
        .mockRejectedValueOnce(new Error('network down'))
        .mockRejectedValueOnce(new Error('network down'));
      const config = makeConfig({ fetcher: fetchMock as unknown as Fetcher });
      const client = createClient(config);

      await expect(client.get('/api/user/profile')).rejects.toThrow('network down');
      // Initial attempt + exactly one automatic retry (GET is idempotent).
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('network-error retry can be disabled per request', async () => {
      const fetchMock = vi.fn<Fetcher>().mockRejectedValue(new Error('network down'));
      const config = makeConfig({ fetcher: fetchMock as unknown as Fetcher });
      const client = createClient(config);

      await expect(
        client.get('/api/user/profile', undefined, { retry: false })
      ).rejects.toThrow('network down');
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it('does not retry non-GET requests on network errors', async () => {
      const fetchMock = vi.fn<Fetcher>().mockRejectedValue(new Error('network down'));
      const config = makeConfig({ fetcher: fetchMock as unknown as Fetcher });
      const client = createClient(config);

      await expect(
        client.post('/api/course/c1/enroll', { userId: 'u1' })
      ).rejects.toThrow('network down');
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it('propagates 500 errors without retry', async () => {
      const fetcher = vi.fn().mockRejectedValueOnce(new ApiError(500, 'oops'));
      const config = makeConfig({ fetcher });
      const client = createClient(config);

      await expect(client.get('/api/user/profile')).rejects.toMatchObject({ status: 500 });
      expect(fetcher).toHaveBeenCalledTimes(1);
    });
  });

  describe('auth.login', () => {
    it('posts credentials to /api/auth/login and returns TokenResponse', async () => {
      const fetcher = vi.fn().mockResolvedValue({
        accessToken: 'a',
        refreshToken: 'r',
        expiresIn: 3600,
      });
      const config = makeConfig({ fetcher });
      const client = createClient(config);

      const result = await client.auth.login('user', 'pass');

      expect(result).toEqual({ accessToken: 'a', refreshToken: 'r', expiresIn: 3600 });
      const [url, init] = fetcher.mock.calls[0] as [string, RequestInit];
      expect(url).toBe('http://localhost:8080/api/auth/login');
      expect(init.method).toBe('POST');
      expect(init.body).toBe(JSON.stringify({ username: 'user', password: 'pass' }));
    });
  });

  describe('auth.refresh', () => {
    it('delegates to the auth interceptor refresh', async () => {
      const fetcher = vi.fn().mockResolvedValue({
        accessToken: 'a',
        refreshToken: 'r',
        expiresIn: 3600,
      });
      const config = makeConfig({ fetcher });
      config.tokenStorage.setRefreshToken('old');
      const client = createClient(config);

      const ok = await client.auth.refresh();
      expect(ok).toBe(true);
      expect(config.tokenStorage.getAccessToken()).toBe('a');
    });
  });

  describe('header injection', () => {
    it('injects Authorization, X-Trace-Id, X-Client-Version on every request', async () => {
      const fetcher = vi.fn().mockResolvedValue({ ok: true });
      const config = makeConfig({ fetcher });
      config.tokenStorage.setAccessToken('tok');
      const client = createClient(config);

      await client.get('/api/user/profile');

      const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
      const headers = new Headers(init.headers);
      expect(headers.get('Authorization')).toBe('Bearer tok');
      expect(headers.get('X-Trace-Id')).toBeTruthy();
      expect(headers.get('X-Client-Version')).toBe('1.0.0');
    });

    it('injects X-Cell-Routing when getCellId returns a value', async () => {
      const fetcher = vi.fn().mockResolvedValue({ ok: true });
      const config = makeConfig({ fetcher, getCellId: () => 'cell-1' });
      const client = createClient(config);

      await client.get('/api/user/profile');

      const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
      const headers = new Headers(init.headers);
      expect(headers.get('X-Cell-Routing')).toBe('cell-1');
    });
  });
});

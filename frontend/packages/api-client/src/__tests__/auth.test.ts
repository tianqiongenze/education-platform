import { describe, it, expect, vi } from 'vitest';
import { createAuthInterceptor } from '../auth';
import type { ClientConfig, TokenStorage } from '../types';

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
    fetcher: vi.fn(),
    tokenStorage: makeStorage(),
    ...overrides,
  };
}

describe('createAuthInterceptor', () => {
  describe('inject', () => {
    it('adds Authorization Bearer header when token present', async () => {
      const config = makeConfig();
      config.tokenStorage.setAccessToken('my-token');
      const interceptor = createAuthInterceptor(config);

      const result = await interceptor('http://localhost:8080/api/x', { method: 'GET' });
      const headers = new Headers(result.headers);
      expect(headers.get('Authorization')).toBe('Bearer my-token');
    });

    it('does not add Authorization header when no token', async () => {
      const config = makeConfig();
      const interceptor = createAuthInterceptor(config);

      const result = await interceptor('http://localhost:8080/api/x', { method: 'GET' });
      const headers = new Headers(result.headers);
      expect(headers.get('Authorization')).toBeNull();
    });

    it('adds X-Cell-Routing header when getCellId returns a value', async () => {
      const config = makeConfig({ getCellId: () => 'cell-123' });
      const interceptor = createAuthInterceptor(config);

      const result = await interceptor('http://localhost:8080/api/x', { method: 'GET' });
      const headers = new Headers(result.headers);
      expect(headers.get('X-Cell-Routing')).toBe('cell-123');
    });

    it('skips X-Cell-Routing header when getCellId returns undefined', async () => {
      const config = makeConfig({ getCellId: () => undefined });
      const interceptor = createAuthInterceptor(config);

      const result = await interceptor('http://localhost:8080/api/x', { method: 'GET' });
      const headers = new Headers(result.headers);
      expect(headers.get('X-Cell-Routing')).toBeNull();
    });

    it('skips X-Cell-Routing header when getCellId not provided', async () => {
      const config = makeConfig();
      const interceptor = createAuthInterceptor(config);

      const result = await interceptor('http://localhost:8080/api/x', { method: 'GET' });
      const headers = new Headers(result.headers);
      expect(headers.get('X-Cell-Routing')).toBeNull();
    });

    it('adds X-Trace-Id header (uuid-like)', async () => {
      const config = makeConfig();
      const interceptor = createAuthInterceptor(config);

      const result = await interceptor('http://localhost:8080/api/x', { method: 'GET' });
      const headers = new Headers(result.headers);
      const traceId = headers.get('X-Trace-Id');
      expect(traceId).toBeTruthy();
      expect(typeof traceId).toBe('string');
      expect(traceId!.length).toBeGreaterThan(8);
    });

    it('adds X-Client-Version header', async () => {
      const config = makeConfig();
      const interceptor = createAuthInterceptor(config);

      const result = await interceptor('http://localhost:8080/api/x', { method: 'GET' });
      const headers = new Headers(result.headers);
      expect(headers.get('X-Client-Version')).toBe('1.0.0');
    });

    it('preserves existing init properties', async () => {
      const config = makeConfig();
      const interceptor = createAuthInterceptor(config);

      const result = await interceptor('http://localhost:8080/api/x', { method: 'POST', body: 'hello' });
      expect(result.method).toBe('POST');
      expect(result.body).toBe('hello');
    });
  });

  describe('refresh', () => {
    it('refreshes token via refreshUrl and stores new tokens', async () => {
      const fetcher = vi.fn().mockResolvedValue({
        accessToken: 'new-access',
        refreshToken: 'new-refresh',
        expiresIn: 3600,
      });
      const config = makeConfig({ fetcher, refreshUrl: '/api/auth/refresh' });
      config.tokenStorage.setRefreshToken('old-refresh');
      const interceptor = createAuthInterceptor(config);

      const ok = await interceptor.refresh();
      expect(ok).toBe(true);
      expect(fetcher).toHaveBeenCalledTimes(1);
      expect(fetcher).toHaveBeenCalledWith(
        'http://localhost:8080/api/auth/refresh',
        expect.objectContaining({ method: 'POST' })
      );
      expect(config.tokenStorage.getAccessToken()).toBe('new-access');
      expect(config.tokenStorage.getRefreshToken()).toBe('new-refresh');
    });

    it('uses default refreshUrl when not provided', async () => {
      const fetcher = vi.fn().mockResolvedValue({
        accessToken: 'a',
        refreshToken: 'r',
        expiresIn: 3600,
      });
      const config = makeConfig({ fetcher });
      config.tokenStorage.setRefreshToken('old');
      const interceptor = createAuthInterceptor(config);

      await interceptor.refresh();
      expect(fetcher).toHaveBeenCalledWith(
        'http://localhost:8080/api/auth/refresh',
        expect.any(Object)
      );
    });

    it('returns false when no refresh token stored', async () => {
      const fetcher = vi.fn();
      const config = makeConfig({ fetcher });
      const interceptor = createAuthInterceptor(config);

      const ok = await interceptor.refresh();
      expect(ok).toBe(false);
      expect(fetcher).not.toHaveBeenCalled();
    });

    it('deduplicates concurrent refresh calls (single promise shared)', async () => {
      let resolveRefresh: (value: unknown) => void = () => {};
      const fetcher = vi.fn().mockReturnValue(new Promise((resolve) => {
        resolveRefresh = resolve;
      }));
      const config = makeConfig({ fetcher });
      config.tokenStorage.setRefreshToken('old');
      const interceptor = createAuthInterceptor(config);

      const p1 = interceptor.refresh();
      const p2 = interceptor.refresh();
      const p3 = interceptor.refresh();

      expect(fetcher).toHaveBeenCalledTimes(1);

      resolveRefresh({ accessToken: 'a', refreshToken: 'r', expiresIn: 3600 });

      const [r1, r2, r3] = await Promise.all([p1, p2, p3]);
      expect(r1).toBe(true);
      expect(r2).toBe(true);
      expect(r3).toBe(true);
      expect(fetcher).toHaveBeenCalledTimes(1);
    });

    it('clears storage on refresh failure (fetcher throws)', async () => {
      const fetcher = vi.fn().mockRejectedValue(new Error('network'));
      const config = makeConfig({ fetcher });
      config.tokenStorage.setRefreshToken('old');
      config.tokenStorage.setAccessToken('old-access');
      const interceptor = createAuthInterceptor(config);

      const ok = await interceptor.refresh();
      expect(ok).toBe(false);
      expect(config.tokenStorage.getAccessToken()).toBeNull();
      expect(config.tokenStorage.getRefreshToken()).toBeNull();
    });

    it('clears storage when response is malformed', async () => {
      const fetcher = vi.fn().mockResolvedValue({ unexpected: true });
      const config = makeConfig({ fetcher });
      config.tokenStorage.setRefreshToken('old');
      const interceptor = createAuthInterceptor(config);

      const ok = await interceptor.refresh();
      expect(ok).toBe(false);
      expect(config.tokenStorage.getRefreshToken()).toBeNull();
    });

    it('allows a new refresh after a completed refresh', async () => {
      const fetcher = vi.fn()
        .mockResolvedValueOnce({ accessToken: 'a1', refreshToken: 'r1', expiresIn: 3600 })
        .mockResolvedValueOnce({ accessToken: 'a2', refreshToken: 'r2', expiresIn: 3600 });
      const config = makeConfig({ fetcher });
      config.tokenStorage.setRefreshToken('old');
      const interceptor = createAuthInterceptor(config);

      await interceptor.refresh();
      await interceptor.refresh();
      expect(fetcher).toHaveBeenCalledTimes(2);
    });
  });
});

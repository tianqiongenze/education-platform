import { describe, it, expect } from 'vitest';
import { createQueryClient } from '../client';

class FakeApiError extends Error {
  status: number;
  constructor(status: number) {
    super(`ApiError ${status}`);
    this.name = 'ApiError';
    this.status = status;
  }
}

describe('createQueryClient', () => {
  it('returns a QueryClient instance', () => {
    const client = createQueryClient();
    expect(client).toBeDefined();
    expect(typeof client.getQueryDefaults).toBe('function');
  });

  it('sets default staleTime to 5 minutes', () => {
    const client = createQueryClient();
    const defaults = client.getDefaultOptions().queries!;
    expect(defaults.staleTime).toBe(5 * 60 * 1000);
  });

  it('sets default gcTime to 30 minutes', () => {
    const client = createQueryClient();
    const defaults = client.getDefaultOptions().queries!;
    expect(defaults.gcTime).toBe(30 * 60 * 1000);
  });

  it('refetchOnWindowFocus enabled', () => {
    const client = createQueryClient();
    const defaults = client.getDefaultOptions().queries!;
    expect(defaults.refetchOnWindowFocus).toBe(true);
  });

  describe('retry logic', () => {
    it('retries up to 3 times for non-ApiError', () => {
      const client = createQueryClient();
      const retry = client.getDefaultOptions().queries!.retry;
      expect(typeof retry).toBe('function');
      const retryFn = retry as (failureCount: number, error: unknown) => boolean;
      expect(retryFn(0, new Error('boom'))).toBe(true);
      expect(retryFn(2, new Error('boom'))).toBe(true);
      expect(retryFn(3, new Error('boom'))).toBe(false);
    });

    it('does not retry 4xx (except 401/429)', () => {
      const client = createQueryClient();
      const retryFn = client.getDefaultOptions().queries!.retry as (
        failureCount: number,
        error: unknown,
      ) => boolean;
      expect(retryFn(0, new FakeApiError(400))).toBe(false);
      expect(retryFn(0, new FakeApiError(403))).toBe(false);
      expect(retryFn(0, new FakeApiError(404))).toBe(false);
    });

    it('retries 401 and 429', () => {
      const client = createQueryClient();
      const retryFn = client.getDefaultOptions().queries!.retry as (
        failureCount: number,
        error: unknown,
      ) => boolean;
      expect(retryFn(0, new FakeApiError(401))).toBe(true);
      expect(retryFn(0, new FakeApiError(429))).toBe(true);
    });

    it('retries 5xx errors', () => {
      const client = createQueryClient();
      const retryFn = client.getDefaultOptions().queries!.retry as (
        failureCount: number,
        error: unknown,
      ) => boolean;
      expect(retryFn(0, new FakeApiError(500))).toBe(true);
      expect(retryFn(2, new FakeApiError(503))).toBe(true);
      expect(retryFn(3, new FakeApiError(503))).toBe(false);
    });
  });

  describe('retryDelay (exponential)', () => {
    it('computes exponential backoff capped at 30s', () => {
      const client = createQueryClient();
      const retryDelay = client.getDefaultOptions().queries!.retryDelay as (
        attempt: number,
      ) => number;
      expect(retryDelay(0)).toBe(1000);
      expect(retryDelay(1)).toBe(2000);
      expect(retryDelay(2)).toBe(4000);
      expect(retryDelay(5)).toBe(32000); // capped
    });
  });

  describe('mutation defaults', () => {
    it('retries mutations once', () => {
      const client = createQueryClient();
      const defaults = client.getDefaultOptions().mutations!;
      expect(defaults.retry).toBe(1);
      expect(defaults.retryDelay).toBe(1000);
    });
  });
});

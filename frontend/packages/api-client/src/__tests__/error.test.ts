import { describe, it, expect } from 'vitest';
import { ApiError } from '../error';

describe('ApiError', () => {
  it('sets name and message', () => {
    const err = new ApiError(500, 'boom');
    expect(err.name).toBe('ApiError');
    expect(err.message).toBe('boom');
    expect(err.status).toBe(500);
  });

  it('preserves traceId and data', () => {
    const err = new ApiError(400, 'bad', 'trace-1', { foo: 'bar' });
    expect(err.traceId).toBe('trace-1');
    expect(err.data).toEqual({ foo: 'bar' });
  });

  it('isUnauthorized returns true only for 401', () => {
    expect(new ApiError(401, 'x').isUnauthorized()).toBe(true);
    expect(new ApiError(403, 'x').isUnauthorized()).toBe(false);
  });

  it('isForbidden returns true only for 403', () => {
    expect(new ApiError(403, 'x').isForbidden()).toBe(true);
    expect(new ApiError(401, 'x').isForbidden()).toBe(false);
  });

  it('isNotFound returns true only for 404', () => {
    expect(new ApiError(404, 'x').isNotFound()).toBe(true);
    expect(new ApiError(400, 'x').isNotFound()).toBe(false);
  });

  it('isRateLimited returns true only for 429', () => {
    expect(new ApiError(429, 'x').isRateLimited()).toBe(true);
    expect(new ApiError(429, 'x').isServerError()).toBe(false);
  });

  it('isServerError returns true for 500+ status codes', () => {
    expect(new ApiError(500, 'x').isServerError()).toBe(true);
    expect(new ApiError(503, 'x').isServerError()).toBe(true);
    expect(new ApiError(499, 'x').isServerError()).toBe(false);
  });

  it('is an instance of Error', () => {
    const err = new ApiError(500, 'x');
    expect(err).toBeInstanceOf(Error);
  });
});

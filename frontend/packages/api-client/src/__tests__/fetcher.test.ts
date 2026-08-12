import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { createBrowserFetcher, createTaroFetcher } from '../fetcher';


describe('createBrowserFetcher', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  function mockFetch(response: { ok: boolean; status: number; statusText?: string; headers?: Record<string, string>; json?: () => Promise<unknown>; text?: () => Promise<string> }): ReturnType<typeof vi.fn> {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: response.ok,
      status: response.status,
      statusText: response.statusText ?? '',
      headers: new Headers(response.headers ?? { 'content-type': 'application/json' }),
      json: response.json ?? (async () => ({})),
      text: response.text ?? (async () => JSON.stringify(await (response.json ?? (async () => ({})))())),
    });
    globalThis.fetch = fetchMock as unknown as typeof fetch;
    return fetchMock;
  }

  it('returns parsed JSON on success', async () => {
    const fetchMock = mockFetch({
      ok: true,
      status: 200,
      json: async () => ({ hello: 'world' }),
    });

    const fetcher = createBrowserFetcher({ apiBaseUrl: 'http://localhost:8080' });
    const result = await fetcher('/api/test', { method: 'GET' });

    expect(result).toEqual({ hello: 'world' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [calledUrl] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(calledUrl).toBe('http://localhost:8080/api/test');
  });

  it('serializes object body and sets Content-Type', async () => {
    const fetchMock = mockFetch({
      ok: true,
      status: 200,
      json: async () => ({ ok: true }),
    });

    const fetcher = createBrowserFetcher();
    await fetcher('http://localhost:8080/api/test', {
      method: 'POST',
      body: { a: 1 } as unknown as string,
    });

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.body).toBe(JSON.stringify({ a: 1 }));
    const headers = new Headers(init.headers);
    expect(headers.get('Content-Type')).toBe('application/json');
  });

  it('does not override an existing Content-Type', async () => {
    const fetchMock = mockFetch({
      ok: true,
      status: 200,
      json: async () => ({ ok: true }),
    });

    const fetcher = createBrowserFetcher();
    await fetcher('http://localhost:8080/api/test', {
      method: 'POST',
      body: { a: 1 } as unknown as string,
      headers: { 'Content-Type': 'text/plain' },
    });

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const headers = new Headers(init.headers);
    expect(headers.get('Content-Type')).toBe('text/plain');
  });

  it('throws ApiError on non-2xx with parsed message and traceId', async () => {
    mockFetch({
      ok: false,
      status: 401,
      statusText: 'Unauthorized',
      headers: { 'content-type': 'application/json', 'x-trace-id': 'trace-xyz' },
      json: async () => ({ message: 'token expired', data: null }),
    });

    const fetcher = createBrowserFetcher();
    await expect(fetcher('http://localhost:8080/api/test', { method: 'GET' })).rejects.toMatchObject({
      name: 'ApiError',
      status: 401,
      message: 'token expired',
      traceId: 'trace-xyz',
    });
  });

  it('throws ApiError with statusText when body is empty', async () => {
    mockFetch({
      ok: false,
      status: 500,
      statusText: 'Internal Server Error',
      headers: { 'content-type': 'application/json' },
      json: async () => { throw new Error('no body'); },
      text: async () => '',
    });

    const fetcher = createBrowserFetcher();
    await expect(fetcher('http://localhost:8080/api/test', { method: 'GET' })).rejects.toMatchObject({
      status: 500,
      message: 'Internal Server Error',
    });
  });

  it('returns text when content-type is not JSON', async () => {
    mockFetch({
      ok: true,
      status: 200,
      headers: { 'content-type': 'text/plain' },
      json: async () => { throw new Error('not json'); },
      text: async () => 'plain text',
    });

    const fetcher = createBrowserFetcher();
    const result = await fetcher('http://localhost:8080/api/test', { method: 'GET' });
    expect(result).toBe('plain text');
  });
});

describe('createTaroFetcher', () => {
  it('adapts Taro.request success response', async () => {
    const taroRequest = vi.fn().mockResolvedValue({
      statusCode: 200,
      data: { hello: 'taro' },
      header: { 'x-trace-id': 'taro-trace' },
    });

    const fetcher = createTaroFetcher(taroRequest);
    const result = await fetcher('http://localhost:8080/api/test', { method: 'GET' });

    expect(result).toEqual({ hello: 'taro' });
    expect(taroRequest).toHaveBeenCalledWith(expect.objectContaining({
      url: 'http://localhost:8080/api/test',
      method: 'GET',
    }));
  });

  it('serializes object body and sets Content-Type', async () => {
    const taroRequest = vi.fn().mockResolvedValue({
      statusCode: 200,
      data: {},
    });

    const fetcher = createTaroFetcher(taroRequest);
    await fetcher('http://localhost:8080/api/test', {
      method: 'POST',
      body: { a: 1 } as unknown as string,
    });

    const call = taroRequest.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(call.data).toBe(JSON.stringify({ a: 1 }));
    const header = call.header as Record<string, string>;
    expect(header['Content-Type']).toBe('application/json');
  });

  it('throws ApiError on non-2xx with message and traceId', async () => {
    const taroRequest = vi.fn().mockResolvedValue({
      statusCode: 403,
      data: { message: 'forbidden', data: null },
      header: { 'x-trace-id': 'taro-403' },
    });

    const fetcher = createTaroFetcher(taroRequest);
    await expect(fetcher('http://localhost:8080/api/test', { method: 'GET' })).rejects.toMatchObject({
      name: 'ApiError',
      status: 403,
      message: 'forbidden',
      traceId: 'taro-403',
    });
  });

  it('throws ApiError with statusCode-based message when data is a string', async () => {
    const taroRequest = vi.fn().mockResolvedValue({
      statusCode: 500,
      data: 'something broke',
    });

    const fetcher = createTaroFetcher(taroRequest);
    await expect(fetcher('http://localhost:8080/api/test', { method: 'GET' })).rejects.toMatchObject({
      status: 500,
      message: 'something broke',
    });
  });

  it('defaults method to GET', async () => {
    const taroRequest = vi.fn().mockResolvedValue({
      statusCode: 200,
      data: {},
    });

    const fetcher = createTaroFetcher(taroRequest);
    await fetcher('http://localhost:8080/api/test', {});

    const call = taroRequest.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(call.method).toBe('GET');
  });
});

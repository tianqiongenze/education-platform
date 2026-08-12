import { ApiError } from './error';
import type { Fetcher } from './types';

interface FetcherOptions {
  apiBaseUrl?: string;
}

function buildUrl(baseUrl: string, url: string, params?: Record<string, unknown>): string {
  const fullUrl = url.startsWith('http') ? url : `${baseUrl}${url}`;
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

function extractTraceId(headers: Headers | Record<string, string> | undefined): string | undefined {
  if (!headers) return undefined;
  if (headers instanceof Headers) {
    return headers.get('X-Trace-Id') ?? headers.get('x-trace-id') ?? undefined;
  }
  const lower: Record<string, string> = {};
  for (const [k, v] of Object.entries(headers)) {
    lower[k.toLowerCase()] = v;
  }
  return lower['x-trace-id'];
}

async function parseErrorBody(res: Response): Promise<{ message: string; data?: unknown }> {
  try {
    const text = await res.text();
    if (!text) return { message: res.statusText };
    const parsed = JSON.parse(text) as { message?: string; data?: unknown };
    return { message: parsed.message ?? res.statusText, data: parsed.data };
  } catch {
    return { message: res.statusText };
  }
}

export function createBrowserFetcher(options: FetcherOptions = {}): Fetcher {
  const baseUrl = options.apiBaseUrl ?? '';
  return async (url: string, init: RequestInit): Promise<unknown> => {
    let finalUrl = url.startsWith('http') ? url : `${baseUrl}${url}`;
    const requestInit: RequestInit = { ...init };

    // Serialize JSON body if an object is passed.
    if (requestInit.body && typeof requestInit.body === 'object' && !(requestInit.body instanceof FormData) && !(requestInit.body instanceof Blob)) {
      requestInit.body = JSON.stringify(requestInit.body);
      const headers = new Headers(requestInit.headers ?? undefined);
      if (!headers.has('Content-Type')) {
        headers.set('Content-Type', 'application/json');
      }
      requestInit.headers = headers;
    }

    // Construct query params if provided via a custom "params" field on init.
    const params = (init as RequestInit & { params?: Record<string, unknown> }).params;
    if (params) {
      finalUrl = buildUrl('', finalUrl, params);
    }

    const res = await fetch(finalUrl, requestInit);
    if (!res.ok) {
      const { message, data } = await parseErrorBody(res);
      const traceId = extractTraceId(res.headers);
      throw new ApiError(res.status, message, traceId, data);
    }

    const contentType = res.headers.get('content-type') ?? '';
    if (contentType.includes('application/json')) {
      return res.json();
    }
    return res.text();
  };
}

interface TaroResponse<T = unknown> {
  statusCode: number;
  data: T;
  header?: Record<string, string>;
}

export function createTaroFetcher(taroRequest: (opts: Record<string, unknown>) => Promise<TaroResponse>): Fetcher {
  return async (url: string, init: RequestInit): Promise<unknown> => {
    const headers: Record<string, string> = {};
    const rawHeaders = init.headers;
    if (rawHeaders) {
      const h = new Headers(rawHeaders as HeadersInit);
      h.forEach((value, key) => {
        headers[key] = value;
      });
    }

    const params = (init as RequestInit & { params?: Record<string, unknown> }).params;
    let finalUrl = url;
    if (params) {
      finalUrl = buildUrl('', url, params);
    }

    const method = init.method ?? 'GET';

    let body: unknown = init.body;
    if (body && typeof body === 'object') {
      body = JSON.stringify(body);
      if (!headers['Content-Type'] && !headers['content-type']) {
        headers['Content-Type'] = 'application/json';
      }
    }

    const res = await taroRequest({
      url: finalUrl,
      method,
      data: body,
      header: headers,
    });

    if (res.statusCode < 200 || res.statusCode >= 300) {
      const errData = res.data as { message?: string; data?: unknown } | string | undefined;
      let message: string;
      let data: unknown;
      if (typeof errData === 'string') {
        message = errData || `HTTP ${res.statusCode}`;
        data = errData;
      } else if (errData && typeof errData === 'object') {
        message = errData.message ?? `HTTP ${res.statusCode}`;
        data = errData.data;
      } else {
        message = `HTTP ${res.statusCode}`;
        data = errData;
      }
      const traceId = extractTraceId(res.header);
      throw new ApiError(res.statusCode, message, traceId, data);
    }

    // Taro returns parsed JSON automatically when Content-Type is JSON.
    return res.data;
  };
}

/**
 * Common utilities and shared configuration for k6 load tests.
 *
 * All test scripts import from here so BASE_URLs, auth flow, helpers,
 * and reusable custom metrics stay in one place.
 *
 * Environment variables:
 *   K6_BASE_URL          - BFF base URL (default http://localhost:8080)
 *   K6_BUSINESS_URL      - Business service URL (default http://localhost:8083)
 *   K6_ITBACKEND_URL     - IT Backend URL (default http://localhost:8085)
 *   K6_AIGW_URL          - AI Gateway URL (default http://localhost:8086)
 *   K6_WS_URL             - WS Gateway URL (default ws://localhost:8081)
 *   K6_TEST_USER         - login username (default loadtest@education.com)
 *   K6_TEST_PASSWORD     - login password (default LoadTest#2026)
 */

import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// ---------------------------------------------------------------------------
// Base URLs (configurable via env)
// ---------------------------------------------------------------------------
export const BASE_URL = __ENV.K6_BASE_URL || 'http://localhost:8080';
export const BUSINESS_URL = __ENV.K6_BUSINESS_URL || 'http://localhost:8083';
export const ITBACKEND_URL = __ENV.K6_ITBACKEND_URL || 'http://localhost:8085';
export const AIGW_URL = __ENV.K6_AIGW_URL || 'http://localhost:8086';
export const WS_URL = __ENV.K6_WS_URL || 'ws://localhost:8081';

export const TEST_USER = __ENV.K6_TEST_USER || 'loadtest@education.com';
export const TEST_PASSWORD = __ENV.K6_TEST_PASSWORD || 'LoadTest#2026';

// ---------------------------------------------------------------------------
// Reusable custom metrics
// ---------------------------------------------------------------------------
export const metrics = {
  orders_per_sec: new Rate('orders_per_sec'),
  idempotency_dedup_rate: new Rate('idempotency_dedup_rate'),
  homework_submitted_per_sec: new Rate('homework_submitted_per_sec'),
  grading_pipeline_latency: new Trend('grading_pipeline_latency', true),
  spike_qps: new Rate('spike_qps'),
  orders_created_total: new Counter('orders_created_total'),
  ws_connect_failures: new Counter('ws_connect_failures'),
  ws_msg_per_sec: new Rate('ws_msg_per_sec'),
  login_latency: new Trend('login_latency', true),
  request_latency: new Trend('request_latency', true),
  error_rate: new Rate('error_rate'),
};

// ---------------------------------------------------------------------------
// Shared default headers
// ---------------------------------------------------------------------------
export const commonHeaders = {
  'Content-Type': 'application/json',
  'Accept': 'application/json',
  'X-Client': 'k6-load-test',
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Sleep a random amount of time between min and max seconds (think time).
 */
export function thinkTime(min = 1, max = 3) {
  const t = Math.random() * (max - min) + min;
  sleep(t);
  return t;
}

/**
 * Generate a random idempotency key (UUID v4-ish).
 */
export function generateIdempotencyKey() {
  return 'xxxxxxxxxxxx4xxxyxxxxxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

/**
 * Generate a random user id in a realistic range.
 */
export function randomUserId(max = 1000000) {
  return Math.floor(Math.random() * max) + 1;
}

/**
 * Generate a random course id.
 */
export function randomCourseId(max = 10000) {
  return Math.floor(Math.random() * max) + 1;
}

/**
 * Login with the test user and return the JWT token.
 * Caches per-VU token in __ENV-free global to avoid re-login every iteration.
 */
let __cachedToken = null;

export function login() {
  if (__cachedToken) {
    return __cachedToken;
  }
  const url = `${BASE_URL}/api/v1/auth/login`;
  const payload = JSON.stringify({
    username: TEST_USER,
    password: TEST_PASSWORD,
  });
  const start = Date.now();
  const res = http.post(url, payload, { headers: commonHeaders });
  metrics.login_latency.add(Date.now() - start);

  const ok = res.status === 200;
  metrics.error_rate.add(!ok);

  if (!ok) {
    return null;
  }

  // Support both { token: "..." } and { data: { token: "..." } }
  let token = null;
  try {
    const body = res.json();
    token = (body && (body.token || (body.data && body.data.token))) || null;
  } catch (e) {
    token = null;
  }

  if (token) {
    __cachedToken = token;
  }
  return token;
}

/**
 * Perform an authenticated request, auto-attaching the JWT token.
 * Usage: authenticatedRequest('get', `/api/v1/users/123`, token)
 */
export function authenticatedRequest(method, path, token, body = null) {
  const url = path.startsWith('http') ? path : `${BASE_URL}${path}`;
  const headers = Object.assign({}, commonHeaders, {
    Authorization: token ? `Bearer ${token}` : '',
  });

  const params = { headers };
  let res;
  const start = Date.now();
  switch (method.toLowerCase()) {
    case 'get':
      res = http.get(url, params);
      break;
    case 'post':
      res = http.post(url, body ? JSON.stringify(body) : '{}', params);
      break;
    case 'put':
      res = http.put(url, body ? JSON.stringify(body) : '{}', params);
      break;
    case 'patch':
      res = http.patch(url, body ? JSON.stringify(body) : '{}', params);
      break;
    case 'delete':
      res = http.del(url, null, params);
      break;
    default:
      res = http.request(method.toUpperCase(), url, body ? JSON.stringify(body) : null, params);
  }
  metrics.request_latency.add(Date.now() - start);
  return res;
}

/**
 * Standard check helper that records the error_rate metric.
 */
export function recordCheck(passed) {
  metrics.error_rate.add(!passed);
  return passed;
}

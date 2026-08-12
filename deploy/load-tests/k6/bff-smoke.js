/**
 * bff-smoke.js — Smoke test for the BFF service.
 *
 * Minimal load to verify all BFF endpoints work end-to-end:
 *   login -> get user -> list courses -> create order -> pay order
 *
 * Run: k6 run bff-smoke.js
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import {
  BASE_URL,
  commonHeaders,
  login,
  authenticatedRequest,
  thinkTime,
  generateIdempotencyKey,
  randomUserId,
  randomCourseId,
  metrics,
  recordCheck,
} from './config/common.js';

export const options = {
  vus: 10,
  duration: '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
  tags: {
    scenario: 'bff-smoke',
    target: '10-vu',
  },
};

export default function () {
  // 1. Login
  const token = login();
  check(token, { 'login returns token': (t) => t !== null && t.length > 0 });

  thinkTime(1, 2);

  // 2. Get user
  const userId = randomUserId();
  const userRes = authenticatedRequest('get', `/api/v1/users/${userId}`, token);
  check(userRes, {
    'get user 200': (r) => r.status === 200,
  }) || recordCheck(false);
  recordCheck(userRes.status === 200);

  thinkTime(1, 3);

  // 3. List courses
  const coursesRes = authenticatedRequest('get', '/api/v1/courses', token);
  check(coursesRes, {
    'list courses 200': (r) => r.status === 200,
  }) || recordCheck(false);
  recordCheck(coursesRes.status === 200);

  thinkTime(1, 3);

  // 4. Create order
  const courseId = randomCourseId();
  const idemKey = generateIdempotencyKey();
  const orderPayload = {
    courseId,
    userId,
    amount: 99.0,
    idempotencyKey: idemKey,
  };
  const createRes = http.post(
    `${BASE_URL}/api/v1/orders`,
    JSON.stringify(orderPayload),
    {
      headers: Object.assign({}, commonHeaders, {
        Authorization: `Bearer ${token}`,
        'X-Idempotency-Key': idemKey,
      }),
    }
  );
  check(createRes, {
    'create order 200|201': (r) => r.status === 200 || r.status === 201,
  }) || recordCheck(false);
  recordCheck(createRes.status === 200 || createRes.status === 201);

  let orderId = null;
  try {
    const body = createRes.json();
    orderId = (body && (body.id || (body.data && body.data.id))) || null;
  } catch (e) {
    orderId = null;
  }

  thinkTime(1, 2);

  // 5. Pay order (only if we got an orderId)
  if (orderId) {
    const payRes = http.post(
      `${BASE_URL}/api/v1/orders/${orderId}/pay`,
      JSON.stringify({ paymentMethod: 'mock' }),
      {
        headers: Object.assign({}, commonHeaders, {
          Authorization: `Bearer ${token}`,
        }),
      }
    );
    check(payRes, {
      'pay order 200': (r) => r.status === 200,
    }) || recordCheck(false);
    recordCheck(payRes.status === 200);
    if (payRes.status === 200) {
      metrics.orders_created_total.add(1);
    }
  }

  thinkTime(1, 2);
}

export function handleSummary(data) {
  return {
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  return `BFF Smoke Test complete.\n`;
}

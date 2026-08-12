/**
 * bff-ramp.js — Ramp-up test, gradually increase to 10K concurrent users.
 *
 * Stages:
 *   0 -> 1000 VUs   (2 min)
 *   1000 -> 5000    (3 min)
 *   5000 -> 10000   (5 min)
 *   hold 10000      (10 min)
 *   10000 -> 0      (2 min)
 *
 * Same flow as the smoke test.
 *
 * Run: k6 run bff-ramp.js
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
  scenarios: {
    'bff-ramp': {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '2m', target: 1000 },
        { duration: '3m', target: 5000 },
        { duration: '5m', target: 10000 },
        { duration: '10m', target: 10000 },
        { duration: '2m', target: 0 },
      ],
      gracefulRampDown: '30s',
      tags: { scenario: 'bff-ramp', target: '10k-concurrent' },
    },
  },
  thresholds: {
    'http_req_duration{scenario:bff-ramp}': ['p(95)<500', 'p(99)<2000'],
    'http_req_failed{scenario:bff-ramp}': ['rate<0.01'],
    error_rate: ['rate<0.01'],
  },
  tags: {
    scenario: 'bff-ramp',
    target: '10k-concurrent',
  },
};

export default function () {
  // 1. Login
  const token = login();
  check(token, { 'login returns token': (t) => t !== null });

  thinkTime(1, 2);

  // 2. Get user
  const userId = randomUserId();
  const userRes = authenticatedRequest('get', `/api/v1/users/${userId}`, token);
  check(userRes, {
    'get user 200': (r) => r.status === 200,
  });
  recordCheck(userRes.status === 200);

  thinkTime(1, 3);

  // 3. List courses
  const coursesRes = authenticatedRequest('get', '/api/v1/courses', token);
  check(coursesRes, {
    'list courses 200': (r) => r.status === 200,
  });
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
  });
  recordCheck(createRes.status === 200 || createRes.status === 201);

  let orderId = null;
  try {
    const body = createRes.json();
    orderId = (body && (body.id || (body.data && body.data.id))) || null;
  } catch (e) {
    orderId = null;
  }

  thinkTime(1, 2);

  // 5. Pay order
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
    });
    recordCheck(payRes.status === 200);
    if (payRes.status === 200) {
      metrics.orders_created_total.add(1);
    }
  }

  thinkTime(1, 2);
}

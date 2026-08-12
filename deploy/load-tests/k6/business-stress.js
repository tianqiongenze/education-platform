/**
 * business-stress.js — Stress test for the business middle-platform.
 *
 * Goal: find the ceiling of the business service, focusing on the
 * write-heavy order creation endpoint. Each VU creates orders with
 * unique idempotency keys.
 *
 * Stages:
 *   0 -> 2000    (1 min)
 *   2000 -> 5000 (2 min)
 *   5000 -> 10000 (3 min)
 *   hold 10000   (5 min)
 *   10000 -> 20000 (3 min)
 *   hold 20000   (5 min)
 *   -> 0         (1 min)
 *
 * Run: k6 run business-stress.js
 */
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import {
  BUSINESS_URL,
  commonHeaders,
  login,
  thinkTime,
  generateIdempotencyKey,
  randomUserId,
  randomCourseId,
  metrics,
  recordCheck,
} from './config/common.js';

// Per-script custom metrics (some come from common.js, define script-specific here)
const orders_per_sec = new Rate('orders_per_sec');
const idempotency_dedup_rate = new Rate('idempotency_dedup_rate');
const order_create_latency = new Trend('order_create_latency', true);

export const options = {
  scenarios: {
    'business-stress': {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '1m', target: 2000 },
        { duration: '2m', target: 5000 },
        { duration: '3m', target: 10000 },
        { duration: '5m', target: 10000 },
        { duration: '3m', target: 20000 },
        { duration: '5m', target: 20000 },
        { duration: '1m', target: 0 },
      ],
      gracefulRampDown: '30s',
      tags: { scenario: 'business-stress' },
    },
  },
  thresholds: {
    'http_req_duration{scenario:business-stress}': ['p(99)<3000'],
    'http_req_failed{scenario:business-stress}': ['rate<0.05'],
    error_rate: ['rate<0.05'],
  },
  tags: {
    scenario: 'business-stress',
    target: '20k-stress',
  },
};

export default function () {
  const token = login();

  group('create user + course lookup', () => {
    // Create a user (write path)
    const userPayload = {
      username: `stress_user_${__VU}_${__ITER}`,
      email: `stress_${__VU}_${__ITER}@loadtest.com`,
      password: 'StressTest#2026',
    };
    const userRes = http.post(
      `${BUSINESS_URL}/api/v1/users`,
      JSON.stringify(userPayload),
      { headers: commonHeaders }
    );
    check(userRes, {
      'create user 200|201': (r) => r.status === 200 || r.status === 201,
    });
    recordCheck(userRes.status === 200 || userRes.status === 201);
  });

  thinkTime(0.5, 1.5);

  group('order creation (write-heavy)', () => {
    const userId = randomUserId();
    const courseId = randomCourseId();
    const idemKey = generateIdempotencyKey();
    const orderPayload = {
      courseId,
      userId,
      amount: Math.round(Math.random() * 1000) / 10 + 9.9,
      idempotencyKey: idemKey,
    };

    const start = Date.now();
    const createRes = http.post(
      `${BUSINESS_URL}/api/v1/orders`,
      JSON.stringify(orderPayload),
      {
        headers: Object.assign({}, commonHeaders, {
          'X-Idempotency-Key': idemKey,
          Authorization: token ? `Bearer ${token}` : '',
        }),
      }
    );
    order_create_latency.add(Date.now() - start);

    const ok = createRes.status === 200 || createRes.status === 201;
    check(createRes, {
      'create order 200|201': (r) => r.status === 200 || r.status === 201,
    });
    recordCheck(ok);

    if (ok) {
      orders_per_sec.add(1);
      metrics.orders_created_total.add(1);

      // Detect idempotency dedup by re-issuing with same key
      const dupRes = http.post(
        `${BUSINESS_URL}/api/v1/orders`,
        JSON.stringify(orderPayload),
        {
          headers: Object.assign({}, commonHeaders, {
            'X-Idempotency-Key': idemKey,
            Authorization: token ? `Bearer ${token}` : '',
          }),
        }
      );
      const deduped =
        dupRes.status === 200 &&
        createRes.body &&
        dupRes.body &&
        createRes.body === dupRes.body;
      idempotency_dedup_rate.add(deduped ? 1 : 0);

      // Pay the order if we have an id
      let orderId = null;
      try {
        const body = createRes.json();
        orderId = (body && (body.id || (body.data && body.data.id))) || null;
      } catch (e) {
        orderId = null;
      }
      if (orderId) {
        const payRes = http.post(
          `${BUSINESS_URL}/api/v1/orders/${orderId}/pay`,
          JSON.stringify({ paymentMethod: 'mock' }),
          {
            headers: Object.assign({}, commonHeaders, {
              Authorization: token ? `Bearer ${token}` : '',
            }),
          }
        );
        check(payRes, {
          'pay order 200': (r) => r.status === 200,
        });
      }
    } else {
      orders_per_sec.add(0);
    }
  });

  thinkTime(0.5, 1.5);
}

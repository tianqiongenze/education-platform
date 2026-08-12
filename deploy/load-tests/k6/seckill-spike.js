/**
 * seckill-spike.js — Flash sale (seckill) extreme spike scenario.
 *
 * All VUs hit POST /api/v1/orders simultaneously with the same courseId.
 * Each VU uses a unique idempotency key so all create different orders.
 *
 * Stages:
 *   0 -> 50000 VUs in 10 seconds (extreme spike)
 *   hold 50000 for 30 seconds
 *   50000 -> 0 in 10 seconds
 *
 * Run: k6 run seckill-spike.js
 *
 * NOTE: requires k6 built with xk6 or recent k6 supporting 50K VUs.
 * Tune --vus / GOMAXPROCS for the host running this.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import {
  BASE_URL,
  BUSINESS_URL,
  commonHeaders,
  login,
  generateIdempotencyKey,
  randomUserId,
  metrics,
  recordCheck,
} from './config/common.js';

const spike_qps = new Rate('spike_qps');
const orders_created_total = new Counter('orders_created_total');
const seckill_latency = new Trend('seckill_latency', true);

// The flash-sale course — all VUs target the same courseId.
const SECKILL_COURSE_ID = parseInt(__ENV.SECKILL_COURSE_ID || '9999', 10);

export const options = {
  scenarios: {
    'seckill-spike': {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 50000 },
        { duration: '30s', target: 50000 },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '5s',
      tags: { scenario: 'seckill-spike' },
    },
  },
  thresholds: {
    'http_req_failed{scenario:seckill-spike}': ['rate<0.10'],
    'http_req_duration{scenario:seckill-spike}': ['p(99)<5000'],
    error_rate: ['rate<0.10'],
  },
  tags: {
    scenario: 'seckill-spike',
    target: '50k-spike',
  },
};

export default function () {
  const token = login();
  const userId = randomUserId();
  const idemKey = generateIdempotencyKey();

  const payload = {
    courseId: SECKILL_COURSE_ID,
    userId,
    amount: 9.9,
    idempotencyKey: idemKey,
    scenario: 'seckill',
  };

  const start = Date.now();
  const res = http.post(
    `${BUSINESS_URL}/api/v1/orders`,
    JSON.stringify(payload),
    {
      headers: Object.assign({}, commonHeaders, {
        Authorization: token ? `Bearer ${token}` : '',
        'X-Idempotency-Key': idemKey,
        'X-Scenario': 'seckill',
      }),
      // Seckill doesn't need follow redirects; fail fast.
      redirects: 0,
      timeout: '10s',
    }
  );
  seckill_latency.add(Date.now() - start);

  // Accept 200/201 as success; 429/503 are expected under load (allowed up to 10%).
  const ok = res.status === 200 || res.status === 201;
  const degraded = res.status === 429 || res.status === 503;

  check(res, {
    'order created (200|201)': (r) => r.status === 200 || r.status === 201,
    'degradation handled (429|503)': (r) => r.status === 429 || r.status === 503 || r.status === 200 || r.status === 201,
  });

  recordCheck(ok || degraded);

  if (ok) {
    spike_qps.add(1);
    orders_created_total.add(1);
  } else {
    spike_qps.add(0);
  }

  // Minimal sleep — seckill is bursty, but allow a tiny gap to avoid HTTP pipe exhaustion.
  sleep(0.05);
}

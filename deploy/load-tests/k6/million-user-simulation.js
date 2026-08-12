/**
 * million-user-simulation.js — Full 1M concurrent user simulation.
 *
 * Designed for distributed k6 execution: 10 k6 instances x 100K VUs each.
 * Use --execution-segment and --execution-segment-sequence to split VUs
 * across instances.
 *
 * Scenarios:
 *   60% browse  (GET)
 *   25% interact (POST)
 *   10% video/ws (lightweight WS-ish GET probe)
 *    5% admin
 *
 * Stages (per instance):
 *   ramp to 100K over 5 min
 *   hold 100K for 20 min
 *   ramp down to 0 over 5 min
 *
 * SP-09 SLO thresholds:
 *   Browse   P95 < 200ms, P99 < 500ms
 *   Interact P95 < 500ms, P99 < 2000ms
 *   Error rate < 0.5%
 *
 * Distributed execution example (3-node cluster):
 *   Node 1: k6 run --execution-segment '0:1/3' --execution-segment-sequence '0,1/3,2/3,1' million-user-simulation.js
 *   Node 2: k6 run --execution-segment '1/3:2/3' --execution-segment-sequence '0,1/3,2/3,1' million-user-simulation.js
 *   Node 3: k6 run --execution-segment '2/3:1' --execution-segment-sequence '0,1/3,2/3,1' million-user-simulation.js
 *
 * For 10-node cluster (1M total), use sequence '0,1/10,2/10,...,9/10,1'
 * and segments 'k/10:(k+1)/10' for k=0..9.
 */
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import {
  BASE_URL,
  BUSINESS_URL,
  ITBACKEND_URL,
  AIGW_URL,
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

// ---------------------------------------------------------------------------
// Per-scenario custom metrics / dashboards
// ---------------------------------------------------------------------------
const browse_rps = new Rate('browse_rps');
const interact_rps = new Rate('interact_rps');
const video_ws_rps = new Rate('video_ws_rps');
const admin_rps = new Rate('admin_rps');

const browse_latency = new Trend('browse_latency', true);
const interact_latency = new Trend('interact_latency', true);
const video_ws_latency = new Trend('video_ws_latency', true);
const admin_latency = new Trend('admin_latency', true);

const error_breakdown_browse = new Counter('error_breakdown_browse');
const error_breakdown_interact = new Counter('error_breakdown_interact');
const error_breakdown_admin = new Counter('error_breakdown_admin');

// ---------------------------------------------------------------------------
// Options — distributed execution aware
// ---------------------------------------------------------------------------
export const options = {
  scenarios: {
    browse: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '5m', target: 60000 }, // 60% of 100K
        { duration: '20m', target: 60000 },
        { duration: '5m', target: 0 },
      ],
      gracefulRampDown: '30s',
      exec: 'browseScenario',
      tags: { scenario: 'browse' },
    },
    interact: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '5m', target: 25000 }, // 25% of 100K
        { duration: '20m', target: 25000 },
        { duration: '5m', target: 0 },
      ],
      gracefulRampDown: '30s',
      exec: 'interactScenario',
      tags: { scenario: 'interact' },
    },
    videows: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '5m', target: 10000 }, // 10% of 100K
        { duration: '20m', target: 10000 },
        { duration: '5m', target: 0 },
      ],
      gracefulRampDown: '30s',
      exec: 'videoWsScenario',
      tags: { scenario: 'videows' },
    },
    admin: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '5m', target: 5000 }, // 5% of 100K
        { duration: '20m', target: 5000 },
        { duration: '5m', target: 0 },
      ],
      gracefulRampDown: '30s',
      exec: 'adminScenario',
      tags: { scenario: 'admin' },
    },
  },
  thresholds: {
    // SP-09 SLO
    'browse_latency': ['p(95)<200', 'p(99)<500'],
    'interact_latency': ['p(95)<500', 'p(99)<2000'],
    'http_req_failed': ['rate<0.005'],
    error_rate: ['rate<0.005'],
  },
  // Distributed execution segment support — set via CLI flags at runtime.
  // These are placeholders; the real values come from --execution-segment.
  tags: {
    test: 'million-user-simulation',
    target: '1m-concurrent',
  },
};

// ---------------------------------------------------------------------------
// Scenario: browse (60%) — GETs
// ---------------------------------------------------------------------------
export function browseScenario() {
  const token = login();
  const start = Date.now();

  group('browse: get user', () => {
    const r = authenticatedRequest('get', `/api/v1/users/${randomUserId()}`, token);
    browse_latency.add(Date.now() - start);
    browse_rps.add(r.status === 200 ? 1 : 0);
    if (r.status !== 200) error_breakdown_browse.add(1);
    check(r, { 'browse user 200': (x) => x.status === 200 });
    recordCheck(r.status === 200);
  });

  thinkTime(1, 3);

  const start2 = Date.now();
  group('browse: list courses', () => {
    const r = authenticatedRequest('get', `/api/v1/courses?page=${1 + (__ITER % 10)}`, token);
    browse_latency.add(Date.now() - start2);
    browse_rps.add(r.status === 200 ? 1 : 0);
    if (r.status !== 200) error_breakdown_browse.add(1);
    check(r, { 'browse courses 200': (x) => x.status === 200 });
    recordCheck(r.status === 200);
  });

  thinkTime(2, 5);
}

// ---------------------------------------------------------------------------
// Scenario: interact (25%) — POSTs
// ---------------------------------------------------------------------------
export function interactScenario() {
  const token = login();
  const idemKey = generateIdempotencyKey();
  const start = Date.now();

  group('interact: create order', () => {
    const payload = {
      courseId: randomCourseId(),
      userId: randomUserId(),
      amount: 9.9,
      idempotencyKey: idemKey,
    };
    const r = http.post(`${BUSINESS_URL}/api/v1/orders`, JSON.stringify(payload), {
      headers: Object.assign({}, commonHeaders, {
        Authorization: token ? `Bearer ${token}` : '',
        'X-Idempotency-Key': idemKey,
      }),
    });
    interact_latency.add(Date.now() - start);
    interact_rps.add(r.status === 200 || r.status === 201 ? 1 : 0);
    if (!(r.status === 200 || r.status === 201)) error_breakdown_interact.add(1);
    check(r, {
      'interact create order 200|201': (x) => x.status === 200 || x.status === 201,
    });
    recordCheck(r.status === 200 || r.status === 201);
    if (r.status === 200 || r.status === 201) {
      metrics.orders_created_total.add(1);
    }
  });

  thinkTime(1, 3);
}

// ---------------------------------------------------------------------------
// Scenario: video/ws (10%) — lightweight probe (uses BFF health-like GET)
// ---------------------------------------------------------------------------
export function videoWsScenario() {
  const token = login();
  const start = Date.now();

  group('videows: stream probe', () => {
    // Probe a video/WS gateway endpoint. Falls back to BFF if not present.
    const r = http.get(`${BASE_URL}/api/v1/courses/${randomCourseId()}`, {
      headers: Object.assign({}, commonHeaders, {
        Authorization: token ? `Bearer ${token}` : '',
        'X-Probe': 'video-ws',
      }),
    });
    video_ws_latency.add(Date.now() - start);
    video_ws_rps.add(r.status === 200 ? 1 : 0);
    check(r, { 'video/ws probe 200': (x) => x.status === 200 });
    recordCheck(r.status === 200);
  });

  thinkTime(2, 5);
}

// ---------------------------------------------------------------------------
// Scenario: admin (5%) — heavier GETs / management endpoints
// ---------------------------------------------------------------------------
export function adminScenario() {
  const token = login();
  const start = Date.now();

  group('admin: list all users', () => {
    const r = authenticatedRequest('get', `/api/v1/users?limit=50&offset=${__ITER * 50}`, token);
    admin_latency.add(Date.now() - start);
    admin_rps.add(r.status === 200 ? 1 : 0);
    if (r.status !== 200) error_breakdown_admin.add(1);
    check(r, { 'admin users 200': (x) => x.status === 200 });
    recordCheck(r.status === 200);
  });

  thinkTime(2, 4);

  const start2 = Date.now();
  group('admin: ai gateway health', () => {
    const r = http.get(`${AIGW_URL}/health`, { headers: commonHeaders });
    admin_latency.add(Date.now() - start2);
    admin_rps.add(r.status === 200 ? 1 : 0);
    check(r, { 'admin aigw health 200': (x) => x.status === 200 });
    recordCheck(r.status === 200);
  });

  thinkTime(2, 4);
}

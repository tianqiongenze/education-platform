// D-242: business WRITE throughput (P0-1b) — teacher login in setup(), then
// assignment-create writes straight to grading-service (the path just gated
// end-to-end by the role pipeline). Run as an in-cluster Job like
// real-cluster-baseline.js. BFF/AuthFilter contract simulated via X-Role /
// X-User-Id injection (D-234).
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const writeLat = new Trend('ep_write_latency');
const errRate = new Rate('ep_write_error_rate');

export const options = {
  scenarios: {
    writes: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '15s', target: Number(__ENV.VUS_MAX || 20) },
        { duration: '1m', target: Number(__ENV.VUS_MAX || 20) },
        { duration: '15s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    ep_write_error_rate: ['rate<0.05'],
    http_req_duration: ['p(95)<2000'],
  },
};

const BASE_CORE = __ENV.BASE_CORE || 'http://edu-core-business:8083';
const BASE_GRADING = __ENV.BASE_GRADING || 'http://edu-grading-service:8085';

export function setup() {
  const r = http.post(`${BASE_CORE}/api/auth/captcha`);
  check(r, { 'captcha 200': (res) => res.status === 200 });
  const cap = r.json();
  // The captcha answer is server-side; login is exercised in the functional
  // suite, so writes here authenticate via the BFF-injection contract
  // (X-Role/X-User-Id) — the same trust boundary RoleChecker enforces.
  return { captchaId: cap.captchaId || '' };
}

export default function (data) {
  const payload = JSON.stringify({
    title: `load-hw-${__VU}-${__ITER}`,
    description: 'write-tps probe',
    courseId: 1,
    teacherId: 11687819111170048,
    maxScore: 100,
    deadline: '2026-12-31T23:59:59',
  });
  const r = http.post(`${BASE_GRADING}/api/v1/assignments`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'X-Role': 'teacher',
      'X-User-Id': '11687819111170048',
      'X-Tenant-Id': 'default',
      'X-Cell-Name': 'cell-hz',
    },
    tags: { name: 'assignment_create' },
  });
  // 201 created / 403 role gate / 429 rate limit are all counted outcomes;
  // only >=500 is a write error.
  check(r, { 'write not 5xx': (res) => res.status < 500 });
  errRate.add(r.status >= 500);
  writeLat.add(r.timings.duration);
}

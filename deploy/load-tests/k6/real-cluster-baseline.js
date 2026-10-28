// D-241: REAL-CLUSTER baseline (first non-mock k6 run in project history).
// v1/v2 reports were mock-server only; this executes against the live 2-node
// applications-cluster ClusterIPs from the master host (k6 binary deployed
// there). Endpoints are the same functional paths proven by T0~T7 smoke.
//
// Run: k6 run -e BASE_BFF=http://edu-portal-bff:80 -e BASE_CORE=http://edu-core-business:8083 real-cluster-baseline.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errRate = new Rate('ep_error_rate');
const bffLat = new Trend('ep_bff_latency');

export const options = {
  scenarios: {
    health_read: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '30s', target: Number(__ENV.VUS_MAX || 50) },
        { duration: '2m', target: Number(__ENV.VUS_MAX || 50) },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '15s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<800', 'p(99)<1500'],
    ep_error_rate: ['rate<0.01'],
  },
  noConnectionReuse: false,
};

const BASE_BFF = __ENV.BASE_BFF || 'http://edu-portal-bff:80';
const BASE_CORE = __ENV.BASE_CORE || 'http://edu-core-business:8083';

export default function () {
  // 1. BFF health (Jetty, ep-web-common filters in the path)
  let r = http.get(`${BASE_BFF}/actuator/health`, { tags: { name: 'bff_health' } });
  check(r, { 'bff health 200': (res) => res.status === 200 });
  errRate.add(r.status >= 500);
  bffLat.add(r.timings.duration);

  // 2. Core service health
  r = http.get(`${BASE_CORE}/actuator/health`, { tags: { name: 'core_health' } });
  check(r, { 'core health 200': (res) => res.status === 200 });
  errRate.add(r.status >= 500);

  // 3. Captcha issuance (real business read: math puzzle + id persistence)
  r = http.get(`${BASE_CORE}/api/auth/captcha`, { tags: { name: 'captcha' } });
  check(r, { 'captcha 200': (res) => res.status === 200 });
  errRate.add(r.status >= 500);

  sleep(1);
}

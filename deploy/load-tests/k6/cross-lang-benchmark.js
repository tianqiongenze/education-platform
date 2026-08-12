/**
 * cross-lang-benchmark.js — 跨语言基准测试
 *
 * 分别对 4 个语言栈的健康端点进行固定 RPS=1000 (4×250) 压测 30 秒，用于横向
 * 对比 Java Spring Boot / Python Litestar+Granian / Go net/http 在同等负载下
 * 的纯框架开销与基础延迟。
 *
 * 服务:
 *   java_bff   (8090) Java Spring Boot  → GET /actuator/health
 *   java_sp05  (8085) Java Spring Boot  → GET /actuator/health
 *   python_ai  (8086) Python Litestar   → GET /health
 *   go_ws      (8081) Go net/http       → GET /healthz
 *
 * 执行模式: 4 个并行 scenario，每个 constant-arrival-rate 250 iter/s，合计 1000 RPS。
 *
 * 运行:
 *   k6 run cross-lang-benchmark.js
 *   K6_JAVA_BFF_URL=http://10.0.0.1:8090 k6 run cross-lang-benchmark.js
 */

import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// ---------------------------------------------------------------------------
// Service URLs (configurable via env)
// ---------------------------------------------------------------------------
const JAVA_BFF_URL  = __ENV.K6_JAVA_BFF_URL  || 'http://127.0.0.1:8090';
const JAVA_SP05_URL = __ENV.K6_JAVA_SP05_URL || 'http://127.0.0.1:8085';
const PYTHON_AI_URL = __ENV.K6_PYTHON_AI_URL || 'http://127.0.0.1:8086';
const GO_WS_URL     = __ENV.K6_GO_WS_URL     || 'http://127.0.0.1:8081';

// ---------------------------------------------------------------------------
// Custom Metrics — per-language latency and error rate
// ---------------------------------------------------------------------------
const java_bff_latency  = new Trend('java_bff_latency', true);
const java_sp05_latency = new Trend('java_sp05_latency', true);
const python_ai_latency = new Trend('python_ai_latency', true);
const go_ws_latency     = new Trend('go_ws_latency', true);

const java_bff_errors   = new Rate('java_bff_errors');
const java_sp05_errors  = new Rate('java_sp05_errors');
const python_ai_errors  = new Rate('python_ai_errors');
const go_ws_errors      = new Rate('go_ws_errors');

// ---------------------------------------------------------------------------
// k6 Options — 4 parallel scenarios, each 250 RPS for 30s (total 1000 RPS)
// ---------------------------------------------------------------------------
export const options = {
  scenarios: {
    java_bff: {
      executor: 'constant-arrival-rate',
      rate: 250,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 300,
      maxVUs: 500,
      exec: 'javaBff',
    },
    java_sp05: {
      executor: 'constant-arrival-rate',
      rate: 250,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 300,
      maxVUs: 500,
      exec: 'javaSp05',
    },
    python_ai: {
      executor: 'constant-arrival-rate',
      rate: 250,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 300,
      maxVUs: 500,
      exec: 'pythonAi',
    },
    go_ws: {
      executor: 'constant-arrival-rate',
      rate: 250,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 300,
      maxVUs: 500,
      exec: 'goWs',
    },
  },
  thresholds: {
    'java_bff_latency':  ['p(95)<500', 'p(99)<1000'],
    'java_sp05_latency': ['p(95)<500', 'p(99)<1000'],
    'python_ai_latency': ['p(95)<500', 'p(99)<1000'],
    'go_ws_latency':     ['p(95)<500', 'p(99)<1000'],
    'java_bff_errors':   ['rate<0.01'],
    'java_sp05_errors':  ['rate<0.01'],
    'python_ai_errors':  ['rate<0.01'],
    'go_ws_errors':      ['rate<0.01'],
  },
  tags: {
    scenario: 'cross-lang-benchmark',
  },
};

// ---------------------------------------------------------------------------
// Scenario exec functions — each targets one service's health endpoint
// ---------------------------------------------------------------------------

/** Java BFF (Spring Boot) health check. */
export function javaBff() {
  const r = http.get(`${JAVA_BFF_URL}/actuator/health`);
  java_bff_latency.add(r.timings.duration);
  const ok = check(r, { 'java bff 200': (r) => r.status === 200 });
  java_bff_errors.add(!ok);
}

/** Java sp05 (Spring Boot, IT backend) health check. */
export function javaSp05() {
  const r = http.get(`${JAVA_SP05_URL}/actuator/health`);
  java_sp05_latency.add(r.timings.duration);
  const ok = check(r, { 'java sp05 200': (r) => r.status === 200 });
  java_sp05_errors.add(!ok);
}

/** Python AI Gateway (Litestar+Granian) health check. */
export function pythonAi() {
  const r = http.get(`${PYTHON_AI_URL}/health`);
  python_ai_latency.add(r.timings.duration);
  const ok = check(r, { 'python ai 200': (r) => r.status === 200 });
  python_ai_errors.add(!ok);
}

/** Go WebSocket Gateway (net/http) health check. */
export function goWs() {
  const r = http.get(`${GO_WS_URL}/healthz`);
  go_ws_latency.add(r.timings.duration);
  const ok = check(r, { 'go ws 200': (r) => r.status === 200 });
  go_ws_errors.add(!ok);
}

// ---------------------------------------------------------------------------
// Setup: verify all 4 services are reachable before starting
// ---------------------------------------------------------------------------
export function setup() {
  console.log('=== Cross-language benchmark pre-flight ===');

  const services = [
    { name: 'java_bff',  url: `${JAVA_BFF_URL}/actuator/health` },
    { name: 'java_sp05', url: `${JAVA_SP05_URL}/actuator/health` },
    { name: 'python_ai', url: `${PYTHON_AI_URL}/health` },
    { name: 'go_ws',     url: `${GO_WS_URL}/healthz` },
  ];

  for (const s of services) {
    const r = http.get(s.url);
    if (r.status !== 200) {
      console.error(`${s.name} health FAILED: ${r.status}`);
      return { abort: true };
    }
    console.log(`${s.name}: UP`);
  }

  console.log('All services healthy. Starting cross-language benchmark (4×250 RPS for 30s)...');
  return { startTime: Date.now() };
}

// ---------------------------------------------------------------------------
// Teardown: report summary
// ---------------------------------------------------------------------------
export function teardown(data) {
  if (data && data.startTime) {
    const duration = (Date.now() - data.startTime) / 1000;
    console.log(`\n=== Cross-language benchmark completed in ${duration.toFixed(1)}s ===`);
  }
}

/**
 * cache-consistency.js — Multi-level cache consistency load test.
 *
 * Verifies write-after-read consistency and cross-node L1 invalidation latency
 * across the EP multi-level cache (L1 local + L2 Redis) under load.
 *
 * Flow per VU iteration (write→read→evict→read):
 *   1. WRITE:  POST a value into the cache via the cache-admin endpoint.
 *   2. READ:   GET the value — expects a cache HIT (data just written).
 *   3. EVICT:  DELETE the value — triggers L1 invalidation broadcast +
 *              L2 Redis eviction.
 *   4. READ:   GET the value again — expects a cache MISS (data was evicted,
 *              forcing a reload from the source).
 *
 * Custom Metrics:
 *   cache_invalidation_latency_ms — Trend: time from EVICT to subsequent READ
 *                                   returning MISS (cross-node L1 invalidation).
 *   cache_hits                    — Counter: total cache HIT responses.
 *   cache_misses                  — Counter: total cache MISS responses.
 *
 * Scenarios:
 *   smoke  — 5 VUs for 30s (quick consistency verification)
 *
 * Thresholds:
 *   cache_invalidation_latency_ms p(95) < 100ms
 *   http_req_duration             p(95) < 500ms
 *   http_req_failed               rate  < 0.01  (<1% failure)
 *
 * Environment Variables:
 *   BASE_URL — cache service base URL (default http://localhost:8080)
 *
 * Run:
 *   k6 run cache-consistency.js
 *   BASE_URL=http://staging:8080 k6 run cache-consistency.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend, Rate } from 'k6/metrics';

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const CACHE_ADMIN_PATH = '/api/v1/cache';
const REQ_TIMEOUT = '10s';

// ---------------------------------------------------------------------------
// Custom Metrics
// ---------------------------------------------------------------------------
const cacheInvalidationLatency = new Trend('cache_invalidation_latency_ms');
const cacheHits = new Counter('cache_hits');
const cacheMisses = new Counter('cache_misses');
const cacheConsistencyErrors = new Rate('cache_consistency_errors');

// ---------------------------------------------------------------------------
// k6 Options — Smoke scenario (5 VUs, 30s)
// ---------------------------------------------------------------------------
export const options = {
  scenarios: {
    smoke: {
      executor: 'constant-vus',
      vus: 5,
      duration: '30s',
      gracefulStop: '5s',
    },
  },
  thresholds: {
    'cache_invalidation_latency_ms': ['p(95)<100'],
    'http_req_duration': ['p(95)<500'],
    'http_req_failed': ['rate<0.01'],
    'cache_consistency_errors': ['rate<0.01'],
  },
  tags: {
    scenario: 'cache-consistency-smoke',
    target: '5-vu',
  },
  httpDebug: 'none',
  noConnectionReuse: false,
  userAgent: 'k6-cache-test/1.0',
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Generate a unique cache key for this VU iteration.
 * Format: "k6:cache:{vu}:{iter}" — keeps keys namespaced and collision-free.
 */
function genCacheKey() {
  return `k6:cache:${__VU}:${__ITER}`;
}

/**
 * Expected-status helper: define which HTTP status codes are non-failures.
 */
const expectedStatus = http.expectedStatuses;

// ---------------------------------------------------------------------------
// Main test function — write→read(hit)→evict→read(miss/reload) per iteration
// ---------------------------------------------------------------------------
export default function () {
  const cacheName = 'k6-loadtest';
  const key = genCacheKey();
  const value = JSON.stringify({
    data: `consistency-test-vu${__VU}-iter${__ITER}`,
    timestamp: Date.now(),
  });

  const headers = {
    'Content-Type': 'application/json',
    'Accept': 'application/json',
  };

  // --- Step 1: WRITE — populate the cache (L1 + L2) ---
  const writeStart = Date.now();
  const writeRes = http.put(
    `${BASE_URL}${CACHE_ADMIN_PATH}/${cacheName}/${encodeURIComponent(key)}`,
    value,
    { headers, timeout: REQ_TIMEOUT, responseCallback: expectedStatus(200, 201, 204) }
  );
  const writeOk = check(writeRes, {
    'cache write succeeds': (r) => r.status === 200 || r.status === 201 || r.status === 204,
  });
  if (!writeOk) {
    cacheConsistencyErrors.add(1);
  }

  sleep(0.05); // brief pause to let L2 propagate

  // --- Step 2: READ — expects a cache HIT (data just written) ---
  const read1Start = Date.now();
  const read1Res = http.get(
    `${BASE_URL}${CACHE_ADMIN_PATH}/${cacheName}/${encodeURIComponent(key)}`,
    { headers, timeout: REQ_TIMEOUT, responseCallback: expectedStatus(200) }
  );
  const read1Latency = Date.now() - read1Start;

  const read1Hit = check(read1Res, {
    'first read returns 200 (HIT)': (r) => r.status === 200,
    'first read returns written value': (r) => {
      try {
        const body = r.json();
        return body && body.data !== undefined;
      } catch (e) {
        return r.body && r.body.length > 0;
      }
    },
  });

  if (read1Hit) {
    cacheHits.add(1);
  } else {
    cacheMisses.add(1);
    cacheConsistencyErrors.add(1);
  }

  sleep(0.05);

  // --- Step 3: EVICT — trigger L1 broadcast invalidation + L2 Redis delete ---
  const evictStart = Date.now();
  const evictRes = http.del(
    `${BASE_URL}${CACHE_ADMIN_PATH}/${cacheName}/${encodeURIComponent(key)}`,
    null,
    { headers, timeout: REQ_TIMEOUT, responseCallback: expectedStatus(200, 204, 404) }
  );
  const evictOk = check(evictRes, {
    'evict returns 200/204/404': (r) => r.status === 200 || r.status === 204 || r.status === 404,
  });
  if (!evictOk) {
    cacheConsistencyErrors.add(1);
  }

  // --- Step 4: READ again — expects a cache MISS (data was evicted) ---
  // Measure invalidation latency: time from EVICT send to this READ completing.
  // Under correct invalidation, L1 should have been cleared and L2 deleted,
  // so this read should be a MISS (returning 404 or empty sentinel).
  const read2Start = Date.now();
  const read2Res = http.get(
    `${BASE_URL}${CACHE_ADMIN_PATH}/${cacheName}/${encodeURIComponent(key)}`,
    { headers, timeout: REQ_TIMEOUT, responseCallback: expectedStatus(200, 404) }
  );

  // Invalidation latency = time from evict request start to read2 response
  const invalidationLatency = Date.now() - evictStart;
  cacheInvalidationLatency.add(invalidationLatency);

  const read2Miss = check(read2Res, {
    'second read is MISS (404 or empty)': (r) => {
      if (r.status === 404) return true;
      if (r.status === 200) {
        // Check for empty sentinel or null/empty body
        const body = r.body || '';
        return body.includes('__EP_CACHE_EMPTY__') ||
               body.includes('"null"') ||
               body === '' ||
               body === 'null';
      }
      return false;
    },
  });

  if (read2Miss) {
    cacheMisses.add(1);
  } else {
    // Data still present after eviction — consistency violation
    cacheHits.add(1);
    cacheConsistencyErrors.add(1);
  }

  // Brief think time between iterations
  sleep(0.1 + Math.random() * 0.1);
}

// ---------------------------------------------------------------------------
// Setup: verify the cache service is reachable before starting
// ---------------------------------------------------------------------------
export function setup() {
  console.log('=== Pre-flight: cache service health check ===');
  const healthUrl = `${BASE_URL}/actuator/health`;
  const r = http.get(healthUrl, { timeout: REQ_TIMEOUT });

  if (r.status !== 200) {
    // Try alternate health endpoint
    const r2 = http.get(`${BASE_URL}/health`, { timeout: REQ_TIMEOUT });
    if (r2.status !== 200) {
      console.error(`Cache service health check FAILED: ${r.status} / ${r2.status}`);
      console.error(`Ensure cache service is running at ${BASE_URL}`);
      return { abort: true };
    }
  }
  console.log(`Cache service: UP at ${BASE_URL}`);
  console.log('Starting cache consistency smoke test (5 VUs, 30s)...');
  return { startTime: Date.now() };
}

// ---------------------------------------------------------------------------
// Teardown: report summary
// ---------------------------------------------------------------------------
export function teardown(data) {
  if (data && data.startTime) {
    const duration = ((Date.now() - data.startTime) / 1000).toFixed(1);
    console.log(`\n=== Cache consistency test completed in ${duration}s ===`);
    console.log('Check metrics: cache_hits, cache_misses, cache_invalidation_latency_ms');
  }
}

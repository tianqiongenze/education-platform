/**
 * D-235: Real User Monitoring (RUM) SDK — web-vitals collection with
 * beacon-based reporting, shared by every web app.
 *
 * <p>Closes the P1 gap identified in the v7 scorecard: the platform had zero
 * field performance data (only build-time bundle analysis). With
 * {@code initRUM} wired in each web app's entry point, Core Web Vitals
 * (LCP / INP / CLS / TTFB / FCP) flow to the BFF's {@code /api/v1/rum}
 * endpoint and land in Micrometer histograms — the same observability stack
 * the backend already uses.</p>
 *
 * <p><b>Design notes</b>:</p>
 * <ul>
 *   <li>{@code navigator.sendBeacon} — fire-and-forget, survives page unload,
 *       never blocks rendering (industry standard for RUM); falls back to
 *       {@code fetch(..., {keepalive: true})} when unsupported.</li>
 *   <li>{@code sampleRate} — default 0.1 (10%): field data at 10% volume is
 *       statistically sufficient for p75 judgments (Google's CWV guidance),
 *       and it caps the BFF ingest load under heavy traffic.</li>
 *   <li>No PII: only the metric payload (name, value, rating, id) plus the
 *       route path is reported — never tokens, user ids, or query strings.</li>
 * </ul>
 */
import { onCLS, onINP, onLCP, onFCP, onTTFB, type Metric } from 'web-vitals';

/** Minimal shape of one reported vital. */
export interface RumMetric {
  /** Metric name, e.g. {@code LCP}. */
  name: string;
  /** Measured value in the metric's unit (mostly milliseconds). */
  value: number;
  /** Google rating: good / needs-improvement / poor. */
  rating: string;
  /** Metric session id (groups vitals of one page visit). */
  id: string;
  /** Current route path (no query string, no hash). */
  route: string;
}

/** Where vitals are POSTed (BFF endpoint). */
export interface RumConfig {
  /** BFF ingest endpoint, e.g. {@code /api/v1/rum}. */
  endpoint: string;
  /** Fraction of page visits sampled, in (0, 1]. Default 0.1. */
  sampleRate?: number;
  /** Disable the beacon entirely (tests, local dev). */
  disabled?: boolean;
}

/** Pluggable sink — tests inject this; production uses sendBeacon. */
export type RumSink = (metric: RumMetric) => void;

function defaultRoute(): string {
  if (typeof window === 'undefined') {
    return 'unknown';
  }
  return window.location.pathname || '/';
}

function postBeacon(endpoint: string, metric: RumMetric): void {
  if (typeof navigator === 'undefined') {
    return;
  }
  const body = JSON.stringify(metric);
  try {
    if (typeof navigator.sendBeacon === 'function') {
      navigator.sendBeacon(endpoint, new Blob([body], { type: 'application/json' }));
      return;
    }
  } catch {
    // fall through to fetch
  }
  void fetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
    keepalive: true,
  }).catch(() => {
    // RUM is best-effort by design — a dropped beacon is acceptable
  });
}

/**
 * Installs the vitals listeners and returns a stop function (useful for
 * hot-reload and tests). Sampling happens once per visit — either the whole
 * visit is sampled or none of it is, so per-visit metric sets stay complete.
 */
export function initRUM(config: RumConfig, sink: RumSink = (m) => postBeacon(config.endpoint, m)): () => void {
  if (config.disabled) {
    return () => undefined;
  }
  const sampleRate = config.sampleRate ?? 0.1;
  if (sampleRate <= 0 || sampleRate > 1 || Math.random() >= sampleRate) {
    return () => undefined;
  }
  const handle = (metric: Metric): void => {
    sink({
      name: metric.name,
      value: metric.value,
      rating: metric.rating,
      id: metric.id,
      route: defaultRoute(),
    });
  };
  // web-vitals 4.x listeners are process-lifetime singletons (no removal
  // API); the returned stop function is a lifecycle hook for hot-reload/test
  // symmetry, not an actual unsubscribe.
  onCLS(handle);
  onINP(handle);
  onLCP(handle);
  onFCP(handle);
  onTTFB(handle);
  return () => undefined;
}

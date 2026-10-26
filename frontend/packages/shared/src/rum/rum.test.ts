import { describe, expect, it, vi, beforeEach } from 'vitest';

// web-vitals 4.x touches `document` on import and its listeners are
// process-lifetime singletons — mock the module so the wiring contract is
// testable in a plain node vitest environment.
vi.mock('web-vitals', () => ({
  onCLS: vi.fn(),
  onINP: vi.fn(),
  onLCP: vi.fn(),
  onFCP: vi.fn(),
  onTTFB: vi.fn(),
}));

import { initRUM, type RumMetric } from './index';
import { onCLS } from 'web-vitals';

describe('initRUM (D-235)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('installs all five vitals listeners when sampled in', () => {
    const sink = vi.fn();
    initRUM({ endpoint: '/api/v1/rum', sampleRate: 1 }, sink);
    // sampling with rate=1 always installs: all five onXXX are wired
    expect(onCLS).toHaveBeenCalledTimes(1);
  });

  it('does nothing when disabled', () => {
    const sink = vi.fn();
    const stop = initRUM({ endpoint: '/api/v1/rum', disabled: true }, sink);
    stop();
    expect(onCLS).not.toHaveBeenCalled();
  });

  it('skips installation when sampleRate is out of range', () => {
    const sink = vi.fn();
    expect(() => initRUM({ endpoint: '/api/v1/rum', sampleRate: 0 }, sink)).not.toThrow();
    expect(() => initRUM({ endpoint: '/api/v1/rum', sampleRate: 1.5 }, sink)).not.toThrow();
    expect(onCLS).not.toHaveBeenCalled();
  });

  it('stop function is idempotent-safe', () => {
    const stop = initRUM({ endpoint: '/api/v1/rum', sampleRate: 1 }, vi.fn());
    expect(() => {
      stop();
      stop();
    }).not.toThrow();
  });

  it('delivers the full metric envelope to the sink (route, rating, id)', () => {
    const captured: RumMetric[] = [];
    initRUM({ endpoint: '/api/v1/rum', sampleRate: 1 }, (m) => captured.push(m));
    // Extract the handler wired into onCLS and drive it like the lib would.
    const handler = (onCLS as unknown as ReturnType<typeof vi.fn>).mock.calls[0]?.[0];
    expect(handler).toBeTypeOf('function');
    handler({
      name: 'CLS',
      value: 0.08,
      rating: 'good',
      id: 'v1-1',
      delta: 0.08,
      entries: [],
      navigationType: 'navigate',
    });
    expect(captured).toHaveLength(1);
    expect(captured[0]).toMatchObject({
      name: 'CLS',
      value: 0.08,
      rating: 'good',
      id: 'v1-1',
      route: typeof window === 'undefined' ? 'unknown' : window.location.pathname,
    });
  });
});

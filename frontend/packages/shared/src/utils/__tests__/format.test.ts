import { describe, it, expect } from 'vitest';
import {
  formatDate,
  formatDuration,
  formatPrice,
  formatFileSize,
  formatNumber,
} from '../format';

describe('formatDate', () => {
  it('formats a valid ISO string', () => {
    const result = formatDate('2026-08-04T10:30:00Z');
    expect(result).toMatch(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}/);
  });

  it('returns empty string for invalid input', () => {
    expect(formatDate('not-a-date')).toBe('');
  });

  it('accepts a Date object', () => {
    const result = formatDate(new Date('2026-01-01T00:00:00Z'));
    expect(result).toMatch(/\d{4}-\d{2}-\d{2}/);
  });
});

describe('formatDuration', () => {
  it('formats seconds only', () => {
    expect(formatDuration(30)).toBe('30s');
  });

  it('formats minutes and seconds', () => {
    expect(formatDuration(90)).toBe('1m 30s');
  });

  it('formats hours minutes seconds', () => {
    expect(formatDuration(3930)).toBe('1h 5m 30s');
  });

  it('handles invalid input', () => {
    expect(formatDuration(NaN)).toBe('0s');
    expect(formatDuration(-1)).toBe('0s');
  });
});

describe('formatPrice', () => {
  it('formats CNY by default', () => {
    expect(formatPrice(99.5)).toBe('¥99.50');
  });

  it('formats USD', () => {
    expect(formatPrice(10, 'USD')).toBe('$10.00');
  });

  it('handles invalid numbers', () => {
    expect(formatPrice(NaN)).toBe('0.00');
  });
});

describe('formatFileSize', () => {
  it('formats bytes', () => {
    expect(formatFileSize(500)).toBe('500 B');
  });

  it('formats KB', () => {
    expect(formatFileSize(1024)).toBe('1.00 KB');
  });

  it('formats MB', () => {
    expect(formatFileSize(1024 * 1024)).toBe('1.00 MB');
  });

  it('formats GB', () => {
    expect(formatFileSize(1024 * 1024 * 1024)).toBe('1.00 GB');
  });

  it('handles invalid input', () => {
    expect(formatFileSize(-1)).toBe('0 B');
  });
});

describe('formatNumber', () => {
  it('returns small numbers as-is', () => {
    expect(formatNumber(999)).toBe('999');
  });

  it('formats thousands as k', () => {
    expect(formatNumber(1000)).toBe('1k');
    expect(formatNumber(1500)).toBe('1.5k');
  });

  it('formats millions as M', () => {
    expect(formatNumber(1_000_000)).toBe('1M');
    expect(formatNumber(1_500_000)).toBe('1.5M');
  });

  it('handles negative numbers', () => {
    expect(formatNumber(-1500)).toBe('-1.5k');
  });
});

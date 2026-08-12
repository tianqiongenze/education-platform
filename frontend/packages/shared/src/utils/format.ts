/**
 * Format a date string or timestamp into a localized display string.
 */
export function formatDate(
  input: string | number | Date,
  locale: string = 'zh-CN',
): string {
  const date = input instanceof Date ? input : new Date(input);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  const hours = String(date.getHours()).padStart(2, '0');
  const minutes = String(date.getMinutes()).padStart(2, '0');

  if (locale === 'zh-CN') {
    return `${year}-${month}-${day} ${hours}:${minutes}`;
  }
  return date.toISOString();
}

/**
 * Format a duration in seconds into a human readable string (e.g. "1h 5m 30s").
 */
export function formatDuration(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) {
    return '0s';
  }
  const totalSeconds = Math.floor(seconds);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const secs = totalSeconds % 60;
  const parts: string[] = [];
  if (hours > 0) {
    parts.push(`${hours}h`);
  }
  if (minutes > 0 || hours > 0) {
    parts.push(`${minutes}m`);
  }
  parts.push(`${secs}s`);
  return parts.join(' ');
}

/**
 * Format a price in cents (or yuan if isYuan=true) into a currency display string.
 */
export function formatPrice(amount: number, currency: string = 'CNY'): string {
  if (!Number.isFinite(amount)) {
    return '0.00';
  }
  const symbol = currency === 'CNY' ? '¥' : currency === 'USD' ? '$' : '';
  return `${symbol}${amount.toFixed(2)}`;
}

/**
 * Format a byte count into a human readable string with the largest fitting unit.
 */
export function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) {
    return '0 B';
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB'] as const;
  const k = 1024;
  if (bytes < k) {
    return `${bytes} B`;
  }
  let value = bytes;
  let unitIndex = 0;
  while (value >= k && unitIndex < units.length - 1) {
    value /= k;
    unitIndex++;
  }
  return `${value.toFixed(2)} ${units[unitIndex]}`;
}

/**
 * Compact a number into a short representation (1000 -> 1k, 1500 -> 1.5k, 1000000 -> 1M).
 */
export function formatNumber(value: number): string {
  if (!Number.isFinite(value)) {
    return '0';
  }
  const abs = Math.abs(value);
  if (abs < 1000) {
    return String(value);
  }
  if (abs < 1_000_000) {
    const result = value / 1000;
    return `${trimNumber(result)}k`;
  }
  const result = value / 1_000_000;
  return `${trimNumber(result)}M`;
}

function trimNumber(value: number): string {
  const rounded = Math.round(value * 10) / 10;
  if (Number.isInteger(rounded)) {
    return String(rounded);
  }
  return rounded.toFixed(1);
}

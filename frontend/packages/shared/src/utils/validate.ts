const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
// Chinese mainland mobile numbers: 1[3-9] followed by 9 digits.
const PHONE_REGEX = /^1[3-9]\d{9}$/;
const URL_REGEX = /^https?:\/\/[^\s]+$/i;

/**
 * Check whether the given value is a valid email address.
 */
export function isEmail(value: string): boolean {
  return typeof value === 'string' && EMAIL_REGEX.test(value);
}

/**
 * Check whether the given value is a valid Chinese mobile phone number.
 */
export function isPhone(value: string): boolean {
  return typeof value === 'string' && PHONE_REGEX.test(value);
}

/**
 * Check whether the given password is strong:
 * at least 8 chars, with upper, lower, and a digit.
 */
export function isStrongPassword(value: string): boolean {
  if (typeof value !== 'string' || value.length < 8) {
    return false;
  }
  const hasUpper = /[A-Z]/.test(value);
  const hasLower = /[a-z]/.test(value);
  const hasDigit = /\d/.test(value);
  return hasUpper && hasLower && hasDigit;
}

/**
 * Check whether the given value is a valid http(s) URL.
 */
export function isUrl(value: string): boolean {
  return typeof value === 'string' && URL_REGEX.test(value);
}

/**
 * Check whether a value is empty: null, undefined, empty string, empty array.
 */
export function isEmpty(value: unknown): boolean {
  if (value === null || value === undefined) {
    return true;
  }
  if (typeof value === 'string') {
    return value.trim().length === 0;
  }
  if (Array.isArray(value)) {
    return value.length === 0;
  }
  return false;
}

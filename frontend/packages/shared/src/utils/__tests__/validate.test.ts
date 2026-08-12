import { describe, it, expect } from 'vitest';
import {
  isEmail,
  isPhone,
  isStrongPassword,
  isUrl,
  isEmpty,
} from '../validate';

describe('isEmail', () => {
  it('returns true for valid emails', () => {
    expect(isEmail('user@example.com')).toBe(true);
  });

  it('returns false for invalid emails', () => {
    expect(isEmail('not-an-email')).toBe(false);
    expect(isEmail('a@')).toBe(false);
    expect(isEmail('@b.com')).toBe(false);
  });
});

describe('isPhone', () => {
  it('returns true for valid Chinese mobile numbers', () => {
    expect(isPhone('13812345678')).toBe(true);
    expect(isPhone('15900001111')).toBe(true);
  });

  it('returns false for invalid phone numbers', () => {
    expect(isPhone('12345678901')).toBe(false);
    expect(isPhone('1381234567')).toBe(false);
    expect(isPhone('abc')).toBe(false);
  });
});

describe('isStrongPassword', () => {
  it('returns true for strong passwords', () => {
    expect(isStrongPassword('Abcdef12')).toBe(true);
  });

  it('returns false for too-short passwords', () => {
    expect(isStrongPassword('Ab1')).toBe(false);
  });

  it('returns false when missing uppercase', () => {
    expect(isStrongPassword('abcdef12')).toBe(false);
  });

  it('returns false when missing digit', () => {
    expect(isStrongPassword('Abcdefgh')).toBe(false);
  });
});

describe('isUrl', () => {
  it('returns true for valid http(s) URLs', () => {
    expect(isUrl('http://example.com')).toBe(true);
    expect(isUrl('https://example.com/path')).toBe(true);
  });

  it('returns false for non-URLs', () => {
    expect(isUrl('not a url')).toBe(false);
    expect(isUrl('ftp://example.com')).toBe(false);
  });
});

describe('isEmpty', () => {
  it('returns true for null and undefined', () => {
    expect(isEmpty(null)).toBe(true);
    expect(isEmpty(undefined)).toBe(true);
  });

  it('returns true for empty string', () => {
    expect(isEmpty('')).toBe(true);
    expect(isEmpty('   ')).toBe(true);
  });

  it('returns true for empty array', () => {
    expect(isEmpty([])).toBe(true);
  });

  it('returns false for non-empty values', () => {
    expect(isEmpty('a')).toBe(false);
    expect(isEmpty([1])).toBe(false);
    expect(isEmpty(0)).toBe(false);
  });
});

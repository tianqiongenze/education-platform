import { describe, it, expect } from 'vitest';
import {
  loginFormSchema,
  courseSearchFormSchema,
  profileEditFormSchema,
} from '../form';

describe('loginFormSchema', () => {
  it('accepts valid login input', () => {
    const result = loginFormSchema.safeParse({
      username: 'alice',
      password: 'password123',
    });
    expect(result.success).toBe(true);
  });

  it('rejects username shorter than 3 chars', () => {
    const result = loginFormSchema.safeParse({
      username: 'ab',
      password: 'password123',
    });
    expect(result.success).toBe(false);
  });

  it('rejects password shorter than 8 chars', () => {
    const result = loginFormSchema.safeParse({
      username: 'alice',
      password: 'short',
    });
    expect(result.success).toBe(false);
  });
});

describe('courseSearchFormSchema', () => {
  it('accepts valid search with defaults', () => {
    const result = courseSearchFormSchema.safeParse({});
    expect(result.success).toBe(true);
  });

  it('accepts keyword and tags', () => {
    const result = courseSearchFormSchema.safeParse({
      keyword: 'math',
      tags: ['science', 'math'],
    });
    expect(result.success).toBe(true);
  });

  it('rejects invalid status', () => {
    const result = courseSearchFormSchema.safeParse({ status: 'invalid' });
    expect(result.success).toBe(false);
  });

  it('rejects page size over 100', () => {
    const result = courseSearchFormSchema.safeParse({ pageSize: 200 });
    expect(result.success).toBe(false);
  });
});

describe('profileEditFormSchema', () => {
  it('accepts a valid profile', () => {
    const result = profileEditFormSchema.safeParse({
      nickname: 'Alice',
      avatar: 'https://example.com/a.png',
      bio: 'hello',
      email: 'alice@example.com',
      phone: '13812345678',
    });
    expect(result.success).toBe(true);
  });

  it('accepts empty avatar and empty email', () => {
    const result = profileEditFormSchema.safeParse({
      nickname: 'Alice',
      avatar: '',
      email: '',
    });
    expect(result.success).toBe(true);
  });

  it('rejects invalid email', () => {
    const result = profileEditFormSchema.safeParse({
      nickname: 'Alice',
      avatar: '',
      email: 'not-email',
    });
    expect(result.success).toBe(false);
  });

  it('rejects invalid phone', () => {
    const result = profileEditFormSchema.safeParse({
      nickname: 'Alice',
      avatar: '',
      phone: '12345',
    });
    expect(result.success).toBe(false);
  });

  it('rejects empty nickname', () => {
    const result = profileEditFormSchema.safeParse({
      nickname: '',
      avatar: '',
    });
    expect(result.success).toBe(false);
  });
});

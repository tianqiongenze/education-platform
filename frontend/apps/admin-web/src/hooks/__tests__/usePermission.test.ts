import { describe, it, expect } from 'vitest';
import { hasRole, getRequiredRole } from '../usePermission';
import type { UserRole } from '@ep/shared';

describe('hasRole', () => {
  it('returns true when user role meets required role', () => {
    expect(hasRole('admin' as UserRole, 'teacher' as UserRole)).toBe(true);
    expect(hasRole('super_admin' as UserRole, 'admin' as UserRole)).toBe(true);
    expect(hasRole('teacher' as UserRole, 'teacher' as UserRole)).toBe(true);
  });

  it('returns false when user role is insufficient', () => {
    expect(hasRole('student' as UserRole, 'teacher' as UserRole)).toBe(false);
    expect(hasRole('teacher' as UserRole, 'admin' as UserRole)).toBe(false);
    expect(hasRole('admin' as UserRole, 'super_admin' as UserRole)).toBe(false);
  });
});

describe('getRequiredRole', () => {
  it('returns super_admin for settings paths', () => {
    expect(getRequiredRole('/settings')).toBe('super_admin');
    expect(getRequiredRole('/settings/general')).toBe('super_admin');
  });

  it('returns admin for teachers/students paths', () => {
    expect(getRequiredRole('/teachers')).toBe('admin');
    expect(getRequiredRole('/students')).toBe('admin');
  });

  it('returns teacher for other paths', () => {
    expect(getRequiredRole('/')).toBe('teacher');
    expect(getRequiredRole('/dashboard')).toBe('teacher');
    expect(getRequiredRole('/courses')).toBe('teacher');
  });
});

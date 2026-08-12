import type { UserRole } from '@ep/shared';

const ROLE_HIERARCHY: Record<UserRole, number> = {
  student: 1,
  teacher: 2,
  admin: 3,
  super_admin: 4,
};

export function hasRole(userRole: UserRole, requiredRole: UserRole): boolean {
  return ROLE_HIERARCHY[userRole] >= ROLE_HIERARCHY[requiredRole];
}

export function getRequiredRole(pathname: string): UserRole | null {
  if (pathname.startsWith('/settings')) return 'super_admin';
  if (pathname.startsWith('/teachers') || pathname.startsWith('/students')) return 'admin';
  if (pathname.startsWith('/')) return 'teacher';
  return null;
}

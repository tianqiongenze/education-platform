import { Navigate, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useUserStore } from '../stores/UserContext';
import { getRequiredRole, hasRole } from '../hooks/usePermission';

export function AuthGuard({ children }: { children: ReactNode }) {
  const { user } = useUserStore();
  const location = useLocation();

  if (!user) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  const requiredRole = getRequiredRole(location.pathname);
  if (requiredRole && !hasRole(user.role, requiredRole)) {
    return <Navigate to="/403" replace />;
  }

  return <>{children}</>;
}

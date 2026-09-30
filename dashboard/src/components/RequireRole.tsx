import type { ReactNode } from 'react';
import { Link, Navigate, useLocation } from 'react-router-dom';
import type { Role } from '../api/types';
import { useAuth } from '../context/AuthContext';
import { hasRole } from '../lib/roles';
import { paths } from '../lib/routes';
import { EmptyState, PageLoading } from './States';
import { RoleBadge } from './Badge';

/** Redirects anonymous visitors to /login (remembering where they were going). */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const location = useLocation();
  if (status === 'loading') return <PageLoading label="Checking your session" />;
  if (status === 'anonymous') return <Navigate to={paths.login()} replace state={{ from: location.pathname + location.search }} />;
  return <>{children}</>;
}

interface RequireRoleProps {
  min: Role;
  /** What this page is, for the friendly message ("the audit log"). */
  feature: string;
  children: ReactNode;
}

/**
 * Client-side route guard. It only improves the experience — the backend remains the real
 * enforcement and answers 403 regardless of what the UI shows.
 */
export function RequireRole({ min, feature, children }: RequireRoleProps) {
  const { user } = useAuth();
  if (hasRole(user?.role, min)) return <>{children}</>;
  return (
    <div className="page">
      <EmptyState title={`${feature} requires the ${min} role`} icon="lock">
        You are signed in {user ? <>as <strong>{user.username}</strong> </> : null}
        {user ? <RoleBadge role={user.role} /> : null}. Ask an {min} to make the change, or sign in with a different role.
        <span className="empty__inline-action">
          <Link to={paths.overview()} className="btn btn--secondary btn--sm">
            Back to overview
          </Link>
        </span>
      </EmptyState>
    </div>
  );
}

/** Renders `children` only when the current user has at least `min`. */
export function IfRole({ min, children }: { min: Role; children: ReactNode }) {
  const { user } = useAuth();
  return hasRole(user?.role, min) ? <>{children}</> : null;
}

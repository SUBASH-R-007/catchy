import type { PolicyChangeRequest, Role } from '../api/types';

const ROLE_RANK: Record<Role, number> = { VIEWER: 0, ENGINEER: 1, ADMIN: 2 };

/** True when `role` is at least `min` (ADMIN > ENGINEER > VIEWER). */
export function hasRole(role: Role | null | undefined, min: Role): boolean {
  if (!role) return false;
  return ROLE_RANK[role] >= ROLE_RANK[min];
}

export interface DecisionPermission {
  allowed: boolean;
  /** Why the action is disabled (shown next to the button). */
  reason?: string;
}

/**
 * Client-side mirror of the backend rule: ENGINEER+ may approve/reject a PENDING request,
 * but only ADMIN may decide their own request. The backend remains the real enforcement.
 */
export function canDecideRequest(
  user: { username: string; role: Role } | null | undefined,
  request: Pick<PolicyChangeRequest, 'status' | 'requestedBy'>,
): DecisionPermission {
  if (!user || !hasRole(user.role, 'ENGINEER')) {
    return { allowed: false, reason: 'Requires the ENGINEER role.' };
  }
  if (request.status !== 'PENDING') {
    return { allowed: false, reason: 'Only PENDING requests can be decided.' };
  }
  if (user.role !== 'ADMIN' && request.requestedBy === user.username) {
    return {
      allowed: false,
      reason: 'You requested this change. A different engineer (or an ADMIN) must decide it.',
    };
  }
  return { allowed: true };
}

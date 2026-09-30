import type { ReactNode } from 'react';
import type {
  AlertSeverity,
  AuditOutcome,
  CacheAction,
  CacheHealthStatus,
  CacheRiskLevel,
  EventSeverity,
  EvictionPolicy,
  PolicyRequestStatus,
  Role,
} from '../api/types';
import { actionTone } from '../lib/events';
import { HEALTH_PRESENTATION, RISK_TONE, type Tone } from '../lib/health';
import { humanizeEnum } from '../lib/format';
import { Icon, type IconName } from './Icon';

interface BadgeProps {
  tone?: Tone;
  icon?: IconName;
  title?: string;
  className?: string;
  children: ReactNode;
}

/** Status pill. Color is never the only signal: every badge carries text and, for status, an icon. */
export function Badge({ tone = 'neutral', icon, title, className, children }: BadgeProps) {
  return (
    <span className={`badge badge--${tone}${className ? ` ${className}` : ''}`} title={title}>
      {icon ? <Icon name={icon} size={13} className="badge__icon" /> : null}
      <span className="badge__text">{children}</span>
    </span>
  );
}

export function HealthBadge({ status }: { status: CacheHealthStatus }) {
  const p = HEALTH_PRESENTATION[status];
  return (
    <Badge tone={p.tone} icon={p.icon} title={`Cache health: ${p.label}`}>
      {p.label}
    </Badge>
  );
}

const RISK_ICON: Record<CacheRiskLevel, IconName> = {
  LOW: 'shield',
  MEDIUM: 'shield',
  HIGH: 'alert',
  CRITICAL: 'x-circle',
};

export function RiskBadge({ level }: { level: CacheRiskLevel }) {
  return (
    <Badge tone={RISK_TONE[level]} icon={RISK_ICON[level]} title={`Healthcare risk level: ${level}`}>
      <span className="sr-only">Risk level </span>
      {level}
    </Badge>
  );
}

export function PolicyBadge({ policy }: { policy: EvictionPolicy | 'MIXED' | 'NONE' }) {
  const dot = policy === 'LRU' ? 'policy-dot policy-dot--lru' : policy === 'LFU' ? 'policy-dot policy-dot--lfu' : 'policy-dot';
  return (
    <span className="badge badge--neutral badge--policy" title={policy === 'MIXED' ? 'Regions use different policies' : `Eviction policy ${policy}`}>
      <span className={dot} aria-hidden="true" />
      <span className="badge__text">
        <span className="sr-only">Policy </span>
        {policy}
      </span>
    </span>
  );
}

export function ActionBadge({ action }: { action: CacheAction }) {
  return (
    <Badge tone={actionTone(action)} className="badge--action">
      <span className="mono">{action}</span>
    </Badge>
  );
}

const SEVERITY_TONE: Record<EventSeverity | AlertSeverity, Tone> = {
  INFO: 'info',
  WARN: 'warning',
  WARNING: 'warning',
  CRITICAL: 'critical',
};
const SEVERITY_ICON: Record<EventSeverity | AlertSeverity, IconName> = {
  INFO: 'info',
  WARN: 'alert',
  WARNING: 'alert',
  CRITICAL: 'x-circle',
};

export function SeverityBadge({ severity }: { severity: EventSeverity | AlertSeverity }) {
  return (
    <Badge tone={SEVERITY_TONE[severity]} icon={SEVERITY_ICON[severity]} title={`Severity: ${severity}`}>
      {humanizeEnum(severity)}
    </Badge>
  );
}

const OUTCOME_PRESENTATION: Record<AuditOutcome, { tone: Tone; icon: IconName }> = {
  SUCCESS: { tone: 'good', icon: 'check' },
  DENIED: { tone: 'warning', icon: 'lock' },
  FAILURE: { tone: 'critical', icon: 'x-circle' },
};

export function OutcomeBadge({ outcome }: { outcome: AuditOutcome }) {
  const p = OUTCOME_PRESENTATION[outcome];
  return (
    <Badge tone={p.tone} icon={p.icon}>
      {humanizeEnum(outcome)}
    </Badge>
  );
}

const ROLE_TONE: Record<Role, Tone> = { ADMIN: 'serious', ENGINEER: 'info', VIEWER: 'neutral' };

export function RoleBadge({ role }: { role: Role }) {
  return (
    <Badge tone={ROLE_TONE[role]} icon="shield" title={`Signed in with the ${role} role`}>
      {role}
    </Badge>
  );
}

const REQUEST_STATUS: Record<PolicyRequestStatus, { tone: Tone; icon: IconName }> = {
  PENDING: { tone: 'warning', icon: 'clock' },
  APPROVED: { tone: 'info', icon: 'check' },
  APPLIED: { tone: 'good', icon: 'check' },
  REJECTED: { tone: 'critical', icon: 'x-circle' },
};

export function RequestStatusBadge({ status }: { status: PolicyRequestStatus }) {
  const p = REQUEST_STATUS[status];
  return (
    <Badge tone={p.tone} icon={p.icon}>
      {status}
    </Badge>
  );
}

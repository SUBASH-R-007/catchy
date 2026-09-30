import type { CacheHealthStatus, CacheRiskLevel, EvictionPolicy } from '../api/types';

/** Memory-utilization thresholds (percent) used by every meter in the dashboard. */
export const MEMORY_THRESHOLDS = { warning: 80, serious: 90, critical: 98 } as const;

export type MeterSeverity = 'ok' | 'warning' | 'serious' | 'critical';

export function memorySeverity(percent: number | null | undefined): MeterSeverity {
  if (typeof percent !== 'number' || !Number.isFinite(percent)) return 'ok';
  if (percent >= MEMORY_THRESHOLDS.critical) return 'critical';
  if (percent >= MEMORY_THRESHOLDS.serious) return 'serious';
  if (percent >= MEMORY_THRESHOLDS.warning) return 'warning';
  return 'ok';
}

export const METER_SEVERITY_LABEL: Record<MeterSeverity, string> = {
  ok: 'Within limits',
  warning: 'Getting full (80%+)',
  serious: 'High (90%+)',
  critical: 'Critical (98%+)',
};

export type Tone = 'neutral' | 'info' | 'good' | 'warning' | 'serious' | 'critical';

/** Worst first. UNKNOWN sorts above GOOD so "no data" is never hidden behind a healthy region. */
const HEALTH_RANK: Record<CacheHealthStatus, number> = {
  CRITICAL: 4,
  WARNING: 3,
  UNKNOWN: 2,
  GOOD: 1,
  EXCELLENT: 0,
};

export function healthRank(status: CacheHealthStatus): number {
  return HEALTH_RANK[status] ?? 0;
}

export function worstHealth(statuses: CacheHealthStatus[]): CacheHealthStatus {
  if (statuses.length === 0) return 'UNKNOWN';
  return statuses.reduce((worst, s) => (healthRank(s) > healthRank(worst) ? s : worst));
}

export interface HealthPresentation {
  label: string;
  tone: Tone;
  icon: 'star' | 'check' | 'alert' | 'x-circle' | 'help';
}

export const HEALTH_PRESENTATION: Record<CacheHealthStatus, HealthPresentation> = {
  EXCELLENT: { label: 'Excellent', tone: 'good', icon: 'star' },
  GOOD: { label: 'Good', tone: 'good', icon: 'check' },
  WARNING: { label: 'Warning', tone: 'warning', icon: 'alert' },
  CRITICAL: { label: 'Critical', tone: 'critical', icon: 'x-circle' },
  UNKNOWN: { label: 'Unknown', tone: 'neutral', icon: 'help' },
};

/** Risk ordering used by the "Highest risk level" sort: CRITICAL > HIGH > MEDIUM > LOW. */
export const RISK_RANK: Record<CacheRiskLevel, number> = {
  LOW: 0,
  MEDIUM: 1,
  HIGH: 2,
  CRITICAL: 3,
};

export function riskRank(level: CacheRiskLevel): number {
  return RISK_RANK[level] ?? 0;
}

export const RISK_TONE: Record<CacheRiskLevel, Tone> = {
  LOW: 'neutral',
  MEDIUM: 'info',
  HIGH: 'serious',
  CRITICAL: 'critical',
};

/** HIGH / CRITICAL regions show the healthcare-safety note. */
export function isHighRisk(level: CacheRiskLevel): boolean {
  return level === 'HIGH' || level === 'CRITICAL';
}

export const HEALTHCARE_SAFETY_NOTE =
  'Cache may support preliminary display only; source validation is mandatory before final action.';

export function otherPolicy(policy: EvictionPolicy): EvictionPolicy {
  return policy === 'LRU' ? 'LFU' : 'LRU';
}

/** Confidence meter band (0..100). */
export function confidenceBand(confidence: number): 'low' | 'medium' | 'high' {
  if (confidence >= 80) return 'high';
  if (confidence >= 50) return 'medium';
  return 'low';
}

/** Hit-rate tone vs. the 65% / 85% targets quoted by the backend's health reasons. */
export function hitRateTone(hitRate: number, requests: number): Tone {
  if (requests <= 0) return 'neutral';
  if (hitRate >= 85) return 'good';
  if (hitRate >= 65) return 'info';
  if (hitRate >= 40) return 'warning';
  return 'critical';
}

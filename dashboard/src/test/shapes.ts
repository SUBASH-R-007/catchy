/**
 * Lightweight runtime shape checks. TypeScript types vanish at runtime, so each schema below is
 * declared as a `Record<keyof T, Spec>` — the compiler forces it to stay in sync with the
 * interfaces in src/api/types.ts, and `checkShape` verifies real payloads against it.
 */
import type {
  Alert,
  ApiKey,
  ApiKeyCreated,
  Application,
  ApplicationSummary,
  AuditLogEntry,
  CacheEvent,
  Health,
  MetricTotals,
  PolicyChangeRequest,
  Project,
  Recommendation,
  RecentWindow,
  RegionConfig,
  RegionMetrics,
  ShadowStats,
  SimulationResult,
  Timeline,
  TimelinePoint,
} from '../api/types';
import {
  ALERT_SEVERITIES,
  AUDIT_ACTIONS,
  AUDIT_OUTCOMES,
  CACHE_ACTIONS,
  EVENT_SEVERITIES,
  EVICTION_POLICIES,
  HEALTH_STATUSES,
  RECOMMENDATION_ACTIONS,
  REQUEST_STATUSES,
  RISK_LEVELS,
  SIMULATION_KINDS,
} from '../api/types';

export type Spec =
  | 'number'
  | 'string'
  | 'boolean'
  | 'iso'
  | { oneOf: readonly string[] }
  | { array: Spec }
  | { optional: Spec }
  | { shape: Record<string, Spec> };

const ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/;

export function checkShape(value: unknown, spec: Spec, path = '$'): string[] {
  if (typeof spec === 'string') {
    if (spec === 'number') return typeof value === 'number' && Number.isFinite(value) ? [] : [`${path}: expected number, got ${typeof value}`];
    if (spec === 'string') return typeof value === 'string' ? [] : [`${path}: expected string, got ${typeof value}`];
    if (spec === 'boolean') return typeof value === 'boolean' ? [] : [`${path}: expected boolean, got ${typeof value}`];
    return typeof value === 'string' && ISO.test(value) ? [] : [`${path}: expected ISO-8601 UTC timestamp, got ${String(value)}`];
  }
  if ('oneOf' in spec) {
    return typeof value === 'string' && spec.oneOf.includes(value) ? [] : [`${path}: ${String(value)} not in [${spec.oneOf.join(', ')}]`];
  }
  if ('optional' in spec) {
    return value === null || value === undefined ? [] : checkShape(value, spec.optional, path);
  }
  if ('array' in spec) {
    if (!Array.isArray(value)) return [`${path}: expected array`];
    return value.flatMap((item, i) => checkShape(item, spec.array, `${path}[${i}]`));
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return [`${path}: expected object`];
  const record = value as Record<string, unknown>;
  return Object.entries(spec.shape).flatMap(([key, sub]) => checkShape(record[key], sub, `${path}.${key}`));
}

const n = 'number' as const;
const s = 'string' as const;
const b = 'boolean' as const;
const iso = 'iso' as const;
const enumOf = (values: readonly string[]): Spec => ({ oneOf: values });
const opt = (spec: Spec): Spec => ({ optional: spec });

export const METRIC_TOTALS_SHAPE: Record<keyof MetricTotals, Spec> = {
  hits: n, misses: n, hitRate: n, missRate: n, puts: n, removes: n, clears: n, evictions: n, expirations: n,
  size: n, capacity: n, estimatedMemoryUsageBytes: n, maximumMemoryBytes: n, memoryUtilizationPercent: n,
  lruEvictions: n, lfuEvictions: n, evictionsDueToEntryLimit: n, evictionsDueToMemoryLimit: n,
  averageGetLatencyMs: n, averagePutLatencyMs: n, sourceCallsAvoided: n, telemetryEventsSent: n,
  telemetryEventsFailed: n, l1Hits: n, victimHits: n, sourceMisses: n, victimEvictions: n, overallHitRate: n,
  refreshesStarted: n, concurrentRequestsCoalesced: n, sourceCallsAvoidedByStampedeShield: n,
  refreshFailures: n, staleServed: n, staleCorrections: n,
};

export const HEALTH_SHAPE: Record<keyof Health, Spec> = {
  status: enumOf(HEALTH_STATUSES),
  score: n,
  reasons: { array: s },
};

const RECENT_WINDOW_SHAPE: Record<keyof RecentWindow, Spec> = {
  minutes: n, hits: n, misses: n, puts: n, evictions: n, expirations: n,
};

const SHADOW_SHAPE: Record<keyof ShadowStats, Spec> = {
  requests: n, windowRequests: n, lruHitRate: n, lfuHitRate: n, topKeyConcentrationPercent: n,
};

const REGION_ONLY_SHAPE: Record<Exclude<keyof RegionMetrics, keyof MetricTotals>, Spec> = {
  applicationId: n,
  applicationName: s,
  environment: s,
  cacheRegion: s,
  riskLevel: enumOf(RISK_LEVELS),
  activePolicy: enumOf(EVICTION_POLICIES),
  defaultTtlMs: n,
  victimEnabled: b,
  victimSize: n,
  victimCapacity: n,
  recentWindow: opt({ shape: RECENT_WINDOW_SHAPE }),
  shadow: opt({ shape: SHADOW_SHAPE }),
  health: { shape: HEALTH_SHAPE },
  instanceCount: n,
  lastUpdated: iso,
};

export const REGION_METRICS_SPEC: Spec = { shape: { ...METRIC_TOTALS_SHAPE, ...REGION_ONLY_SHAPE } };

export const APPLICATION_SUMMARY_SHAPE: Record<keyof ApplicationSummary, Spec> = {
  applicationId: n,
  projectId: n,
  projectName: s,
  name: s,
  displayName: s,
  environment: s,
  regionCount: n,
  totals: { shape: METRIC_TOTALS_SHAPE },
  health: { shape: HEALTH_SHAPE },
  activePolicies: { array: enumOf(EVICTION_POLICIES) },
  policyLabel: enumOf(['LRU', 'LFU', 'MIXED', 'NONE']),
  recommendationSummary: s,
  lastTelemetryAt: opt(iso),
  secondsSinceLastTelemetry: opt(n),
};

export const APPLICATION_SUMMARY_SPEC: Spec = { shape: APPLICATION_SUMMARY_SHAPE };

const ALERT_SHAPE: Record<keyof Alert, Spec> = {
  id: s, severity: enumOf(ALERT_SEVERITIES), applicationId: n, applicationName: s, cacheRegion: s, message: s, since: iso,
};
export const ALERT_SPEC: Spec = { shape: ALERT_SHAPE };

const EVENT_SHAPE: Record<keyof CacheEvent, Spec> = {
  id: n, timestamp: iso, applicationName: s, environment: s, cacheRegion: s,
  action: enumOf(CACHE_ACTIONS),
  keyFingerprint: opt(s), reason: opt(s), policy: enumOf(EVICTION_POLICIES),
  frequency: opt(n), lastAccessAgeMs: opt(n), remainingTtlMs: opt(n), estimatedEntrySizeBytes: opt(n),
  cacheSizeBefore: opt(n), cacheSizeAfter: opt(n), memoryBeforeBytes: opt(n), memoryAfterBytes: opt(n),
  valueReturned: opt(b), severity: enumOf(EVENT_SEVERITIES), latencyMs: opt(n),
};
export const EVENT_SPEC: Spec = { shape: EVENT_SHAPE };

const RECOMMENDATION_SHAPE: Record<keyof Recommendation, Spec> = {
  id: n, applicationId: n, applicationName: s, cacheRegion: s,
  currentPolicy: enumOf(EVICTION_POLICIES), recommendedPolicy: enumOf(EVICTION_POLICIES),
  action: enumOf(RECOMMENDATION_ACTIONS), summary: s,
  lruShadowHitRate: n, lfuShadowHitRate: n, improvementPercent: n, confidence: n,
  reason: s, aiExplanation: opt(s), sampleSize: n, minimumSampleMet: b, cooldownActive: b,
  cooldownEndsAt: opt(iso), approvalRequired: b, createdAt: iso, pendingRequestId: opt(n),
};
export const RECOMMENDATION_SPEC: Spec = { shape: RECOMMENDATION_SHAPE };

const REQUEST_SHAPE: Record<keyof PolicyChangeRequest, Spec> = {
  id: n, applicationId: n, applicationName: s, cacheRegion: s,
  currentPolicy: enumOf(EVICTION_POLICIES), requestedPolicy: enumOf(EVICTION_POLICIES),
  reason: s, status: enumOf(REQUEST_STATUSES), requestedBy: s,
  decidedBy: opt(s), decisionNote: opt(s), createdAt: iso, decidedAt: opt(iso), appliedAt: opt(iso),
  recommendationId: opt(n),
};
export const POLICY_REQUEST_SPEC: Spec = { shape: REQUEST_SHAPE };

const POINT_SHAPE: Record<keyof TimelinePoint, Spec> = {
  bucketStart: iso, hits: n, misses: n, puts: n, evictions: n, expirations: n, hitRate: n,
};
const TIMELINE_SHAPE: Record<keyof Timeline, Spec> = {
  cacheRegion: opt(s), bucketSeconds: n, points: { array: { shape: POINT_SHAPE } },
};
export const TIMELINE_SPEC: Spec = { shape: TIMELINE_SHAPE };

const PROJECT_SHAPE: Record<keyof Project, Spec> = {
  id: n, name: s, description: opt(s), createdAt: iso, applicationCount: n,
};
export const PROJECT_SPEC: Spec = { shape: PROJECT_SHAPE };

const APPLICATION_SHAPE: Record<keyof Application, Spec> = {
  id: n, projectId: n, projectName: s, name: s, displayName: s, environment: s,
  description: opt(s), createdAt: iso, lastTelemetryAt: opt(iso), regionCount: n,
};
export const APPLICATION_SPEC: Spec = { shape: APPLICATION_SHAPE };

const API_KEY_SHAPE: Record<keyof ApiKey, Spec> = {
  id: n, applicationId: n, label: s, keyPrefix: s, maskedKey: s, createdAt: iso,
  lastUsedAt: opt(iso), revokedAt: opt(iso), active: b,
};
export const API_KEY_SPEC: Spec = { shape: API_KEY_SHAPE };

const API_KEY_CREATED_SHAPE: Record<keyof ApiKeyCreated, Spec> = {
  id: n, applicationId: n, label: s, keyPrefix: s, maskedKey: s, apiKey: s, createdAt: iso,
};
export const API_KEY_CREATED_SPEC: Spec = { shape: API_KEY_CREATED_SHAPE };

const CONFIG_SHAPE: Record<keyof RegionConfig, Spec> = {
  cacheRegion: s,
  riskLevel: enumOf(RISK_LEVELS),
  reported: { shape: { maximumEntries: opt(n), maximumMemoryBytes: opt(n), defaultTtlMs: opt(n), activePolicy: opt(enumOf(EVICTION_POLICIES)) } },
  desired: opt({ shape: { maximumEntries: opt(n), maximumMemoryBytes: opt(n), defaultTtlMs: opt(n) } }),
  pending: b,
  tuningVersion: opt(n),
  updatedBy: opt(s),
  updatedAt: opt(iso),
};
export const REGION_CONFIG_SPEC: Spec = { shape: CONFIG_SHAPE };

const AUDIT_SHAPE: Record<keyof AuditLogEntry, Spec> = {
  id: n, timestamp: iso, actor: s, actorRole: opt(enumOf(['VIEWER', 'ENGINEER', 'ADMIN', 'SYSTEM'])),
  action: enumOf(AUDIT_ACTIONS), targetType: opt(s), targetId: opt(s), applicationId: opt(n),
  details: opt(s), outcome: enumOf(AUDIT_OUTCOMES),
};
export const AUDIT_SPEC: Spec = { shape: AUDIT_SHAPE };

const SIMULATION_SHAPE: Record<keyof SimulationResult, Spec> = {
  simulationId: s, kind: enumOf(SIMULATION_KINDS), applicationId: n, cacheRegions: { array: s },
  startedAt: iso, durationMs: n, summary: s,
  steps: { array: { shape: { name: s, description: s, detail: s } } },
  hits: n, misses: n, hitRate: n, evictions: n, expirations: n,
  comparison: opt({ array: { shape: { pattern: s, lruHitRate: n, lfuHitRate: n, winner: s, interpretation: s } } }),
};
export const SIMULATION_SPEC: Spec = { shape: SIMULATION_SHAPE };

export const OVERVIEW_SPEC: Spec = {
  shape: {
    generatedAt: iso,
    projectsMonitored: n,
    applicationsMonitored: n,
    cacheRegionsMonitored: n,
    totals: { shape: METRIC_TOTALS_SHAPE },
    activeAlerts: { array: ALERT_SPEC },
    latestRecommendations: { array: RECOMMENDATION_SPEC },
    applications: { array: APPLICATION_SUMMARY_SPEC },
    regions: { array: REGION_METRICS_SPEC },
  },
};

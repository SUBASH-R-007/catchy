/**
 * TypeScript model of the CATCHY Telemetry Service API contract (docs/api.md).
 * JSON property names are camelCase; timestamps are ISO-8601 UTC strings.
 * Optional fields may be `null` or absent — clients treat both the same.
 */

// ---------------------------------------------------------------------------
// Enums (as runtime arrays + union types so they can be iterated and validated)
// ---------------------------------------------------------------------------

export const ROLES = ['VIEWER', 'ENGINEER', 'ADMIN'] as const;
export type Role = (typeof ROLES)[number];

export const EVICTION_POLICIES = ['LRU', 'LFU'] as const;
export type EvictionPolicy = (typeof EVICTION_POLICIES)[number];

export const RISK_LEVELS = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const;
export type CacheRiskLevel = (typeof RISK_LEVELS)[number];

export const HEALTH_STATUSES = ['EXCELLENT', 'GOOD', 'WARNING', 'CRITICAL', 'UNKNOWN'] as const;
export type CacheHealthStatus = (typeof HEALTH_STATUSES)[number];

export const EVENT_SEVERITIES = ['INFO', 'WARN', 'CRITICAL'] as const;
export type EventSeverity = (typeof EVENT_SEVERITIES)[number];

export const CACHE_ACTIONS = [
  'HIT',
  'MISS',
  'PUT',
  'REMOVE',
  'CLEAR',
  'EXPIRED',
  'EVICTED',
  'MEMORY_EVICTED',
  'ENTRY_LIMIT_EVICTED',
  'POLICY_CHANGED',
  'CLEANUP',
  'POLICY_RECOMMENDATION',
  'VICTIM_HIT',
  'STALE_SERVED',
  'REFRESH_STARTED',
  'REFRESH_COALESCED',
  'REFRESH_FAILED',
  'SOURCE_VALIDATED',
  'CONFIG_CHANGED',
] as const;
export type CacheAction = (typeof CACHE_ACTIONS)[number];

export const ALERT_SEVERITIES = ['INFO', 'WARNING', 'CRITICAL'] as const;
export type AlertSeverity = (typeof ALERT_SEVERITIES)[number];

export const RECOMMENDATION_ACTIONS = ['SWITCH', 'KEEP'] as const;
export type RecommendationAction = (typeof RECOMMENDATION_ACTIONS)[number];

export const REQUEST_STATUSES = ['PENDING', 'APPROVED', 'APPLIED', 'REJECTED'] as const;
export type PolicyRequestStatus = (typeof REQUEST_STATUSES)[number];

export const AUDIT_OUTCOMES = ['SUCCESS', 'DENIED', 'FAILURE'] as const;
export type AuditOutcome = (typeof AUDIT_OUTCOMES)[number];

export const AUDIT_ACTIONS = [
  'LOGIN_SUCCESS',
  'LOGIN_FAILURE',
  'PROJECT_CREATED',
  'APPLICATION_CREATED',
  'API_KEY_CREATED',
  'API_KEY_REVOKED',
  'POLICY_CHANGE_REQUESTED',
  'POLICY_CHANGE_APPROVED',
  'POLICY_CHANGE_REJECTED',
  'POLICY_CHANGE_APPLIED',
  'CONFIG_CHANGE_REQUESTED',
  'CONFIG_CHANGE_APPLIED',
  'RECOMMENDATION_EVALUATED',
  'SIMULATION_RUN',
  'TELEMETRY_REJECTED',
  'ACCESS_DENIED',
] as const;
export type AuditAction = (typeof AUDIT_ACTIONS)[number];

export const SIMULATION_KINDS = [
  'sample-workload',
  'high-load',
  'ttl-expiration',
  'policy-comparison',
] as const;
export type SimulationKind = (typeof SIMULATION_KINDS)[number];

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

export interface ApiErrorBody {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
  details?: string[] | null;
}

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------

export interface LoginRequest {
  username: string;
  password: string;
}

export interface DemoLoginRequest {
  role: Role;
}

export interface LoginResponse {
  token: string;
  username: string;
  role: Role;
  expiresAt: string;
}

export interface CurrentUser {
  username: string;
  role: Role;
}

export interface HealthCheckResponse {
  status: string;
  service: string;
  version: string;
  demoMode: boolean;
}

// ---------------------------------------------------------------------------
// Shared metric types
// ---------------------------------------------------------------------------

export interface Health {
  status: CacheHealthStatus;
  score: number;
  reasons: string[];
}

export interface MetricTotals {
  hits: number;
  misses: number;
  hitRate: number;
  missRate: number;
  puts: number;
  removes: number;
  clears: number;
  evictions: number;
  expirations: number;
  size: number;
  capacity: number;
  estimatedMemoryUsageBytes: number;
  maximumMemoryBytes: number;
  memoryUtilizationPercent: number;
  lruEvictions: number;
  lfuEvictions: number;
  evictionsDueToEntryLimit: number;
  evictionsDueToMemoryLimit: number;
  averageGetLatencyMs: number;
  averagePutLatencyMs: number;
  sourceCallsAvoided: number;
  telemetryEventsSent: number;
  telemetryEventsFailed: number;
  l1Hits: number;
  victimHits: number;
  sourceMisses: number;
  victimEvictions: number;
  overallHitRate: number;
  refreshesStarted: number;
  concurrentRequestsCoalesced: number;
  sourceCallsAvoidedByStampedeShield: number;
  refreshFailures: number;
  staleServed: number;
  staleCorrections: number;
}

export interface RecentWindow {
  minutes: number;
  hits: number;
  misses: number;
  puts: number;
  evictions: number;
  expirations: number;
}

export interface ShadowStats {
  requests: number;
  windowRequests: number;
  lruHitRate: number;
  lfuHitRate: number;
  topKeyConcentrationPercent: number;
}

export interface RegionMetrics extends MetricTotals {
  applicationId: number;
  applicationName: string;
  environment: string;
  cacheRegion: string;
  riskLevel: CacheRiskLevel;
  activePolicy: EvictionPolicy;
  defaultTtlMs: number;
  victimEnabled: boolean;
  victimSize: number;
  victimCapacity: number;
  recentWindow?: RecentWindow | null;
  shadow?: ShadowStats | null;
  health: Health;
  instanceCount: number;
  lastUpdated: string;
}

export interface ApplicationSummary {
  applicationId: number;
  projectId: number;
  projectName: string;
  name: string;
  displayName: string;
  environment: string;
  regionCount: number;
  totals: MetricTotals;
  health: Health;
  activePolicies: EvictionPolicy[];
  /** The policy when all regions agree, "MIXED" otherwise, "NONE" when nothing has reported. */
  policyLabel: EvictionPolicy | 'MIXED' | 'NONE';
  recommendationSummary: string;
  lastTelemetryAt?: string | null;
  secondsSinceLastTelemetry?: number | null;
}

export interface ApplicationMetrics extends ApplicationSummary {
  regions: RegionMetrics[];
}

export interface Alert {
  id: string;
  severity: AlertSeverity;
  applicationId: number;
  applicationName: string;
  cacheRegion: string;
  message: string;
  since: string;
}

export interface CacheEvent {
  id: number;
  timestamp: string;
  applicationName: string;
  environment: string;
  cacheRegion: string;
  action: CacheAction;
  /** `null` in category-only mode. Raw keys and values never appear anywhere. */
  keyFingerprint?: string | null;
  reason?: string | null;
  policy: EvictionPolicy;
  frequency?: number | null;
  lastAccessAgeMs?: number | null;
  remainingTtlMs?: number | null;
  estimatedEntrySizeBytes?: number | null;
  cacheSizeBefore?: number | null;
  cacheSizeAfter?: number | null;
  memoryBeforeBytes?: number | null;
  memoryAfterBytes?: number | null;
  valueReturned?: boolean | null;
  severity: EventSeverity;
  latencyMs?: number | null;
}

export interface Recommendation {
  id: number;
  applicationId: number;
  applicationName: string;
  cacheRegion: string;
  currentPolicy: EvictionPolicy;
  recommendedPolicy: EvictionPolicy;
  action: RecommendationAction;
  summary: string;
  lruShadowHitRate: number;
  lfuShadowHitRate: number;
  improvementPercent: number;
  confidence: number;
  reason: string;
  aiExplanation?: string | null;
  sampleSize: number;
  minimumSampleMet: boolean;
  cooldownActive: boolean;
  cooldownEndsAt?: string | null;
  approvalRequired: boolean;
  createdAt: string;
  pendingRequestId?: number | null;
}

export interface PolicyChangeRequest {
  id: number;
  applicationId: number;
  applicationName: string;
  cacheRegion: string;
  currentPolicy: EvictionPolicy;
  requestedPolicy: EvictionPolicy;
  reason: string;
  status: PolicyRequestStatus;
  requestedBy: string;
  decidedBy?: string | null;
  decisionNote?: string | null;
  createdAt: string;
  decidedAt?: string | null;
  appliedAt?: string | null;
  recommendationId?: number | null;
}

export interface CreatePolicyChangeRequest {
  cacheRegion: string;
  requestedPolicy: EvictionPolicy;
  reason?: string;
  recommendationId?: number | null;
}

export interface DecisionRequest {
  note?: string;
}

export interface TimelinePoint {
  bucketStart: string;
  hits: number;
  misses: number;
  puts: number;
  evictions: number;
  expirations: number;
  hitRate: number;
}

export interface Timeline {
  /** `null` for application-level / global timelines. */
  cacheRegion?: string | null;
  bucketSeconds: number;
  points: TimelinePoint[];
}

export interface TimelineQuery {
  minutes?: number;
  bucketSeconds?: number;
}

// ---------------------------------------------------------------------------
// Projects / applications / API keys
// ---------------------------------------------------------------------------

export interface Project {
  id: number;
  name: string;
  description?: string | null;
  createdAt: string;
  applicationCount: number;
}

export interface CreateProjectRequest {
  name: string;
  description?: string;
}

export interface ProjectMetrics {
  projectId: number;
  projectName: string;
  applicationCount: number;
  regionCount: number;
  totals: MetricTotals;
  health: Health;
  applications: ApplicationSummary[];
}

export interface Application {
  id: number;
  projectId: number;
  projectName: string;
  name: string;
  displayName: string;
  environment: string;
  description?: string | null;
  createdAt: string;
  lastTelemetryAt?: string | null;
  regionCount: number;
}

export interface CreateApplicationRequest {
  name: string;
  displayName?: string;
  environment: string;
  description?: string;
}

export interface ApiKey {
  id: number;
  applicationId: number;
  label: string;
  keyPrefix: string;
  maskedKey: string;
  createdAt: string;
  lastUsedAt?: string | null;
  revokedAt?: string | null;
  active: boolean;
}

export interface ApiKeyCreated {
  id: number;
  applicationId: number;
  label: string;
  keyPrefix: string;
  maskedKey: string;
  /** Plaintext key — returned once only. */
  apiKey: string;
  createdAt: string;
}

export interface CreateApiKeyRequest {
  label: string;
}

// ---------------------------------------------------------------------------
// Overview
// ---------------------------------------------------------------------------

export interface Overview {
  generatedAt: string;
  projectsMonitored: number;
  applicationsMonitored: number;
  cacheRegionsMonitored: number;
  totals: MetricTotals;
  activeAlerts: Alert[];
  latestRecommendations: Recommendation[];
  applications: ApplicationSummary[];
  regions: RegionMetrics[];
}

// ---------------------------------------------------------------------------
// Region configuration
// ---------------------------------------------------------------------------

export interface ReportedConfig {
  maximumEntries?: number | null;
  maximumMemoryBytes?: number | null;
  defaultTtlMs?: number | null;
  activePolicy?: EvictionPolicy | null;
}

export interface DesiredConfig {
  maximumEntries?: number | null;
  maximumMemoryBytes?: number | null;
  defaultTtlMs?: number | null;
}

export interface RegionConfig {
  cacheRegion: string;
  riskLevel: CacheRiskLevel;
  reported: ReportedConfig;
  /** `null` if never overridden. */
  desired?: DesiredConfig | null;
  pending: boolean;
  tuningVersion?: number | null;
  updatedBy?: string | null;
  updatedAt?: string | null;
}

export interface UpdateRegionConfigRequest {
  maximumEntries?: number;
  maximumMemoryBytes?: number;
  defaultTtlMs?: number;
  reason?: string;
}

/** Bounds from the contract; shared by the form validation and the mock. */
export const CONFIG_BOUNDS = {
  maximumEntries: { min: 1, max: 10_000_000 },
  maximumMemoryBytes: { min: 1024 },
  defaultTtlMs: { min: 1000 },
} as const;

// ---------------------------------------------------------------------------
// Audit log
// ---------------------------------------------------------------------------

export interface AuditLogEntry {
  id: number;
  timestamp: string;
  actor: string;
  /** `SYSTEM` for actions the service performs itself (e.g. a policy change confirmed by an SDK snapshot). */
  actorRole?: Role | 'SYSTEM' | null;
  action: AuditAction;
  targetType?: string | null;
  targetId?: string | null;
  applicationId?: number | null;
  details?: string | null;
  outcome: AuditOutcome;
}

export interface AuditLogQuery {
  limit?: number;
  action?: AuditAction | '';
  applicationId?: number | null;
}

// ---------------------------------------------------------------------------
// Simulation
// ---------------------------------------------------------------------------

export interface SimulationStep {
  name: string;
  description: string;
  detail: string;
}

export interface SimulationComparisonRow {
  pattern: string;
  lruHitRate: number;
  lfuHitRate: number;
  winner: EvictionPolicy | 'TIE' | string;
  interpretation: string;
}

export interface SimulationResult {
  simulationId: string;
  kind: SimulationKind;
  applicationId: number;
  cacheRegions: string[];
  startedAt: string;
  durationMs: number;
  summary: string;
  steps: SimulationStep[];
  hits: number;
  misses: number;
  hitRate: number;
  evictions: number;
  expirations: number;
  /** `null` except for `policy-comparison`. */
  comparison?: SimulationComparisonRow[] | null;
}

export interface SimulationRequest {
  requests?: number;
}

export const SIMULATION_REQUEST_BOUNDS = { min: 50, max: 20_000 } as const;

// ---------------------------------------------------------------------------
// Event query
// ---------------------------------------------------------------------------

export interface EventQuery {
  limit?: number;
  actions?: CacheAction[];
  since?: string;
}

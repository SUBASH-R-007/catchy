/**
 * Metrics stream payload, schema v2. Mirrors docs/api/metrics.schema.json exactly (SPEC 8.3);
 * keep both in sync.
 */

export type PolicyType = 'LRU' | 'LFU' | 'LFU_DECAY';

export const POLICY_TYPES: readonly PolicyType[] = ['LRU', 'LFU', 'LFU_DECAY'];

export type RemovalCause = 'EXPLICIT' | 'REPLACED' | 'EVICTED' | 'EXPIRED';

export const REMOVAL_CAUSES: readonly RemovalCause[] = [
  'EXPLICIT',
  'REPLACED',
  'EVICTED',
  'EXPIRED',
];

export type Pattern =
  | 'UNIFORM'
  | 'ZIPF'
  | 'SCAN_POLLUTION'
  | 'LOOP'
  | 'SHIFTING_HOTSPOT'
  | 'TTL_BURST'
  | 'FORMULARY'
  | 'PROVIDER_DIRECTORY';

export const PATTERNS: readonly Pattern[] = [
  'UNIFORM',
  'ZIPF',
  'SCAN_POLLUTION',
  'LOOP',
  'SHIFTING_HOTSPOT',
  'TTL_BURST',
  'FORMULARY',
  'PROVIDER_DIRECTORY',
];

export interface CacheMetrics {
  name: string;
  group: string;
  policy: PolicyType;
  size: number;
  capacity: number;
  hits: number;
  misses: number;
  /** Cumulative hit rate, 0-1. */
  hitRate: number;
  /** Hit rate over the last 20 ticks (10 s), 0-1. */
  hitRateWindow10s: number;
  evictions: number;
  expirations: number;
  opsPerSec: number;
  getP50Micros: number;
  getP99Micros: number;
  /** Estimate: equals hits. */
  dbCallsAvoided: number;
  /** Estimate: hits x mean simulated DB latency. */
  latencySavedMs: number;
  /** Estimate: dbCallsAvoided / 1000 x cost per 1000 calls. */
  estCostSaved: number;
}

export interface AdvisorRecommendation {
  current: PolicyType;
  recommended: PolicyType;
  expectedGainPts: number;
  windowSec: number;
}

export interface GroupMetrics {
  name: string;
  caches: string[];
  optimalHitRate: number | null;
  advisor: AdvisorRecommendation | null;
}

export interface SimulationStatus {
  id: string;
  running: boolean;
  group: string;
  pattern: Pattern;
  /** Guided demo act (1-4), or null for a free-running simulation. */
  act: number | null;
  phaseIndex: number;
  phaseCount: number;
  phaseCaption: string | null;
  phaseStartedTs: number;
}

export interface RemovalEvent {
  ts: number;
  cache: string;
  key: string;
  cause: RemovalCause;
}

export interface MetricsSnapshot {
  /** Server time of the tick, epoch ms. */
  ts: number;
  caches: CacheMetrics[];
  groups: GroupMetrics[];
  simulation: SimulationStatus | null;
  /** Removals since the previous tick, at most 50. */
  events: RemovalEvent[];
}

/** Connection state of the metrics stream, shown by ConnectionLed. */
export type ConnectionState = 'connecting' | 'live' | 'reconnecting' | 'offline';

/** RFC 7807 problem detail returned by the server on errors. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
}

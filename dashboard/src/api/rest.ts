import type { Pattern, PolicyType } from './types';

/**
 * REST shapes for the /api/caches, /api/simulations and /api/stress endpoints. Mirrors the schemas in docs/api/openapi.yaml exactly
 * (SPEC 8.2); keep both in sync.
 */

/** A cache's configuration, as returned by the server (every field present). */
export interface CacheConfig {
  name: string;
  policy: PolicyType;
  capacity: number;
  /** null: entries never expire unless a put sets its own TTL. */
  defaultTtlMs: number | null;
  concurrencyLevel: number;
  group: string;
}

/**
 * POST /api/caches body. Omitted fields take the server defaults: policy LRU, concurrencyLevel 1,
 * group "playground", defaultTtlMs null.
 */
export interface CreateCacheRequest {
  name: string;
  capacity: number;
  policy?: PolicyType;
  defaultTtlMs?: number | null;
  concurrencyLevel?: number;
  group?: string;
}

/** CacheConfig plus live stats (since creation or the last reset-stats). */
export interface CacheInfo extends CacheConfig {
  size: number;
  hits: number;
  misses: number;
  hitRate: number;
  missRate: number;
  evictions: number;
  expirations: number;
  puts: number;
}

/** One live entry, in policy order. */
export interface EntryView {
  key: string;
  /** 0 under LRU. */
  frequency: number;
  /** null when the entry never expires. */
  ttlRemainingMs: number | null;
}

/** GET /api/caches/{name}/entries/{key}: a normal lookup (counts toward hits/misses). */
export interface GetResult {
  hit: boolean;
  value: string | null;
  ttlRemainingMs: number | null;
}

/** PUT /api/caches/{name}/entries/{key} body; ttlMs overrides the cache's default TTL. */
export interface PutRequest {
  value: string;
  ttlMs?: number | null;
}

/** DELETE /api/caches/{name}/entries/{key} response. */
export interface DeleteResult {
  removed: boolean;
}

/** The policy's internal order, for "Inside the cache". */
export interface PolicySnapshot {
  type: PolicyType;
  entries: { key: string; frequency: number }[];
}

/** POST /api/simulations body (SPEC 8.2, 9.2). Omitted fields take the server defaults. */
export interface SimulationRequest {
  /** An existing group, e.g. "demo". */
  group: string;
  pattern: Pattern;
  /** 0-200,000; 0 = unthrottled. Server default 5,000. */
  opsPerSec?: number;
  /** Share of reads, 0-1. Server default 0.9. */
  readRatio?: number;
  /** 1-3,600. Server default 60. */
  durationSec?: number;
  /** Server default 42. */
  seed?: number;
  /** Pattern overrides, e.g. keySpace, zipfS (SPEC 9.1 defaults). */
  params?: Record<string, unknown>;
}

/** POST /api/simulations response. */
export interface SimulationStarted {
  id: string;
}

/** The two cache implementations the stress harness can drive. */
export type StressImpl = 'SINGLE_LOCK' | 'SEGMENTED';

/** POST /api/stress body. Omitted fields take the server defaults. */
export interface StressConfig {
  impl?: StressImpl;
  /** 1-64, default 32. */
  threads?: number;
  /** 100-10,000, default 5,000. */
  durationMs?: number;
  keySpace?: number;
  readRatio?: number;
  capacity?: number;
  policy?: PolicyType;
  seed?: number;
}

/** One invariant check of a stress run. */
export interface StressInvariant {
  name: string;
  passed: boolean;
  detail: string;
}

/** POST /api/stress response. */
export interface StressReport {
  impl: StressImpl;
  threads: number;
  /** Measured run time. */
  durationMs: number;
  totalOps: number;
  opsPerSec: number;
  /** Always five, in order: Size bound, Accounting, No phantom values, Structure intact, No exceptions. */
  invariants: StressInvariant[];
  /** At most 10 exception summaries. */
  exceptions: string[];
  deadlockFree: boolean;
}

/** POST /api/stress/stampede body. */
export interface StampedeRequest {
  /** 1-500, default 200. */
  threads?: number;
  /** 0-2,000, default 200. */
  loaderDelayMs?: number;
}

/** POST /api/stress/stampede response. */
export interface StampedeResult {
  threads: number;
  /** Expected: 1. */
  loaderCalls: number;
  allSameValue: boolean;
  durationMs: number;
}

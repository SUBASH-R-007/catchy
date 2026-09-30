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

// ---------------------------------------------------------------------------------------------
// Step 4 · advisor and trace replay (SPEC 6.3, 8.2, 9.5). Owned by the Policy Race / Trace
// Replay pages; other Step 4 shapes live in their own blocks.
// ---------------------------------------------------------------------------------------------

/** POST /api/groups/{group}/advisor/apply response (409 ProblemDetail when nothing to apply). */
export interface AdvisorApplyResult {
  switchedTo: PolicyType;
}

/** POST /api/traces (multipart field "file") and POST /api/traces/sample response. */
export interface TraceUploaded {
  traceId: string;
  rows: number;
}

/** POST /api/traces/{id}/replay body. */
export interface ReplayRequest {
  capacity: number;
  policies: PolicyType[];
}

/** One policy's offline replay result. */
export interface PolicyReplayResult {
  policy: PolicyType;
  /** 0-1. */
  hitRate: number;
  hits: number;
  misses: number;
  evictions: number;
}

/** POST /api/traces/{id}/replay response. */
export interface ReplayResult {
  traceId: string;
  rows: number;
  capacity: number;
  results: PolicyReplayResult[];
  /** Bélády (MIN) hit rate over the same trace and capacity, 0-1. */
  optimalHitRate: number;
  durationMs: number;
}

// ---- Benchmarks and guided demo (SPEC 5 cache-bench, 9.4) ----------------------------------

/** The four implementations the JMH benchmarks compare. */
export type BenchImpl = 'single' | 'segmented' | 'syncLinkedHashMap' | 'caffeine';

/** JMH workloads: 90 % reads, 50/50, 90 % writes. */
export type BenchWorkload = 'read90' | 'mixed50' | 'write90';

/** One JMH measurement. */
export interface BenchResult {
  impl: BenchImpl;
  workload: BenchWorkload;
  /** 1, 4, 16 or 32. */
  threads: number;
  opsPerSec: number;
  /** JMH error margin as a percentage of the score. */
  errorPct: number;
}

/** GET /api/bench: exported JMH results, or the shipped sample file when `sample` is true. */
export interface BenchResults {
  sample: boolean;
  machine: { cpu: string; cores: number; os: string; jvm: string };
  /** e.g. "1 fork, 3 warmup + 5 measurement iterations". */
  settings?: string;
  results: BenchResult[];
}

/** One timed phase of a guided demo act. */
export interface DemoPhase {
  pattern: string;
  durationSec: number;
  caption: string;
}

/** POST /api/demo/acts/{n}/start response. Act 4 has no phases and no group. */
export interface DemoAct {
  act: number;
  title: string;
  group: string | null;
  phases: DemoPhase[];
}

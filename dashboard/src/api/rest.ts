import type { PolicyType } from './types';

/**
 * REST shapes for the /api/caches endpoints. Mirrors the schemas in docs/api/openapi.yaml exactly
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

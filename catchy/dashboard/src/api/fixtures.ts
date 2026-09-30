import type { CacheMetrics, MetricsSnapshot } from './types';

/** Test and story data shaped exactly like the SPEC 8.3 example payload. */

export function cacheMetrics(overrides: Partial<CacheMetrics> = {}): CacheMetrics {
  return {
    name: 'lru-A',
    group: 'demo',
    policy: 'LRU',
    size: 1000,
    capacity: 1000,
    hits: 812344,
    misses: 190211,
    hitRate: 0.81,
    hitRateWindow10s: 0.774,
    evictions: 180102,
    expirations: 9120,
    opsPerSec: 5012,
    getP50Micros: 0.8,
    getP99Micros: 6.4,
    dbCallsAvoided: 812344,
    latencySavedMs: 9748128,
    estCostSaved: 40.62,
    ...overrides,
  };
}

export function snapshot(overrides: Partial<MetricsSnapshot> = {}): MetricsSnapshot {
  return {
    ts: 1790000000500,
    caches: [
      cacheMetrics(),
      cacheMetrics({ name: 'lfu-A', policy: 'LFU', hitRate: 0.84, hitRateWindow10s: 0.86 }),
    ],
    groups: [
      {
        name: 'demo',
        caches: ['lru-A', 'lfu-A'],
        optimalHitRate: 0.861,
        advisor: { current: 'LRU', recommended: 'LFU', expectedGainPts: 7.4, windowSec: 30 },
      },
    ],
    simulation: {
      id: 's-12',
      running: true,
      group: 'demo',
      pattern: 'SCAN_POLLUTION',
      act: 1,
      phaseIndex: 1,
      phaseCount: 3,
      phaseCaption: "A one-off scan floods the cache — watch LRU's line",
      phaseStartedTs: 1790000000000,
    },
    events: [{ ts: 1790000000400, cache: 'lru-A', key: 'drug:4411', cause: 'EVICTED' }],
    ...overrides,
  };
}

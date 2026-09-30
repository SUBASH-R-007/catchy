import type { StreamHistory } from '../../api/streamReducer';
import type { CacheMetrics, PolicyType } from '../../api/types';

export interface Kpis {
  name: string;
  policy: PolicyType;
  hitRate10s: number;
  missRate10s: number;
  hitRateCumulative: number;
  opsPerSec: number;
  size: number;
  capacity: number;
  evictions: number;
  evictionsPerSec: number;
  expirations: number;
  expirationsPerSec: number;
  getP50Micros: number;
  getP99Micros: number;
}

function find(caches: readonly CacheMetrics[], name: string): CacheMetrics | undefined {
  return caches.find((c) => c.name === name);
}

/** Per-second rate of a monotonic counter between the last two snapshots (0 after a reset). */
function perSecond(
  history: StreamHistory,
  name: string,
  pick: (c: CacheMetrics) => number,
): number {
  const n = history.snapshots.length;
  const last = history.snapshots[n - 1];
  const prev = history.snapshots[n - 2];
  if (!last || !prev) return 0;
  const a = find(prev.caches, name);
  const b = find(last.caches, name);
  const seconds = (last.ts - prev.ts) / 1000;
  if (!a || !b || seconds <= 0) return 0;
  return Math.max(0, (pick(b) - pick(a)) / seconds);
}

/** KPI tile values for one cache, or null if the cache is not in the latest snapshot. */
export function kpisFor(history: StreamHistory, name: string): Kpis | null {
  const c = history.latest ? find(history.latest.caches, name) : undefined;
  if (!c) return null;
  return {
    name: c.name,
    policy: c.policy,
    hitRate10s: c.hitRateWindow10s,
    missRate10s: 1 - c.hitRateWindow10s,
    hitRateCumulative: c.hitRate,
    opsPerSec: c.opsPerSec,
    size: c.size,
    capacity: c.capacity,
    evictions: c.evictions,
    evictionsPerSec: perSecond(history, name, (x) => x.evictions),
    expirations: c.expirations,
    expirationsPerSec: perSecond(history, name, (x) => x.expirations),
    getP50Micros: c.getP50Micros,
    getP99Micros: c.getP99Micros,
  };
}

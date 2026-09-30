import type { StreamHistory } from '../../api/streamReducer';
import type { CacheMetrics, PolicyType } from '../../api/types';

/** The "removals by cause" chart averages over this much of the stream history. */
export const REMOVAL_WINDOW_MS = 10_000;

export interface RemovalRate {
  name: string;
  policy: PolicyType;
  evictionsPerSec: number;
  expirationsPerSec: number;
}

/**
 * Evictions/s and expirations/s per cache (caches of the latest snapshot, in server order),
 * averaged over the last {@code windowMs} of history. Each rate is the sum of counter increases
 * between consecutive snapshots divided by the time those snapshots span. A decrease (a stats reset
 * or a re-created cache) counts as 0 for that step, so rates are never negative. Steps where the
 * cache is missing from either snapshot are skipped.
 */
export function removalRates(
  history: StreamHistory,
  windowMs: number = REMOVAL_WINDOW_MS,
): RemovalRate[] {
  const latest = history.latest;
  if (!latest) return [];
  const from = latest.ts - windowMs;
  const inWindow = history.snapshots.filter((s) => s.ts >= from);

  return latest.caches.map((cache) => {
    let evictions = 0;
    let expirations = 0;
    let elapsedMs = 0;
    for (let i = 1; i < inWindow.length; i++) {
      const prevSnap = inWindow[i - 1];
      const curSnap = inWindow[i];
      if (!prevSnap || !curSnap) continue;
      const a = find(prevSnap.caches, cache.name);
      const b = find(curSnap.caches, cache.name);
      const dt = curSnap.ts - prevSnap.ts;
      if (!a || !b || dt <= 0) continue;
      evictions += Math.max(0, b.evictions - a.evictions);
      expirations += Math.max(0, b.expirations - a.expirations);
      elapsedMs += dt;
    }
    const perSec = (n: number) => (elapsedMs > 0 ? (n * 1000) / elapsedMs : 0);
    return {
      name: cache.name,
      policy: cache.policy,
      evictionsPerSec: perSec(evictions),
      expirationsPerSec: perSec(expirations),
    };
  });
}

function find(caches: readonly CacheMetrics[], name: string): CacheMetrics | undefined {
  return caches.find((c) => c.name === name);
}

/** 0.25 → "0.3/s", 12.4 → "12/s", 5012 → "5K/s". */
export function formatPerSecond(rate: number): string {
  const v = Number.isFinite(rate) ? Math.max(0, rate) : 0;
  if (v === 0) return '0/s';
  if (v < 10) return `${v.toFixed(1)}/s`;
  if (v < 1000) return `${Math.round(v)}/s`;
  return `${(v / 1000).toFixed(v < 10_000 ? 1 : 0)}K/s`;
}

/** Plain-language summary of the removal rates, for screen readers and the chart caption. */
export function removalSummary(rates: readonly RemovalRate[]): string {
  if (rates.length === 0) return 'No caches to report.';
  const active = rates.filter((r) => r.evictionsPerSec > 0 || r.expirationsPerSec > 0);
  if (active.length === 0) {
    return 'No cache evicted or expired anything in the last 10 seconds.';
  }
  const parts = rates.map(
    (r) =>
      `${r.name}: ${formatPerSecond(r.evictionsPerSec)} evictions and ${formatPerSecond(r.expirationsPerSec)} expirations.`,
  );
  const top = [...rates].sort(
    (x, y) => y.evictionsPerSec + y.expirationsPerSec - (x.evictionsPerSec + x.expirationsPerSec),
  )[0];
  const cause =
    top && top.evictionsPerSec >= top.expirationsPerSec
      ? 'mostly evictions (it is full and must make room)'
      : 'mostly expirations (TTLs running out)';
  const lead = top ? ` ${top.name} removes the most entries, ${cause}.` : '';
  return `Average over the last 10 seconds. ${parts.join(' ')}${lead}`;
}

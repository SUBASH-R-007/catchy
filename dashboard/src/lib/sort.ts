import type { CacheRiskLevel, RegionMetrics } from '../api/types';
import { riskRank } from './health';

export type RegionSortKey =
  | 'hit-desc'
  | 'hit-asc'
  | 'memory-desc'
  | 'evictions-desc'
  | 'expirations-desc'
  | 'recent-desc'
  | 'avoided-desc'
  | 'risk-desc';

export const REGION_SORT_OPTIONS: ReadonlyArray<{ key: RegionSortKey; label: string }> = [
  { key: 'hit-desc', label: 'Highest hit rate' },
  { key: 'hit-asc', label: 'Lowest hit rate' },
  { key: 'memory-desc', label: 'Highest estimated memory' },
  { key: 'evictions-desc', label: 'Most evictions' },
  { key: 'expirations-desc', label: 'Most expirations' },
  { key: 'recent-desc', label: 'Most recently updated' },
  { key: 'avoided-desc', label: 'Highest source calls avoided' },
  { key: 'risk-desc', label: 'Highest risk level' },
];

function time(r: RegionMetrics): number {
  const t = Date.parse(r.lastUpdated);
  return Number.isFinite(t) ? t : 0;
}

/** Primary comparator (negative = `a` first). Ties are broken by application then region name. */
function primary(key: RegionSortKey, a: RegionMetrics, b: RegionMetrics): number {
  switch (key) {
    case 'hit-desc':
      return b.hitRate - a.hitRate;
    case 'hit-asc':
      return a.hitRate - b.hitRate;
    case 'memory-desc':
      return b.estimatedMemoryUsageBytes - a.estimatedMemoryUsageBytes;
    case 'evictions-desc':
      return b.evictions - a.evictions;
    case 'expirations-desc':
      return b.expirations - a.expirations;
    case 'recent-desc':
      return time(b) - time(a);
    case 'avoided-desc':
      return b.sourceCallsAvoided - a.sourceCallsAvoided;
    case 'risk-desc':
      return riskRank(b.riskLevel) - riskRank(a.riskLevel);
  }
}

/** Returns a new, stably sorted array; the input is never mutated. */
export function sortRegions(regions: readonly RegionMetrics[], key: RegionSortKey): RegionMetrics[] {
  return [...regions].sort((a, b) => {
    const p = primary(key, a, b);
    if (p !== 0) return p;
    const byApp = a.applicationName.localeCompare(b.applicationName);
    if (byApp !== 0) return byApp;
    return a.cacheRegion.localeCompare(b.cacheRegion);
  });
}

export interface RegionFilters {
  applicationId?: number | null;
  riskLevel?: CacheRiskLevel | '' | null;
  query?: string;
}

export function filterRegions(regions: readonly RegionMetrics[], filters: RegionFilters): RegionMetrics[] {
  const q = (filters.query ?? '').trim().toLowerCase();
  return regions.filter((r) => {
    if (filters.applicationId != null && r.applicationId !== filters.applicationId) return false;
    if (filters.riskLevel && r.riskLevel !== filters.riskLevel) return false;
    if (q && !r.cacheRegion.toLowerCase().includes(q)) return false;
    return true;
  });
}

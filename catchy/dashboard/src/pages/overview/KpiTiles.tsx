import { MetricTile } from '../../components';
import {
  formatCompact,
  formatInteger,
  formatMicros,
  formatPercent,
  formatRate,
} from '../../lib/format';
import type { Kpis } from './kpis';

/** The seven KPI tiles for the selected cache (SPEC 10.5, Overview). */
export function KpiTiles({ kpis }: { kpis: Kpis }) {
  const fill = kpis.capacity > 0 ? kpis.size / kpis.capacity : 0;
  return (
    <div className="grid grid-cols-2 gap-4 md:grid-cols-4 xl:grid-cols-7">
      <MetricTile
        label="Hit rate"
        value={formatPercent(kpis.hitRate10s)}
        sublabel={`All time ${formatPercent(kpis.hitRateCumulative)}`}
        accent="var(--good)"
        info="Share of lookups in the last 10 seconds that found the value in the cache, so the database was not called."
      />
      <MetricTile
        label="Miss rate"
        value={formatPercent(kpis.missRate10s)}
        sublabel="Last 10 s"
        accent="var(--warn)"
        info="Share of lookups in the last 10 seconds that did not find a live value and had to go to the database."
      />
      <MetricTile
        label="Ops/sec"
        value={formatRate(kpis.opsPerSec)}
        sublabel="Cache operations"
        info="Cache operations (gets and puts) handled per second, measured over the last half second."
      />
      <MetricTile
        label="Size"
        value={formatCompact(kpis.size)}
        sublabel={`of ${formatInteger(kpis.capacity)} · ${formatPercent(fill, 0)} full`}
        info="Entries held now against the maximum; a full cache must evict one entry for every new key."
      />
      <MetricTile
        label="Evictions"
        value={formatCompact(kpis.evictions)}
        sublabel={`${formatInteger(kpis.evictionsPerSec)}/s now`}
        info="Entries the eviction policy removed to make room for new keys, since start or the last stats reset."
      />
      <MetricTile
        label="Expirations"
        value={formatCompact(kpis.expirations)}
        sublabel={`${formatInteger(kpis.expirationsPerSec)}/s now`}
        info="Entries removed because their time-to-live ran out; this is independent of the eviction policy."
      />
      <MetricTile
        label="p99 get"
        value={formatMicros(kpis.getP99Micros)}
        sublabel={`p50 ${formatMicros(kpis.getP50Micros)}`}
        info="99% of cache lookups in the last 10 seconds completed faster than this."
      />
    </div>
  );
}

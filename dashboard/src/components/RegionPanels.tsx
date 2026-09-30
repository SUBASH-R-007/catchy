import type { CacheRiskLevel, MetricTotals, RegionMetrics } from '../api/types';
import { HBars, type BarGroup } from '../charts/HBars';
import { Meter } from '../charts/Meter';
import { formatBytes, formatCompact, formatDuration, formatInt, formatLatencyMs, formatPercent } from '../lib/format';
import { HEALTHCARE_SAFETY_NOTE, isHighRisk } from '../lib/health';
import { Icon } from './Icon';
import { Tile } from './Tile';

/** Metric tiles for a single region (hit rate, memory, latency, expirations, telemetry ...). */
export function RegionMetricTiles({ m }: { m: RegionMetrics }) {
  return (
    <div className="grid-kpi grid-kpi--4">
      <Tile label="Hit rate" value={formatPercent(m.hitRate)} sub={`${formatInt(m.hits)} hits`} />
      <Tile label="Miss rate" value={formatPercent(m.missRate)} sub={`${formatInt(m.misses)} misses`} />
      <Tile label="Entries / capacity" value={formatInt(m.size)} sub={`of ${formatInt(m.capacity)} entries`} />
      <Tile label="Memory" qualifier="estimated" value={formatBytes(m.estimatedMemoryUsageBytes)} sub={`of ${formatBytes(m.maximumMemoryBytes)} max`} />
      <Tile label="Memory utilization" qualifier="estimated" value={formatPercent(m.memoryUtilizationPercent)}>
        <Meter size="sm" percent={m.memoryUtilizationPercent} label="Estimated memory utilization" valueText="" showSeverity />
      </Tile>
      <Tile label="Source calls avoided" value={formatCompact(m.sourceCallsAvoided)} sub={`${formatInt(m.sourceCallsAvoided)} calls`} />
      <Tile label="Avg get latency" value={formatLatencyMs(m.averageGetLatencyMs)} />
      <Tile label="Avg put latency" value={formatLatencyMs(m.averagePutLatencyMs)} />
      <Tile label="Expirations" value={formatInt(m.expirations)} sub={m.recentWindow ? `${formatInt(m.recentWindow.expirations)} in last ${m.recentWindow.minutes} min` : undefined} />
      <Tile label="Evictions" value={formatInt(m.evictions)} sub={m.recentWindow ? `${formatInt(m.recentWindow.evictions)} in last ${m.recentWindow.minutes} min` : undefined} />
      <Tile
        label="Telemetry events"
        value={formatInt(m.telemetryEventsSent)}
        sub={`sent · ${formatInt(m.telemetryEventsFailed)} failed`}
        tone={m.telemetryEventsFailed > 0 ? 'warning' : undefined}
      />
      <Tile label="Default TTL" value={formatDuration(m.defaultTtlMs)} sub={`${m.instanceCount} SDK instance${m.instanceCount === 1 ? '' : 's'}`} />
    </div>
  );
}

/** Live (measured) hit rate next to the Policy Arena's simulated LRU and LFU hit rates. */
export function PolicyComparisonBars({ m }: { m: RegionMetrics }) {
  const s = m.shadow;
  if (!s) return <p className="chart-empty">The SDK has not reported shadow data for this region yet.</p>;
  const groups: BarGroup[] = [
    {
      rows: [
        {
          key: 'live',
          label: (
            <>
              Live hit rate <span className="faint">(measured, {m.activePolicy})</span>
            </>
          ),
          value: m.hitRate,
          color: 'var(--series-neutral)',
          valueLabel: formatPercent(m.hitRate, 1),
        },
        {
          key: 'lru',
          label: (
            <>
              LRU <span className="faint">(simulated{m.activePolicy === 'LRU' ? ', active' : ''})</span>
            </>
          ),
          value: s.lruHitRate,
          color: 'var(--series-lru)',
          valueLabel: formatPercent(s.lruHitRate, 1),
        },
        {
          key: 'lfu',
          label: (
            <>
              LFU <span className="faint">(simulated{m.activePolicy === 'LFU' ? ', active' : ''})</span>
            </>
          ),
          value: s.lfuHitRate,
          color: 'var(--series-lfu)',
          valueLabel: formatPercent(s.lfuHitRate, 1),
        },
      ],
    },
  ];
  return (
    <div className="stack-sm">
      <HBars
        groups={groups}
        percent
        ariaLabel={`Live hit rate ${formatPercent(m.hitRate, 1)}; simulated LRU ${formatPercent(s.lruHitRate, 1)}; simulated LFU ${formatPercent(s.lfuHitRate, 1)}`}
      />
      <p className="faint">
        Simulated over the most recent {formatInt(s.windowRequests)} reads; the top keys receive {formatPercent(s.topKeyConcentrationPercent, 1)} of requests. Shadow rates are estimates, not live measurements.
      </p>
    </div>
  );
}

/** Why entries left the cache: by limit, by policy, and by TTL. */
export function EvictionReasonBars({ m }: { m: MetricTotals }) {
  const groups: BarGroup[] = [
    {
      label: 'Evictions by limit',
      rows: [
        { key: 'entry', label: 'Entry limit', value: m.evictionsDueToEntryLimit, color: 'var(--series-evict)', valueLabel: formatInt(m.evictionsDueToEntryLimit) },
        { key: 'memory', label: 'Memory limit', value: m.evictionsDueToMemoryLimit, color: 'var(--series-evict)', valueLabel: formatInt(m.evictionsDueToMemoryLimit) },
      ],
    },
    {
      label: 'Evictions by policy',
      rows: [
        { key: 'lru', label: 'LRU evictions', value: m.lruEvictions, color: 'var(--series-evict)', valueLabel: formatInt(m.lruEvictions) },
        { key: 'lfu', label: 'LFU evictions', value: m.lfuEvictions, color: 'var(--series-evict)', valueLabel: formatInt(m.lfuEvictions) },
      ],
    },
    {
      label: 'Time-based removal',
      rows: [{ key: 'exp', label: 'Expirations (TTL)', value: m.expirations, color: 'var(--series-expire)', valueLabel: formatInt(m.expirations) }],
    },
  ];
  const any = m.evictions + m.expirations > 0;
  return any ? (
    <HBars
      groups={groups}
      ariaLabel={`Removals: ${formatInt(m.evictionsDueToEntryLimit)} entry-limit evictions, ${formatInt(m.evictionsDueToMemoryLimit)} memory-limit evictions, ${formatInt(m.lruEvictions)} LRU, ${formatInt(m.lfuEvictions)} LFU, ${formatInt(m.expirations)} expirations`}
    />
  ) : (
    <p className="chart-empty">No evictions or expirations recorded yet.</p>
  );
}

/** Expiration count with the recent window next to the all-time total. */
export function ExpirationPanel({ m }: { m: RegionMetrics }) {
  const w = m.recentWindow;
  return (
    <div className="stack-sm">
      <div className="grid-kpi grid-kpi--2">
        <Tile label="Expirations (total)" value={formatInt(m.expirations)} sub={`default TTL ${formatDuration(m.defaultTtlMs)}`} />
        <Tile label={w ? `Expirations (last ${w.minutes} min)` : 'Expirations (recent)'} value={w ? formatInt(w.expirations) : '—'} sub={w ? `${formatInt(w.evictions)} evictions, ${formatInt(w.puts)} puts` : 'No recent window reported'} />
      </div>
      {w ? (
        <HBars
          ariaLabel={`Removals in the last ${w.minutes} minutes`}
          groups={[
            {
              rows: [
                { key: 'e', label: 'Evictions', value: w.evictions, color: 'var(--series-evict)', valueLabel: formatInt(w.evictions) },
                { key: 'x', label: 'Expirations', value: w.expirations, color: 'var(--series-expire)', valueLabel: formatInt(w.expirations) },
              ],
            },
          ]}
        />
      ) : null}
    </div>
  );
}

/** Victim-cache layer: only meaningful when the region has it enabled. */
export function VictimPanel({ m }: { m: RegionMetrics }) {
  const pct = m.victimCapacity > 0 ? (m.victimSize / m.victimCapacity) * 100 : 0;
  return (
    <div className="stack-sm">
      <div className="grid-kpi grid-kpi--2">
        <Tile label="L1 hits" value={formatInt(m.l1Hits)} />
        <Tile label="Victim hits" value={formatInt(m.victimHits)} sub="rescued from the victim layer" />
        <Tile label="Source misses" value={formatInt(m.sourceMisses)} sub="went to the source of truth" />
        <Tile label="Overall hit rate" value={formatPercent(m.overallHitRate)} sub="(L1 + victim) / requests" />
      </div>
      <div>
        <p className="faint">
          Victim layer fill: {formatInt(m.victimSize)} of {formatInt(m.victimCapacity)} entries · {formatInt(m.victimEvictions)} overflow evictions
        </p>
        <Meter size="sm" percent={pct} label="Victim layer fill" valueText={formatPercent(pct, 0)} />
      </div>
    </div>
  );
}

/** Stampede shield: refreshes started, callers coalesced, calls avoided, failures. */
export function StampedePanel({ m }: { m: RegionMetrics }) {
  const active = m.refreshesStarted + m.concurrentRequestsCoalesced + m.refreshFailures > 0;
  return (
    <div className="stack-sm">
      <div className="grid-kpi grid-kpi--2">
        <Tile label="Refreshes started" value={formatInt(m.refreshesStarted)} />
        <Tile label="Requests coalesced" value={formatInt(m.concurrentRequestsCoalesced)} sub="waited for an in-flight refresh" />
        <Tile label="Source calls avoided" value={formatInt(m.sourceCallsAvoidedByStampedeShield)} sub="by the stampede shield" />
        <Tile label="Refresh failures" value={formatInt(m.refreshFailures)} tone={m.refreshFailures > 0 ? 'critical' : undefined} />
      </div>
      {!active ? <p className="faint">No stampede activity recorded for this region.</p> : null}
    </div>
  );
}

/** Healthcare-safety reminder for HIGH / CRITICAL regions. */
export function SafetyNote({ risk }: { risk: CacheRiskLevel }) {
  if (!isHighRisk(risk)) return null;
  return (
    <aside className={`safety-note safety-note--${risk.toLowerCase()}`} role="note" aria-label="Healthcare safety note">
      <Icon name="shield" size={18} />
      <div>
        <strong>{risk === 'CRITICAL' ? 'Critical-risk region' : 'High-risk region'}</strong>
        <p>{HEALTHCARE_SAFETY_NOTE}</p>
      </div>
    </aside>
  );
}

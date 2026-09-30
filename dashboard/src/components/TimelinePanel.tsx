import { useMemo, useState } from 'react';
import type { Timeline } from '../api/types';
import { ChartFrame, type ChartTableData, type LegendItem } from '../charts/ChartFrame';
import { TimelineChart, type TimelineSeries } from '../charts/TimelineChart';
import { formatClock, formatInt, formatPercent } from '../lib/format';
import { ErrorState, LoadingBlock } from './States';

export interface TimelineRange {
  minutes: number;
  bucketSeconds: number;
  label: string;
}

export const TIMELINE_RANGES: readonly TimelineRange[] = [
  { minutes: 5, bucketSeconds: 5, label: 'Last 5 min' },
  { minutes: 15, bucketSeconds: 10, label: 'Last 15 min' },
  { minutes: 60, bucketSeconds: 30, label: 'Last 60 min' },
];

export const DEFAULT_TIMELINE_RANGE = TIMELINE_RANGES[1] as TimelineRange;

/**
 * The newest bucket is still filling (only part of its interval has elapsed), so plotting it would show a
 * misleading dip. Hide it until it completes; fixtures and historical data are unaffected.
 */
export function completeBuckets<T extends { bucketStart: string }>(points: readonly T[], bucketSeconds: number, now: number = Date.now()): T[] {
  const last = points[points.length - 1];
  if (points.length > 2 && last && Date.parse(last.bucketStart) + bucketSeconds * 1000 > now) return points.slice(0, -1);
  return [...points];
}

type View = 'requests' | 'removals' | 'hitrate';

const VIEWS: ReadonlyArray<{ key: View; label: string }> = [
  { key: 'requests', label: 'Hits & misses' },
  { key: 'removals', label: 'Evictions & expirations' },
  { key: 'hitrate', label: 'Hit rate' },
];

interface TimelinePanelProps {
  title?: string;
  timeline: Timeline | undefined;
  loading: boolean;
  error: Error | null;
  refreshing?: boolean;
  onRetry: () => void;
  range: TimelineRange;
  onRangeChange: (range: TimelineRange) => void;
  /** Shown when there are no points at all. */
  emptyText?: string;
}

/**
 * Hits / misses timeline with a view toggle. Evictions and expirations live on their own axis in a
 * separate view instead of a dual-axis plot (their magnitude is far smaller than hit counts).
 */
export function TimelinePanel({
  title = 'Traffic timeline',
  timeline,
  loading,
  error,
  refreshing,
  onRetry,
  range,
  onRangeChange,
  emptyText,
}: TimelinePanelProps) {
  const [view, setView] = useState<View>('requests');

  const model = useMemo(() => {
    const points = timeline ? completeBuckets(timeline.points, timeline.bucketSeconds) : [];
    const labels = points.map((p) => p.bucketStart);
    const all: Record<View, { series: TimelineSeries[]; legend: LegendItem[]; table: ChartTableData; yMax?: number; yFormat?: (n: number) => string; area: boolean }> = {
      requests: {
        series: [
          { key: 'hits', label: 'Hits', color: 'var(--series-hit)', values: points.map((p) => p.hits) },
          { key: 'misses', label: 'Misses', color: 'var(--series-miss)', values: points.map((p) => p.misses) },
        ],
        legend: [
          { label: 'Hits', color: 'var(--series-hit)', kind: 'line' },
          { label: 'Misses', color: 'var(--series-miss)', kind: 'line' },
        ],
        table: {
          headers: ['Time', 'Hits', 'Misses', 'Puts', 'Hit rate'],
          rows: points.map((p) => [formatClock(p.bucketStart), formatInt(p.hits), formatInt(p.misses), formatInt(p.puts), formatPercent(p.hitRate)]),
        },
        area: true,
      },
      removals: {
        series: [
          { key: 'evictions', label: 'Evictions', color: 'var(--series-evict)', values: points.map((p) => p.evictions) },
          { key: 'expirations', label: 'Expirations', color: 'var(--series-expire)', values: points.map((p) => p.expirations) },
        ],
        legend: [
          { label: 'Evictions', color: 'var(--series-evict)', kind: 'line' },
          { label: 'Expirations', color: 'var(--series-expire)', kind: 'line' },
        ],
        table: {
          headers: ['Time', 'Evictions', 'Expirations'],
          rows: points.map((p) => [formatClock(p.bucketStart), formatInt(p.evictions), formatInt(p.expirations)]),
        },
        area: false,
      },
      hitrate: {
        series: [
          {
            key: 'hitRate',
            label: 'Hit rate',
            color: 'var(--series-hit)',
            values: points.map((p) => p.hitRate),
            format: (v) => formatPercent(v),
          },
        ],
        legend: [],
        table: {
          headers: ['Time', 'Hit rate'],
          rows: points.map((p) => [formatClock(p.bucketStart), formatPercent(p.hitRate)]),
        },
        yMax: 100,
        yFormat: (n) => `${Math.round(n)}%`,
        area: true,
      },
    };
    const totals = points.reduce(
      (t, p) => ({ hits: t.hits + p.hits, misses: t.misses + p.misses, evictions: t.evictions + p.evictions, expirations: t.expirations + p.expirations }),
      { hits: 0, misses: 0, evictions: 0, expirations: 0 },
    );
    return { labels, all, totals, count: points.length };
  }, [timeline]);

  const current = model.all[view];
  const summary =
    view === 'requests'
      ? `Hits and misses per ${timeline?.bucketSeconds ?? range.bucketSeconds} second bucket over the ${range.label.toLowerCase()}. Total ${formatInt(model.totals.hits)} hits and ${formatInt(model.totals.misses)} misses.`
      : view === 'removals'
        ? `Evictions and expirations per bucket over the ${range.label.toLowerCase()}. Total ${formatInt(model.totals.evictions)} evictions and ${formatInt(model.totals.expirations)} expirations.`
        : `Hit rate per bucket over the ${range.label.toLowerCase()}.`;

  const controls = (
    <div className="timeline-controls">
      <div className="segmented" role="group" aria-label="Timeline series">
        {VIEWS.map((v) => (
          <button key={v.key} type="button" className={`segmented__btn${view === v.key ? ' is-active' : ''}`} aria-pressed={view === v.key} onClick={() => setView(v.key)}>
            {v.label}
          </button>
        ))}
      </div>
      <label className="inline-select">
        <span className="sr-only">Time range</span>
        <select
          className="select select--sm"
          value={range.minutes}
          onChange={(e) => onRangeChange(TIMELINE_RANGES.find((r) => r.minutes === Number(e.target.value)) ?? DEFAULT_TIMELINE_RANGE)}
        >
          {TIMELINE_RANGES.map((r) => (
            <option key={r.minutes} value={r.minutes}>
              {r.label}
            </option>
          ))}
        </select>
      </label>
    </div>
  );

  if (error && !timeline) {
    return (
      <ChartFrame title={title} controls={controls}>
        <ErrorState error={error} onRetry={onRetry} title="Could not load the timeline" />
      </ChartFrame>
    );
  }
  if (loading || !timeline) {
    return (
      <ChartFrame title={title} controls={controls}>
        <LoadingBlock label="Loading timeline" lines={6} height={18} />
      </ChartFrame>
    );
  }

  return (
    <ChartFrame
      title={title}
      subtitle={`Per ${timeline.bucketSeconds} s bucket · ${range.label.toLowerCase()}`}
      summary={summary}
      legend={current.legend}
      table={current.table}
      controls={controls}
      dimmed={refreshing}
    >
      <TimelineChart
        labels={model.labels}
        series={current.series}
        yMax={current.yMax}
        yFormat={current.yFormat}
        area={current.area}
        ariaLabel={summary}
        emptyText={emptyText ?? 'No telemetry in this window yet. Start a demo service or run a simulation.'}
      />
    </ChartFrame>
  );
}

import type { CacheInfo } from '../../api/rest';
import type { HitRateRow, SeriesInfo, StreamHistory } from '../../api/streamReducer';
import type {
  AdvisorRecommendation,
  CacheMetrics,
  GroupMetrics,
  MetricsSnapshot,
  Pattern,
  RemovalEvent,
} from '../../api/types';

/** The group the server boots with (SPEC 8.1). */
export const DEFAULT_GROUP = 'demo';

/** The event log shows at most this many removals. */
export const EVENT_LOG_LIMIT = 50;

/** Fixed parameters of a Policy Race run (only the pattern and rate are user-controlled). */
export const RACE_READ_RATIO = 0.9;
export const RACE_DURATION_SEC = 300;
export const RACE_SEED = 42;

export const OPS_MIN = 500;
export const OPS_MAX = 20_000;
export const OPS_STEP = 500;
export const OPS_DEFAULT = 5000;

/** Distinct group names from the REST cache list and the live stream, sorted. */
export function groupNames(
  restCaches: readonly CacheInfo[] | undefined,
  streamCaches: readonly CacheMetrics[],
): string[] {
  const names = new Set<string>();
  for (const c of restCaches ?? []) names.add(c.group);
  for (const c of streamCaches) names.add(c.group);
  return [...names].sort((a, b) => a.localeCompare(b));
}

/**
 * The group to show: the requested one (e.g. from ?group=, even if it is not listed yet — the
 * guided demo may just have created it), else "demo" when it exists, else the first group.
 */
export function pickGroup(requested: string | null, groups: readonly string[]): string {
  if (requested) return requested;
  if (groups.includes(DEFAULT_GROUP) || groups.length === 0) return DEFAULT_GROUP;
  return groups[0] ?? DEFAULT_GROUP;
}

/** Chart series for the caches of one group, in server order. */
export function groupSeries(history: StreamHistory, group: string): SeriesInfo[] {
  return (history.latest?.caches ?? [])
    .filter((c) => c.group === group)
    .map((c) => ({ name: c.name, policy: c.policy }));
}

export interface LoggedRemoval extends RemovalEvent {
  /** Unique within the log: ts, cache, key and cause. */
  id: string;
}

/**
 * The most recent removals (newest first, at most {@code limit}) of the given caches, taken from
 * every snapshot's events in the history. Identical events (same ts, cache, key and cause) are
 * listed once, even if a reconnect delivered them twice.
 */
export function recentRemovals(
  history: StreamHistory,
  caches: readonly string[],
  limit: number = EVENT_LOG_LIMIT,
): LoggedRemoval[] {
  const wanted = new Set(caches);
  const seen = new Set<string>();
  const out: LoggedRemoval[] = [];
  for (let i = history.snapshots.length - 1; i >= 0 && out.length < limit; i--) {
    const events = (history.snapshots[i]?.events ?? []).filter((e) => wanted.has(e.cache));
    // Newest first within a tick; the sort is stable, so equal timestamps keep reverse order.
    const newestFirst = [...events].reverse().sort((a, b) => b.ts - a.ts);
    for (const e of newestFirst) {
      const id = `${e.ts}|${e.cache}|${e.key}|${e.cause}`;
      if (seen.has(id)) continue;
      seen.add(id);
      out.push({ ...e, id });
      if (out.length >= limit) break;
    }
  }
  return out;
}

export interface SimulationView {
  running: boolean;
  id: string | null;
  group: string | null;
  pattern: Pattern | null;
  caption: string | null;
  /** Server-time milliseconds since the current phase started. */
  elapsedMs: number;
}

/** What the controls show about the server's simulation, from the latest snapshot. */
export function simulationView(latest: MetricsSnapshot | null): SimulationView {
  const sim = latest?.simulation;
  if (!latest || !sim) {
    return { running: false, id: null, group: null, pattern: null, caption: null, elapsedMs: 0 };
  }
  return {
    running: sim.running,
    id: sim.id,
    group: sim.group,
    pattern: sim.pattern,
    caption: sim.phaseCaption,
    elapsedMs: Math.max(0, latest.ts - sim.phaseStartedTs),
  };
}

/** 65_400 → "1:05". */
export function formatElapsed(ms: number): string {
  const total = Math.floor(Math.max(0, Number.isFinite(ms) ? ms : 0) / 1000);
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}

/**
 * Row key of the optimal (Bélády) series in the hit-rate chart. The characters are not valid in
 * server cache names, so it never collides with a cache series.
 */
export const OPTIMAL_KEY = '§optimal';

/** The group's entry in a snapshot, or null. */
export function groupMetrics(
  snapshot: MetricsSnapshot | null | undefined,
  group: string,
): GroupMetrics | null {
  return snapshot?.groups.find((g) => g.name === group) ?? null;
}

/**
 * Adds the group's optimal hit rate, as reported in each snapshot, to the chart rows (matched by
 * tick time). Ticks where it was null (not computed yet) keep no value, so the line has a gap.
 */
export function withOptimal(
  rows: readonly HitRateRow[],
  history: StreamHistory,
  group: string,
): HitRateRow[] {
  const byTs = new Map<number, number>();
  for (const s of history.snapshots) {
    const optimal = groupMetrics(s, group)?.optimalHitRate;
    if (typeof optimal === 'number' && Number.isFinite(optimal)) byTs.set(s.ts, optimal);
  }
  return rows.map((r) => {
    const optimal = byTs.get(r.ts);
    return optimal === undefined ? r : ({ ...r, [OPTIMAL_KEY]: optimal } as HitRateRow);
  });
}

/** True when any snapshot in the window carries an optimal hit rate for the group. */
export function hasOptimal(history: StreamHistory, group: string): boolean {
  return history.snapshots.some((s) => typeof groupMetrics(s, group)?.optimalHitRate === 'number');
}

/** Identity of a recommendation, so a banner hidden after Apply stays hidden until it changes. */
export function recommendationKey(group: string, advisor: AdvisorRecommendation): string {
  return `${group}|${advisor.current}|${advisor.recommended}`;
}

/** "+7.4 points (last 30 s)". */
export function gainText(advisor: AdvisorRecommendation): string {
  const gain = Number.isFinite(advisor.expectedGainPts) ? advisor.expectedGainPts : 0;
  return `+${gain.toFixed(1)} points (last ${advisor.windowSec} s)`;
}

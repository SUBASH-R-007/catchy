import type { MetricsSnapshot, PolicyType } from './types';

/** Snapshots kept in memory: 120 ticks of 500 ms = 60 s of history (SPEC 10.4). */
export const RING_CAPACITY = 120;

export interface StreamHistory {
  /** Oldest first, at most RING_CAPACITY entries. */
  snapshots: readonly MetricsSnapshot[];
  latest: MetricsSnapshot | null;
}

export const EMPTY_HISTORY: StreamHistory = { snapshots: [], latest: null };

/**
 * Appends newly received snapshots, keeping the most recent RING_CAPACITY. Out-of-order or
 * duplicate ticks (ts not newer than the latest) are dropped. Returns the same object when nothing
 * changes, so React can skip a render.
 */
export function appendSnapshots(
  history: StreamHistory,
  incoming: readonly MetricsSnapshot[],
): StreamHistory {
  let lastTs = history.latest?.ts ?? Number.NEGATIVE_INFINITY;
  const fresh: MetricsSnapshot[] = [];
  for (const s of incoming) {
    if (s.ts > lastTs) {
      fresh.push(s);
      lastTs = s.ts;
    }
  }
  if (fresh.length === 0) return history;
  const combined = [...history.snapshots, ...fresh];
  const snapshots = combined.length > RING_CAPACITY ? combined.slice(-RING_CAPACITY) : combined;
  return { snapshots, latest: snapshots[snapshots.length - 1] ?? null };
}

/** One chart row: the tick time plus each cache's windowed hit rate keyed by cache name. */
export type HitRateRow = { ts: number } & Record<string, number>;

export interface SeriesInfo {
  name: string;
  policy: PolicyType;
}

/** Caches present in the latest snapshot, in server order. */
export function seriesOf(history: StreamHistory): SeriesInfo[] {
  return (history.latest?.caches ?? []).map((c) => ({ name: c.name, policy: c.policy }));
}

/** Rows for the "hit rate over time" chart (hitRateWindow10s per cache), capped at 120 points. */
export function hitRateRows(history: StreamHistory): HitRateRow[] {
  return history.snapshots.map((s) => {
    const row: HitRateRow = { ts: s.ts } as HitRateRow;
    for (const c of s.caches) row[c.name] = c.hitRateWindow10s;
    return row;
  });
}

export interface PhaseMarker {
  ts: number;
  caption: string;
}

/**
 * Vertical chart markers: one wherever the simulation's phase (or the simulation itself) changes
 * between consecutive snapshots. The first snapshot in the window never produces a marker, because
 * its phase may have started earlier.
 */
export function phaseMarkers(history: StreamHistory): PhaseMarker[] {
  const markers: PhaseMarker[] = [];
  let previousKey: string | null = null;
  history.snapshots.forEach((s, i) => {
    const sim = s.simulation;
    const key = sim ? `${sim.id}#${sim.phaseIndex}` : 'none';
    if (i > 0 && key !== previousKey && sim) {
      markers.push({ ts: s.ts, caption: sim.phaseCaption ?? sim.pattern });
    }
    previousKey = key;
  });
  return markers;
}

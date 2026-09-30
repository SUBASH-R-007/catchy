import type { PolicySnapshot } from '../../api/rest';

/** "Inside the cache" shows this many entries per cache (SPEC 10.5). */
export const INSIDE_LIMIT = 20;

/** Snapshots are polled every second, only while the panel is on screen and the tab visible. */
export const INSIDE_POLL_MS = 1000;

export interface RecencyKey {
  key: string;
  /** 1 = most recently used. */
  rank: number;
}

/** LRU: the most recent keys, most recent first (the server already sends them in that order). */
export function recencyStrip(snapshot: PolicySnapshot, limit = INSIDE_LIMIT): RecencyKey[] {
  return snapshot.entries.slice(0, limit).map((e, i) => ({ key: e.key, rank: i + 1 }));
}

export interface FrequencyBar {
  key: string;
  frequency: number;
  /** Bar length relative to the largest frequency shown, 0-1. */
  share: number;
}

/**
 * LFU / LFU decay: the top frequencies, highest first. Sorted here as well (stable), so the bars
 * are right even if the server's order changes.
 */
export function frequencyBars(snapshot: PolicySnapshot, limit = INSIDE_LIMIT): FrequencyBar[] {
  const top = snapshot.entries
    .map((e) => ({ key: e.key, frequency: Number.isFinite(e.frequency) ? e.frequency : 0 }))
    .sort((a, b) => b.frequency - a.frequency)
    .slice(0, limit);
  const max = Math.max(0, ...top.map((e) => e.frequency));
  return top.map((e) => ({ ...e, share: max > 0 ? Math.max(0, e.frequency) / max : 0 }));
}

/** One sentence describing the snapshot, for screen readers. */
export function insideSummary(cache: string, snapshot: PolicySnapshot): string {
  if (snapshot.entries.length === 0) return `${cache} is empty.`;
  if (snapshot.type === 'LRU') {
    const strip = recencyStrip(snapshot);
    return `${cache} (LRU): ${strip.length} most recently used keys, newest first; ${strip[0]?.key} is the most recent and ${strip[strip.length - 1]?.key} is closest to eviction among them.`;
  }
  const bars = frequencyBars(snapshot);
  const first = bars[0];
  const last = bars[bars.length - 1];
  return `${cache}: top ${bars.length} keys by access frequency, from ${first?.key} (${first?.frequency}) down to ${last?.key} (${last?.frequency}).`;
}

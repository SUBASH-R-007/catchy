import { describe, expect, it } from 'vitest';
import { snapshot } from './fixtures';
import {
  appendSnapshots,
  EMPTY_HISTORY,
  hitRateRows,
  phaseMarkers,
  RING_CAPACITY,
  seriesOf,
} from './streamReducer';
import type { MetricsSnapshot, SimulationStatus } from './types';

const tick = (ts: number, sim: Partial<SimulationStatus> | null = null): MetricsSnapshot => {
  const base = snapshot({ ts });
  return {
    ...base,
    simulation: sim === null ? null : { ...(base.simulation as SimulationStatus), ...sim },
  };
};

describe('appendSnapshots', () => {
  it('appends in order and tracks the latest', () => {
    const h = appendSnapshots(EMPTY_HISTORY, [tick(1), tick(2)]);
    expect(h.snapshots.map((s) => s.ts)).toEqual([1, 2]);
    expect(h.latest?.ts).toBe(2);
  });

  it('keeps only the last 120 snapshots (60 s)', () => {
    const many = Array.from({ length: 150 }, (_, i) => tick(i + 1));
    const h = appendSnapshots(EMPTY_HISTORY, many);
    expect(h.snapshots).toHaveLength(RING_CAPACITY);
    expect(h.snapshots[0]?.ts).toBe(31);
    expect(h.latest?.ts).toBe(150);
  });

  it('drops duplicate and out-of-order ticks and returns the same object when nothing changes', () => {
    const h1 = appendSnapshots(EMPTY_HISTORY, [tick(5)]);
    const h2 = appendSnapshots(h1, [tick(5), tick(3)]);
    expect(h2).toBe(h1);
    const h3 = appendSnapshots(h1, [tick(4), tick(6), tick(6)]);
    expect(h3.snapshots.map((s) => s.ts)).toEqual([5, 6]);
  });
});

describe('chart derivations', () => {
  it('builds one row per tick keyed by cache name', () => {
    const h = appendSnapshots(EMPTY_HISTORY, [tick(1), tick(2)]);
    expect(hitRateRows(h)).toEqual([
      { ts: 1, 'lru-A': 0.774, 'lfu-A': 0.86 },
      { ts: 2, 'lru-A': 0.774, 'lfu-A': 0.86 },
    ]);
    expect(seriesOf(h)).toEqual([
      { name: 'lru-A', policy: 'LRU' },
      { name: 'lfu-A', policy: 'LFU' },
    ]);
  });

  it('marks each phase change but not the first visible phase', () => {
    const h = appendSnapshots(EMPTY_HISTORY, [
      tick(1, { phaseIndex: 0, phaseCaption: 'warm-up' }),
      tick(2, { phaseIndex: 0, phaseCaption: 'warm-up' }),
      tick(3, { phaseIndex: 1, phaseCaption: 'scan' }),
      tick(4, { phaseIndex: 1, phaseCaption: 'scan' }),
      tick(5, { phaseIndex: 2, phaseCaption: null, pattern: 'ZIPF' }),
      tick(6, null),
    ]);
    expect(phaseMarkers(h)).toEqual([
      { ts: 3, caption: 'scan' },
      { ts: 5, caption: 'ZIPF' },
    ]);
  });

  it('marks a new simulation even when its phase index repeats', () => {
    const h = appendSnapshots(EMPTY_HISTORY, [
      tick(1, { id: 'a', phaseIndex: 0, phaseCaption: 'first' }),
      tick(2, { id: 'b', phaseIndex: 0, phaseCaption: 'second' }),
    ]);
    expect(phaseMarkers(h)).toEqual([{ ts: 2, caption: 'second' }]);
  });
});

import { describe, expect, it } from 'vitest';
import { cacheMetrics, snapshot } from '../../api/fixtures';
import { appendSnapshots, EMPTY_HISTORY } from '../../api/streamReducer';
import type { CacheMetrics } from '../../api/types';
import { formatPerSecond, removalRates, removalSummary } from './removals';

function tick(ts: number, caches: CacheMetrics[]) {
  return snapshot({ ts, caches, simulation: null, events: [] });
}

const lru = (evictions: number, expirations: number) =>
  cacheMetrics({ name: 'lru-A', evictions, expirations });
const lfu = (evictions: number, expirations: number) =>
  cacheMetrics({ name: 'lfu-A', policy: 'LFU', evictions, expirations });

describe('removalRates', () => {
  it('is empty without history', () => {
    expect(removalRates(EMPTY_HISTORY)).toEqual([]);
  });

  it('reports 0 with a single snapshot', () => {
    const h = appendSnapshots(EMPTY_HISTORY, [tick(1000, [lru(10, 5)])]);
    expect(removalRates(h)).toEqual([
      { name: 'lru-A', policy: 'LRU', evictionsPerSec: 0, expirationsPerSec: 0 },
    ]);
  });

  it('averages counter deltas over the elapsed time', () => {
    const h = appendSnapshots(EMPTY_HISTORY, [
      tick(0, [lru(0, 0), lfu(0, 0)]),
      tick(500, [lru(50, 1), lfu(0, 0)]),
      tick(1000, [lru(100, 2), lfu(10, 0)]),
    ]);
    const [a, b] = removalRates(h);
    expect(a).toMatchObject({ name: 'lru-A', evictionsPerSec: 100, expirationsPerSec: 2 });
    expect(b).toMatchObject({ name: 'lfu-A', policy: 'LFU', evictionsPerSec: 10 });
  });

  it('only uses the last window of history', () => {
    const h = appendSnapshots(EMPTY_HISTORY, [
      tick(0, [lru(0, 0)]),
      tick(10_000, [lru(1_000_000, 0)]), // before the window: ignored
      tick(15_000, [lru(1_000_100, 0)]),
      tick(20_000, [lru(1_000_200, 0)]),
    ]);
    expect(removalRates(h, 10_000)[0]?.evictionsPerSec).toBeCloseTo(20);
  });

  it('clamps a counter reset to 0 instead of going negative', () => {
    const h = appendSnapshots(EMPTY_HISTORY, [
      tick(0, [lru(500, 50)]),
      tick(1000, [lru(0, 0)]), // reset-stats
      tick(2000, [lru(40, 4)]),
    ]);
    const [r] = removalRates(h);
    expect(r?.evictionsPerSec).toBe(20);
    expect(r?.expirationsPerSec).toBe(2);
  });

  it('skips steps where the cache did not exist yet', () => {
    const h = appendSnapshots(EMPTY_HISTORY, [
      tick(0, [lfu(0, 0)]),
      tick(1000, [lfu(0, 0), lru(0, 0)]),
      tick(2000, [lfu(0, 0), lru(30, 0)]),
    ]);
    expect(removalRates(h).find((r) => r.name === 'lru-A')?.evictionsPerSec).toBe(30);
  });
});

describe('formatPerSecond', () => {
  it.each([
    [0, '0/s'],
    [0.25, '0.3/s'],
    [9.94, '9.9/s'],
    [12.4, '12/s'],
    [5012, '5.0K/s'],
    [25_000, '25K/s'],
    [-3, '0/s'],
    [Number.NaN, '0/s'],
  ])('%s → %s', (input, expected) => {
    expect(formatPerSecond(input)).toBe(expected);
  });
});

describe('removalSummary', () => {
  it('says so when nothing was removed', () => {
    expect(
      removalSummary([{ name: 'a', policy: 'LRU', evictionsPerSec: 0, expirationsPerSec: 0 }]),
    ).toMatch(/No cache evicted or expired/);
  });

  it('names the cache that removes the most, and why', () => {
    const text = removalSummary([
      { name: 'a', policy: 'LRU', evictionsPerSec: 2, expirationsPerSec: 0 },
      { name: 'b', policy: 'LFU', evictionsPerSec: 1, expirationsPerSec: 30 },
    ]);
    expect(text).toContain('a: 2.0/s evictions and 0/s expirations.');
    expect(text).toContain('b removes the most entries, mostly expirations');
  });
});

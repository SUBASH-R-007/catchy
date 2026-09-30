import { describe, expect, it } from 'vitest';
import { cacheMetrics, snapshot } from '../../api/fixtures';
import type { CacheInfo } from '../../api/rest';
import { appendSnapshots, EMPTY_HISTORY } from '../../api/streamReducer';
import { PATTERNS, type RemovalEvent } from '../../api/types';
import { PATTERN_INFO } from './patterns';
import {
  formatElapsed,
  groupNames,
  groupSeries,
  pickGroup,
  recentRemovals,
  simulationView,
} from './race';

function tick(ts: number, events: RemovalEvent[]) {
  return snapshot({ ts, events, simulation: null });
}

const ev = (ts: number, key: string, cache = 'lru-A', cause: RemovalEvent['cause'] = 'EVICTED') =>
  ({ ts, key, cache, cause }) as RemovalEvent;

describe('recentRemovals', () => {
  it('is empty without history', () => {
    expect(recentRemovals(EMPTY_HISTORY, ['lru-A'])).toEqual([]);
  });

  it('lists events from every snapshot, newest first', () => {
    const h = appendSnapshots(EMPTY_HISTORY, [
      tick(1000, [ev(900, 'a'), ev(950, 'b')]),
      tick(1500, [ev(1400, 'c', 'lfu-A', 'EXPIRED')]),
    ]);
    expect(recentRemovals(h, ['lru-A', 'lfu-A']).map((e) => e.key)).toEqual(['c', 'b', 'a']);
  });

  it("keeps only the group's caches", () => {
    const h = appendSnapshots(EMPTY_HISTORY, [
      tick(1000, [ev(900, 'a'), ev(910, 'x', 'play-1', 'EXPLICIT')]),
    ]);
    expect(recentRemovals(h, ['lru-A']).map((e) => e.key)).toEqual(['a']);
  });

  it('drops duplicate events', () => {
    const h = appendSnapshots(EMPTY_HISTORY, [
      tick(1000, [ev(900, 'a'), ev(900, 'a')]),
      tick(1500, [ev(900, 'a'), ev(900, 'a', 'lru-A', 'REPLACED')]),
    ]);
    const log = recentRemovals(h, ['lru-A']);
    expect(log.map((e) => `${e.key}:${e.cause}`)).toEqual(['a:REPLACED', 'a:EVICTED']);
    expect(new Set(log.map((e) => e.id)).size).toBe(log.length);
  });

  it('caps the log at 50, keeping the newest', () => {
    const snaps = Array.from({ length: 3 }, (_, s) =>
      tick(
        (s + 1) * 1000,
        Array.from({ length: 30 }, (_, i) => ev(s * 1000 + i, `k${s}-${i}`)),
      ),
    );
    const log = recentRemovals(appendSnapshots(EMPTY_HISTORY, snaps), ['lru-A']);
    expect(log).toHaveLength(50);
    expect(log[0]?.key).toBe('k2-29');
    expect(log[49]?.key).toBe('k1-10');
  });
});

describe('groups', () => {
  const info = (name: string, group: string) => ({ name, group }) as CacheInfo;

  it('merges REST and stream groups, sorted and unique', () => {
    expect(
      groupNames(
        [info('a', 'playground'), info('b', 'demo')],
        [cacheMetrics({ group: 'demo' }), cacheMetrics({ name: 'x', group: 'act2' })],
      ),
    ).toEqual(['act2', 'demo', 'playground']);
    expect(groupNames(undefined, [])).toEqual([]);
  });

  it('prefers the requested group, then demo, then the first', () => {
    expect(pickGroup('act2', ['demo'])).toBe('act2');
    expect(pickGroup(null, ['a', 'demo'])).toBe('demo');
    expect(pickGroup(null, ['a', 'b'])).toBe('a');
    expect(pickGroup(null, [])).toBe('demo');
  });

  it("charts only the group's caches", () => {
    const h = appendSnapshots(EMPTY_HISTORY, [
      snapshot({
        caches: [
          cacheMetrics(),
          cacheMetrics({ name: 'play-1', group: 'playground' }),
          cacheMetrics({ name: 'lfu-A', policy: 'LFU' }),
        ],
      }),
    ]);
    expect(groupSeries(h, 'demo')).toEqual([
      { name: 'lru-A', policy: 'LRU' },
      { name: 'lfu-A', policy: 'LFU' },
    ]);
  });
});

describe('simulationView', () => {
  it('reports no simulation', () => {
    expect(simulationView(null).running).toBe(false);
    expect(simulationView(snapshot({ simulation: null })).id).toBeNull();
  });

  it('reads pattern, caption and server-time elapsed', () => {
    const view = simulationView(snapshot({ ts: 1790000042500 }));
    expect(view).toMatchObject({
      running: true,
      id: 's-12',
      group: 'demo',
      pattern: 'SCAN_POLLUTION',
      elapsedMs: 42_500,
    });
    expect(view.caption).toContain('scan floods the cache');
  });

  it('formats elapsed time as m:ss', () => {
    expect(formatElapsed(0)).toBe('0:00');
    expect(formatElapsed(65_400)).toBe('1:05');
    expect(formatElapsed(-5)).toBe('0:00');
  });
});

describe('PATTERN_INFO', () => {
  it('describes all eight patterns', () => {
    for (const p of PATTERNS) expect(PATTERN_INFO[p].description.length).toBeGreaterThan(10);
  });
});

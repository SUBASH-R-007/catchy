import { describe, expect, it } from 'vitest';
import { cacheMetrics, snapshot } from './fixtures';
import { isMetricsSnapshot, parseMetricsSnapshot } from './guard';

describe('isMetricsSnapshot', () => {
  it('accepts the SPEC 8.3 example payload', () => {
    expect(isMetricsSnapshot(snapshot())).toBe(true);
  });

  it('accepts the nullable fields as null', () => {
    const s = snapshot({
      simulation: null,
      groups: [{ name: 'demo', caches: [], optimalHitRate: null, advisor: null }],
    });
    expect(isMetricsSnapshot(s)).toBe(true);
  });

  it.each([
    ['a missing field', () => ({ ...snapshot(), events: undefined })],
    ['a rate above 1', () => snapshot({ caches: [cacheMetrics({ hitRate: 1.2 })] })],
    ['a negative counter', () => snapshot({ caches: [cacheMetrics({ hits: -1 })] })],
    ['a fractional counter', () => snapshot({ caches: [cacheMetrics({ evictions: 1.5 })] })],
    ['an unknown policy', () => snapshot({ caches: [cacheMetrics({ policy: 'FIFO' as 'LRU' })] })],
    ['NaN', () => snapshot({ caches: [cacheMetrics({ opsPerSec: Number.NaN })] })],
    [
      'an unknown removal cause',
      () => snapshot({ events: [{ ts: 1, cache: 'c', key: 'k', cause: 'GONE' as 'EVICTED' }] }),
    ],
    [
      'more than 50 events',
      () =>
        snapshot({
          events: Array.from({ length: 51 }, (_, i) => ({
            ts: i,
            cache: 'c',
            key: `k${i}`,
            cause: 'EVICTED' as const,
          })),
        }),
    ],
    ['an array', () => []],
    ['null', () => null],
  ])('rejects %s', (_label, make) => {
    expect(isMetricsSnapshot(make())).toBe(false);
  });
});

describe('parseMetricsSnapshot', () => {
  it('parses valid JSON', () => {
    expect(parseMetricsSnapshot(JSON.stringify(snapshot()))).toEqual(snapshot());
  });

  it('returns null for malformed JSON or the wrong shape', () => {
    expect(parseMetricsSnapshot('{not json')).toBeNull();
    expect(parseMetricsSnapshot('{"ts": 1}')).toBeNull();
  });
});

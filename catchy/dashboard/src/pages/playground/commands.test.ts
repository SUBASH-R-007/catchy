import { describe, expect, it } from 'vitest';
import {
  describeDelete,
  describeGet,
  describePut,
  formatTtl,
  nextCacheName,
  parseCapacity,
  parseTtlSeconds,
  pushHistory,
  ttlRemainingAt,
  validateCacheName,
  validateKey,
  type HistoryEntry,
} from './commands';

describe('parseTtlSeconds', () => {
  it.each([
    ['', { ok: true, value: null }],
    ['  ', { ok: true, value: null }],
    ['30', { ok: true, value: 30_000 }],
    ['1.5', { ok: true, value: 1500 }],
    ['0.0001', { ok: true, value: 1 }],
  ])('%j → %j', (text, expected) => {
    expect(parseTtlSeconds(text)).toEqual(expected);
  });

  it.each(['0', '-1', 'abc'])('rejects %j', (text) => {
    expect(parseTtlSeconds(text).ok).toBe(false);
  });
});

describe('parseCapacity', () => {
  it('accepts whole numbers in range', () => {
    expect(parseCapacity('5')).toEqual({ ok: true, value: 5 });
  });
  it.each(['0', '2.5', '', '1000001'])('rejects %j', (text) => {
    expect(parseCapacity(text).ok).toBe(false);
  });
});

describe('validation', () => {
  it('checks cache names against the server pattern', () => {
    expect(validateCacheName('play_1-A')).toBeNull();
    expect(validateCacheName('has space')).not.toBeNull();
    expect(validateCacheName('x'.repeat(41))).not.toBeNull();
  });

  it('checks keys', () => {
    expect(validateKey('drug:4411')).toBeNull();
    expect(validateKey('')).toBe('Enter a key.');
    expect(validateKey('a/b')).toMatch(/cannot contain/);
    expect(validateKey('k'.repeat(201))).toMatch(/at most 200/);
  });

  it('suggests the next cache name', () => {
    expect(nextCacheName('play-1')).toBe('play-2');
    expect(nextCacheName('orders')).toBe('orders-2');
  });
});

describe('TTL countdown', () => {
  it('interpolates from the fetch time and stops at 0', () => {
    expect(ttlRemainingAt(5000, 1000, 2500)).toBe(3500);
    expect(ttlRemainingAt(5000, 1000, 9000)).toBe(0);
    expect(ttlRemainingAt(null, 1000, 9000)).toBeNull();
    expect(ttlRemainingAt(5000, 1000, 500)).toBe(5000);
  });

  it.each([
    [null, 'never'],
    [0, 'expired'],
    [4321, '4.4 s'],
    [50, '0.1 s'],
    [90_000, '1.5 min'],
  ])('%s → %s', (ms, text) => {
    expect(formatTtl(ms)).toBe(text);
  });
});

describe('result sentences', () => {
  it('describes a hit with its TTL', () => {
    const r = describeGet('k', { hit: true, value: 'v1', ttlRemainingMs: 4000 });
    expect(r.message).toBe('HIT — value "v1", expires in 4.0 s.');
    expect(describeGet('k', { hit: true, value: 'v', ttlRemainingMs: null }).message).toContain(
      'never expires',
    );
  });

  it('describes a miss', () => {
    const r = describeGet('k', { hit: false, value: null, ttlRemainingMs: null });
    expect(r.message).toBe('MISS — the key is not in the cache (or it expired).');
  });

  it('describes a put, falling back to the cache default TTL', () => {
    expect(describePut('k', 2000, null).message).toBe('Stored "k"; it expires in 2.0 s.');
    expect(describePut('k', null, 10_000).message).toContain('(the cache default)');
    expect(describePut('k', null, null).message).toContain('never expires');
  });

  it('describes a delete', () => {
    expect(describeDelete('k', true).short).toBe('removed');
    expect(describeDelete('k', false).short).toBe('not found');
  });

  it('keeps the newest ten history entries', () => {
    let h: HistoryEntry[] = [];
    for (let i = 0; i < 12; i++)
      h = pushHistory(h, { ...describeDelete(`k${i}`, true), id: i, ts: i });
    expect(h).toHaveLength(10);
    expect(h[0]?.key).toBe('k11');
    expect(h[9]?.key).toBe('k2');
  });
});

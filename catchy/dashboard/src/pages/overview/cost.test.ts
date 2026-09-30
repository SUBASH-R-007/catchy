import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  DEFAULT_PRICE_PER_1000,
  estimateCostSaved,
  MEAN_DB_LATENCY_MS,
  parsePrice,
  PRICE_STORAGE_KEY,
  readStoredPrice,
  writeStoredPrice,
} from './cost';

describe('estimateCostSaved', () => {
  it('is calls avoided / 1000 x price', () => {
    expect(estimateCostSaved(812_344, 0.05)).toBeCloseTo(40.6172, 6);
    expect(estimateCostSaved(1000, 2)).toBe(2);
  });

  it('is 0 for no calls, a zero price or bad input', () => {
    expect(estimateCostSaved(0, 0.05)).toBe(0);
    expect(estimateCostSaved(5000, 0)).toBe(0);
    expect(estimateCostSaved(Number.NaN, 0.05)).toBe(0);
    expect(estimateCostSaved(-10, 0.05)).toBe(0);
  });

  it('uses the SPEC 9.3 mean latency of 12.5 ms', () => {
    expect(MEAN_DB_LATENCY_MS).toBe(12.5);
  });
});

describe('parsePrice', () => {
  it('accepts non-negative numbers', () => {
    expect(parsePrice('0.05')).toEqual({ ok: true, value: 0.05 });
    expect(parsePrice(' 0 ')).toEqual({ ok: true, value: 0 });
  });

  it('rejects empty, negative, non-numeric and absurd prices', () => {
    expect(parsePrice('').ok).toBe(false);
    expect(parsePrice('-1').ok).toBe(false);
    expect(parsePrice('abc').ok).toBe(false);
    expect(parsePrice('1e9').ok).toBe(false);
  });
});

describe('price storage', () => {
  afterEach(() => {
    window.localStorage.clear();
  });

  it('defaults to 0.05 and round-trips a stored price', () => {
    expect(readStoredPrice()).toBe(DEFAULT_PRICE_PER_1000);
    writeStoredPrice(0.2);
    expect(window.localStorage.getItem(PRICE_STORAGE_KEY)).toBe('0.2');
    expect(readStoredPrice()).toBe(0.2);
  });

  it('ignores a corrupt stored value', () => {
    window.localStorage.setItem(PRICE_STORAGE_KEY, 'lots');
    expect(readStoredPrice()).toBe(DEFAULT_PRICE_PER_1000);
  });

  it('survives blocked storage', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(readStoredPrice()).toBe(DEFAULT_PRICE_PER_1000);
    expect(() => writeStoredPrice(1)).not.toThrow();
  });
});

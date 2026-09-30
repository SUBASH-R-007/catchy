import { describe, expect, it } from 'vitest';
import { CONFIG_BOUNDS } from '../api/types';
import type { RegionConfig } from '../api/types';
import { initialFormValues, validateConfigForm } from './configForm';
import { actionsForGroups, isEvictionAction, memoryReleasedBytes } from './events';
import {
  confidenceBand,
  healthRank,
  hitRateTone,
  isHighRisk,
  memorySeverity,
  MEMORY_THRESHOLDS,
  riskRank,
  worstHealth,
} from './health';
import { canDecideRequest, hasRole } from './roles';

describe('memory thresholds (80 / 90 / 98)', () => {
  it('exposes the documented thresholds', () => {
    expect(MEMORY_THRESHOLDS).toEqual({ warning: 80, serious: 90, critical: 98 });
  });
  it.each([
    [0, 'ok'],
    [79.99, 'ok'],
    [80, 'warning'],
    [89.9, 'warning'],
    [90, 'serious'],
    [97.99, 'serious'],
    [98, 'critical'],
    [100, 'critical'],
  ] as const)('%s%% is %s', (percent, expected) => {
    expect(memorySeverity(percent)).toBe(expected);
  });
  it('treats missing or invalid values as ok', () => {
    expect(memorySeverity(null)).toBe('ok');
    expect(memorySeverity(Number.NaN)).toBe('ok');
  });
});

describe('health helpers', () => {
  it('ranks statuses worst-first, with UNKNOWN above GOOD', () => {
    const statuses = ['EXCELLENT', 'GOOD', 'UNKNOWN', 'WARNING', 'CRITICAL'] as const;
    const byRank = [...statuses].sort((a, b) => healthRank(b) - healthRank(a));
    expect(byRank).toEqual(['CRITICAL', 'WARNING', 'UNKNOWN', 'GOOD', 'EXCELLENT']);
    expect(worstHealth(['GOOD', 'WARNING', 'EXCELLENT'])).toBe('WARNING');
    expect(worstHealth([])).toBe('UNKNOWN');
  });
  it('orders and classifies risk', () => {
    expect(riskRank('CRITICAL')).toBeGreaterThan(riskRank('HIGH'));
    expect(riskRank('HIGH')).toBeGreaterThan(riskRank('MEDIUM'));
    expect(riskRank('MEDIUM')).toBeGreaterThan(riskRank('LOW'));
    expect(isHighRisk('HIGH')).toBe(true);
    expect(isHighRisk('CRITICAL')).toBe(true);
    expect(isHighRisk('MEDIUM')).toBe(false);
  });
  it('bands confidence and hit rate', () => {
    expect(confidenceBand(92)).toBe('high');
    expect(confidenceBand(60)).toBe('medium');
    expect(confidenceBand(20)).toBe('low');
    expect(hitRateTone(91, 100)).toBe('good');
    expect(hitRateTone(70, 100)).toBe('info');
    expect(hitRateTone(50, 100)).toBe('warning');
    expect(hitRateTone(10, 100)).toBe('critical');
    expect(hitRateTone(0, 0)).toBe('neutral');
  });
});

describe('roles', () => {
  it('orders VIEWER < ENGINEER < ADMIN', () => {
    expect(hasRole('ADMIN', 'ENGINEER')).toBe(true);
    expect(hasRole('ENGINEER', 'ADMIN')).toBe(false);
    expect(hasRole('VIEWER', 'ENGINEER')).toBe(false);
    expect(hasRole(null, 'VIEWER')).toBe(false);
  });
  it('mirrors the backend rule: no self-approval unless ADMIN', () => {
    const pending = { status: 'PENDING', requestedBy: 'engineer' } as const;
    expect(canDecideRequest({ username: 'engineer', role: 'ENGINEER' }, pending).allowed).toBe(false);
    expect(canDecideRequest({ username: 'engineer', role: 'ENGINEER' }, pending).reason).toMatch(/requested this change/i);
    expect(canDecideRequest({ username: 'admin', role: 'ADMIN' }, { status: 'PENDING', requestedBy: 'admin' }).allowed).toBe(true);
    expect(canDecideRequest({ username: 'other', role: 'ENGINEER' }, pending).allowed).toBe(true);
    expect(canDecideRequest({ username: 'viewer', role: 'VIEWER' }, pending).allowed).toBe(false);
    expect(canDecideRequest({ username: 'admin', role: 'ADMIN' }, { status: 'APPLIED', requestedBy: 'x' }).allowed).toBe(false);
  });
});

describe('event helpers', () => {
  it('flattens selected chips into a deduplicated actions list', () => {
    expect(actionsForGroups([])).toEqual([]);
    expect(actionsForGroups(['evictions'])).toEqual(['EVICTED', 'MEMORY_EVICTED', 'ENTRY_LIMIT_EVICTED']);
    expect(actionsForGroups(['misses', 'hits'])).toEqual(['HIT', 'VICTIM_HIT', 'STALE_SERVED', 'MISS']);
  });
  it('reports memory released only for evictions and expirations', () => {
    expect(memoryReleasedBytes({ action: 'ENTRY_LIMIT_EVICTED', memoryBeforeBytes: 901_000, memoryAfterBytes: 899_200 })).toBe(1800);
    expect(memoryReleasedBytes({ action: 'HIT', memoryBeforeBytes: 10, memoryAfterBytes: 5 })).toBeNull();
    expect(memoryReleasedBytes({ action: 'EVICTED', memoryBeforeBytes: 5, memoryAfterBytes: 5 })).toBeNull();
    expect(isEvictionAction('MEMORY_EVICTED')).toBe(true);
    expect(isEvictionAction('EXPIRED')).toBe(false);
  });
});

describe('configuration form validation', () => {
  const config: RegionConfig = {
    cacheRegion: 'claim-rules',
    riskLevel: 'MEDIUM',
    reported: { maximumEntries: 500, maximumMemoryBytes: 268_435_456, defaultTtlMs: 900_000, activePolicy: 'LFU' },
    desired: null,
    pending: false,
  };
  const base = initialFormValues(config);

  it('shows memory in MB and TTL in seconds', () => {
    expect(base).toMatchObject({ maximumEntries: '500', maximumMemoryMb: '256', defaultTtlSeconds: '900' });
  });
  it('requires at least one change', () => {
    expect(validateConfigForm(base, base).errors.form).toBeTruthy();
  });
  it('converts MB to bytes and seconds to ms and only sends changed fields', () => {
    const { request, errors } = validateConfigForm({ ...base, maximumEntries: '800', maximumMemoryMb: '512', reason: 'month-end' }, base);
    expect(errors).toEqual({});
    expect(request).toEqual({ maximumEntries: 800, maximumMemoryBytes: 536_870_912, reason: 'month-end' });
    expect(validateConfigForm({ ...base, defaultTtlSeconds: '600' }, base).request).toEqual({ defaultTtlMs: 600_000 });
  });
  it('enforces the contract bounds', () => {
    expect(validateConfigForm({ ...base, maximumEntries: '0' }, base).errors.maximumEntries).toBeTruthy();
    expect(validateConfigForm({ ...base, maximumEntries: String(CONFIG_BOUNDS.maximumEntries.max + 1) }, base).errors.maximumEntries).toBeTruthy();
    expect(validateConfigForm({ ...base, maximumEntries: '1.5' }, base).errors.maximumEntries).toBeTruthy();
    expect(validateConfigForm({ ...base, maximumMemoryMb: '0.0001' }, base).errors.maximumMemoryMb).toBeTruthy();
    expect(validateConfigForm({ ...base, defaultTtlSeconds: '0.5' }, base).errors.defaultTtlSeconds).toBeTruthy();
    expect(validateConfigForm({ ...base, maximumEntries: String(CONFIG_BOUNDS.maximumEntries.max) }, base).errors).toEqual({});
    expect(validateConfigForm({ ...base, defaultTtlSeconds: '1' }, base).request?.defaultTtlMs).toBe(1000);
  });
  it('seeds from the desired override when one is pending', () => {
    const pending = initialFormValues({ ...config, desired: { maximumEntries: 800, maximumMemoryBytes: null, defaultTtlMs: null }, pending: true });
    expect(pending.maximumEntries).toBe('800');
    expect(pending.maximumMemoryMb).toBe('256');
  });
});

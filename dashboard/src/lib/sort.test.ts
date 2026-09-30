import { describe, expect, it } from 'vitest';
import { makeRegion } from '../test/fixtures';
import { filterRegions, REGION_SORT_OPTIONS, sortRegions, type RegionSortKey } from './sort';

const regions = [
  makeRegion({ cacheRegion: 'a-low', riskLevel: 'LOW', hitRate: 90, estimatedMemoryUsageBytes: 500, evictions: 5, expirations: 50, sourceCallsAvoided: 900, lastUpdated: '2026-09-30T09:00:10Z' }),
  makeRegion({ cacheRegion: 'b-crit', riskLevel: 'CRITICAL', hitRate: 40, estimatedMemoryUsageBytes: 900, evictions: 50, expirations: 10, sourceCallsAvoided: 100, lastUpdated: '2026-09-30T09:00:30Z' }),
  makeRegion({ cacheRegion: 'c-med', riskLevel: 'MEDIUM', hitRate: 70, estimatedMemoryUsageBytes: 100, evictions: 20, expirations: 90, sourceCallsAvoided: 5000, lastUpdated: '2026-09-30T09:00:00Z' }),
  makeRegion({ cacheRegion: 'd-high', riskLevel: 'HIGH', hitRate: 60, estimatedMemoryUsageBytes: 700, evictions: 80, expirations: 30, sourceCallsAvoided: 300, lastUpdated: '2026-09-30T09:00:20Z' }),
];

const order = (key: RegionSortKey) => sortRegions(regions, key).map((r) => r.cacheRegion);

describe('sortRegions: all eight modes', () => {
  it('offers exactly the eight documented sort modes', () => {
    expect(REGION_SORT_OPTIONS.map((o) => o.label)).toEqual([
      'Highest hit rate',
      'Lowest hit rate',
      'Highest estimated memory',
      'Most evictions',
      'Most expirations',
      'Most recently updated',
      'Highest source calls avoided',
      'Highest risk level',
    ]);
  });
  it('highest hit rate', () => expect(order('hit-desc')).toEqual(['a-low', 'c-med', 'd-high', 'b-crit']));
  it('lowest hit rate', () => expect(order('hit-asc')).toEqual(['b-crit', 'd-high', 'c-med', 'a-low']));
  it('highest estimated memory', () => expect(order('memory-desc')).toEqual(['b-crit', 'd-high', 'a-low', 'c-med']));
  it('most evictions', () => expect(order('evictions-desc')).toEqual(['d-high', 'b-crit', 'c-med', 'a-low']));
  it('most expirations', () => expect(order('expirations-desc')).toEqual(['c-med', 'a-low', 'd-high', 'b-crit']));
  it('most recently updated', () => expect(order('recent-desc')).toEqual(['b-crit', 'd-high', 'a-low', 'c-med']));
  it('highest source calls avoided', () => expect(order('avoided-desc')).toEqual(['c-med', 'a-low', 'd-high', 'b-crit']));
  it('highest risk level follows CRITICAL > HIGH > MEDIUM > LOW', () => expect(order('risk-desc')).toEqual(['b-crit', 'd-high', 'c-med', 'a-low']));
});

describe('sortRegions behaviour', () => {
  it('does not mutate its input', () => {
    const copy = regions.map((r) => r.cacheRegion);
    sortRegions(regions, 'hit-asc');
    expect(regions.map((r) => r.cacheRegion)).toEqual(copy);
  });
  it('breaks ties by application then region name, deterministically', () => {
    const tied = [
      makeRegion({ cacheRegion: 'z', applicationName: 'b-app', riskLevel: 'LOW' }),
      makeRegion({ cacheRegion: 'y', applicationName: 'a-app', riskLevel: 'LOW' }),
      makeRegion({ cacheRegion: 'x', applicationName: 'a-app', riskLevel: 'LOW' }),
    ];
    expect(sortRegions(tied, 'risk-desc').map((r) => `${r.applicationName}/${r.cacheRegion}`)).toEqual(['a-app/x', 'a-app/y', 'b-app/z']);
  });
  it('ranks risk by severity, not alphabetically', () => {
    const all = (['LOW', 'CRITICAL', 'MEDIUM', 'HIGH'] as const).map((riskLevel) => makeRegion({ cacheRegion: riskLevel, riskLevel }));
    expect(sortRegions(all, 'risk-desc').map((r) => r.riskLevel)).toEqual(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']);
  });
});

describe('filterRegions', () => {
  const mixed = [
    makeRegion({ applicationId: 1, cacheRegion: 'claim-rules', riskLevel: 'MEDIUM' }),
    makeRegion({ applicationId: 1, cacheRegion: 'claim-lookup', riskLevel: 'LOW' }),
    makeRegion({ applicationId: 2, cacheRegion: 'member-eligibility', riskLevel: 'HIGH' }),
  ];
  it('filters by application', () => expect(filterRegions(mixed, { applicationId: 2 }).map((r) => r.cacheRegion)).toEqual(['member-eligibility']));
  it('filters by risk level', () => expect(filterRegions(mixed, { riskLevel: 'LOW' }).map((r) => r.cacheRegion)).toEqual(['claim-lookup']));
  it('searches region names case-insensitively', () => expect(filterRegions(mixed, { query: ' CLAIM ' })).toHaveLength(2));
  it('combines filters and treats empty values as "all"', () => {
    expect(filterRegions(mixed, { applicationId: 1, riskLevel: 'MEDIUM', query: 'rules' })).toHaveLength(1);
    expect(filterRegions(mixed, { applicationId: null, riskLevel: '', query: '' })).toHaveLength(3);
  });
});

import { describe, expect, it } from 'vitest';
import {
  busCaption,
  CHIP_CENTERS,
  electronPosition,
  electronsPerSecond,
  EMPTY_BUS,
  HIT_PATH,
  LANE_BACK,
  LANE_OUT,
  MAX_ELECTRONS,
  MISS_PATH,
  mulberry32,
  niceCeil,
  pathLength,
  pointAt,
  requestsPerElectron,
  stepBus,
  travelSeconds,
  type BusState,
} from './busMath';

describe('bus paths', () => {
  it('measures and walks a polyline by arc length', () => {
    const path = [
      { x: 0, y: 0 },
      { x: 10, y: 0 },
      { x: 10, y: 10 },
    ];
    expect(pathLength(path)).toBe(20);
    expect(pointAt(path, 0)).toEqual({ x: 0, y: 0 });
    expect(pointAt(path, 0.25)).toEqual({ x: 5, y: 0 });
    expect(pointAt(path, 0.75)).toEqual({ x: 10, y: 5 });
    expect(pointAt(path, 2)).toEqual({ x: 10, y: 10 });
  });

  it('turns hits around in the cache and misses in the database', () => {
    const hitTurn = pointAt(HIT_PATH, pathLength(HIT_PATH.slice(0, 2)) / pathLength(HIT_PATH));
    expect(hitTurn).toEqual({ x: CHIP_CENTERS.cache, y: LANE_OUT });
    const missTurn = pointAt(MISS_PATH, pathLength(MISS_PATH.slice(0, 2)) / pathLength(MISS_PATH));
    expect(missTurn).toEqual({ x: CHIP_CENTERS.db, y: LANE_OUT });
    expect(pointAt(MISS_PATH, 1)).toEqual({ x: CHIP_CENTERS.client, y: LANE_BACK });
    expect(travelSeconds(MISS_PATH)).toBeGreaterThan(travelSeconds(HIT_PATH));
  });
});

describe('spawn rate', () => {
  it('rounds requests per electron to 1, 2 or 5 × 10ⁿ', () => {
    expect(niceCeil(0)).toBe(1);
    expect(niceCeil(3)).toBe(5);
    expect(niceCeil(626.5)).toBe(1000);
    expect(niceCeil(1500)).toBe(2000);
    expect(requestsPerElectron(5012)).toBe(1000);
    expect(requestsPerElectron(4)).toBe(1);
  });

  it('is proportional to ops/sec and stays readable', () => {
    expect(electronsPerSecond(0)).toBe(0);
    expect(electronsPerSecond(Number.NaN)).toBe(0);
    expect(electronsPerSecond(5012)).toBeCloseTo(5.012);
    expect(electronsPerSecond(4)).toBe(4);
    for (const ops of [9, 80, 5_000, 123_456, 9_000_000]) {
      expect(electronsPerSecond(ops)).toBeLessThanOrEqual(8);
      expect(electronsPerSecond(ops)).toBeGreaterThan(3);
    }
  });
});

describe('stepBus', () => {
  const random = () => 0.5;

  function run(state: BusState, seconds: number, opsPerSec: number, hitRate: number): BusState {
    let s = state;
    for (let t = 0; t < seconds * 60; t++) {
      s = stepBus(s, { dt: 1 / 60, opsPerSec, hitRate, random });
    }
    return s;
  }

  it('spawns at the electron rate and carries fractions between frames', () => {
    const s = stepBus(EMPTY_BUS, { dt: 0.5, opsPerSec: 4, hitRate: 1, random });
    expect(s.electrons).toHaveLength(2);
    expect(s.carry).toBeCloseTo(0);
    const s2 = stepBus(s, { dt: 0.1, opsPerSec: 4, hitRate: 1, random });
    expect(s2.electrons).toHaveLength(2);
    expect(s2.carry).toBeCloseTo(0.4);
  });

  it('decides hit or miss with probability hitRate', () => {
    const hits = stepBus(EMPTY_BUS, { dt: 1, opsPerSec: 5, hitRate: 0.84, random: () => 0.83 });
    expect(hits.electrons.every((e) => e.hit)).toBe(true);
    const misses = stepBus(EMPTY_BUS, { dt: 1, opsPerSec: 5, hitRate: 0.84, random: () => 0.84 });
    expect(misses.electrons.every((e) => !e.hit)).toBe(true);
  });

  it('matches the hit rate over many electrons with the seeded PRNG', () => {
    const rng = mulberry32(7);
    let hits = 0;
    let total = 0;
    let s = EMPTY_BUS;
    for (let i = 0; i < 2000; i++) {
      s = stepBus({ electrons: [], carry: s.carry }, { dt: 0.25, opsPerSec: 8, hitRate: 0.84, random: rng });
      hits += s.electrons.filter((e) => e.hit).length;
      total += s.electrons.length;
    }
    expect(total).toBe(4000);
    expect(hits / total).toBeGreaterThan(0.81);
    expect(hits / total).toBeLessThan(0.87);
  });

  it('retires electrons when they arrive back at the client', () => {
    const s = stepBus(EMPTY_BUS, { dt: 0.25, opsPerSec: 4, hitRate: 1, random });
    expect(s.electrons).toHaveLength(1);
    const later = stepBus(s, { dt: travelSeconds(HIT_PATH), opsPerSec: 0, hitRate: 1, random });
    expect(later.electrons).toHaveLength(0);
  });

  it('never shows more than 30 electrons', () => {
    let s = EMPTY_BUS;
    for (let i = 0; i < 100; i++) {
      s = stepBus(s, { dt: 0.1, opsPerSec: 1e9, hitRate: 0, random });
      expect(s.electrons.length).toBeLessThanOrEqual(MAX_ELECTRONS);
    }
    const busy = run(EMPTY_BUS, 10, 1e9, 0);
    expect(busy.electrons.length).toBeLessThanOrEqual(MAX_ELECTRONS);
  });

  it('positions electrons along their path', () => {
    const e = { hit: true, age: 0, duration: travelSeconds(HIT_PATH) };
    expect(electronPosition(e)).toEqual({ x: CHIP_CENTERS.client, y: LANE_OUT });
    expect(electronPosition({ ...e, age: e.duration })).toEqual({
      x: CHIP_CENTERS.client,
      y: LANE_BACK,
    });
  });
});

describe('busCaption', () => {
  it('states the share of requests that never reached the database', () => {
    expect(busCaption(0.838, 5000)).toBe('84% of requests never reached the database.');
    expect(busCaption(0.5, 0)).toMatch(/No requests right now/);
  });
});

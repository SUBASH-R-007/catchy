/**
 * Pure geometry and spawning math for the Data bus (SPEC 10.6). No DOM and no timers here, so the
 * animation loop in DataBus.tsx stays thin and this file is unit-tested directly.
 */

export interface Point {
  x: number;
  y: number;
}

/** SVG viewBox of the strip. */
export const BUS_WIDTH = 720;
export const BUS_HEIGHT = 160;

/** Chip boxes: CLIENT, CACHE and DB, left to right. */
export const CHIP_WIDTH = 120;
export const CHIP_HEIGHT = 88;
export const CHIP_TOP = (BUS_HEIGHT - CHIP_HEIGHT) / 2;
export const CHIP_CENTERS = { client: 100, cache: 360, db: 620 } as const;

/** Requests travel right on the upper lane and return on the lower lane. */
export const LANE_OUT = 62;
export const LANE_BACK = 98;

/** At most this many electrons are on screen at once. */
export const MAX_ELECTRONS = 30;

/** Electron speed along the traces, in viewBox units per second. */
export const ELECTRON_SPEED = 360;

/** The spawn rate aims for at most this many electrons per second. */
export const TARGET_ELECTRONS_PER_SEC = 8;

/** A hit turns around inside the cache chip. */
export const HIT_PATH: readonly Point[] = [
  { x: CHIP_CENTERS.client, y: LANE_OUT },
  { x: CHIP_CENTERS.cache, y: LANE_OUT },
  { x: CHIP_CENTERS.cache, y: LANE_BACK },
  { x: CHIP_CENTERS.client, y: LANE_BACK },
];

/** A miss passes through the cache, turns around inside the database and comes back. */
export const MISS_PATH: readonly Point[] = [
  { x: CHIP_CENTERS.client, y: LANE_OUT },
  { x: CHIP_CENTERS.db, y: LANE_OUT },
  { x: CHIP_CENTERS.db, y: LANE_BACK },
  { x: CHIP_CENTERS.client, y: LANE_BACK },
];

/** Total length of a polyline. */
export function pathLength(path: readonly Point[]): number {
  let total = 0;
  for (let i = 1; i < path.length; i++) {
    const a = path[i - 1];
    const b = path[i];
    if (a && b) total += Math.hypot(b.x - a.x, b.y - a.y);
  }
  return total;
}

/** The point a fraction `t` (clamped to 0-1) of the way along a polyline, by arc length. */
export function pointAt(path: readonly Point[], t: number): Point {
  const first = path[0] ?? { x: 0, y: 0 };
  const total = pathLength(path);
  if (total === 0) return first;
  let remaining = Math.min(1, Math.max(0, t)) * total;
  for (let i = 1; i < path.length; i++) {
    const a = path[i - 1];
    const b = path[i];
    if (!a || !b) continue;
    const segment = Math.hypot(b.x - a.x, b.y - a.y);
    if (remaining <= segment) {
      const f = segment === 0 ? 0 : remaining / segment;
      return { x: a.x + (b.x - a.x) * f, y: a.y + (b.y - a.y) * f };
    }
    remaining -= segment;
  }
  return path[path.length - 1] ?? first;
}

/** Seconds an electron needs for a whole path. */
export function travelSeconds(path: readonly Point[]): number {
  return pathLength(path) / ELECTRON_SPEED;
}

/** Rounds up to 1, 2 or 5 × a power of ten. */
export function niceCeil(n: number): number {
  if (!Number.isFinite(n) || n <= 1) return 1;
  const power = 10 ** Math.floor(Math.log10(n));
  for (const step of [1, 2, 5, 10]) {
    if (step * power >= n) return step * power;
  }
  return 10 * power;
}

/** How many requests one electron stands for: a round number that keeps the rate readable. */
export function requestsPerElectron(opsPerSec: number): number {
  return niceCeil(Math.max(0, opsPerSec) / TARGET_ELECTRONS_PER_SEC);
}

/** Electrons per second for a cache doing `opsPerSec` requests per second. */
export function electronsPerSecond(opsPerSec: number): number {
  const ops = Number.isFinite(opsPerSec) ? Math.max(0, opsPerSec) : 0;
  return ops === 0 ? 0 : ops / requestsPerElectron(ops);
}

export interface Electron {
  hit: boolean;
  /** Seconds since the electron left the client. */
  age: number;
  /** Seconds the whole trip takes. */
  duration: number;
}

export interface BusState {
  electrons: Electron[];
  /** Fractional electrons owed by the spawner, carried between frames. */
  carry: number;
}

export const EMPTY_BUS: BusState = { electrons: [], carry: 0 };

export interface StepInput {
  /** Frame time in seconds (callers clamp long gaps, e.g. after a hidden tab). */
  dt: number;
  opsPerSec: number;
  /** Probability that a new electron is a hit, 0-1. */
  hitRate: number;
  /** Uniform random number in [0, 1). */
  random: () => number;
}

/**
 * Advances every electron by `dt`, drops the ones that arrived home and spawns new ones at
 * `electronsPerSecond(opsPerSec)`, never exceeding MAX_ELECTRONS on screen. Spawns owed while the
 * bus is full are dropped rather than queued, so the strip never bursts afterwards.
 */
export function stepBus(state: BusState, input: StepInput): BusState {
  const dt = Math.max(0, input.dt);
  const electrons = state.electrons
    .map((e) => ({ ...e, age: e.age + dt }))
    .filter((e) => e.age < e.duration);
  let carry = state.carry + dt * electronsPerSecond(input.opsPerSec);
  const hitRate = Math.min(1, Math.max(0, input.hitRate));
  while (carry >= 1) {
    carry -= 1;
    if (electrons.length >= MAX_ELECTRONS) continue;
    const hit = input.random() < hitRate;
    electrons.push({ hit, age: 0, duration: travelSeconds(hit ? HIT_PATH : MISS_PATH) });
  }
  return { electrons, carry };
}

/** Where an electron is drawn now. */
export function electronPosition(electron: Electron): Point {
  return pointAt(electron.hit ? HIT_PATH : MISS_PATH, electron.age / electron.duration);
}

/** "84% of requests never reached the database." */
export function busCaption(hitRate: number, opsPerSec: number): string {
  if (!(opsPerSec > 0)) return 'No requests right now — start a workload to see traffic.';
  const pct = Math.round(Math.min(1, Math.max(0, hitRate)) * 100);
  return `${pct}% of requests never reached the database.`;
}

/** Seeded mulberry32 PRNG: the electron stream is reproducible (CLAUDE.md: seeded randomness). */
export function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

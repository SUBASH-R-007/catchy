/**
 * Simulations for the mock backend. They run small but *real* LRU / LFU caches over synthetic
 * key streams (keys are made-up strings such as "a-12" — never patient or member data) and turn the
 * outcome into `sim-*` regions, events and a contract-shaped SimulationResult.
 */
import type {
  CacheAction,
  EvictionPolicy,
  SimulationComparisonRow,
  SimulationKind,
  SimulationResult,
  SimulationStep,
} from '../types';
import { formatBytes, formatDuration } from '../../lib/format';
import { buildEvent, type EngineContext } from './engine';
import { emptyCounters, TICK_MS, type RegionSeed, type RegionState, type TickDelta } from './model';
import { Rng, fakeFingerprint } from './prng';

export interface SimEvictionInfo {
  key: string;
  kind: 'entry' | 'memory';
  size: number;
  ageMs: number;
  freq: number;
}

interface SimEntry {
  size: number;
  freq: number;
  lastAccess: number;
  expiresAt: number | null;
  seq: number;
}

export type GetOutcome = 'hit' | 'miss' | 'expired';

/** A deliberately small cache with LRU / LFU eviction, an entry limit, an optional byte limit and TTL. */
export class SimCache {
  readonly entries = new Map<string, SimEntry>();
  memory = 0;
  private seq = 0;

  constructor(
    readonly policy: EvictionPolicy,
    readonly capacity: number,
    readonly maxBytes: number | null,
    readonly ttlMs: number | null,
  ) {}

  get size(): number {
    return this.entries.size;
  }

  get(key: string, now: number): GetOutcome {
    const e = this.entries.get(key);
    if (!e) return 'miss';
    if (e.expiresAt !== null && now >= e.expiresAt) {
      this.entries.delete(key);
      this.memory -= e.size;
      return 'expired';
    }
    e.freq += 1;
    e.lastAccess = now;
    return 'hit';
  }

  put(key: string, size: number, now: number): SimEvictionInfo[] {
    const evicted: SimEvictionInfo[] = [];
    const existing = this.entries.get(key);
    if (existing) {
      this.memory -= existing.size;
      this.entries.delete(key);
    }
    while (this.entries.size >= this.capacity && this.entries.size > 0) {
      evicted.push(this.evictOne('entry', now));
    }
    while (this.maxBytes !== null && this.memory + size > this.maxBytes && this.entries.size > 0) {
      evicted.push(this.evictOne('memory', now));
    }
    this.seq += 1;
    this.entries.set(key, {
      size,
      freq: existing ? existing.freq : 1,
      lastAccess: now,
      expiresAt: this.ttlMs === null ? null : now + this.ttlMs,
      seq: this.seq,
    });
    this.memory += size;
    return evicted;
  }

  private evictOne(kind: 'entry' | 'memory', now: number): SimEvictionInfo {
    let victimKey = '';
    let victim: SimEntry | null = null;
    for (const [key, e] of this.entries) {
      if (victim === null) {
        victim = e;
        victimKey = key;
        continue;
      }
      const better =
        this.policy === 'LRU'
          ? e.lastAccess < victim.lastAccess || (e.lastAccess === victim.lastAccess && e.seq < victim.seq)
          : e.freq < victim.freq ||
            (e.freq === victim.freq &&
              (e.lastAccess < victim.lastAccess || (e.lastAccess === victim.lastAccess && e.seq < victim.seq)));
      if (better) {
        victim = e;
        victimKey = key;
      }
    }
    if (victim === null) throw new Error('evictOne on empty cache');
    this.entries.delete(victimKey);
    this.memory -= victim.size;
    return { key: victimKey, kind, size: victim.size, ageMs: Math.max(0, now - victim.lastAccess), freq: victim.freq };
  }
}

// ---------------------------------------------------------------------------
// Key streams
// ---------------------------------------------------------------------------

/** Pattern A — LRU-friendly: a small working set that keeps moving. */
export function patternAStream(n: number, rng: Rng): string[] {
  const keys: string[] = [];
  for (let i = 0; i < n; i += 1) {
    const phase = Math.floor(i / 40);
    keys.push(`a-${phase * 3 + rng.int(0, 5)}`);
  }
  return keys;
}

/** Pattern B — LFU-friendly: a few keys stay popular while scans of one-off keys pass through. */
export function patternBStream(n: number, rng: Rng): string[] {
  const keys: string[] = [];
  let scan = 0;
  while (keys.length < n) {
    for (let i = 0; i < 10 && keys.length < n; i += 1) keys.push(`b-hot-${rng.int(0, 3)}`);
    for (let i = 0; i < 8 && keys.length < n; i += 1) {
      keys.push(`b-scan-${scan}`);
      scan += 1;
    }
  }
  return keys;
}

export interface StreamRun {
  hits: number;
  misses: number;
  evictions: number;
  hitRate: number;
}

/** Run a key stream through a fresh cache (size 1 KB per entry) and report the hit rate. */
export function runStream(policy: EvictionPolicy, capacity: number, keys: string[]): StreamRun {
  const cache = new SimCache(policy, capacity, null, null);
  let hits = 0;
  let misses = 0;
  let evictions = 0;
  keys.forEach((key, i) => {
    if (cache.get(key, i) === 'hit') {
      hits += 1;
    } else {
      misses += 1;
      evictions += cache.put(key, 1024, i).length;
    }
  });
  const total = hits + misses;
  return { hits, misses, evictions, hitRate: total === 0 ? 0 : Math.round((hits / total) * 1000) / 10 };
}

// ---------------------------------------------------------------------------
// Region scaffolding
// ---------------------------------------------------------------------------

function simSeed(name: string, policy: EvictionPolicy, capacity: number, maxBytes: number, ttlMs: number): RegionSeed {
  return {
    name,
    riskLevel: 'LOW',
    policy,
    capacity,
    entryBytes: 1024,
    maxMemoryBytes: maxBytes,
    defaultTtlMs: ttlMs,
    startFill: 0,
    fillCeiling: 1,
    rps: 0,
    lruRate: 0,
    lfuRate: 0,
    liveOffset: 0,
    hitNoise: 0,
    concentration: 0,
    expirationsPerTick: 0,
    uptimeTicks: 0,
  };
}

interface Recorder {
  region: RegionState;
  ctx: EngineContext;
  startMs: number;
  stepMs: number;
  index: number;
  sampleEvery: number;
  hits: number;
  misses: number;
  puts: number;
  evictions: number;
  expirations: number;
  deltas: TickDelta[];
}

function makeRecorder(
  appId: number,
  ctx: EngineContext,
  name: string,
  kind: SimulationKind,
  policy: EvictionPolicy,
  capacity: number,
  maxBytes: number,
  ttlMs: number,
  now: number,
  total: number,
): Recorder {
  const seed = simSeed(name, policy, capacity, maxBytes, ttlMs);
  const region: RegionState = {
    applicationId: appId,
    seed,
    name,
    riskLevel: 'LOW',
    policy,
    capacity,
    maxMemoryBytes: maxBytes,
    defaultTtlMs: ttlMs,
    counters: emptyCounters(),
    size: 0,
    memoryBytes: 0,
    victimEnabled: false,
    victimSize: 0,
    victimCapacity: 0,
    liveHit: 0,
    shadow: null,
    history: [],
    events: [],
    lastUpdated: now,
    tickCount: 0,
    burstLeft: 0,
    lastPolicyChangeAt: null,
    desired: null,
    tuningVersion: 0,
    updatedBy: null,
    updatedAt: null,
    pendingConfigApplyAtTick: null,
    pendingPolicy: null,
    avgGetLatencyMs: 0.011,
    avgPutLatencyMs: 0.029,
    simulation: kind,
  };
  const windowMs = 30_000;
  return {
    region,
    ctx,
    startMs: now - windowMs,
    stepMs: windowMs / Math.max(1, total),
    index: 0,
    sampleEvery: Math.max(1, Math.round(total / 140)),
    hits: 0,
    misses: 0,
    puts: 0,
    evictions: 0,
    expirations: 0,
    deltas: [],
  };
}

function record(
  rec: Recorder,
  cache: SimCache,
  action: CacheAction,
  key: string,
  reason: string,
  extra: { size?: number; sizeBefore: number; memBefore: number; freq?: number; ageMs?: number; valueReturned: boolean; severity?: 'INFO' | 'WARN' },
): void {
  const r = rec.region;
  const ts = Math.round(rec.startMs + rec.index * rec.stepMs);
  const critical = action !== 'HIT' && action !== 'MISS' && action !== 'PUT';
  if (!critical && rec.index % rec.sampleEvery !== 0) return;
  r.events.push(
    buildEvent(r, rec.ctx, action, {
      reason,
      fingerprint: fakeFingerprint(key),
      frequency: extra.freq ?? 1,
      lastAccessAgeMs: extra.ageMs ?? 0,
      remainingTtlMs: cache.ttlMs ?? 0,
      entrySize: extra.size ?? 0,
      sizeBefore: extra.sizeBefore,
      sizeAfter: cache.size,
      memBefore: extra.memBefore,
      memAfter: cache.memory,
      valueReturned: extra.valueReturned,
      severity: extra.severity ?? 'INFO',
      latencyMs: 0.01,
      timestamp: ts,
    }),
  );
  if (r.events.length > 500) r.events.splice(0, r.events.length - 500);
}

/** One simulated request: get, and on a miss load + put. */
function step(rec: Recorder, cache: SimCache, key: string, size: number, virtualNow: number): void {
  const r = rec.region;
  const c = r.counters;
  const sizeBefore = cache.size;
  const memBefore = cache.memory;
  const outcome = cache.get(key, virtualNow);
  if (outcome === 'hit') {
    rec.hits += 1;
    c.hits += 1;
    record(rec, cache, 'HIT', key, 'Valid entry returned', { sizeBefore, memBefore, valueReturned: true, freq: 2 });
  } else {
    rec.misses += 1;
    c.misses += 1;
    if (outcome === 'expired') {
      rec.expirations += 1;
      c.expirations += 1;
      record(rec, cache, 'EXPIRED', key, `Entry reached its ${formatDuration(cache.ttlMs)} TTL and was removed.`, {
        size,
        sizeBefore,
        memBefore,
        valueReturned: false,
      });
    }
    record(
      rec,
      cache,
      'MISS',
      key,
      outcome === 'expired' ? 'Entry had expired; loaded from the source of truth' : 'Key not present; loaded from the source of truth',
      { sizeBefore, memBefore, valueReturned: false, freq: 0 },
    );
    const before = cache.size;
    const memAt = cache.memory;
    const evicted = cache.put(key, size, virtualNow);
    rec.puts += 1;
    c.puts += 1;
    record(rec, cache, 'PUT', key, 'Stored after loading from the source of truth', {
      size,
      sizeBefore: before,
      memBefore: memAt,
      valueReturned: false,
    });
    for (const ev of evicted) {
      rec.evictions += 1;
      c.evictions += 1;
      if (cache.policy === 'LRU') c.lruEvictions += 1;
      else c.lfuEvictions += 1;
      const action: CacheAction = ev.kind === 'entry' ? 'ENTRY_LIMIT_EVICTED' : 'MEMORY_EVICTED';
      if (ev.kind === 'entry') c.evictionsDueToEntryLimit += 1;
      else c.evictionsDueToMemoryLimit += 1;
      const released = `Released ${formatBytes(ev.size)}.`;
      const lead =
        ev.kind === 'entry'
          ? `Entry limit reached (${cache.capacity});`
          : `Memory limit reached (estimated ${formatBytes(cache.maxBytes ?? 0)});`;
      const why =
        cache.policy === 'LRU'
          ? `${ev.kind === 'entry' ? 'least-recently-used' : 'evicted the least-recently-used'} entry had not been accessed for ${Math.max(1, Math.round(ev.ageMs / 1000))} seconds.`
          : `${ev.kind === 'entry' ? 'least-frequently-used' : 'evicted the least-frequently-used'} entry (accessed ${ev.freq} ${ev.freq === 1 ? 'time' : 'times'}) was removed.`;
      record(rec, cache, action, ev.key, `${lead} ${why} ${released}`, {
        size: ev.size,
        sizeBefore: Math.min(cache.capacity, cache.size + 1),
        memBefore: cache.memory + ev.size,
        freq: ev.freq,
        ageMs: ev.ageMs,
        valueReturned: false,
        severity: ev.kind === 'memory' ? 'WARN' : 'INFO',
      });
    }
  }
  rec.index += 1;
}

function finish(rec: Recorder, now: number): RegionState {
  const r = rec.region;
  r.size = 0;
  r.lastUpdated = now;
  // Spread the run over ~10 three-second buckets so the timeline chart has something to show.
  const buckets = 10;
  const per = (n: number, i: number) => Math.floor((n * (i + 1)) / buckets) - Math.floor((n * i) / buckets);
  for (let i = 0; i < buckets; i += 1) {
    r.history.push({
      t: now - (buckets - 1 - i) * TICK_MS,
      hits: per(rec.hits, i),
      misses: per(rec.misses, i),
      puts: per(rec.puts, i),
      evictions: per(rec.evictions, i),
      expirations: per(rec.expirations, i),
    });
  }
  r.counters.telemetryEventsSent = Math.round(rec.index / 10) + buckets;
  return r;
}

function setFinalState(rec: Recorder, cache: SimCache): void {
  rec.region.size = cache.size;
  rec.region.memoryBytes = cache.memory;
}

// ---------------------------------------------------------------------------
// Simulations
// ---------------------------------------------------------------------------

export interface SimulationRun {
  result: SimulationResult;
  regions: RegionState[];
}

export const DEFAULT_REQUESTS: Record<SimulationKind, number> = {
  'sample-workload': 1000,
  'high-load': 5000,
  'ttl-expiration': 400,
  'policy-comparison': 2000,
};

const pct = (n: number, d: number) => (d === 0 ? 0 : Math.round((n / d) * 1000) / 10);

export function runSimulation(
  kind: SimulationKind,
  applicationId: number,
  requests: number | undefined,
  ctx: EngineContext,
  now: number,
  simulationId: string,
): SimulationRun {
  const n = requests ?? DEFAULT_REQUESTS[kind];
  const rng = ctx.rng;
  const started = performance.now();
  const steps: SimulationStep[] = [];
  const regions: RegionState[] = [];
  let summary = '';
  let comparison: SimulationComparisonRow[] | null = null;
  let totals = { hits: 0, misses: 0, evictions: 0, expirations: 0 };

  if (kind === 'sample-workload') {
    const rec = makeRecorder(applicationId, ctx, 'sim-sample-workload', kind, 'LRU', 120, 8 * 1024 * 1024, 60_000, now, n);
    const cache = new SimCache('LRU', 120, rec.region.maxMemoryBytes, 60_000);
    for (let i = 0; i < n; i += 1) {
      const hot = rng.chance(0.7);
      const key = hot ? `sample-hot-${Math.min(59, Math.floor(rng.float() ** 2 * 60))}` : `sample-cold-${rng.int(0, 3000)}`;
      step(rec, cache, key, rng.int(800, 2400), i * 40);
    }
    setFinalState(rec, cache);
    regions.push(finish(rec, now));
    steps.push(
      { name: 'Warm-up', description: 'Load synthetic keys on demand', detail: `${n} requests against a 120-entry LRU region` },
      { name: 'Hot set', description: '70% of requests target about 60 popular keys', detail: 'Popular keys stay resident; cold one-off keys churn the rest' },
    );
    totals = { hits: rec.hits, misses: rec.misses, evictions: rec.evictions, expirations: rec.expirations };
    summary = `Sample workload finished: ${pct(rec.hits, n)}% hit rate over ${n} requests with ${rec.evictions} evictions.`;
  } else if (kind === 'high-load') {
    const half = Math.floor(n / 2);
    const limit = 192 * 1024;
    const rec = makeRecorder(applicationId, ctx, 'sim-high-load', kind, 'LRU', 20, limit, 120_000, now, n);
    const cache = new SimCache('LRU', 20, limit, 120_000);
    for (let i = 0; i < half; i += 1) step(rec, cache, `load-d-${i}`, 512, i * 10);
    const afterD = { evictions: rec.evictions, entry: rec.region.counters.evictionsDueToEntryLimit };
    for (let i = half; i < n; i += 1) step(rec, cache, `load-e-${i}`, rng.int(12_000, 22_000), i * 10);
    setFinalState(rec, cache);
    regions.push(finish(rec, now));
    steps.push(
      {
        name: 'Pattern D — capacity eviction',
        description: 'Unique small entries exceed the 20-entry limit',
        detail: `${afterD.entry} entry-limit evictions`,
      },
      {
        name: 'Pattern E — memory eviction',
        description: `Large entries hit the ${formatBytes(limit)} estimated memory limit before the entry limit`,
        detail: `${rec.region.counters.evictionsDueToMemoryLimit} memory-limit evictions`,
      },
    );
    totals = { hits: rec.hits, misses: rec.misses, evictions: rec.evictions, expirations: rec.expirations };
    summary = `High-load run triggered ${rec.region.counters.evictionsDueToEntryLimit} entry-limit and ${rec.region.counters.evictionsDueToMemoryLimit} memory-limit evictions.`;
  } else if (kind === 'ttl-expiration') {
    const ttl = 2000;
    const rec = makeRecorder(applicationId, ctx, 'sim-ttl-expiration', kind, 'LRU', 500, 8 * 1024 * 1024, ttl, now, n);
    const cache = new SimCache('LRU', 500, rec.region.maxMemoryBytes, ttl);
    let clock = 0;
    for (let i = 0; i < n; i += 1) {
      clock += rng.int(20, 120);
      const hot = rng.chance(0.35);
      const key = hot ? `ttl-hot-${rng.int(0, 4)}` : `ttl-key-${i % 40}`;
      step(rec, cache, key, 1024, clock);
    }
    setFinalState(rec, cache);
    regions.push(finish(rec, now));
    steps.push({
      name: 'Pattern C — TTL expiry',
      description: `Entries live for ${formatDuration(ttl)}; most keys are revisited after their TTL`,
      detail: `${rec.expirations} expirations, each followed by a MISS and a reload`,
    });
    totals = { hits: rec.hits, misses: rec.misses, evictions: rec.evictions, expirations: rec.expirations };
    summary = `TTL run produced ${rec.expirations} expirations and ${rec.misses} misses (expired entries are reloaded from the source).`;
  } else {
    const half = Math.floor(n / 2);
    const streamA = patternAStream(half, rng);
    const streamB = patternBStream(n - half, rng);
    const cap = 8;
    const aLru = runStream('LRU', cap, streamA);
    const aLfu = runStream('LFU', cap, streamA);
    const bLru = runStream('LRU', cap, streamB);
    const bLfu = runStream('LFU', cap, streamB);
    const winner = (lru: number, lfu: number): string => (lru > lfu ? 'LRU' : lfu > lru ? 'LFU' : 'TIE');
    comparison = [
      {
        pattern: 'A — LRU-friendly changing access',
        lruHitRate: aLru.hitRate,
        lfuHitRate: aLfu.hitRate,
        winner: winner(aLru.hitRate, aLfu.hitRate),
        interpretation:
          aLru.hitRate >= aLfu.hitRate
            ? 'Recently accessed keys matter most; LRU keeps the moving working set.'
            : 'Old popular keys still pay off here; LFU held them.',
      },
      {
        pattern: 'B — LFU-friendly stable popularity',
        lruHitRate: bLru.hitRate,
        lfuHitRate: bLfu.hitRate,
        winner: winner(bLru.hitRate, bLfu.hitRate),
        interpretation:
          bLfu.hitRate >= bLru.hitRate
            ? 'A few keys are consistently popular; LFU protects them from one-off scans.'
            : 'Scans did not hurt LRU enough to matter at this size.',
      },
    ];
    const regionLru = makeRecorder(applicationId, ctx, 'sim-policy-lru', kind, 'LRU', cap, 8 * 1024 * 1024, 120_000, now, n);
    const regionLfu = makeRecorder(applicationId, ctx, 'sim-policy-lfu', kind, 'LFU', cap, 8 * 1024 * 1024, 120_000, now, n);
    for (const [rec, policy] of [
      [regionLru, 'LRU'],
      [regionLfu, 'LFU'],
    ] as const) {
      const cache = new SimCache(policy, cap, rec.region.maxMemoryBytes, 120_000);
      [...streamA, ...streamB].forEach((key, i) => step(rec, cache, key, 1024, i));
      setFinalState(rec, cache);
      regions.push(finish(rec, now));
    }
    steps.push(
      {
        name: 'Pattern A',
        description: 'LRU-friendly changing access',
        detail: `A,B,C,A,D,E,D,E,F,G style moving set → LRU ${aLru.hitRate.toFixed(1)}% / LFU ${aLfu.hitRate.toFixed(1)}%`,
      },
      {
        name: 'Pattern B',
        description: 'LFU-friendly stable popularity',
        detail: `A,A,A,A,B,B,B,C,D,E,A,B style hot keys plus scans → LRU ${bLru.hitRate.toFixed(1)}% / LFU ${bLfu.hitRate.toFixed(1)}%`,
      },
    );
    const lfuRec = regionLfu.region.counters;
    const lruRec = regionLru.region.counters;
    totals = {
      hits: lfuRec.hits + lruRec.hits,
      misses: lfuRec.misses + lruRec.misses,
      evictions: lfuRec.evictions + lruRec.evictions,
      expirations: 0,
    };
    const a = comparison[0] as SimulationComparisonRow;
    const b = comparison[1] as SimulationComparisonRow;
    summary = `${b.winner} wins the stable-popularity pattern by ${Math.abs(b.lfuHitRate - b.lruHitRate).toFixed(1)} pts; ${a.winner} wins the changing pattern by ${Math.abs(a.lruHitRate - a.lfuHitRate).toFixed(1)} pts.`;
  }

  const total = totals.hits + totals.misses;
  return {
    regions,
    result: {
      simulationId,
      kind,
      applicationId,
      cacheRegions: regions.map((r) => r.name),
      startedAt: new Date(now).toISOString(),
      durationMs: Math.max(1, Math.round(performance.now() - started)),
      summary,
      steps,
      hits: totals.hits,
      misses: totals.misses,
      hitRate: pct(totals.hits, total),
      evictions: totals.evictions,
      expirations: totals.expirations,
      comparison,
    },
  };
}

/**
 * The evolving part of the mock: turns a RegionSeed into a region whose counters grow on every
 * tick, emits Eviction X-ray events and aggregates everything into contract-shaped objects.
 */
import type {
  CacheAction,
  CacheEvent,
  EventSeverity,
  EvictionPolicy,
  MetricTotals,
  Recommendation,
  RecentWindow,
  RegionMetrics,
  Timeline,
  TimelinePoint,
} from '../types';
import { formatBytes, formatDuration, formatInt } from '../../lib/format';
import { evaluateHealth, type HealthEvaluation } from './health';
import {
  COOLDOWN_MS,
  EVENT_LIMIT,
  HISTORY_LIMIT,
  TICK_MS,
  emptyCounters,
  type AppRecord,
  type RegionSeed,
  type RegionState,
  type TickDelta,
} from './model';
import { Rng, fakeFingerprint } from './prng';

export interface EngineContext {
  rng: Rng;
  nextEventId: () => number;
  app: Pick<AppRecord, 'name' | 'environment'>;
}

const round1 = (v: number) => Math.round(v * 10) / 10;
const round2 = (v: number) => Math.round(v * 100) / 100;
const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));

export function shadowRateFor(r: Pick<RegionState, 'shadow' | 'policy'>, policy: EvictionPolicy): number {
  if (!r.shadow) return 0;
  return policy === 'LRU' ? r.shadow.lru : r.shadow.lfu;
}

// ---------------------------------------------------------------------------
// Region creation
// ---------------------------------------------------------------------------

export function createRegion(applicationId: number, seed: RegionSeed, now: number): RegionState {
  const c = emptyCounters();
  const live = (seed.policy === 'LRU' ? seed.lruRate : seed.lfuRate) + seed.liveOffset;
  const requests0 = Math.round(seed.rps * 3 * seed.uptimeTicks);
  c.hits = Math.round((requests0 * live) / 100);
  c.misses = requests0 - c.hits;
  c.puts = Math.round(c.misses * 0.96);
  c.removes = Math.round(c.puts * 0.01);
  const pressured = seed.fillCeiling >= 1;
  c.evictions = pressured ? Math.round(c.puts * 0.55) : Math.round(c.puts * 0.012);
  c.evictionsDueToEntryLimit = Math.round(c.evictions * 0.9);
  c.evictionsDueToMemoryLimit = c.evictions - c.evictionsDueToEntryLimit;
  if (seed.policy === 'LRU') c.lruEvictions = c.evictions;
  else c.lfuEvictions = c.evictions;
  c.expirations = Math.round(seed.expirationsPerTick * seed.uptimeTicks * 0.9);
  c.victimHits = seed.victim ? Math.round(c.hits * seed.victim.hitShare) : 0;
  c.victimEvictions = seed.victim ? Math.round(c.evictions * 0.2) : 0;
  if (seed.stampede) {
    c.refreshesStarted = Math.round(seed.uptimeTicks * 1.1);
    c.concurrentRequestsCoalesced = c.refreshesStarted * 14;
  }
  c.telemetryEventsSent = seed.uptimeTicks * 3;

  const size = Math.round(seed.capacity * seed.startFill);
  return {
    applicationId,
    seed,
    name: seed.name,
    riskLevel: seed.riskLevel,
    policy: seed.policy,
    capacity: seed.capacity,
    maxMemoryBytes: seed.maxMemoryBytes,
    defaultTtlMs: seed.defaultTtlMs,
    counters: c,
    size,
    memoryBytes: size * seed.entryBytes,
    victimEnabled: Boolean(seed.victim),
    victimSize: seed.victim ? Math.round(seed.victim.capacity * 0.4) : 0,
    victimCapacity: seed.victim?.capacity ?? 0,
    liveHit: live,
    shadow: {
      lru: seed.lruRate,
      lfu: seed.lfuRate,
      concentration: seed.concentration,
      requests: requests0,
    },
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
    avgGetLatencyMs: 0.012,
    avgPutLatencyMs: 0.031,
    simulation: null,
  };
}

// ---------------------------------------------------------------------------
// Events
// ---------------------------------------------------------------------------

interface EventOptions {
  severity?: EventSeverity;
  reason: string;
  frequency?: number;
  lastAccessAgeMs?: number;
  remainingTtlMs?: number;
  entrySize?: number;
  sizeBefore: number;
  sizeAfter: number;
  memBefore: number;
  memAfter: number;
  valueReturned: boolean;
  latencyMs: number;
  fingerprint?: string | null;
  timestamp: number;
  policy?: EvictionPolicy;
}

export function buildEvent(
  r: Pick<RegionState, 'name' | 'policy'>,
  ctx: EngineContext,
  action: CacheAction,
  o: EventOptions,
): CacheEvent {
  return {
    id: ctx.nextEventId(),
    timestamp: new Date(o.timestamp).toISOString(),
    applicationName: ctx.app.name,
    environment: ctx.app.environment,
    cacheRegion: r.name,
    action,
    keyFingerprint: o.fingerprint === undefined ? fakeFingerprint(`${r.name}:${ctx.rng.hex(8)}`) : o.fingerprint,
    reason: o.reason,
    policy: o.policy ?? r.policy,
    frequency: o.frequency ?? 1,
    lastAccessAgeMs: o.lastAccessAgeMs ?? 0,
    remainingTtlMs: o.remainingTtlMs ?? 0,
    estimatedEntrySizeBytes: o.entrySize ?? 0,
    cacheSizeBefore: o.sizeBefore,
    cacheSizeAfter: o.sizeAfter,
    memoryBeforeBytes: o.memBefore,
    memoryAfterBytes: o.memAfter,
    valueReturned: o.valueReturned,
    severity: o.severity ?? 'INFO',
    latencyMs: o.latencyMs,
  };
}

export function pushEvent(r: RegionState, event: CacheEvent): void {
  r.events.push(event);
  if (r.events.length > EVENT_LIMIT) r.events.splice(0, r.events.length - EVENT_LIMIT);
}

function evictionReason(
  kind: 'entry' | 'memory',
  r: RegionState,
  age: number,
  freq: number,
  entrySize: number,
): string {
  const released = `Released ${formatBytes(entrySize)}.`;
  if (kind === 'entry') {
    const lead = `Entry limit reached (${formatInt(r.capacity)});`;
    return r.policy === 'LRU'
      ? `${lead} least-recently-used entry had not been accessed for ${Math.round(age / 1000)} seconds. ${released}`
      : `${lead} least-frequently-used entry (accessed ${freq} ${freq === 1 ? 'time' : 'times'}) was evicted. ${released}`;
  }
  const lead = `Memory limit reached (estimated ${formatBytes(r.maxMemoryBytes)});`;
  return r.policy === 'LRU'
    ? `${lead} evicted the least-recently-used entry (idle ${Math.round(age / 1000)} seconds) to stay under the limit. ${released}`
    : `${lead} evicted the least-frequently-used entry (frequency ${freq}) to stay under the limit. ${released}`;
}

interface TickDeltas {
  hits: number;
  misses: number;
  puts: number;
  evictEntry: number;
  evictMem: number;
  expirations: number;
  refreshes: number;
  coalesced: number;
  burst: boolean;
}

function emitTickEvents(r: RegionState, ctx: EngineContext, now: number, d: TickDeltas): void {
  const { rng } = ctx;
  const ts = () => now - rng.int(0, TICK_MS - 1);
  const size = r.size;
  const mem = r.memoryBytes;
  const entrySize = () => Math.round(r.seed.entryBytes * rng.between(0.5, 1.6));
  const ttl = () => Math.round(r.defaultTtlMs * rng.between(0.05, 1));
  const pressureSeverity: EventSeverity = d.burst || mem / r.maxMemoryBytes >= 0.9 ? 'WARN' : 'INFO';

  const hitEvents = Math.min(d.hits, rng.int(0, 2));
  for (let i = 0; i < hitEvents; i += 1) {
    const victim = r.victimEnabled && rng.chance(0.15);
    const action: CacheAction = victim ? 'VICTIM_HIT' : 'HIT';
    pushEvent(
      r,
      buildEvent(r, ctx, action, {
        reason: victim ? 'Entry promoted from the victim layer back to L1' : 'Valid entry returned',
        frequency: rng.int(2, 40),
        lastAccessAgeMs: rng.int(50, 20_000),
        remainingTtlMs: ttl(),
        entrySize: entrySize(),
        sizeBefore: size,
        sizeAfter: size,
        memBefore: mem,
        memAfter: mem,
        valueReturned: true,
        latencyMs: round2(rng.between(0.004, 0.05)),
        timestamp: ts(),
      }),
    );
  }

  const missEvents = Math.min(d.misses, rng.int(0, 2));
  for (let i = 0; i < missEvents; i += 1) {
    const expired = rng.chance(0.2);
    pushEvent(
      r,
      buildEvent(r, ctx, 'MISS', {
        reason: expired ? 'Entry had expired; loaded from the source of truth' : 'Key not present; loaded from the source of truth',
        frequency: 0,
        sizeBefore: size,
        sizeAfter: size,
        memBefore: mem,
        memAfter: mem,
        valueReturned: false,
        latencyMs: round2(rng.between(0.004, 0.03)),
        timestamp: ts(),
      }),
    );
  }

  if (d.puts > 0 && rng.chance(0.7)) {
    const es = entrySize();
    pushEvent(
      r,
      buildEvent(r, ctx, 'PUT', {
        reason: 'Stored after loading from the source of truth',
        frequency: 1,
        remainingTtlMs: r.defaultTtlMs,
        entrySize: es,
        sizeBefore: Math.max(0, size - 1),
        sizeAfter: size,
        memBefore: Math.max(0, mem - es),
        memAfter: mem,
        valueReturned: false,
        latencyMs: round2(rng.between(0.01, 0.08)),
        timestamp: ts(),
      }),
    );
  }

  const expirationEvents = Math.min(d.expirations, rng.int(0, 2));
  for (let i = 0; i < expirationEvents; i += 1) {
    const es = entrySize();
    pushEvent(
      r,
      buildEvent(r, ctx, 'EXPIRED', {
        reason: `Entry reached its ${formatDuration(r.defaultTtlMs)} TTL and was removed.`,
        frequency: rng.int(1, 25),
        lastAccessAgeMs: rng.int(1_000, r.defaultTtlMs),
        remainingTtlMs: 0,
        entrySize: es,
        sizeBefore: size + 1,
        sizeAfter: size,
        memBefore: mem + es,
        memAfter: mem,
        valueReturned: false,
        latencyMs: 0.01,
        timestamp: ts(),
      }),
    );
  }

  const entryEvents = Math.min(d.evictEntry, d.burst ? 4 : rng.int(1, 3));
  for (let i = 0; i < entryEvents; i += 1) {
    const es = entrySize();
    const age = rng.int(2_000, 240_000);
    const freq = rng.int(1, 6);
    pushEvent(
      r,
      buildEvent(r, ctx, 'ENTRY_LIMIT_EVICTED', {
        severity: pressureSeverity,
        reason: evictionReason('entry', r, age, freq, es),
        frequency: freq,
        lastAccessAgeMs: age,
        remainingTtlMs: ttl(),
        entrySize: es,
        sizeBefore: Math.min(r.capacity, size + 1),
        sizeAfter: size,
        memBefore: mem + es,
        memAfter: mem,
        valueReturned: false,
        latencyMs: round2(rng.between(0.01, 0.06)),
        timestamp: ts(),
      }),
    );
  }

  const memEvents = Math.min(d.evictMem, rng.int(1, 2));
  for (let i = 0; i < memEvents; i += 1) {
    const es = entrySize();
    const age = rng.int(2_000, 240_000);
    const freq = rng.int(1, 6);
    pushEvent(
      r,
      buildEvent(r, ctx, 'MEMORY_EVICTED', {
        severity: 'WARN',
        reason: evictionReason('memory', r, age, freq, es),
        frequency: freq,
        lastAccessAgeMs: age,
        remainingTtlMs: ttl(),
        entrySize: es,
        sizeBefore: size + 1,
        sizeAfter: size,
        memBefore: mem + es,
        memAfter: mem,
        valueReturned: false,
        latencyMs: round2(rng.between(0.01, 0.06)),
        timestamp: ts(),
      }),
    );
  }

  if (d.refreshes > 0) {
    pushEvent(
      r,
      buildEvent(r, ctx, 'REFRESH_STARTED', {
        reason: 'Single refresh started for an expired hot entry; other callers wait for it.',
        frequency: rng.int(5, 60),
        remainingTtlMs: 0,
        sizeBefore: size,
        sizeAfter: size,
        memBefore: mem,
        memAfter: mem,
        valueReturned: false,
        latencyMs: round2(rng.between(2, 14)),
        timestamp: ts(),
      }),
    );
  }
  if (d.coalesced > 0) {
    pushEvent(
      r,
      buildEvent(r, ctx, 'REFRESH_COALESCED', {
        reason: `${d.coalesced} concurrent requests were coalesced into one source call (stampede shield).`,
        frequency: rng.int(5, 60),
        sizeBefore: size,
        sizeAfter: size,
        memBefore: mem,
        memAfter: mem,
        valueReturned: true,
        latencyMs: round2(rng.between(0.01, 0.2)),
        timestamp: ts(),
      }),
    );
  }
}

// ---------------------------------------------------------------------------
// Tick
// ---------------------------------------------------------------------------

export function tickRegion(r: RegionState, ctx: EngineContext, now: number): TickDelta {
  const { rng } = ctx;
  const s = r.seed;
  r.tickCount += 1;

  // Shadow rates drift around their base values; the live rate eases toward the active policy's rate.
  if (r.shadow) {
    r.shadow.lru = clamp(r.shadow.lru + (s.lruRate - r.shadow.lru) * 0.1 + rng.gauss() * 0.12, 1, 99.5);
    r.shadow.lfu = clamp(r.shadow.lfu + (s.lfuRate - r.shadow.lfu) * 0.1 + rng.gauss() * 0.12, 1, 99.5);
    r.shadow.concentration = clamp(r.shadow.concentration + rng.gauss() * 0.15, 5, 99);
  }
  const target = (r.policy === 'LRU' ? s.lruRate : s.lfuRate) + s.liveOffset;
  r.liveHit = clamp(r.liveHit + (target - r.liveHit) * 0.08 + rng.gauss() * s.hitNoise * 0.25, 1, 99.5);
  const rateNow = clamp(r.liveHit + rng.gauss() * s.hitNoise * 0.6, 1, 99.8);

  const requests = Math.max(1, Math.round(s.rps * 3 * rng.between(0.8, 1.2)));
  let hits = Math.round((requests * rateNow) / 100);
  let misses = requests - hits;

  // Deterministic eviction burst (cold-start style traffic surge: extra misses, puts and evictions).
  if (s.burstEvery && (r.tickCount + (s.burstOffset ?? 0)) % s.burstEvery === 0) r.burstLeft = 3;
  let burstEvictions = 0;
  if (r.burstLeft > 0) {
    burstEvictions = Math.round(((s.burstSize ?? 30) / 3) * rng.between(0.7, 1.3));
    misses += burstEvictions;
    r.burstLeft -= 1;
  }
  hits = Math.max(0, hits);

  const puts = Math.round(misses * 0.96);
  const expirations = Math.max(0, Math.round(s.expirationsPerTick * rng.between(0.4, 1.6)));
  const removes = rng.chance(0.15) ? 1 : 0;

  let size = r.size + puts - expirations - removes;
  let evictEntry = 0;
  let evictMem = 0;
  if (size > r.capacity) {
    evictEntry = size - r.capacity;
    size = r.capacity;
  } else if (s.fillCeiling < 1) {
    const ceiling = Math.floor(r.capacity * s.fillCeiling);
    if (size > ceiling) {
      r.counters.removes += size - ceiling;
      size = ceiling;
    }
  }
  if (burstEvictions > 0 && s.fillCeiling < 1) {
    // bursts on relaxed regions still push entries out
    evictEntry += burstEvictions;
    size = Math.max(0, size - burstEvictions);
  }
  let memory = size * s.entryBytes * rng.between(0.985, 1.015);
  if (memory > r.maxMemoryBytes * 0.985) {
    const target97 = r.maxMemoryBytes * 0.965;
    evictMem = Math.max(1, Math.ceil((memory - target97) / s.entryBytes));
    size = Math.max(0, size - evictMem);
    memory = size * s.entryBytes;
  }
  size = Math.max(0, size);

  const evictions = evictEntry + evictMem;
  const c = r.counters;
  c.hits += hits;
  c.misses += misses;
  c.puts += puts;
  c.removes += removes;
  c.expirations += expirations;
  c.evictions += evictions;
  c.evictionsDueToEntryLimit += evictEntry;
  c.evictionsDueToMemoryLimit += evictMem;
  if (r.policy === 'LRU') c.lruEvictions += evictions;
  else c.lfuEvictions += evictions;
  let refreshes = 0;
  let coalesced = 0;
  if (r.victimEnabled && s.victim) {
    c.victimHits += Math.round(hits * s.victim.hitShare * rng.between(0.6, 1.4));
    c.victimEvictions += Math.round(evictions * 0.2);
    r.victimSize = clamp(Math.round(r.victimSize + rng.gauss() * 4), 0, r.victimCapacity);
  }
  if (s.stampede) {
    refreshes = rng.chance(0.55) ? rng.int(1, 2) : 0;
    coalesced = refreshes > 0 ? rng.int(4, 30) : 0;
    c.refreshesStarted += refreshes;
    c.concurrentRequestsCoalesced += coalesced;
  }
  c.telemetryEventsSent += 3;

  r.size = size;
  r.memoryBytes = Math.round(memory);
  r.avgGetLatencyMs = round2(0.012 + rng.gauss() * 0.0008) || 0.012;
  r.avgPutLatencyMs = round2(0.031 + rng.gauss() * 0.002) || 0.031;
  if (r.shadow) r.shadow.requests += requests;
  r.lastUpdated = now;

  emitTickEvents(r, ctx, now, {
    hits,
    misses,
    puts,
    evictEntry,
    evictMem,
    expirations,
    refreshes,
    coalesced,
    burst: burstEvictions > 0,
  });

  const delta: TickDelta = { t: now, hits, misses, puts, evictions, expirations };
  r.history.push(delta);
  if (r.history.length > HISTORY_LIMIT) r.history.splice(0, r.history.length - HISTORY_LIMIT);
  return delta;
}

// ---------------------------------------------------------------------------
// Aggregation
// ---------------------------------------------------------------------------

function percent(part: number, whole: number): number {
  return whole > 0 ? round2((part / whole) * 100) : 0;
}

export function totalsOfRegion(r: RegionState): MetricTotals {
  const c = r.counters;
  const requests = c.hits + c.misses;
  const victimHits = Math.min(c.victimHits, c.hits);
  return {
    hits: c.hits,
    misses: c.misses,
    hitRate: percent(c.hits, requests),
    missRate: percent(c.misses, requests),
    puts: c.puts,
    removes: c.removes,
    clears: c.clears,
    evictions: c.evictions,
    expirations: c.expirations,
    size: r.size,
    capacity: r.capacity,
    estimatedMemoryUsageBytes: r.memoryBytes,
    maximumMemoryBytes: r.maxMemoryBytes,
    memoryUtilizationPercent: percent(r.memoryBytes, r.maxMemoryBytes),
    lruEvictions: c.lruEvictions,
    lfuEvictions: c.lfuEvictions,
    evictionsDueToEntryLimit: c.evictionsDueToEntryLimit,
    evictionsDueToMemoryLimit: c.evictionsDueToMemoryLimit,
    averageGetLatencyMs: r.avgGetLatencyMs,
    averagePutLatencyMs: r.avgPutLatencyMs,
    sourceCallsAvoided: c.hits,
    telemetryEventsSent: c.telemetryEventsSent,
    telemetryEventsFailed: c.telemetryEventsFailed,
    l1Hits: c.hits - victimHits,
    victimHits,
    sourceMisses: c.misses,
    victimEvictions: c.victimEvictions,
    overallHitRate: percent(c.hits, requests),
    refreshesStarted: c.refreshesStarted,
    concurrentRequestsCoalesced: c.concurrentRequestsCoalesced,
    sourceCallsAvoidedByStampedeShield: c.concurrentRequestsCoalesced,
    refreshFailures: c.refreshFailures,
    staleServed: c.staleServed,
    staleCorrections: c.staleCorrections,
  };
}

export function emptyTotals(): MetricTotals {
  return totalsOfRegion({
    counters: emptyCounters(),
    size: 0,
    capacity: 0,
    memoryBytes: 0,
    maxMemoryBytes: 0,
    avgGetLatencyMs: 0,
    avgPutLatencyMs: 0,
  } as RegionState);
}

/** Sum MetricTotals (as the service does across instances / regions); latencies weighted by op counts. */
export function sumTotals(list: MetricTotals[]): MetricTotals {
  if (list.length === 0) return emptyTotals();
  const sum = (pick: (t: MetricTotals) => number) => list.reduce((acc, t) => acc + pick(t), 0);
  const hits = sum((t) => t.hits);
  const misses = sum((t) => t.misses);
  const requests = hits + misses;
  const puts = sum((t) => t.puts);
  const mem = sum((t) => t.estimatedMemoryUsageBytes);
  const maxMem = sum((t) => t.maximumMemoryBytes);
  const l1 = sum((t) => t.l1Hits);
  const victim = sum((t) => t.victimHits);
  const weighted = (pick: (t: MetricTotals) => number, weight: (t: MetricTotals) => number) => {
    const w = list.reduce((acc, t) => acc + weight(t), 0);
    if (w === 0) return 0;
    return list.reduce((acc, t) => acc + pick(t) * weight(t), 0) / w;
  };
  return {
    hits,
    misses,
    hitRate: percent(hits, requests),
    missRate: percent(misses, requests),
    puts,
    removes: sum((t) => t.removes),
    clears: sum((t) => t.clears),
    evictions: sum((t) => t.evictions),
    expirations: sum((t) => t.expirations),
    size: sum((t) => t.size),
    capacity: sum((t) => t.capacity),
    estimatedMemoryUsageBytes: mem,
    maximumMemoryBytes: maxMem,
    memoryUtilizationPercent: percent(mem, maxMem),
    lruEvictions: sum((t) => t.lruEvictions),
    lfuEvictions: sum((t) => t.lfuEvictions),
    evictionsDueToEntryLimit: sum((t) => t.evictionsDueToEntryLimit),
    evictionsDueToMemoryLimit: sum((t) => t.evictionsDueToMemoryLimit),
    averageGetLatencyMs: round2(weighted((t) => t.averageGetLatencyMs, (t) => t.hits + t.misses) * 1000) / 1000,
    averagePutLatencyMs: round2(weighted((t) => t.averagePutLatencyMs, (t) => t.puts) * 1000) / 1000,
    sourceCallsAvoided: sum((t) => t.sourceCallsAvoided),
    telemetryEventsSent: sum((t) => t.telemetryEventsSent),
    telemetryEventsFailed: sum((t) => t.telemetryEventsFailed),
    l1Hits: l1,
    victimHits: victim,
    sourceMisses: sum((t) => t.sourceMisses),
    victimEvictions: sum((t) => t.victimEvictions),
    overallHitRate: percent(l1 + victim, requests),
    refreshesStarted: sum((t) => t.refreshesStarted),
    concurrentRequestsCoalesced: sum((t) => t.concurrentRequestsCoalesced),
    sourceCallsAvoidedByStampedeShield: sum((t) => t.sourceCallsAvoidedByStampedeShield),
    refreshFailures: sum((t) => t.refreshFailures),
    staleServed: sum((t) => t.staleServed),
    staleCorrections: sum((t) => t.staleCorrections),
  };
}

export function recentWindowOf(r: RegionState, now: number, minutes = 10): RecentWindow {
  const from = now - minutes * 60_000;
  const w: RecentWindow = { minutes, hits: 0, misses: 0, puts: 0, evictions: 0, expirations: 0 };
  for (let i = r.history.length - 1; i >= 0; i -= 1) {
    const d = r.history[i] as TickDelta;
    if (d.t < from) break;
    w.hits += d.hits;
    w.misses += d.misses;
    w.puts += d.puts;
    w.evictions += d.evictions;
    w.expirations += d.expirations;
  }
  return w;
}

export function healthOfRegion(r: RegionState, now: number): HealthEvaluation {
  const totals = totalsOfRegion(r);
  const window = recentWindowOf(r, now);
  return evaluateHealth({
    requests: totals.hits + totals.misses,
    hitRate: totals.hitRate,
    memoryUtilizationPercent: totals.memoryUtilizationPercent,
    recentEvictions: window.evictions,
    recentMinutes: window.minutes,
    telemetryEventsFailed: totals.telemetryEventsFailed,
  });
}

export function regionMetrics(r: RegionState, app: AppRecord, now: number): RegionMetrics {
  const totals = totalsOfRegion(r);
  return {
    ...totals,
    applicationId: app.id,
    applicationName: app.name,
    environment: app.environment,
    cacheRegion: r.name,
    riskLevel: r.riskLevel,
    activePolicy: r.policy,
    defaultTtlMs: r.defaultTtlMs,
    victimEnabled: r.victimEnabled,
    victimSize: r.victimSize,
    victimCapacity: r.victimCapacity,
    recentWindow: recentWindowOf(r, now),
    shadow: r.shadow
      ? {
          requests: r.shadow.requests,
          windowRequests: Math.min(1000, r.shadow.requests),
          lruHitRate: round1(r.shadow.lru),
          lfuHitRate: round1(r.shadow.lfu),
          topKeyConcentrationPercent: round1(r.shadow.concentration),
        }
      : null,
    health: healthOfRegion(r, now).health,
    instanceCount: 1,
    lastUpdated: new Date(r.lastUpdated).toISOString(),
  };
}

// ---------------------------------------------------------------------------
// Timeline
// ---------------------------------------------------------------------------

export function buildTimeline(
  histories: TickDelta[][],
  now: number,
  minutes: number,
  bucketSeconds: number,
  cacheRegion: string | null,
): Timeline {
  const bucketMs = bucketSeconds * 1000;
  const count = Math.max(1, Math.ceil((minutes * 60) / bucketSeconds));
  const end = Math.floor(now / bucketMs) * bucketMs;
  const start = end - (count - 1) * bucketMs;
  const points: TimelinePoint[] = [];
  for (let i = 0; i < count; i += 1) {
    points.push({
      bucketStart: new Date(start + i * bucketMs).toISOString(),
      hits: 0,
      misses: 0,
      puts: 0,
      evictions: 0,
      expirations: 0,
      hitRate: 0,
    });
  }
  // Each tick covers (t - TICK_MS, t]; split its deltas across the buckets it overlaps so a 10 s bucket
  // does not alternate between holding three and four ticks.
  const acc = points.map(() => ({ hits: 0, misses: 0, puts: 0, evictions: 0, expirations: 0 }));
  for (const history of histories) {
    for (let i = history.length - 1; i >= 0; i -= 1) {
      const d = history[i] as TickDelta;
      if (d.t < start) break;
      const spanStart = d.t - TICK_MS;
      const first = Math.floor((spanStart - start) / bucketMs);
      const last = Math.floor((d.t - 1 - start) / bucketMs);
      for (let b = first; b <= last; b += 1) {
        const a = acc[b];
        if (!a) continue;
        const bs = start + b * bucketMs;
        const overlap = Math.max(0, Math.min(bs + bucketMs, d.t) - Math.max(bs, spanStart));
        const f = overlap / TICK_MS;
        a.hits += d.hits * f;
        a.misses += d.misses * f;
        a.puts += d.puts * f;
        a.evictions += d.evictions * f;
        a.expirations += d.expirations * f;
      }
    }
  }
  points.forEach((p, i) => {
    const a = acc[i] as (typeof acc)[number];
    p.hits = Math.round(a.hits);
    p.misses = Math.round(a.misses);
    p.puts = Math.round(a.puts);
    p.evictions = Math.round(a.evictions);
    p.expirations = Math.round(a.expirations);
  });
  for (const p of points) p.hitRate = percent(p.hits, p.hits + p.misses);
  return { cacheRegion, bucketSeconds, points };
}

// ---------------------------------------------------------------------------
// Recommendation engine (deterministic, mirrors the contract rules)
// ---------------------------------------------------------------------------

export const MIN_SAMPLE = 100;
export const MIN_IMPROVEMENT_POINTS = 5;

export function computeRecommendation(
  app: Pick<AppRecord, 'id' | 'name'>,
  r: RegionState,
  now: number,
  id: number,
  createdAt: number,
  pendingRequestId: number | null,
): Recommendation | null {
  if (!r.shadow) return null;
  const current = r.policy;
  const alt: EvictionPolicy = current === 'LRU' ? 'LFU' : 'LRU';
  const curRate = shadowRateFor(r, current);
  const altRate = shadowRateFor(r, alt);
  const improvement = round1(altRate - curRate);
  const sample = Math.min(1000, r.shadow.requests);
  const minimumSampleMet = sample >= MIN_SAMPLE;
  const cooldownActive = r.lastPolicyChangeAt !== null && now - r.lastPolicyChangeAt < COOLDOWN_MS;
  const cooldownEndsAt =
    cooldownActive && r.lastPolicyChangeAt !== null
      ? new Date(r.lastPolicyChangeAt + COOLDOWN_MS).toISOString()
      : null;
  const switchNow = minimumSampleMet && improvement >= MIN_IMPROVEMENT_POINTS && !cooldownActive;

  let reason: string;
  if (switchNow) {
    reason =
      alt === 'LFU'
        ? `A small stable set of keys dominates repeated requests (top 20% of keys receive ${round1(r.shadow.concentration)}% of requests); LFU retains them.`
        : 'Access has shifted to a changing working set; recency matters more than frequency, so LRU keeps the recently used entries.';
  } else if (!minimumSampleMet) {
    reason = `Not enough samples yet (${sample} of ${MIN_SAMPLE} required) to compare policies reliably.`;
  } else if (improvement >= MIN_IMPROVEMENT_POINTS && cooldownActive) {
    reason = `${alt} would gain ${improvement.toFixed(1)} points, but a policy change was applied recently; waiting for the cooldown to end.`;
  } else if (improvement > 0) {
    reason = `${alt} would gain only ${improvement.toFixed(1)} points, below the ${MIN_IMPROVEMENT_POINTS}-point threshold; keep ${current}.`;
  } else {
    reason = `${current} already performs best in simulation (${curRate.toFixed(1)}% vs ${altRate.toFixed(1)}% for ${alt}).`;
  }

  const confidence = switchNow
    ? clamp(Math.round(55 + improvement * 2.2 + sample / 40), 50, 98)
    : clamp(Math.round(62 + Math.max(0, -improvement) * 2.5 + sample / 60), 40, 97);

  return {
    id,
    applicationId: app.id,
    applicationName: app.name,
    cacheRegion: r.name,
    currentPolicy: current,
    recommendedPolicy: switchNow ? alt : current,
    action: switchNow ? 'SWITCH' : 'KEEP',
    summary: switchNow ? `Switch to ${alt}` : `Keep ${current}`,
    lruShadowHitRate: round1(r.shadow.lru),
    lfuShadowHitRate: round1(r.shadow.lfu),
    improvementPercent: improvement,
    confidence,
    reason,
    aiExplanation: switchNow ? (r.seed.aiExplanation ?? null) : null,
    sampleSize: sample,
    minimumSampleMet,
    cooldownActive,
    cooldownEndsAt,
    approvalRequired: true,
    createdAt: new Date(createdAt).toISOString(),
    pendingRequestId,
  };
}

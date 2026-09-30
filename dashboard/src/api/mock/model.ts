import type {
  CacheEvent,
  CacheRiskLevel,
  DesiredConfig,
  EvictionPolicy,
  SimulationKind,
} from '../types';

/** One mock "telemetry snapshot" interval. Poll = 3 s, so each poll typically sees one new tick. */
export const TICK_MS = 3000;
export const HISTORY_LIMIT = 2400; // 2 hours of ticks
export const EVENT_LIMIT = 500;
export const COOLDOWN_MS = 5 * 60 * 1000;
/** Ticks between an approved change and the "SDK" reporting it applied. */
export const APPLY_DELAY_TICKS = 2;

/** Cumulative counters since the (simulated) SDK instance started. */
export interface Counters {
  hits: number;
  misses: number;
  puts: number;
  removes: number;
  clears: number;
  evictions: number;
  expirations: number;
  lruEvictions: number;
  lfuEvictions: number;
  evictionsDueToEntryLimit: number;
  evictionsDueToMemoryLimit: number;
  victimHits: number;
  victimEvictions: number;
  refreshesStarted: number;
  concurrentRequestsCoalesced: number;
  refreshFailures: number;
  staleServed: number;
  staleCorrections: number;
  telemetryEventsSent: number;
  telemetryEventsFailed: number;
}

export function emptyCounters(): Counters {
  return {
    hits: 0,
    misses: 0,
    puts: 0,
    removes: 0,
    clears: 0,
    evictions: 0,
    expirations: 0,
    lruEvictions: 0,
    lfuEvictions: 0,
    evictionsDueToEntryLimit: 0,
    evictionsDueToMemoryLimit: 0,
    victimHits: 0,
    victimEvictions: 0,
    refreshesStarted: 0,
    concurrentRequestsCoalesced: 0,
    refreshFailures: 0,
    staleServed: 0,
    staleCorrections: 0,
    telemetryEventsSent: 0,
    telemetryEventsFailed: 0,
  };
}

/** Static behaviour of a demo region; drives the generator. */
export interface RegionSeed {
  name: string;
  riskLevel: CacheRiskLevel;
  policy: EvictionPolicy;
  capacity: number;
  entryBytes: number;
  maxMemoryBytes: number;
  defaultTtlMs: number;
  /** Initial fill as a fraction of capacity. */
  startFill: number;
  /** The region never grows beyond this fraction of capacity unless it is >= 1 (then it evicts). */
  fillCeiling: number;
  /** Requests per second. */
  rps: number;
  /** Shadow (simulated) hit rates, percent. The live rate follows the *active* policy's shadow rate. */
  lruRate: number;
  lfuRate: number;
  /** live hit rate = shadow(active policy) + liveOffset */
  liveOffset: number;
  hitNoise: number;
  concentration: number;
  expirationsPerTick: number;
  victim?: { capacity: number; hitShare: number };
  stampede?: boolean;
  /** Deterministic eviction burst schedule: every `burstEvery` ticks (offset by `burstOffset`). */
  burstEvery?: number;
  burstOffset?: number;
  burstSize?: number;
  /** Pretend the SDK has been running for this many ticks before the mock started. */
  uptimeTicks: number;
  aiExplanation?: string;
}

export interface TickDelta {
  /** Epoch ms of the tick. */
  t: number;
  hits: number;
  misses: number;
  puts: number;
  evictions: number;
  expirations: number;
}

export interface ShadowState {
  lru: number;
  lfu: number;
  concentration: number;
  requests: number;
}

export interface PendingPolicyApply {
  requestId: number;
  policy: EvictionPolicy;
  applyAtTick: number;
}

export interface RegionState {
  applicationId: number;
  seed: RegionSeed;
  name: string;
  riskLevel: CacheRiskLevel;
  policy: EvictionPolicy;
  capacity: number;
  maxMemoryBytes: number;
  defaultTtlMs: number;
  counters: Counters;
  size: number;
  memoryBytes: number;
  victimEnabled: boolean;
  victimSize: number;
  victimCapacity: number;
  liveHit: number;
  shadow: ShadowState | null;
  history: TickDelta[];
  events: CacheEvent[];
  lastUpdated: number;
  tickCount: number;
  burstLeft: number;
  lastPolicyChangeAt: number | null;
  desired: DesiredConfig | null;
  tuningVersion: number;
  updatedBy: string | null;
  updatedAt: string | null;
  pendingConfigApplyAtTick: number | null;
  pendingPolicy: PendingPolicyApply | null;
  /** Average get/put latency (ms) to report. */
  avgGetLatencyMs: number;
  avgPutLatencyMs: number;
  /** Simulation regions never tick; their numbers are fixed by the run. */
  simulation: SimulationKind | null;
}

export interface AppRecord {
  id: number;
  projectId: number;
  name: string;
  displayName: string;
  environment: string;
  description: string;
  createdAt: number;
  regions: Map<string, RegionState>;
}

export interface ProjectRecord {
  id: number;
  name: string;
  description: string;
  createdAt: number;
}

export interface ApiKeyRecord {
  id: number;
  applicationId: number;
  label: string;
  keyPrefix: string;
  createdAt: number;
  lastUsedAt: number | null;
  revokedAt: number | null;
}

// ---------------------------------------------------------------------------
// Demo seed data
// ---------------------------------------------------------------------------

export const DEMO_PROJECTS: Array<Omit<ProjectRecord, 'createdAt'>> = [
  { id: 1, name: 'Claims Platform', description: 'Claims adjudication and processing services' },
  { id: 2, name: 'Member Services', description: 'Eligibility, member and provider lookup services' },
  { id: 3, name: 'Utilization Management', description: 'Prior authorization and utilization review services' },
];

export interface DemoAppSeed {
  id: number;
  projectId: number;
  name: string;
  displayName: string;
  environment: string;
  description: string;
  regions: RegionSeed[];
}

export const DEMO_APPS: DemoAppSeed[] = [
  {
    id: 1,
    projectId: 1,
    name: 'claims-service',
    displayName: 'Claims Service',
    environment: 'staging',
    description: 'Adjudication rules and claim lookups for the claims workflow.',
    regions: [
      {
        name: 'claim-rules',
        riskLevel: 'MEDIUM',
        policy: 'LFU',
        capacity: 50_000,
        entryBytes: 3_500,
        maxMemoryBytes: 268_435_456,
        defaultTtlMs: 900_000,
        startFill: 0.7,
        fillCeiling: 0.78,
        rps: 55,
        lruRate: 82.1,
        lfuRate: 91.6,
        liveOffset: -0.2,
        hitNoise: 0.9,
        concentration: 78.5,
        expirationsPerTick: 2,
        victim: { capacity: 2_500, hitShare: 0.004 },
        uptimeTicks: 3_400,
        burstEvery: 97,
        burstOffset: 55,
        burstSize: 40,
      },
      {
        name: 'claim-lookup',
        riskLevel: 'LOW',
        policy: 'LRU',
        capacity: 10_000,
        entryBytes: 1_400,
        maxMemoryBytes: 67_108_864,
        defaultTtlMs: 300_000,
        startFill: 0.55,
        fillCeiling: 0.6,
        rps: 34,
        lruRate: 96.4,
        lfuRate: 93.1,
        liveOffset: -0.1,
        hitNoise: 0.4,
        concentration: 41.2,
        expirationsPerTick: 3,
        uptimeTicks: 3_400,
      },
    ],
  },
  {
    id: 2,
    projectId: 2,
    name: 'eligibility-service',
    displayName: 'Eligibility Service',
    environment: 'staging',
    description: 'Member eligibility verification and provider directory lookups.',
    regions: [
      {
        name: 'member-eligibility',
        riskLevel: 'HIGH',
        policy: 'LRU',
        capacity: 20_000,
        entryBytes: 1_900,
        maxMemoryBytes: 40_860_000,
        defaultTtlMs: 600_000,
        startFill: 0.99,
        fillCeiling: 1,
        rps: 12,
        lruRate: 58.4,
        lfuRate: 71.2,
        liveOffset: 0.3,
        hitNoise: 1.4,
        concentration: 74.3,
        expirationsPerTick: 1,
        uptimeTicks: 2_800,
        aiExplanation:
          'Eligibility lookups are dominated by a small, stable group of plans that are read repeatedly, while one-off member scans push them out of an LRU cache. LFU keeps the repeatedly requested entries resident, which is why its simulated hit rate is higher.',
      },
      {
        name: 'provider-directory',
        riskLevel: 'LOW',
        policy: 'LFU',
        capacity: 30_000,
        entryBytes: 900,
        maxMemoryBytes: 134_217_728,
        defaultTtlMs: 1_800_000,
        startFill: 0.6,
        fillCeiling: 0.66,
        rps: 21,
        lruRate: 84.6,
        lfuRate: 88.3,
        liveOffset: -0.3,
        hitNoise: 0.8,
        concentration: 52.6,
        expirationsPerTick: 1,
        uptimeTicks: 2_800,
      },
    ],
  },
  {
    id: 3,
    projectId: 3,
    name: 'authorization-service',
    displayName: 'Authorization Service',
    environment: 'staging',
    description: 'Prior-authorization decision support. Cached data is advisory only.',
    regions: [
      {
        name: 'authorization-decision',
        riskLevel: 'CRITICAL',
        policy: 'LRU',
        capacity: 5_000,
        entryBytes: 2_200,
        maxMemoryBytes: 33_554_432,
        defaultTtlMs: 60_000,
        startFill: 0.8,
        fillCeiling: 0.86,
        rps: 9,
        lruRate: 79.3,
        lfuRate: 76.8,
        liveOffset: 0.1,
        hitNoise: 0.9,
        concentration: 35.8,
        expirationsPerTick: 4,
        victim: { capacity: 300, hitShare: 0.03 },
        uptimeTicks: 2_000,
      },
      {
        name: 'prior-auth-rules',
        riskLevel: 'MEDIUM',
        policy: 'LRU',
        capacity: 8_000,
        entryBytes: 3_000,
        maxMemoryBytes: 67_108_864,
        defaultTtlMs: 900_000,
        startFill: 0.5,
        fillCeiling: 0.55,
        rps: 16,
        lruRate: 92.7,
        lfuRate: 91.9,
        liveOffset: -0.1,
        hitNoise: 0.6,
        concentration: 61.1,
        expirationsPerTick: 1,
        stampede: true,
        uptimeTicks: 2_000,
      },
    ],
  },
  {
    id: 4,
    projectId: 2,
    name: 'provider-portal-gateway',
    displayName: 'Provider Portal Gateway',
    environment: 'dev',
    description: 'Newly onboarded; no SDK has reported telemetry yet.',
    regions: [],
  },
];

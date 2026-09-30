import type {
  Alert,
  ApplicationSummary,
  MetricTotals,
  Overview,
  PolicyChangeRequest,
  Recommendation,
  RegionMetrics,
  Timeline,
} from '../api/types';

export function makeTotals(overrides: Partial<MetricTotals> = {}): MetricTotals {
  return {
    hits: 9140,
    misses: 860,
    hitRate: 91.4,
    missRate: 8.6,
    puts: 900,
    removes: 3,
    clears: 0,
    evictions: 120,
    expirations: 40,
    size: 410,
    capacity: 500,
    estimatedMemoryUsageBytes: 138_412_032,
    maximumMemoryBytes: 268_435_456,
    memoryUtilizationPercent: 51.56,
    lruEvictions: 0,
    lfuEvictions: 120,
    evictionsDueToEntryLimit: 100,
    evictionsDueToMemoryLimit: 20,
    averageGetLatencyMs: 0.012,
    averagePutLatencyMs: 0.031,
    sourceCallsAvoided: 9140,
    telemetryEventsSent: 512,
    telemetryEventsFailed: 0,
    l1Hits: 9100,
    victimHits: 40,
    sourceMisses: 860,
    victimEvictions: 5,
    overallHitRate: 91.4,
    refreshesStarted: 12,
    concurrentRequestsCoalesced: 180,
    sourceCallsAvoidedByStampedeShield: 180,
    refreshFailures: 0,
    staleServed: 0,
    staleCorrections: 0,
    ...overrides,
  };
}

export function makeRegion(overrides: Partial<RegionMetrics> = {}): RegionMetrics {
  return {
    ...makeTotals(),
    applicationId: 1,
    applicationName: 'claims-service',
    environment: 'staging',
    cacheRegion: 'claim-rules',
    riskLevel: 'MEDIUM',
    activePolicy: 'LFU',
    defaultTtlMs: 900_000,
    victimEnabled: false,
    victimSize: 0,
    victimCapacity: 0,
    recentWindow: { minutes: 10, hits: 820, misses: 40, puts: 45, evictions: 12, expirations: 3 },
    shadow: { requests: 10_000, windowRequests: 1000, lruHitRate: 69.4, lfuHitRate: 80.9, topKeyConcentrationPercent: 78.5 },
    health: { status: 'GOOD', score: 78, reasons: ['Hit rate is 91.4%, above 85% target.'] },
    instanceCount: 1,
    lastUpdated: '2026-09-30T09:15:30Z',
    ...overrides,
  };
}

export function makeSummary(overrides: Partial<ApplicationSummary> = {}): ApplicationSummary {
  return {
    applicationId: 1,
    projectId: 1,
    projectName: 'Claims Platform',
    name: 'claims-service',
    displayName: 'Claims Service',
    environment: 'staging',
    regionCount: 2,
    totals: makeTotals(),
    health: { status: 'GOOD', score: 80, reasons: ['Hit rate is 91.4%, above 85% target.'] },
    activePolicies: ['LFU'],
    policyLabel: 'LFU',
    recommendationSummary: 'Keep LFU',
    lastTelemetryAt: new Date(Date.now() - 3000).toISOString(),
    secondsSinceLastTelemetry: 3,
    ...overrides,
  };
}

export function makeRecommendation(overrides: Partial<Recommendation> = {}): Recommendation {
  return {
    id: 7,
    applicationId: 1,
    applicationName: 'claims-service',
    cacheRegion: 'claim-rules',
    currentPolicy: 'LRU',
    recommendedPolicy: 'LFU',
    action: 'SWITCH',
    summary: 'Switch to LFU',
    lruShadowHitRate: 69.4,
    lfuShadowHitRate: 80.9,
    improvementPercent: 11.5,
    confidence: 92,
    reason: 'A small stable set of keys dominates repeated requests.',
    aiExplanation: null,
    sampleSize: 1000,
    minimumSampleMet: true,
    cooldownActive: false,
    cooldownEndsAt: null,
    approvalRequired: true,
    createdAt: '2026-09-30T09:15:30Z',
    pendingRequestId: null,
    ...overrides,
  };
}

export function makeRequest(overrides: Partial<PolicyChangeRequest> = {}): PolicyChangeRequest {
  return {
    id: 3,
    applicationId: 1,
    applicationName: 'claims-service',
    cacheRegion: 'claim-rules',
    currentPolicy: 'LRU',
    requestedPolicy: 'LFU',
    reason: 'Policy Arena recommends LFU (+11.5 pts).',
    status: 'PENDING',
    requestedBy: 'engineer',
    decidedBy: null,
    decisionNote: null,
    createdAt: '2026-09-30T09:16:00Z',
    decidedAt: null,
    appliedAt: null,
    recommendationId: 7,
    ...overrides,
  };
}

export function makeAlert(overrides: Partial<Alert> = {}): Alert {
  return {
    id: '1:claim-rules:HIT_RATE',
    severity: 'WARNING',
    applicationId: 1,
    applicationName: 'claims-service',
    cacheRegion: 'claim-rules',
    message: 'Hit rate is 48.2%, below 65% target.',
    since: '2026-09-30T09:10:00Z',
    ...overrides,
  };
}

export function makeOverview(overrides: Partial<Overview> = {}): Overview {
  const regions = [makeRegion(), makeRegion({ cacheRegion: 'claim-lookup', riskLevel: 'LOW', activePolicy: 'LRU' })];
  return {
    generatedAt: '2026-09-30T09:15:30Z',
    projectsMonitored: 3,
    applicationsMonitored: 2,
    cacheRegionsMonitored: 2,
    totals: makeTotals({ hits: 18_280, misses: 1720, hitRate: 91.4, missRate: 8.6, sourceCallsAvoided: 1_284_000 }),
    activeAlerts: [makeAlert(), makeAlert({ id: '1:claim-rules:MEMORY', severity: 'CRITICAL', message: 'Memory utilization is 99%.' })],
    latestRecommendations: [makeRecommendation()],
    applications: [makeSummary()],
    regions,
    ...overrides,
  };
}

export function makeTimeline(points = 6, bucketSeconds = 10): Timeline {
  const start = Date.parse('2026-09-30T09:00:00Z');
  return {
    cacheRegion: null,
    bucketSeconds,
    points: Array.from({ length: points }, (_, i) => ({
      bucketStart: new Date(start + i * bucketSeconds * 1000).toISOString(),
      hits: 100 + i * 10,
      misses: 10 + i,
      puts: 10 + i,
      evictions: i,
      expirations: i % 2,
      hitRate: 90,
    })),
  };
}

export function emptyOverview(): Overview {
  return makeOverview({
    projectsMonitored: 0,
    applicationsMonitored: 0,
    cacheRegionsMonitored: 0,
    totals: makeTotals({
      hits: 0, misses: 0, hitRate: 0, missRate: 0, puts: 0, evictions: 0, expirations: 0, size: 0, capacity: 0,
      estimatedMemoryUsageBytes: 0, maximumMemoryBytes: 0, memoryUtilizationPercent: 0, sourceCallsAvoided: 0,
      lruEvictions: 0, lfuEvictions: 0, evictionsDueToEntryLimit: 0, evictionsDueToMemoryLimit: 0,
    }),
    activeAlerts: [],
    latestRecommendations: [],
    applications: [],
    regions: [],
  });
}

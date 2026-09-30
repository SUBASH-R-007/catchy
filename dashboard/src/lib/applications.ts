import type { Application, ApplicationSummary, RegionMetrics } from '../api/types';
import { healthRank } from './health';

export function applicationLabel(app: Pick<Application, 'displayName' | 'name' | 'environment'>): string {
  return `${app.displayName || app.name} (${app.environment})`;
}

/** A summary for an application that has never reported telemetry (empty totals, UNKNOWN health). */
export function emptySummary(app: Application): ApplicationSummary {
  return {
    applicationId: app.id,
    projectId: app.projectId,
    projectName: app.projectName,
    name: app.name,
    displayName: app.displayName,
    environment: app.environment,
    regionCount: app.regionCount,
    totals: {
      hits: 0, misses: 0, hitRate: 0, missRate: 0, puts: 0, removes: 0, clears: 0, evictions: 0, expirations: 0,
      size: 0, capacity: 0, estimatedMemoryUsageBytes: 0, maximumMemoryBytes: 0, memoryUtilizationPercent: 0,
      lruEvictions: 0, lfuEvictions: 0, evictionsDueToEntryLimit: 0, evictionsDueToMemoryLimit: 0,
      averageGetLatencyMs: 0, averagePutLatencyMs: 0, sourceCallsAvoided: 0, telemetryEventsSent: 0,
      telemetryEventsFailed: 0, l1Hits: 0, victimHits: 0, sourceMisses: 0, victimEvictions: 0, overallHitRate: 0,
      refreshesStarted: 0, concurrentRequestsCoalesced: 0, sourceCallsAvoidedByStampedeShield: 0,
      refreshFailures: 0, staleServed: 0, staleCorrections: 0,
    },
    health: { status: 'UNKNOWN', score: 0, reasons: ['No telemetry received yet.'] },
    activePolicies: [],
    policyLabel: 'NONE',
    recommendationSummary: 'No data yet',
    lastTelemetryAt: app.lastTelemetryAt ?? null,
    secondsSinceLastTelemetry: null,
  };
}

/**
 * All registered applications as summaries: the metrics summary when one exists, otherwise an
 * empty "no telemetry yet" summary — so a brand-new application is still listed.
 */
export function mergeSummaries(apps: readonly Application[], summaries: readonly ApplicationSummary[]): ApplicationSummary[] {
  const byId = new Map(summaries.map((s) => [s.applicationId, s]));
  return apps.map((a) => byId.get(a.id) ?? emptySummary(a));
}

/** The region whose health is worst (highest rank, then lowest score) — used to explain an application's health. */
export function worstRegion(regions: readonly RegionMetrics[]): RegionMetrics | null {
  if (regions.length === 0) return null;
  return regions.reduce((w, r) => {
    const rr = healthRank(r.health.status);
    const wr = healthRank(w.health.status);
    return rr > wr || (rr === wr && r.health.score < w.health.score) ? r : w;
  });
}

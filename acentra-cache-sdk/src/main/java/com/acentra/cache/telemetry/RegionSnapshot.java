package com.acentra.cache.telemetry;

import com.acentra.cache.CacheRiskLevel;
import com.acentra.cache.EvictionPolicy;
import java.time.Instant;

/**
 * Cumulative counters of one region since the SDK instance started (wire shape in docs/api.md).
 * Snapshots are exact; routine events are sampled, so the dashboard aggregates from snapshots.
 */
public record RegionSnapshot(
        Instant capturedAt,
        String cacheRegion,
        CacheRiskLevel riskLevel,
        EvictionPolicy activePolicy,
        long hits,
        long misses,
        long puts,
        long removes,
        long clears,
        long evictions,
        long expirations,
        int size,
        int capacity,
        long estimatedMemoryUsageBytes,
        long maximumMemoryBytes,
        long lruEvictions,
        long lfuEvictions,
        long evictionsDueToEntryLimit,
        long evictionsDueToMemoryLimit,
        double averageGetLatencyMs,
        double averagePutLatencyMs,
        long sourceCallsAvoided,
        long telemetryEventsSent,
        long telemetryEventsFailed,
        int telemetryFailureStreak,
        long l1Hits,
        long victimHits,
        long sourceMisses,
        long victimEvictions,
        boolean victimEnabled,
        int victimSize,
        int victimCapacity,
        long refreshesStarted,
        long concurrentRequestsCoalesced,
        long sourceCallsAvoidedByStampedeShield,
        long refreshFailures,
        long sourceCalls,
        long sourceErrors,
        long staleServed,
        long staleCorrections,
        long staleDataViolations,
        long defaultTtlMs,
        Instant lastPolicyChangeAt,
        RecentWindow recentWindow,
        Shadow shadow) {

    public record RecentWindow(int minutes, long hits, long misses, long puts, long evictions, long expirations) {}

    public record Shadow(
            long requests,
            int windowRequests,
            double lruHitRate,
            double lfuHitRate,
            double topKeyConcentrationPercent) {}
}

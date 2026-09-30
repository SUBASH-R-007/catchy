package com.acentra.cache;

/**
 * Point-in-time metrics of one cache region. Primary-layer (L1) figures: {@code size}, {@code capacity},
 * {@code estimatedMemoryUsageBytes}, {@code maximumMemoryBytes}. Victim-layer figures are reported separately.
 * Memory figures are <b>estimated cache memory usage</b>, not exact JVM heap allocation.
 */
public record CacheMetrics(
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
        EvictionPolicy activePolicy,
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
        long staleDataViolations) {

    public long requests() {
        return hits + misses;
    }

    /** hits / (hits + misses) * 100; 0 when there were no requests. */
    public double hitRate() {
        long total = requests();
        return total == 0 ? 0.0 : (double) hits / total * 100.0;
    }

    /** misses / (hits + misses) * 100; 0 when there were no requests. hitRate + missRate == 100 otherwise. */
    public double missRate() {
        long total = requests();
        return total == 0 ? 0.0 : (double) misses / total * 100.0;
    }

    /** (l1Hits + victimHits) / total requests * 100. */
    public double overallHitRate() {
        long total = requests();
        return total == 0 ? 0.0 : (double) (l1Hits + victimHits) / total * 100.0;
    }

    public double memoryUtilizationPercent() {
        return maximumMemoryBytes <= 0 ? 0.0 : (double) estimatedMemoryUsageBytes / maximumMemoryBytes * 100.0;
    }
}

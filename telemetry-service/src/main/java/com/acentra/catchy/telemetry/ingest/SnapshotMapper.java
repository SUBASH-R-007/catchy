package com.acentra.catchy.telemetry.ingest;

import com.acentra.cache.telemetry.RegionSnapshot;
import com.acentra.catchy.telemetry.domain.CacheMetricsSnapshot;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Maps the SDK wire snapshot onto the persistence row. */
final class SnapshotMapper {
    private SnapshotMapper() {}

    static CacheMetricsSnapshot toEntity(Long applicationId, String instanceId, RegionSnapshot s, Instant receivedAt) {
        CacheMetricsSnapshot e = new CacheMetricsSnapshot();
        e.applicationId = applicationId;
        e.cacheRegion = s.cacheRegion();
        e.instanceId = instanceId;
        e.capturedAt = s.capturedAt().truncatedTo(ChronoUnit.MILLIS);
        e.receivedAt = receivedAt;
        e.riskLevel = s.riskLevel().name();
        e.activePolicy = s.activePolicy().name();
        e.hits = s.hits();
        e.misses = s.misses();
        e.puts = s.puts();
        e.removes = s.removes();
        e.clears = s.clears();
        e.evictions = s.evictions();
        e.expirations = s.expirations();
        e.cacheSize = s.size();
        e.capacity = s.capacity();
        e.estimatedMemoryUsageBytes = s.estimatedMemoryUsageBytes();
        e.maximumMemoryBytes = s.maximumMemoryBytes();
        e.lruEvictions = s.lruEvictions();
        e.lfuEvictions = s.lfuEvictions();
        e.evictionsDueToEntryLimit = s.evictionsDueToEntryLimit();
        e.evictionsDueToMemoryLimit = s.evictionsDueToMemoryLimit();
        e.averageGetLatencyMs = s.averageGetLatencyMs();
        e.averagePutLatencyMs = s.averagePutLatencyMs();
        e.sourceCallsAvoided = s.sourceCallsAvoided();
        e.telemetryEventsSent = s.telemetryEventsSent();
        e.telemetryEventsFailed = s.telemetryEventsFailed();
        e.telemetryFailureStreak = s.telemetryFailureStreak();
        e.l1Hits = s.l1Hits();
        e.victimHits = s.victimHits();
        e.sourceMisses = s.sourceMisses();
        e.victimEvictions = s.victimEvictions();
        e.victimEnabled = s.victimEnabled();
        e.victimSize = s.victimSize();
        e.victimCapacity = s.victimCapacity();
        e.refreshesStarted = s.refreshesStarted();
        e.concurrentRequestsCoalesced = s.concurrentRequestsCoalesced();
        e.sourceCallsAvoidedByStampedeShield = s.sourceCallsAvoidedByStampedeShield();
        e.refreshFailures = s.refreshFailures();
        e.sourceCalls = s.sourceCalls();
        e.sourceErrors = s.sourceErrors();
        e.staleServed = s.staleServed();
        e.staleCorrections = s.staleCorrections();
        e.staleDataViolations = s.staleDataViolations();
        e.defaultTtlMs = s.defaultTtlMs();
        e.lastPolicyChangeAt = s.lastPolicyChangeAt();
        RegionSnapshot.RecentWindow rw = s.recentWindow();
        if (rw != null) {
            e.recentMinutes = rw.minutes();
            e.recentHits = rw.hits();
            e.recentMisses = rw.misses();
            e.recentPuts = rw.puts();
            e.recentEvictions = rw.evictions();
            e.recentExpirations = rw.expirations();
        }
        RegionSnapshot.Shadow sh = s.shadow();
        if (sh != null) {
            e.hasShadow = true;
            e.shadowRequests = sh.requests();
            e.shadowWindowRequests = sh.windowRequests();
            e.shadowLruHitRate = sh.lruHitRate();
            e.shadowLfuHitRate = sh.lfuHitRate();
            e.shadowTopKeyConcentration = sh.topKeyConcentrationPercent();
        }
        return e;
    }
}

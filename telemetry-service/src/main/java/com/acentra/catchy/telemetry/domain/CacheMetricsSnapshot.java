package com.acentra.catchy.telemetry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One received cumulative region snapshot (counters since the SDK instance started). The source of truth for all
 * aggregated metrics; {@code latest} flags the newest row per (application, region, instance).
 */
@Entity
@Table(name = "cache_metrics_snapshot")
public class CacheMetricsSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false)
    public Long applicationId;
    @Column(nullable = false, length = 63)
    public String cacheRegion;
    @Column(nullable = false, length = 80)
    public String instanceId;
    @Column(nullable = false)
    public Instant capturedAt;
    @Column(nullable = false)
    public Instant receivedAt;
    public boolean latest;
    @Column(nullable = false, length = 16)
    public String riskLevel;
    @Column(nullable = false, length = 8)
    public String activePolicy;

    public long hits;
    public long misses;
    public long puts;
    public long removes;
    public long clears;
    public long evictions;
    public long expirations;
    public int cacheSize;
    public int capacity;
    public long estimatedMemoryUsageBytes;
    public long maximumMemoryBytes;
    public long lruEvictions;
    public long lfuEvictions;
    public long evictionsDueToEntryLimit;
    public long evictionsDueToMemoryLimit;
    public double averageGetLatencyMs;
    public double averagePutLatencyMs;
    public long sourceCallsAvoided;
    public long telemetryEventsSent;
    public long telemetryEventsFailed;
    public int telemetryFailureStreak;
    @Column(name = "l1_hits")
    public long l1Hits;
    public long victimHits;
    public long sourceMisses;
    public long victimEvictions;
    public boolean victimEnabled;
    public int victimSize;
    public int victimCapacity;
    public long refreshesStarted;
    public long concurrentRequestsCoalesced;
    public long sourceCallsAvoidedByStampedeShield;
    public long refreshFailures;
    public long sourceCalls;
    public long sourceErrors;
    public long staleServed;
    public long staleCorrections;
    public long staleDataViolations;
    public long defaultTtlMs;
    public Instant lastPolicyChangeAt;

    public int recentMinutes;
    public long recentHits;
    public long recentMisses;
    public long recentPuts;
    public long recentEvictions;
    public long recentExpirations;

    public boolean hasShadow;
    public long shadowRequests;
    public int shadowWindowRequests;
    public double shadowLruHitRate;
    public double shadowLfuHitRate;
    public double shadowTopKeyConcentration;
}

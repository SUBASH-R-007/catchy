package com.acentra.catchy.telemetry;

import com.acentra.cache.CacheAction;
import com.acentra.cache.CacheRiskLevel;
import com.acentra.cache.EventSeverity;
import com.acentra.cache.EvictionPolicy;
import com.acentra.cache.telemetry.RegionSnapshot;
import com.acentra.cache.telemetry.TelemetryBatch;
import com.acentra.cache.telemetry.TelemetryEvent;
import java.time.Instant;
import java.util.List;

/** Builders for SDK wire records used as request bodies in tests. */
final class TestData {
    private TestData() {}

    /** Mutable builder with sensible defaults; only the interesting counters need to be set. */
    static final class Snap {
        Instant capturedAt = Instant.now();
        String region = "claim-rules";
        CacheRiskLevel risk = CacheRiskLevel.MEDIUM;
        EvictionPolicy policy = EvictionPolicy.LRU;
        long hits, misses, puts, evictions, expirations;
        int size = 10, capacity = 500;
        long mem = 1_000, maxMem = 10_000;
        double avgGet = 0.0, avgPut = 0.0;
        int failureStreak = 0;
        long sourceCalls, sourceErrors, staleViolations;
        long defaultTtlMs = 900_000;
        long entryLimitEvictions, memoryLimitEvictions;
        Instant lastPolicyChangeAt;
        RegionSnapshot.RecentWindow recent = new RegionSnapshot.RecentWindow(10, 0, 0, 0, 0, 0);
        RegionSnapshot.Shadow shadow;

        Snap region(String v) { region = v; return this; }
        Snap at(Instant v) { capturedAt = v; return this; }
        Snap policy(EvictionPolicy v) { policy = v; return this; }
        Snap risk(CacheRiskLevel v) { risk = v; return this; }
        Snap counters(long hits, long misses, long puts, long evictions, long expirations) {
            this.hits = hits; this.misses = misses; this.puts = puts; this.evictions = evictions; this.expirations = expirations;
            return this;
        }
        Snap memory(long used, long max) { mem = used; maxMem = max; return this; }
        Snap sizing(int size, int capacity) { this.size = size; this.capacity = capacity; return this; }
        Snap latency(double get, double put) { avgGet = get; avgPut = put; return this; }
        Snap recent(long hits, long misses, long puts, long evictions, long expirations) {
            recent = new RegionSnapshot.RecentWindow(10, hits, misses, puts, evictions, expirations);
            return this;
        }
        Snap shadow(long windowRequests, double lru, double lfu, double concentration) {
            shadow = new RegionSnapshot.Shadow(windowRequests * 10, (int) windowRequests, lru, lfu, concentration);
            return this;
        }
        Snap lastPolicyChange(Instant v) { lastPolicyChangeAt = v; return this; }
        Snap ttl(long v) { defaultTtlMs = v; return this; }
        Snap source(long calls, long errors) { sourceCalls = calls; sourceErrors = errors; return this; }
        Snap failureStreak(int v) { failureStreak = v; return this; }
        Snap staleViolations(long v) { staleViolations = v; return this; }

        RegionSnapshot build() {
            return new RegionSnapshot(capturedAt, region, risk, policy, hits, misses, puts, 0, 0, evictions, expirations,
                    size, capacity, mem, maxMem, policy == EvictionPolicy.LRU ? evictions : 0,
                    policy == EvictionPolicy.LFU ? evictions : 0, entryLimitEvictions, memoryLimitEvictions, avgGet, avgPut,
                    hits, hits + misses, 0, failureStreak, hits, 0, misses, 0, false, 0, 0, 0, 0, 0, 0, sourceCalls,
                    sourceErrors, 0, 0, staleViolations, defaultTtlMs, lastPolicyChangeAt, recent, shadow);
        }
    }

    static Snap snap() {
        return new Snap();
    }

    static TelemetryEvent event(String region, CacheAction action, String fingerprint) {
        return new TelemetryEvent(Instant.now(), null, null, region, action, fingerprint, "synthetic test event",
                EvictionPolicy.LRU, 1, 100, 5_000, 1_800, 10, 10, 1_000, 1_000, action == CacheAction.HIT,
                EventSeverity.INFO, 0.01);
    }

    static TelemetryBatch batch(String app, String env, String instance, List<TelemetryEvent> events, List<RegionSnapshot> snaps) {
        return new TelemetryBatch(app, env, instance, "1.0.0", Instant.now(), events, snaps);
    }
}

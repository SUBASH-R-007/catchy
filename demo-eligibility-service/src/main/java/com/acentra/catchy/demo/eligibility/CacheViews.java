package com.acentra.catchy.demo.eligibility;

import com.acentra.cache.AcentraCache;
import com.acentra.cache.CacheHealthReport;
import com.acentra.cache.CacheMetrics;
import com.acentra.cache.CachePolicyRecommendation;
import com.acentra.cache.CacheRegionConfig;

/** Local, safe-fields-only views of a region (counters and settings; never keys, fingerprints or values). */
public final class CacheViews {
    private CacheViews() {}

    public record MetricsView(long hits, long misses, double hitRatePercent, long puts, long evictions,
                              long entryLimitEvictions, long memoryLimitEvictions, long expirations, int size, int capacity,
                              long estimatedMemoryUsageBytes, long maximumMemoryBytes, double memoryUtilizationPercent,
                              long sourceCalls, long sourceCallsAvoided, long concurrentRequestsCoalesced, long victimHits,
                              int victimSize, long staleServed, long staleCorrections, double averageGetLatencyMs) {
        static MetricsView of(CacheMetrics m) {
            return new MetricsView(m.hits(), m.misses(), round(m.hitRate()), m.puts(), m.evictions(),
                    m.evictionsDueToEntryLimit(), m.evictionsDueToMemoryLimit(), m.expirations(), m.size(), m.capacity(),
                    m.estimatedMemoryUsageBytes(), m.maximumMemoryBytes(), round(m.memoryUtilizationPercent()),
                    m.sourceCalls(), m.sourceCallsAvoided(), m.concurrentRequestsCoalesced(), m.victimHits(), m.victimSize(),
                    m.staleServed(), m.staleCorrections(), m.averageGetLatencyMs());
        }
    }

    public record RegionView(String region, String riskLevel, String activePolicy, boolean staleWhileRevalidate,
                             long defaultTtlMs, MetricsView metrics, CacheHealthReport health,
                             CachePolicyRecommendation recommendation) {}

    public static RegionView of(AcentraCache<?, ?> cache) {
        CacheRegionConfig cfg = cache.getConfig();
        CacheMetrics m = cache.getMetrics();
        return new RegionView(cfg.regionName(), cfg.riskLevel().name(), m.activePolicy().name(),
                cfg.staleWhileRevalidate(), cfg.defaultTtl().toMillis(), MetricsView.of(m), cache.getHealth(),
                cache.getRecommendation());
    }

    static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

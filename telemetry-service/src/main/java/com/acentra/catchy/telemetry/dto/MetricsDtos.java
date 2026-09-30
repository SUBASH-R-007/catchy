package com.acentra.catchy.telemetry.dto;

import com.acentra.cache.CacheHealthStatus;
import com.acentra.cache.CacheRiskLevel;
import com.acentra.cache.EvictionPolicy;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.util.List;

/** Response shapes of the metrics endpoints (docs/api.md "Metrics"). */
public final class MetricsDtos {
    private MetricsDtos() {}

    public record Health(CacheHealthStatus status, int score, List<String> reasons) {}

    public record RecentWindow(int minutes, long hits, long misses, long puts, long evictions, long expirations) {}

    public record Shadow(long requests, long windowRequests, double lruHitRate, double lfuHitRate,
                         double topKeyConcentrationPercent) {}

    /** MetricTotals + identity + region state; the totals are flattened into the same JSON object. */
    public record RegionMetrics(
            Long applicationId, String applicationName, String environment, String cacheRegion,
            CacheRiskLevel riskLevel, EvictionPolicy activePolicy, long defaultTtlMs,
            boolean victimEnabled, long victimSize, long victimCapacity,
            RecentWindow recentWindow, Shadow shadow, Health health,
            int instanceCount, Instant lastUpdated,
            @JsonUnwrapped MetricTotals totals) {}

    public record ApplicationSummary(
            Long applicationId, Long projectId, String projectName, String name, String displayName, String environment,
            int regionCount, MetricTotals totals, Health health, List<String> activePolicies, String policyLabel,
            String recommendationSummary, Instant lastTelemetryAt, Long secondsSinceLastTelemetry) {}

    public record ApplicationMetrics(@JsonUnwrapped ApplicationSummary summary, List<RegionMetrics> regions) {}

    public record ProjectMetrics(
            Long projectId, String projectName, int applicationCount, int regionCount,
            MetricTotals totals, Health health, List<ApplicationSummary> applications) {}

    public record Alert(
            String id, String severity, Long applicationId, String applicationName, String cacheRegion,
            String message, Instant since) {}

    public record Overview(
            Instant generatedAt, int projectsMonitored, int applicationsMonitored, int cacheRegionsMonitored,
            MetricTotals totals, List<Alert> activeAlerts, List<PolicyDtos.Recommendation> latestRecommendations,
            List<ApplicationSummary> applications, List<RegionMetrics> regions) {}

    public record TimelinePoint(Instant bucketStart, long hits, long misses, long puts, long evictions,
                                long expirations, double hitRate) {}

    public record Timeline(String cacheRegion, int bucketSeconds, List<TimelinePoint> points) {}
}

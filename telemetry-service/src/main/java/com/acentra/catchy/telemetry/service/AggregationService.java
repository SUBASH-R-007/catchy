package com.acentra.catchy.telemetry.service;

import static com.acentra.catchy.telemetry.config.Times.round2;

import com.acentra.cache.CacheHealthEvaluator;
import com.acentra.cache.CacheHealthReport;
import com.acentra.cache.CacheRiskLevel;
import com.acentra.cache.EvictionPolicy;
import com.acentra.catchy.telemetry.config.CatchyProperties;
import com.acentra.catchy.telemetry.config.Times;
import com.acentra.catchy.telemetry.domain.CacheMetricsSnapshot;
import com.acentra.catchy.telemetry.domain.SnapshotRepository;
import com.acentra.catchy.telemetry.dto.MetricTotals;
import com.acentra.catchy.telemetry.dto.MetricsDtos.Health;
import com.acentra.catchy.telemetry.dto.MetricsDtos.RecentWindow;
import com.acentra.catchy.telemetry.dto.MetricsDtos.Shadow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the aggregated view of every (application, region) from the latest snapshot of each SDK instance.
 * Displayed totals are the sum across instances; rates are derived from the summed counters; latencies are weighted
 * by operation counts; health comes from the SDK's own {@link CacheHealthEvaluator}.
 */
@Service
public class AggregationService {

    /** Aggregated state of one region (all instances). */
    public record RegionAggregate(
            Long applicationId, String cacheRegion, CacheRiskLevel riskLevel, EvictionPolicy activePolicy,
            long defaultTtlMs, boolean victimEnabled, long victimSize, long victimCapacity,
            RecentWindow recentWindow, Shadow shadow, Health health, int instanceCount, Instant lastUpdated,
            Instant lastPolicyChangeAt, MetricTotals totals, int reportedMaxEntries, long reportedMaxMemoryBytes) {}

    private final SnapshotRepository snapshots;
    private final CatchyProperties props;
    private final Clock clock;

    public AggregationService(SnapshotRepository snapshots, CatchyProperties props, Clock clock) {
        this.snapshots = snapshots;
        this.props = props;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<Long, List<RegionAggregate>> allByApplication() {
        return group(snapshots.findByLatestTrue());
    }

    @Transactional(readOnly = true)
    public List<RegionAggregate> forApplication(Long applicationId) {
        return group(snapshots.findByApplicationIdAndLatestTrue(applicationId)).getOrDefault(applicationId, List.of());
    }

    @Transactional(readOnly = true)
    public Optional<RegionAggregate> forRegion(Long applicationId, String region) {
        List<CacheMetricsSnapshot> rows = snapshots.findByApplicationIdAndCacheRegionAndLatestTrue(applicationId, region);
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(aggregate(applicationId, region, rows, Times.now(clock), props.metrics().instanceStaleAfter()));
    }

    private Map<Long, List<RegionAggregate>> group(List<CacheMetricsSnapshot> rows) {
        Instant now = Times.now(clock);
        Map<Long, Map<String, List<CacheMetricsSnapshot>>> grouped = new TreeMap<>();
        for (CacheMetricsSnapshot r : rows) {
            grouped.computeIfAbsent(r.applicationId, k -> new TreeMap<>())
                    .computeIfAbsent(r.cacheRegion, k -> new ArrayList<>()).add(r);
        }
        Map<Long, List<RegionAggregate>> out = new LinkedHashMap<>();
        grouped.forEach((appId, regions) -> {
            List<RegionAggregate> list = new ArrayList<>();
            regions.forEach((region, rs) -> list.add(aggregate(appId, region, rs, now, props.metrics().instanceStaleAfter())));
            out.put(appId, list);
        });
        return out;
    }

    /**
     * Pure aggregation of the snapshot rows of one region. Only the newest row per instance counts, and instances that
     * stopped reporting long before the region's newest instance are dropped.
     */
    public static RegionAggregate aggregate(Long applicationId, String region, List<CacheMetricsSnapshot> rows,
                                            Instant now, Duration staleAfter) {
        Map<String, CacheMetricsSnapshot> newestPerInstance = new LinkedHashMap<>();
        for (CacheMetricsSnapshot r : rows) {
            newestPerInstance.merge(r.instanceId, r, (a, b) -> newer(a, b) ? a : b);
        }
        Instant newestReceived = newestPerInstance.values().stream().map(r -> r.receivedAt).max(Comparator.naturalOrder()).orElse(now);
        List<CacheMetricsSnapshot> active = newestPerInstance.values().stream()
                .filter(r -> !r.receivedAt.isBefore(newestReceived.minus(staleAfter))).toList();
        CacheMetricsSnapshot primary = active.stream().reduce((a, b) -> newer(a, b) ? a : b).orElseThrow();

        MetricTotals.Acc acc = new MetricTotals.Acc();
        long victimSize = 0, victimCapacity = 0, sourceCalls = 0, sourceErrors = 0, staleViolations = 0;
        long recentHits = 0, recentMisses = 0, recentPuts = 0, recentEvictions = 0, recentExpirations = 0;
        int recentMinutes = 0, failureStreak = 0;
        boolean victimEnabled = false;
        Instant lastPolicyChange = null;
        long shadowRequests = 0, shadowWindow = 0;
        double lruW = 0, lfuW = 0, concW = 0;
        boolean anyShadow = false;
        for (CacheMetricsSnapshot s : active) {
            acc.addSnapshot(s);
            victimSize += s.victimSize;
            victimCapacity += s.victimCapacity;
            victimEnabled |= s.victimEnabled;
            sourceCalls += s.sourceCalls;
            sourceErrors += s.sourceErrors;
            staleViolations += s.staleDataViolations;
            recentHits += s.recentHits;
            recentMisses += s.recentMisses;
            recentPuts += s.recentPuts;
            recentEvictions += s.recentEvictions;
            recentExpirations += s.recentExpirations;
            recentMinutes = Math.max(recentMinutes, s.recentMinutes);
            failureStreak = Math.max(failureStreak, s.telemetryFailureStreak);
            if (s.lastPolicyChangeAt != null && (lastPolicyChange == null || s.lastPolicyChangeAt.isAfter(lastPolicyChange))) {
                lastPolicyChange = s.lastPolicyChangeAt;
            }
            if (s.hasShadow && s.shadowWindowRequests > 0) {
                anyShadow = true;
                shadowRequests += s.shadowRequests;
                shadowWindow += s.shadowWindowRequests;
                lruW += s.shadowLruHitRate * s.shadowWindowRequests;
                lfuW += s.shadowLfuHitRate * s.shadowWindowRequests;
                concW += s.shadowTopKeyConcentration * s.shadowWindowRequests;
            }
        }
        if (recentMinutes <= 0) recentMinutes = 10;
        Shadow shadow = anyShadow
                ? new Shadow(shadowRequests, shadowWindow, round2(lruW / shadowWindow), round2(lfuW / shadowWindow),
                        round2(concW / shadowWindow))
                : null;
        RecentWindow recent = new RecentWindow(recentMinutes, recentHits, recentMisses, recentPuts, recentEvictions, recentExpirations);

        long secondsSince = Math.max(0, Duration.between(newestReceived, now).getSeconds());
        double sourceErrorRate = sourceCalls == 0 ? 0.0 : (double) sourceErrors / sourceCalls * 100.0;
        CacheRiskLevel risk = CacheRiskLevel.valueOf(primary.riskLevel);
        CacheHealthReport report = CacheHealthEvaluator.evaluate(new CacheHealthEvaluator.Input(
                acc.requests(), acc.exactHitRate(), acc.exactMemoryUtilization(), recentEvictions, recentExpirations,
                recentPuts, recentMinutes, failureStreak, risk, staleViolations, sourceErrorRate, secondsSince));
        Health health = new Health(report.status(), report.score(), report.reasons());

        return new RegionAggregate(applicationId, region, risk, EvictionPolicy.valueOf(primary.activePolicy),
                primary.defaultTtlMs, victimEnabled, victimSize, victimCapacity, recent, shadow, health, active.size(),
                newestReceived, lastPolicyChange, acc.build(), primary.capacity, primary.maximumMemoryBytes);
    }

    /** True when {@code a} is at least as recent as {@code b}. */
    private static boolean newer(CacheMetricsSnapshot a, CacheMetricsSnapshot b) {
        int c = a.capturedAt.compareTo(b.capturedAt);
        if (c != 0) return c > 0;
        return (a.id == null ? 0 : a.id) >= (b.id == null ? 0 : b.id);
    }
}

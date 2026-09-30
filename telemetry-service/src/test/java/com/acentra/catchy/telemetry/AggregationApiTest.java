package com.acentra.catchy.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acentra.cache.CacheAction;
import com.acentra.cache.CacheHealthEvaluator;
import com.acentra.cache.CacheHealthReport;
import com.acentra.cache.CacheRiskLevel;
import com.acentra.cache.EvictionPolicy;
import com.acentra.cache.telemetry.RegionSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AggregationApiTest extends AbstractApiTest {

    private void send(TestApp app, String instance, RegionSnapshot... snaps) throws Exception {
        ingest(app.apiKey(), TestData.batch(null, null, instance, List.of(), List.of(snaps))).andExpect(status().isAccepted());
    }

    @Test
    void totalsAreSummedAcrossInstancesAndRegionsWithDerivedRates() throws Exception {
        TestApp app = createApp("claims-service");
        send(app, "pod-a",
                TestData.snap().region("claim-rules").counters(800, 200, 100, 10, 5).sizing(100, 500).memory(2_000, 10_000)
                        .latency(0.010, 0.030).build(),
                TestData.snap().region("member-lookup").policy(EvictionPolicy.LFU).counters(50, 50, 10, 0, 0).sizing(20, 100)
                        .memory(500, 5_000).build());
        send(app, "pod-b",
                TestData.snap().region("claim-rules").counters(600, 400, 50, 20, 0).sizing(50, 500).memory(3_000, 10_000)
                        .latency(0.020, 0.050).build());

        JsonNode region = json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/regions/claim-rules/metrics")
                .andExpect(status().isOk()));
        assertThat(region.get("hits").asLong()).isEqualTo(1400);
        assertThat(region.get("misses").asLong()).isEqualTo(600);
        assertThat(region.get("hitRate").asDouble()).isEqualTo(70.0);
        assertThat(region.get("missRate").asDouble()).isEqualTo(30.0);
        assertThat(region.get("puts").asLong()).isEqualTo(150);
        assertThat(region.get("evictions").asLong()).isEqualTo(30);
        assertThat(region.get("expirations").asLong()).isEqualTo(5);
        assertThat(region.get("size").asLong()).isEqualTo(150);
        assertThat(region.get("capacity").asLong()).isEqualTo(1000);
        assertThat(region.get("estimatedMemoryUsageBytes").asLong()).isEqualTo(5_000);
        assertThat(region.get("maximumMemoryBytes").asLong()).isEqualTo(20_000);
        assertThat(region.get("memoryUtilizationPercent").asDouble()).isEqualTo(25.0);
        // latencies are weighted by operation counts: gets 1000 vs 1000, puts 100 vs 50
        assertThat(region.get("averageGetLatencyMs").asDouble()).isCloseTo(0.015, within(0.00001));
        assertThat(region.get("averagePutLatencyMs").asDouble()).isCloseTo(0.0367, within(0.00001));
        assertThat(region.get("instanceCount").asInt()).isEqualTo(2);
        assertThat(region.get("applicationName").asText()).isEqualTo("claims-service");
        assertThat(region.get("environment").asText()).isEqualTo("staging");
        assertThat(region.get("lastUpdated").asText()).isNotBlank();

        JsonNode application = json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/metrics").andExpect(status().isOk()));
        assertThat(application.get("regionCount").asInt()).isEqualTo(2);
        assertThat(application.get("totals").get("hits").asLong()).isEqualTo(1450);
        assertThat(application.get("totals").get("misses").asLong()).isEqualTo(650);
        assertThat(application.get("totals").get("hitRate").asDouble()).isEqualTo(69.05);
        assertThat(application.get("totals").get("missRate").asDouble()).isEqualTo(30.95);
        assertThat(application.get("activePolicies")).extracting(JsonNode::asText).containsExactly("LFU", "LRU");
        assertThat(application.get("policyLabel").asText()).isEqualTo("MIXED");
        assertThat(application.get("regions")).hasSize(2);
        assertThat(application.get("projectName").asText()).startsWith("Project ");

        JsonNode overview = json(getAs("viewer", "/api/v1/overview").andExpect(status().isOk()));
        assertThat(overview.get("cacheRegionsMonitored").asInt()).isEqualTo(2);
        assertThat(overview.get("applicationsMonitored").asInt()).isEqualTo(1);
        assertThat(overview.get("totals").get("hits").asLong()).isEqualTo(1450);
        assertThat(overview.get("regions")).hasSize(2);

        JsonNode project = json(getAs("viewer", "/api/v1/projects/" + app.projectId() + "/metrics").andExpect(status().isOk()));
        assertThat(project.get("regionCount").asInt()).isEqualTo(2);
        assertThat(project.get("totals").get("hits").asLong()).isEqualTo(1450);
    }

    @Test
    void onlyTheLatestSnapshotPerInstanceCountsTowardsTotals() throws Exception {
        TestApp app = createApp("claims-service");
        Instant now = Instant.now();
        send(app, "pod-a", TestData.snap().at(now.minusSeconds(20)).counters(100, 10, 10, 0, 0).build());
        send(app, "pod-a", TestData.snap().at(now.minusSeconds(10)).counters(300, 30, 30, 0, 0).build());
        JsonNode region = json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/regions/claim-rules/metrics"));
        assertThat(region.get("hits").asLong()).isEqualTo(300);
        assertThat(region.get("instanceCount").asInt()).isEqualTo(1);
    }

    @Test
    void emptyStateHasZeroTotalsEmptyListsAndNoDataSummaries() throws Exception {
        TestApp app = createApp("claims-service");
        JsonNode overview = json(getAs("viewer", "/api/v1/overview").andExpect(status().isOk()));
        assertThat(overview.get("projectsMonitored").asInt()).isEqualTo(1);
        assertThat(overview.get("applicationsMonitored").asInt()).isEqualTo(1);
        assertThat(overview.get("cacheRegionsMonitored").asInt()).isZero();
        assertThat(overview.get("totals").get("hits").asLong()).isZero();
        assertThat(overview.get("totals").get("hitRate").asDouble()).isZero();
        assertThat(overview.get("activeAlerts")).isEmpty();
        assertThat(overview.get("latestRecommendations")).isEmpty();
        assertThat(overview.get("regions")).isEmpty();
        JsonNode summary = overview.get("applications").get(0);
        assertThat(summary.get("policyLabel").asText()).isEqualTo("NONE");
        assertThat(summary.get("recommendationSummary").asText()).isEqualTo("No data yet");
        assertThat(summary.get("lastTelemetryAt").isNull()).isTrue();
        assertThat(summary.get("secondsSinceLastTelemetry").isNull()).isTrue();
        assertThat(summary.get("health").get("status").asText()).isEqualTo("UNKNOWN");
        assertThat(json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/regions"))).isEmpty();
    }

    @Test
    void unknownApplicationAndRegionAre404() throws Exception {
        TestApp app = createApp("claims-service");
        getAs("viewer", "/api/v1/applications/999999/metrics").andExpect(status().isNotFound());
        getAs("viewer", "/api/v1/applications/" + app.appId() + "/regions/nope/metrics").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        getAs("viewer", "/api/v1/applications/" + app.appId() + "/regions/nope/health").andExpect(status().isNotFound());
        getAs("viewer", "/api/v1/projects/999999/metrics").andExpect(status().isNotFound());
        getAs("viewer", "/api/v1/applications/abc/metrics").andExpect(status().isBadRequest());
    }

    @Test
    void healthComesFromTheSdkEvaluatorAndAlertsCarryItsReasonsVerbatim() throws Exception {
        TestApp app = createApp("claims-service");
        // 482 hits / 518 misses = 48.2% hit rate, 93% memory, 436 recent evictions
        send(app, "pod-a", TestData.snap().region("claim-rules").counters(482, 518, 700, 436, 0).memory(9_300, 10_000)
                .recent(400, 400, 600, 436, 0).build());
        // healthy region
        send(app, "pod-a", TestData.snap().region("member-lookup").counters(950, 50, 100, 0, 0).memory(2_000, 10_000).build());

        CacheHealthReport expected = CacheHealthEvaluator.evaluate(new CacheHealthEvaluator.Input(1000, 48.2, 93.0, 436, 0, 600,
                10, 0, CacheRiskLevel.MEDIUM, 0, 0.0, 0L));
        JsonNode health = json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/regions/claim-rules/health")
                .andExpect(status().isOk()));
        assertThat(health.get("status").asText()).isEqualTo(expected.status().name()).isEqualTo("WARNING");
        assertThat(health.get("score").asInt()).isEqualTo(expected.score());
        List<String> reasons = new ArrayList<>();
        health.get("reasons").forEach(n -> reasons.add(n.asText()));
        assertThat(reasons).containsExactlyElementsOf(expected.reasons());
        assertThat(reasons).contains("Hit rate is 48.2%, below 65% target.", "Memory utilization is 93%.",
                "436 evictions occurred in the last 10 minutes.");

        CacheHealthReport healthy = CacheHealthEvaluator.evaluate(new CacheHealthEvaluator.Input(1000, 95.0, 20.0, 0, 0, 100, 10, 0,
                CacheRiskLevel.MEDIUM, 0, 0.0, 0L));
        JsonNode healthyJson = json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/regions/member-lookup/health"));
        assertThat(healthyJson.get("status").asText()).isEqualTo(healthy.status().name()).isEqualTo("EXCELLENT");
        assertThat(healthyJson.get("score").asInt()).isEqualTo(healthy.score());

        // application health is the worst region health
        JsonNode summary = json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/metrics"));
        assertThat(summary.get("health").get("status").asText()).isEqualTo("WARNING");

        // one alert per health reason of a WARNING region
        JsonNode alerts = json(getAs("viewer", "/api/v1/overview")).get("activeAlerts");
        assertThat(alerts).hasSize(expected.reasons().size());
        List<String> messages = new ArrayList<>();
        alerts.forEach(a -> {
            messages.add(a.get("message").asText());
            assertThat(a.get("cacheRegion").asText()).isEqualTo("claim-rules");
            assertThat(a.get("severity").asText()).isEqualTo("WARNING");
            assertThat(a.get("id").asText()).startsWith(app.appId() + ":claim-rules:");
            assertThat(a.get("since").asText()).isNotBlank();
        });
        assertThat(messages).containsExactlyInAnyOrderElementsOf(expected.reasons());
    }

    @Test
    void silentRegionTurnsCriticalFromServerSideReceiptTime() throws Exception {
        TestApp app = createApp("claims-service");
        send(app, "pod-a", TestData.snap().counters(900, 100, 100, 0, 0).build());
        jdbc.update("update cache_metrics_snapshot set received_at = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(200)));
        JsonNode health = json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/regions/claim-rules/health"));
        assertThat(health.get("status").asText()).isEqualTo("CRITICAL");
        assertThat(health.get("reasons").get(0).asText()).startsWith("No telemetry received for 2");
        JsonNode alert = json(getAs("viewer", "/api/v1/overview")).get("activeAlerts").get(0);
        assertThat(alert.get("severity").asText()).isEqualTo("CRITICAL");
        assertThat(alert.get("id").asText()).endsWith(":TELEMETRY_SILENT");
    }

    @Test
    void timelineBucketsDeltasBetweenConsecutiveSnapshotsIncludingEmptyBuckets() throws Exception {
        TestApp app = createApp("claims-service");
        Instant now = Instant.now();
        Instant t0 = now.minusSeconds(60);
        Instant t1 = now.minusSeconds(50);
        Instant t2 = now.minusSeconds(40);
        send(app, "pod-a", TestData.snap().at(t0).counters(0, 0, 0, 0, 0).build());
        send(app, "pod-a", TestData.snap().at(t1).counters(100, 20, 20, 5, 0).build());
        send(app, "pod-a", TestData.snap().at(t2).counters(250, 50, 45, 7, 2).build());

        JsonNode tl = json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/regions/claim-rules/timeline?minutes=15&bucketSeconds=5")
                .andExpect(status().isOk()));
        assertThat(tl.get("cacheRegion").asText()).isEqualTo("claim-rules");
        assertThat(tl.get("bucketSeconds").asInt()).isEqualTo(5);
        JsonNode points = tl.get("points");
        assertThat(points).hasSize(15 * 60 / 5 + 1);
        long totalHits = 0, totalMisses = 0, totalEvictions = 0, totalExpirations = 0, totalPuts = 0;
        for (JsonNode p : points) {
            totalHits += p.get("hits").asLong();
            totalMisses += p.get("misses").asLong();
            totalPuts += p.get("puts").asLong();
            totalEvictions += p.get("evictions").asLong();
            totalExpirations += p.get("expirations").asLong();
            assertThat(p.get("hits").asLong()).isGreaterThanOrEqualTo(0);
        }
        assertThat(totalHits).isEqualTo(250);
        assertThat(totalMisses).isEqualTo(50);
        assertThat(totalPuts).isEqualTo(45);
        assertThat(totalEvictions).isEqualTo(7);
        assertThat(totalExpirations).isEqualTo(2);
        // oldest first, consecutive, each delta lands in the bucket of the later snapshot
        assertThat(points.get(0).get("bucketStart").asText()).isLessThan(points.get(1).get("bucketStart").asText());
        JsonNode b1 = pointAt(points, t1, 5);
        assertThat(b1.get("hits").asLong()).isEqualTo(100);
        assertThat(b1.get("misses").asLong()).isEqualTo(20);
        assertThat(b1.get("hitRate").asDouble()).isEqualTo(83.33);
        JsonNode b2 = pointAt(points, t2, 5);
        assertThat(b2.get("hits").asLong()).isEqualTo(150);
        assertThat(b2.get("misses").asLong()).isEqualTo(30);
        assertThat(b2.get("hitRate").asDouble()).isEqualTo(83.33);
        // an empty bucket is present and reports zeros
        JsonNode empty = points.get(0);
        assertThat(empty.get("hits").asLong()).isZero();
        assertThat(empty.get("hitRate").asDouble()).isZero();

        JsonNode appTl = json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/timeline?minutes=5&bucketSeconds=10"));
        assertThat(appTl.get("cacheRegion").isNull()).isTrue();
        assertThat(sum(appTl.get("points"), "hits")).isEqualTo(250);
        JsonNode globalTl = json(getAs("viewer", "/api/v1/timeline"));
        assertThat(globalTl.get("cacheRegion").isNull()).isTrue();
        assertThat(globalTl.get("bucketSeconds").asInt()).isEqualTo(10);
        assertThat(sum(globalTl.get("points"), "hits")).isEqualTo(250);
    }

    @Test
    void counterDecreaseIsTreatedAsInstanceRestartNeverNegative() throws Exception {
        TestApp app = createApp("claims-service");
        Instant now = Instant.now();
        send(app, "pod-a", TestData.snap().at(now.minusSeconds(90)).counters(1000, 100, 100, 10, 1).build());
        send(app, "pod-a", TestData.snap().at(now.minusSeconds(80)).counters(1100, 120, 110, 12, 1).build());   // +100 / +20
        send(app, "pod-a", TestData.snap().at(now.minusSeconds(70)).counters(30, 5, 4, 0, 0).build());          // restart
        send(app, "pod-a", TestData.snap().at(now.minusSeconds(60)).counters(80, 15, 9, 1, 0).build());         // +50 / +10

        JsonNode tl = json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/regions/claim-rules/timeline?minutes=5&bucketSeconds=10"));
        for (JsonNode p : tl.get("points")) {
            for (String f : List.of("hits", "misses", "puts", "evictions", "expirations")) {
                assertThat(p.get(f).asLong()).as(f).isGreaterThanOrEqualTo(0);
            }
        }
        assertThat(sum(tl.get("points"), "hits")).isEqualTo(100 + 30 + 50);
        assertThat(sum(tl.get("points"), "misses")).isEqualTo(20 + 5 + 10);
        // displayed totals follow the latest snapshot of the instance (counters since its restart)
        JsonNode region = json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/regions/claim-rules/metrics"));
        assertThat(region.get("hits").asLong()).isEqualTo(80);
    }

    @Test
    void timelineParametersAreValidatedAndUnknownRegionIs404() throws Exception {
        TestApp app = createApp("claims-service");
        String base = "/api/v1/applications/" + app.appId() + "/timeline";
        getAs("viewer", base + "?minutes=0").andExpect(status().isBadRequest());
        getAs("viewer", base + "?minutes=121").andExpect(status().isBadRequest());
        getAs("viewer", base + "?bucketSeconds=4").andExpect(status().isBadRequest());
        getAs("viewer", base + "?bucketSeconds=301").andExpect(status().isBadRequest());
        getAs("viewer", "/api/v1/applications/" + app.appId() + "/regions/nope/timeline").andExpect(status().isNotFound());
        getAs("viewer", "/api/v1/applications/999999/timeline").andExpect(status().isNotFound());
        getAs("viewer", "/api/v1/timeline?minutes=120&bucketSeconds=300").andExpect(status().isOk());
    }

    @Test
    void eventsAreReturnedNewestFirstWithFiltersAndOnlyFingerprints() throws Exception {
        TestApp app = createApp("claims-service");
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        List<com.acentra.cache.telemetry.TelemetryEvent> events = new ArrayList<>();
        CacheAction[] actions = {CacheAction.HIT, CacheAction.MISS, CacheAction.EVICTED, CacheAction.EXPIRED, CacheAction.MISS};
        for (int i = 0; i < actions.length; i++) {
            var template = TestData.event("claim-rules", actions[i], "sha256:4a1d9c03be7f2a6" + i);
            events.add(new com.acentra.cache.telemetry.TelemetryEvent(base.minusSeconds(100 - i * 10L), null, null,
                    template.cacheRegion(), template.action(), template.keyFingerprint(), template.reason(), template.policy(),
                    template.frequency(), template.lastAccessAgeMs(), template.remainingTtlMs(), template.estimatedEntrySizeBytes(),
                    template.cacheSizeBefore(), template.cacheSizeAfter(), template.memoryBeforeBytes(), template.memoryAfterBytes(),
                    template.valueReturned(), template.severity(), template.latencyMs()));
        }
        events.add(TestData.event("member-lookup", CacheAction.HIT, null));
        ingest(app.apiKey(), TestData.batch(null, null, "pod-a", events, List.of())).andExpect(status().isAccepted());

        String url = "/api/v1/applications/" + app.appId();
        JsonNode all = json(getAs("viewer", url + "/regions/claim-rules/events").andExpect(status().isOk()));
        assertThat(all).hasSize(5);
        assertThat(all.get(0).get("timestamp").asText()).isGreaterThan(all.get(4).get("timestamp").asText());
        assertThat(all.get(0).get("keyFingerprint").asText()).startsWith("sha256:");
        assertThat(all.get(0).get("applicationName").asText()).isEqualTo("claims-service");
        assertThat(all.get(0).fieldNames()).toIterable().doesNotContain("rawKey", "key", "value");

        JsonNode filtered = json(getAs("viewer", url + "/regions/claim-rules/events?actions=MISS,EVICTED"));
        assertThat(filtered).hasSize(3);
        filtered.forEach(e -> assertThat(e.get("action").asText()).isIn("MISS", "EVICTED"));
        assertThat(json(getAs("viewer", url + "/regions/claim-rules/events?limit=2"))).hasSize(2);
        assertThat(json(getAs("viewer", url + "/events"))).hasSize(6);
        assertThat(json(getAs("viewer", url + "/regions/claim-rules/events?since=" + base.minusSeconds(85)))).hasSize(3);

        getAs("viewer", url + "/regions/claim-rules/events?limit=501").andExpect(status().isBadRequest());
        getAs("viewer", url + "/regions/claim-rules/events?actions=BOGUS").andExpect(status().isBadRequest());
        getAs("viewer", url + "/regions/claim-rules/events?since=yesterday").andExpect(status().isBadRequest());
        getAs("viewer", url + "/regions/unknown/events").andExpect(status().isNotFound());
    }

    private static JsonNode pointAt(JsonNode points, Instant t, int bucketSeconds) {
        Instant bucket = Instant.ofEpochSecond(Math.floorDiv(t.getEpochSecond(), bucketSeconds) * bucketSeconds);
        for (JsonNode p : points) {
            if (Instant.parse(p.get("bucketStart").asText()).equals(bucket)) return p;
        }
        throw new AssertionError("no bucket for " + bucket);
    }

    private static long sum(JsonNode points, String field) {
        long total = 0;
        for (JsonNode p : points) total += p.get(field).asLong();
        return total;
    }
}

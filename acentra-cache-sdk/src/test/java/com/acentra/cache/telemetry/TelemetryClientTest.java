package com.acentra.cache.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.acentra.cache.AcentraCache;
import com.acentra.cache.AcentraCacheManager;
import com.acentra.cache.CacheRegionConfig;
import com.acentra.cache.EvictionPolicy;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TelemetryClientTest {
    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(202);
    private volatile String controlJson = "{\"regions\":[]}";

    private String start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/telemetry/events/batch", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            keys.add(ex.getRequestHeaders().getFirst("X-AcentraCache-Key"));
            ex.sendResponseHeaders(status.get(), -1);
            ex.close();
        });
        server.createContext("/api/v1/telemetry/control", ex -> {
            byte[] b = controlJson.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static void awaitTrue(java.util.function.BooleanSupplier s, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!s.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(25);
    }

    @Test
    void batchesEventsAndSnapshotsWithApiKeyAndNeverSendsRawKeysOrValues() throws Exception {
        String url = start();
        try (AcentraCacheManager mgr = AcentraCacheManager.builder().applicationName("claims-service").environment("staging")
                .keySalt("salt").telemetryEndpoint(url).telemetryApiKey("acc_test0001_00000000000000000000000000000001")
                .flushInterval(Duration.ofMillis(200)).snapshotInterval(Duration.ofMillis(100)).build()) {
            AcentraCache<String, String> c = mgr.cache(CacheRegionConfig.builder().regionName("eligibility-summary")
                    .maximumEntries(5).routineEventSampling(1));
            for (int i = 0; i < 20; i++) {
                c.put("eligibility:member:987654321-" + i, "DIAGNOSIS-secret-" + i);
                c.get("eligibility:member:987654321-" + i);
            }
            awaitTrue(() -> bodies.stream().anyMatch(b -> b.contains("\"events\":[{")) && bodies.stream().anyMatch(b -> b.contains("\"snapshots\":[{")), 5000);
            assertThat(bodies).isNotEmpty();
            String all = String.join("\n", bodies);
            assertThat(all).contains("\"applicationName\":\"claims-service\"").contains("\"environment\":\"staging\"")
                    .contains("\"cacheRegion\":\"eligibility-summary\"").contains("sha256:");
            assertThat(all).doesNotContain("987654321").doesNotContain("DIAGNOSIS").doesNotContain("secret-");
            assertThat(keys).allMatch(k -> k.startsWith("acc_test0001_"));
            awaitTrue(() -> mgr.telemetry().eventsSent("eligibility-summary") > 0, 3000);
            assertThat(mgr.telemetry().eventsSent("eligibility-summary")).isGreaterThan(0);
            assertThat(mgr.telemetry().failureStreak()).isZero();
        }
    }

    @Test
    void batchSizeThresholdTriggersEarlySend() throws Exception {
        String url = start();
        BatchingHttpTelemetryClient client = new BatchingHttpTelemetryClient(BatchingHttpTelemetryClient.config()
                .endpoint(url).apiKey("acc_k").applicationName("a").flushInterval(Duration.ofSeconds(30)).batchSize(10)
                .controlPollingEnabled(false));
        try {
            for (int i = 0; i < 12; i++) client.publishEvent(sampleEvent());
            awaitTrue(() -> !bodies.isEmpty(), 3000);
            assertThat(bodies).as("sent before the 30 s interval because 10 events queued").isNotEmpty();
        } finally {
            client.close();
        }
    }

    @Test
    void serviceDownNeverBreaksCacheAndCountsFailures() throws Exception {
        // nothing listens on this port
        try (AcentraCacheManager mgr = AcentraCacheManager.builder().applicationName("claims-service")
                .telemetryEndpoint("http://127.0.0.1:1").telemetryApiKey("acc_k")
                .flushInterval(Duration.ofMillis(150)).snapshotInterval(Duration.ofMillis(100)).build()) {
            AcentraCache<String, String> c = mgr.cache(CacheRegionConfig.builder().regionName("claim-rules").maximumEntries(50).routineEventSampling(1));
            long t0 = System.nanoTime();
            for (int i = 0; i < 20_000; i++) {
                c.put("k" + (i % 40), "v");
                c.get("k" + (i % 40));
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(ms).as("cache stays fast while telemetry is unreachable").isLessThan(8000);
            assertThat(c.get("k1")).isPresent();
            awaitTrue(() -> mgr.telemetry().eventsFailed("claim-rules") > 0, 5000);
            assertThat(mgr.telemetry().eventsFailed("claim-rules")).isGreaterThan(0);
            assertThat(mgr.telemetry().failureStreak()).isGreaterThan(0);
            assertThat(c.getHealth().reasons()).isNotEmpty();
        }
    }

    @Test
    void retriesWithBackoffAfterServerErrorAndRecovers() throws Exception {
        String url = start();
        status.set(503);
        BatchingHttpTelemetryClient client = new BatchingHttpTelemetryClient(BatchingHttpTelemetryClient.config()
                .endpoint(url).apiKey("acc_k").applicationName("a").flushInterval(Duration.ofMillis(100)).batchSize(5)
                .initialBackoff(Duration.ofMillis(100)).maxBackoff(Duration.ofMillis(400)).controlPollingEnabled(false));
        try {
            for (int i = 0; i < 6; i++) client.publishEvent(sampleEvent());
            awaitTrue(() -> client.failureStreak() >= 1, 3000);
            assertThat(client.failureStreak()).isGreaterThanOrEqualTo(1);
            status.set(202);
            awaitTrue(() -> client.failureStreak() == 0 && client.eventsSent("claim-rules") >= 6, 6000);
            assertThat(client.failureStreak()).isZero();
            assertThat(client.eventsSent("claim-rules")).as("retried events were delivered").isGreaterThanOrEqualTo(6);
        } finally {
            client.close();
        }
    }

    @Test
    void clientErrorBatchesAreDroppedNotRetriedForever() throws Exception {
        String url = start();
        status.set(400);
        BatchingHttpTelemetryClient client = new BatchingHttpTelemetryClient(BatchingHttpTelemetryClient.config()
                .endpoint(url).apiKey("acc_k").applicationName("a").flushInterval(Duration.ofMillis(100)).batchSize(3)
                .initialBackoff(Duration.ofMillis(50)).controlPollingEnabled(false));
        try {
            for (int i = 0; i < 3; i++) client.publishEvent(sampleEvent());
            awaitTrue(() -> client.eventsFailed("claim-rules") >= 3, 3000);
            int before = bodies.size();
            Thread.sleep(600);
            assertThat(bodies.size() - before).as("no endless retry of a rejected batch").isLessThanOrEqualTo(3);
        } finally {
            client.close();
        }
    }

    @Test
    void controlDirectivesApplyOnlyApprovedPolicyAndTuningOnce() throws Exception {
        String url = start();
        controlJson = "{\"regions\":[{\"cacheRegion\":\"claim-rules\",\"desiredPolicy\":\"LFU\",\"policyRequestId\":3,"
                + "\"tuning\":{\"maximumEntries\":7,\"maximumMemoryBytes\":null,\"defaultTtlMs\":60000},\"tuningVersion\":2}]}";
        try (AcentraCacheManager mgr = AcentraCacheManager.builder().applicationName("claims-service")
                .telemetryEndpoint(url).telemetryApiKey("acc_k").flushInterval(Duration.ofMillis(100))
                .snapshotInterval(Duration.ofMillis(100)).build()) {
            AcentraCache<String, String> c = mgr.cache(CacheRegionConfig.builder().regionName("claim-rules")
                    .maximumEntries(20).defaultPolicy(EvictionPolicy.LRU));
            awaitTrue(() -> c.getCurrentPolicy() == EvictionPolicy.LFU && c.getConfig().maximumEntries() == 7, 5000);
            assertThat(c.getCurrentPolicy()).isEqualTo(EvictionPolicy.LFU);
            assertThat(c.getConfig().maximumEntries()).isEqualTo(7);
            assertThat(c.getConfig().defaultTtl()).isEqualTo(Duration.ofSeconds(60));
            // a local change is not reverted by the same, already-applied request
            c.changePolicy(EvictionPolicy.LRU);
            Thread.sleep(500);
            assertThat(c.getCurrentPolicy()).isEqualTo(EvictionPolicy.LRU);
        }
    }

    private static TelemetryEvent sampleEvent() {
        return new TelemetryEvent(java.time.Instant.now(), "a", "local", "claim-rules", com.acentra.cache.CacheAction.MISS,
                "sha256:0123456789abcdef", "Key not present", EvictionPolicy.LRU, 0, 0, 0, 0, 0, 0, 0, 0, false,
                com.acentra.cache.EventSeverity.INFO, 0.1);
    }
}

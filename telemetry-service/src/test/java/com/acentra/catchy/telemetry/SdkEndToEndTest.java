package com.acentra.catchy.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.acentra.cache.AcentraCache;
import com.acentra.cache.AcentraCacheManager;
import com.acentra.cache.CacheRegionConfig;
import com.acentra.cache.EvictionPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The real SDK (AcentraCacheManager with its HTTP telemetry client) talking to this service over HTTP: ingestion
 * with an API key, aggregated metrics, privacy of the stored data, and the approve-then-apply control loop for a policy
 * change and an admin configuration change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:catchy-e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
class SdkEndToEndTest {

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> r) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isBetween(200, 299);
        return r.body().isBlank() ? null : mapper.readTree(r.body());
    }

    private String login(String user, String password) throws Exception {
        return ok(call("POST", "/api/v1/auth/login", null, "{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}"))
                .get("token").asText();
    }

    @Test
    void sdkIngestsAggregatesAndAppliesApprovedPolicyAndConfiguration() throws Exception {
        String admin = login("admin", "test-admin-pass");
        String engineer = login("engineer", "test-engineer-pass");
        long projectId = ok(call("POST", "/api/v1/projects", admin, "{\"name\":\"E2E Platform\"}")).get("id").asLong();
        long appId = ok(call("POST", "/api/v1/projects/" + projectId + "/applications", admin,
                "{\"name\":\"claims-service\",\"environment\":\"staging\"}")).get("id").asLong();
        String apiKey = ok(call("POST", "/api/v1/applications/" + appId + "/api-keys", admin, "{\"label\":\"e2e\"}")).get("apiKey").asText();

        try (AcentraCacheManager manager = AcentraCacheManager.builder()
                .applicationName("claims-service").environment("staging").instanceId("e2e-instance-1")
                .keySalt("e2e-test-salt")
                .telemetryEndpoint("http://localhost:" + port).telemetryApiKey(apiKey)
                .flushInterval(Duration.ofMillis(200)).snapshotInterval(Duration.ofMillis(100))
                .cleanupInterval(Duration.ofSeconds(1)).build()) {
            AcentraCache<String, String> cache = manager.cache(CacheRegionConfig.builder().regionName("claim-rules")
                    .maximumEntries(500).defaultPolicy(EvictionPolicy.LRU).routineEventSampling(1));

            // traffic with deliberately recognisable raw keys that must never reach the service
            for (int i = 0; i < 150; i++) cache.put("member-ssn-123456789-" + i, "patient-value-" + i);
            for (int i = 0; i < 150; i++) cache.get("member-ssn-123456789-" + i);
            for (int i = 0; i < 50; i++) cache.get("unknown-claim-" + i);

            await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
                HttpResponse<String> r = call("GET", "/api/v1/applications/" + appId + "/regions/claim-rules/metrics", engineer, null);
                assertThat(r.statusCode()).isEqualTo(200);
                JsonNode m = mapper.readTree(r.body());
                assertThat(m.get("hits").asLong()).isEqualTo(150);
                assertThat(m.get("misses").asLong()).isEqualTo(50);
                assertThat(m.get("hitRate").asDouble()).isEqualTo(75.0);
                assertThat(m.get("puts").asLong()).isEqualTo(150);
                assertThat(m.get("shadow").isNull()).isFalse();
                assertThat(m.get("instanceCount").asInt()).isEqualTo(1);
            });

            // events arrive as fingerprints only; raw keys and values appear nowhere in responses or storage
            JsonNode events = ok(call("GET", "/api/v1/applications/" + appId + "/regions/claim-rules/events?limit=500", engineer, null));
            assertThat(events.size()).isGreaterThan(0);
            String everything = events.toString() + jdbc.queryForList("select reason, key_fingerprint from cache_telemetry_event").toString()
                    + jdbc.queryForList("select * from cache_metrics_snapshot").toString()
                    + jdbc.queryForList("select details from audit_log").toString();
            assertThat(everything).doesNotContain("member-ssn").doesNotContain("patient-value").doesNotContain("unknown-claim")
                    .doesNotContain(apiKey);
            assertThat(events.toString()).contains("sha256:");
            assertThat(jdbc.queryForObject("select count(*) from cache_telemetry_event where action = 'MISS'", Long.class)).isGreaterThan(0);

            // approve-then-apply: the SDK polls /telemetry/control and switches policy only after approval
            long requestId = ok(call("POST", "/api/v1/applications/" + appId + "/policy-change-requests", engineer,
                    "{\"cacheRegion\":\"claim-rules\",\"requestedPolicy\":\"LFU\",\"reason\":\"e2e\"}")).get("id").asLong();
            Thread.sleep(800);
            assertThat(cache.getCurrentPolicy()).as("a pending request is never applied").isEqualTo(EvictionPolicy.LRU);
            ok(call("POST", "/api/v1/policy-change-requests/" + requestId + "/approve", admin, "{\"note\":\"e2e approval\"}"));
            await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
                assertThat(cache.getCurrentPolicy()).isEqualTo(EvictionPolicy.LFU);
                assertThat(status(requestId)).isEqualTo("APPLIED");
            });
            assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'POLICY_CHANGE_APPLIED'", Long.class)).isEqualTo(1);

            // admin configuration change is delivered and then reported back as applied
            ok(call("PUT", "/api/v1/applications/" + appId + "/regions/claim-rules/config", admin,
                    "{\"maximumEntries\":60,\"defaultTtlMs\":120000,\"reason\":\"e2e\"}"));
            await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
                assertThat(cache.getConfig().maximumEntries()).isEqualTo(60);
                assertThat(cache.size()).isLessThanOrEqualTo(60);
                JsonNode cfg = mapper.readTree(call("GET", "/api/v1/applications/" + appId + "/regions/claim-rules/config", engineer, null).body());
                assertThat(cfg.get("pending").asBoolean()).isFalse();
                assertThat(cfg.get("reported").get("maximumEntries").asLong()).isEqualTo(60);
                assertThat(cfg.get("reported").get("defaultTtlMs").asLong()).isEqualTo(120_000);
            });
            assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'CONFIG_CHANGE_APPLIED'", Long.class)).isEqualTo(1);
        }
    }

    private String status(long requestId) {
        return jdbc.queryForObject("select status from policy_change_request where id = ?", String.class, requestId);
    }
}

package com.acentra.catchy.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SimulationApiTest extends AbstractApiTest {

    private TestApp app;

    @BeforeEach
    void app() throws Exception {
        app = createApp("claims-service");
    }

    private JsonNode simulate(String kind, String body) throws Exception {
        return json(postAs("engineer", "/api/v1/applications/" + app.appId() + "/simulate/" + kind, body).andExpect(status().isOk()));
    }

    private List<String> regionNames(JsonNode result) {
        List<String> out = new ArrayList<>();
        result.get("cacheRegions").forEach(n -> out.add(n.asText()));
        return out;
    }

    /** The persisted actions of a region, looked up one action at a time so the page limit cannot hide old events. */
    private Set<String> eventActions(String region) throws Exception {
        Set<String> found = new java.util.TreeSet<>();
        for (String action : List.of("HIT", "MISS", "PUT", "EXPIRED", "ENTRY_LIMIT_EVICTED", "MEMORY_EVICTED", "EVICTED")) {
            JsonNode events = json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/regions/" + region
                    + "/events?limit=1&actions=" + action));
            if (events.size() > 0) found.add(action);
        }
        return found;
    }

    private JsonNode region(String name) throws Exception {
        return json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/regions/" + name + "/metrics").andExpect(status().isOk()));
    }

    @Test
    void sampleWorkloadRunsRealCachesAndAppearsAsARegion() throws Exception {
        JsonNode r = simulate("sample-workload", "{\"requests\":1000}");
        assertThat(r.get("kind").asText()).isEqualTo("sample-workload");
        assertThat(r.get("applicationId").asLong()).isEqualTo(app.appId());
        assertThat(r.get("simulationId").asText()).isNotBlank();
        assertThat(regionNames(r)).containsExactly("sim-sample-workload");
        assertThat(r.get("steps").size()).isGreaterThanOrEqualTo(4);
        assertThat(r.get("steps").get(0).get("name").asText()).contains("Pattern A");
        assertThat(r.get("hits").asLong() + r.get("misses").asLong()).isGreaterThan(1000);
        assertThat(r.get("comparison").isNull()).isTrue();
        assertThat(r.get("summary").asText()).isNotBlank();
        assertThat(r.get("durationMs").asLong()).isLessThan(5000);

        JsonNode region = region("sim-sample-workload");
        assertThat(region.get("hits").asLong()).isEqualTo(r.get("hits").asLong());
        assertThat(region.get("misses").asLong()).isEqualTo(r.get("misses").asLong());
        assertThat(region.get("puts").asLong()).isGreaterThan(0);
        assertThat(region.get("instanceCount").asInt()).isGreaterThan(1);
        assertThat(region.get("applicationName").asText()).isEqualTo("claims-service");
        assertThat(eventActions("sim-sample-workload")).contains("MISS", "ENTRY_LIMIT_EVICTED");
        assertThat(json(getAs("viewer", "/api/v1/overview")).get("regions")).hasSize(1);
        assertThat(auditCount("SIMULATION_RUN", "SUCCESS")).isEqualTo(1);
        // simulations only ever use synthetic fingerprints: no raw key material anywhere in stored events
        List<String> fingerprints = jdbc.queryForList("select key_fingerprint from cache_telemetry_event where key_fingerprint is not null", String.class);
        assertThat(fingerprints).isNotEmpty().allMatch(f -> f.matches("^sha256:[0-9a-f]{8,64}$"));
    }

    @Test
    void highLoadTriggersEntryLimitAndMemoryLimitEvictions() throws Exception {
        JsonNode r = simulate("high-load", "{\"requests\":1200}");
        assertThat(regionNames(r)).containsExactly("sim-high-load");
        assertThat(r.get("evictions").asLong()).isGreaterThan(0);
        JsonNode region = region("sim-high-load");
        assertThat(region.get("evictionsDueToEntryLimit").asLong()).isGreaterThan(0);
        assertThat(region.get("evictionsDueToMemoryLimit").asLong()).isGreaterThan(0);
        assertThat(eventActions("sim-high-load")).contains("ENTRY_LIMIT_EVICTED", "MEMORY_EVICTED");
        // heavy evictions and a low hit rate show up in the health assessment
        assertThat(region.get("health").get("status").asText()).isIn("WARNING", "CRITICAL");
    }

    @Test
    void ttlExpirationPersistsMissAndExpiredEvents() throws Exception {
        JsonNode r = simulate("ttl-expiration", null);
        assertThat(regionNames(r)).containsExactly("sim-ttl-expiration");
        assertThat(r.get("expirations").asLong()).isGreaterThan(0);
        assertThat(r.get("durationMs").asLong()).isLessThan(5000);
        assertThat(eventActions("sim-ttl-expiration")).contains("MISS", "EXPIRED");
        JsonNode region = region("sim-ttl-expiration");
        assertThat(region.get("expirations").asLong()).isGreaterThan(0);
        assertThat(region.get("misses").asLong()).isGreaterThan(0);
        // the events carry fingerprints and reasons, never the synthetic keys themselves
        assertThat(jdbc.queryForList("select reason from cache_telemetry_event", String.class).toString()).doesNotContain("ttl-batch-");
    }

    @Test
    void policyComparisonFillsComparisonAndDrivesThePolicyArena() throws Exception {
        JsonNode r = simulate("policy-comparison", "{\"requests\":3000}");
        assertThat(regionNames(r)).containsExactly("sim-policy-lru", "sim-policy-lfu");
        JsonNode comparison = r.get("comparison");
        assertThat(comparison).hasSize(2);
        JsonNode a = comparison.get(0);
        JsonNode b = comparison.get(1);
        assertThat(a.get("pattern").asText()).startsWith("A");
        assertThat(a.get("winner").asText()).isEqualTo("LRU");
        assertThat(a.get("lruHitRate").asDouble()).isGreaterThan(a.get("lfuHitRate").asDouble());
        assertThat(b.get("pattern").asText()).startsWith("B");
        assertThat(b.get("winner").asText()).isEqualTo("LFU");
        assertThat(b.get("lfuHitRate").asDouble()).isGreaterThan(b.get("lruHitRate").asDouble());
        assertThat(a.get("interpretation").asText()).isNotBlank();
        assertThat(r.get("summary").asText()).contains("LRU wins the changing-access pattern").contains("LFU wins the stable-popularity pattern");

        JsonNode lru = region("sim-policy-lru");
        JsonNode lfu = region("sim-policy-lfu");
        assertThat(lru.get("activePolicy").asText()).isEqualTo("LRU");
        assertThat(lfu.get("activePolicy").asText()).isEqualTo("LFU");
        assertThat(lru.get("shadow").isNull()).isFalse();
        // the shadow data reflects the decisive (stable popularity) pattern: LFU would beat the LRU region
        assertThat(lru.get("shadow").get("lfuHitRate").asDouble()).isGreaterThan(lru.get("shadow").get("lruHitRate").asDouble());

        JsonNode recs = json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/recommendations"));
        JsonNode lruRec = null;
        for (JsonNode rec : recs) if (rec.get("cacheRegion").asText().equals("sim-policy-lru")) lruRec = rec;
        assertThat(lruRec).isNotNull();
        assertThat(lruRec.get("action").asText()).isEqualTo("SWITCH");
        assertThat(lruRec.get("recommendedPolicy").asText()).isEqualTo("LFU");
    }

    @Test
    void requestBoundsKindsAndRolesAreEnforced() throws Exception {
        String base = "/api/v1/applications/" + app.appId() + "/simulate/";
        postAs("engineer", base + "sample-workload", "{\"requests\":49}").andExpect(status().isBadRequest());
        postAs("engineer", base + "sample-workload", "{\"requests\":20001}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        postAs("engineer", base + "warp-drive", null).andExpect(status().isNotFound());
        postAs("engineer", "/api/v1/applications/999999/simulate/sample-workload", null).andExpect(status().isNotFound());
        postAs("viewer", base + "sample-workload", null).andExpect(status().isForbidden());
        postAs("admin", base + "sample-workload", "{\"requests\":50}").andExpect(status().isOk());
    }

    @Test
    void repeatedRunsKeepCountersMonotonicPerSimulatorInstance() throws Exception {
        simulate("sample-workload", "{\"requests\":500}");
        long first = region("sim-sample-workload").get("hits").asLong();
        simulate("sample-workload", "{\"requests\":500}");
        long second = region("sim-sample-workload").get("hits").asLong();
        // each run is a fresh set of simulator caches (counters restart); the total reflects the latest run, not a sum of runs
        assertThat(second).isBetween(first - first / 2, first + first / 2);
        JsonNode timeline = json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/regions/sim-sample-workload/timeline?minutes=5&bucketSeconds=5"));
        long total = 0;
        for (JsonNode p : timeline.get("points")) {
            assertThat(p.get("hits").asLong()).isGreaterThanOrEqualTo(0);
            total += p.get("hits").asLong();
        }
        assertThat(total).as("both runs are visible as timeline deltas").isGreaterThanOrEqualTo(first + second - 1);
    }
}

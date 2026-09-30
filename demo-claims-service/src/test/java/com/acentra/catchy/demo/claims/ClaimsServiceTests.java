package com.acentra.catchy.demo.claims;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acentra.cache.AcentraCache;
import com.acentra.cache.CacheRegionConfig;
import com.acentra.cache.CacheRiskLevel;
import com.acentra.cache.EvictionPolicy;
import com.acentra.cache.telemetry.TelemetryEvent;
import com.acentra.catchy.demo.claims.ClaimsModels.ProviderGroupSummary;
import com.acentra.catchy.demo.claims.ClaimsModels.RuleSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** No network: telemetry goes to an in-process recorder instead of the telemetry service. All ids are synthetic. */
@SpringBootTest
@AutoConfigureMockMvc
class ClaimsServiceTests {

    @TestConfiguration
    static class RecorderConfig {
        @Bean
        RecordingTelemetryClient recordingTelemetryClient() {
            return new RecordingTelemetryClient();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AcentraCache<String, RuleSet> rules;
    @Autowired AcentraCache<String, ProviderGroupSummary> providers;
    @Autowired SimulatedSource source;
    @Autowired RecordingTelemetryClient telemetry;

    private static String uniqueId(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private JsonNode regionOf(JsonNode summary, String region) {
        for (JsonNode r : summary.get("regions")) if (region.equals(r.get("region").asText())) return r;
        throw new AssertionError("region missing in summary: " + region);
    }

    @Test
    void healthEndpoint() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP")).andExpect(jsonPath("$.service").value("claims-service"));
    }

    @Test
    void ruleSetReturnsDataAndServedFromFlipsFromSourceToCache() throws Exception {
        String id = uniqueId("RS-");
        mvc.perform(get("/api/claims/rules/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleSetId").value(id))
                .andExpect(jsonPath("$.version").isNotEmpty())
                .andExpect(jsonPath("$.rules.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(8)))
                .andExpect(jsonPath("$.servedFrom").value("SOURCE"));
        mvc.perform(get("/api/claims/rules/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.servedFrom").value("CACHE"));
    }

    @Test
    void providerReturnsDataAndServedFromFlipsFromSourceToCache() throws Exception {
        String id = uniqueId("PG-T-");
        mvc.perform(get("/api/providers/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.providerGroupId").value(id))
                .andExpect(jsonPath("$.displayName").value(org.hamcrest.Matchers.startsWith("Synthetic Provider Group")))
                .andExpect(jsonPath("$.servedFrom").value("SOURCE"));
        mvc.perform(get("/api/providers/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.servedFrom").value("CACHE"));
    }

    @Test
    void invalidIdsAreRejectedWith400AndNeverEchoed() throws Exception {
        String tooLong = "A".repeat(41);
        mvc.perform(get("/api/claims/rules/" + tooLong)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/claims/rules/bad_id!")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("bad_id"))));
        mvc.perform(get("/api/claims/rules/has%20space")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/providers/" + tooLong)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/providers/semi:colon")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/providers/under_score")).andExpect(status().isBadRequest());
    }

    @Test
    void repeatedWorkloadReturnsSummaryAndChangesRegionMetrics() throws Exception {
        JsonNode before = body(mvc.perform(get("/api/demo/claims/cache")).andExpect(status().isOk()));
        JsonNode summary = body(mvc.perform(post("/api/demo/claims/workload/repeated")).andExpect(status().isOk()));

        assertThat(summary.get("workload").asText()).isEqualTo("repeated");
        assertThat(summary.get("requests").asInt()).isEqualTo(300);
        assertThat(summary.get("cacheHits").asLong() + summary.get("cacheMisses").asLong()).isEqualTo(300);
        assertThat(summary.get("cacheHits").asLong()).isPositive();
        assertThat(summary.get("cacheMisses").asLong()).isPositive();
        assertThat(summary.get("sourceCallsMade").asLong()).isPositive();
        assertThat(summary.get("sourceCallsAvoided").asLong()).isPositive();
        assertThat(summary.get("evictions").asLong()).as("cold scans exceed the 40-entry region").isPositive();
        assertThat(summary.get("hint").asText()).contains("http://localhost:5173");
        JsonNode rulesDelta = regionOf(summary, "claim-rules");
        assertThat(rulesDelta.get("activePolicy").asText()).isEqualTo("LFU");
        assertThat(regionOf(summary, "provider-directory").get("activePolicy").asText()).isEqualTo("LRU");

        JsonNode after = body(mvc.perform(get("/api/demo/claims/cache")).andExpect(status().isOk()));
        JsonNode mb = before.get("regions").get(0).get("metrics");
        JsonNode ma = after.get("regions").get(0).get("metrics");
        assertThat(ma.get("hits").asLong()).isGreaterThan(mb.get("hits").asLong());
        assertThat(ma.get("misses").asLong()).isGreaterThan(mb.get("misses").asLong());
        assertThat(ma.get("evictions").asLong()).isGreaterThan(mb.get("evictions").asLong());
        assertThat(ma.get("size").asInt()).isLessThanOrEqualTo(40);
    }

    @Test
    void changingWorkloadEvictsAndAcceptsRequestsBody() throws Exception {
        JsonNode summary = body(mvc.perform(post("/api/demo/claims/workload/changing")
                .contentType(MediaType.APPLICATION_JSON).content("{\"requests\": 150}")).andExpect(status().isOk()));
        assertThat(summary.get("workload").asText()).isEqualTo("changing");
        assertThat(summary.get("requests").asInt()).isEqualTo(150);
        assertThat(summary.get("cacheHits").asLong() + summary.get("cacheMisses").asLong()).isEqualTo(150);
        assertThat(summary.get("evictions").asLong()).isPositive();
        assertThat(summary.get("sourceCallsMade").asLong()).isPositive();
    }

    @Test
    void expireWorkloadProducesMissesAndExpirations() throws Exception {
        JsonNode summary = body(mvc.perform(post("/api/demo/claims/workload/expire")).andExpect(status().isOk()));
        assertThat(summary.get("workload").asText()).isEqualTo("expire");
        assertThat(summary.get("requests").asInt()).isEqualTo(60);
        assertThat(summary.get("expirations").asLong()).as("short-TTL entries expired").isGreaterThanOrEqualTo(10);
        assertThat(summary.get("cacheHits").asLong()).as("entries were read while still fresh").isGreaterThanOrEqualTo(10);
        assertThat(summary.get("cacheMisses").asLong()).as("reads after expiry miss").isGreaterThanOrEqualTo(30);
        assertThat(regionOf(summary, "provider-directory").get("expirations").asLong()).isGreaterThanOrEqualTo(10);
        assertThat(telemetry.events()).anyMatch(e -> e.action().name().equals("EXPIRED"));
    }

    @Test
    void stampedeWorkloadCollapsesConcurrentLoadsIntoFewSourceCalls() throws Exception {
        JsonNode summary = body(mvc.perform(post("/api/demo/claims/workload/stampede")).andExpect(status().isOk()));
        assertThat(summary.get("requests").asInt()).isEqualTo(24);
        assertThat(summary.get("sourceCallsMade").asLong()).isPositive().isLessThan(24);
        assertThat(summary.get("sourceCallsAvoided").asLong()).isPositive();
    }

    @Test
    void workloadBodyBoundsAreEnforced() throws Exception {
        for (String bad : List.of("{\"requests\": 9}", "{\"requests\": 5001}", "{\"requests\": -1}")) {
            mvc.perform(post("/api/demo/claims/workload/repeated").contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/demo/claims/workload/repeated").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cacheViewShowsSafeRegionSummaries() throws Exception {
        mvc.perform(get("/api/demo/claims/cache")).andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("claims-service"))
                .andExpect(jsonPath("$.regions.length()").value(2))
                .andExpect(jsonPath("$.regions[0].region").value("claim-rules"))
                .andExpect(jsonPath("$.regions[0].riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$.regions[0].metrics.capacity").value(40))
                .andExpect(jsonPath("$.regions[0].health.status").isNotEmpty())
                .andExpect(jsonPath("$.regions[0].recommendation.approvalRequired").value(true))
                .andExpect(jsonPath("$.regions[1].region").value("provider-directory"))
                .andExpect(jsonPath("$.regions[1].staleWhileRevalidate").value(true));
    }

    @Test
    void clearEndpointEmptiesTheRegions() throws Exception {
        String id = uniqueId("RS-");
        mvc.perform(get("/api/claims/rules/" + id)).andExpect(status().isOk());
        mvc.perform(post("/api/demo/claims/cache/clear")).andExpect(status().isOk())
                .andExpect(jsonPath("$.clearedEntries['claim-rules']").isNumber());
        assertThat(rules.size()).isZero();
        mvc.perform(get("/api/claims/rules/" + id)).andExpect(jsonPath("$.servedFrom").value("SOURCE"));
    }

    @Test
    void regionsAreConfiguredAsDesigned() {
        CacheRegionConfig r = rules.getConfig();
        assertThat(r.defaultPolicy()).isEqualTo(EvictionPolicy.LFU);
        assertThat(r.riskLevel()).isEqualTo(CacheRiskLevel.MEDIUM);
        assertThat(r.defaultTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(r.maximumEntries()).isEqualTo(40);
        assertThat(r.maximumMemoryBytes()).isEqualTo(1024L * 1024L);
        assertThat(r.victimCacheEnabled()).isTrue();
        assertThat(r.staleWhileRevalidate()).isFalse();

        CacheRegionConfig p = providers.getConfig();
        assertThat(p.defaultPolicy()).isEqualTo(EvictionPolicy.LRU);
        assertThat(p.riskLevel()).isEqualTo(CacheRiskLevel.LOW);
        assertThat(p.defaultTtl()).isEqualTo(Duration.ofHours(1));
        assertThat(p.maximumEntries()).isEqualTo(200);
        assertThat(p.staleWhileRevalidate()).isTrue();
        assertThat(p.staleGrace()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void sourceIsDeterministicAndCountsCalls() {
        long before = source.ruleSetCalls();
        RuleSet a = source.loadRuleSet("RS-DETERMINISTIC");
        RuleSet b = source.loadRuleSet("RS-DETERMINISTIC");
        assertThat(a).isEqualTo(b);
        assertThat(source.ruleSetCalls()).isEqualTo(before + 2);
        assertThat(source.loadRuleSet("RS-OTHER")).isNotEqualTo(a);
    }

    @Test
    void telemetryCarriesOnlyKeyFingerprintsNeverRawIds() throws Exception {
        String id = uniqueId("RS-PRIV-");
        mvc.perform(get("/api/claims/rules/" + id)).andExpect(status().isOk());
        mvc.perform(get("/api/claims/rules/" + id)).andExpect(status().isOk());
        List<TelemetryEvent> mine = telemetry.events().stream()
                .filter(e -> e.cacheRegion().equals("claim-rules")).toList();
        assertThat(mine).isNotEmpty();
        for (TelemetryEvent e : mine) {
            assertThat(e.keyFingerprint() == null || e.keyFingerprint().matches("^sha256:[0-9a-f]{8,64}$")).isTrue();
            assertThat(String.valueOf(e.reason())).doesNotContain(id);
            assertThat(String.valueOf(e.keyFingerprint())).doesNotContain(id);
        }
        assertThat(mine.stream().map(TelemetryEvent::keyFingerprint)).anyMatch(f -> f != null && f.startsWith("sha256:"));
    }
}

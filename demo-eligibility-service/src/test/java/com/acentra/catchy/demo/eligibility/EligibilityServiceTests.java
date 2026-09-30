package com.acentra.catchy.demo.eligibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acentra.cache.AcentraCache;
import com.acentra.cache.CacheDecisionEvent;
import com.acentra.cache.CacheRegionConfig;
import com.acentra.cache.CacheRiskLevel;
import com.acentra.cache.EvictionPolicy;
import com.acentra.cache.telemetry.TelemetryEvent;
import com.acentra.catchy.demo.eligibility.EligibilityModels.AuthorizationDecision;
import com.acentra.catchy.demo.eligibility.EligibilityModels.EligibilityResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** No network: telemetry goes to an in-process recorder instead of the telemetry service. All ids are synthetic. */
@SpringBootTest
@AutoConfigureMockMvc
class EligibilityServiceTests {

    @TestConfiguration
    static class RecorderConfig {
        @Bean
        RecordingTelemetryClient recordingTelemetryClient() {
            return new RecordingTelemetryClient();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AcentraCache<String, EligibilityResult> eligibilityCache;
    @Autowired AcentraCache<String, AuthorizationDecision> authorizationCache;
    @Autowired SimulatedSource source;
    @Autowired MemberKeyHasher hasher;
    @Autowired RecordingTelemetryClient telemetry;

    /** A fresh synthetic id in a range no workload uses, so each test starts with nothing cached for it. */
    private static String synId() {
        return String.format("SYN-%09d", ThreadLocalRandom.current().nextInt(300_000_000, 800_000_000));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private JsonNode regionOf(JsonNode summary, String region) {
        for (JsonNode r : summary.get("regions")) if (region.equals(r.get("region").asText())) return r;
        throw new AssertionError("region missing in summary: " + region);
    }

    @Test
    void healthEndpoint() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP")).andExpect(jsonPath("$.service").value("eligibility-service"));
    }

    @Test
    void eligibilityReturnsMinimalResultAndServedFromFlipsFromSourceToCache() throws Exception {
        String id = synId();
        JsonNode first = body(mvc.perform(get("/api/eligibility/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberRef").value(id)).andExpect(jsonPath("$.servedFrom").value("SOURCE"))
                .andExpect(jsonPath("$.active").isBoolean()).andExpect(jsonPath("$.planTier").isNotEmpty())
                .andExpect(jsonPath("$.note").isNotEmpty()));
        JsonNode second = body(mvc.perform(get("/api/eligibility/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.servedFrom").value("CACHE")));
        assertThat(second.get("active")).isEqualTo(first.get("active"));
        assertThat(second.get("planTier")).isEqualTo(first.get("planTier"));
        // minimal derived result only: no other member attributes in the response
        assertThat(first.fieldNames()).toIterable().containsExactlyInAnyOrder("memberRef", "active", "planTier", "asOf",
                "servedFrom", "note");
    }

    @Test
    void invalidIdsAreRejectedWith400AndNeverEchoed() throws Exception {
        for (String bad : List.of("BAD-123", "SYN-", "SYN-1234567890", "syn-000123", "SYN-00012a", "SYN_000123", "SYN-12%2034")) {
            mvc.perform(get("/api/eligibility/" + bad)).andExpect(status().isBadRequest());
            mvc.perform(post("/api/eligibility/" + bad + "/validate")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/authorization/" + bad + "/preliminary")).andExpect(status().isBadRequest());
            mvc.perform(post("/api/authorization/" + bad + "/finalize")).andExpect(status().isBadRequest());
            mvc.perform(post("/api/demo/eligibility/source/" + bad + "/change")).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/eligibility/BAD-123")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(not(org.hamcrest.Matchers.containsString("BAD-123"))));
        // boundary: 9 digits is fine
        mvc.perform(get("/api/eligibility/SYN-123456789")).andExpect(status().isOk());
    }

    @Test
    void cacheKeyIsASaltedHashAndTheRawIdNeverReachesTheCacheOrTelemetry() throws Exception {
        String id = synId();
        mvc.perform(get("/api/eligibility/" + id)).andExpect(status().isOk());
        mvc.perform(get("/api/eligibility/" + id)).andExpect(status().isOk());
        mvc.perform(post("/api/eligibility/" + id + "/validate")).andExpect(status().isOk());

        String hashed = hasher.memberKey(id);
        assertThat(hashed).matches("^[0-9a-f]{64}$").doesNotContain(id);
        assertThat(eligibilityCache.peekEntry(hashed)).as("entry is stored under the hashed key").isPresent();
        assertThat(eligibilityCache.peekEntry(id)).as("the raw id is not a cache key").isEmpty();
        assertThat(eligibilityCache.peekEntry("eligibility:member:" + id)).isEmpty();

        // what the cache reports locally (Eviction X-ray) ...
        List<CacheDecisionEvent> local = eligibilityCache.getDecisionEvents();
        assertThat(local).isNotEmpty();
        for (CacheDecisionEvent e : local) {
            assertThat(e.keyFingerprint() == null || e.keyFingerprint().startsWith("sha256:")).isTrue();
            assertThat(String.valueOf(e.keyFingerprint())).doesNotContain(id);
            assertThat(e.reason()).doesNotContain(id).doesNotContain("SYN-");
        }
        assertThat(local.stream().map(CacheDecisionEvent::keyFingerprint)).anyMatch(f -> f != null && f.startsWith("sha256:"));

        // ... and exactly what would be sent to the telemetry service
        List<TelemetryEvent> sent = telemetry.events();
        assertThat(sent).isNotEmpty();
        for (TelemetryEvent e : sent) {
            assertThat(e.keyFingerprint() == null || e.keyFingerprint().matches("^sha256:[0-9a-f]{8,64}$")).isTrue();
            for (String s : new String[] {e.keyFingerprint(), e.reason(), e.cacheRegion(), e.applicationName(), e.environment()}) {
                assertThat(String.valueOf(s)).doesNotContain(id).doesNotContain("SYN-");
            }
        }
    }

    @Test
    void validateDetectsDriftAfterTheSourceChangesAndCorrectsTheCache() throws Exception {
        String id = synId();
        JsonNode cached = body(mvc.perform(get("/api/eligibility/" + id)).andExpect(status().isOk()));
        boolean activeBefore = cached.get("active").asBoolean();

        mvc.perform(post("/api/demo/eligibility/source/" + id + "/change")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceStatus").value(activeBefore ? "INACTIVE" : "ACTIVE"));

        // the cache is now stale but still served within its TTL: this is the risk
        mvc.perform(get("/api/eligibility/" + id)).andExpect(jsonPath("$.servedFrom").value("CACHE"))
                .andExpect(jsonPath("$.active").value(activeBefore));

        long calls = source.eligibilityCalls();
        mvc.perform(post("/api/eligibility/" + id + "/validate")).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberRef").value(id))
                .andExpect(jsonPath("$.cachedBefore.active").value(activeBefore))
                .andExpect(jsonPath("$.sourceValue.active").value(!activeBefore))
                .andExpect(jsonPath("$.driftDetected").value(true))
                .andExpect(jsonPath("$.cacheCorrected").value(true));
        assertThat(source.eligibilityCalls()).as("validate always asks the source").isEqualTo(calls + 1);

        // the cache now holds the corrected value
        mvc.perform(get("/api/eligibility/" + id)).andExpect(jsonPath("$.servedFrom").value("CACHE"))
                .andExpect(jsonPath("$.active").value(!activeBefore));
        // and a second validation finds no drift
        mvc.perform(post("/api/eligibility/" + id + "/validate")).andExpect(jsonPath("$.driftDetected").value(false))
                .andExpect(jsonPath("$.cacheCorrected").value(false));
        assertThat(eligibilityCache.getMetrics().staleCorrections()).isPositive();
    }

    @Test
    void validateWithNothingCachedLoadsFromSourceWithoutDrift() throws Exception {
        String id = synId();
        mvc.perform(post("/api/eligibility/" + id + "/validate")).andExpect(status().isOk())
                .andExpect(jsonPath("$.cachedBefore").value(nullValue()))
                .andExpect(jsonPath("$.sourceValue.planTier").isNotEmpty())
                .andExpect(jsonPath("$.driftDetected").value(false));
        mvc.perform(get("/api/eligibility/" + id)).andExpect(jsonPath("$.servedFrom").value("CACHE"));
    }

    @Test
    void preliminaryAuthorizationIsFlaggedPreliminaryAndNeverFinal() throws Exception {
        String id = synId();
        mvc.perform(get("/api/authorization/" + id + "/preliminary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.requestRef").value(id))
                .andExpect(jsonPath("$.preliminary").value(true))
                .andExpect(jsonPath("$.finalDecisionAllowed").value(false))
                .andExpect(jsonPath("$.servedFrom").value("SOURCE"))
                .andExpect(jsonPath("$.status").isNotEmpty());
        mvc.perform(get("/api/authorization/" + id + "/preliminary"))
                .andExpect(jsonPath("$.preliminary").value(true))
                .andExpect(jsonPath("$.finalDecisionAllowed").value(false))
                .andExpect(jsonPath("$.servedFrom").value("CACHE"));
    }

    @Test
    void finalizeAlwaysCallsTheSourceEvenWhenTheDecisionIsCached() throws Exception {
        String id = synId();
        mvc.perform(get("/api/authorization/" + id + "/preliminary")).andExpect(jsonPath("$.servedFrom").value("SOURCE"));
        mvc.perform(get("/api/authorization/" + id + "/preliminary")).andExpect(jsonPath("$.servedFrom").value("CACHE"));

        long calls = source.authorizationCalls();
        long regionCalls = authorizationCache.getMetrics().sourceCalls();
        for (int i = 1; i <= 3; i++) {
            mvc.perform(post("/api/authorization/" + id + "/finalize")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestRef").value(id))
                    .andExpect(jsonPath("$.preliminary").value(false))
                    .andExpect(jsonPath("$.validatedAgainstSource").value(true))
                    .andExpect(jsonPath("$.finalDecisionAllowed").value(true))
                    .andExpect(jsonPath("$.driftDetected").value(false));
            assertThat(source.authorizationCalls()).as("finalize #" + i + " hit the source").isEqualTo(calls + i);
        }
        assertThat(authorizationCache.getMetrics().sourceCalls()).isEqualTo(regionCalls + 3);
    }

    @Test
    void finalizeUsesTheSourceValueWhenTheCachedPreliminaryHasDrifted() throws Exception {
        String id = synId();
        JsonNode prelim = body(mvc.perform(get("/api/authorization/" + id + "/preliminary")).andExpect(status().isOk()));
        String cachedStatus = prelim.get("status").asText();
        String sourceStatus = body(mvc.perform(post("/api/demo/eligibility/authorization-source/" + id + "/change"))
                .andExpect(status().isOk())).get("sourceStatus").asText();
        assertThat(sourceStatus).isNotEqualTo(cachedStatus);

        // preliminary still shows the old (cached) status ...
        mvc.perform(get("/api/authorization/" + id + "/preliminary")).andExpect(jsonPath("$.status").value(cachedStatus));
        // ... but the final decision is validated against the source
        mvc.perform(post("/api/authorization/" + id + "/finalize")).andExpect(jsonPath("$.status").value(sourceStatus))
                .andExpect(jsonPath("$.driftDetected").value(true)).andExpect(jsonPath("$.cacheCorrected").value(true))
                .andExpect(jsonPath("$.validatedAgainstSource").value(true));
        mvc.perform(get("/api/authorization/" + id + "/preliminary")).andExpect(jsonPath("$.status").value(sourceStatus));
    }

    @Test
    void repeatedWorkloadHasAHighHitRateAndExercisesAuthorization() throws Exception {
        JsonNode before = body(mvc.perform(get("/api/demo/eligibility/cache")).andExpect(status().isOk()));
        long authCallsBefore = source.authorizationCalls();
        JsonNode summary = body(mvc.perform(post("/api/demo/eligibility/workload/repeated")).andExpect(status().isOk()));

        assertThat(summary.get("workload").asText()).isEqualTo("repeated");
        assertThat(summary.get("requests").asInt()).isEqualTo(500 + 4);
        JsonNode elig = regionOf(summary, "eligibility-summary");
        assertThat(elig.get("hits").asLong() + elig.get("misses").asLong()).isEqualTo(500);
        assertThat(elig.get("hits").asLong()).as("few members queried repeatedly").isGreaterThan(elig.get("misses").asLong() * 3);
        assertThat(elig.get("activePolicy").asText()).isEqualTo("LRU");
        JsonNode auth = regionOf(summary, "authorization-decision");
        assertThat(auth.get("sourceCalls").asLong()).as("finalize always reaches the source").isGreaterThanOrEqualTo(4);
        assertThat(source.authorizationCalls() - authCallsBefore).isGreaterThanOrEqualTo(4);
        assertThat(summary.get("sourceCallsAvoided").asLong()).isPositive();
        assertThat(summary.get("hint").asText()).contains("http://localhost:5173");

        JsonNode after = body(mvc.perform(get("/api/demo/eligibility/cache")).andExpect(status().isOk()));
        assertThat(after.get("regions").get(0).get("metrics").get("hits").asLong())
                .isGreaterThan(before.get("regions").get(0).get("metrics").get("hits").asLong());
    }

    @Test
    void highChurnWorkloadTriggersMemoryLimitEvictionsAndExpirations() throws Exception {
        JsonNode summary = body(mvc.perform(post("/api/demo/eligibility/workload/high-churn")
                .contentType(MediaType.APPLICATION_JSON).content("{\"requests\": 600}")).andExpect(status().isOk()));

        assertThat(summary.get("workload").asText()).isEqualTo("high-churn");
        assertThat(summary.get("requests").asInt()).isEqualTo(600 + 3);
        assertThat(summary.get("truncated").asBoolean()).isFalse();
        JsonNode elig = regionOf(summary, "eligibility-summary");
        assertThat(elig.get("hits").asLong() + elig.get("misses").asLong()).isEqualTo(600);
        assertThat(elig.get("misses").asLong()).as("unique members never hit").isEqualTo(600);
        assertThat(elig.get("memoryLimitEvictions").asLong()).as("the 512 KB limit drives eviction")
                .isGreaterThanOrEqualTo(400);
        assertThat(elig.get("entryLimitEvictions").asLong()).isZero();
        assertThat(elig.get("expirations").asLong()).as("short-TTL entries expired").isPositive();
        assertThat(elig.get("size").asInt()).isLessThanOrEqualTo(128);
        assertThat(summary.get("evictions").asLong()).isPositive();
        assertThat(eligibilityCache.getMetrics().evictionsDueToMemoryLimit()).isPositive();
    }

    @Test
    void workloadBodyBoundsAreEnforced() throws Exception {
        for (String bad : List.of("{\"requests\": 9}", "{\"requests\": 10001}", "{\"requests\": 0}")) {
            mvc.perform(post("/api/demo/eligibility/workload/repeated").contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
            mvc.perform(post("/api/demo/eligibility/workload/high-churn").contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/demo/eligibility/workload/repeated").contentType(MediaType.APPLICATION_JSON).content("[1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cacheViewShowsSafeRegionSummaries() throws Exception {
        mvc.perform(get("/api/demo/eligibility/cache")).andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("eligibility-service"))
                .andExpect(jsonPath("$.regions.length()").value(2))
                .andExpect(jsonPath("$.regions[0].region").value("eligibility-summary"))
                .andExpect(jsonPath("$.regions[0].riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.regions[0].staleWhileRevalidate").value(false))
                .andExpect(jsonPath("$.regions[0].metrics.capacity").value(150))
                .andExpect(jsonPath("$.regions[0].metrics.maximumMemoryBytes").value(512 * 1024))
                .andExpect(jsonPath("$.regions[0].health.status").isNotEmpty())
                .andExpect(jsonPath("$.regions[0].recommendation.approvalRequired").value(true))
                .andExpect(jsonPath("$.regions[1].region").value("authorization-decision"))
                .andExpect(jsonPath("$.regions[1].riskLevel").value("CRITICAL"))
                .andExpect(jsonPath("$.regions[1].staleWhileRevalidate").value(false));
    }

    @Test
    void clearEndpointEmptiesTheRegions() throws Exception {
        String id = synId();
        mvc.perform(get("/api/eligibility/" + id)).andExpect(status().isOk());
        mvc.perform(post("/api/demo/eligibility/cache/clear")).andExpect(status().isOk())
                .andExpect(jsonPath("$.clearedEntries['eligibility-summary']").isNumber());
        assertThat(eligibilityCache.size()).isZero();
        mvc.perform(get("/api/eligibility/" + id)).andExpect(jsonPath("$.servedFrom").value("SOURCE"));
    }

    @Test
    void staleWhileRevalidateIsNotConfiguredOnHighAndCriticalRegions() {
        CacheRegionConfig e = eligibilityCache.getConfig();
        assertThat(e.riskLevel()).isEqualTo(CacheRiskLevel.HIGH);
        assertThat(e.staleWhileRevalidate()).isFalse();
        assertThat(e.defaultPolicy()).isEqualTo(EvictionPolicy.LRU);
        assertThat(e.defaultTtl()).isBetween(Duration.ofMinutes(1), Duration.ofMinutes(5)).isEqualTo(Duration.ofMinutes(2));
        assertThat(e.maximumEntries()).isEqualTo(150);
        assertThat(e.maximumMemoryBytes()).isEqualTo(512L * 1024L);

        CacheRegionConfig a = authorizationCache.getConfig();
        assertThat(a.riskLevel()).isEqualTo(CacheRiskLevel.CRITICAL);
        assertThat(a.staleWhileRevalidate()).isFalse();
        assertThat(a.defaultTtl()).isEqualTo(Duration.ofSeconds(60));
        assertThat(a.maximumEntries()).isLessThanOrEqualTo(100);

        // the SDK itself refuses to enable it on risky regions
        for (CacheRiskLevel risk : List.of(CacheRiskLevel.HIGH, CacheRiskLevel.CRITICAL)) {
            assertThatThrownBy(() -> CacheRegionConfig.builder().regionName("x").riskLevel(risk).staleWhileRevalidate(true).build())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LOW");
        }
    }

    @Test
    void sourceIsDeterministicAndASourceChangeIsVisibleOnlyThroughTheSource() {
        String id = "SYN-000424242";
        EligibilityResult a = source.loadEligibility(id);
        assertThat(source.loadEligibility(id)).isEqualTo(a);
        EligibilityResult flipped = source.flipEligibility(id);
        assertThat(flipped.active()).isEqualTo(!a.active());
        assertThat(source.loadEligibility(id)).isEqualTo(flipped);
        assertThat(source.flipEligibility(id).active()).isEqualTo(a.active());
    }
}

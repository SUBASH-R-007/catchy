package com.acentra.catchy.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acentra.cache.CachePolicyRecommendation;
import com.acentra.cache.EvictionPolicy;
import com.acentra.cache.PolicyAdvisor;
import com.acentra.cache.telemetry.RegionSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecommendationApiTest extends AbstractApiTest {

    private void send(TestApp app, RegionSnapshot snap) throws Exception {
        ingest(app.apiKey(), TestData.batch(null, null, "pod-a", List.of(), List.of(snap))).andExpect(status().isAccepted());
    }

    private JsonNode recommendation(TestApp app, String region) throws Exception {
        JsonNode all = json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/recommendations").andExpect(status().isOk()));
        for (JsonNode r : all) if (r.get("cacheRegion").asText().equals(region)) return r;
        throw new AssertionError("no recommendation for " + region);
    }

    @Test
    void switchIsRecommendedWhenShadowGainMeetsThresholdAndMatchesTheSdkAdvisor() throws Exception {
        TestApp app = createApp("claims-service");
        send(app, TestData.snap().policy(EvictionPolicy.LRU).counters(700, 300, 300, 10, 0).shadow(1000, 69.4, 80.9, 78.5).build());
        JsonNode r = recommendation(app, "claim-rules");

        CachePolicyRecommendation sdk = PolicyAdvisor.evaluate(
                new PolicyAdvisor.Input(EvictionPolicy.LRU, 1000, 69.4, 80.9, 78.5, null, Instant.now()), PolicyAdvisor.Settings.defaults());
        assertThat(r.get("action").asText()).isEqualTo("SWITCH").isEqualTo(sdk.action().name());
        assertThat(r.get("currentPolicy").asText()).isEqualTo("LRU");
        assertThat(r.get("recommendedPolicy").asText()).isEqualTo("LFU");
        assertThat(r.get("summary").asText()).isEqualTo("Switch to LFU");
        assertThat(r.get("lruShadowHitRate").asDouble()).isEqualTo(69.4);
        assertThat(r.get("lfuShadowHitRate").asDouble()).isEqualTo(80.9);
        assertThat(r.get("improvementPercent").asDouble()).isEqualTo(11.5);
        assertThat(r.get("confidence").asInt()).isEqualTo(sdk.confidence());
        assertThat(r.get("reason").asText()).isEqualTo(sdk.reason());
        assertThat(r.get("sampleSize").asLong()).isEqualTo(1000);
        assertThat(r.get("minimumSampleMet").asBoolean()).isTrue();
        assertThat(r.get("cooldownActive").asBoolean()).isFalse();
        assertThat(r.get("approvalRequired").asBoolean()).isTrue();
        assertThat(r.get("aiExplanation").isNull()).isTrue();
        assertThat(r.get("pendingRequestId").isNull()).isTrue();
        assertThat(r.get("applicationName").asText()).isEqualTo("claims-service");

        // the application summary mirrors the decision
        JsonNode summary = json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/metrics"));
        assertThat(summary.get("recommendationSummary").asText()).isEqualTo("Switch claim-rules to LFU");
        // stored once per region: the id is stable across evaluations and the overview exposes the latest
        long id = r.get("id").asLong();
        assertThat(recommendation(app, "claim-rules").get("id").asLong()).isEqualTo(id);
        assertThat(json(getAs("viewer", "/api/v1/overview")).get("latestRecommendations").get(0).get("id").asLong()).isEqualTo(id);
    }

    @Test
    void firstAppearanceOfARegionStoresItsRecommendationForTheOverviewWithoutAnyRead() throws Exception {
        TestApp app = createApp("claims-service");
        send(app, TestData.snap().policy(EvictionPolicy.LRU).counters(700, 300, 300, 0, 0).shadow(1000, 69.4, 80.9, 78.5).build());
        JsonNode latest = json(getAs("viewer", "/api/v1/overview")).get("latestRecommendations");
        assertThat(latest).hasSize(1);
        assertThat(latest.get(0).get("action").asText()).isEqualTo("SWITCH");
        assertThat(latest.get(0).get("cacheRegion").asText()).isEqualTo("claim-rules");
    }

    @Test
    void keepWhenGainIsBelowThresholdOrCurrentPolicyIsBetter() throws Exception {
        TestApp app = createApp("claims-service");
        send(app, TestData.snap().region("small-gain").policy(EvictionPolicy.LRU).counters(700, 300, 300, 0, 0)
                .shadow(1000, 70.0, 73.0, 50).build());
        send(app, TestData.snap().region("current-wins").policy(EvictionPolicy.LFU).counters(700, 300, 300, 0, 0)
                .shadow(1000, 60.0, 80.0, 50).build());
        JsonNode small = recommendation(app, "small-gain");
        assertThat(small.get("action").asText()).isEqualTo("KEEP");
        assertThat(small.get("summary").asText()).isEqualTo("Keep LRU");
        assertThat(small.get("improvementPercent").asDouble()).isEqualTo(3.0);
        assertThat(small.get("recommendedPolicy").asText()).isEqualTo("LRU");
        JsonNode better = recommendation(app, "current-wins");
        assertThat(better.get("action").asText()).isEqualTo("KEEP");
        assertThat(better.get("improvementPercent").asDouble()).isEqualTo(-20.0);
        assertThat(better.get("reason").asText()).contains("outperforms");
        assertThat(json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/metrics")).get("recommendationSummary").asText())
                .isEqualTo("Keep LFU/LRU");
    }

    @Test
    void minimumSampleIsRequiredEvenForAHugeShadowGain() throws Exception {
        TestApp app = createApp("claims-service");
        send(app, TestData.snap().policy(EvictionPolicy.LRU).counters(70, 30, 30, 0, 0).shadow(50, 10.0, 90.0, 80).build());
        JsonNode r = recommendation(app, "claim-rules");
        assertThat(r.get("action").asText()).isEqualTo("KEEP");
        assertThat(r.get("minimumSampleMet").asBoolean()).isFalse();
        assertThat(r.get("sampleSize").asLong()).isEqualTo(50);
        assertThat(r.get("reason").asText()).startsWith("Not enough data yet");
    }

    @Test
    void noShadowDataYieldsKeepWithZeroSample() throws Exception {
        TestApp app = createApp("claims-service");
        send(app, TestData.snap().policy(EvictionPolicy.LFU).counters(10, 10, 10, 0, 0).build());
        JsonNode r = recommendation(app, "claim-rules");
        assertThat(r.get("action").asText()).isEqualTo("KEEP");
        assertThat(r.get("sampleSize").asLong()).isZero();
        assertThat(r.get("minimumSampleMet").asBoolean()).isFalse();
        assertThat(json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/regions/claim-rules/metrics")).get("shadow").isNull())
                .isTrue();
    }

    @Test
    void recentPolicyChangeActivatesCooldownAndExpiredCooldownAllowsSwitch() throws Exception {
        TestApp app = createApp("claims-service");
        send(app, TestData.snap().policy(EvictionPolicy.LRU).counters(700, 300, 300, 0, 0).shadow(1000, 60.0, 80.0, 70)
                .lastPolicyChange(Instant.now().minusSeconds(60)).build());
        JsonNode cooling = recommendation(app, "claim-rules");
        assertThat(cooling.get("action").asText()).isEqualTo("KEEP");
        assertThat(cooling.get("cooldownActive").asBoolean()).isTrue();
        assertThat(cooling.get("cooldownEndsAt").asText()).isNotBlank();
        assertThat(cooling.get("reason").asText()).contains("cooldown");

        send(app, TestData.snap().policy(EvictionPolicy.LRU).counters(800, 400, 400, 0, 0).shadow(1000, 60.0, 80.0, 70)
                .lastPolicyChange(Instant.now().minusSeconds(600)).at(Instant.now().plusSeconds(1)).build());
        JsonNode done = recommendation(app, "claim-rules");
        assertThat(done.get("action").asText()).isEqualTo("SWITCH");
        assertThat(done.get("cooldownActive").asBoolean()).isFalse();
        assertThat(done.get("cooldownEndsAt").isNull()).isTrue();
        assertThat(done.get("id").asLong()).isEqualTo(cooling.get("id").asLong());
    }

    @Test
    void evaluateEndpointRefreshesAndIsAuditedAndRoleRestricted() throws Exception {
        TestApp app = createApp("claims-service");
        send(app, TestData.snap().policy(EvictionPolicy.LRU).counters(700, 300, 300, 0, 0).shadow(1000, 60.0, 80.0, 70).build());
        postAs("viewer", "/api/v1/applications/" + app.appId() + "/recommendations/evaluate", null).andExpect(status().isForbidden());
        JsonNode list = json(postAs("engineer", "/api/v1/applications/" + app.appId() + "/recommendations/evaluate", null)
                .andExpect(status().isOk()));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("action").asText()).isEqualTo("SWITCH");
        assertThat(auditCount("RECOMMENDATION_EVALUATED", "SUCCESS")).isEqualTo(1);
        postAs("engineer", "/api/v1/applications/999999/recommendations/evaluate", null).andExpect(status().isNotFound());
    }
}

package com.acentra.catchy.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acentra.cache.CacheAction;
import com.acentra.cache.telemetry.TelemetryEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class IngestionApiTest extends AbstractApiTest {

    private static final String FP = "sha256:4a1d9c03be7f2a61";

    @Test
    void validKeyAcceptsBatchAndUpdatesApplicationAndKey() throws Exception {
        TestApp app = createApp("claims-service");
        var batch = TestData.batch(null, null, "claims-service-8091",
                List.of(TestData.event("claim-rules", CacheAction.HIT, FP), TestData.event("claim-rules", CacheAction.MISS, null)),
                List.of(TestData.snap().counters(90, 10, 10, 0, 0).build()));
        ingest(app.apiKey(), batch).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedEvents").value(2))
                .andExpect(jsonPath("$.acceptedSnapshots").value(1));

        assertThat(count("cache_telemetry_event")).isEqualTo(2);
        assertThat(count("cache_metrics_snapshot")).isEqualTo(1);
        getAs("viewer", "/api/v1/applications/" + app.appId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.regionCount").value(1))
                .andExpect(jsonPath("$.lastTelemetryAt").isNotEmpty());
        JsonNode keys = json(getAs("admin", "/api/v1/applications/" + app.appId() + "/api-keys"));
        assertThat(keys.get(0).get("lastUsedAt").isNull()).isFalse();
    }

    @Test
    void exactSdkJsonShapeWithNullsIsAccepted() throws Exception {
        TestApp app = createApp("claims-service");
        var batch = TestData.batch("claims-service", "staging", "pod-1", List.of(TestData.event("claim-rules", CacheAction.PUT, null)),
                List.of(TestData.snap().counters(5, 5, 5, 0, 0).build()));
        String raw = sdkJson.writeValueAsString(batch);
        assertThat(raw).contains("\"shadow\":null").contains("\"keyFingerprint\":null").contains("\"lastPolicyChangeAt\":null");
        ingestRaw(app.apiKey(), raw).andExpect(status().isAccepted());
    }

    @Test
    void missingUnknownMalformedAndRevokedKeysAreUnauthorized() throws Exception {
        TestApp app = createApp("claims-service");
        String body = sdkJson.writeValueAsString(TestData.batch(null, null, "i-1", List.of(), List.of()));
        ingestRaw(null, body).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        ingestRaw("acc_00000000_00000000000000000000000000000000", body).andExpect(status().isUnauthorized());
        ingestRaw("garbage", body).andExpect(status().isUnauthorized());

        ingestRaw(app.apiKey(), body).andExpect(status().isAccepted());
        long keyId = json(getAs("admin", "/api/v1/applications/" + app.appId() + "/api-keys")).get(0).get("id").asLong();
        deleteAs("admin", "/api/v1/api-keys/" + keyId).andExpect(status().isNoContent());
        ingestRaw(app.apiKey(), body).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/telemetry/control").header("X-AcentraCache-Key", app.apiKey())).andExpect(status().isUnauthorized());
    }

    @Test
    void dashboardBearerTokenIsNotAcceptedOnTelemetryEndpoints() throws Exception {
        mvc.perform(get("/api/v1/telemetry/control").header("Authorization", "Bearer " + token("admin")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void payloadForAnotherApplicationOrEnvironmentIsForbidden() throws Exception {
        TestApp app = createApp("claims-service");
        ingest(app.apiKey(), TestData.batch("eligibility-service", "staging", "i-1", List.of(), List.of()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        ingest(app.apiKey(), TestData.batch("claims-service", "production", "i-1", List.of(), List.of()))
                .andExpect(status().isForbidden());
        TelemetryEvent other = new TelemetryEvent(java.time.Instant.now(), "other-app", "staging", "claim-rules", CacheAction.HIT, FP,
                "r", com.acentra.cache.EvictionPolicy.LRU, 0, 0, 0, 0, 0, 0, 0, 0, true, com.acentra.cache.EventSeverity.INFO, 0);
        ingest(app.apiKey(), TestData.batch(null, null, "i-1", List.of(other), List.of())).andExpect(status().isForbidden());
        assertThat(count("cache_telemetry_event")).isZero();
        assertThat(auditCount("ACCESS_DENIED", "DENIED")).isGreaterThanOrEqualTo(3);
    }

    @Test
    void unknownOrSensitiveFieldsAreRejectedAuditedAndNotStored() throws Exception {
        TestApp app = createApp("claims-service");
        String secretKey = "member-12345-claim-987";
        String secretValue = "John Q. Patient";
        String raw = """
                {"applicationName":"claims-service","environment":"staging","instanceId":"i-1","value":"%s",
                 "events":[{"timestamp":"2026-09-30T09:15:08.120Z","cacheRegion":"claim-rules","action":"HIT","policy":"LRU",
                            "rawKey":"%s","memberId":"M123456789","keyFingerprint":null}],
                 "snapshots":[]}
                """.formatted(secretValue, secretKey);
        String response = ingestRaw(app.apiKey(), raw).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).contains("value").contains("events[0].rawKey").contains("events[0].memberId");
        assertThat(response).doesNotContain(secretKey).doesNotContain(secretValue).doesNotContain("M123456789");

        assertThat(count("cache_telemetry_event")).isZero();
        assertThat(count("cache_metrics_snapshot")).isZero();
        assertThat(auditCount("TELEMETRY_REJECTED", "DENIED")).isEqualTo(1);
        String details = jdbc.queryForObject("select details from audit_log where action = 'TELEMETRY_REJECTED'", String.class);
        assertThat(details).contains("events[0].rawKey").doesNotContain(secretKey).doesNotContain(secretValue).doesNotContain("M123456789");
    }

    @Test
    void unknownFieldInsideSnapshotShadowAndSingleEventEndpointAreRejectedToo() throws Exception {
        TestApp app = createApp("claims-service");
        String snapshotJson = sdkJson.writeValueAsString(TestData.snap().shadow(200, 50, 60, 70).build());
        String tampered = snapshotJson.replace("\"topKeyConcentrationPercent\"", "\"patientName\":\"Jane\",\"topKeyConcentrationPercent\"");
        String raw = "{\"applicationName\":\"claims-service\",\"environment\":\"staging\",\"instanceId\":\"i-1\",\"events\":[],\"snapshots\":["
                + tampered + "]}";
        String response = ingestRaw(app.apiKey(), raw).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(response).contains("snapshots[0].shadow.patientName").doesNotContain("Jane");

        String single = "{\"applicationName\":\"claims-service\",\"environment\":\"staging\",\"timestamp\":\"2026-09-30T09:15:08.120Z\","
                + "\"cacheRegion\":\"claim-rules\",\"action\":\"HIT\",\"policy\":\"LRU\",\"responseBody\":\"secret payload\"}";
        String r2 = mvc.perform(post("/api/v1/telemetry/events").header("X-AcentraCache-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).content(single))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(r2).contains("responseBody").doesNotContain("secret payload");
        assertThat(auditCount("TELEMETRY_REJECTED", "DENIED")).isEqualTo(2);
    }

    @Test
    void singleEventEndpointRequiresApplicationAndEnvironment() throws Exception {
        TestApp app = createApp("claims-service");
        String ok = "{\"applicationName\":\"claims-service\",\"environment\":\"staging\",\"timestamp\":\"2026-09-30T09:15:08.120Z\","
                + "\"cacheRegion\":\"claim-rules\",\"action\":\"HIT\",\"keyFingerprint\":\"" + FP + "\",\"policy\":\"LRU\"}";
        mvc.perform(post("/api/v1/telemetry/events").header("X-AcentraCache-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).content(ok))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.acceptedEvents").value(1))
                .andExpect(jsonPath("$.acceptedSnapshots").value(0));
        String missing = ok.replace("\"applicationName\":\"claims-service\",", "");
        mvc.perform(post("/api/v1/telemetry/events").header("X-AcentraCache-Key", app.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).content(missing))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0]").value("applicationName: must not be blank"));
    }

    @Test
    void fingerprintRegionReasonNumbersAndEnumsAreValidated() throws Exception {
        TestApp app = createApp("claims-service");
        String base = "{\"instanceId\":\"i-1\",\"events\":[%s],\"snapshots\":[]}";
        String ev = "{\"timestamp\":\"2026-09-30T09:15:08.120Z\",\"cacheRegion\":\"%s\",\"action\":\"%s\",\"policy\":\"LRU\","
                + "\"keyFingerprint\":%s,\"reason\":\"%s\",\"frequency\":%d}";

        // valid: null fingerprint and a well-formed one
        ingestRaw(app.apiKey(), base.formatted(ev.formatted("claim-rules", "HIT", "null", "ok", 1))).andExpect(status().isAccepted());
        ingestRaw(app.apiKey(), base.formatted(ev.formatted("claim-rules", "HIT", "\"" + FP + "\"", "ok", 1))).andExpect(status().isAccepted());

        // raw-looking key instead of a fingerprint
        String rawKeyLike = json(ingestRaw(app.apiKey(), base.formatted(ev.formatted("claim-rules", "HIT", "\"member-12345\"", "ok", 1)))
                .andExpect(status().isBadRequest())).toString();
        assertThat(rawKeyLike).contains("events[0].keyFingerprint").doesNotContain("member-12345");
        ingestRaw(app.apiKey(), base.formatted(ev.formatted("claim-rules", "HIT", "\"sha256:ABCDEF12\"", "ok", 1))).andExpect(status().isBadRequest());
        ingestRaw(app.apiKey(), base.formatted(ev.formatted("claim-rules", "HIT", "\"sha256:abc\"", "ok", 1))).andExpect(status().isBadRequest());

        ingestRaw(app.apiKey(), base.formatted(ev.formatted("Bad Region!", "HIT", "null", "ok", 1))).andExpect(status().isBadRequest());
        ingestRaw(app.apiKey(), base.formatted(ev.formatted("claim-rules", "HIT", "null", "x".repeat(401), 1))).andExpect(status().isBadRequest());
        ingestRaw(app.apiKey(), base.formatted(ev.formatted("claim-rules", "HIT", "null", "x".repeat(400), 1))).andExpect(status().isAccepted());
        ingestRaw(app.apiKey(), base.formatted(ev.formatted("claim-rules", "HIT", "null", "ok", -5))).andExpect(status().isBadRequest());
        String enumResponse = ingestRaw(app.apiKey(), base.formatted(ev.formatted("claim-rules", "NOT_AN_ACTION", "null", "ok", 1)))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(enumResponse).contains("events[0].action").doesNotContain("NOT_AN_ACTION");
        ingestRaw(app.apiKey(), "{not json").andExpect(status().isBadRequest());
        ingestRaw(app.apiKey(), "[]").andExpect(status().isBadRequest());
        ingestRaw(app.apiKey(), "{\"instanceId\":\"bad instance!\",\"events\":[],\"snapshots\":[]}").andExpect(status().isBadRequest());
    }

    @Test
    void batchLargerThan500EventsIsRejected() throws Exception {
        TestApp app = createApp("claims-service");
        List<TelemetryEvent> events = new ArrayList<>();
        for (int i = 0; i < 501; i++) events.add(TestData.event("claim-rules", CacheAction.MISS, null));
        ingest(app.apiKey(), TestData.batch(null, null, "i-1", events, List.of())).andExpect(status().isBadRequest());
        ingest(app.apiKey(), TestData.batch(null, null, "i-1", events.subList(0, 500), List.of())).andExpect(status().isAccepted());
        assertThat(count("cache_telemetry_event")).isEqualTo(500);
    }

    @Test
    void bodiesLargerThanOneMegabyteAreRejectedWith413() throws Exception {
        TestApp app = createApp("claims-service");
        String padding = "a".repeat(1_100_000);
        String raw = "{\"instanceId\":\"i-1\",\"events\":[],\"snapshots\":[],\"sdkVersion\":\"" + padding + "\"}";
        ingestRaw(app.apiKey(), raw).andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.status").value(413));
    }

    @Test
    void controlEndpointReturnsEmptyRegionsWhenNothingIsPending() throws Exception {
        TestApp app = createApp("claims-service");
        mvc.perform(get("/api/v1/telemetry/control").header("X-AcentraCache-Key", app.apiKey()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.regions").isArray()).andExpect(jsonPath("$.regions").isEmpty());
    }

    @Test
    void resentSnapshotIsAcceptedButStoredOnce() throws Exception {
        TestApp app = createApp("claims-service");
        var snap = TestData.snap().counters(10, 0, 1, 0, 0).build();
        var batch = TestData.batch(null, null, "i-1", List.of(), List.of(snap));
        ingest(app.apiKey(), batch).andExpect(status().isAccepted());
        ingest(app.apiKey(), batch).andExpect(status().isAccepted()).andExpect(jsonPath("$.acceptedSnapshots").value(1));
        assertThat(count("cache_metrics_snapshot")).isEqualTo(1);
    }
}

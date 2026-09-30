package com.acentra.catchy.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acentra.cache.EvictionPolicy;
import com.acentra.catchy.telemetry.security.ApiKeys;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Projects, applications, API keys, region configuration and the audit log. */
class AdminApiTest extends AbstractApiTest {

    // ---- catalog -----------------------------------------------------------------------------------------------------

    @Test
    void projectsAreCreatedByAdminsValidatedAndUniqueIgnoringCase() throws Exception {
        JsonNode p = json(postAs("admin", "/api/v1/projects", "{\"name\":\"Claims Platform\",\"description\":\"Claims services\"}")
                .andExpect(status().isCreated()));
        assertThat(p.get("name").asText()).isEqualTo("Claims Platform");
        assertThat(p.get("applicationCount").asInt()).isZero();
        assertThat(p.get("createdAt").asText()).isNotBlank();
        postAs("admin", "/api/v1/projects", "{\"name\":\"claims platform\"}").andExpect(status().isConflict());
        postAs("admin", "/api/v1/projects", "{\"name\":\"x\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed")).andExpect(jsonPath("$.details[0]").value("name: size must be between 2 and 80"));
        postAs("admin", "/api/v1/projects", "{\"description\":\"no name\"}").andExpect(status().isBadRequest());
        postAs("admin", "/api/v1/projects", "{not json").andExpect(status().isBadRequest());
        assertThat(auditCount("PROJECT_CREATED", "SUCCESS")).isEqualTo(1);
        assertThat(json(getAs("viewer", "/api/v1/projects"))).hasSize(1);
        getAs("viewer", "/api/v1/projects/" + p.get("id").asLong()).andExpect(status().isOk());
        getAs("viewer", "/api/v1/projects/999999").andExpect(status().isNotFound());
    }

    @Test
    void applicationsAreValidatedAndUniquePerNameAndEnvironment() throws Exception {
        long projectId = json(postAs("admin", "/api/v1/projects", "{\"name\":\"Eligibility Platform\"}")).get("id").asLong();
        String url = "/api/v1/projects/" + projectId + "/applications";
        JsonNode app = json(postAs("admin", url, "{\"name\":\"eligibility-service\",\"displayName\":\"Eligibility Service\","
                + "\"environment\":\"staging\",\"description\":\"d\"}").andExpect(status().isCreated()));
        assertThat(app.get("projectName").asText()).isEqualTo("Eligibility Platform");
        assertThat(app.get("displayName").asText()).isEqualTo("Eligibility Service");
        assertThat(app.get("regionCount").asInt()).isZero();
        assertThat(app.get("lastTelemetryAt").isNull()).isTrue();
        postAs("admin", url, "{\"name\":\"eligibility-service\",\"environment\":\"staging\"}").andExpect(status().isConflict());
        postAs("admin", url, "{\"name\":\"eligibility-service\",\"environment\":\"production\"}").andExpect(status().isCreated());
        postAs("admin", url, "{\"name\":\"Bad Name\",\"environment\":\"staging\"}").andExpect(status().isBadRequest());
        postAs("admin", url, "{\"name\":\"ok-name\",\"environment\":\"Staging!\"}").andExpect(status().isBadRequest());
        postAs("admin", "/api/v1/projects/999999/applications", "{\"name\":\"ok-name\",\"environment\":\"dev\"}").andExpect(status().isNotFound());
        assertThat(auditCount("APPLICATION_CREATED", "SUCCESS")).isEqualTo(2);
        assertThat(json(getAs("viewer", url))).hasSize(2);
        assertThat(json(getAs("viewer", "/api/v1/applications"))).hasSize(2);
        getAs("viewer", "/api/v1/applications/" + app.get("id").asLong()).andExpect(status().isOk());
        assertThat(json(getAs("viewer", "/api/v1/projects")).get(0).get("applicationCount").asInt()).isEqualTo(2);
    }

    // ---- API keys ----------------------------------------------------------------------------------------------------

    @Test
    void apiKeyIsShownOnceStoredHashedAndAuditedOnCreateAndRevoke() throws Exception {
        long projectId = json(postAs("admin", "/api/v1/projects", "{\"name\":\"Claims Platform\"}")).get("id").asLong();
        long appId = json(postAs("admin", "/api/v1/projects/" + projectId + "/applications",
                "{\"name\":\"claims-service\",\"environment\":\"staging\"}")).get("id").asLong();

        JsonNode created = json(postAs("admin", "/api/v1/applications/" + appId + "/api-keys", "{\"label\":\"staging pod 1\"}")
                .andExpect(status().isCreated()));
        String plaintext = created.get("apiKey").asText();
        assertThat(plaintext).matches("^acc_[0-9a-f]{8}_[0-9a-f]{32}$");
        String prefix = created.get("keyPrefix").asText();
        assertThat(plaintext).startsWith(prefix + "_");
        assertThat(created.get("maskedKey").asText()).isEqualTo(prefix + "_" + "•".repeat(16));
        assertThat(created.get("label").asText()).isEqualTo("staging pod 1");

        // never returned again
        String listing = getAs("admin", "/api/v1/applications/" + appId + "/api-keys").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listing).doesNotContain(plaintext).doesNotContain("\"apiKey\"");
        JsonNode listed = sdkJson.readTree(listing).get(0);
        assertThat(listed.get("active").asBoolean()).isTrue();
        assertThat(listed.get("keyPrefix").asText()).isEqualTo(prefix);
        assertThat(listed.get("revokedAt").isNull()).isTrue();

        // stored hashed: no column of any table contains the plaintext
        Map<String, Object> row = jdbc.queryForMap("select * from application_api_key");
        assertThat(row.get("key_hash")).isEqualTo(ApiKeys.hash(plaintext));
        assertThat(row.get("key_prefix")).isEqualTo(prefix);
        assertThat(row.values().toString()).doesNotContain(plaintext);
        assertThat(jdbc.queryForList("select details from audit_log", String.class).toString()).doesNotContain(plaintext);
        assertThat(auditCount("API_KEY_CREATED", "SUCCESS")).isEqualTo(1);

        // revoke: 204, key is unusable, idempotent, audited once
        long keyId = created.get("id").asLong();
        deleteAs("admin", "/api/v1/api-keys/" + keyId).andExpect(status().isNoContent());
        deleteAs("admin", "/api/v1/api-keys/" + keyId).andExpect(status().isNoContent());
        deleteAs("admin", "/api/v1/api-keys/999999").andExpect(status().isNotFound());
        assertThat(auditCount("API_KEY_REVOKED", "SUCCESS")).isEqualTo(1);
        assertThat(json(getAs("admin", "/api/v1/applications/" + appId + "/api-keys")).get(0).get("active").asBoolean()).isFalse();
        ingestRaw(plaintext, "{\"events\":[],\"snapshots\":[]}").andExpect(status().isUnauthorized());
        postAs("admin", "/api/v1/applications/999999/api-keys", "{\"label\":\"x\"}").andExpect(status().isNotFound());
    }

    // ---- region configuration ----------------------------------------------------------------------------------------

    @Test
    void regionConfigDesiredTuningIsVersionedValidatedAuditedAndDeliveredThroughControl() throws Exception {
        TestApp app = createApp("claims-service");
        ingest(app.apiKey(), TestData.batch(null, null, "pod-a", List.of(),
                List.of(TestData.snap().policy(EvictionPolicy.LFU).sizing(10, 500).memory(1_000, 268_435_456).ttl(900_000)
                        .at(Instant.now().minusSeconds(5)).build()))).andExpect(status().isAccepted());
        String url = "/api/v1/applications/" + app.appId() + "/regions/claim-rules/config";

        JsonNode initial = json(getAs("viewer", url).andExpect(status().isOk()));
        assertThat(initial.get("reported").get("maximumEntries").asLong()).isEqualTo(500);
        assertThat(initial.get("reported").get("maximumMemoryBytes").asLong()).isEqualTo(268_435_456L);
        assertThat(initial.get("reported").get("defaultTtlMs").asLong()).isEqualTo(900_000);
        assertThat(initial.get("reported").get("activePolicy").asText()).isEqualTo("LFU");
        assertThat(initial.get("desired").isNull()).isTrue();
        assertThat(initial.get("pending").asBoolean()).isFalse();
        assertThat(initial.get("riskLevel").asText()).isEqualTo("MEDIUM");

        // authorization and validation
        putAs("engineer", url, "{\"maximumEntries\":800}").andExpect(status().isForbidden());
        putAs("viewer", url, "{\"maximumEntries\":800}").andExpect(status().isForbidden());
        putAs("admin", url, "{}").andExpect(status().isBadRequest());
        putAs("admin", url, "{\"reason\":\"only a reason\"}").andExpect(status().isBadRequest());
        putAs("admin", url, "{\"maximumEntries\":0}").andExpect(status().isBadRequest());
        putAs("admin", url, "{\"maximumEntries\":10000001}").andExpect(status().isBadRequest());
        putAs("admin", url, "{\"maximumMemoryBytes\":1023}").andExpect(status().isBadRequest());
        putAs("admin", url, "{\"defaultTtlMs\":999}").andExpect(status().isBadRequest());
        putAs("admin", "/api/v1/applications/" + app.appId() + "/regions/nope/config", "{\"maximumEntries\":800}").andExpect(status().isNotFound());

        JsonNode v1 = json(putAs("admin", url, "{\"maximumEntries\":800,\"reason\":\"Raise capacity for month-end load\"}")
                .andExpect(status().isOk()));
        assertThat(v1.get("desired").get("maximumEntries").asLong()).isEqualTo(800);
        assertThat(v1.get("desired").get("maximumMemoryBytes").isNull()).isTrue();
        assertThat(v1.get("desired").get("defaultTtlMs").isNull()).isTrue();
        assertThat(v1.get("pending").asBoolean()).isTrue();
        assertThat(v1.get("tuningVersion").asLong()).isEqualTo(1);
        assertThat(v1.get("updatedBy").asText()).isEqualTo("admin");
        assertThat(v1.get("updatedAt").asText()).isNotBlank();
        assertThat(auditCount("CONFIG_CHANGE_REQUESTED", "SUCCESS")).isEqualTo(1);

        JsonNode v2 = json(putAs("admin", url, "{\"defaultTtlMs\":600000,\"maximumMemoryBytes\":536870912}").andExpect(status().isOk()));
        assertThat(v2.get("tuningVersion").asLong()).isEqualTo(2);
        assertThat(v2.get("desired").get("maximumEntries").asLong()).as("earlier desired values are kept").isEqualTo(800);
        assertThat(v2.get("desired").get("defaultTtlMs").asLong()).isEqualTo(600_000);

        // delivered to the SDK exactly as the contract says
        JsonNode control = readJson(mvc.perform(get("/api/v1/telemetry/control").header("X-AcentraCache-Key", app.apiKey()))
                .andExpect(status().isOk()).andReturn());
        JsonNode region = control.get("regions").get(0);
        assertThat(region.get("cacheRegion").asText()).isEqualTo("claim-rules");
        assertThat(region.get("desiredPolicy").isNull()).isTrue();
        assertThat(region.get("policyRequestId").isNull()).isTrue();
        assertThat(region.get("tuning").get("maximumEntries").asLong()).isEqualTo(800);
        assertThat(region.get("tuning").get("maximumMemoryBytes").asLong()).isEqualTo(536_870_912L);
        assertThat(region.get("tuning").get("defaultTtlMs").asLong()).isEqualTo(600_000);
        assertThat(region.get("tuningVersion").asLong()).isEqualTo(2);

        // still pending while the SDK reports the old values
        assertThat(auditCount("CONFIG_CHANGE_APPLIED", "SUCCESS")).isZero();
        assertThat(json(getAs("viewer", url)).get("pending").asBoolean()).isTrue();

        // the SDK applied the tuning: a snapshot now matches the desired values
        ingest(app.apiKey(), TestData.batch(null, null, "pod-a", List.of(),
                List.of(TestData.snap().policy(EvictionPolicy.LFU).sizing(10, 800).memory(1_000, 536_870_912L).ttl(600_000).build())))
                .andExpect(status().isAccepted());
        assertThat(auditCount("CONFIG_CHANGE_APPLIED", "SUCCESS")).isEqualTo(1);
        JsonNode applied = json(getAs("viewer", url));
        assertThat(applied.get("pending").asBoolean()).isFalse();
        assertThat(applied.get("reported").get("maximumEntries").asLong()).isEqualTo(800);
        // and it is audited only once
        ingest(app.apiKey(), TestData.batch(null, null, "pod-a", List.of(),
                List.of(TestData.snap().policy(EvictionPolicy.LFU).sizing(10, 800).memory(1_000, 536_870_912L).ttl(600_000)
                        .at(Instant.now().plusSeconds(2)).build()))).andExpect(status().isAccepted());
        assertThat(auditCount("CONFIG_CHANGE_APPLIED", "SUCCESS")).isEqualTo(1);
    }

    // ---- audit log ---------------------------------------------------------------------------------------------------

    @Test
    void auditLogIsAdminOnlyFilterableNewestFirstAndFreeOfSecrets() throws Exception {
        TestApp app = createApp("claims-service");
        getAs("viewer", "/api/v1/audit-logs").andExpect(status().isForbidden());
        getAs("engineer", "/api/v1/audit-logs").andExpect(status().isForbidden());
        JsonNode all = json(getAs("admin", "/api/v1/audit-logs").andExpect(status().isOk()));
        assertThat(all.size()).isGreaterThanOrEqualTo(5);
        assertThat(all.get(0).get("id").asLong()).isGreaterThan(all.get(1).get("id").asLong());
        JsonNode entry = all.get(0);
        assertThat(entry.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "timestamp", "actor", "actorRole", "action",
                "targetType", "targetId", "applicationId", "details", "outcome");
        JsonNode keys = json(getAs("admin", "/api/v1/audit-logs?action=API_KEY_CREATED&applicationId=" + app.appId()));
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).get("actor").asText()).isEqualTo("admin");
        assertThat(keys.get(0).get("actorRole").asText()).isEqualTo("ADMIN");
        assertThat(keys.get(0).get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(keys.get(0).get("details").asText()).startsWith("Created key 'test key' (acc_");
        assertThat(keys.get(0).get("details").asText()).doesNotContain(app.apiKey());
        assertThat(json(getAs("admin", "/api/v1/audit-logs?limit=2"))).hasSize(2);
        getAs("admin", "/api/v1/audit-logs?limit=501").andExpect(status().isBadRequest());
        getAs("admin", "/api/v1/audit-logs?action=drop table").andExpect(status().isBadRequest());
        assertThat(getAs("admin", "/api/v1/audit-logs?limit=500").andReturn().getResponse().getContentAsString()).doesNotContain(app.apiKey());
    }
}

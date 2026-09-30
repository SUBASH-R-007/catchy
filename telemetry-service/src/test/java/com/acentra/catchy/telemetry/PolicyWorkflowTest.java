package com.acentra.catchy.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acentra.cache.EvictionPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolicyWorkflowTest extends AbstractApiTest {

    private TestApp app;

    @BeforeEach
    void app() throws Exception {
        app = createApp("claims-service");
        report(EvictionPolicy.LRU, Instant.now().minusSeconds(5));
    }

    private void report(EvictionPolicy policy, Instant at) throws Exception {
        ingest(app.apiKey(), TestData.batch(null, null, "pod-a", List.of(),
                List.of(TestData.snap().policy(policy).at(at).counters(700, 300, 300, 0, 0).shadow(1000, 60, 80, 70).build())))
                .andExpect(status().isAccepted());
    }

    private String requestsUrl() {
        return "/api/v1/applications/" + app.appId() + "/policy-change-requests";
    }

    private JsonNode create(String user, String policy) throws Exception {
        return json(postAs(user, requestsUrl(), "{\"cacheRegion\":\"claim-rules\",\"requestedPolicy\":\"" + policy
                + "\",\"reason\":\"Policy Arena recommends it\"}").andExpect(status().isCreated()));
    }

    private JsonNode control() throws Exception {
        return readJson(mvc.perform(get("/api/v1/telemetry/control").header("X-AcentraCache-Key", app.apiKey()))
                .andExpect(status().isOk()).andReturn());
    }

    @Test
    void engineerCreatesRequestWithValidation() throws Exception {
        JsonNode r = create("engineer", "LFU");
        assertThat(r.get("status").asText()).isEqualTo("PENDING");
        assertThat(r.get("requestedBy").asText()).isEqualTo("engineer");
        assertThat(r.get("currentPolicy").asText()).isEqualTo("LRU");
        assertThat(r.get("requestedPolicy").asText()).isEqualTo("LFU");
        assertThat(r.get("applicationName").asText()).isEqualTo("claims-service");
        assertThat(r.get("decidedBy").isNull()).isTrue();
        assertThat(auditCount("POLICY_CHANGE_REQUESTED", "SUCCESS")).isEqualTo(1);

        // one PENDING request per region
        postAs("engineer", requestsUrl(), "{\"cacheRegion\":\"claim-rules\",\"requestedPolicy\":\"LFU\"}").andExpect(status().isConflict());
        // the recommendation now shows the pending request
        JsonNode rec = json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/recommendations")).get(0);
        assertThat(rec.get("pendingRequestId").asLong()).isEqualTo(r.get("id").asLong());
        assertThat(json(getAs("viewer", requestsUrl()))).hasSize(1);
    }

    @Test
    void requestedPolicyMustDifferRegionMustExistAndBodyMustBeValid() throws Exception {
        postAs("engineer", requestsUrl(), "{\"cacheRegion\":\"claim-rules\",\"requestedPolicy\":\"LRU\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("requestedPolicy must differ from the current policy (LRU)"));
        postAs("engineer", requestsUrl(), "{\"cacheRegion\":\"nope\",\"requestedPolicy\":\"LFU\"}").andExpect(status().isNotFound());
        postAs("engineer", requestsUrl(), "{\"cacheRegion\":\"claim-rules\",\"requestedPolicy\":\"FIFO\"}").andExpect(status().isBadRequest());
        postAs("engineer", requestsUrl(), "{\"requestedPolicy\":\"LFU\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("cacheRegion: must not be blank"));
        postAs("engineer", requestsUrl(), "{\"cacheRegion\":\"claim-rules\",\"requestedPolicy\":\"LFU\",\"recommendationId\":424242}")
                .andExpect(status().isBadRequest());
        postAs("viewer", requestsUrl(), "{\"cacheRegion\":\"claim-rules\",\"requestedPolicy\":\"LFU\"}").andExpect(status().isForbidden());
    }

    @Test
    void engineerCannotApproveOrRejectOwnRequestButAdminCan() throws Exception {
        long id = create("engineer", "LFU").get("id").asLong();
        String base = "/api/v1/policy-change-requests/" + id;
        postAs("engineer", base + "/approve", null).andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        postAs("engineer", base + "/reject", null).andExpect(status().isForbidden());
        assertThat(auditCount("ACCESS_DENIED", "DENIED")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from policy_change_request where id = ?", String.class, id)).isEqualTo("PENDING");
        postAs("viewer", base + "/approve", null).andExpect(status().isForbidden());

        JsonNode approved = json(postAs("admin", base + "/approve", "{\"note\":\"Looks right; shadow gain is stable.\"}")
                .andExpect(status().isOk()));
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("decidedBy").asText()).isEqualTo("admin");
        assertThat(approved.get("decisionNote").asText()).startsWith("Looks right");
        assertThat(approved.get("decidedAt").asText()).isNotBlank();
        assertThat(auditCount("POLICY_CHANGE_APPROVED", "SUCCESS")).isEqualTo(1);

        // no longer pending
        postAs("admin", base + "/approve", null).andExpect(status().isConflict());
        postAs("admin", base + "/reject", null).andExpect(status().isConflict());
        postAs("admin", "/api/v1/policy-change-requests/999999/approve", null).andExpect(status().isNotFound());
    }

    @Test
    void adminMayApproveTheirOwnRequest() throws Exception {
        long id = create("admin", "LFU").get("id").asLong();
        JsonNode approved = json(postAs("admin", "/api/v1/policy-change-requests/" + id + "/approve", null).andExpect(status().isOk()));
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("requestedBy").asText()).isEqualTo("admin");
        assertThat(approved.get("decidedBy").asText()).isEqualTo("admin");
    }

    @Test
    void rejectedRequestIsNotDeliveredAndARegionCanBeRequestedAgain() throws Exception {
        long id = create("engineer", "LFU").get("id").asLong();
        JsonNode rejected = json(postAs("admin", "/api/v1/policy-change-requests/" + id + "/reject", "{\"note\":\"Not now\"}")
                .andExpect(status().isOk()));
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.get("decisionNote").asText()).isEqualTo("Not now");
        assertThat(auditCount("POLICY_CHANGE_REJECTED", "SUCCESS")).isEqualTo(1);
        assertThat(control().get("regions")).isEmpty();
        assertThat(create("engineer", "LFU").get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void approvedPolicyIsDeliveredThroughControlAndBecomesAppliedWhenTheSdkReportsIt() throws Exception {
        assertThat(control().get("regions")).isEmpty();
        long id = create("engineer", "LFU").get("id").asLong();
        assertThat(control().get("regions")).as("a pending request is not delivered").isEmpty();

        postAs("admin", "/api/v1/policy-change-requests/" + id + "/approve", null).andExpect(status().isOk());
        JsonNode region = control().get("regions").get(0);
        assertThat(region.get("cacheRegion").asText()).isEqualTo("claim-rules");
        assertThat(region.get("desiredPolicy").asText()).isEqualTo("LFU");
        assertThat(region.get("policyRequestId").asLong()).isEqualTo(id);
        assertThat(region.get("tuning").isNull()).isTrue();
        assertThat(region.get("tuningVersion").isNull()).isTrue();

        // a snapshot that still reports LRU leaves the request APPROVED
        report(EvictionPolicy.LRU, Instant.now().minusSeconds(3));
        assertThat(dbStatus(id)).isEqualTo("APPROVED");

        // the SDK applied the change: a later snapshot reports LFU
        report(EvictionPolicy.LFU, Instant.now());
        assertThat(dbStatus(id)).isEqualTo("APPLIED");
        JsonNode applied = json(getAs("viewer", requestsUrl())).get(0);
        assertThat(applied.get("status").asText()).isEqualTo("APPLIED");
        assertThat(applied.get("appliedAt").asText()).isNotBlank();
        assertThat(auditCount("POLICY_CHANGE_APPLIED", "SUCCESS")).isEqualTo(1);
        // an APPLIED request is still the desired state the SDK converges to (e.g. after an SDK restart)
        assertThat(control().get("regions").get(0).get("desiredPolicy").asText()).isEqualTo("LFU");

        // applying starts the cooldown, so the Policy Arena does not immediately flip back
        report(EvictionPolicy.LFU, Instant.now().plusSeconds(1));
        JsonNode rec = json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/recommendations")).get(0);
        assertThat(rec.get("currentPolicy").asText()).isEqualTo("LFU");
        assertThat(rec.get("cooldownActive").asBoolean()).isTrue();
    }

    @Test
    void latestDecisionWinsInControl() throws Exception {
        long first = create("engineer", "LFU").get("id").asLong();
        postAs("admin", "/api/v1/policy-change-requests/" + first + "/approve", null).andExpect(status().isOk());
        report(EvictionPolicy.LFU, Instant.now());
        long second = create("engineer", "LRU").get("id").asLong();
        postAs("admin", "/api/v1/policy-change-requests/" + second + "/approve", null).andExpect(status().isOk());
        JsonNode region = control().get("regions").get(0);
        assertThat(region.get("desiredPolicy").asText()).isEqualTo("LRU");
        assertThat(region.get("policyRequestId").asLong()).isEqualTo(second);
    }

    private String dbStatus(long id) {
        return jdbc.queryForObject("select status from policy_change_request where id = ?", String.class, id);
    }
}

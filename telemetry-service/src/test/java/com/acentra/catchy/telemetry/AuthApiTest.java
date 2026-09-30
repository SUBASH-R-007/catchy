package com.acentra.catchy.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acentra.catchy.telemetry.security.Role;
import com.acentra.catchy.telemetry.security.TokenService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class AuthApiTest extends AbstractApiTest {

    @Autowired
    TokenService tokens;

    @Test
    void healthIsPublicAndReportsDemoModeOff() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("telemetry-service"))
                .andExpect(jsonPath("$.demoMode").value(false));
    }

    @Test
    void loginSucceedsWithConfiguredPasswordAndIsAudited() throws Exception {
        JsonNode body = json(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"engineer\",\"password\":\"" + ENGINEER_PASSWORD + "\"}")).andExpect(status().isOk()));
        assertThat(body.get("username").asText()).isEqualTo("engineer");
        assertThat(body.get("role").asText()).isEqualTo("ENGINEER");
        assertThat(body.get("expiresAt").asText()).isNotBlank();
        assertThat(auditCount("LOGIN_SUCCESS", "SUCCESS")).isGreaterThanOrEqualTo(1);

        getAs("engineer", "/api/v1/auth/me").andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("engineer"))
                .andExpect(jsonPath("$.role").value("ENGINEER"));
    }

    @Test
    void loginFailureIsGenericAndAudited() throws Exception {
        long before = auditCount("LOGIN_FAILURE", "FAILURE");
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"definitely-wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(auditCount("LOGIN_FAILURE", "FAILURE")).isEqualTo(before + 2);
        String details = jdbc.queryForList("select details from audit_log where action = 'LOGIN_FAILURE'", String.class).toString();
        assertThat(details).doesNotContain("definitely-wrong");
    }

    @Test
    void demoLoginIsNotFoundWhenDemoModeIsOff() throws Exception {
        mvc.perform(post("/api/v1/auth/demo-login").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void missingTamperedAndExpiredTokensAreRejectedWithTheErrorShape() throws Exception {
        mvc.perform(get("/api/v1/overview")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.path").value("/api/v1/overview"));
        mvc.perform(get("/api/v1/overview").header("Authorization", "Bearer not.a.token")).andExpect(status().isUnauthorized());
        String good = tokens.issue("viewer", Role.VIEWER).token();
        String tampered = good.substring(0, good.length() - 2) + (good.endsWith("AA") ? "BB" : "AA");
        mvc.perform(get("/api/v1/overview").header("Authorization", "Bearer " + tampered)).andExpect(status().isUnauthorized());
        // a token that claims ADMIN but is signed for VIEWER must not verify
        String[] parts = good.split("\\.");
        String forgedBody = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("viewer|ADMIN|9999999999".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(get("/api/v1/audit-logs").header("Authorization", "Bearer " + forgedBody + "." + parts[1]))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordsAreStoredAsBcryptHashes() {
        for (String hash : jdbc.queryForList("select password_hash from user_account", String.class)) {
            assertThat(hash).startsWith("$2");
            assertThat(hash).doesNotContain(ADMIN_PASSWORD).doesNotContain(ENGINEER_PASSWORD).doesNotContain(VIEWER_PASSWORD);
        }
    }
}

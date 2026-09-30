package com.acentra.catchy.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Demo mode on, no passwords configured: users can only sign in through the one-click demo login. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "catchy.demo-mode=true",
        "catchy.security.admin-password=",
        "catchy.security.engineer-password=",
        "catchy.security.viewer-password=",
        "spring.datasource.url=jdbc:h2:mem:catchy-demo;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
class DemoModeTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    private JsonNode demoLogin(String role) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/demo-login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    @Test
    void healthReportsDemoMode() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk()).andExpect(jsonPath("$.demoMode").value(true));
    }

    @Test
    void demoLoginIssuesAWorkingTokenForEachRoleAndIsAudited() throws Exception {
        for (String role : new String[] {"VIEWER", "ENGINEER", "ADMIN"}) {
            JsonNode login = demoLogin(role);
            assertThat(login.get("role").asText()).isEqualTo(role);
            assertThat(login.get("username").asText()).isEqualTo(role.toLowerCase());
            assertThat(login.get("expiresAt").asText()).isNotBlank();
            mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + login.get("token").asText()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.role").value(role));
        }
        Long audited = jdbc.queryForObject(
                "select count(*) from audit_log where action = 'LOGIN_SUCCESS' and details like 'Demo login%'", Long.class);
        assertThat(audited).isGreaterThanOrEqualTo(3);
        mvc.perform(post("/api/v1/auth/demo-login").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"SUPERUSER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void usersWithoutConfiguredPasswordCannotUsePasswordLogin() throws Exception {
        for (String user : new String[] {"admin", "engineer", "viewer"}) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + user + "\",\"password\":\"admin\"}"))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(jdbc.queryForList("select password_hash from user_account", String.class)).allMatch(h -> h == null);
    }

    @Test
    void demoEngineerCanWorkButNotAdminThings() throws Exception {
        String engineer = demoLogin("ENGINEER").get("token").asText();
        mvc.perform(get("/api/v1/overview").header("Authorization", "Bearer " + engineer)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/audit-logs").header("Authorization", "Bearer " + engineer)).andExpect(status().isForbidden());
    }
}

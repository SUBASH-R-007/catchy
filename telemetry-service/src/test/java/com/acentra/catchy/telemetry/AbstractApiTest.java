package com.acentra.catchy.telemetry;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.acentra.cache.telemetry.TelemetryJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Shared plumbing for the MockMvc integration tests (H2 in PostgreSQL mode, profile "test"). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class AbstractApiTest {

    protected static final String ADMIN_PASSWORD = "test-admin-pass";
    protected static final String ENGINEER_PASSWORD = "test-engineer-pass";
    protected static final String VIEWER_PASSWORD = "test-viewer-pass";
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();
    private static final List<String> TABLES = List.of("cache_policy_recommendation", "policy_change_request",
            "region_config_override", "cache_telemetry_event", "cache_metrics_snapshot", "application_api_key",
            "application_service", "project", "audit_log");

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;

    /** The SDK's own mapper: produces exactly the JSON a real client sends. */
    protected final ObjectMapper sdkJson = TelemetryJson.mapper();

    record TestApp(long projectId, long appId, String name, String apiKey) {}

    @BeforeEach
    void cleanDatabase() {
        for (String table : TABLES) jdbc.update("delete from " + table);
    }

    // ---- auth --------------------------------------------------------------------------------------------------------

    protected String token(String username) throws Exception {
        String cached = TOKENS.get(username);
        if (cached != null) return cached;
        String password = switch (username) {
            case "admin" -> ADMIN_PASSWORD;
            case "engineer" -> ENGINEER_PASSWORD;
            default -> VIEWER_PASSWORD;
        };
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")).andReturn();
        String token = readJson(r).get("token").asText();
        TOKENS.put(username, token);
        return token;
    }

    // ---- request helpers ---------------------------------------------------------------------------------------------

    protected ResultActions getAs(String user, String url) throws Exception {
        return mvc.perform(withAuth(get(url), user));
    }

    protected ResultActions postAs(String user, String url, String body) throws Exception {
        MockHttpServletRequestBuilder b = withAuth(post(url), user);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(b);
    }

    protected ResultActions putAs(String user, String url, String body) throws Exception {
        return mvc.perform(withAuth(put(url), user).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions deleteAs(String user, String url) throws Exception {
        return mvc.perform(withAuth(delete(url), user));
    }

    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder b, String user) throws Exception {
        return user == null ? b : b.header("Authorization", "Bearer " + token(user));
    }

    protected ResultActions ingestRaw(String apiKey, String rawJson) throws Exception {
        MockHttpServletRequestBuilder b = post("/api/v1/telemetry/events/batch").contentType(MediaType.APPLICATION_JSON).content(rawJson);
        if (apiKey != null) b.header("X-AcentraCache-Key", apiKey);
        return mvc.perform(b);
    }

    protected ResultActions ingest(String apiKey, Object body) throws Exception {
        return ingestRaw(apiKey, sdkJson.writeValueAsString(body));
    }

    protected JsonNode readJson(MvcResult result) throws Exception {
        return sdkJson.readTree(result.getResponse().getContentAsString());
    }

    protected JsonNode json(ResultActions actions) throws Exception {
        return readJson(actions.andReturn());
    }

    // ---- fixtures ----------------------------------------------------------------------------------------------------

    /** Creates a project, an application (staging) and an API key through the admin API. */
    protected TestApp createApp(String name) throws Exception {
        JsonNode project = json(postAs("admin", "/api/v1/projects", "{\"name\":\"Project " + name + "\"}"));
        long projectId = project.get("id").asLong();
        JsonNode app = json(postAs("admin", "/api/v1/projects/" + projectId + "/applications",
                "{\"name\":\"" + name + "\",\"environment\":\"staging\"}"));
        long appId = app.get("id").asLong();
        JsonNode key = json(postAs("admin", "/api/v1/applications/" + appId + "/api-keys", "{\"label\":\"test key\"}"));
        return new TestApp(projectId, appId, name, key.get("apiKey").asText());
    }

    protected long count(String table) {
        Long n = jdbc.queryForObject("select count(*) from " + table, Long.class);
        return n == null ? 0 : n;
    }

    protected long auditCount(String action, String outcome) {
        Long n = jdbc.queryForObject("select count(*) from audit_log where action = ? and outcome = ?", Long.class, action, outcome);
        return n == null ? 0 : n;
    }
}

package com.acentra.catchy.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Role authorization matrix: VIEWER < ENGINEER < ADMIN, API keys never act as dashboard users. */
class SecurityMatrixTest extends AbstractApiTest {

    private record Endpoint(HttpMethod method, String path, String body, String minRole) {}

    private static final List<String> ROLES = List.of("viewer", "engineer", "admin");

    private int call(String user, Endpoint e, TestApp app) throws Exception {
        String path = e.path().replace("{app}", String.valueOf(app.appId())).replace("{project}", String.valueOf(app.projectId()));
        MockHttpServletRequestBuilder b = request(e.method(), path);
        if (user != null) b.header("Authorization", "Bearer " + token(user));
        if (e.body() != null) b.contentType(MediaType.APPLICATION_JSON).content(e.body());
        return mvc.perform(b).andReturn().getResponse().getStatus();
    }

    @Test
    void everyEndpointEnforcesItsMinimumRole() throws Exception {
        TestApp app = createApp("claims-service");
        List<Endpoint> endpoints = List.of(
                new Endpoint(HttpMethod.GET, "/api/v1/overview", null, "viewer"),
                new Endpoint(HttpMethod.GET, "/api/v1/auth/me", null, "viewer"),
                new Endpoint(HttpMethod.GET, "/api/v1/projects", null, "viewer"),
                new Endpoint(HttpMethod.GET, "/api/v1/projects/{project}/metrics", null, "viewer"),
                new Endpoint(HttpMethod.GET, "/api/v1/applications", null, "viewer"),
                new Endpoint(HttpMethod.GET, "/api/v1/applications/{app}/metrics", null, "viewer"),
                new Endpoint(HttpMethod.GET, "/api/v1/applications/{app}/regions", null, "viewer"),
                new Endpoint(HttpMethod.GET, "/api/v1/applications/{app}/regions/x/events", null, "viewer"),
                new Endpoint(HttpMethod.GET, "/api/v1/applications/{app}/regions/x/config", null, "viewer"),
                new Endpoint(HttpMethod.GET, "/api/v1/applications/{app}/recommendations", null, "viewer"),
                new Endpoint(HttpMethod.GET, "/api/v1/applications/{app}/policy-change-requests", null, "viewer"),
                new Endpoint(HttpMethod.GET, "/api/v1/timeline", null, "viewer"),
                new Endpoint(HttpMethod.POST, "/api/v1/applications/{app}/simulate/unknown-kind", null, "engineer"),
                new Endpoint(HttpMethod.POST, "/api/v1/applications/{app}/recommendations/evaluate", null, "engineer"),
                new Endpoint(HttpMethod.POST, "/api/v1/applications/{app}/policy-change-requests", "{}", "engineer"),
                new Endpoint(HttpMethod.POST, "/api/v1/policy-change-requests/999999/approve", null, "engineer"),
                new Endpoint(HttpMethod.POST, "/api/v1/policy-change-requests/999999/reject", null, "engineer"),
                new Endpoint(HttpMethod.POST, "/api/v1/projects", "{\"name\":\"Matrix Project\"}", "admin"),
                new Endpoint(HttpMethod.POST, "/api/v1/projects/{project}/applications", "{\"name\":\"matrix-app\",\"environment\":\"dev\"}", "admin"),
                new Endpoint(HttpMethod.GET, "/api/v1/applications/{app}/api-keys", null, "admin"),
                new Endpoint(HttpMethod.POST, "/api/v1/applications/{app}/api-keys", "{\"label\":\"m\"}", "admin"),
                new Endpoint(HttpMethod.DELETE, "/api/v1/api-keys/999999", null, "admin"),
                new Endpoint(HttpMethod.PUT, "/api/v1/applications/{app}/regions/x/config", "{\"maximumEntries\":5}", "admin"),
                new Endpoint(HttpMethod.GET, "/api/v1/audit-logs", null, "admin"),
                // anything mutating that is not explicitly listed defaults to ADMIN
                new Endpoint(HttpMethod.POST, "/api/v1/not-a-real-endpoint", "{}", "admin"));

        int denials = 0;
        List<String> failures = new ArrayList<>();
        for (Endpoint e : endpoints) {
            int anonymous = call(null, e, app);
            if (anonymous != 401) failures.add("anonymous " + e.method() + " " + e.path() + " -> " + anonymous);
            for (String role : ROLES) {
                int actual = call(role, e, app);
                boolean allowed = ROLES.indexOf(role) >= ROLES.indexOf(e.minRole());
                if (allowed && (actual == 401 || actual == 403)) failures.add(role + " should reach " + e.method() + " " + e.path() + " but got " + actual);
                if (!allowed && actual != 403) failures.add(role + " must be denied " + e.method() + " " + e.path() + " but got " + actual);
                if (!allowed && actual == 403) denials++;
            }
        }
        assertThat(failures).isEmpty();
        // every denial of an authenticated user is written to the audit log
        assertThat(auditCount("ACCESS_DENIED", "DENIED")).isEqualTo(denials);
    }

    @Test
    void deniedResponsesUseTheErrorShapeAndNameTheRoleProblemOnly() throws Exception {
        mvc.perform(request(HttpMethod.GET, "/api/v1/audit-logs").header("Authorization", "Bearer " + token("viewer")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("Access denied: insufficient role"))
                .andExpect(jsonPath("$.path").value("/api/v1/audit-logs"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
        String details = jdbc.queryForObject("select details from audit_log where action = 'ACCESS_DENIED'", String.class);
        assertThat(details).contains("GET /api/v1/audit-logs").contains("VIEWER");
        assertThat(jdbc.queryForObject("select actor from audit_log where action = 'ACCESS_DENIED'", String.class)).isEqualTo("viewer");
    }

    @Test
    void unknownPathsAndMethodsUseTheErrorShape() throws Exception {
        mvc.perform(request(HttpMethod.GET, "/api/v1/nothing-here").header("Authorization", "Bearer " + token("viewer")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        mvc.perform(request(HttpMethod.POST, "/api/v1/overview").header("Authorization", "Bearer " + token("admin")))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.status").value(405));
        mvc.perform(request(HttpMethod.GET, "/api/v1/applications/abc/metrics").header("Authorization", "Bearer " + token("viewer")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid value for parameter 'applicationId'"));
    }

    @Test
    void corsIsRestrictedToTheDashboardOrigin() throws Exception {
        mvc.perform(options("/api/v1/overview").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/api/v1/overview").header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void apiKeysDoNotActAsDashboardUsers() throws Exception {
        TestApp app = createApp("claims-service");
        mvc.perform(request(HttpMethod.GET, "/api/v1/overview").header("X-AcentraCache-Key", app.apiKey()))
                .andExpect(status().isUnauthorized());
        mvc.perform(request(HttpMethod.GET, "/api/v1/audit-logs").header("X-AcentraCache-Key", app.apiKey()))
                .andExpect(status().isUnauthorized());
    }
}

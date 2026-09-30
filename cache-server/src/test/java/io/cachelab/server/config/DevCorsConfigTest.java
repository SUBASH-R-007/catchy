package io.cachelab.server.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cachelab.server.api.HealthController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
@ActiveProfiles("dev")
class DevCorsConfigTest {

  private static final String DASHBOARD = DevCorsConfig.DASHBOARD_DEV_ORIGIN;

  @Autowired private MockMvc mockMvc;

  @Test
  void allowsTheViteDevServer() throws Exception {
    mockMvc
        .perform(get("/api/health").header(HttpHeaders.ORIGIN, DASHBOARD))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, DASHBOARD));
  }

  @Test
  void preflightAllowsTheApiMethods() throws Exception {
    mockMvc
        .perform(
            options("/api/health")
                .header(HttpHeaders.ORIGIN, DASHBOARD)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, DASHBOARD))
        .andExpect(
            header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,PUT,DELETE"));
  }

  @Test
  void rejectsOtherOrigins() throws Exception {
    mockMvc
        .perform(get("/api/health").header(HttpHeaders.ORIGIN, "http://evil.example"))
        .andExpect(status().isForbidden());
  }
}

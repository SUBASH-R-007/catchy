package io.cachelab.server.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness endpoint used by the dashboard's connection LED and by deployment checks. */
@RestController
@RequestMapping("/api")
public class HealthController {

  /**
   * Body of {@code GET /api/health}.
   *
   * @param status always {@code "UP"} while the server answers
   */
  public record HealthStatus(String status) {}

  private static final HealthStatus UP = new HealthStatus("UP");

  /**
   * Reports that the server is up.
   *
   * @return {@code {"status":"UP"}}
   */
  @GetMapping("/health")
  public HealthStatus health() {
    return UP;
  }
}

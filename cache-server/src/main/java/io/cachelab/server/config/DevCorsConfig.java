package io.cachelab.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows the Vite dev server ({@value #DASHBOARD_DEV_ORIGIN}) to call {@code /api/**}. Active only
 * under the {@code dev} profile; in production the dashboard is served from the same origin.
 */
@Configuration(proxyBeanMethods = false)
@Profile("dev")
public class DevCorsConfig implements WebMvcConfigurer {

  /** Origin of the dashboard's Vite dev server. */
  public static final String DASHBOARD_DEV_ORIGIN = "http://localhost:5173";

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        .allowedOrigins(DASHBOARD_DEV_ORIGIN)
        .allowedMethods("GET", "POST", "PUT", "DELETE");
  }
}

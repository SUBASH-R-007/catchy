package io.cachelab.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the CacheLab demo server: REST API, the metrics SSE stream and (from Step 3) the
 * workload lab.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CacheLabServerApplication {

  /**
   * Starts the server.
   *
   * @param args command-line arguments, passed through to Spring Boot
   */
  public static void main(String[] args) {
    SpringApplication.run(CacheLabServerApplication.class, args);
  }
}

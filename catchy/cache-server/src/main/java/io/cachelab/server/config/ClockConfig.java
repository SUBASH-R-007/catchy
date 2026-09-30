package io.cachelab.server.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides the wall {@link Clock} used for timestamps, so tests can substitute a fixed one. */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

  /**
   * The server clock.
   *
   * @return the system clock in UTC; thread-safe
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}

package io.cachelab.server.config;

import io.cachelab.Ticker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides the TTL time source; tests replace it with a manually advanced ticker. */
@Configuration(proxyBeanMethods = false)
public class TickerConfig {

  /**
   * The system nanosecond ticker.
   *
   * @return {@link Ticker#system()}
   */
  @Bean
  public Ticker ticker() {
    return Ticker.system();
  }
}

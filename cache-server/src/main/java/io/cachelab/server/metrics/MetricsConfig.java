package io.cachelab.server.metrics;

import io.cachelab.server.config.CacheLabProperties;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the metrics stream: the {@link CostModel} and the {@link MetricsSource} chosen by {@code
 * cachelab.metrics.fake}.
 */
@Configuration(proxyBeanMethods = false)
public class MetricsConfig {

  private static final Logger log = LoggerFactory.getLogger(MetricsConfig.class);

  /**
   * The cost model built from {@code cachelab.cost.*}.
   *
   * @param properties bound configuration
   * @return the cost model
   */
  @Bean
  public CostModel costModel(CacheLabProperties properties) {
    CacheLabProperties.Cost cost = properties.cost();
    return new CostModel(cost.meanDbLatencyMs(), cost.perThousandCalls(), cost.currency());
  }

  /**
   * The source of the metrics stream: {@link FakeMetricsSource} when {@code
   * cachelab.metrics.fake=true}, otherwise {@link EmptyMetricsSource} (until Step 3 wires real
   * caches).
   *
   * @param properties bound configuration
   * @param clock clock for timestamps
   * @param costModel cost model for the fake estimates
   * @return the metrics source
   */
  @Bean
  public MetricsSource metricsSource(
      CacheLabProperties properties, Clock clock, CostModel costModel) {
    CacheLabProperties.Metrics metrics = properties.metrics();
    if (metrics.fake()) {
      log.info(
          "Metrics stream uses the FAKE generator (seed {}), for development only", metrics.seed());
      return new FakeMetricsSource(metrics.seed(), clock, costModel);
    }
    return new EmptyMetricsSource(clock);
  }
}

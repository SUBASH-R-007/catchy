package io.cachelab.server.metrics;

import io.cachelab.server.cache.CacheRegistry;
import io.cachelab.server.cache.EventRing;
import io.cachelab.server.config.CacheLabProperties;
import io.cachelab.server.simulation.SimulationService;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
   * The source of the metrics stream: {@link RealMetricsSource} over the live caches (the default,
   * {@code cachelab.metrics.fake=false}), or the seeded {@link FakeMetricsSource} for dashboard
   * development when {@code cachelab.metrics.fake=true}.
   *
   * @param properties bound configuration
   * @param clock clock for timestamps
   * @param costModel cost model for the estimates
   * @param registry the live caches
   * @param events the removal event ring
   * @param latencies per-cache latency recorders
   * @param simulations supplies the simulation status (resolved lazily)
   * @return the metrics source
   */
  @Bean
  public MetricsSource metricsSource(
      CacheLabProperties properties,
      Clock clock,
      CostModel costModel,
      CacheRegistry registry,
      EventRing events,
      LatencyRecorders latencies,
      ObjectProvider<SimulationService> simulations) {
    CacheLabProperties.Metrics metrics = properties.metrics();
    if (metrics.fake()) {
      log.info(
          "Metrics stream uses the FAKE generator (seed {}), for development only", metrics.seed());
      return new FakeMetricsSource(metrics.seed(), clock, costModel);
    }
    return new RealMetricsSource(
        registry,
        events,
        latencies,
        () -> simulations.getObject().status(),
        costModel,
        clock,
        System::nanoTime);
  }
}

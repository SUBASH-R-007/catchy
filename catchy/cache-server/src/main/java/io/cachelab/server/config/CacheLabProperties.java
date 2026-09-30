package io.cachelab.server.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed view of the {@code cachelab.*} configuration properties.
 *
 * <p>Immutable and thread-safe. Missing properties fall back to the documented defaults, so no
 * component is ever {@code null}.
 *
 * @param metrics settings of the metrics stream ({@code cachelab.metrics.*})
 * @param cost settings of the cost model ({@code cachelab.cost.*})
 */
@Validated
@ConfigurationProperties(prefix = "cachelab")
public record CacheLabProperties(
    @Valid @NotNull @DefaultValue Metrics metrics, @Valid @NotNull @DefaultValue Cost cost) {

  /**
   * Metrics stream settings.
   *
   * @param fake {@code true} to feed the SSE stream from the seeded fake generator instead of real
   *     caches
   * @param seed seed of the fake generator; the same seed produces the same sequence of ticks
   */
  public record Metrics(@DefaultValue("false") boolean fake, @DefaultValue("42") long seed) {}

  /**
   * Cost model assumptions, shown in the dashboard as estimates.
   *
   * @param perThousandCalls estimated cost of 1,000 database calls, in {@code currency}
   * @param currency currency label, for example {@code "$"}
   * @param meanDbLatencyMs mean simulated database latency per call, in milliseconds
   */
  public record Cost(
      @PositiveOrZero @DefaultValue("0.05") double perThousandCalls,
      @NotNull @DefaultValue("$") String currency,
      @PositiveOrZero @DefaultValue("12.5") double meanDbLatencyMs) {}
}

package io.cachelab.server.metrics;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Placeholder used when {@code cachelab.metrics.fake=false} until real caches are wired (Step 3):
 * every tick carries only a timestamp, empty lists and no simulation.
 *
 * <p>Thread-safe; {@link #next()} is O(1).
 */
public final class EmptyMetricsSource implements MetricsSource {

  private final Clock clock;

  /**
   * Creates the source.
   *
   * @param clock clock for {@code ts}; never {@code null}
   */
  public EmptyMetricsSource(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public MetricsSnapshot next() {
    return new MetricsSnapshot(clock.millis(), List.of(), List.of(), null, List.of());
  }
}

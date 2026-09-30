package io.cachelab.server.metrics;

import java.util.List;
import java.util.Objects;

/**
 * One tick of the metrics stream: the payload of each SSE event named {@code metrics} (schema v2,
 * {@code docs/api/metrics.schema.json}). Field names and order mirror the schema exactly.
 *
 * <p>Immutable and thread-safe; the lists are defensive copies.
 *
 * @param ts server time of this tick, epoch milliseconds
 * @param caches per-cache metrics; never {@code null}
 * @param groups per-group metrics; never {@code null}
 * @param simulation the running simulation, or {@code null} when none runs
 * @param events removals since the previous tick, at most {@value #MAX_EVENTS}; never {@code null}
 */
public record MetricsSnapshot(
    long ts,
    List<CacheMetrics> caches,
    List<GroupMetrics> groups,
    SimulationStatus simulation,
    List<RemovalEvent> events) {

  /** Maximum number of removal events carried by one tick. */
  public static final int MAX_EVENTS = 50;

  /**
   * Validates and copies the lists.
   *
   * @throws NullPointerException if a list or one of its elements is {@code null}
   * @throws IllegalArgumentException if {@code events} holds more than {@value #MAX_EVENTS} items
   */
  public MetricsSnapshot {
    caches = List.copyOf(Objects.requireNonNull(caches, "caches"));
    groups = List.copyOf(Objects.requireNonNull(groups, "groups"));
    events = List.copyOf(Objects.requireNonNull(events, "events"));
    if (events.size() > MAX_EVENTS) {
      throw new IllegalArgumentException("at most " + MAX_EVENTS + " events per tick");
    }
  }
}

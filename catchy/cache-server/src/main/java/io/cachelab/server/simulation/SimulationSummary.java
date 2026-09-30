package io.cachelab.server.simulation;

import io.cachelab.PolicyType;
import java.util.List;
import java.util.Objects;

/**
 * Final per-cache results of one simulation, kept for reports (SPEC 9.5). Counts cover only this
 * simulation: they are the difference between each cache's statistics at the end and at the start.
 *
 * <p>Immutable and thread-safe.
 *
 * @param id the simulation id
 * @param plan what was run
 * @param startedTs epoch milliseconds when it started
 * @param endedTs epoch milliseconds when it ended
 * @param ops operations executed (each applied to every cache)
 * @param completed {@code true} if it ran to the end, {@code false} if it was stopped or failed
 * @param caches per-cache results, in the group's order
 */
public record SimulationSummary(
    String id,
    SimulationPlan plan,
    long startedTs,
    long endedTs,
    long ops,
    boolean completed,
    List<CacheSummary> caches) {

  /** Copies {@code caches}. */
  public SimulationSummary {
    caches = List.copyOf(Objects.requireNonNull(caches, "caches"));
  }

  /**
   * Results of one cache.
   *
   * @param name cache name
   * @param policy policy at the end of the run
   * @param hits hits during the run
   * @param misses misses during the run
   * @param hitRate {@code hits / (hits + misses)}, 0 without requests
   * @param evictions evictions during the run
   * @param expirations expirations during the run
   * @param dbCalls simulated database loads (one per read miss)
   * @param meanDbLatencyMs mean simulated latency of those loads, 0 without loads
   */
  public record CacheSummary(
      String name,
      PolicyType policy,
      long hits,
      long misses,
      double hitRate,
      long evictions,
      long expirations,
      long dbCalls,
      double meanDbLatencyMs) {}
}

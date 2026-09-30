package io.cachelab.server.metrics;

import java.util.List;
import java.util.Objects;

/**
 * Metrics of one comparison group in a {@link MetricsSnapshot} ({@code $defs/group} in the schema).
 *
 * <p>Immutable and thread-safe; {@code caches} is a defensive copy.
 *
 * @param name group name
 * @param caches names of the caches in the group; never {@code null}
 * @param optimalHitRate Bélády (MIN) hit rate over the group's recorded trace, or {@code null} when
 *     not yet computed
 * @param advisor the policy advisor's recommendation, or {@code null} when there is none
 */
public record GroupMetrics(
    String name, List<String> caches, Double optimalHitRate, AdvisorRecommendation advisor) {

  /**
   * Copies {@code caches}.
   *
   * @throws NullPointerException if {@code caches} or one of its elements is {@code null}
   */
  public GroupMetrics {
    caches = List.copyOf(Objects.requireNonNull(caches, "caches"));
  }
}

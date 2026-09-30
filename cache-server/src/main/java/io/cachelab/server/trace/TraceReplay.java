package io.cachelab.server.trace;

import io.cachelab.PolicyType;
import io.cachelab.advisor.OptimalReplay;
import io.cachelab.advisor.ShadowCache;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Replays a trace offline and unthrottled (SPEC 9.5): through a fresh {@link ShadowCache} per
 * requested policy (logical time, so LFU_DECAY decays every 50,000 accesses) and through Bélády's
 * {@link OptimalReplay}. TTL plays no part; the results are pure eviction-policy comparisons.
 *
 * <p>Thread-safety: stateless. Complexity: O(N) per policy plus O(N log C) for the optimal, for N
 * rows and capacity C.
 */
public final class TraceReplay {

  private TraceReplay() {}

  /**
   * The result of one policy.
   *
   * @param policy the simulated policy
   * @param hitRate hits ÷ rows
   * @param hits simulated hits
   * @param misses simulated misses
   * @param evictions simulated evictions
   */
  public record PolicyResult(
      PolicyType policy, double hitRate, long hits, long misses, long evictions) {}

  /**
   * The result of a replay.
   *
   * @param traceId the trace
   * @param rows rows replayed
   * @param capacity simulated capacity
   * @param results one result per requested policy, in request order
   * @param optimalHitRate Bélády's hit rate: the upper bound for any policy
   * @param durationMs wall time the replay took
   */
  public record Result(
      String traceId,
      int rows,
      int capacity,
      List<PolicyResult> results,
      double optimalHitRate,
      long durationMs) {

    /** Copies {@code results}. */
    public Result {
      results = List.copyOf(results);
    }
  }

  /**
   * Replays a trace.
   *
   * @param traceId the trace id, echoed in the result
   * @param keys the trace; never empty
   * @param capacity the simulated capacity, at least 1
   * @param policies the policies to compare, without duplicates; never empty
   * @return the per-policy results and the optimal hit rate
   */
  public static Result replay(
      String traceId, List<String> keys, int capacity, List<PolicyType> policies) {
    Objects.requireNonNull(keys, "keys");
    long start = System.nanoTime();
    List<PolicyResult> results = new ArrayList<>(policies.size());
    for (PolicyType policy : policies) {
      ShadowCache.Result r = ShadowCache.replay(keys, capacity, policy);
      results.add(new PolicyResult(policy, r.hitRate(), r.hits(), r.misses(), r.evictions()));
    }
    double optimal = OptimalReplay.hitRate(keys, capacity);
    long durationMs = (System.nanoTime() - start) / 1_000_000;
    return new Result(traceId, keys.size(), capacity, results, optimal, durationMs);
  }
}

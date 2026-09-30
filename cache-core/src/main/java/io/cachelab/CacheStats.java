package io.cachelab;

/**
 * An immutable snapshot of a cache's cumulative statistics.
 *
 * <p>A <em>request</em> is one lookup through {@link Cache#get(Object)} or {@link
 * Cache#getOrLoad(Object, java.util.function.Function)}; each request is either a hit or a miss.
 * Rates are in the range 0.0 to 1.0 and are 0.0 (never {@code NaN}) when there were no requests.
 *
 * <p>Snapshots can be subtracted with {@link #minus(CacheStats)} to compute the activity in an
 * interval, and added with {@link #plus(CacheStats)} to aggregate several caches or segments.
 *
 * <p>Null handling: methods taking another snapshot reject null with {@link NullPointerException}.
 * Thread-safety: immutable. Complexity: every method is O(1).
 *
 * @param hitCount lookups that found a live value
 * @param missCount lookups that found nothing, or only an expired value
 * @param evictionCount entries evicted by the eviction policy
 * @param expirationCount entries removed because their TTL elapsed
 * @param loadSuccessCount loader invocations that returned a value
 * @param loadFailureCount loader invocations that threw or returned null
 * @param totalLoadTimeNanos total time spent in loader invocations, in nanoseconds
 * @param putCount calls to {@code put}, including those made on behalf of a successful load
 */
public record CacheStats(
    long hitCount,
    long missCount,
    long evictionCount,
    long expirationCount,
    long loadSuccessCount,
    long loadFailureCount,
    long totalLoadTimeNanos,
    long putCount) {

  private static final CacheStats EMPTY = new CacheStats(0, 0, 0, 0, 0, 0, 0, 0);

  /**
   * Validates that no counter is negative.
   *
   * @throws IllegalArgumentException if any counter is negative
   */
  public CacheStats {
    requireNonNegative("hitCount", hitCount);
    requireNonNegative("missCount", missCount);
    requireNonNegative("evictionCount", evictionCount);
    requireNonNegative("expirationCount", expirationCount);
    requireNonNegative("loadSuccessCount", loadSuccessCount);
    requireNonNegative("loadFailureCount", loadFailureCount);
    requireNonNegative("totalLoadTimeNanos", totalLoadTimeNanos);
    requireNonNegative("putCount", putCount);
  }

  /**
   * Returns a snapshot with every counter at zero.
   *
   * @return the empty snapshot, never null
   */
  public static CacheStats empty() {
    return EMPTY;
  }

  /**
   * Returns the number of lookups: hits plus misses (saturating at {@link Long#MAX_VALUE}).
   *
   * @return the request count
   */
  public long requestCount() {
    return saturatedAdd(hitCount, missCount);
  }

  /**
   * Returns the fraction of lookups that were hits.
   *
   * @return hits divided by requests, or 0.0 when there were no requests
   */
  public double hitRate() {
    long requests = requestCount();
    return requests == 0 ? 0.0 : (double) hitCount / requests;
  }

  /**
   * Returns the fraction of lookups that were misses.
   *
   * @return misses divided by requests, or 0.0 when there were no requests
   */
  public double missRate() {
    long requests = requestCount();
    return requests == 0 ? 0.0 : (double) missCount / requests;
  }

  /**
   * Returns the number of loader invocations, successful or not.
   *
   * @return load successes plus load failures (saturating)
   */
  public long loadCount() {
    return saturatedAdd(loadSuccessCount, loadFailureCount);
  }

  /**
   * Returns the average time spent per loader invocation.
   *
   * @return total load time divided by the load count, in nanoseconds, or 0.0 when there were no
   *     loads
   */
  public double averageLoadPenaltyNanos() {
    long loads = loadCount();
    return loads == 0 ? 0.0 : (double) totalLoadTimeNanos / loads;
  }

  /**
   * Returns the difference between this snapshot and an earlier one, counter by counter. Each
   * result is clamped at zero, so a reset baseline never produces negative counts.
   *
   * @param other the earlier snapshot; must not be null
   * @return a new snapshot holding {@code max(0, this - other)} for each counter
   * @throws NullPointerException if {@code other} is null
   */
  public CacheStats minus(CacheStats other) {
    return new CacheStats(
        Math.max(0, hitCount - other.hitCount),
        Math.max(0, missCount - other.missCount),
        Math.max(0, evictionCount - other.evictionCount),
        Math.max(0, expirationCount - other.expirationCount),
        Math.max(0, loadSuccessCount - other.loadSuccessCount),
        Math.max(0, loadFailureCount - other.loadFailureCount),
        Math.max(0, totalLoadTimeNanos - other.totalLoadTimeNanos),
        Math.max(0, putCount - other.putCount));
  }

  /**
   * Returns the counter-by-counter sum of this snapshot and another, saturating at {@link
   * Long#MAX_VALUE}.
   *
   * @param other the snapshot to add; must not be null
   * @return a new snapshot holding the sums
   * @throws NullPointerException if {@code other} is null
   */
  public CacheStats plus(CacheStats other) {
    return new CacheStats(
        saturatedAdd(hitCount, other.hitCount),
        saturatedAdd(missCount, other.missCount),
        saturatedAdd(evictionCount, other.evictionCount),
        saturatedAdd(expirationCount, other.expirationCount),
        saturatedAdd(loadSuccessCount, other.loadSuccessCount),
        saturatedAdd(loadFailureCount, other.loadFailureCount),
        saturatedAdd(totalLoadTimeNanos, other.totalLoadTimeNanos),
        saturatedAdd(putCount, other.putCount));
  }

  private static long saturatedAdd(long a, long b) {
    long sum = a + b;
    // Overflow happened only if both operands share a sign that the sum does not.
    return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
  }

  private static void requireNonNegative(String name, long value) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must not be negative, but was " + value);
    }
  }
}

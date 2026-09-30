package io.cachelab.advisor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Bélády's optimal offline cache-aside (the MIN algorithm, SPEC 6.4): on a miss with a full cache,
 * evict the resident key whose next use lies furthest in the future. No real policy can beat it, so
 * its hit rate is the upper bound shown as the "optimal" line — it needs to know the future, so it
 * is a yardstick, not a deployable policy.
 *
 * <p>Next-use indices are precomputed with one backward pass; a key that never recurs gets {@code
 * Long.MAX_VALUE - i}, so every next-use value is unique and can key a {@link TreeMap} of
 * residents.
 *
 * <p>Thread-safety: stateless. Complexity: O(N log C) time and O(N) memory for a trace of N
 * accesses and capacity C.
 */
public final class OptimalReplay {

  private OptimalReplay() {}

  /**
   * The outcome of a replay.
   *
   * @param hits accesses served from the cache
   * @param misses accesses that loaded the key
   */
  public record Result(long hits, long misses) {

    /**
     * Returns hits divided by accesses.
     *
     * @return the hit rate, 0 for an empty trace
     */
    public double hitRate() {
      long total = hits + misses;
      return total == 0 ? 0.0 : (double) hits / total;
    }
  }

  /**
   * Returns the optimal hit rate for a trace.
   *
   * @param trace the access sequence; must not be null or contain null
   * @param capacity the cache size, at least 1
   * @param <K> the key type
   * @return hits ÷ accesses under MIN
   */
  public static <K> double hitRate(List<K> trace, int capacity) {
    return replay(trace, capacity).hitRate();
  }

  /**
   * Replays a trace under MIN.
   *
   * @param trace the access sequence; must not be null or contain null
   * @param capacity the cache size, at least 1
   * @param <K> the key type
   * @return the hit and miss counts
   * @throws IllegalArgumentException if {@code capacity} is less than 1
   */
  public static <K> Result replay(List<K> trace, int capacity) {
    Objects.requireNonNull(trace, "trace");
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be at least 1, but was " + capacity);
    }
    long[] nextUse = nextUses(trace);
    TreeMap<Long, K> residentsByNextUse = new TreeMap<>();
    Map<K, Long> nextUseOfResident = new HashMap<>();
    long hits = 0;
    for (int i = 0; i < trace.size(); i++) {
      K key = trace.get(i);
      Long current = nextUseOfResident.get(key);
      if (current != null) {
        hits++;
        residentsByNextUse.remove(current);
      } else if (nextUseOfResident.size() >= capacity) {
        K evicted = residentsByNextUse.pollLastEntry().getValue();
        nextUseOfResident.remove(evicted);
      }
      residentsByNextUse.put(nextUse[i], key);
      nextUseOfResident.put(key, nextUse[i]);
    }
    return new Result(hits, trace.size() - hits);
  }

  private static <K> long[] nextUses(List<K> trace) {
    long[] nextUse = new long[trace.size()];
    Map<K, Integer> seenLater = new HashMap<>();
    for (int i = trace.size() - 1; i >= 0; i--) {
      K key = Objects.requireNonNull(trace.get(i), "trace contains null");
      Integer next = seenLater.put(key, i);
      nextUse[i] = next == null ? Long.MAX_VALUE - i : next;
    }
    return nextUse;
  }
}

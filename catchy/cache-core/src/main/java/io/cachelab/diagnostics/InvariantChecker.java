package io.cachelab.diagnostics;

import io.cachelab.Cache;
import io.cachelab.CacheStats;
import io.cachelab.diagnostics.StressReport.InvariantResult;
import io.cachelab.internal.BoundedCache;
import io.cachelab.internal.SegmentedCache;
import java.util.List;

/**
 * The five invariants a stress run must keep (SPEC 5). Each check returns a result with a
 * plain-language detail, whether it passed or failed.
 *
 * <p>Counter checks run only after all worker threads joined, because {@code LongAdder} sums are
 * not atomic snapshots while updates are in flight.
 *
 * <p>Thread-safety: stateless; call after the workers have finished.
 */
public final class InvariantChecker {

  /** Invariant names, in report order. */
  public static final List<String> NAMES =
      List.of("Size bound", "Accounting", "No phantom values", "Structure intact", "No exceptions");

  private InvariantChecker() {}

  /**
   * Size bound: every sampled {@code size()}, including the final one, is at most the maximum.
   *
   * @param maxSampled the largest size observed by the sampler
   * @param samples how many samples were taken
   * @param finalSize the size after the run
   * @param capacity the maximum size
   * @return the result
   */
  public static InvariantResult sizeBound(
      int maxSampled, int samples, int finalSize, int capacity) {
    int max = Math.max(maxSampled, finalSize);
    return new InvariantResult(
        NAMES.get(0),
        max <= capacity,
        String.format(
            "largest size seen %,d across %,d samples (limit %,d)", max, samples, capacity));
  }

  /**
   * Accounting: hits plus misses equals the gets the workers counted.
   *
   * @param stats the cache statistics after the join
   * @param gets the gets counted by the workers
   * @return the result
   */
  public static InvariantResult accounting(CacheStats stats, long gets) {
    long recorded = stats.hitCount() + stats.missCount();
    return new InvariantResult(
        NAMES.get(1),
        recorded == gets,
        String.format(
            "hits %,d + misses %,d = %,d; workers made %,d gets",
            stats.hitCount(), stats.missCount(), recorded, gets));
  }

  /**
   * No phantom values: every value returned by {@code get(k)} was written for key {@code k}.
   *
   * @param phantoms values that belonged to another key
   * @param checked values checked
   * @param example one offending value, or null
   * @return the result
   */
  public static InvariantResult noPhantoms(long phantoms, long checked, String example) {
    String detail =
        phantoms == 0
            ? String.format("all %,d returned values belonged to their key", checked)
            : String.format(
                "%,d of %,d values belonged to another key, e.g. %s", phantoms, checked, example);
    return new InvariantResult(NAMES.get(2), phantoms == 0, detail);
  }

  /**
   * Structure intact: the eviction structures of every segment pass their own checks.
   *
   * @param cache the stressed cache
   * @return the result
   */
  public static InvariantResult structure(Cache<?, ?> cache) {
    try {
      int segments;
      if (cache instanceof SegmentedCache<?, ?> segmented) {
        segmented.checkInvariants();
        segments = segmented.segments().size();
      } else if (cache instanceof BoundedCache<?, ?> single) {
        single.checkInvariants();
        segments = 1;
      } else {
        return new InvariantResult(NAMES.get(3), false, "unknown cache implementation");
      }
      return new InvariantResult(
          NAMES.get(3),
          true,
          "links, frequency buckets and sizes are consistent in all "
              + segments
              + (segments == 1 ? " segment" : " segments"));
    } catch (IllegalStateException e) {
      return new InvariantResult(NAMES.get(3), false, e.getMessage());
    }
  }

  /**
   * No exceptions: no worker thread threw.
   *
   * @param count the number of exceptions caught
   * @param first a summary of the first one, or null
   * @return the result
   */
  public static InvariantResult noExceptions(int count, String first) {
    return new InvariantResult(
        NAMES.get(4),
        count == 0,
        count == 0 ? "no worker thread threw" : count + " exceptions, first: " + first);
  }
}

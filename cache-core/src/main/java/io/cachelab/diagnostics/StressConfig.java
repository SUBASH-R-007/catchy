package io.cachelab.diagnostics;

import io.cachelab.PolicyType;
import java.time.Duration;
import java.util.Objects;

/**
 * Parameters of one stress run (SPEC 5).
 *
 * <p>Immutable and thread-safe.
 *
 * @param impl which cache engine to stress
 * @param threads worker threads, 1 to 64
 * @param duration how long the workers run, at most 10 s
 * @param keySpace number of distinct keys, at least 1
 * @param readRatio probability that an operation is a get, 0 to 1
 * @param capacity the cache's maximum size, at least 1
 * @param policy the eviction policy
 * @param seed base seed; worker {@code t} uses {@code seed + t}
 */
public record StressConfig(
    Impl impl,
    int threads,
    Duration duration,
    int keySpace,
    double readRatio,
    int capacity,
    PolicyType policy,
    long seed) {

  /** The cache engine under test. */
  public enum Impl {
    /** One lock around the whole cache ({@code concurrencyLevel = 1}). */
    SINGLE_LOCK,
    /** Sixteen independently locked segments ({@code concurrencyLevel = 16}). */
    SEGMENTED
  }

  /** Segment count used for {@link Impl#SEGMENTED}. */
  public static final int SEGMENTS = 16;

  /** Longest allowed run. */
  public static final Duration MAX_DURATION = Duration.ofSeconds(10);

  /**
   * Validates every component.
   *
   * @throws NullPointerException if {@code impl}, {@code duration} or {@code policy} is null
   * @throws IllegalArgumentException if a value is out of range
   */
  public StressConfig {
    Objects.requireNonNull(impl, "impl");
    Objects.requireNonNull(duration, "duration");
    Objects.requireNonNull(policy, "policy");
    check(threads >= 1 && threads <= 64, "threads must be between 1 and 64, but was " + threads);
    check(
        !duration.isNegative() && !duration.isZero() && duration.compareTo(MAX_DURATION) <= 0,
        "duration must be positive and at most 10 s, but was " + duration);
    check(keySpace >= 1, "keySpace must be at least 1, but was " + keySpace);
    check(readRatio >= 0 && readRatio <= 1, "readRatio must be between 0 and 1: " + readRatio);
    check(capacity >= 1, "capacity must be at least 1, but was " + capacity);
    check(
        impl != Impl.SEGMENTED || capacity >= SEGMENTS,
        "a segmented stress run needs capacity >= " + SEGMENTS + ", but was " + capacity);
  }

  /**
   * The gate configuration: 32 threads for 5 s over 10,000 keys, 80 % reads, capacity 1,000.
   *
   * @param impl the engine
   * @param seed the base seed
   * @return the configuration
   */
  public static StressConfig standard(Impl impl, long seed) {
    return new StressConfig(
        impl, 32, Duration.ofSeconds(5), 10_000, 0.8, 1_000, PolicyType.LRU, seed);
  }

  private static void check(boolean ok, String message) {
    if (!ok) {
      throw new IllegalArgumentException(message);
    }
  }
}

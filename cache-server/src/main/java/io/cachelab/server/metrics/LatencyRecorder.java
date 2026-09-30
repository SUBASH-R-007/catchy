package io.cachelab.server.metrics;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Latency histogram of one cache's {@code get} calls (SPEC 8.1): fixed log-scale buckets from
 * {@value #MIN_NANOS} ns (0.1 µs) to {@value #MAX_NANOS} ns (100 ms), {@value #BUCKETS_PER_DECADE}
 * buckets per decade (each about 12% wide). Values outside the range land in the first or last
 * bucket.
 *
 * <p>The window is a ring of {@value #SLICES} slices. The metrics publisher calls {@link #rotate()}
 * once per 500 ms tick, so percentiles cover the last 10 s. A reported percentile is the geometric
 * midpoint of the bucket holding it (error at most about 6%).
 *
 * <p>Thread-safe. {@link #record} is lock-free, allocation-free and O(1); {@link #rotate} and the
 * percentile reads are O(buckets x slices) and meant for the single metrics thread.
 */
public final class LatencyRecorder {

  /** Lower bound of the bucket range, in nanoseconds. */
  public static final long MIN_NANOS = 100;

  /** Upper bound of the bucket range, in nanoseconds. */
  public static final long MAX_NANOS = 100_000_000;

  /** Buckets per factor of 10. */
  public static final int BUCKETS_PER_DECADE = 20;

  /** Slices in the ring: 20 ticks of 500 ms = 10 s. */
  public static final int SLICES = 20;

  static final int BUCKETS = 6 * BUCKETS_PER_DECADE; // 1e2 .. 1e8 ns

  private final AtomicLongArray[] slices = new AtomicLongArray[SLICES];
  private volatile int current;

  /** Creates an empty recorder. */
  public LatencyRecorder() {
    for (int i = 0; i < SLICES; i++) {
      slices[i] = new AtomicLongArray(BUCKETS);
    }
  }

  /**
   * Records one measurement into the current slice.
   *
   * @param nanos the duration in nanoseconds; negative values count as 0
   */
  public void record(long nanos) {
    slices[current].incrementAndGet(bucketOf(nanos));
  }

  /** Starts a new slice, dropping the oldest one. Call once per metrics tick. */
  public void rotate() {
    int next = (current + 1) % SLICES;
    AtomicLongArray slice = slices[next];
    for (int b = 0; b < BUCKETS; b++) {
      slice.set(b, 0);
    }
    current = next;
  }

  /**
   * Returns the median over the window.
   *
   * @return microseconds, or 0 without samples
   */
  public double p50Micros() {
    return percentileMicros(0.50);
  }

  /**
   * Returns the 99th percentile over the window.
   *
   * @return microseconds, or 0 without samples
   */
  public double p99Micros() {
    return percentileMicros(0.99);
  }

  /**
   * Returns a percentile over the window.
   *
   * @param q the quantile, in (0, 1]
   * @return microseconds (the holding bucket's geometric midpoint), or 0 without samples
   */
  public double percentileMicros(double q) {
    long[] counts = new long[BUCKETS];
    long total = 0;
    for (AtomicLongArray slice : slices) {
      for (int b = 0; b < BUCKETS; b++) {
        long c = slice.get(b);
        counts[b] += c;
        total += c;
      }
    }
    if (total == 0) {
      return 0.0;
    }
    long rank = Math.max(1, (long) Math.ceil(q * total));
    long seen = 0;
    for (int b = 0; b < BUCKETS; b++) {
      seen += counts[b];
      if (seen >= rank) {
        return midpointMicros(b);
      }
    }
    return midpointMicros(BUCKETS - 1);
  }

  static int bucketOf(long nanos) {
    if (nanos <= MIN_NANOS) {
      return 0;
    }
    int b = (int) (Math.log10((double) nanos / MIN_NANOS) * BUCKETS_PER_DECADE);
    return Math.min(b, BUCKETS - 1);
  }

  static double midpointMicros(int bucket) {
    return MIN_NANOS / 1000.0 * Math.pow(10, (bucket + 0.5) / BUCKETS_PER_DECADE);
  }
}

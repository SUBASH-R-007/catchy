package io.cachelab.server.metrics;

/**
 * Sliding hit rate over the last {@code n} ticks, kept as a ring of per-tick deltas. With the
 * default of {@value #DEFAULT_TICKS} ticks of 500 ms this is {@code hitRateWindow10s}.
 *
 * <p>Not thread-safe: owned by the single metrics thread. {@link #record} and {@link #rate()} are
 * O(1).
 */
public final class HitRateWindow {

  /** Ticks in a 10 s window at one tick per 500 ms. */
  public static final int DEFAULT_TICKS = 20;

  private final long[] hits;
  private final long[] requests;
  private int next;
  private long sumHits;
  private long sumRequests;

  /** Creates a window of {@value #DEFAULT_TICKS} ticks. */
  public HitRateWindow() {
    this(DEFAULT_TICKS);
  }

  /**
   * Creates a window of {@code ticks} ticks.
   *
   * @param ticks window length in ticks
   * @throws IllegalArgumentException if {@code ticks < 1}
   */
  public HitRateWindow(int ticks) {
    if (ticks < 1) {
      throw new IllegalArgumentException("ticks must be >= 1: " + ticks);
    }
    this.hits = new long[ticks];
    this.requests = new long[ticks];
  }

  /**
   * Records one tick, dropping the oldest once the window is full.
   *
   * @param hitsDelta hits during the tick
   * @param missesDelta misses during the tick
   * @throws IllegalArgumentException if a delta is negative
   */
  public void record(long hitsDelta, long missesDelta) {
    if (hitsDelta < 0 || missesDelta < 0) {
      throw new IllegalArgumentException("deltas must be >= 0: " + hitsDelta + ", " + missesDelta);
    }
    sumHits += hitsDelta - hits[next];
    sumRequests += hitsDelta + missesDelta - requests[next];
    hits[next] = hitsDelta;
    requests[next] = hitsDelta + missesDelta;
    next = (next + 1) % hits.length;
  }

  /**
   * Returns the hit rate over the window.
   *
   * @return hits / requests over the recorded ticks, in [0, 1]; 0 when there were no requests
   */
  public double rate() {
    return sumRequests == 0 ? 0.0 : (double) sumHits / sumRequests;
  }
}

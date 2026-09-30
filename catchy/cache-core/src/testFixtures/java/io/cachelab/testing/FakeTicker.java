package io.cachelab.testing;

import io.cachelab.Ticker;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A manually advanced {@link Ticker} for deterministic TTL tests: time moves only when the test
 * says so, so no test needs {@code Thread.sleep}.
 *
 * <p>Thread-safety: safe to read and advance from any thread.
 */
public final class FakeTicker implements Ticker {

  private final AtomicLong nanos;

  /** Creates a ticker reading 0. */
  public FakeTicker() {
    this(0);
  }

  /**
   * Creates a ticker with the given starting reading, which may be negative (as {@link
   * System#nanoTime()} can be).
   *
   * @param startNanos the initial reading in nanoseconds
   */
  public FakeTicker(long startNanos) {
    this.nanos = new AtomicLong(startNanos);
  }

  @Override
  public long read() {
    return nanos.get();
  }

  /**
   * Moves time forward.
   *
   * @param duration how far to advance; must not be null or negative
   * @return this ticker, for chaining
   * @throws NullPointerException if {@code duration} is null
   * @throws IllegalArgumentException if {@code duration} is negative
   */
  public FakeTicker advance(Duration duration) {
    Objects.requireNonNull(duration, "duration");
    if (duration.isNegative()) {
      throw new IllegalArgumentException("a ticker cannot go backwards: " + duration);
    }
    nanos.addAndGet(duration.toNanos());
    return this;
  }

  /**
   * Moves time forward by a number of milliseconds.
   *
   * @param millis how far to advance; must not be negative
   * @return this ticker, for chaining
   * @throws IllegalArgumentException if {@code millis} is negative
   */
  public FakeTicker advanceMillis(long millis) {
    return advance(Duration.ofMillis(millis));
  }
}

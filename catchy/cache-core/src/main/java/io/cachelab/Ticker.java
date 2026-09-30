package io.cachelab;

/**
 * A source of monotonic time in nanoseconds, used for TTL decisions. Tests substitute a manual
 * ticker to control time without sleeping.
 *
 * <p>Only differences between two readings are meaningful; the origin is arbitrary and readings may
 * be negative.
 *
 * <p>Thread-safety: implementations must be safe to call from any thread.
 */
@FunctionalInterface
public interface Ticker {

  /**
   * Returns the current reading in nanoseconds. Complexity: O(1).
   *
   * @return nanoseconds since an arbitrary, fixed origin
   */
  long read();

  /**
   * Returns a ticker backed by {@link System#nanoTime()}.
   *
   * @return the shared system ticker, never null
   */
  static Ticker system() {
    return SystemTicker.INSTANCE;
  }
}

/** The {@link System#nanoTime()} ticker. */
enum SystemTicker implements Ticker {
  INSTANCE;

  @Override
  public long read() {
    return System.nanoTime();
  }
}

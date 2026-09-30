package io.cachelab.server.metrics;

/**
 * Produces the metrics stream one tick at a time. {@link MetricsPublisher} calls {@link #next()}
 * every 500 ms from its single thread.
 *
 * <p>Implementations need not be thread-safe beyond being called from one thread at a time.
 */
public interface MetricsSource {

  /**
   * Produces the next tick; each call covers the 500 ms since the previous one.
   *
   * @return the snapshot for this tick; never {@code null}
   */
  MetricsSnapshot next();
}

package io.cachelab.internal;

import io.cachelab.CacheStats;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free statistics counters ({@link LongAdder}), recorded outside the cache lock.
 *
 * <p>Thread-safety: safe for concurrent use. A {@link #snapshot()} taken while other threads record
 * is not atomic across counters; checks that compare counters must run after the threads join.
 */
final class StatsRecorder {

  private final LongAdder hits = new LongAdder();
  private final LongAdder misses = new LongAdder();
  private final LongAdder evictions = new LongAdder();
  private final LongAdder expirations = new LongAdder();
  private final LongAdder loadSuccess = new LongAdder();
  private final LongAdder loadFailure = new LongAdder();
  private final LongAdder totalLoadNanos = new LongAdder();
  private final LongAdder puts = new LongAdder();

  void hit() {
    hits.increment();
  }

  void miss() {
    misses.increment();
  }

  void eviction() {
    evictions.increment();
  }

  void expiration() {
    expirations.increment();
  }

  void put() {
    puts.increment();
  }

  void loadSuccess(long nanos) {
    loadSuccess.increment();
    totalLoadNanos.add(Math.max(0, nanos));
  }

  void loadFailure(long nanos) {
    loadFailure.increment();
    totalLoadNanos.add(Math.max(0, nanos));
  }

  CacheStats snapshot() {
    return new CacheStats(
        hits.sum(),
        misses.sum(),
        evictions.sum(),
        expirations.sum(),
        loadSuccess.sum(),
        loadFailure.sum(),
        totalLoadNanos.sum(),
        puts.sum());
  }
}

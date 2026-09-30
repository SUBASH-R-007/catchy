package io.cachelab.internal;

import io.cachelab.AccessObserver;
import io.cachelab.PolicyType;
import io.cachelab.RemovalListener;
import io.cachelab.Ticker;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * The validated configuration a {@code CacheBuilder} hands to the engine. Internal API.
 *
 * <p>Thread-safety: immutable (the observer list is copied).
 *
 * @param <K> the key type
 * @param <V> the value type
 * @param name the cache name
 * @param maximumSize the capacity, at least 1
 * @param policy the initial eviction policy
 * @param defaultTtlNanos the default TTL in nanoseconds, or {@link #NO_TTL}
 * @param concurrencyLevel the number of segments, a power of two
 * @param removalListener the removal listener, or null
 * @param removalExecutor the executor for removal notifications, or null for the caller thread
 * @param accessObservers observers notified after every lookup
 * @param ticker the time source for TTL decisions
 * @param sweepIntervalNanos the interval between expiry sweeps
 * @param decayIntervalNanos the interval between LFU_DECAY decays
 */
public record CacheSettings<K, V>(
    String name,
    int maximumSize,
    PolicyType policy,
    long defaultTtlNanos,
    int concurrencyLevel,
    RemovalListener<? super K, ? super V> removalListener,
    Executor removalExecutor,
    List<AccessObserver<? super K>> accessObservers,
    Ticker ticker,
    long sweepIntervalNanos,
    long decayIntervalNanos) {

  /** Marker for "no default TTL": entries put without a TTL never expire. */
  public static final long NO_TTL = -1;

  /**
   * Validates required components and copies the observer list.
   *
   * @throws NullPointerException if a required component is null
   * @throws IllegalArgumentException if {@code maximumSize} is less than 1
   */
  public CacheSettings {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(ticker, "ticker");
    accessObservers = List.copyOf(accessObservers);
    if (maximumSize < 1) {
      throw new IllegalArgumentException("maximumSize must be at least 1, but was " + maximumSize);
    }
  }

  /**
   * Returns a copy with another name and capacity, used to configure the segments of a segmented
   * cache.
   *
   * @param segmentName the segment's name
   * @param segmentSize the segment's capacity
   * @return the segment settings
   */
  public CacheSettings<K, V> forSegment(String segmentName, int segmentSize) {
    return new CacheSettings<>(
        segmentName,
        segmentSize,
        policy,
        defaultTtlNanos,
        1,
        removalListener,
        removalExecutor,
        accessObservers,
        ticker,
        sweepIntervalNanos,
        decayIntervalNanos);
  }
}

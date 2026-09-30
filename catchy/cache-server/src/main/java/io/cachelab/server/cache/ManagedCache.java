package io.cachelab.server.cache;

import io.cachelab.Cache;
import io.cachelab.CacheStats;
import io.cachelab.PolicyType;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A registry entry: one live cache with its configuration and a statistics baseline.
 *
 * <p>{@code LongAdder} counters cannot be zeroed safely, so {@link #resetStats()} stores a baseline
 * and {@link #stats()} reports {@code current - baseline} (SPEC 8.2).
 *
 * <p>Thread-safe.
 */
public final class ManagedCache {

  private final Cache<String, String> cache;
  private final AtomicReference<CacheConfig> config;
  private final AtomicReference<CacheStats> baseline = new AtomicReference<>(CacheStats.empty());

  /**
   * Wraps a cache.
   *
   * @param config its configuration
   * @param cache the cache
   */
  public ManagedCache(CacheConfig config, Cache<String, String> cache) {
    this.config = new AtomicReference<>(Objects.requireNonNull(config, "config"));
    this.cache = Objects.requireNonNull(cache, "cache");
  }

  /**
   * Returns the cache.
   *
   * @return the live cache
   */
  public Cache<String, String> cache() {
    return cache;
  }

  /**
   * Returns the configuration, reflecting the current policy.
   *
   * @return the configuration
   */
  public CacheConfig config() {
    return config.get();
  }

  /**
   * Returns the cache's name.
   *
   * @return the name
   */
  public String name() {
    return cache.name();
  }

  /**
   * Returns the statistics since creation or the last reset.
   *
   * @return current minus baseline
   */
  public CacheStats stats() {
    return cache.stats().minus(baseline.get());
  }

  /** Makes {@link #stats()} start again from zero. */
  public void resetStats() {
    baseline.set(cache.stats());
  }

  /**
   * Switches the eviction policy and records it in the configuration.
   *
   * @param policy the new policy
   * @return the updated configuration
   */
  public CacheConfig switchPolicy(PolicyType policy) {
    cache.switchPolicy(policy);
    return config.updateAndGet(c -> c.withPolicy(policy));
  }
}

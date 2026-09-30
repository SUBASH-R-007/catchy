package io.cachelab.server.api;

import io.cachelab.CacheStats;
import io.cachelab.EntryView;
import io.cachelab.PolicyType;
import io.cachelab.server.cache.CacheConfig;
import io.cachelab.server.cache.ManagedCache;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Optional;

/** Request and response bodies of the cache API (schemas in {@code docs/api/openapi.yaml}). */
public final class CacheDtos {

  private CacheDtos() {}

  /**
   * A cache's configuration plus live statistics since creation or the last reset.
   *
   * @param name cache name
   * @param policy eviction policy
   * @param capacity maximum entries
   * @param defaultTtlMs default TTL, or null
   * @param concurrencyLevel segments
   * @param group comparison group
   * @param size current entries
   * @param hits lookups that found a live value
   * @param misses lookups that did not
   * @param hitRate hits / lookups (0 when none)
   * @param missRate misses / lookups (0 when none)
   * @param evictions entries evicted by the policy
   * @param expirations entries removed by TTL
   * @param puts put operations
   */
  public record CacheInfo(
      String name,
      PolicyType policy,
      int capacity,
      Long defaultTtlMs,
      int concurrencyLevel,
      String group,
      int size,
      long hits,
      long misses,
      double hitRate,
      double missRate,
      long evictions,
      long expirations,
      long puts) {

    static CacheInfo of(ManagedCache managed) {
      CacheConfig c = managed.config();
      CacheStats s = managed.stats();
      return new CacheInfo(
          c.name(),
          c.policy(),
          c.capacity(),
          c.defaultTtlMs(),
          c.concurrencyLevel(),
          c.group(),
          managed.cache().size(),
          s.hitCount(),
          s.missCount(),
          s.hitRate(),
          s.missRate(),
          s.evictionCount(),
          s.expirationCount(),
          s.putCount());
    }
  }

  /**
   * One entry as listed by {@code GET /entries}.
   *
   * @param key the key
   * @param frequency the policy's access count (0 under LRU)
   * @param ttlRemainingMs milliseconds until expiry, or null if it never expires
   */
  public record EntryDto(String key, long frequency, Long ttlRemainingMs) {
    static EntryDto of(EntryView<String> view) {
      return new EntryDto(view.key(), view.frequency(), millis(view.ttlRemaining()));
    }
  }

  /**
   * Result of {@code GET /entries/{key}}.
   *
   * @param hit whether a live value was found
   * @param value the value, or null on a miss
   * @param ttlRemainingMs milliseconds until expiry, or null
   */
  public record GetResult(boolean hit, String value, Long ttlRemainingMs) {}

  /**
   * Body of {@code PUT /entries/{key}}.
   *
   * @param value the value to store
   * @param ttlMs per-entry TTL in milliseconds, or null for the cache's default
   */
  public record PutRequest(@NotNull @Size(max = 10_000) String value, @Min(1) Long ttlMs) {}

  /**
   * Result of {@code DELETE /entries/{key}}.
   *
   * @param removed whether a live entry was removed
   */
  public record RemoveResult(boolean removed) {}

  /**
   * Body of {@code POST /policy}.
   *
   * @param policy the policy to switch to
   */
  public record PolicyRequest(@NotNull PolicyType policy) {}

  static Long millis(Optional<Duration> duration) {
    return duration.map(Duration::toMillis).orElse(null);
  }
}

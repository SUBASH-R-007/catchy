package io.cachelab.server.cache;

import io.cachelab.PolicyType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Configuration of one managed cache (SPEC 8.1): the request body of {@code POST /api/caches} and
 * the response of cache operations. Omitted optional fields get defaults: policy {@code LRU},
 * concurrency level 1, group {@code "playground"}, no default TTL.
 *
 * <p>Immutable and thread-safe.
 *
 * @param name unique cache name: 1–40 letters, digits, {@code _} or {@code -}
 * @param policy eviction policy
 * @param capacity maximum number of entries, 1 to 1,000,000
 * @param defaultTtlMs TTL applied to puts without their own TTL, or {@code null} for none
 * @param concurrencyLevel number of segments: a power of two, at most 64 and at most the capacity
 * @param group comparison group that shares a workload
 */
public record CacheConfig(
    @NotNull @Pattern(regexp = NAME_PATTERN, message = "must be 1-40 letters, digits, _ or -")
        String name,
    PolicyType policy,
    @Min(1) @Max(1_000_000) int capacity,
    @Min(1) Long defaultTtlMs,
    @Min(1) @Max(64) Integer concurrencyLevel,
    @Pattern(regexp = NAME_PATTERN, message = "must be 1-40 letters, digits, _ or -")
        String group) {

  /** Allowed characters and length of cache and group names. */
  public static final String NAME_PATTERN = "^[A-Za-z0-9_-]{1,40}$";

  /** Group used when a request does not name one. */
  public static final String DEFAULT_GROUP = "playground";

  /** Fills in the documented defaults for omitted optional fields. */
  public CacheConfig {
    policy = policy == null ? PolicyType.LRU : policy;
    concurrencyLevel = concurrencyLevel == null ? 1 : concurrencyLevel;
    group = group == null ? DEFAULT_GROUP : group;
  }

  /**
   * Returns a copy with another policy.
   *
   * @param newPolicy the policy
   * @return the updated configuration
   */
  public CacheConfig withPolicy(PolicyType newPolicy) {
    return new CacheConfig(name, newPolicy, capacity, defaultTtlMs, concurrencyLevel, group);
  }
}

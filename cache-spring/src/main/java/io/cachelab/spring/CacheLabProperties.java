package io.cachelab.spring;

import io.cachelab.PolicyType;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code cachelab.*} configuration (SPEC 7):
 *
 * <pre>
 * cachelab.enabled=true
 * cachelab.caches.formulary.maximum-size=10000
 * cachelab.caches.formulary.policy=lfu          # lru | lfu | lfu_decay
 * cachelab.caches.formulary.default-ttl=10m
 * cachelab.caches.formulary.concurrency-level=16
 * </pre>
 *
 * <p>Caches named by {@code @Cacheable} but not configured here get {@link Spec#Spec() defaults}.
 *
 * <p>Mutable JavaBean for Spring binding; not thread-safe while binding, read-only afterwards.
 */
@ConfigurationProperties(prefix = "cachelab")
public class CacheLabProperties {

  private boolean enabled = true;
  private Map<String, Spec> caches = new LinkedHashMap<>();

  /** Creates empty properties (Spring binds the values). */
  public CacheLabProperties() {}

  /**
   * Returns whether the auto-configuration is active.
   *
   * @return true unless {@code cachelab.enabled=false}
   */
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Sets whether the auto-configuration is active.
   *
   * @param enabled the flag
   */
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  /**
   * Returns the per-cache settings, by cache name.
   *
   * @return the mutable map
   */
  public Map<String, Spec> getCaches() {
    return caches;
  }

  /**
   * Replaces the per-cache settings.
   *
   * @param caches cache name to settings
   */
  public void setCaches(Map<String, Spec> caches) {
    this.caches = caches;
  }

  /** Settings of one cache. Defaults: 10,000 entries, LRU, no TTL, one segment. */
  public static class Spec {
    private int maximumSize = 10_000;
    private PolicyType policy = PolicyType.LRU;
    private Duration defaultTtl;
    private int concurrencyLevel = 1;

    /** Creates a spec with the defaults. */
    public Spec() {}

    /**
     * Returns the capacity.
     *
     * @return the maximum number of entries
     */
    public int getMaximumSize() {
      return maximumSize;
    }

    /**
     * Sets the capacity.
     *
     * @param maximumSize at least 1
     */
    public void setMaximumSize(int maximumSize) {
      this.maximumSize = maximumSize;
    }

    /**
     * Returns the eviction policy.
     *
     * @return the policy
     */
    public PolicyType getPolicy() {
      return policy;
    }

    /**
     * Sets the eviction policy ({@code lru}, {@code lfu} or {@code lfu_decay}).
     *
     * @param policy the policy
     */
    public void setPolicy(PolicyType policy) {
      this.policy = policy;
    }

    /**
     * Returns the default TTL.
     *
     * @return the TTL, or null for none
     */
    public Duration getDefaultTtl() {
      return defaultTtl;
    }

    /**
     * Sets the default TTL, e.g. {@code 10m}.
     *
     * @param defaultTtl the TTL, or null for none
     */
    public void setDefaultTtl(Duration defaultTtl) {
      this.defaultTtl = defaultTtl;
    }

    /**
     * Returns the number of segments.
     *
     * @return a power of two
     */
    public int getConcurrencyLevel() {
      return concurrencyLevel;
    }

    /**
     * Sets the number of segments.
     *
     * @param concurrencyLevel a power of two, at most the maximum size
     */
    public void setConcurrencyLevel(int concurrencyLevel) {
      this.concurrencyLevel = concurrencyLevel;
    }
  }
}

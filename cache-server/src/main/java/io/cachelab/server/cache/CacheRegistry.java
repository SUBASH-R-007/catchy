package io.cachelab.server.cache;

import io.cachelab.Cache;
import io.cachelab.CacheBuilder;
import io.cachelab.PolicyType;
import io.cachelab.Ticker;
import io.cachelab.server.metrics.RemovalEvent;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The server's named caches (SPEC 8.1), grouped into comparison groups that share a workload.
 *
 * <p>Boot state: group {@code demo} with {@code lru-A} (LRU) and {@code lfu-A} (LFU), capacity
 * 1,000, no TTL. Every cache reports its removals to the {@link EventRing}.
 *
 * <p>Thread-safe: create and delete are atomic per name.
 */
@Component
public final class CacheRegistry {

  /** The default comparison group. */
  public static final String DEMO_GROUP = "demo";

  private final Map<String, ManagedCache> caches = new ConcurrentHashMap<>();
  private final EventRing events;
  private final Ticker ticker;
  private final Clock clock;

  /**
   * Creates the registry with the default demo group.
   *
   * @param events receives every removal
   * @param ticker time source for TTL decisions (a fake one in tests)
   * @param clock wall clock for event timestamps
   */
  public CacheRegistry(EventRing events, Ticker ticker, Clock clock) {
    this.events = Objects.requireNonNull(events, "events");
    this.ticker = Objects.requireNonNull(ticker, "ticker");
    this.clock = Objects.requireNonNull(clock, "clock");
    create(new CacheConfig("lru-A", PolicyType.LRU, 1_000, null, 1, DEMO_GROUP));
    create(new CacheConfig("lfu-A", PolicyType.LFU, 1_000, null, 1, DEMO_GROUP));
  }

  /**
   * Creates and registers a cache.
   *
   * @param config the configuration (validated by the builder too)
   * @return the registered cache
   * @throws CacheAlreadyExistsException if the name is taken
   * @throws IllegalArgumentException or {@link IllegalStateException} if the builder rejects the
   *     configuration
   */
  public ManagedCache create(CacheConfig config) {
    Objects.requireNonNull(config, "config");
    ManagedCache[] created = new ManagedCache[1];
    caches.compute(
        config.name(),
        (name, existing) -> {
          if (existing != null) {
            throw new CacheAlreadyExistsException(name);
          }
          created[0] = new ManagedCache(config, build(config));
          return created[0];
        });
    return created[0];
  }

  private Cache<String, String> build(CacheConfig config) {
    String name = config.name();
    CacheBuilder<String, String> builder =
        CacheBuilder.<String, String>newBuilder()
            .name(name)
            .maximumSize(config.capacity())
            .evictionPolicy(config.policy())
            .concurrencyLevel(config.concurrencyLevel())
            .ticker(ticker)
            .removalListener(
                (key, value, cause) ->
                    events.add(new RemovalEvent(clock.millis(), name, key, cause)));
    if (config.defaultTtlMs() != null) {
      builder.defaultTtl(Duration.ofMillis(config.defaultTtlMs()));
    }
    return builder.build();
  }

  /**
   * Returns the cache with the given name.
   *
   * @param name the name
   * @return the cache
   * @throws CacheNotFoundException if there is none
   */
  public ManagedCache get(String name) {
    ManagedCache cache = caches.get(name);
    if (cache == null) {
      throw new CacheNotFoundException(name);
    }
    return cache;
  }

  /**
   * Removes a cache and stops its sweeper.
   *
   * @param name the name
   * @throws CacheNotFoundException if there is none
   */
  public void delete(String name) {
    ManagedCache removed = caches.remove(name);
    if (removed == null) {
      throw new CacheNotFoundException(name);
    }
    removed.cache().close();
  }

  /**
   * Returns every cache, sorted by group and then name.
   *
   * @return the caches
   */
  public List<ManagedCache> list() {
    return caches.values().stream()
        .sorted(
            Comparator.comparing((ManagedCache c) -> c.config().group())
                .thenComparing(ManagedCache::name))
        .toList();
  }

  /**
   * Returns the caches of every group, groups and members sorted by name.
   *
   * @return group name to member caches
   */
  public Map<String, List<ManagedCache>> groups() {
    Map<String, List<ManagedCache>> groups = new LinkedHashMap<>();
    for (ManagedCache cache : list()) {
      groups.computeIfAbsent(cache.config().group(), g -> new ArrayList<>()).add(cache);
    }
    return groups;
  }

  /** Stops every cache's sweeper at shutdown. */
  @PreDestroy
  public void closeAll() {
    caches.values().forEach(c -> c.cache().close());
  }
}

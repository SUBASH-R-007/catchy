package io.cachelab.server.cache;

import io.cachelab.AccessObserver;
import io.cachelab.Cache;
import io.cachelab.CacheBuilder;
import io.cachelab.PolicyType;
import io.cachelab.Ticker;
import io.cachelab.advisor.Recommendation;
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
 * 1,000, no TTL. Every cache reports its removals to the {@link EventRing}. Each group is a {@link
 * CacheGroup} holding a key recorder and a policy advisor attached to its primary cache; every
 * cache is built with a forwarding observer that feeds the group only while it is the primary.
 *
 * <p>Thread-safe: create and delete are serialized; lookups of caches and groups are lock-free.
 */
@Component
public final class CacheRegistry {

  /** The default comparison group. */
  public static final String DEMO_GROUP = "demo";

  private final Map<String, ManagedCache> caches = new ConcurrentHashMap<>();
  private final Map<String, CacheGroup> groups = new ConcurrentHashMap<>();
  private final EventRing events;
  private final Ticker ticker;
  private final Clock clock;

  /**
   * Creates the registry with the default demo group.
   *
   * @param events receives every removal
   * @param ticker time source for TTL decisions (a fake one in tests)
   * @param clock wall clock for event timestamps and the advisors' windows
   */
  public CacheRegistry(EventRing events, Ticker ticker, Clock clock) {
    this.events = Objects.requireNonNull(events, "events");
    this.ticker = Objects.requireNonNull(ticker, "ticker");
    this.clock = Objects.requireNonNull(clock, "clock");
    create(new CacheConfig("lru-A", PolicyType.LRU, 1_000, null, 1, DEMO_GROUP));
    create(new CacheConfig("lfu-A", PolicyType.LFU, 1_000, null, 1, DEMO_GROUP));
  }

  /**
   * Creates and registers a cache; it joins (or starts) its group.
   *
   * @param config the configuration (validated by the builder too)
   * @return the registered cache
   * @throws CacheAlreadyExistsException if the name is taken
   * @throws IllegalArgumentException or {@link IllegalStateException} if the builder rejects the
   *     configuration
   */
  public synchronized ManagedCache create(CacheConfig config) {
    Objects.requireNonNull(config, "config");
    if (caches.containsKey(config.name())) {
      throw new CacheAlreadyExistsException(config.name());
    }
    CacheGroup group =
        groups.getOrDefault(config.group(), new CacheGroup(config.group(), clock::millis));
    GroupForwarder forwarder = new GroupForwarder(group);
    ManagedCache created = new ManagedCache(config, build(config, forwarder));
    forwarder.owner = created;
    caches.put(config.name(), created);
    group.add(created);
    groups.put(group.name(), group);
    return created;
  }

  private Cache<String, String> build(CacheConfig config, AccessObserver<String> observer) {
    String name = config.name();
    CacheBuilder<String, String> builder =
        CacheBuilder.<String, String>newBuilder()
            .name(name)
            .maximumSize(config.capacity())
            .evictionPolicy(config.policy())
            .concurrencyLevel(config.concurrencyLevel())
            .ticker(ticker)
            .accessObserver(observer)
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
   * Removes a cache and stops its sweeper. If it was its group's primary, the group's advisor and
   * recorder restart on the next member; an emptied group disappears.
   *
   * @param name the name
   * @throws CacheNotFoundException if there is none
   */
  public synchronized void delete(String name) {
    ManagedCache removed = caches.remove(name);
    if (removed == null) {
      throw new CacheNotFoundException(name);
    }
    CacheGroup group = groups.get(removed.config().group());
    if (group != null) {
      group.remove(removed);
      if (group.isEmpty()) {
        groups.remove(group.name());
      }
    }
    removed.cache().close();
  }

  /**
   * Switches a cache's eviction policy; if it is its group's primary, the advisor's windows are
   * reset so the next recommendation rests on fresh evidence (SPEC 6.3).
   *
   * @param name the cache
   * @param policy the new policy
   * @return the updated configuration
   * @throws CacheNotFoundException if there is no such cache
   */
  public CacheConfig switchPolicy(String name, PolicyType policy) {
    ManagedCache cache = get(name);
    PolicyType before = cache.config().policy();
    CacheConfig updated = cache.switchPolicy(policy);
    CacheGroup group = groups.get(updated.group());
    if (group != null && group.primary() == cache && before != policy) {
      group.resetAdvisor();
    }
    return updated;
  }

  /**
   * Applies a group's advisor recommendation: every member whose policy equals the
   * recommendation's {@code current} policy (always including the primary) switches to the
   * recommended one, then the advisor's windows are reset.
   *
   * @param groupName the group
   * @return the policy switched to
   * @throws GroupNotFoundException if there is no such group
   * @throws NoRecommendationException if the advisor has no recommendation
   */
  public synchronized PolicyType applyRecommendation(String groupName) {
    CacheGroup group = group(groupName);
    Recommendation rec =
        group.recommendation().orElseThrow(() -> new NoRecommendationException(groupName));
    ManagedCache primary = group.primary();
    for (ManagedCache member : group.members()) {
      if (member == primary || member.config().policy() == rec.current()) {
        member.switchPolicy(rec.recommended());
      }
    }
    group.resetAdvisor();
    return rec.recommended();
  }

  /**
   * Returns a group's intelligence.
   *
   * @param name the group name
   * @return the group
   * @throws GroupNotFoundException if no cache belongs to it
   */
  public CacheGroup group(String name) {
    CacheGroup group = groups.get(name);
    if (group == null) {
      throw new GroupNotFoundException(name);
    }
    return group;
  }

  /**
   * Returns every group's intelligence, sorted by name.
   *
   * @return the groups
   */
  public List<CacheGroup> groupList() {
    return groups.values().stream().sorted(Comparator.comparing(CacheGroup::name)).toList();
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
    Map<String, List<ManagedCache>> result = new LinkedHashMap<>();
    for (ManagedCache cache : list()) {
      result.computeIfAbsent(cache.config().group(), g -> new ArrayList<>()).add(cache);
    }
    return result;
  }

  /** Stops every cache's sweeper at shutdown. */
  @PreDestroy
  public void closeAll() {
    caches.values().forEach(c -> c.cache().close());
  }

  /** Forwards a cache's lookups to its group, which records them only from the primary. */
  private static final class GroupForwarder implements AccessObserver<String> {
    private final CacheGroup group;
    private volatile ManagedCache owner;

    GroupForwarder(CacheGroup group) {
      this.group = group;
    }

    @Override
    public void onAccess(String key, boolean hit) {
      ManagedCache self = owner;
      if (self != null) {
        group.onAccess(self, key, hit);
      }
    }
  }
}

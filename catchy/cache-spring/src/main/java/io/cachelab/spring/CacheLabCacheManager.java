package io.cachelab.spring;

import io.cachelab.Cache;
import io.cachelab.CacheBuilder;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.cache.CacheManager;

/**
 * A Spring {@link CacheManager} backed by CacheLab. Caches listed in {@code cachelab.caches.*} are
 * created at startup with their settings; any other name used by {@code @Cacheable} is created on
 * first use with the default settings (10,000 entries, LRU, no TTL).
 *
 * <p>Thread-safety: thread-safe; each name maps to exactly one cache. {@link #destroy()} stops
 * every cache's sweeper thread.
 */
public class CacheLabCacheManager implements CacheManager, DisposableBean {

  private final Map<String, CacheLabSpringCache> caches = new ConcurrentHashMap<>();
  private final Map<String, CacheLabProperties.Spec> specs;
  private final List<Consumer<CacheLabSpringCache>> creationListeners =
      new CopyOnWriteArrayList<>();

  /**
   * Creates the manager and the configured caches.
   *
   * @param properties bound {@code cachelab.*} properties
   */
  public CacheLabCacheManager(CacheLabProperties properties) {
    this.specs = Map.copyOf(Objects.requireNonNull(properties, "properties").getCaches());
    specs.forEach((name, spec) -> caches.put(name, new CacheLabSpringCache(build(name, spec))));
  }

  @Override
  public CacheLabSpringCache getCache(String name) {
    CacheLabSpringCache[] created = new CacheLabSpringCache[1];
    CacheLabSpringCache cache =
        caches.computeIfAbsent(
            name,
            n -> {
              created[0] = new CacheLabSpringCache(build(n, specs.get(n)));
              return created[0];
            });
    if (created[0] != null) {
      creationListeners.forEach(l -> l.accept(created[0]));
    }
    return cache;
  }

  private static Cache<Object, Object> build(String name, CacheLabProperties.Spec spec) {
    CacheLabProperties.Spec s = spec == null ? new CacheLabProperties.Spec() : spec;
    CacheBuilder<Object, Object> builder =
        CacheBuilder.newBuilder()
            .name(name)
            .maximumSize(s.getMaximumSize())
            .evictionPolicy(s.getPolicy())
            .concurrencyLevel(s.getConcurrencyLevel());
    if (s.getDefaultTtl() != null) {
      builder.defaultTtl(s.getDefaultTtl());
    }
    return builder.build();
  }

  @Override
  public Collection<String> getCacheNames() {
    return List.copyOf(caches.keySet());
  }

  /**
   * Returns every cache created so far.
   *
   * @return the caches
   */
  public Collection<CacheLabSpringCache> caches() {
    return List.copyOf(caches.values());
  }

  /**
   * Registers a callback for caches created after this call (existing ones are not replayed). Used
   * by the Micrometer binder to meter caches created on first use.
   *
   * @param listener called once per newly created cache
   */
  public void onCacheCreated(Consumer<CacheLabSpringCache> listener) {
    creationListeners.add(Objects.requireNonNull(listener, "listener"));
  }

  @Override
  public void destroy() {
    caches.values().forEach(c -> c.getNativeCache().close());
  }
}

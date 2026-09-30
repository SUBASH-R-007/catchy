package io.cachelab.spring;

import io.cachelab.Cache;
import io.cachelab.CacheStats;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Objects;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

/**
 * Binds every CacheLab cache to Micrometer (SPEC 7), each meter tagged {@code cache=<name>}:
 *
 * <ul>
 *   <li>{@code cache.gets{result=hit|miss}} — lookups
 *   <li>{@code cache.puts} — puts
 *   <li>{@code cache.evictions}, {@code cache.expirations} — removals by cause
 *   <li>{@code cache.size} — current entries
 *   <li>{@code cache.loads{result=success|failure}} — loader invocations
 * </ul>
 *
 * <p>In Prometheus these appear as {@code cache_gets_total}, {@code cache_size} and so on. Caches
 * the manager creates later (on first {@code @Cacheable} use) are bound when they appear.
 *
 * <p>Thread-safety: counters read live, lock-free statistics.
 */
public class CacheLabMetrics implements MeterBinder {

  private final CacheLabCacheManager manager;

  /**
   * Creates the binder.
   *
   * @param manager the cache manager whose caches are metered
   */
  public CacheLabMetrics(CacheLabCacheManager manager) {
    this.manager = Objects.requireNonNull(manager, "manager");
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    manager.caches().forEach(c -> bind(registry, c.getNativeCache()));
    manager.onCacheCreated(c -> bind(registry, c.getNativeCache()));
  }

  /**
   * Binds one cache.
   *
   * @param registry the meter registry
   * @param cache the cache
   */
  public static void bind(MeterRegistry registry, Cache<?, ?> cache) {
    counter(registry, cache, "cache.gets", "hit", CacheStats::hitCount, "Cache lookups that hit");
    counter(
        registry, cache, "cache.gets", "miss", CacheStats::missCount, "Cache lookups that missed");
    counter(registry, cache, "cache.puts", null, CacheStats::putCount, "Entries put");
    counter(registry, cache, "cache.evictions", null, CacheStats::evictionCount, "Evictions");
    counter(
        registry, cache, "cache.expirations", null, CacheStats::expirationCount, "TTL expiries");
    counter(registry, cache, "cache.loads", "success", CacheStats::loadSuccessCount, "Loads");
    counter(
        registry, cache, "cache.loads", "failure", CacheStats::loadFailureCount, "Failed loads");
    Gauge.builder("cache.size", cache, Cache::size)
        .tag("cache", cache.name())
        .description("Entries currently held")
        .register(registry);
  }

  private static void counter(
      MeterRegistry registry,
      Cache<?, ?> cache,
      String name,
      String result,
      ToLongFunction<CacheStats> stat,
      String description) {
    ToDoubleFunction<Cache<?, ?>> read = c -> stat.applyAsLong(c.stats());
    FunctionCounter.Builder<Cache<?, ?>> builder =
        FunctionCounter.builder(name, cache, read)
            .tag("cache", cache.name())
            .description(description);
    if (result != null) {
      builder.tag("result", result);
    }
    builder.register(registry);
  }
}

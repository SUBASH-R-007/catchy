package io.cachelab.spring;

import io.cachelab.Cache;
import io.cachelab.CacheLoadException;
import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.springframework.cache.support.SimpleValueWrapper;

/**
 * Adapts a CacheLab {@link Cache} to Spring's {@link org.springframework.cache.Cache}, so
 * {@code @Cacheable}, {@code @CachePut} and {@code @CacheEvict} work unchanged.
 *
 * <p><b>Nulls:</b> Spring may cache a method's {@code null} result, but CacheLab rejects null
 * values. A private {@code NullValue} marker is stored instead and unwrapped on read, so a cached
 * {@code null} is a hit returning {@code null}.
 *
 * <p>{@link #get(Object, Callable)} uses CacheLab's single-flight {@code getOrLoad}: concurrent
 * misses on one key run the method once.
 *
 * <p>Thread-safety: thread-safe, like the underlying cache.
 */
public class CacheLabSpringCache implements org.springframework.cache.Cache {

  /** Stored in place of {@code null}. */
  private static final class NullValue implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private static final NullValue INSTANCE = new NullValue();

    private Object readResolve() {
      return INSTANCE;
    }
  }

  private final Cache<Object, Object> cache;

  /**
   * Wraps a cache.
   *
   * @param cache the CacheLab cache; must not be null
   */
  public CacheLabSpringCache(Cache<Object, Object> cache) {
    this.cache = Objects.requireNonNull(cache, "cache");
  }

  @Override
  public String getName() {
    return cache.name();
  }

  @Override
  public Cache<Object, Object> getNativeCache() {
    return cache;
  }

  @Override
  public ValueWrapper get(Object key) {
    return cache.get(key).map(v -> new SimpleValueWrapper(fromStore(v))).orElse(null);
  }

  @Override
  public <T> T get(Object key, Class<T> type) {
    Object value = cache.get(key).map(CacheLabSpringCache::fromStore).orElse(null);
    if (value != null && type != null && !type.isInstance(value)) {
      throw new IllegalStateException(
          "Cached value is not of required type [" + type.getName() + "]: " + value);
    }
    return type == null ? null : type.cast(value);
  }

  @Override
  @SuppressWarnings("unchecked") // the loader's result is stored and returned unchanged
  public <T> T get(Object key, Callable<T> valueLoader) {
    try {
      return (T)
          fromStore(
              cache.getOrLoad(
                  key,
                  k -> {
                    try {
                      return toStore(valueLoader.call());
                    } catch (RuntimeException e) {
                      throw e;
                    } catch (Exception e) {
                      throw new CacheLoadException("value loader failed", e);
                    }
                  }));
    } catch (CacheLoadException e) {
      throw new ValueRetrievalException(key, valueLoader, rootCause(e));
    }
  }

  @Override
  public void put(Object key, Object value) {
    cache.put(key, toStore(value));
  }

  @Override
  public void evict(Object key) {
    cache.remove(key);
  }

  @Override
  public boolean evictIfPresent(Object key) {
    return cache.remove(key);
  }

  @Override
  public void clear() {
    cache.clear();
  }

  private static Object toStore(Object value) {
    return value == null ? NullValue.INSTANCE : value;
  }

  private static Object fromStore(Object stored) {
    return stored instanceof NullValue ? null : stored;
  }

  private static Throwable rootCause(CacheLoadException e) {
    Throwable cause = e.getCause();
    while (cause instanceof CacheLoadException c && c.getCause() != null) {
      cause = c.getCause();
    }
    return cause == null ? e : cause;
  }
}

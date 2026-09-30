package io.cachelab;

/**
 * Observes every key lookup made through {@link Cache#get(Object)} and {@link
 * Cache#getOrLoad(Object, java.util.function.Function)}. Used by the policy advisor and the key
 * recorder to see the access stream without changing it.
 *
 * <p>Invocation: after the cache's internal lock is released, on the calling thread, once per
 * lookup. Exceptions are caught and logged through {@link System.Logger}; they never break the
 * cache.
 *
 * <p>Thread-safety: an observer may be invoked concurrently from several threads and must be
 * thread-safe itself. It should be fast, because it runs on the caller's thread.
 *
 * @param <K> the key type
 */
@FunctionalInterface
public interface AccessObserver<K> {

  /**
   * Called once per lookup.
   *
   * @param key the looked-up key, never null
   * @param hit {@code true} if a live value was found in the cache
   */
  void onAccess(K key, boolean hit);
}

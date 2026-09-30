package io.cachelab;

/**
 * Receives a notification each time an entry leaves a {@link Cache}.
 *
 * <p>Invocation: always <em>after</em> the cache's internal lock is released, either on the thread
 * that caused the removal or on the configured {@link CacheBuilder#removalExecutor removal
 * executor}. A listener may therefore call back into the cache without deadlocking.
 *
 * <p>Exceptions thrown by a listener are caught and logged through {@link System.Logger}; they
 * never break the cache or reach the caller.
 *
 * <p>Thread-safety: a listener may be invoked concurrently from several threads and must be
 * thread-safe itself.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
@FunctionalInterface
public interface RemovalListener<K, V> {

  /**
   * Called once for each removed entry.
   *
   * @param key the removed entry's key, never null
   * @param value the removed (or, for {@link RemovalCause#REPLACED}, the old) value, never null
   * @param cause why the entry was removed, never null
   */
  void onRemoval(K key, V value, RemovalCause cause);
}

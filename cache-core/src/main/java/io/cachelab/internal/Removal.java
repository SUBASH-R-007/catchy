package io.cachelab.internal;

import io.cachelab.RemovalCause;

/**
 * A removal collected under the cache lock and dispatched to the removal listener after the lock is
 * released (SPEC 4.6).
 *
 * @param key the removed key
 * @param value the removed (or replaced) value
 * @param cause why it was removed
 * @param <K> the key type
 * @param <V> the value type
 */
record Removal<K, V>(K key, V value, RemovalCause cause) {}

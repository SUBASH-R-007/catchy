package io.cachelab.testing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * An obviously-correct LRU model for differential tests: an access-ordered {@link LinkedHashMap}.
 * Keys only, cache-aside semantics: a miss does not insert by itself; the caller puts.
 *
 * <p>Thread-safety: not thread-safe. Complexity: O(1) per operation.
 *
 * @param <K> the key type
 */
public final class ReferenceLru<K> {

  private final int capacity;
  private final LinkedHashMap<K, Boolean> map = new LinkedHashMap<>(16, 0.75f, true);

  /**
   * Creates an empty model.
   *
   * @param capacity the maximum number of keys, at least 1
   */
  public ReferenceLru(int capacity) {
    this.capacity = capacity;
  }

  /**
   * Looks a key up; a hit makes it the most recently used.
   *
   * @param key the key
   * @return whether the key was present
   */
  public boolean get(K key) {
    return map.get(key) != null;
  }

  /**
   * Inserts a key, or touches it if present (a replace counts as an access and never evicts).
   *
   * @param key the key
   * @return the evicted key, or null if nothing was evicted
   */
  public K put(K key) {
    if (map.get(key) != null) {
      return null;
    }
    K evicted = null;
    if (map.size() >= capacity) {
      evicted = map.keySet().iterator().next();
      map.remove(evicted);
    }
    map.put(key, Boolean.TRUE);
    return evicted;
  }

  /**
   * Removes a key.
   *
   * @param key the key
   * @return whether it was present
   */
  public boolean remove(K key) {
    return map.remove(key) != null;
  }

  /**
   * Returns the keys, most recently used first (the order an LRU snapshot reports).
   *
   * @return the keys
   */
  public List<K> mostRecentFirst() {
    List<K> keys = new ArrayList<>(map.keySet());
    Collections.reverse(keys);
    return keys;
  }
}

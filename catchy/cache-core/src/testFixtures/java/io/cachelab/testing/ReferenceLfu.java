package io.cachelab.testing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An obviously-correct LFU model for differential tests: per key a frequency and the tick of its
 * last access; the victim is the minimum {@code (frequency, lastAccessTick)}, found by linear scan.
 * Keys only, cache-aside semantics.
 *
 * <p>Thread-safety: not thread-safe. Complexity: O(n) per eviction, O(1) otherwise.
 *
 * @param <K> the key type
 */
public final class ReferenceLfu<K> {

  private final int capacity;
  private final Map<K, long[]> entries = new HashMap<>(); // [frequency, lastAccessTick]
  private long tick;

  /**
   * Creates an empty model.
   *
   * @param capacity the maximum number of keys, at least 1
   */
  public ReferenceLfu(int capacity) {
    this.capacity = capacity;
  }

  /**
   * Looks a key up; a hit increments its frequency and refreshes its recency.
   *
   * @param key the key
   * @return whether the key was present
   */
  public boolean get(K key) {
    long[] e = entries.get(key);
    if (e == null) {
      return false;
    }
    e[0]++;
    e[1] = ++tick;
    return true;
  }

  /**
   * Inserts a key with frequency 1, or touches it if present (never evicts on replace).
   *
   * @param key the key
   * @return the evicted key, or null if nothing was evicted
   */
  public K put(K key) {
    if (get(key)) {
      return null;
    }
    K evicted = null;
    if (entries.size() >= capacity) {
      evicted =
          entries.entrySet().stream()
              .min(
                  Comparator.<Map.Entry<K, long[]>>comparingLong(x -> x.getValue()[0])
                      .thenComparingLong(x -> x.getValue()[1]))
              .orElseThrow()
              .getKey();
      entries.remove(evicted);
    }
    entries.put(key, new long[] {1, ++tick});
    return evicted;
  }

  /**
   * Removes a key.
   *
   * @param key the key
   * @return whether it was present
   */
  public boolean remove(K key) {
    return entries.remove(key) != null;
  }

  /** Halves every frequency (minimum 1), as LFU_DECAY does. */
  public void decay() {
    for (long[] e : entries.values()) {
      e[0] = Math.max(1, e[0] >> 1);
    }
  }

  /**
   * Returns the keys highest frequency first, most recent first within a frequency (the order an
   * LFU snapshot reports).
   *
   * @return the keys
   */
  public List<K> snapshotOrder() {
    List<Map.Entry<K, long[]>> list = new ArrayList<>(entries.entrySet());
    list.sort(
        Comparator.<Map.Entry<K, long[]>>comparingLong(x -> -x.getValue()[0])
            .thenComparingLong(x -> -x.getValue()[1]));
    return list.stream().map(Map.Entry::getKey).toList();
  }

  /**
   * Returns a key's frequency.
   *
   * @param key the key
   * @return the frequency, or 0 if absent
   */
  public long frequency(K key) {
    long[] e = entries.get(key);
    return e == null ? 0 : e[0];
  }
}

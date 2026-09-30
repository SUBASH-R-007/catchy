package io.cachelab;

import java.util.List;
import java.util.Objects;

/**
 * An immutable view of an eviction policy's internal order, used to visualise "inside the cache".
 *
 * <p>For {@link PolicyType#LRU} the entries are most-recent-first and every frequency is 0. For
 * {@link PolicyType#LFU} and {@link PolicyType#LFU_DECAY} they are highest-frequency-first, most
 * recent first within one frequency.
 *
 * <p>Null handling: neither the type, the list nor any entry may be null. Thread-safety: immutable.
 *
 * @param <K> the key type
 * @param type the policy that produced this snapshot
 * @param entries the entries in policy order; copied defensively
 */
public record PolicySnapshot<K>(PolicyType type, List<Entry<K>> entries) {

  /**
   * Validates and defensively copies the components.
   *
   * @throws NullPointerException if {@code type}, {@code entries} or any entry is null
   */
  public PolicySnapshot {
    Objects.requireNonNull(type, "type");
    entries = List.copyOf(entries);
  }

  /**
   * One key and its access frequency as seen by the policy.
   *
   * @param <K> the key type
   * @param key the key, never null
   * @param frequency the policy's access count for the key; 0 for LRU
   */
  public record Entry<K>(K key, long frequency) {

    /**
     * Validates the components.
     *
     * @throws NullPointerException if {@code key} is null
     * @throws IllegalArgumentException if {@code frequency} is negative
     */
    public Entry {
      Objects.requireNonNull(key, "key");
      if (frequency < 0) {
        throw new IllegalArgumentException("frequency must not be negative, but was " + frequency);
      }
    }
  }
}

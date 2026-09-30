package io.cachelab.internal;

import io.cachelab.PolicyType;
import java.util.HashMap;
import java.util.Objects;

/**
 * A keys-only cache-aside simulator driven by a real {@link EvictionPolicy}: a hit is an access, a
 * miss inserts the key, evicting the policy's victim when full. No values, no TTL. Used by shadow
 * caches and offline trace replay. Internal API.
 *
 * <p>Thread-safety: not thread-safe; callers synchronise. Complexity: O(1) per access; {@link
 * #decay()} O(n) for LFU_DECAY.
 *
 * @param <K> the key type
 */
public final class KeysOnlyCache<K> {

  private static final Object PRESENT = new Object();

  private final int capacity;
  private final PolicyType type;
  private final HashMap<K, Node<K, Object>> map;
  private final EvictionPolicy<K, Object> policy;
  private long tick;
  private long evictions;

  /**
   * Creates an empty simulator.
   *
   * @param capacity the maximum number of keys, at least 1
   * @param type the eviction policy
   * @throws IllegalArgumentException if {@code capacity} is less than 1
   */
  public KeysOnlyCache(int capacity, PolicyType type) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be at least 1, but was " + capacity);
    }
    this.capacity = capacity;
    this.type = Objects.requireNonNull(type, "type");
    this.map = HashMap.newHashMap(Math.min(capacity, 1 << 16));
    this.policy = Policies.create(type);
  }

  /**
   * Looks a key up and, on a miss, inserts it.
   *
   * @param key the key; must not be null
   * @return whether it was a hit
   */
  public boolean access(K key) {
    Node<K, Object> n = map.get(key);
    if (n != null) {
      n.lastAccess = ++tick;
      policy.onAccess(n);
      return true;
    }
    if (map.size() >= capacity) {
      Node<K, Object> victim = policy.pollVictim();
      map.remove(victim.key);
      evictions++;
    }
    n = new Node<>(key, PRESENT);
    n.lastAccess = ++tick;
    map.put(key, n);
    policy.onInsert(n);
    return false;
  }

  /** Halves frequencies under LFU_DECAY; a no-op for the other policies. */
  public void decay() {
    policy.decay();
  }

  /**
   * Returns the number of evictions so far.
   *
   * @return the eviction count
   */
  public long evictions() {
    return evictions;
  }

  /**
   * Returns the number of keys held.
   *
   * @return the size
   */
  public int size() {
    return map.size();
  }

  /**
   * Returns the simulated policy.
   *
   * @return the policy type
   */
  public PolicyType type() {
    return type;
  }
}

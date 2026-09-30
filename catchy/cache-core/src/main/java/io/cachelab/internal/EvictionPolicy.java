package io.cachelab.internal;

import io.cachelab.PolicySnapshot;
import io.cachelab.PolicyType;
import java.util.List;

/**
 * Decides which live entry to evict from a full cache. Internal API: not for library users.
 *
 * <p><b>Hard rule:</b> a policy never reads time, TTL or {@code expiresAt}. Expiry is handled by a
 * separate expiry index, and the policy is only told that a node was removed. This keeps TTL
 * behaviour identical under every policy.
 *
 * <p>Null handling: nodes passed in are never null. Thread-safety: not thread-safe; every method is
 * called with the owning cache's lock held. Complexity: every method is O(1) except {@link
 * #snapshot(int)} (O(limit)), {@link #rebuildFrom(List)} (O(n log b) for LFU, where b is the number
 * of distinct frequencies), {@link #decay()} (O(n)) and {@link #checkInvariants()} (O(n)).
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public interface EvictionPolicy<K, V> {

  /**
   * Starts tracking a new entry. The node's frequency may be preset only through {@link
   * #rebuildFrom(List)}.
   *
   * @param n the newly inserted, unlinked node
   */
  void onInsert(Node<K, V> n);

  /**
   * Records an access to a tracked entry: a hit or a replacing {@code put}.
   *
   * @param n the accessed node
   */
  void onAccess(Node<K, V> n);

  /**
   * Stops tracking an entry removed for any reason other than this policy's own eviction: explicit
   * removal, expiry or clear.
   *
   * @param n the removed node
   */
  void onRemove(Node<K, V> n);

  /**
   * Unlinks and returns the entry to evict.
   *
   * @return the victim, or {@code null} if the policy tracks no entries
   */
  Node<K, V> pollVictim();

  /**
   * Returns up to {@code limit} entries in policy order (see {@link PolicySnapshot}).
   *
   * @param limit the maximum number of entries; not negative
   * @return the snapshot, never null
   */
  PolicySnapshot<K> snapshot(int limit);

  /**
   * Replaces all tracked state with the given nodes, used by a runtime policy switch. Frequency
   * policies keep each node's existing frequency (floor 1); recency policies ignore it.
   *
   * @param nodesLeastRecentFirst every live node, ordered by last access, oldest first
   */
  void rebuildFrom(List<Node<K, V>> nodesLeastRecentFirst);

  /** Ages access history; only {@link PolicyType#LFU_DECAY} does anything. */
  default void decay() {}

  /**
   * Verifies the internal structure: symmetric links, no cycles, a count equal to the tracked size
   * and, for LFU, strictly ascending non-empty buckets whose frequency matches every member.
   *
   * @throws IllegalStateException describing the first violation found
   */
  void checkInvariants();

  /**
   * Returns which policy this is.
   *
   * @return the policy type, never null
   */
  PolicyType type();

  /**
   * Returns the number of tracked entries; the engine cross-checks it against its map.
   *
   * @return the entry count
   */
  int size();
}

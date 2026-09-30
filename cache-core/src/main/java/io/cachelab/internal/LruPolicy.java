package io.cachelab.internal;

import io.cachelab.PolicySnapshot;
import io.cachelab.PolicyType;
import java.util.ArrayList;
import java.util.List;

/**
 * Least-recently-used eviction: one intrusive list with the most recently used entry at the front;
 * the victim is the back. Never reads time or TTL.
 *
 * <p>Thread-safety: not thread-safe; guarded by the owning cache's lock. Complexity: O(1) per
 * operation; {@link #snapshot(int)} O(limit); {@link #rebuildFrom(List)} and {@link
 * #checkInvariants()} O(n).
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class LruPolicy<K, V> implements EvictionPolicy<K, V> {

  private final IntrusiveList<K, V> list = new IntrusiveList<>();

  /** Creates an empty policy. */
  public LruPolicy() {}

  @Override
  public void onInsert(Node<K, V> n) {
    n.frequency = 0;
    list.linkFirst(n);
  }

  @Override
  public void onAccess(Node<K, V> n) {
    list.unlink(n);
    list.linkFirst(n);
  }

  @Override
  public void onRemove(Node<K, V> n) {
    list.unlink(n);
  }

  @Override
  public Node<K, V> pollVictim() {
    return list.pollLast();
  }

  @Override
  public PolicySnapshot<K> snapshot(int limit) {
    List<PolicySnapshot.Entry<K>> entries = new ArrayList<>(Math.min(limit, list.size()));
    for (Node<K, V> n : list) {
      if (entries.size() >= limit) {
        break;
      }
      entries.add(new PolicySnapshot.Entry<>(n.key, 0));
    }
    return new PolicySnapshot<>(PolicyType.LRU, entries);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Frequencies are left untouched (LRU ignores them), so switching LFU → LRU → LFU keeps them.
   */
  @Override
  public void rebuildFrom(List<Node<K, V>> nodesLeastRecentFirst) {
    list.clear();
    for (Node<K, V> n : nodesLeastRecentFirst) {
      n.bucket = null;
      n.prev = null;
      n.next = null;
      list.linkFirst(n);
    }
  }

  @Override
  public void checkInvariants() {
    list.checkInvariants("LRU list");
    for (Node<K, V> n : list) {
      if (n.bucket != null) {
        throw new IllegalStateException("LRU node " + n + " still points at an LFU bucket");
      }
    }
  }

  @Override
  public PolicyType type() {
    return PolicyType.LRU;
  }

  @Override
  public int size() {
    return list.size();
  }
}

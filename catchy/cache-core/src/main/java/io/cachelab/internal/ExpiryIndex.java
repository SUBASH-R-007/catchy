package io.cachelab.internal;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.function.Consumer;

/**
 * Expiry deadlines, kept separate from the eviction policy (ADR-002): a min-heap of tickets ordered
 * by deadline. Every {@code put} schedules a new ticket carrying the node's version; a ticket is
 * <em>stale</em> once its node was removed or put again, and stale tickets are discarded when they
 * reach the head. This makes a TTL reset O(log n) with no heap removal.
 *
 * <p>Deadlines are compared by subtraction, so ticker wrap-around is harmless as long as deadlines
 * are within 2<sup>62</sup> ns of each other (TTLs are capped accordingly).
 *
 * <p>Thread-safety: not thread-safe; guarded by the owning cache's lock. Complexity: {@link
 * #schedule} O(log n); {@link #purgeExpired} O((removed + stale) log n); {@link #compact} O(n log
 * n).
 *
 * @param <K> the key type
 * @param <V> the value type
 */
final class ExpiryIndex<K, V> {

  /** One scheduled deadline for one version of a node. */
  record Ticket<K, V>(long expiresAt, int version, Node<K, V> node) {
    boolean isStale() {
      return node.removed || node.version != version;
    }
  }

  private final Comparator<Ticket<K, V>> byDeadline =
      (a, b) -> Long.signum(a.expiresAt() - b.expiresAt());

  private PriorityQueue<Ticket<K, V>> queue = new PriorityQueue<>(byDeadline);

  /** Schedules the node's current deadline, unless it never expires. */
  void schedule(Node<K, V> n) {
    if (n.expiresAt != Node.NEVER) {
      queue.add(new Ticket<>(n.expiresAt, n.version, n));
    }
  }

  /**
   * Hands expired nodes to {@code onExpired}, earliest deadline first, until the head is not yet
   * expired or {@code max} nodes were expired. Stale tickets met on the way are discarded. The
   * consumer must remove the node from the cache and mark it removed.
   *
   * @return the number of nodes expired
   */
  int purgeExpired(long now, int max, Consumer<Node<K, V>> onExpired) {
    int expired = 0;
    while (expired < max) {
      Ticket<K, V> head = queue.peek();
      if (head == null) {
        break;
      }
      if (head.isStale()) {
        queue.poll();
        continue;
      }
      if (now - head.expiresAt() < 0) {
        break;
      }
      queue.poll();
      onExpired.accept(head.node());
      expired++;
    }
    return expired;
  }

  /**
   * True when stale tickets dominate: more than {@code 2 * liveCount + 1024} tickets (SPEC 4.5).
   */
  boolean needsCompaction(int liveCount) {
    return queue.size() > 2L * liveCount + 1024;
  }

  /** Rebuilds the heap from the live nodes' current deadlines, dropping every stale ticket. */
  void compact(Iterable<Node<K, V>> liveNodes) {
    PriorityQueue<Ticket<K, V>> rebuilt = new PriorityQueue<>(byDeadline);
    for (Node<K, V> n : liveNodes) {
      if (!n.removed && n.expiresAt != Node.NEVER) {
        rebuilt.add(new Ticket<>(n.expiresAt, n.version, n));
      }
    }
    queue = rebuilt;
  }

  void clear() {
    queue.clear();
  }

  /** The number of tickets, including stale ones not yet discarded. */
  int size() {
    return queue.size();
  }
}

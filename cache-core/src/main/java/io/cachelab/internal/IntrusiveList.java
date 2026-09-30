package io.cachelab.internal;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A doubly linked list threaded through {@link Node#prev}/{@link Node#next}, with sentinel head and
 * tail nodes so linking and unlinking never branch on emptiness. The front holds the most recently
 * linked node. A node can be in at most one intrusive list at a time.
 *
 * <p>Thread-safety: not thread-safe; guarded by the owning cache's lock. Complexity: every
 * operation is O(1) except iteration and {@link #checkInvariants(String)}, which are O(n).
 *
 * @param <K> the key type
 * @param <V> the value type
 */
final class IntrusiveList<K, V> implements Iterable<Node<K, V>> {

  private final Node<K, V> head = Node.sentinel();
  private final Node<K, V> tail = Node.sentinel();
  private int size;

  IntrusiveList() {
    head.next = tail;
    tail.prev = head;
  }

  /** Links {@code n} (currently unlinked) at the front. */
  void linkFirst(Node<K, V> n) {
    linkAfter(head, n);
  }

  /** Links {@code n} (currently unlinked) at the back. */
  void linkLast(Node<K, V> n) {
    linkAfter(tail.prev, n);
  }

  private void linkAfter(Node<K, V> anchor, Node<K, V> n) {
    Node<K, V> after = anchor.next;
    n.prev = anchor;
    n.next = after;
    anchor.next = n;
    after.prev = n;
    size++;
  }

  /** Unlinks {@code n}, which must be in this list, and clears its links. */
  void unlink(Node<K, V> n) {
    n.prev.next = n.next;
    n.next.prev = n.prev;
    n.prev = null;
    n.next = null;
    size--;
  }

  /** Unlinks and returns the back (least recently linked) node, or null if empty. */
  Node<K, V> pollLast() {
    Node<K, V> last = tail.prev;
    if (last == head) {
      return null;
    }
    unlink(last);
    return last;
  }

  /** Returns the back node without unlinking it, or null if empty. */
  Node<K, V> peekLast() {
    return tail.prev == head ? null : tail.prev;
  }

  /** Returns the front node without unlinking it, or null if empty. */
  Node<K, V> peekFirst() {
    return head.next == tail ? null : head.next;
  }

  boolean isEmpty() {
    return size == 0;
  }

  int size() {
    return size;
  }

  /**
   * Moves every node of {@code other} into this list, keeping the order by {@link Node#lastAccess}
   * descending (most recent first). Both lists must already be in that order. A linear merge:
   * O(size() + other.size()), no allocation. {@code other} is left empty.
   */
  void mergeByRecency(IntrusiveList<K, V> other) {
    Node<K, V> cursor = head.next;
    Node<K, V> n;
    while ((n = other.pollFirst()) != null) {
      while (cursor != tail && cursor.lastAccess > n.lastAccess) {
        cursor = cursor.next;
      }
      linkAfter(cursor.prev, n); // n goes right before cursor
    }
  }

  /** Unlinks and returns the front (most recently linked) node, or null if empty. */
  Node<K, V> pollFirst() {
    Node<K, V> first = head.next;
    if (first == tail) {
      return null;
    }
    unlink(first);
    return first;
  }

  /** Removes every node, clearing their links. O(n). */
  void clear() {
    Node<K, V> n = head.next;
    while (n != tail) {
      Node<K, V> next = n.next;
      n.prev = null;
      n.next = null;
      n = next;
    }
    head.next = tail;
    tail.prev = head;
    size = 0;
  }

  /** Iterates from the front (most recent) to the back. Do not modify the list while iterating. */
  @Override
  public Iterator<Node<K, V>> iterator() {
    return new Iterator<>() {
      private Node<K, V> next = head.next;

      @Override
      public boolean hasNext() {
        return next != tail;
      }

      @Override
      public Node<K, V> next() {
        if (next == tail) {
          throw new NoSuchElementException();
        }
        Node<K, V> current = next;
        next = next.next;
        return current;
      }
    };
  }

  /**
   * Verifies symmetric links, the absence of cycles and that the node count equals {@link #size()}.
   *
   * @param where a label for error messages
   * @return the number of nodes walked
   * @throws IllegalStateException describing the first violation
   */
  int checkInvariants(String where) {
    int count = 0;
    Node<K, V> prev = head;
    Node<K, V> n = head.next;
    while (n != tail) {
      if (n == null) {
        throw new IllegalStateException(where + ": broken next link after " + prev);
      }
      if (n.prev != prev) {
        throw new IllegalStateException(where + ": asymmetric link at " + n);
      }
      if (++count > size) {
        throw new IllegalStateException(where + ": more nodes than size " + size + " (cycle?)");
      }
      prev = n;
      n = n.next;
    }
    if (tail.prev != prev) {
      throw new IllegalStateException(where + ": tail.prev does not point at the last node");
    }
    if (count != size) {
      throw new IllegalStateException(where + ": walked " + count + " nodes but size is " + size);
    }
    return count;
  }
}

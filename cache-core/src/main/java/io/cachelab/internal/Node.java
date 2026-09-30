package io.cachelab.internal;

import java.util.Objects;

/**
 * One cache entry, linked intrusively into an eviction policy's lists. Internal API: not for
 * library users.
 *
 * <p>The node carries state for two independent components. The eviction policy owns {@code prev},
 * {@code next}, {@code bucket} and {@code frequency}. The engine owns {@code value}, {@code
 * expiresAt}, {@code version}, {@code removed} and {@code lastAccess}. Policies never read {@code
 * expiresAt}.
 *
 * <p>Thread-safety: not thread-safe. Every field except {@code value} is read and written only
 * while the owning cache's lock is held; {@code value} is volatile so it may be published to
 * readers that already hold a reference.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class Node<K, V> {

  /** Sentinel for {@link #expiresAt} meaning "never expires". */
  public static final long NEVER = Long.MAX_VALUE;

  final K key;
  volatile V value;

  /** Ticker reading at which the entry expires, or {@link #NEVER}. */
  long expiresAt = NEVER;

  /** Incremented on every put; lets the expiry index discard stale tickets. */
  int version;

  /** Set once the node has left the cache; it must never be relinked. */
  boolean removed;

  Node<K, V> prev;
  Node<K, V> next;
  FreqBucket<K, V> bucket;
  long frequency;

  /** Monotonic access tick (not time), set on every successful access. */
  long lastAccess;

  /**
   * Creates an unlinked node that never expires.
   *
   * @param key the key; must not be null
   * @param value the value; may be null only for keys-only simulations such as a shadow cache
   * @throws NullPointerException if {@code key} is null
   */
  public Node(K key, V value) {
    this.key = Objects.requireNonNull(key, "key");
    this.value = value;
  }

  /** Sentinel constructor: the only way to create a node with a null key. */
  private Node() {
    this.key = null;
    this.value = null;
  }

  /** Creates a list sentinel (head or tail); it never holds an entry. */
  static <K, V> Node<K, V> sentinel() {
    return new Node<>();
  }

  /**
   * Returns the key.
   *
   * @return the key, never null
   */
  public K key() {
    return key;
  }

  /**
   * Returns whether the entry has expired at time {@code now}. A node with {@link #NEVER} never
   * expires, whatever the reading; otherwise the comparison uses subtraction so it stays correct
   * when ticker readings wrap around. Complexity: O(1).
   *
   * @param now the current ticker reading in nanoseconds
   * @return {@code true} if the entry's expiry time has been reached
   */
  public boolean isExpired(long now) {
    return expiresAt != NEVER && now - expiresAt >= 0;
  }

  @Override
  public String toString() {
    return "Node[" + key + ", freq=" + frequency + ", lastAccess=" + lastAccess + "]";
  }
}

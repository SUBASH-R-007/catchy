package io.cachelab.advisor;

import io.cachelab.AccessObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Records the most recent lookup keys in a ring buffer (SPEC 6.4), so the optimal hit rate can be
 * computed over real traffic.
 *
 * <p>Thread-safety: {@link #onAccess} and {@link #snapshot} synchronise briefly. Memory: one
 * reference per slot.
 *
 * @param <K> the key type
 */
public final class KeyRecorder<K> implements AccessObserver<K> {

  /** Default capacity: the last 200,000 lookups. */
  public static final int DEFAULT_CAPACITY = 200_000;

  private final Object[] ring;
  private long count;

  /** Creates a recorder for the last 200,000 lookups. */
  public KeyRecorder() {
    this(DEFAULT_CAPACITY);
  }

  /**
   * Creates a recorder.
   *
   * @param capacity the number of keys kept, at least 1
   */
  public KeyRecorder(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be at least 1, but was " + capacity);
    }
    ring = new Object[capacity];
  }

  @Override
  public synchronized void onAccess(K key, boolean hit) {
    ring[(int) (count % ring.length)] = Objects.requireNonNull(key, "key");
    count++;
  }

  /**
   * Returns the recorded keys, oldest first.
   *
   * @return a copy of the recorded keys
   */
  @SuppressWarnings("unchecked") // only K instances are ever stored
  public synchronized List<K> snapshot() {
    int size = (int) Math.min(count, ring.length);
    List<K> keys = new ArrayList<>(size);
    long first = count - size;
    for (long i = first; i < count; i++) {
      keys.add((K) ring[(int) (i % ring.length)]);
    }
    return keys;
  }

  /**
   * Returns how many lookups were recorded in total, including overwritten ones.
   *
   * @return the lookup count
   */
  public synchronized long totalRecorded() {
    return count;
  }

  /** Forgets every recorded key. */
  public synchronized void clear() {
    java.util.Arrays.fill(ring, null);
    count = 0;
  }
}

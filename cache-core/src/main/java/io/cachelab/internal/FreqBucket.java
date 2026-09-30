package io.cachelab.internal;

/**
 * A frequency bucket in the LFU policies' ascending bucket list: every node in {@link #entries} has
 * been accessed exactly {@link #freq} times. Buckets are linked to their neighbours so a node can
 * move to {@code freq + 1} in O(1). Within a bucket, nodes are most-recent-first, so the back of
 * the list is the least recently used node of that frequency.
 *
 * <p>Thread-safety: not thread-safe; guarded by the owning cache's lock.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
final class FreqBucket<K, V> {

  final long freq;
  final IntrusiveList<K, V> entries = new IntrusiveList<>();
  FreqBucket<K, V> prev;
  FreqBucket<K, V> next;

  FreqBucket(long freq) {
    this.freq = freq;
  }

  @Override
  public String toString() {
    return "FreqBucket[freq=" + freq + ", size=" + entries.size() + "]";
  }
}

package io.cachelab.internal;

/**
 * A frequency bucket in the LFU policies' ascending bucket list: every node in the bucket has been
 * accessed exactly {@link #freq} times. Buckets are linked to their neighbours so the policy can
 * move a node to {@code freq + 1} in O(1).
 *
 * <p>The member list of nodes is added with the LFU policy (Step 2).
 *
 * <p>Thread-safety: not thread-safe; guarded by the owning cache's lock.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
final class FreqBucket<K, V> {

  final long freq;
  FreqBucket<K, V> prev;
  FreqBucket<K, V> next;

  FreqBucket(long freq) {
    this.freq = freq;
  }
}

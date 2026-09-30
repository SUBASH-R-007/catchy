package io.cachelab.internal;

import io.cachelab.PolicyType;

/**
 * LFU with periodic decay (SPEC 6.1): the same O(1) bucket structure as {@link LfuPolicy}, plus
 * {@link #decay()}, which halves every frequency ({@code f -> max(1, f >> 1)}) so that past
 * popularity fades and new favourites can overtake old ones. Never reads time: the engine decides
 * when to decay.
 *
 * <p>Decay is a single linear pass. Halving preserves the order between buckets, so the source
 * buckets that land on the same new frequency are consecutive (1, 2 and 3 all become 1; 2k and 2k+1
 * become k). Their member lists, each already most-recent-first, are merged by {@link
 * Node#lastAccess} in place, which keeps least-recent-first eviction among equal frequencies.
 *
 * <p>Thread-safety: not thread-safe; guarded by the owning cache's lock. Complexity: as {@link
 * LfuPolicy}; {@link #decay()} O(n) with no per-node allocation.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class LfuDecayPolicy<K, V> extends LfuPolicy<K, V> {

  /** Creates an empty policy. */
  public LfuDecayPolicy() {}

  @Override
  public void decay() {
    FreqBucket<K, V> source = head.next;
    head.next = tail;
    tail.prev = head;
    while (source != tail) {
      long newFreq = Math.max(1, source.freq >> 1);
      FreqBucket<K, V> merged = new FreqBucket<>(newFreq);
      while (source != tail && Math.max(1, source.freq >> 1) == newFreq) {
        FreqBucket<K, V> next = source.next;
        merged.entries.mergeByRecency(source.entries);
        source.prev = null;
        source.next = null;
        source = next;
      }
      for (Node<K, V> n : merged.entries) {
        n.frequency = newFreq;
        n.bucket = merged;
      }
      linkBucketBefore(tail, merged);
    }
  }

  @Override
  public PolicyType type() {
    return PolicyType.LFU_DECAY;
  }
}

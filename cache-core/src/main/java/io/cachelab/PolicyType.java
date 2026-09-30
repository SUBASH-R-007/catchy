package io.cachelab;

/**
 * The eviction policies a {@link Cache} can use. The policy decides which live entry to evict when
 * a new key is inserted into a full cache; it never affects expiry.
 *
 * <p>Thread-safety: enum constants are immutable and safe to share.
 */
public enum PolicyType {

  /**
   * Least recently used: evicts the entry whose last access is oldest. O(1) per operation. Adapts
   * quickly to shifting popularity but is flushed by one-off scans.
   */
  LRU,

  /**
   * Least frequently used: evicts the entry with the fewest accesses, breaking ties by evicting the
   * least recently used among them. O(1) per operation using frequency buckets. Resists scans but
   * can cling to formerly popular keys.
   */
  LFU,

  /**
   * LFU with periodic decay: every decay interval all frequencies are halved (minimum 1), so old
   * popularity fades. O(1) per operation plus an O(n) decay pass.
   */
  LFU_DECAY
}

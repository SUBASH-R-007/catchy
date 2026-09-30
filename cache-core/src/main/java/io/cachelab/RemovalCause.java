package io.cachelab;

/**
 * Why an entry left the cache, as reported to a {@link RemovalListener}.
 *
 * <p>Thread-safety: enum constants are immutable and safe to share.
 */
public enum RemovalCause {

  /** Removed by the caller through {@link Cache#remove(Object)} or {@link Cache#clear()}. */
  EXPLICIT,

  /** The value was overwritten by a {@code put} on the same key; the old value is reported. */
  REPLACED,

  /** Evicted by the eviction policy to make room for a new key in a full cache. */
  EVICTED,

  /** Removed because its time-to-live elapsed, either lazily on access or by the sweeper. */
  EXPIRED
}

package io.cachelab;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable, read-only view of one cache entry, as listed by {@link Cache#entries(int)}.
 *
 * <p>Null handling: no component may be null; a missing TTL is {@link Optional#empty()}.
 * Thread-safety: immutable.
 *
 * @param <K> the key type
 * @param key the entry's key
 * @param frequency the policy's access count for the entry; 0 under {@link PolicyType#LRU}
 * @param ttlRemaining the time left before expiry, or empty if the entry never expires
 */
public record EntryView<K>(K key, long frequency, Optional<Duration> ttlRemaining) {

  /**
   * Validates the components.
   *
   * @throws NullPointerException if {@code key} or {@code ttlRemaining} is null
   * @throws IllegalArgumentException if {@code frequency} is negative
   */
  public EntryView {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(ttlRemaining, "ttlRemaining");
    if (frequency < 0) {
      throw new IllegalArgumentException("frequency must not be negative, but was " + frequency);
    }
  }
}

package io.cachelab.internal;

import io.cachelab.PolicyType;
import java.util.Objects;

/**
 * Creates eviction policies by type. Internal API.
 *
 * <p>Thread-safety: stateless.
 */
public final class Policies {

  private Policies() {}

  /**
   * Creates an empty policy of the given type.
   *
   * @param type the policy type; must not be null
   * @param <K> the key type
   * @param <V> the value type
   * @return a new, empty policy
   * @throws NullPointerException if {@code type} is null
   */
  public static <K, V> EvictionPolicy<K, V> create(PolicyType type) {
    return switch (Objects.requireNonNull(type, "type")) {
      case LRU -> new LruPolicy<>();
      case LFU -> new LfuPolicy<>();
      case LFU_DECAY ->
          throw new UnsupportedOperationException("LFU_DECAY arrives in Step 3 (SPEC 6.1)");
    };
  }
}

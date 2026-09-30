package io.cachelab.server.metrics;

import io.cachelab.server.cache.ManagedCache;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * One {@link LatencyRecorder} per live cache instance. Keyed by the {@link ManagedCache} object
 * (identity), so a cache that is deleted and recreated under the same name starts with an empty
 * histogram.
 *
 * <p>Thread-safe: the simulation thread records while the metrics thread reads, rotates and prunes.
 */
@Component
public class LatencyRecorders {

  private final Map<ManagedCache, LatencyRecorder> recorders = new ConcurrentHashMap<>();

  /**
   * Returns the recorder of a cache, creating it on first use.
   *
   * @param cache the cache; never {@code null}
   * @return its recorder
   */
  public LatencyRecorder of(ManagedCache cache) {
    return recorders.computeIfAbsent(
        Objects.requireNonNull(cache, "cache"), c -> new LatencyRecorder());
  }

  /**
   * Returns the recorder of a cache without creating one.
   *
   * @param cache the cache
   * @return its recorder, or {@code null} when nothing was recorded for it yet
   */
  public LatencyRecorder peek(ManagedCache cache) {
    return recorders.get(cache);
  }

  /** Rotates every recorder: call once per metrics tick. */
  public void rotateAll() {
    recorders.values().forEach(LatencyRecorder::rotate);
  }

  /**
   * Drops the recorders of caches that are no longer registered.
   *
   * @param live the registered caches
   */
  public void retainOnly(Collection<ManagedCache> live) {
    Set<ManagedCache> keep = Set.copyOf(live);
    recorders.keySet().removeIf(c -> !keep.contains(c));
  }
}

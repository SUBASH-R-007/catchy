package io.cachelab.advisor;

import io.cachelab.PolicyType;
import io.cachelab.internal.KeysOnlyCache;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * A keys-only simulation of one eviction policy on the real access stream (SPEC 6.3): it sees every
 * lookup the real cache sees and answers "would this policy have hit?", without storing values.
 * Cache-aside: a miss inserts the key, evicting when full. <b>TTL is ignored</b> — shadows compare
 * eviction policies, and expiry is identical under every policy (ADR-002).
 *
 * <p>Hits and misses are also counted in 1-second buckets (the last {@value #MAX_WINDOW_SEC}
 * seconds) for windowed hit rates. Under {@link PolicyType#LFU_DECAY} the shadow decays itself
 * every decay interval of its clock, like the real engine.
 *
 * <p>Thread-safety: guarded by its own lock. Complexity: O(1) per access.
 *
 * @param <K> the key type
 */
public final class ShadowCache<K> {

  /** Longest window, in seconds, that can be queried. */
  public static final int MAX_WINDOW_SEC = 60;

  /** Default decay interval for LFU_DECAY shadows, matching the engine default. */
  public static final long DEFAULT_DECAY_INTERVAL_MS = 10_000;

  private final ReentrantLock lock = new ReentrantLock();
  private final KeysOnlyCache<K> keys;
  private final LongSupplier clockMillis;
  private final long decayIntervalMs;
  private final long[] bucketSecond = new long[MAX_WINDOW_SEC];
  private final long[] bucketHits = new long[MAX_WINDOW_SEC];
  private final long[] bucketMisses = new long[MAX_WINDOW_SEC];
  private long hits;
  private long misses;
  private long lastDecayMs;

  /**
   * Creates an empty shadow.
   *
   * @param capacity the simulated capacity (the real cache's maximum size)
   * @param policy the policy to simulate
   * @param clockMillis time source for the windows and for decay, in milliseconds
   * @param decayIntervalMs decay interval for LFU_DECAY, positive
   */
  public ShadowCache(
      int capacity, PolicyType policy, LongSupplier clockMillis, long decayIntervalMs) {
    this.keys = new KeysOnlyCache<>(capacity, policy);
    this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    if (decayIntervalMs <= 0) {
      throw new IllegalArgumentException("decayIntervalMs must be positive: " + decayIntervalMs);
    }
    this.decayIntervalMs = decayIntervalMs;
    this.lastDecayMs = clockMillis.getAsLong();
    java.util.Arrays.fill(bucketSecond, Long.MIN_VALUE);
  }

  /**
   * Simulates one lookup.
   *
   * @param key the looked-up key; must not be null
   * @return whether the simulated policy would have hit
   */
  public boolean access(K key) {
    Objects.requireNonNull(key, "key");
    long now = clockMillis.getAsLong();
    lock.lock();
    try {
      if (keys.type() == PolicyType.LFU_DECAY && now - lastDecayMs >= decayIntervalMs) {
        // Catch up on every interval that elapsed (e.g. after quiet traffic); after 64 halvings
        // every frequency is 1, so more would change nothing.
        long periods = (now - lastDecayMs) / decayIntervalMs;
        for (long i = 0; i < Math.min(periods, 64); i++) {
          keys.decay();
        }
        lastDecayMs += periods * decayIntervalMs;
      }
      boolean hit = keys.access(key);
      count(now / 1000, hit);
      return hit;
    } finally {
      lock.unlock();
    }
  }

  private void count(long second, boolean hit) {
    int slot = (int) Math.floorMod(second, (long) MAX_WINDOW_SEC);
    if (bucketSecond[slot] != second) {
      bucketSecond[slot] = second;
      bucketHits[slot] = 0;
      bucketMisses[slot] = 0;
    }
    if (hit) {
      bucketHits[slot]++;
      hits++;
    } else {
      bucketMisses[slot]++;
      misses++;
    }
  }

  /**
   * Returns the hit rate over the last {@code seconds} seconds (including the current one).
   *
   * @param seconds window length, 1 to {@value #MAX_WINDOW_SEC}
   * @return the windowed hit rate, 0 when there were no accesses
   */
  public double windowHitRate(int seconds) {
    long[] counts = windowCounts(seconds);
    long total = counts[0] + counts[1];
    return total == 0 ? 0.0 : (double) counts[0] / total;
  }

  /**
   * Returns the number of accesses in the last {@code seconds} seconds.
   *
   * @param seconds window length, 1 to {@value #MAX_WINDOW_SEC}
   * @return the access count
   */
  public long windowRequests(int seconds) {
    long[] counts = windowCounts(seconds);
    return counts[0] + counts[1];
  }

  private long[] windowCounts(int seconds) {
    if (seconds < 1 || seconds > MAX_WINDOW_SEC) {
      throw new IllegalArgumentException("seconds must be 1.." + MAX_WINDOW_SEC + ": " + seconds);
    }
    long nowSecond = clockMillis.getAsLong() / 1000;
    long windowHits = 0;
    long windowMisses = 0;
    lock.lock();
    try {
      for (int i = 0; i < MAX_WINDOW_SEC; i++) {
        long age = nowSecond - bucketSecond[i];
        if (bucketSecond[i] != Long.MIN_VALUE && age >= 0 && age < seconds) {
          windowHits += bucketHits[i];
          windowMisses += bucketMisses[i];
        }
      }
    } finally {
      lock.unlock();
    }
    return new long[] {windowHits, windowMisses};
  }

  /** Forgets the windowed counts (the simulated contents and totals are kept). */
  public void resetWindow() {
    lock.lock();
    try {
      java.util.Arrays.fill(bucketSecond, Long.MIN_VALUE);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns cumulative counts since creation.
   *
   * @return hits, misses and evictions
   */
  public Result totals() {
    lock.lock();
    try {
      return new Result(hits, misses, keys.evictions());
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the simulated policy.
   *
   * @return the policy
   */
  public PolicyType policy() {
    return keys.type();
  }

  /**
   * Replays a whole trace offline through a fresh shadow. Time is logical — access {@code i}
   * happens at {@code i / 5} ms (5,000 accesses per second) — so LFU_DECAY decays every 50,000
   * accesses, deterministically.
   *
   * @param trace the access sequence
   * @param capacity the simulated capacity
   * @param policy the policy
   * @param <K> the key type
   * @return the counts
   */
  public static <K> Result replay(List<K> trace, int capacity, PolicyType policy) {
    long[] index = {0};
    ShadowCache<K> shadow =
        new ShadowCache<>(capacity, policy, () -> index[0] / 5, DEFAULT_DECAY_INTERVAL_MS);
    for (K key : trace) {
      shadow.access(key);
      index[0]++;
    }
    return shadow.totals();
  }

  /**
   * Hit, miss and eviction counts.
   *
   * @param hits simulated hits
   * @param misses simulated misses
   * @param evictions simulated evictions
   */
  public record Result(long hits, long misses, long evictions) {

    /**
     * Returns hits divided by accesses.
     *
     * @return the hit rate, 0 when there were none
     */
    public double hitRate() {
      long total = hits + misses;
      return total == 0 ? 0.0 : (double) hits / total;
    }
  }
}

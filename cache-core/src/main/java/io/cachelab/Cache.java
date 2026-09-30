package io.cachelab;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * A bounded, thread-safe, in-memory key-value cache with a selectable eviction policy ({@link
 * PolicyType#LRU}, {@link PolicyType#LFU} or {@link PolicyType#LFU_DECAY}) and per-entry
 * time-to-live (TTL) that is independent of the eviction policy.
 *
 * <p>Instances are created with {@link CacheBuilder}.
 *
 * <h2>Semantics</h2>
 *
 * <ul>
 *   <li><b>Capacity:</b> {@link #size()} never exceeds the configured maximum size after any call
 *       returns. Inserting a new key into a full cache first purges expired entries and only then
 *       evicts a live entry chosen by the eviction policy ({@link RemovalCause#EVICTED}).
 *   <li><b>Replace:</b> {@code put} on an existing key replaces the value, resets the TTL (to the
 *       given one, or the default), counts as an access for the policy, fires {@link
 *       RemovalCause#REPLACED} with the old value and never evicts.
 *   <li><b>Expiry:</b> an expired entry is never returned. {@code get} on an expired entry returns
 *       empty, removes the entry, records one miss and one expiration and fires {@link
 *       RemovalCause#EXPIRED}. A background sweeper also removes expired entries periodically.
 *   <li><b>Independence:</b> TTL is enforced by a separate expiry index; eviction policies never
 *       read time or TTL, so expiry behaves identically under every policy.
 * </ul>
 *
 * <h2>Null handling</h2>
 *
 * <p>Null keys, values, durations and functions are rejected with {@link NullPointerException}.
 * Absence is expressed with {@link Optional}.
 *
 * <h2>Thread safety</h2>
 *
 * <p>All methods are safe to call concurrently. User code (removal listeners, access observers and
 * loaders) is never invoked while an internal lock is held, so it may call back into the cache.
 *
 * <h2>Complexity</h2>
 *
 * <p>{@code get}, {@code remove} and {@code put} run in O(1) for every policy, plus O(log n) to
 * schedule a TTL. {@link #switchPolicy(PolicyType)} runs in O(n log n) once per switch.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public interface Cache<K, V> extends AutoCloseable {

  /**
   * Returns the value mapped to {@code key}, if present and not expired.
   *
   * <p>A hit counts as an access for the eviction policy. A miss (absent or expired key) records
   * one miss; an expired entry is removed and reported as {@link RemovalCause#EXPIRED}. Access
   * observers are notified after the lookup completes.
   *
   * <p>Complexity: O(1).
   *
   * @param key the key to look up; must not be null
   * @return the live value, or {@link Optional#empty()} if absent or expired
   * @throws NullPointerException if {@code key} is null
   */
  Optional<V> get(K key);

  /**
   * Maps {@code key} to {@code value} using the default TTL, or no expiry if none is configured.
   *
   * <p>If the key already exists, the value is replaced, the TTL is reset, the entry counts as
   * accessed and {@link RemovalCause#REPLACED} is fired with the old value; nothing is evicted. If
   * the key is new and the cache is full, expired entries are purged first, then the policy's
   * victim is evicted.
   *
   * <p>Complexity: O(1), plus O(log n) when the entry has a TTL.
   *
   * @param key the key; must not be null
   * @param value the value; must not be null
   * @throws NullPointerException if {@code key} or {@code value} is null
   */
  void put(K key, V value);

  /**
   * Maps {@code key} to {@code value} with a per-entry TTL that overrides the default TTL.
   *
   * <p>Behaves like {@link #put(Object, Object)} otherwise. The entry expires once {@code ttl} has
   * elapsed on the cache's {@link Ticker}, regardless of the eviction policy.
   *
   * <p>Complexity: O(1) plus O(log n) to schedule the expiry.
   *
   * @param key the key; must not be null
   * @param value the value; must not be null
   * @param ttl the time-to-live; must not be null and must be positive
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if {@code ttl} is zero or negative
   */
  void put(K key, V value, Duration ttl);

  /**
   * Returns the live value for {@code key}, loading and caching it on a miss.
   *
   * <p>Loading is single-flight: when many threads miss on the same key at once, the loader runs
   * exactly once and every caller receives the same value. The loader runs outside any cache lock.
   * Each call that misses records one miss; a successful load records a load success and its load
   * time and then stores the value as {@link #put(Object, Object)} does. A failed load is not
   * cached.
   *
   * <p>Complexity: O(1) on a hit; on a miss, the cost of the loader plus a {@code put}.
   *
   * @param key the key; must not be null
   * @param loader computes the value on a miss; must not be null and must not return null
   * @return the cached or freshly loaded value, never null
   * @throws NullPointerException if {@code key} or {@code loader} is null
   * @throws CacheLoadException if the loader throws or returns null; the cause is preserved
   */
  V getOrLoad(K key, Function<? super K, ? extends V> loader);

  /**
   * Removes the entry for {@code key}, if present.
   *
   * <p>A removed live entry is reported as {@link RemovalCause#EXPLICIT}. An entry that had already
   * expired is reported as {@link RemovalCause#EXPIRED} and this method returns {@code false}.
   *
   * <p>Complexity: O(1).
   *
   * @param key the key; must not be null
   * @return {@code true} if a live entry was removed
   * @throws NullPointerException if {@code key} is null
   */
  boolean remove(K key);

  /**
   * Returns the time left before the entry for {@code key} expires.
   *
   * <p>This is a read-only query: it does not count as an access and does not change statistics.
   *
   * <p>Complexity: O(1).
   *
   * @param key the key; must not be null
   * @return the remaining TTL, or empty if the key is missing, expired or has no TTL
   * @throws NullPointerException if {@code key} is null
   */
  Optional<Duration> ttlRemaining(K key);

  /**
   * Returns the number of entries currently held.
   *
   * <p>The count may include entries that expired but have not been swept yet; the background
   * sweeper removes them within one sweep interval.
   *
   * <p>Complexity: O(1) for a single-lock cache; O(segments) for a segmented cache.
   *
   * @return the number of entries, never more than the maximum size
   */
  int size();

  /**
   * Removes every entry. Each removal is reported as {@link RemovalCause#EXPLICIT}.
   *
   * <p>Statistics are not reset. Complexity: O(n).
   */
  void clear();

  /**
   * Returns a point-in-time snapshot of the cumulative statistics.
   *
   * <p>Counters are updated without a global lock, so a snapshot taken while other threads are
   * active may be slightly inconsistent between counters; each counter is individually accurate.
   *
   * <p>Complexity: O(1) (O(segments) for a segmented cache).
   *
   * @return the statistics, never null
   */
  CacheStats stats();

  /**
   * Returns the eviction policy currently in effect.
   *
   * @return the current policy, never null
   */
  PolicyType policyType();

  /**
   * Switches the eviction policy at runtime without losing entries.
   *
   * <p>Entries keep their values and TTLs. The new policy is rebuilt from the current entries in
   * recency order; frequency-based policies keep each entry's existing access frequency. Switching
   * to the current policy is a no-op.
   *
   * <p>Complexity: O(n log n), once per switch, performed under the cache lock.
   *
   * @param newPolicy the policy to switch to; must not be null
   * @throws NullPointerException if {@code newPolicy} is null
   */
  void switchPolicy(PolicyType newPolicy);

  /**
   * Returns a view of the policy's internal order, for visualisation.
   *
   * <p>For {@link PolicyType#LRU} the entries are most-recent-first with frequency 0; for {@link
   * PolicyType#LFU} and {@link PolicyType#LFU_DECAY} they are highest-frequency-first.
   *
   * <p>Complexity: O(limit).
   *
   * @param limit the maximum number of entries to return; must not be negative
   * @return the snapshot, never null
   * @throws IllegalArgumentException if {@code limit} is negative
   */
  PolicySnapshot<K> policySnapshot(int limit);

  /**
   * Returns up to {@code limit} entries with their access frequency and remaining TTL, in the
   * policy's order (see {@link #policySnapshot(int)}). Expired entries are skipped.
   *
   * <p>This is a read-only query: it does not count as an access. Complexity: O(limit).
   *
   * @param limit the maximum number of entries to return; must not be negative
   * @return the entries, never null
   * @throws IllegalArgumentException if {@code limit} is negative
   */
  List<EntryView<K>> entries(int limit);

  /**
   * Returns the cache's name, used in thread names, metrics and logs.
   *
   * @return the name, never null
   */
  String name();

  /**
   * Stops the background sweeper thread. Entries remain readable; calling {@code close} more than
   * once has no further effect.
   */
  @Override
  void close();
}

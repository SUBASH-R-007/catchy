package io.cachelab.internal;

import static java.util.Objects.requireNonNull;

import io.cachelab.Cache;
import io.cachelab.CacheStats;
import io.cachelab.EntryView;
import io.cachelab.PolicySnapshot;
import io.cachelab.PolicyType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * A lock-striped cache (SPEC 4.8, ADR-001): {@code N} independent {@link BoundedCache} segments,
 * chosen by {@code spread(hash) & (N - 1)} with {@code spread(h) = h ^ (h >>> 16)}. Threads that
 * touch different segments never contend.
 *
 * <p><b>Capacity is exact:</b> the first {@code maximumSize % N} segments get one extra slot, so
 * the segment capacities add up to {@code maximumSize}.
 *
 * <p><b>Ordering trade-off:</b> eviction order is exact <em>within</em> a segment and approximate
 * <em>globally</em> — the victim is the policy's choice within the segment that receives the new
 * key. Snapshots are merged into one global view: LRU by a shared access-tick source, LFU by
 * frequency (then recency).
 *
 * <p>Thread-safety: all public methods are thread-safe. Complexity: per-key operations as {@link
 * BoundedCache}; {@link #size()}, {@link #stats()}, {@link #clear()} and {@link
 * #switchPolicy(PolicyType)} visit every segment.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class SegmentedCache<K, V> implements Cache<K, V> {

  private final String name;
  private final int maximumSize;
  private final List<BoundedCache<K, V>> segments;
  private final int mask;
  private volatile PolicyType policyType;
  private Sweeper sweeper;

  private SegmentedCache(CacheSettings<K, V> settings) {
    int n = settings.concurrencyLevel();
    if (n < 2 || Integer.bitCount(n) != 1 || n > settings.maximumSize()) {
      throw new IllegalArgumentException(
          "concurrencyLevel must be a power of two between 2 and maximumSize, but was " + n);
    }
    this.name = settings.name();
    this.maximumSize = settings.maximumSize();
    this.mask = n - 1;
    this.policyType = settings.policy();
    AtomicLong sharedTicks = new AtomicLong();
    int base = maximumSize / n;
    int extra = maximumSize % n;
    List<BoundedCache<K, V>> built = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      int capacity = base + (i < extra ? 1 : 0);
      built.add(new BoundedCache<>(settings.forSegment(name + "#" + i, capacity), sharedTicks));
    }
    this.segments = List.copyOf(built);
  }

  /**
   * Creates a segmented cache and starts its one shared sweeper.
   *
   * @param settings the validated configuration; {@code concurrencyLevel} is the segment count
   * @param <K> the key type
   * @param <V> the value type
   * @return the running cache
   */
  public static <K, V> SegmentedCache<K, V> create(CacheSettings<K, V> settings) {
    SegmentedCache<K, V> cache = new SegmentedCache<>(settings);
    cache.sweeper =
        new Sweeper(
            settings.name(),
            settings.sweepIntervalNanos(),
            () -> cache.segments.forEach(BoundedCache::sweep));
    return cache;
  }

  private BoundedCache<K, V> segmentFor(Object key) {
    int h = requireNonNull(key, "key").hashCode();
    return segments.get((h ^ (h >>> 16)) & mask);
  }

  @Override
  public Optional<V> get(K key) {
    return segmentFor(key).get(key);
  }

  @Override
  public void put(K key, V value) {
    segmentFor(key).put(key, value);
  }

  @Override
  public void put(K key, V value, Duration ttl) {
    segmentFor(key).put(key, value, ttl);
  }

  @Override
  public V getOrLoad(K key, Function<? super K, ? extends V> loader) {
    return segmentFor(key).getOrLoad(key, loader);
  }

  @Override
  public boolean remove(K key) {
    return segmentFor(key).remove(key);
  }

  @Override
  public Optional<Duration> ttlRemaining(K key) {
    return segmentFor(key).ttlRemaining(key);
  }

  @Override
  public int size() {
    int size = 0;
    for (BoundedCache<K, V> s : segments) {
      size += s.size();
    }
    return size;
  }

  @Override
  public void clear() {
    segments.forEach(BoundedCache::clear);
  }

  @Override
  public CacheStats stats() {
    CacheStats total = CacheStats.empty();
    for (BoundedCache<K, V> s : segments) {
      total = total.plus(s.stats());
    }
    return total;
  }

  @Override
  public PolicyType policyType() {
    return policyType;
  }

  /** Switches each segment in turn; each segment's switch is atomic under its own lock. */
  @Override
  public void switchPolicy(PolicyType newPolicy) {
    requireNonNull(newPolicy, "newPolicy");
    segments.forEach(s -> s.switchPolicy(newPolicy));
    policyType = newPolicy;
  }

  @Override
  public PolicySnapshot<K> policySnapshot(int limit) {
    List<BoundedCache.Ranked<PolicySnapshot.Entry<K>>> merged = new ArrayList<>();
    for (BoundedCache<K, V> s : segments) {
      merged.addAll(s.rankedSnapshot(limit));
    }
    merged.sort(globalOrder());
    List<PolicySnapshot.Entry<K>> top =
        merged.stream().limit(limit).map(BoundedCache.Ranked::item).toList();
    return new PolicySnapshot<>(policyType, top);
  }

  @Override
  public List<EntryView<K>> entries(int limit) {
    List<BoundedCache.Ranked<EntryView<K>>> merged = new ArrayList<>();
    for (BoundedCache<K, V> s : segments) {
      merged.addAll(s.rankedEntries(limit));
    }
    merged.sort(globalOrder());
    return merged.stream().limit(limit).map(BoundedCache.Ranked::item).toList();
  }

  /** LRU: most recent first. LFU / LFU_DECAY: highest frequency first, then most recent. */
  private <T> Comparator<BoundedCache.Ranked<T>> globalOrder() {
    Comparator<BoundedCache.Ranked<T>> byRecency =
        Comparator.comparingLong((BoundedCache.Ranked<T> r) -> r.lastAccess()).reversed();
    if (policyType == PolicyType.LRU) {
      return byRecency;
    }
    return Comparator.comparingLong((BoundedCache.Ranked<T> r) -> r.frequency())
        .reversed()
        .thenComparing(byRecency);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public void close() {
    if (sweeper != null) {
      sweeper.close();
    }
  }

  /**
   * Verifies every segment's structure and that the total size respects the maximum.
   *
   * @throws IllegalStateException describing the first violation
   */
  public void checkInvariants() {
    int total = 0;
    int capacity = 0;
    for (BoundedCache<K, V> s : segments) {
      s.checkInvariants();
      total += s.size();
      capacity += s.maximumSize();
    }
    if (capacity != maximumSize) {
      throw new IllegalStateException(
          name + ": segment capacities sum to " + capacity + ", not " + maximumSize);
    }
    if (total > maximumSize) {
      throw new IllegalStateException(name + ": size " + total + " exceeds " + maximumSize);
    }
  }

  /**
   * Returns the segments, for diagnostics.
   *
   * @return the segments, in index order
   */
  public List<BoundedCache<K, V>> segments() {
    return segments;
  }
}

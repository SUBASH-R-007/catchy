package io.cachelab.internal;

import static java.util.Objects.requireNonNull;

import io.cachelab.AccessObserver;
import io.cachelab.Cache;
import io.cachelab.CacheLoadException;
import io.cachelab.CacheStats;
import io.cachelab.EntryView;
import io.cachelab.PolicySnapshot;
import io.cachelab.PolicyType;
import io.cachelab.RemovalCause;
import io.cachelab.RemovalListener;
import io.cachelab.Ticker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * The single-lock cache engine (SPEC 4.4): a {@link HashMap} of intrusive {@link Node}s, one
 * non-fair {@link ReentrantLock}, an {@link EvictionPolicy} for victim choice and a separate {@link
 * ExpiryIndex} for TTL (ADR-002).
 *
 * <p>Locking rules (SPEC 4.6): the ticker is read before the lock is taken; removals are collected
 * under the lock and dispatched to the listener after it is released; access observers, loaders and
 * listeners never run while the lock is held, so they may call back into the cache.
 *
 * <p>See {@link Cache} for the public semantics. Thread-safety: all public methods are thread-safe.
 * Complexity: get/put/remove O(1) plus O(log n) to schedule a TTL; {@link
 * #switchPolicy(PolicyType)} O(n log n).
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public final class BoundedCache<K, V> implements Cache<K, V> {

  /** Expired entries purged before a live entry is evicted to make room (SPEC 4.4). */
  static final int PURGE_BEFORE_EVICT = 16;

  /** Expired entries purged per background sweep (SPEC 4.5). */
  static final int PURGE_PER_SWEEP = 256;

  /** TTLs are capped at 2^62 ns (~146 years) so deadline arithmetic never overflows. */
  static final long MAX_TTL_NANOS = Long.MAX_VALUE >> 1;

  private static final System.Logger LOG = System.getLogger(BoundedCache.class.getName());

  private final String name;
  private final int maximumSize;
  private final long defaultTtlNanos;
  private final long decayIntervalNanos;
  private final Ticker ticker;
  private final RemovalListener<? super K, ? super V> listener;
  private final Executor removalExecutor;
  private final List<AccessObserver<? super K>> observers;
  private final AtomicLong accessTicks;

  private final ReentrantLock lock = new ReentrantLock();
  private final HashMap<K, Node<K, V>> map;
  private final ExpiryIndex<K, V> expiry = new ExpiryIndex<>();
  private final StatsRecorder stats = new StatsRecorder();
  private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

  private EvictionPolicy<K, V> policy; // guarded by lock
  private volatile PolicyType policyType;
  private long lastDecay; // guarded by lock
  private Sweeper sweeper;

  /**
   * Creates a cache without a background sweeper. Use {@link #create(CacheSettings)} for a
   * standalone cache; a segmented cache creates its segments with this constructor and sweeps them
   * itself.
   *
   * @param settings the validated configuration
   * @param accessTicks the monotonic access-tick source (shared across segments)
   */
  public BoundedCache(CacheSettings<K, V> settings, AtomicLong accessTicks) {
    this.name = settings.name();
    this.maximumSize = settings.maximumSize();
    this.defaultTtlNanos =
        settings.defaultTtlNanos() == CacheSettings.NO_TTL
            ? CacheSettings.NO_TTL
            : Math.min(settings.defaultTtlNanos(), MAX_TTL_NANOS);
    this.decayIntervalNanos = settings.decayIntervalNanos();
    this.ticker = settings.ticker();
    this.listener = settings.removalListener();
    this.removalExecutor = settings.removalExecutor();
    this.observers = settings.accessObservers();
    this.accessTicks = requireNonNull(accessTicks, "accessTicks");
    this.map = HashMap.newHashMap(Math.min(maximumSize, 1 << 16));
    this.policy = Policies.create(settings.policy());
    this.policyType = settings.policy();
    this.lastDecay = ticker.read();
  }

  /**
   * Creates a standalone cache and starts its background sweeper.
   *
   * @param settings the validated configuration
   * @param <K> the key type
   * @param <V> the value type
   * @return the running cache
   */
  public static <K, V> BoundedCache<K, V> create(CacheSettings<K, V> settings) {
    BoundedCache<K, V> cache = new BoundedCache<>(settings, new AtomicLong());
    cache.sweeper = new Sweeper(settings.name(), settings.sweepIntervalNanos(), cache::sweep);
    return cache;
  }

  @Override
  public Optional<V> get(K key) {
    requireNonNull(key, "key");
    long now = ticker.read();
    List<Removal<K, V>> removals = null;
    V value = null;
    lock.lock();
    try {
      Node<K, V> n = map.get(key);
      if (n != null && n.isExpired(now)) {
        removals = new ArrayList<>(1);
        expireLocked(n, removals);
      } else if (n != null) {
        touchLocked(n);
        value = n.value;
      }
    } finally {
      lock.unlock();
    }
    boolean hit = value != null;
    if (hit) {
      stats.hit();
    } else {
      stats.miss();
    }
    dispatch(removals);
    notifyObservers(key, hit);
    return Optional.ofNullable(value);
  }

  @Override
  public void put(K key, V value) {
    putWithTtl(key, value, defaultTtlNanos);
  }

  @Override
  public void put(K key, V value, Duration ttl) {
    requireNonNull(ttl, "ttl");
    if (ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("ttl must be positive, but was " + ttl);
    }
    putWithTtl(key, value, toCappedNanos(ttl));
  }

  private void putWithTtl(K key, V value, long ttlNanos) {
    requireNonNull(key, "key");
    requireNonNull(value, "value");
    long now = ticker.read();
    List<Removal<K, V>> removals = new ArrayList<>(2);
    lock.lock();
    try {
      putLocked(key, value, ttlNanos, now, removals);
    } finally {
      lock.unlock();
    }
    stats.put();
    dispatch(removals);
  }

  private void putLocked(K key, V value, long ttlNanos, long now, List<Removal<K, V>> removals) {
    Node<K, V> n = map.get(key);
    if (n != null && n.isExpired(now)) {
      expireLocked(n, removals); // a dead entry is not "replaced"; the key is inserted afresh
      n = null;
    }
    if (n != null) {
      V old = n.value;
      n.value = value;
      n.version++;
      n.expiresAt = deadline(now, ttlNanos);
      expiry.schedule(n);
      touchLocked(n);
      removals.add(new Removal<>(key, old, RemovalCause.REPLACED));
    } else {
      makeRoomLocked(now, removals);
      n = new Node<>(key, value);
      n.version = 1;
      n.expiresAt = deadline(now, ttlNanos);
      n.lastAccess = accessTicks.incrementAndGet();
      map.put(key, n);
      policy.onInsert(n);
      expiry.schedule(n);
    }
    if (expiry.needsCompaction(map.size())) {
      expiry.compact(map.values());
    }
  }

  /** Purges expired entries first; evicts the policy's victim only if still full (SPEC 4.4). */
  private void makeRoomLocked(long now, List<Removal<K, V>> removals) {
    if (map.size() < maximumSize) {
      return;
    }
    expiry.purgeExpired(now, PURGE_BEFORE_EVICT, n -> expireLocked(n, removals));
    if (map.size() < maximumSize) {
      return;
    }
    Node<K, V> victim = policy.pollVictim();
    map.remove(victim.key);
    victim.removed = true;
    stats.eviction();
    removals.add(new Removal<>(victim.key, victim.value, RemovalCause.EVICTED));
  }

  @Override
  public V getOrLoad(K key, Function<? super K, ? extends V> loader) {
    requireNonNull(key, "key");
    requireNonNull(loader, "loader");
    Optional<V> cached = get(key); // records exactly one hit or miss for this call
    if (cached.isPresent()) {
      return cached.get();
    }
    CompletableFuture<V> mine = new CompletableFuture<>();
    CompletableFuture<V> existing = inFlight.putIfAbsent(key, mine);
    if (existing != null) {
      return await(key, existing);
    }
    try {
      return loadAndPublish(key, loader, mine);
    } finally {
      inFlight.remove(key, mine);
    }
  }

  /** Runs the loader outside the lock (the calling thread won the single-flight race). */
  private V loadAndPublish(K key, Function<? super K, ? extends V> loader, CompletableFuture<V> f) {
    V raced = peekLive(key); // a load that finished just before we registered: reuse it
    if (raced != null) {
      f.complete(raced);
      return raced;
    }
    long start = System.nanoTime();
    try {
      V value = loader.apply(key);
      if (value == null) {
        throw new CacheLoadException("loader returned null for key " + key, null);
      }
      put(key, value);
      stats.loadSuccess(System.nanoTime() - start);
      f.complete(value);
      return value;
    } catch (RuntimeException | Error e) {
      stats.loadFailure(System.nanoTime() - start);
      CacheLoadException failure =
          e instanceof CacheLoadException c
              ? c
              : new CacheLoadException("loader failed for key " + key, e);
      f.completeExceptionally(failure);
      throw failure;
    }
  }

  private V await(K key, CompletableFuture<V> inFlightLoad) {
    try {
      return inFlightLoad.join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof CacheLoadException c) {
        throw new CacheLoadException(c.getMessage(), c.getCause());
      }
      throw new CacheLoadException("loader failed for key " + key, cause);
    }
  }

  /** Returns the live value without counting an access or touching statistics. */
  private V peekLive(K key) {
    long now = ticker.read();
    lock.lock();
    try {
      Node<K, V> n = map.get(key);
      return n == null || n.isExpired(now) ? null : n.value;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean remove(K key) {
    requireNonNull(key, "key");
    long now = ticker.read();
    List<Removal<K, V>> removals = new ArrayList<>(1);
    boolean removedLive = false;
    lock.lock();
    try {
      Node<K, V> n = map.get(key);
      if (n != null && n.isExpired(now)) {
        expireLocked(n, removals);
      } else if (n != null) {
        unlinkLocked(n);
        removals.add(new Removal<>(key, n.value, RemovalCause.EXPLICIT));
        removedLive = true;
      }
    } finally {
      lock.unlock();
    }
    dispatch(removals);
    return removedLive;
  }

  @Override
  public Optional<Duration> ttlRemaining(K key) {
    requireNonNull(key, "key");
    long now = ticker.read();
    lock.lock();
    try {
      Node<K, V> n = map.get(key);
      return n == null ? Optional.empty() : remaining(n, now);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public int size() {
    lock.lock();
    try {
      return map.size();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void clear() {
    List<Removal<K, V>> removals;
    lock.lock();
    try {
      removals = new ArrayList<>(map.size());
      for (Node<K, V> n : map.values()) {
        n.removed = true;
        removals.add(new Removal<>(n.key, n.value, RemovalCause.EXPLICIT));
      }
      map.clear();
      expiry.clear();
      policy = Policies.create(policyType);
    } finally {
      lock.unlock();
    }
    dispatch(removals);
  }

  @Override
  public CacheStats stats() {
    return stats.snapshot();
  }

  @Override
  public PolicyType policyType() {
    return policyType;
  }

  @Override
  public void switchPolicy(PolicyType newPolicy) {
    requireNonNull(newPolicy, "newPolicy");
    lock.lock();
    try {
      if (newPolicy == policyType) {
        return;
      }
      List<Node<K, V>> nodes = new ArrayList<>(map.values());
      nodes.sort(Comparator.comparingLong(n -> n.lastAccess));
      EvictionPolicy<K, V> next = Policies.create(newPolicy);
      next.rebuildFrom(nodes);
      policy = next;
      policyType = newPolicy;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public PolicySnapshot<K> policySnapshot(int limit) {
    requireNonNegative(limit);
    lock.lock();
    try {
      return policy.snapshot(limit);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public List<EntryView<K>> entries(int limit) {
    requireNonNegative(limit);
    long now = ticker.read();
    lock.lock();
    try {
      List<EntryView<K>> views = new ArrayList<>(Math.min(limit, map.size()));
      int ask = limit;
      while (limit > 0) {
        PolicySnapshot<K> snapshot = policy.snapshot(ask);
        views.clear();
        for (PolicySnapshot.Entry<K> e : snapshot.entries()) {
          Node<K, V> n = map.get(e.key());
          if (n != null && !n.isExpired(now)) {
            views.add(new EntryView<>(e.key(), e.frequency(), remaining(n, now)));
            if (views.size() == limit) {
              return views;
            }
          }
        }
        if (snapshot.entries().size() < ask) {
          break; // the policy has no more entries; expired ones were skipped
        }
        ask = (int) Math.min(Integer.MAX_VALUE, ask * 2L);
      }
      return views;
    } finally {
      lock.unlock();
    }
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
   * One background sweep: purges up to 256 expired entries and, under {@link PolicyType#LFU_DECAY},
   * decays frequencies once the decay interval has elapsed. The engine owns this time check so the
   * policy stays time-free. Removal events are dispatched after the lock is released.
   */
  public void sweep() {
    long now = ticker.read();
    List<Removal<K, V>> removals = new ArrayList<>();
    lock.lock();
    try {
      expiry.purgeExpired(now, PURGE_PER_SWEEP, n -> expireLocked(n, removals));
      if (policyType == PolicyType.LFU_DECAY && now - lastDecay >= decayIntervalNanos) {
        policy.decay();
        lastDecay = now;
      }
    } finally {
      lock.unlock();
    }
    dispatch(removals);
  }

  /**
   * Verifies the structure: the policy's own invariants, that the policy tracks exactly the mapped
   * nodes, and that the size bound holds. Used by tests and the stress harness.
   *
   * @throws IllegalStateException describing the first violation
   */
  public void checkInvariants() {
    lock.lock();
    try {
      policy.checkInvariants();
      if (policy.size() != map.size()) {
        throw new IllegalStateException(
            name + ": policy tracks " + policy.size() + " entries but the map holds " + map.size());
      }
      if (map.size() > maximumSize) {
        throw new IllegalStateException(
            name + ": size " + map.size() + " exceeds maximumSize " + maximumSize);
      }
      for (Node<K, V> n : map.values()) {
        if (n.removed) {
          throw new IllegalStateException(name + ": mapped node " + n + " is marked removed");
        }
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns the configured capacity.
   *
   * @return the maximum number of entries
   */
  public int maximumSize() {
    return maximumSize;
  }

  private void touchLocked(Node<K, V> n) {
    n.lastAccess = accessTicks.incrementAndGet();
    policy.onAccess(n);
  }

  private void expireLocked(Node<K, V> n, List<Removal<K, V>> removals) {
    unlinkLocked(n);
    stats.expiration();
    removals.add(new Removal<>(n.key, n.value, RemovalCause.EXPIRED));
  }

  private void unlinkLocked(Node<K, V> n) {
    map.remove(n.key);
    policy.onRemove(n);
    n.removed = true;
  }

  private void dispatch(List<Removal<K, V>> removals) {
    if (listener == null || removals == null || removals.isEmpty()) {
      return;
    }
    if (removalExecutor == null) {
      notifyListener(removals);
      return;
    }
    try {
      removalExecutor.execute(() -> notifyListener(removals));
    } catch (RejectedExecutionException e) {
      LOG.log(System.Logger.Level.WARNING, "Removal executor rejected events for " + name, e);
    }
  }

  private void notifyListener(List<Removal<K, V>> removals) {
    for (Removal<K, V> r : removals) {
      try {
        listener.onRemoval(r.key(), r.value(), r.cause());
      } catch (Exception e) {
        LOG.log(System.Logger.Level.WARNING, "Removal listener failed in cache " + name, e);
      }
    }
  }

  private void notifyObservers(K key, boolean hit) {
    for (AccessObserver<? super K> observer : observers) {
      try {
        observer.onAccess(key, hit);
      } catch (Exception e) {
        LOG.log(System.Logger.Level.WARNING, "Access observer failed in cache " + name, e);
      }
    }
  }

  private static Optional<Duration> remaining(Node<?, ?> n, long now) {
    if (n.expiresAt == Node.NEVER || n.isExpired(now)) {
      return Optional.empty();
    }
    return Optional.of(Duration.ofNanos(n.expiresAt - now));
  }

  private static long deadline(long now, long ttlNanos) {
    if (ttlNanos == CacheSettings.NO_TTL) {
      return Node.NEVER;
    }
    long at = now + ttlNanos; // may wrap; comparisons use subtraction
    return at == Node.NEVER ? at - 1 : at;
  }

  static long toCappedNanos(Duration ttl) {
    return ttl.compareTo(Duration.ofNanos(MAX_TTL_NANOS)) >= 0 ? MAX_TTL_NANOS : ttl.toNanos();
  }

  private static void requireNonNegative(int limit) {
    if (limit < 0) {
      throw new IllegalArgumentException("limit must not be negative, but was " + limit);
    }
  }
}

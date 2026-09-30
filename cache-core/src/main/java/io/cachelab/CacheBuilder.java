package io.cachelab;

import io.cachelab.internal.BoundedCache;
import io.cachelab.internal.CacheSettings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fluent builder for {@link Cache} instances.
 *
 * <pre>{@code
 * Cache<String, Drug> formulary = CacheBuilder.<String, Drug>newBuilder()
 *     .name("formulary")
 *     .maximumSize(10_000)
 *     .evictionPolicy(PolicyType.LFU)
 *     .defaultTtl(Duration.ofMinutes(10))
 *     .build();
 * }</pre>
 *
 * <p>Every option is validated when it is set, with a message naming the option and the rejected
 * value, for example {@code "maximumSize must be at least 1, but was 0"}. Rules that relate two
 * options (such as {@code concurrencyLevel <= maximumSize}) are validated by {@link #build()}.
 * Setting an option twice keeps the last value, except {@link #accessObserver(AccessObserver)},
 * which accumulates.
 *
 * <p>Null handling: every setter rejects null with {@link NullPointerException}. Thread-safety: a
 * builder is not thread-safe; configure it on one thread. The caches it builds are thread-safe.
 * Complexity: every method is O(1), except {@code build()}, which is O(maximum size / segment
 * count) for a segmented cache.
 *
 * @param <K> the key type of the caches to build
 * @param <V> the value type of the caches to build
 */
public final class CacheBuilder<K, V> {

  /** Default interval between background expiry sweeps. */
  public static final Duration DEFAULT_SWEEP_INTERVAL = Duration.ofMillis(100);

  /** Smallest allowed sweep interval. */
  public static final Duration MINIMUM_SWEEP_INTERVAL = Duration.ofMillis(10);

  /** Default interval between frequency decays for {@link PolicyType#LFU_DECAY}. */
  public static final Duration DEFAULT_DECAY_INTERVAL = Duration.ofSeconds(10);

  private static final AtomicInteger NAME_SEQUENCE = new AtomicInteger();
  private static final int UNSET = -1;

  private String name;
  private int maximumSize = UNSET;
  private PolicyType evictionPolicy = PolicyType.LRU;
  private Duration defaultTtl;
  private int concurrencyLevel = 1;
  private RemovalListener<? super K, ? super V> removalListener;
  private Executor removalExecutor;
  private final List<AccessObserver<? super K>> accessObservers = new ArrayList<>();
  private Ticker ticker = Ticker.system();
  private Duration sweepInterval = DEFAULT_SWEEP_INTERVAL;
  private Duration decayInterval = DEFAULT_DECAY_INTERVAL;

  private CacheBuilder() {}

  /**
   * Creates a builder with default settings: LRU eviction, no default TTL, a single lock, the
   * system ticker, a 100 ms sweep interval and a 10 s decay interval. {@link #maximumSize(int)}
   * must be set before {@link #build()}.
   *
   * @param <K> the key type
   * @param <V> the value type
   * @return a new builder, never null
   */
  public static <K, V> CacheBuilder<K, V> newBuilder() {
    return new CacheBuilder<>();
  }

  /**
   * Sets the cache's name, used in thread names, metrics and logs. Defaults to {@code "cache-N"}.
   *
   * @param name the name; must not be null or blank
   * @return this builder
   * @throws NullPointerException if {@code name} is null
   * @throws IllegalArgumentException if {@code name} is blank
   */
  public CacheBuilder<K, V> name(String name) {
    Objects.requireNonNull(name, "name");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank, but was \"" + name + "\"");
    }
    this.name = name;
    return this;
  }

  /**
   * Sets the maximum number of entries. Required.
   *
   * @param maximumSize the capacity; must be at least 1
   * @return this builder
   * @throws IllegalArgumentException if {@code maximumSize} is less than 1
   */
  public CacheBuilder<K, V> maximumSize(int maximumSize) {
    if (maximumSize < 1) {
      throw new IllegalArgumentException("maximumSize must be at least 1, but was " + maximumSize);
    }
    this.maximumSize = maximumSize;
    return this;
  }

  /**
   * Sets the eviction policy. Defaults to {@link PolicyType#LRU}. It can be changed later with
   * {@link Cache#switchPolicy(PolicyType)}.
   *
   * @param policy the policy; must not be null
   * @return this builder
   * @throws NullPointerException if {@code policy} is null
   */
  public CacheBuilder<K, V> evictionPolicy(PolicyType policy) {
    this.evictionPolicy = Objects.requireNonNull(policy, "evictionPolicy");
    return this;
  }

  /**
   * Sets the TTL applied by {@link Cache#put(Object, Object)}. Without it, such entries never
   * expire. A per-entry TTL passed to {@link Cache#put(Object, Object, Duration)} always wins.
   *
   * @param ttl the default time-to-live; must not be null and must be positive
   * @return this builder
   * @throws NullPointerException if {@code ttl} is null
   * @throws IllegalArgumentException if {@code ttl} is zero or negative
   */
  public CacheBuilder<K, V> defaultTtl(Duration ttl) {
    Objects.requireNonNull(ttl, "defaultTtl");
    if (ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("defaultTtl must be positive, but was " + format(ttl));
    }
    this.defaultTtl = ttl;
    return this;
  }

  /**
   * Sets the number of independently locked segments. {@code 1} (the default) builds a single-lock
   * cache with exact global ordering; a larger value builds a segmented cache whose ordering is
   * exact within each segment and approximate across segments.
   *
   * @param concurrencyLevel a power of two, at least 1, and not more than the maximum size
   * @return this builder
   * @throws IllegalArgumentException if {@code concurrencyLevel} is not a power of two of at least
   *     1
   */
  public CacheBuilder<K, V> concurrencyLevel(int concurrencyLevel) {
    if (concurrencyLevel < 1 || Integer.bitCount(concurrencyLevel) != 1) {
      throw new IllegalArgumentException(
          "concurrencyLevel must be a power of two of at least 1, but was " + concurrencyLevel);
    }
    this.concurrencyLevel = concurrencyLevel;
    return this;
  }

  /**
   * Sets the listener notified after each entry is removed. See {@link RemovalListener} for when
   * and where it is invoked.
   *
   * @param listener the listener; must not be null
   * @return this builder
   * @throws NullPointerException if {@code listener} is null
   */
  public CacheBuilder<K, V> removalListener(RemovalListener<? super K, ? super V> listener) {
    this.removalListener = Objects.requireNonNull(listener, "removalListener");
    return this;
  }

  /**
   * Sets the executor that runs removal listeners. Without it, listeners run on the thread that
   * caused the removal, after the cache lock is released.
   *
   * @param executor the executor; must not be null
   * @return this builder
   * @throws NullPointerException if {@code executor} is null
   */
  public CacheBuilder<K, V> removalExecutor(Executor executor) {
    this.removalExecutor = Objects.requireNonNull(executor, "removalExecutor");
    return this;
  }

  /**
   * Adds an observer notified after every lookup. May be called several times; observers are
   * notified in the order they were added.
   *
   * @param observer the observer; must not be null
   * @return this builder
   * @throws NullPointerException if {@code observer} is null
   */
  public CacheBuilder<K, V> accessObserver(AccessObserver<? super K> observer) {
    accessObservers.add(Objects.requireNonNull(observer, "accessObserver"));
    return this;
  }

  /**
   * Sets the time source used for TTL decisions. Defaults to {@link Ticker#system()}.
   *
   * @param ticker the ticker; must not be null
   * @return this builder
   * @throws NullPointerException if {@code ticker} is null
   */
  public CacheBuilder<K, V> ticker(Ticker ticker) {
    this.ticker = Objects.requireNonNull(ticker, "ticker");
    return this;
  }

  /**
   * Sets how often the background sweeper purges expired entries. Defaults to 100 ms.
   *
   * @param interval the sweep interval; must not be null and must be at least 10 ms
   * @return this builder
   * @throws NullPointerException if {@code interval} is null
   * @throws IllegalArgumentException if {@code interval} is shorter than 10 ms
   */
  public CacheBuilder<K, V> sweepInterval(Duration interval) {
    Objects.requireNonNull(interval, "sweepInterval");
    if (interval.compareTo(MINIMUM_SWEEP_INTERVAL) < 0) {
      throw new IllegalArgumentException(
          "sweepInterval must be at least 10 ms, but was " + format(interval));
    }
    this.sweepInterval = interval;
    return this;
  }

  /**
   * Sets how often {@link PolicyType#LFU_DECAY} halves all frequencies. Defaults to 10 s. Ignored
   * by the other policies. Decay is checked on each sweep, so its resolution is the sweep interval.
   *
   * @param interval the decay interval; must not be null and must be positive
   * @return this builder
   * @throws NullPointerException if {@code interval} is null
   * @throws IllegalArgumentException if {@code interval} is zero or negative
   */
  public CacheBuilder<K, V> decayInterval(Duration interval) {
    Objects.requireNonNull(interval, "decayInterval");
    if (interval.isZero() || interval.isNegative()) {
      throw new IllegalArgumentException(
          "decayInterval must be positive, but was " + format(interval));
    }
    this.decayInterval = interval;
    return this;
  }

  /**
   * Validates the combined configuration and builds a running cache: a single-lock cache for
   * concurrency level 1, a segmented cache otherwise. The cache starts a background sweeper thread;
   * call {@link Cache#close()} to stop it.
   *
   * @return a new, running cache
   * @throws IllegalStateException if {@link #maximumSize(int)} was not set, or if the concurrency
   *     level exceeds the maximum size
   */
  public Cache<K, V> build() {
    validate();
    CacheSettings<K, V> settings =
        new CacheSettings<>(
            resolveName(),
            maximumSize,
            evictionPolicy,
            defaultTtl == null ? CacheSettings.NO_TTL : saturatedNanos(defaultTtl),
            concurrencyLevel,
            removalListener,
            removalExecutor,
            accessObservers,
            ticker,
            saturatedNanos(sweepInterval),
            saturatedNanos(decayInterval));
    if (concurrencyLevel == 1) {
      return BoundedCache.create(settings);
    }
    throw new UnsupportedOperationException(
        "concurrencyLevel > 1 (SegmentedCache) arrives in Step 3");
  }

  private static long saturatedNanos(Duration duration) {
    try {
      return duration.toNanos();
    } catch (ArithmeticException tooLong) {
      return Long.MAX_VALUE;
    }
  }

  private void validate() {
    if (maximumSize == UNSET) {
      throw new IllegalStateException("maximumSize must be set before build()");
    }
    if (concurrencyLevel > maximumSize) {
      throw new IllegalStateException(
          "concurrencyLevel ("
              + concurrencyLevel
              + ") must not exceed maximumSize ("
              + maximumSize
              + ")");
    }
  }

  /**
   * Returns the configured name, or a freshly generated {@code "cache-N"} if none was set. Each
   * call without a configured name consumes one sequence number.
   */
  String resolveName() {
    return name != null ? name : "cache-" + NAME_SEQUENCE.incrementAndGet();
  }

  Optional<Duration> defaultTtl() {
    return Optional.ofNullable(defaultTtl);
  }

  int maximumSize() {
    return maximumSize;
  }

  PolicyType evictionPolicy() {
    return evictionPolicy;
  }

  int concurrencyLevel() {
    return concurrencyLevel;
  }

  Optional<RemovalListener<? super K, ? super V>> removalListener() {
    return Optional.ofNullable(removalListener);
  }

  Optional<Executor> removalExecutor() {
    return Optional.ofNullable(removalExecutor);
  }

  List<AccessObserver<? super K>> accessObservers() {
    return List.copyOf(accessObservers);
  }

  Ticker ticker() {
    return ticker;
  }

  Duration sweepInterval() {
    return sweepInterval;
  }

  Duration decayInterval() {
    return decayInterval;
  }

  private static String format(Duration duration) {
    try {
      long nanos = duration.toNanos();
      return nanos % 1_000_000 == 0 ? nanos / 1_000_000 + " ms" : nanos + " ns";
    } catch (ArithmeticException tooLargeForNanos) {
      return duration.toString();
    }
  }
}

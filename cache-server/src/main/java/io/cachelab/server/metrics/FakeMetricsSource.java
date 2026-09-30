package io.cachelab.server.metrics;

import io.cachelab.PolicyType;
import io.cachelab.RemovalCause;
import io.cachelab.server.workload.Pattern;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * Seeded fake metrics for developing the dashboard before the engine exists (Steps 1-2, enabled by
 * {@code cachelab.metrics.fake=true}).
 *
 * <p>Group {@code demo} holds {@code lru-A} (LRU) and {@code lfu-A} (LFU), capacity 1,000, at about
 * 5,000 ops/s. Per-tick hit rates follow bounded mean-reverting random walks (LRU around 0.78, LFU
 * around 0.84). Every 30 s cycle a fake "scan" runs from 20 s to 25 s: {@code lru-A} drops to about
 * 0.35 and then recovers, while {@code lfu-A} holds. The simulation status loops through three
 * phases aligned with that cycle.
 *
 * <p>Determinism: generator time advances by tick index ({@code elapsedMs = tick x 500}), never by
 * wall clock, and all randomness comes from one {@link SplittableRandom} seeded at construction, so
 * the same seed yields the same sequence of ticks. Only {@code ts} and {@code phaseStartedTs} read
 * the {@link Clock}.
 *
 * <p>Thread-safe ({@link #next()} is synchronized); O(1) per tick.
 */
public final class FakeMetricsSource implements MetricsSource {

  static final long TICK_MS = 500;
  static final long CYCLE_MS = 30_000;
  static final long DIP_START_MS = 20_000;
  static final long DIP_END_MS = 25_000;
  static final String GROUP = "demo";
  static final String LRU_CACHE = "lru-A";
  static final String LFU_CACHE = "lfu-A";
  static final long CAPACITY = 1_000;
  static final String SIMULATION_ID = "fake-1";
  static final int MAX_EVENTS_PER_CACHE = 8;

  private static final int BASE_OPS_PER_TICK = 2_500;
  private static final double OPS_JITTER = 0.03;
  private static final int KEY_SPACE = 10_000;

  private final SplittableRandom rnd;
  private final Clock clock;
  private final CostModel costModel;
  private final List<FakeCache> caches;
  private final GroupMetrics group;
  private long tickIndex;
  private FakePhase currentPhase;
  private long phaseStartedTs;

  /**
   * Creates a generator positioned at tick 0.
   *
   * @param seed random seed; equal seeds give equal tick sequences
   * @param clock clock for {@code ts} and {@code phaseStartedTs}; never {@code null}
   * @param costModel turns hits into the cost estimates; never {@code null}
   */
  public FakeMetricsSource(long seed, Clock clock, CostModel costModel) {
    this.rnd = new SplittableRandom(seed);
    this.clock = Objects.requireNonNull(clock, "clock");
    this.costModel = Objects.requireNonNull(costModel, "costModel");
    this.caches =
        List.of(
            new FakeCache(LRU_CACHE, PolicyType.LRU, 0.78, true),
            new FakeCache(LFU_CACHE, PolicyType.LFU, 0.84, false));
    this.group = new GroupMetrics(GROUP, List.of(LRU_CACHE, LFU_CACHE), null, null);
  }

  @Override
  public synchronized MetricsSnapshot next() {
    long cycleMs = (tickIndex++ * TICK_MS) % CYCLE_MS;
    FakePhase phase = FakePhase.at(cycleMs);
    long ts = clock.millis();
    int ops = (int) Math.round(BASE_OPS_PER_TICK * (1 + jitter(rnd, OPS_JITTER)));
    List<CacheMetrics> metrics = new ArrayList<>(caches.size());
    List<RemovalEvent> events = new ArrayList<>();
    for (FakeCache cache : caches) {
      long evictionsDelta = cache.step(ops, phase == FakePhase.SCAN, rnd);
      metrics.add(cache.toMetrics(GROUP, costModel));
      addEvictionEvents(cache.name, evictionsDelta, ts, events);
    }
    events.sort(Comparator.comparingLong(RemovalEvent::ts));
    if (phase != currentPhase) {
      // Fixed once per phase, so wall-clock jitter between ticks never moves the phase start.
      currentPhase = phase;
      phaseStartedTs = ts - (cycleMs - phase.startMs);
    }
    return new MetricsSnapshot(ts, metrics, List.of(group), simulation(phase), events);
  }

  private SimulationStatus simulation(FakePhase phase) {
    return new SimulationStatus(
        SIMULATION_ID,
        true,
        GROUP,
        phase.pattern,
        null,
        phase.ordinal(),
        FakePhase.values().length,
        phase.caption,
        phaseStartedTs);
  }

  /** Adds 0-8 eviction events (never more than {@code evictionsDelta}) timed inside the tick. */
  private void addEvictionEvents(
      String cache, long evictionsDelta, long ts, List<RemovalEvent> out) {
    long count = Math.min(evictionsDelta, rnd.nextInt(MAX_EVENTS_PER_CACHE + 1));
    for (long i = 0; i < count; i++) {
      long eventTs = ts - rnd.nextLong(TICK_MS);
      String key = "key:" + rnd.nextInt(KEY_SPACE);
      out.add(new RemovalEvent(eventTs, cache, key, RemovalCause.EVICTED));
    }
  }

  private static double jitter(SplittableRandom rnd, double amplitude) {
    return rnd.nextDouble(-amplitude, amplitude);
  }

  /** The three phases of the fake 30 s cycle. */
  private enum FakePhase {
    STEADY(0, Pattern.ZIPF, "Fake stream (dev only): steady Zipf traffic"),
    SCAN(DIP_START_MS, Pattern.SCAN_POLLUTION, "Fake stream (dev only): a scan floods lru-A"),
    RECOVERY(DIP_END_MS, Pattern.ZIPF, "Fake stream (dev only): lru-A recovers");

    private final long startMs;
    private final Pattern pattern;
    private final String caption;

    FakePhase(long startMs, Pattern pattern, String caption) {
      this.startMs = startMs;
      this.pattern = pattern;
      this.caption = caption;
    }

    static FakePhase at(long cycleMs) {
      if (cycleMs < DIP_START_MS) {
        return STEADY;
      }
      return cycleMs < DIP_END_MS ? SCAN : RECOVERY;
    }
  }

  /** Mutable counters of one fake cache; confined to {@link #next()}. */
  private static final class FakeCache {
    private static final double MIN_RATE = 0.55;
    private static final double MAX_RATE = 0.95;
    private static final double REVERSION = 0.15;
    private static final double WALK_STEP = 0.015;
    private static final double DIP_RATE = 0.35;
    private static final double DIP_JITTER = 0.03;
    private static final double RECOVERY_START_RATE = 0.58;
    private static final double P50_MICROS = 0.8;
    private static final double P99_MICROS = 6.4;

    private final String name;
    private final PolicyType policy;
    private final double baseRate;
    private final boolean dipsDuringScan;
    private final HitRateWindow window = new HitRateWindow();
    private double walkRate;
    private long size;
    private long hits;
    private long misses;
    private long evictions;
    private double opsPerSec;
    private double p50Micros;
    private double p99Micros;

    FakeCache(String name, PolicyType policy, double baseRate, boolean dipsDuringScan) {
      this.name = name;
      this.policy = policy;
      this.baseRate = baseRate;
      this.dipsDuringScan = dipsDuringScan;
      this.walkRate = baseRate;
    }

    /** Advances one tick of {@code ops} requests and returns the evictions it caused. */
    long step(int ops, boolean scan, SplittableRandom rnd) {
      long hitsDelta = Math.round(ops * nextRate(scan, rnd));
      long missesDelta = ops - hitsDelta;
      hits += hitsDelta;
      misses += missesDelta;
      long inserted = Math.min(CAPACITY - size, missesDelta);
      size += inserted;
      long evictionsDelta = missesDelta - inserted;
      evictions += evictionsDelta;
      window.record(hitsDelta, missesDelta);
      opsPerSec = ops * (1000.0 / TICK_MS);
      p50Micros = round2(P50_MICROS + jitter(rnd, 0.1));
      p99Micros = round2(P99_MICROS + jitter(rnd, 1.0));
      return evictionsDelta;
    }

    /** Per-tick hit rate: the scan dip, or one step of the bounded mean-reverting walk. */
    private double nextRate(boolean scan, SplittableRandom rnd) {
      if (scan && dipsDuringScan) {
        walkRate = RECOVERY_START_RATE; // after the scan the walk climbs back towards its base
        return DIP_RATE + jitter(rnd, DIP_JITTER);
      }
      walkRate += REVERSION * (baseRate - walkRate) + jitter(rnd, WALK_STEP);
      walkRate = Math.max(MIN_RATE, Math.min(MAX_RATE, walkRate));
      return walkRate;
    }

    CacheMetrics toMetrics(String group, CostModel cost) {
      long requests = hits + misses;
      double hitRate = requests == 0 ? 0.0 : (double) hits / requests;
      return new CacheMetrics(
          name,
          group,
          policy,
          size,
          CAPACITY,
          hits,
          misses,
          hitRate,
          window.rate(),
          evictions,
          0L,
          opsPerSec,
          p50Micros,
          p99Micros,
          cost.dbCallsAvoided(hits),
          cost.latencySavedMs(hits),
          cost.estCostSaved(hits));
    }

    private static double round2(double value) {
      return Math.round(value * 100) / 100.0;
    }
  }
}

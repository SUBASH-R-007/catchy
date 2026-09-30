package io.cachelab.server.simulation;

import io.cachelab.Cache;
import io.cachelab.CacheStats;
import io.cachelab.server.cache.ManagedCache;
import io.cachelab.server.metrics.LatencyRecorder;
import io.cachelab.server.metrics.LatencyRecorders;
import io.cachelab.server.simulation.SimulationSummary.CacheSummary;
import io.cachelab.server.workload.KeyStream;
import io.cachelab.server.workload.KeyStreams;
import io.cachelab.server.workload.Pattern;
import io.cachelab.server.workload.SimulatedDatabase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;

/**
 * Executes the operations of one {@link SimulationPlan} against a group's caches (SPEC 9.2).
 *
 * <p>Each operation takes the next key from the phase's {@link KeyStream}; then, with probability
 * {@code readRatio}, it reads the key from every cache in order (timing each {@code get} into that
 * cache's {@link LatencyRecorder}; a miss loads from the cache's {@link SimulatedDatabase} and puts
 * the value), otherwise it puts a new value into every cache. Under {@code TTL_BURST} {@value
 * #TTL_SHARE_PERCENT}% of operations put with a 2 s TTL, decided once per operation for all caches.
 * Every random decision comes from one {@link SplittableRandom} seeded with the plan's seed, so
 * every cache sees the identical sequence and non-TTL runs are reproducible.
 *
 * <p>All key streams of the plan are built (and their parameters validated) in the constructor.
 *
 * <p>Not thread-safe: confined to one simulation thread (or a test's thread).
 */
final class SimulationRunner {

  static final int TTL_SHARE_PERCENT = 30;
  static final Duration BURST_TTL = Duration.ofSeconds(2);
  private static final double TTL_SHARE = TTL_SHARE_PERCENT / 100.0;

  private final SimulationPlan plan;
  private final List<ManagedCache> caches;
  private final List<LatencyRecorder> recorders;
  private final List<SimulatedDatabase> databases;
  private final List<CacheStats> startStats;
  private final List<KeyStream> streams;
  private final SplittableRandom rnd;
  private final int effectiveRate;
  private int phaseIndex = -1;
  private KeyStream stream;
  private boolean ttlBurst;
  private long phaseOps;
  private long totalOps;

  SimulationRunner(SimulationPlan plan, List<ManagedCache> caches, LatencyRecorders latencies) {
    this.plan = plan;
    this.caches = List.copyOf(caches);
    this.rnd = new SplittableRandom(plan.seed());
    this.effectiveRate = KeyStreams.effectiveRate(plan.opsPerSec());
    this.streams = new ArrayList<>();
    for (SimulationPlan.Phase phase : plan.phases()) {
      streams.add(KeyStreams.create(phase.pattern(), phase.params()));
    }
    this.recorders = new ArrayList<>();
    this.databases = new ArrayList<>();
    this.startStats = new ArrayList<>();
    for (int i = 0; i < this.caches.size(); i++) {
      recorders.add(latencies.of(this.caches.get(i)));
      // Separate latency streams: loading never perturbs the workload's random sequence.
      databases.add(new SimulatedDatabase(plan.seed() * 31 + i + 1));
      startStats.add(this.caches.get(i).stats());
    }
    enterPhase(0);
  }

  /** Switches to phase {@code index}; its logical time and op index start again from 0. */
  void enterPhase(int index) {
    phaseIndex = index;
    stream = streams.get(index);
    ttlBurst = plan.phases().get(index).pattern() == Pattern.TTL_BURST;
    phaseOps = 0;
  }

  int phaseIndex() {
    return phaseIndex;
  }

  long totalOps() {
    return totalOps;
  }

  /** Operations a phase lasts in logical time: {@code durationSec x effectiveRate}. */
  long logicalPhaseOps(int index) {
    return (long) plan.phases().get(index).durationSec() * effectiveRate;
  }

  /**
   * Runs {@code n} operations synchronously, advancing phases by logical time; after the last
   * phase's logical length it keeps running the last phase.
   */
  void runLogical(long n) {
    for (long i = 0; i < n; i++) {
      boolean hasNext = phaseIndex + 1 < plan.phases().size();
      if (hasNext && phaseOps >= logicalPhaseOps(phaseIndex)) {
        enterPhase(phaseIndex + 1);
      }
      step();
    }
  }

  /** Executes one operation on every cache of the group. */
  void step() {
    long elapsedMs = KeyStreams.logicalMillis(phaseOps, effectiveRate);
    String key = stream.next(rnd, phaseOps, elapsedMs);
    boolean read = rnd.nextDouble() < plan.readRatio();
    Duration ttl = ttlBurst && rnd.nextDouble() < TTL_SHARE ? BURST_TTL : null;
    if (read) {
      for (int i = 0; i < caches.size(); i++) {
        Cache<String, String> cache = caches.get(i).cache();
        long start = System.nanoTime();
        Optional<String> value = cache.get(key);
        recorders.get(i).record(System.nanoTime() - start);
        if (value.isEmpty()) {
          put(cache, key, databases.get(i).load(key), ttl);
        }
      }
    } else {
      String value = "v" + totalOps;
      for (ManagedCache managed : caches) {
        put(managed.cache(), key, value, ttl);
      }
    }
    phaseOps++;
    totalOps++;
  }

  private static void put(Cache<String, String> cache, String key, String value, Duration ttl) {
    if (ttl == null) {
      cache.put(key, value);
    } else {
      cache.put(key, value, ttl);
    }
  }

  /** Builds the final summary from each cache's statistics since this runner was created. */
  SimulationSummary summary(String id, long startedTs, long endedTs, boolean completed) {
    List<CacheSummary> result = new ArrayList<>(caches.size());
    for (int i = 0; i < caches.size(); i++) {
      ManagedCache managed = caches.get(i);
      CacheStats s = managed.stats().minus(startStats.get(i));
      SimulatedDatabase db = databases.get(i);
      result.add(
          new CacheSummary(
              managed.name(),
              managed.config().policy(),
              s.hitCount(),
              s.missCount(),
              s.hitRate(),
              s.evictionCount(),
              s.expirationCount(),
              db.calls(),
              db.meanLatencyMs()));
    }
    return new SimulationSummary(id, plan, startedTs, endedTs, totalOps, completed, result);
  }
}

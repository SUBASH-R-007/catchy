package io.cachelab.server.metrics;

import io.cachelab.CacheStats;
import io.cachelab.server.cache.CacheConfig;
import io.cachelab.server.cache.CacheGroup;
import io.cachelab.server.cache.CacheRegistry;
import io.cachelab.server.cache.EventRing;
import io.cachelab.server.cache.GroupNotFoundException;
import io.cachelab.server.cache.ManagedCache;
import java.time.Clock;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The metrics stream built from the live caches (SPEC 8.3).
 *
 * <p>Per tick and per registered cache: cumulative counters from {@link ManagedCache#stats()}
 * (which honours {@code reset-stats}); per-tick deltas against the previous tick, tracked per cache
 * <em>instance</em> so a recreated cache starts fresh; {@code hitRateWindow10s} from a {@link
 * HitRateWindow} of the last 20 deltas; {@code opsPerSec} = (Δhits + Δmisses + Δputs) over the
 * measured tick interval; {@code get} percentiles from the cache's {@link LatencyRecorder}; cost
 * estimates from the {@link CostModel}. A counter that went down means the stats were reset: the
 * window is cleared and the counters since the reset count as this tick's delta.
 *
 * <p>{@code groups} come from {@link CacheRegistry#groups()}, with {@code optimalHitRate} and {@code
 * advisor} from the group's {@link CacheGroup} (null until available), {@code simulation} from the simulation service, and
 * {@code events} are the removals since the previous tick (newest {@value
 * MetricsSnapshot#MAX_EVENTS} kept), read through an {@link EventRing} cursor. Each tick ends by
 * rotating the latency recorders, so their percentiles cover the last 20 ticks.
 *
 * <p>Not thread-safe: called only from the publisher thread. A tick is O(caches + events).
 */
public final class RealMetricsSource implements MetricsSource {

  private final CacheRegistry registry;
  private final EventRing events;
  private final LatencyRecorders latencies;
  private final Supplier<SimulationStatus> simulation;
  private final CostModel cost;
  private final Clock clock;
  private final LongSupplier nanoTime;
  private final Map<ManagedCache, Tracked> tracked = new IdentityHashMap<>();
  private long eventCursor;
  private long lastTickNanos;

  /**
   * Creates the source. Events that happened before construction are not reported.
   *
   * @param registry the live caches
   * @param events the removal event ring
   * @param latencies per-cache {@code get} latency recorders
   * @param simulation supplies the current simulation status, or {@code null}
   * @param cost turns hits into the cost estimates
   * @param clock clock for {@code ts}
   * @param nanoTime monotonic time source for the tick interval (for example {@code
   *     System::nanoTime})
   */
  public RealMetricsSource(
      CacheRegistry registry,
      EventRing events,
      LatencyRecorders latencies,
      Supplier<SimulationStatus> simulation,
      CostModel cost,
      Clock clock,
      LongSupplier nanoTime) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.events = Objects.requireNonNull(events, "events");
    this.latencies = Objects.requireNonNull(latencies, "latencies");
    this.simulation = Objects.requireNonNull(simulation, "simulation");
    this.cost = Objects.requireNonNull(cost, "cost");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    this.eventCursor = events.nextSequence();
    this.lastTickNanos = nanoTime.getAsLong() - MetricsPublisher.TICK_MS * 1_000_000;
  }

  @Override
  public MetricsSnapshot next() {
    long now = nanoTime.getAsLong();
    double intervalSec = Math.max(1, now - lastTickNanos) / 1e9;
    lastTickNanos = now;
    Map<String, List<ManagedCache>> groups = registry.groups();
    List<ManagedCache> live = groups.values().stream().flatMap(List::stream).toList();
    tracked.keySet().retainAll(live);
    latencies.retainOnly(live);
    List<CacheMetrics> caches = new ArrayList<>(live.size());
    for (ManagedCache cache : live) {
      caches.add(metricsOf(cache, intervalSec));
    }
    List<GroupMetrics> groupMetrics = new ArrayList<>(groups.size());
    groups.forEach((name, members) -> groupMetrics.add(groupMetricsOf(name, members)));
    EventRing.Batch batch = events.read(eventCursor, MetricsSnapshot.MAX_EVENTS);
    eventCursor = batch.nextCursor();
    latencies.rotateAll();
    return new MetricsSnapshot(
        clock.millis(), caches, groupMetrics, simulation.get(), batch.events());
  }

  private GroupMetrics groupMetricsOf(String name, List<ManagedCache> members) {
    List<String> names = members.stream().map(ManagedCache::name).toList();
    CacheGroup group;
    try {
      group = registry.group(name);
    } catch (GroupNotFoundException e) { // deleted between the two reads
      return new GroupMetrics(name, names, null, null);
    }
    AdvisorRecommendation advisor =
        group
            .recommendation()
            .map(
                r ->
                    new AdvisorRecommendation(
                        r.current(), r.recommended(), r.expectedGainPts(), r.windowSec()))
            .orElse(null);
    return new GroupMetrics(name, names, group.optimalHitRate(), advisor);
  }

  private CacheMetrics metricsOf(ManagedCache cache, double intervalSec) {
    CacheStats stats = cache.stats();
    Tracked t = tracked.computeIfAbsent(cache, c -> new Tracked(stats));
    CacheStats delta = t.advance(stats);
    long requestsDelta = delta.hitCount() + delta.missCount();
    double opsPerSec = (requestsDelta + delta.putCount()) / intervalSec;
    LatencyRecorder recorder = latencies.peek(cache);
    CacheConfig config = cache.config();
    return new CacheMetrics(
        cache.name(),
        config.group(),
        config.policy(),
        cache.cache().size(),
        config.capacity(),
        stats.hitCount(),
        stats.missCount(),
        stats.hitRate(),
        t.window.rate(),
        stats.evictionCount(),
        stats.expirationCount(),
        opsPerSec,
        recorder == null ? 0.0 : recorder.p50Micros(),
        recorder == null ? 0.0 : recorder.p99Micros(),
        cost.dbCallsAvoided(stats.hitCount()),
        cost.latencySavedMs(stats.hitCount()),
        cost.estCostSaved(stats.hitCount()));
  }

  /** Previous-tick counters and hit-rate window of one cache instance. */
  private static final class Tracked {
    private CacheStats previous;
    private HitRateWindow window = new HitRateWindow();

    Tracked(CacheStats first) {
      this.previous = first;
    }

    /** Returns this tick's delta and records it in the window; handles a stats reset. */
    CacheStats advance(CacheStats current) {
      boolean reset =
          current.hitCount() < previous.hitCount()
              || current.missCount() < previous.missCount()
              || current.putCount() < previous.putCount();
      CacheStats delta = reset ? current : current.minus(previous);
      if (reset) {
        window = new HitRateWindow();
      }
      previous = current;
      window.record(delta.hitCount(), delta.missCount());
      return delta;
    }
  }
}

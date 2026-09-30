package io.cachelab.server.metrics;

import static io.cachelab.server.metrics.FakeMetricsSource.CAPACITY;
import static io.cachelab.server.metrics.FakeMetricsSource.CYCLE_MS;
import static io.cachelab.server.metrics.FakeMetricsSource.LFU_CACHE;
import static io.cachelab.server.metrics.FakeMetricsSource.LRU_CACHE;
import static io.cachelab.server.metrics.FakeMetricsSource.TICK_MS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import io.cachelab.PolicyType;
import io.cachelab.RemovalCause;
import io.cachelab.server.workload.Pattern;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

class FakeMetricsSourceTest {

  private static final long START_MS = 1_790_000_000_000L;
  private static final int TICKS = 240; // 2 minutes = 4 full cycles
  private static final CostModel COST = new CostModel(12.5, 0.05, "$");
  private static final List<String> CACHES = List.of(LRU_CACHE, LFU_CACHE);

  private final List<MetricsSnapshot> ticks = run(42, TICKS);

  @Test
  void sameSeedGivesIdenticalSequences() {
    assertThat(run(42, TICKS)).isEqualTo(ticks);
    assertThat(run(7, TICKS)).isNotEqualTo(ticks);
  }

  @Test
  void countersAreMonotonicAndValuesInRange() {
    for (String name : CACHES) {
      CacheMetrics previous = null;
      for (MetricsSnapshot tick : ticks) {
        CacheMetrics now = cache(tick, name);
        assertThat(now.hitRate()).isBetween(0.0, 1.0);
        assertThat(now.hitRateWindow10s()).isBetween(0.0, 1.0);
        assertThat(now.hitRate()).isEqualTo((double) now.hits() / (now.hits() + now.misses()));
        assertThat(now.capacity()).isEqualTo(CAPACITY);
        assertThat(now.size()).isBetween(0L, CAPACITY);
        assertThat(now.size() + now.evictions())
            .as("every miss is inserted")
            .isEqualTo(now.misses());
        assertThat(now.expirations()).isZero();
        assertThat(now.opsPerSec()).isBetween(4_700.0, 5_300.0);
        assertThat(now.getP50Micros()).isBetween(0.7, 0.9);
        assertThat(now.getP99Micros()).isBetween(5.4, 7.4);
        assertThat(tick.events()).hasSizeLessThanOrEqualTo(MetricsSnapshot.MAX_EVENTS);
        if (previous != null) {
          assertThat(now.hits()).isGreaterThan(previous.hits());
          assertThat(now.misses()).isGreaterThan(previous.misses());
          assertThat(now.evictions()).isGreaterThanOrEqualTo(previous.evictions());
          assertThat(now.size()).isGreaterThanOrEqualTo(previous.size());
        }
        previous = now;
      }
      assertThat(previous.evictions()).as("the cache fills up and evicts").isPositive();
    }
  }

  @Test
  void costEstimatesFollowTheHits() {
    for (MetricsSnapshot tick : ticks) {
      for (CacheMetrics cache : tick.caches()) {
        assertThat(cache.dbCallsAvoided()).isEqualTo(cache.hits());
        assertThat(cache.latencySavedMs()).isCloseTo(cache.hits() * 12.5, within(1e-6));
        assertThat(cache.estCostSaved()).isCloseTo(cache.hits() / 1000.0 * 0.05, within(1e-9));
      }
    }
  }

  @Test
  void lruDipsOnlyDuringTheScanWindowAndLfuHolds() {
    int dipTicks = 0;
    for (int i = 0; i < TICKS; i++) {
      double lru = tickHitRate(i, LRU_CACHE);
      double lfu = tickHitRate(i, LFU_CACHE);
      if (inScanWindow(i)) {
        dipTicks++;
        assertThat(lru).as("lru-A during the scan, tick %d", i).isBetween(0.25, 0.45);
      } else {
        assertThat(lru).as("lru-A outside the scan, tick %d", i).isGreaterThanOrEqualTo(0.5);
      }
      assertThat(lfu).as("lfu-A, tick %d", i).isGreaterThanOrEqualTo(0.5);
    }
    assertThat(dipTicks).as("5 s of every 30 s").isEqualTo(TICKS / 6);
  }

  @Test
  void windowRateIsTheRatioOfTheLastTwentyDeltas() {
    for (String name : CACHES) {
      Deque<long[]> ring = new ArrayDeque<>();
      for (int i = 0; i < TICKS; i++) {
        long[] delta = delta(i, name);
        ring.addLast(delta);
        if (ring.size() > HitRateWindow.DEFAULT_TICKS) {
          ring.removeFirst();
        }
        long hits = ring.stream().mapToLong(d -> d[0]).sum();
        long requests = ring.stream().mapToLong(d -> d[0] + d[1]).sum();
        assertThat(cache(ticks.get(i), name).hitRateWindow10s())
            .as("%s tick %d", name, i)
            .isCloseTo((double) hits / requests, within(1e-12));
      }
    }
  }

  @Test
  void simulationAndGroupsFollowTheCycle() {
    for (int i = 0; i < TICKS; i++) {
      MetricsSnapshot tick = ticks.get(i);
      long cycleMs = (i * TICK_MS) % CYCLE_MS;
      int expectedPhase = cycleMs < 20_000 ? 0 : cycleMs < 25_000 ? 1 : 2;
      long phaseStartMs = List.of(0L, 20_000L, 25_000L).get(expectedPhase);
      SimulationStatus sim = tick.simulation();

      assertThat(sim.id()).isEqualTo("fake-1");
      assertThat(sim.running()).isTrue();
      assertThat(sim.group()).isEqualTo("demo");
      assertThat(sim.act()).isNull();
      assertThat(sim.phaseCount()).isEqualTo(3);
      assertThat(sim.phaseIndex()).as("tick %d", i).isEqualTo(expectedPhase);
      assertThat(sim.pattern())
          .isEqualTo(expectedPhase == 1 ? Pattern.SCAN_POLLUTION : Pattern.ZIPF);
      assertThat(sim.phaseCaption())
          .startsWith("Fake stream (dev only): ")
          .isEqualTo(caption(expectedPhase));
      assertThat(sim.phaseStartedTs()).isEqualTo(tick.ts() - (cycleMs - phaseStartMs));
      assertThat(tick.groups())
          .containsExactly(new GroupMetrics("demo", List.of(LRU_CACHE, LFU_CACHE), null, null));
      assertThat(tick.caches())
          .extracting(CacheMetrics::name, CacheMetrics::group, CacheMetrics::policy)
          .containsExactly(
              tuple(LRU_CACHE, "demo", PolicyType.LRU), tuple(LFU_CACHE, "demo", PolicyType.LFU));
    }
  }

  @Test
  void phaseStartIsStableWithinAPhaseDespiteClockJitter() {
    StepClock clock = new StepClock(START_MS);
    FakeMetricsSource source = new FakeMetricsSource(42, clock, COST);
    SimulationStatus previous = null;
    int phaseChanges = 0;
    for (int i = 0; i < TICKS; i++) {
      SimulationStatus sim = source.next().simulation();
      if (previous != null && previous.phaseIndex() == sim.phaseIndex()) {
        assertThat(sim.phaseStartedTs()).as("tick %d", i).isEqualTo(previous.phaseStartedTs());
      } else if (previous != null) {
        phaseChanges++;
        assertThat(sim.phaseStartedTs()).isGreaterThan(previous.phaseStartedTs());
      }
      previous = sim;
      clock.advance(TICK_MS + (i % 2 == 0 ? 7 : -5));
    }
    assertThat(phaseChanges).as("3 phase changes per 30 s cycle").isEqualTo(3 * TICKS / 60 - 1);
  }

  @Test
  void eventsAreBoundedByEvictionsAndTimedInsideTheTick() {
    int total = 0;
    for (int i = 0; i < TICKS; i++) {
      MetricsSnapshot tick = ticks.get(i);
      for (String name : CACHES) {
        long evictionsDelta =
            cache(tick, name).evictions()
                - (i == 0 ? 0 : cache(ticks.get(i - 1), name).evictions());
        List<RemovalEvent> events =
            tick.events().stream().filter(e -> e.cache().equals(name)).toList();
        assertThat((long) events.size())
            .isLessThanOrEqualTo(Math.min(evictionsDelta, FakeMetricsSource.MAX_EVENTS_PER_CACHE));
        total += events.size();
      }
      assertThat(tick.events())
          .allSatisfy(
              e -> {
                assertThat(e.ts()).isBetween(tick.ts() - TICK_MS + 1, tick.ts());
                assertThat(e.cause()).isEqualTo(RemovalCause.EVICTED);
                assertThat(e.key()).matches("key:\\d+");
              })
          .isSortedAccordingTo((a, b) -> Long.compare(a.ts(), b.ts()));
    }
    assertThat(total).as("events are actually produced").isGreaterThan(TICKS);
  }

  private static List<MetricsSnapshot> run(long seed, int count) {
    StepClock clock = new StepClock(START_MS);
    FakeMetricsSource source = new FakeMetricsSource(seed, clock, COST);
    List<MetricsSnapshot> result = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      result.add(source.next());
      clock.advance(TICK_MS);
    }
    return result;
  }

  private static boolean inScanWindow(int tickIndex) {
    long cycleMs = (tickIndex * TICK_MS) % CYCLE_MS;
    return cycleMs >= 20_000 && cycleMs < 25_000;
  }

  private static String caption(int phase) {
    return switch (phase) {
      case 0 -> "Fake stream (dev only): steady Zipf traffic";
      case 1 -> "Fake stream (dev only): a scan floods lru-A";
      default -> "Fake stream (dev only): lru-A recovers";
    };
  }

  private static CacheMetrics cache(MetricsSnapshot tick, String name) {
    return tick.caches().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
  }

  /** {hitsDelta, missesDelta} of tick {@code i}. */
  private long[] delta(int i, String name) {
    CacheMetrics now = cache(ticks.get(i), name);
    long hits = now.hits();
    long misses = now.misses();
    if (i > 0) {
      CacheMetrics previous = cache(ticks.get(i - 1), name);
      hits -= previous.hits();
      misses -= previous.misses();
    }
    return new long[] {hits, misses};
  }

  private double tickHitRate(int i, String name) {
    long[] delta = delta(i, name);
    return (double) delta[0] / (delta[0] + delta[1]);
  }

  /** A clock that moves only when told to. */
  private static final class StepClock extends Clock {
    private long millis;

    StepClock(long millis) {
      this.millis = millis;
    }

    void advance(long ms) {
      millis += ms;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis);
    }

    @Override
    public long millis() {
      return millis;
    }
  }
}

package io.cachelab.server.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cachelab.PolicyType;
import io.cachelab.RemovalCause;
import io.cachelab.server.cache.CacheConfig;
import io.cachelab.server.cache.CacheRegistry;
import io.cachelab.server.cache.EventRing;
import io.cachelab.server.cache.ManagedCache;
import io.cachelab.server.simulation.SimulationPlan;
import io.cachelab.server.simulation.SimulationService;
import io.cachelab.server.workload.Pattern;
import io.cachelab.testing.FakeTicker;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/** The real metrics source over a hand-driven registry, with a controllable tick interval. */
@JsonTest
class RealMetricsSourceTest {

  private static final long TICK_NANOS = 500_000_000L;

  @Autowired private ObjectMapper objectMapper;

  private final EventRing events = new EventRing();
  private final CacheRegistry registry =
      new CacheRegistry(events, new FakeTicker(), Clock.systemUTC());
  private final LatencyRecorders latencies = new LatencyRecorders();
  private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
  private final AtomicReference<SimulationStatus> simulation = new AtomicReference<>();
  private final RealMetricsSource source =
      new RealMetricsSource(
          registry,
          events,
          latencies,
          simulation::get,
          new CostModel(12.5, 0.05, "$"),
          Clock.systemUTC(),
          nanos::get);

  @AfterEach
  void close() {
    registry.closeAll();
  }

  private MetricsSnapshot tick() {
    nanos.addAndGet(TICK_NANOS);
    return source.next();
  }

  private static CacheMetrics cache(MetricsSnapshot s, String name) {
    return s.caches().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
  }

  /** {@code hits} gets of a key that is present and {@code misses} gets of absent keys. */
  private void traffic(ManagedCache cache, int hits, int misses) {
    cache.cache().put("hot", "v");
    for (int i = 0; i < hits; i++) {
      cache.cache().get("hot");
    }
    for (int i = 0; i < misses; i++) {
      cache.cache().get("absent-" + i);
    }
  }

  @Test
  void reportsEveryCacheAndGroupWithNullStepFourFields() {
    MetricsSnapshot s = tick();
    assertThat(s.caches()).extracting(CacheMetrics::name).containsExactly("lfu-A", "lru-A");
    assertThat(s.groups()).hasSize(1);
    assertThat(s.groups().get(0).caches()).containsExactly("lfu-A", "lru-A");
    assertThat(s.groups().get(0).optimalHitRate()).isNull();
    assertThat(s.groups().get(0).advisor()).isNull();
    assertThat(s.simulation()).isNull();
    CacheMetrics lru = cache(s, "lru-A");
    assertThat(lru.policy()).isEqualTo(PolicyType.LRU);
    assertThat(lru.capacity()).isEqualTo(1_000);
    assertThat(lru.getP50Micros()).isZero();
    assertThat(lru.opsPerSec()).isZero();
  }

  @Test
  void deltasDriveOpsPerSecAndTheWindow() {
    tick();
    ManagedCache lru = registry.get("lru-A");
    traffic(lru, 300, 100); // 1 put + 400 gets
    CacheMetrics first = cache(tick(), "lru-A");
    assertThat(first.hits()).isEqualTo(300);
    assertThat(first.misses()).isEqualTo(100);
    assertThat(first.hitRate()).isCloseTo(0.75, within(1e-9));
    assertThat(first.hitRateWindow10s()).isCloseTo(0.75, within(1e-9));
    assertThat(first.opsPerSec()).isCloseTo(401 / 0.5, within(1e-6));
    assertThat(first.dbCallsAvoided()).isEqualTo(300);
    assertThat(first.latencySavedMs()).isCloseTo(300 * 12.5, within(1e-9));
    assertThat(first.estCostSaved()).isCloseTo(0.015, within(1e-9));

    traffic(lru, 0, 400);
    nanos.addAndGet(TICK_NANOS); // a slow tick: 1 s since the previous one
    CacheMetrics second = cache(tick(), "lru-A");
    assertThat(second.hitRateWindow10s()).isCloseTo(300 / 800.0, within(1e-9));
    assertThat(second.opsPerSec()).isCloseTo(401 / 1.0, within(1e-6));
    assertThat(second.hitRate()).isCloseTo(300 / 800.0, within(1e-9));

    for (int i = 0; i < HitRateWindow.DEFAULT_TICKS; i++) {
      tick(); // idle ticks push the traffic out of the 10 s window
    }
    CacheMetrics idle = cache(tick(), "lru-A");
    assertThat(idle.hitRateWindow10s()).isZero();
    assertThat(idle.opsPerSec()).isZero();
    assertThat(idle.hits()).isEqualTo(300);
  }

  @Test
  void aStatsResetClearsTheWindowAndRebaselines() {
    tick();
    ManagedCache lru = registry.get("lru-A");
    traffic(lru, 900, 100);
    tick();
    lru.resetStats();
    traffic(lru, 10, 30);
    CacheMetrics afterReset = cache(tick(), "lru-A");
    assertThat(afterReset.hits()).isEqualTo(10);
    assertThat(afterReset.hitRateWindow10s()).isCloseTo(0.25, within(1e-9));
    assertThat(afterReset.opsPerSec()).isCloseTo(41 / 0.5, within(1e-6));
  }

  @Test
  void aRecreatedCacheStartsFresh() {
    registry.create(new CacheConfig("tmp", PolicyType.LRU, 10, null, 1, "g"));
    tick();
    traffic(registry.get("tmp"), 50, 50);
    assertThat(cache(tick(), "tmp").hitRateWindow10s()).isEqualTo(0.5);
    registry.delete("tmp");
    assertThat(tick().caches()).extracting(CacheMetrics::name).doesNotContain("tmp");

    registry.create(new CacheConfig("tmp", PolicyType.LFU, 10, null, 1, "g"));
    CacheMetrics fresh = cache(tick(), "tmp");
    assertThat(fresh.hits()).isZero();
    assertThat(fresh.hitRateWindow10s()).isZero();
    assertThat(fresh.policy()).isEqualTo(PolicyType.LFU);
  }

  @Test
  void eventsAreThoseSinceThePreviousTickNewestFiftyKept() {
    registry.create(new CacheConfig("tiny", PolicyType.LRU, 1, null, 1, "g"));
    tick();
    registry.get("tiny").cache().put("a", "1");
    registry.get("tiny").cache().put("b", "2"); // evicts a
    MetricsSnapshot first = tick();
    assertThat(first.events()).hasSize(1);
    assertThat(first.events().get(0).key()).isEqualTo("a");
    assertThat(first.events().get(0).cause()).isEqualTo(RemovalCause.EVICTED);
    assertThat(tick().events()).isEmpty();

    for (int i = 0; i < 80; i++) {
      registry.get("tiny").cache().put("k" + i, "v");
    }
    MetricsSnapshot burst = tick();
    assertThat(burst.events()).hasSize(MetricsSnapshot.MAX_EVENTS);
    assertThat(burst.events().get(0).key()).isEqualTo("k29"); // b, k0..k78 evicted; newest 50
    assertThat(burst.events().get(49).key()).isEqualTo("k78");
    assertThat(tick().events()).isEmpty();
  }

  @Test
  void latencyPercentilesComeFromTheCachesRecorder() {
    ManagedCache lfu = registry.get("lfu-A");
    for (int i = 0; i < 100; i++) {
      latencies.of(lfu).record(1_000);
    }
    CacheMetrics m = cache(tick(), "lfu-A");
    assertThat(m.getP50Micros()).isCloseTo(1.0, within(0.07));
    assertThat(m.getP99Micros()).isCloseTo(1.0, within(0.07));
  }

  @Test
  void payloadsWithARunningSimulationMatchTheSchema() throws IOException {
    MiniJsonSchemaValidator validator = MiniJsonSchemaValidator.forMetricsSchema();
    SimulationService service = new SimulationService(registry, latencies, Clock.systemUTC());
    try {
      service.start(
          SimulationPlan.single("demo", Pattern.SCAN_POLLUTION, 20_000, 0.9, 30, 42, null, "c"));
      simulation.set(service.status());
      tick();
      for (int i = 0; i < 6; i++) {
        await()
            .atMost(Duration.ofSeconds(5))
            .until(() -> registry.get("lru-A").stats().requestCount() > 0);
        simulation.set(service.status());
        MetricsSnapshot s = tick();
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(s));
        assertThat(validator.validate(json)).as("%s", json).isEmpty();
        assertThat(json.get("simulation").get("running").asBoolean()).isTrue();
        Thread.sleep(100); // let the simulation produce traffic between ticks (not TTL logic)
      }
      MetricsSnapshot last = tick();
      assertThat(cache(last, "lru-A").hits()).isPositive();
      assertThat(last.events()).isNotEmpty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      service.stopAll();
    }
  }
}

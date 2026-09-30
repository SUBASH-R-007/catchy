package io.cachelab.server.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.cachelab.PolicyType;
import io.cachelab.server.cache.CacheConfig;
import io.cachelab.server.cache.CacheRegistry;
import io.cachelab.server.cache.EventRing;
import io.cachelab.server.metrics.LatencyRecorders;
import io.cachelab.server.simulation.SimulationSummary.CacheSummary;
import io.cachelab.server.workload.Pattern;
import io.cachelab.testing.FakeTicker;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Runs workloads synchronously ({@link SimulationService#runOps}) on the default {@code demo} group
 * (lru-A and lfu-A, capacity 1,000): Gate 3 determinism and the expected outcome of each pattern.
 */
class SimulationWorkloadTest {

  private static final int OUTCOME_OPS = 200_000;

  private final FakeTicker ticker = new FakeTicker();
  private CacheRegistry registry;

  @AfterEach
  void close() {
    if (registry != null) {
      registry.closeAll();
    }
  }

  private SimulationService freshService() {
    close();
    registry = new CacheRegistry(new EventRing(), ticker, Clock.systemUTC());
    return new SimulationService(registry, new LatencyRecorders(), Clock.systemUTC());
  }

  private static SimulationPlan plan(Pattern pattern, double readRatio) {
    return SimulationPlan.single("demo", pattern, 0, readRatio, 60, 42, Map.of(), null);
  }

  private Map<String, CacheSummary> run(Pattern pattern, double readRatio, long ops) {
    SimulationSummary summary = freshService().runOps(plan(pattern, readRatio), ops);
    assertThat(summary.ops()).isEqualTo(ops);
    return Map.of("lru", byName(summary, "lru-A"), "lfu", byName(summary, "lfu-A"));
  }

  private static CacheSummary byName(SimulationSummary summary, String name) {
    return summary.caches().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
  }

  @ParameterizedTest
  @EnumSource(value = Pattern.class, names = "TTL_BURST", mode = EnumSource.Mode.EXCLUDE)
  void sameSeedGivesIdenticalHitsAndMissesPerCache(Pattern pattern) {
    List<CacheSummary> first = freshService().runOps(plan(pattern, 0.9), 50_000).caches();
    List<CacheSummary> second = freshService().runOps(plan(pattern, 0.9), 50_000).caches();

    assertThat(first).hasSize(2);
    for (int i = 0; i < first.size(); i++) {
      assertThat(second.get(i).hits()).isEqualTo(first.get(i).hits());
      assertThat(second.get(i).misses()).isEqualTo(first.get(i).misses());
      assertThat(second.get(i).evictions()).isEqualTo(first.get(i).evictions());
    }
    assertThat(first.get(0).hits() + first.get(0).misses()).isPositive();
  }

  @Test
  void everyCacheSeesTheSameRequests() {
    Map<String, CacheSummary> r = run(Pattern.ZIPF, 0.9, 20_000);
    CacheSummary lru = r.get("lru");
    CacheSummary lfu = r.get("lfu");
    assertThat(lru.hits() + lru.misses()).isEqualTo(lfu.hits() + lfu.misses());
    assertThat(lru.dbCalls()).isEqualTo(lru.misses());
    assertThat(lru.meanDbLatencyMs()).isBetween(5.0, 20.0);
    assertThat(lru.policy()).isEqualTo(PolicyType.LRU);
    assertThat(lfu.policy()).isEqualTo(PolicyType.LFU);
  }

  @Test
  void uniformHitsAboutTenPercentForBothPolicies() {
    Map<String, CacheSummary> r = run(Pattern.UNIFORM, 0.9, OUTCOME_OPS);
    report("UNIFORM", r);
    assertThat(r.get("lru").hitRate()).isCloseTo(0.10, within(0.03));
    assertThat(r.get("lfu").hitRate()).isCloseTo(0.10, within(0.03));
  }

  @Test
  void zipfIsHighForBothAndLfuIsNotBehind() {
    Map<String, CacheSummary> r = run(Pattern.ZIPF, 0.9, OUTCOME_OPS);
    report("ZIPF", r);
    assertThat(r.get("lru").hitRate()).isGreaterThan(0.5);
    assertThat(r.get("lfu").hitRate()).isGreaterThan(0.5);
    assertThat(r.get("lfu").hitRate()).isGreaterThanOrEqualTo(r.get("lru").hitRate() - 0.01);
  }

  @Test
  void scanPollutionHurtsLruAndNotLfu() {
    Map<String, CacheSummary> r = run(Pattern.SCAN_POLLUTION, 0.9, OUTCOME_OPS);
    report("SCAN_POLLUTION", r);
    assertThat(r.get("lfu").hitRate()).isGreaterThan(r.get("lru").hitRate() + 0.05);
  }

  @Test
  void loopDefeatsLruButNotLfu() {
    Map<String, CacheSummary> r = run(Pattern.LOOP, 0.9, OUTCOME_OPS);
    report("LOOP", r);
    assertThat(r.get("lru").hitRate()).isLessThan(0.05);
    assertThat(r.get("lfu").hitRate()).isGreaterThan(0.5);
  }

  @Test
  void ttlBurstExpiresEntriesUnderEveryPolicy() {
    SimulationService service = freshService();
    SimulationPlan plan = plan(Pattern.TTL_BURST, 0.9);
    service.runOps(plan, 20_000);
    ticker.advanceMillis(2_001); // every burst TTL (2 s) has now elapsed
    SimulationSummary after = service.runOps(plan, 20_000);

    long lruExpired = registry.get("lru-A").stats().expirationCount();
    long lfuExpired = registry.get("lfu-A").stats().expirationCount();
    assertThat(lruExpired).isPositive();
    assertThat(lfuExpired).isPositive();
    assertThat(after.caches()).allSatisfy(c -> assertThat(c.hits()).isPositive());
  }

  @Test
  void multiPhasePlansAdvanceByLogicalTime() {
    SimulationService service = freshService();
    registry.create(new CacheConfig("solo", PolicyType.LRU, 10, null, 1, "g"));
    SimulationPlan plan =
        new SimulationPlan(
            "g",
            100,
            1.0,
            1,
            1,
            List.of(
                new SimulationPlan.Phase(
                    Pattern.LOOP, 1, "a", Map.of("loopSize", 5, "rereadShare", 0)),
                new SimulationPlan.Phase(
                    Pattern.LOOP, 1, "b", Map.of("loopSize", 50, "rereadShare", 0))));
    // Phase 1 = 1 s x 100 ops/s over 5 keys: 95 hits. Phase 2 restarts the loop over 50 keys: only
    // key:0-4 (still cached) hit on its first pass, and an LRU of 10 misses everything after.
    CacheSummary solo = service.runOps(plan, 200).caches().get(0);
    assertThat(solo.hits()).isEqualTo(100);
    assertThat(solo.misses()).isEqualTo(100);
  }

  private static void report(String pattern, Map<String, CacheSummary> r) {
    System.out.printf(
        "OUTCOME %-15s LRU %.3f  LFU %.3f%n",
        pattern, r.get("lru").hitRate(), r.get("lfu").hitRate());
  }
}

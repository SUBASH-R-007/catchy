package io.cachelab.server.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.cachelab.server.cache.CacheRegistry;
import io.cachelab.server.cache.EventRing;
import io.cachelab.server.cache.GroupNotFoundException;
import io.cachelab.server.metrics.LatencyRecorders;
import io.cachelab.server.metrics.SimulationStatus;
import io.cachelab.server.workload.Pattern;
import io.cachelab.testing.FakeTicker;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SimulationServiceTest {

  private final CacheRegistry registry =
      new CacheRegistry(new EventRing(), new FakeTicker(), Clock.systemUTC());
  private final LatencyRecorders latencies = new LatencyRecorders();
  private final SimulationService service =
      new SimulationService(registry, latencies, Clock.systemUTC());

  @AfterEach
  void tearDown() {
    service.stopAll();
    registry.closeAll();
  }

  private static SimulationPlan zipf(int durationSec) {
    return SimulationPlan.single("demo", Pattern.ZIPF, 2_000, 0.9, durationSec, 42, null, "cap");
  }

  private static boolean simThreadAlive(String id) {
    return Thread.getAllStackTraces().keySet().stream()
        .anyMatch(t -> t.getName().equals("cachelab-sim-" + id) && t.isAlive());
  }

  @Test
  void noStatusBeforeTheFirstSimulation() {
    assertThat(service.status()).isNull();
    assertThat(service.latestSummary()).isEmpty();
  }

  @Test
  void runsForItsDurationThenKeepsAStoppedStatusAndASummary() {
    String id = service.start(zipf(1));

    SimulationStatus running = service.status();
    assertThat(running.id()).isEqualTo(id);
    assertThat(running.running()).isTrue();
    assertThat(running.group()).isEqualTo("demo");
    assertThat(running.pattern()).isEqualTo(Pattern.ZIPF);
    assertThat(running.act()).isNull();
    assertThat(running.phaseIndex()).isZero();
    assertThat(running.phaseCount()).isEqualTo(1);
    assertThat(running.phaseCaption()).isEqualTo("cap");
    assertThat(simThreadAlive(id)).isTrue();

    await().atMost(Duration.ofSeconds(10)).until(() -> !service.status().running());
    assertThat(service.status().id()).isEqualTo(id);
    SimulationSummary summary = service.summary(id).orElseThrow();
    assertThat(summary.completed()).isTrue();
    // ~2,000 ops in 1 s at 2,000 ops/s (pacing), each applied to both caches
    assertThat(summary.ops()).isBetween(1_500L, 2_100L);
    assertThat(summary.caches())
        .extracting(SimulationSummary.CacheSummary::name)
        .containsExactly("lfu-A", "lru-A");
    assertThat(latencies.peek(registry.get("lru-A")).p50Micros()).isPositive();
    assertThat(service.latestSummary()).contains(summary);
  }

  @Test
  void stopEndsTheRunImmediately() {
    String id = service.start(zipf(60));
    service.stop(id);

    assertThat(service.status().running()).isFalse();
    assertThat(simThreadAlive(id)).isFalse();
    assertThat(service.summary(id).orElseThrow().completed()).isFalse();
    service.stop(id); // stopping a finished simulation is a no-op
  }

  @Test
  void startingANewSimulationStopsThePreviousOne() {
    String first = service.start(zipf(60));
    String second = service.start(zipf(60));

    assertThat(second).isNotEqualTo(first);
    assertThat(simThreadAlive(first)).isFalse();
    assertThat(service.summary(first).orElseThrow().completed()).isFalse();
    assertThat(service.status().id()).isEqualTo(second);
    assertThat(service.status().running()).isTrue();
  }

  @Test
  void phasesAdvanceInOrderWithTheirCaptions() {
    SimulationPlan plan =
        new SimulationPlan(
            "demo",
            1_000,
            0.9,
            42,
            1,
            List.of(
                new SimulationPlan.Phase(Pattern.ZIPF, 1, "first", null),
                new SimulationPlan.Phase(Pattern.SCAN_POLLUTION, 1, "second", null)));
    long before = System.currentTimeMillis();
    service.start(plan);
    assertThat(service.status().act()).isEqualTo(1);
    assertThat(service.status().phaseCount()).isEqualTo(2);

    await().atMost(Duration.ofSeconds(10)).until(() -> service.status().phaseIndex() == 1);
    SimulationStatus second = service.status();
    assertThat(second.pattern()).isEqualTo(Pattern.SCAN_POLLUTION);
    assertThat(second.phaseCaption()).isEqualTo("second");
    assertThat(second.phaseStartedTs()).isGreaterThanOrEqualTo(before + 900);

    await().atMost(Duration.ofSeconds(10)).until(() -> !service.status().running());
    assertThat(service.status().phaseIndex()).isEqualTo(1);
  }

  @Test
  void rejectsUnknownGroupsIdsAndParametersWithoutStoppingTheRunningOne() {
    String id = service.start(zipf(60));

    assertThatThrownBy(
            () ->
                service.start(
                    SimulationPlan.single("nope", Pattern.ZIPF, 0, 0.9, 5, 1, null, null)))
        .isInstanceOf(GroupNotFoundException.class);
    assertThatThrownBy(
            () ->
                service.start(
                    SimulationPlan.single(
                        "demo", Pattern.LOOP, 0, 0.9, 5, 1, Map.of("bogus", 1), null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.stop("s-999")).isInstanceOf(SimulationNotFoundException.class);
    assertThatThrownBy(() -> service.stop("x")).isInstanceOf(SimulationNotFoundException.class);

    assertThat(service.status().id()).isEqualTo(id);
    assertThat(service.status().running()).isTrue();
  }
}

package io.cachelab.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cachelab.CacheBuilder;
import io.cachelab.CacheStats;
import io.cachelab.PolicyType;
import io.cachelab.diagnostics.StressReport.InvariantResult;
import io.cachelab.internal.BoundedCache;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DiagnosticsTest {

  @ParameterizedTest
  @EnumSource(StressConfig.Impl.class)
  void aShortStressRunKeepsAllFiveInvariants(StressConfig.Impl impl) {
    StressConfig config =
        new StressConfig(impl, 8, Duration.ofMillis(400), 2_000, 0.8, 256, PolicyType.LFU, 7);
    StressReport report = StressHarness.run(config);
    assertThat(report.invariants())
        .extracting(InvariantResult::name)
        .isEqualTo(InvariantChecker.NAMES);
    assertThat(report.invariants()).allSatisfy(r -> assertThat(r.passed()).as(r.detail()).isTrue());
    assertThat(report.deadlockFree()).isTrue();
    assertThat(report.passed()).isTrue();
    assertThat(report.totalOps()).isPositive();
    assertThat(report.opsPerSec()).isPositive();
    assertThat(report.durationMs()).isGreaterThanOrEqualTo(400);
    assertThat(report.exceptions()).isEmpty();
  }

  @Test
  void theStampedeLoadsOnce() {
    StampedeResult result = StampedeTest.run();
    assertThat(result.threads()).isEqualTo(200);
    assertThat(result.loaderCalls()).isEqualTo(1);
    assertThat(result.allSameValue()).isTrue();
    assertThat(result.passed()).isTrue();
    assertThat(result.durationMs()).isGreaterThanOrEqualTo(200);
  }

  @Test
  void theCheckerReportsViolations() {
    assertThat(InvariantChecker.sizeBound(1_001, 10, 900, 1_000).passed()).isFalse();
    assertThat(InvariantChecker.sizeBound(999, 10, 1_000, 1_000).passed()).isTrue();
    CacheStats stats = new CacheStats(5, 4, 0, 0, 0, 0, 0, 0);
    assertThat(InvariantChecker.accounting(stats, 10).passed()).isFalse();
    assertThat(InvariantChecker.accounting(stats, 9).detail()).contains("hits 5 + misses 4 = 9");
    assertThat(InvariantChecker.noPhantoms(1, 50, "key:1 -> k=key:2;t=0;s=1").detail())
        .contains("key:1 -> k=key:2");
    assertThat(InvariantChecker.noExceptions(2, "IllegalStateException: boom").passed()).isFalse();
  }

  @Test
  void theStructureCheckCatchesACorruptedCache() throws Exception {
    try (BoundedCache<String, String> cache =
        (BoundedCache<String, String>)
            CacheBuilder.<String, String>newBuilder().maximumSize(4).build()) {
      cache.put("a", "1");
      assertThat(InvariantChecker.structure(cache).passed()).isTrue();
      // Corrupt the map behind the policy's back: the policy now tracks a node the map lost.
      var mapField = BoundedCache.class.getDeclaredField("map");
      mapField.setAccessible(true);
      ((java.util.Map<?, ?>) mapField.get(cache)).clear();
      InvariantResult result = InvariantChecker.structure(cache);
      assertThat(result.passed()).isFalse();
      assertThat(result.detail()).contains("policy tracks 1 entries but the map holds 0");
    }
  }

  @Test
  void stressConfigValidatesItsRanges() {
    assertThatThrownBy(
            () ->
                new StressConfig(
                    StressConfig.Impl.SINGLE_LOCK,
                    65,
                    Duration.ofSeconds(1),
                    10,
                    0.5,
                    10,
                    PolicyType.LRU,
                    1))
        .hasMessageContaining("threads must be between 1 and 64");
    assertThatThrownBy(
            () ->
                new StressConfig(
                    StressConfig.Impl.SINGLE_LOCK,
                    4,
                    Duration.ofSeconds(11),
                    10,
                    0.5,
                    10,
                    PolicyType.LRU,
                    1))
        .hasMessageContaining("at most 10 s");
    assertThatThrownBy(
            () ->
                new StressConfig(
                    StressConfig.Impl.SEGMENTED,
                    4,
                    Duration.ofSeconds(1),
                    10,
                    0.5,
                    8,
                    PolicyType.LRU,
                    1))
        .hasMessageContaining("capacity >= 16");
    assertThatThrownBy(() -> StampedeTest.run(0, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

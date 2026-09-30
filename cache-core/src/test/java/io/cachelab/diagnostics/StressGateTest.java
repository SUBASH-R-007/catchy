package io.cachelab.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Gate 3 (SPEC 12): 32 threads × 5 s must pass 20 consecutive runs for both engines with no
 * deadlocks. About 3.5 minutes, so it is tagged {@code stress} and runs only through {@code
 * ./gradlew :cache-core:stressTest}.
 */
@Tag("stress")
class StressGateTest {

  @ParameterizedTest
  @EnumSource(StressConfig.Impl.class)
  void twentyConsecutiveRunsPass(StressConfig.Impl impl) {
    for (int run = 1; run <= 20; run++) {
      StressReport report = StressHarness.run(StressConfig.standard(impl, run));
      System.out.printf(
          "%s run %2d: %,d ops (%,.0f ops/s), deadlock-free=%s, invariants=%s%n",
          impl,
          run,
          report.totalOps(),
          report.opsPerSec(),
          report.deadlockFree(),
          report.invariants().stream().map(r -> r.passed() ? "✔" : "✘ " + r.detail()).toList());
      assertThat(report.passed()).as("%s run %d: %s", impl, run, report).isTrue();
    }
  }
}

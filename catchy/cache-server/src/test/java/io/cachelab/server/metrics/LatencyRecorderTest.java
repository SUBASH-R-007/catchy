package io.cachelab.server.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class LatencyRecorderTest {

  @Test
  void emptyRecorderReportsZero() {
    LatencyRecorder r = new LatencyRecorder();
    assertThat(r.p50Micros()).isZero();
    assertThat(r.p99Micros()).isZero();
  }

  @Test
  void percentilesLandInTheRightBucket() {
    LatencyRecorder r = new LatencyRecorder();
    for (int i = 0; i < 980; i++) {
      r.record(1_000); // 1 µs
    }
    for (int i = 0; i < 20; i++) {
      r.record(2_000_000); // 2 ms
    }
    assertThat(r.p50Micros()).isCloseTo(1.0, within(0.07));
    assertThat(r.p99Micros()).isCloseTo(2_000.0, within(2_000 * 0.07));
    assertThat(r.percentileMicros(0.98)).isCloseTo(1.0, within(0.07));
  }

  @Test
  void uniformSpreadGivesMonotonicPercentiles() {
    LatencyRecorder r = new LatencyRecorder();
    for (int us = 1; us <= 1_000; us++) {
      r.record(us * 1_000L);
    }
    assertThat(r.p50Micros()).isCloseTo(500.0, within(500 * 0.07));
    assertThat(r.p99Micros()).isCloseTo(990.0, within(990 * 0.07));
  }

  @Test
  void outOfRangeValuesAreClamped() {
    LatencyRecorder r = new LatencyRecorder();
    r.record(-5);
    r.record(0);
    assertThat(r.p99Micros()).isLessThan(0.11);
    LatencyRecorder slow = new LatencyRecorder();
    slow.record(10_000_000_000L); // 10 s
    assertThat(slow.p50Micros()).isBetween(90_000.0, 100_000.0);
  }

  @Test
  void samplesLeaveTheWindowAfterTwentyRotations() {
    LatencyRecorder r = new LatencyRecorder();
    r.record(5_000_000); // 5 ms
    for (int i = 0; i < LatencyRecorder.SLICES - 1; i++) {
      r.rotate();
      r.record(1_000);
    }
    assertThat(r.p99Micros()).isGreaterThan(4_000); // the old sample is still inside the window
    r.rotate();
    assertThat(r.p99Micros()).isLessThan(1.1); // ... and gone after the 20th rotation
  }
}

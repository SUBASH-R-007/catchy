package io.cachelab.server.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HitRateWindowTest {

  @Test
  void emptyWindowReportsZero() {
    assertThat(new HitRateWindow().rate()).isZero();
  }

  @Test
  void rateCoversOnlyTheLastTicks() {
    HitRateWindow window = new HitRateWindow(2);
    window.record(0, 100); // falls out of the window below
    window.record(30, 70);
    window.record(90, 10);

    assertThat(window.rate()).isEqualTo(120.0 / 200.0);
  }

  @Test
  void rejectsInvalidInput() {
    assertThatThrownBy(() -> new HitRateWindow(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new HitRateWindow().record(-1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

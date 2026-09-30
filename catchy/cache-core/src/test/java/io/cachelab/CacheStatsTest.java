package io.cachelab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class CacheStatsTest {

  private static CacheStats stats(long hits, long misses) {
    return new CacheStats(hits, misses, 0, 0, 0, 0, 0, 0);
  }

  @Test
  void ratesAreZeroNotNanWithoutRequests() {
    CacheStats empty = CacheStats.empty();
    assertThat(empty.requestCount()).isZero();
    assertThat(empty.hitRate()).isZero();
    assertThat(empty.missRate()).isZero();
    assertThat(empty.averageLoadPenaltyNanos()).isZero();
  }

  @Test
  void derivesRatesFromHitsAndMisses() {
    CacheStats s = stats(81, 19);
    assertThat(s.requestCount()).isEqualTo(100);
    assertThat(s.hitRate()).isCloseTo(0.81, within(1e-12));
    assertThat(s.missRate()).isCloseTo(0.19, within(1e-12));
    assertThat(s.hitRate() + s.missRate()).isCloseTo(1.0, within(1e-12));
  }

  @Test
  void averageLoadPenaltyCountsFailuresToo() {
    CacheStats s = new CacheStats(0, 0, 0, 0, 3, 1, 4_000, 0);
    assertThat(s.loadCount()).isEqualTo(4);
    assertThat(s.averageLoadPenaltyNanos()).isEqualTo(1_000.0);
  }

  @Test
  void minusComputesIntervalDeltas() {
    CacheStats before = new CacheStats(10, 5, 2, 1, 3, 1, 700, 20);
    CacheStats after = new CacheStats(15, 9, 4, 1, 5, 2, 1_000, 26);
    assertThat(after.minus(before)).isEqualTo(new CacheStats(5, 4, 2, 0, 2, 1, 300, 6));
  }

  @Test
  void minusClampsAtZero() {
    assertThat(stats(1, 1).minus(stats(5, 5))).isEqualTo(CacheStats.empty());
  }

  @Test
  void plusSumsAndSaturates() {
    CacheStats a = new CacheStats(1, 2, 3, 4, 5, 6, 7, 8);
    assertThat(a.plus(a)).isEqualTo(new CacheStats(2, 4, 6, 8, 10, 12, 14, 16));
    assertThat(stats(Long.MAX_VALUE, 0).plus(stats(1, 0)).hitCount()).isEqualTo(Long.MAX_VALUE);
    assertThat(stats(Long.MAX_VALUE, Long.MAX_VALUE).requestCount()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void rejectsNegativeCounters() {
    assertThatThrownBy(() -> new CacheStats(0, -1, 0, 0, 0, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("missCount must not be negative, but was -1");
  }

  @Test
  void rejectsNullOperands() {
    assertThatThrownBy(() -> CacheStats.empty().minus(null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> CacheStats.empty().plus(null))
        .isInstanceOf(NullPointerException.class);
  }
}

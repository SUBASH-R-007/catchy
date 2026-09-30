package io.cachelab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cachelab.testing.FakeTicker;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CacheBuilderTest {

  private final CacheBuilder<String, String> builder = CacheBuilder.newBuilder();

  @Test
  void defaultsMatchTheSpec() {
    assertThat(builder.evictionPolicy()).isEqualTo(PolicyType.LRU);
    assertThat(builder.defaultTtl()).isEmpty();
    assertThat(builder.concurrencyLevel()).isEqualTo(1);
    assertThat(builder.sweepInterval()).isEqualTo(Duration.ofMillis(100));
    assertThat(builder.decayInterval()).isEqualTo(Duration.ofSeconds(10));
    assertThat(builder.ticker()).isSameAs(Ticker.system());
    assertThat(builder.removalListener()).isEmpty();
    assertThat(builder.removalExecutor()).isEmpty();
    assertThat(builder.accessObservers()).isEmpty();
  }

  @Test
  void defaultNameIsSequential() {
    String first = CacheBuilder.newBuilder().resolveName();
    String second = CacheBuilder.newBuilder().resolveName();
    assertThat(first).matches("cache-\\d+");
    assertThat(second).matches("cache-\\d+");
    int n1 = Integer.parseInt(first.substring("cache-".length()));
    int n2 = Integer.parseInt(second.substring("cache-".length()));
    assertThat(n2).isGreaterThan(n1);
  }

  @Test
  void explicitNameIsKept() {
    assertThat(builder.name("formulary").resolveName()).isEqualTo("formulary");
  }

  @Test
  void rejectsBlankName() {
    assertThatThrownBy(() -> builder.name("  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("name must not be blank, but was \"  \"");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void rejectsMaximumSizeBelowOne(int size) {
    assertThatThrownBy(() -> builder.maximumSize(size))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("maximumSize must be at least 1, but was " + size);
  }

  @Test
  void acceptsMaximumSizeOfOne() {
    assertThat(builder.maximumSize(1).maximumSize()).isEqualTo(1);
  }

  @Test
  void rejectsNonPositiveDefaultTtl() {
    assertThatThrownBy(() -> builder.defaultTtl(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("defaultTtl must be positive, but was 0 ms");
    assertThatThrownBy(() -> builder.defaultTtl(Duration.ofNanos(-5)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("defaultTtl must be positive, but was -5 ns");
  }

  @Test
  void acceptsPositiveDefaultTtl() {
    assertThat(builder.defaultTtl(Duration.ofMinutes(10)).defaultTtl())
        .contains(Duration.ofMinutes(10));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -2, 3, 6, 12, 1000})
  void rejectsConcurrencyLevelThatIsNotAPowerOfTwo(int level) {
    assertThatThrownBy(() -> builder.concurrencyLevel(level))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("concurrencyLevel must be a power of two of at least 1, but was " + level);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 16, 1024})
  void acceptsPowerOfTwoConcurrencyLevel(int level) {
    assertThat(builder.concurrencyLevel(level).concurrencyLevel()).isEqualTo(level);
  }

  @Test
  void rejectsSweepIntervalBelowTenMillis() {
    assertThatThrownBy(() -> builder.sweepInterval(Duration.ofMillis(9)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("sweepInterval must be at least 10 ms, but was 9 ms");
    assertThat(builder.sweepInterval(Duration.ofMillis(10)).sweepInterval())
        .isEqualTo(Duration.ofMillis(10));
  }

  @Test
  void rejectsNonPositiveDecayInterval() {
    assertThatThrownBy(() -> builder.decayInterval(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("decayInterval must be positive, but was 0 ms");
  }

  @Test
  void formatsHugeDurationsWithoutOverflow() {
    assertThatThrownBy(() -> builder.defaultTtl(Duration.ofSeconds(Long.MIN_VALUE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("defaultTtl must be positive, but was PT-");
  }

  @Test
  void rejectsNullsWithTheOptionName() {
    assertThatThrownBy(() -> builder.name(null)).hasMessage("name");
    assertThatThrownBy(() -> builder.evictionPolicy(null)).hasMessage("evictionPolicy");
    assertThatThrownBy(() -> builder.defaultTtl(null)).hasMessage("defaultTtl");
    assertThatThrownBy(() -> builder.removalListener(null)).hasMessage("removalListener");
    assertThatThrownBy(() -> builder.removalExecutor(null)).hasMessage("removalExecutor");
    assertThatThrownBy(() -> builder.accessObserver(null)).hasMessage("accessObserver");
    assertThatThrownBy(() -> builder.ticker(null)).hasMessage("ticker");
    assertThatThrownBy(() -> builder.sweepInterval(null)).hasMessage("sweepInterval");
    assertThatThrownBy(() -> builder.decayInterval(null)).hasMessage("decayInterval");
  }

  @Test
  void keepsConfiguredCollaborators() {
    FakeTicker ticker = new FakeTicker();
    Executor executor = Runnable::run;
    RemovalListener<Object, Object> listener = (k, v, cause) -> {};
    AccessObserver<Object> first = (k, hit) -> {};
    AccessObserver<String> second = (k, hit) -> {};

    builder
        .ticker(ticker)
        .removalExecutor(executor)
        .removalListener(listener)
        .accessObserver(first)
        .accessObserver(second)
        .evictionPolicy(PolicyType.LFU_DECAY);

    assertThat(builder.ticker()).isSameAs(ticker);
    assertThat(builder.removalExecutor()).containsSame(executor);
    assertThat(builder.removalListener()).containsSame(listener);
    assertThat(builder.accessObservers()).containsExactly(first, second);
    assertThat(builder.evictionPolicy()).isEqualTo(PolicyType.LFU_DECAY);
  }

  @Test
  void buildRequiresMaximumSize() {
    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("maximumSize must be set before build()");
  }

  @Test
  void buildRejectsConcurrencyLevelAboveMaximumSize() {
    builder.maximumSize(8).concurrencyLevel(16);
    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("concurrencyLevel (16) must not exceed maximumSize (8)");
  }

  @Test
  void buildsAWorkingSingleLockCache() {
    try (Cache<String, String> cache =
        builder.name("built").maximumSize(2).evictionPolicy(PolicyType.LFU).build()) {
      cache.put("k", "v");
      assertThat(cache.get("k")).contains("v");
      assertThat(cache.name()).isEqualTo("built");
      assertThat(cache.policyType()).isEqualTo(PolicyType.LFU);
    }
  }

  @Test
  void hugeDurationsSaturateInsteadOfOverflowing() {
    try (Cache<String, String> cache =
        builder
            .maximumSize(1)
            .defaultTtl(Duration.ofSeconds(Long.MAX_VALUE))
            .decayInterval(Duration.ofSeconds(Long.MAX_VALUE))
            .build()) {
      cache.put("k", "v");
      assertThat(cache.get("k")).contains("v");
      assertThat(cache.ttlRemaining("k")).isPresent();
    }
  }
}

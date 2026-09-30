package io.cachelab.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.cachelab.Cache;
import io.cachelab.CacheBuilder;
import io.cachelab.PolicySnapshot;
import io.cachelab.PolicyType;
import io.cachelab.testing.FakeTicker;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SegmentedCacheTest {

  private static SegmentedCache<String, String> segmented(
      String name, int maximumSize, int segments, PolicyType policy) {
    return (SegmentedCache<String, String>)
        CacheBuilder.<String, String>newBuilder()
            .name(name)
            .maximumSize(maximumSize)
            .concurrencyLevel(segments)
            .evictionPolicy(policy)
            .ticker(new FakeTicker())
            .build();
  }

  @Test
  void theBuilderCreatesASegmentedCacheForConcurrencyAboveOne() {
    try (Cache<String, String> c =
        CacheBuilder.<String, String>newBuilder().maximumSize(100).concurrencyLevel(8).build()) {
      assertThat(c).isInstanceOf(SegmentedCache.class);
    }
  }

  @Test
  void segmentCapacitiesAddUpExactlyToTheMaximum() {
    try (SegmentedCache<String, String> c = segmented("split", 10, 4, PolicyType.LRU)) {
      assertThat(c.segments()).extracting(BoundedCache::maximumSize).containsExactly(3, 3, 2, 2);
      c.checkInvariants();
    }
  }

  @Test
  void sizeNeverExceedsTheMaximumAndStatsAreSummed() {
    try (SegmentedCache<String, String> c = segmented("bound", 64, 16, PolicyType.LFU)) {
      for (int i = 0; i < 5_000; i++) {
        c.put("k" + i, "v");
        assertThat(c.size()).isLessThanOrEqualTo(64);
      }
      for (int i = 4_990; i < 5_000; i++) {
        c.get("k" + i);
      }
      c.checkInvariants();
      assertThat(c.stats().putCount()).isEqualTo(5_000);
      assertThat(c.stats().requestCount()).isEqualTo(10);
      assertThat(c.stats().evictionCount()).isEqualTo(5_000 - c.size());
    }
  }

  @Test
  void keysAlwaysRouteToTheSameSegment() {
    try (SegmentedCache<String, String> c = segmented("route", 1_000, 8, PolicyType.LRU)) {
      c.put("drug:1", "a");
      c.put("drug:1", "b", Duration.ofSeconds(5));
      assertThat(c.get("drug:1")).contains("b");
      assertThat(c.ttlRemaining("drug:1")).contains(Duration.ofSeconds(5));
      assertThat(c.getOrLoad("drug:2", k -> "loaded")).isEqualTo("loaded");
      assertThat(c.remove("drug:1")).isTrue();
      assertThat(c.size()).isEqualTo(1);
      c.clear();
      assertThat(c.size()).isZero();
    }
  }

  @Test
  void lruSnapshotsMergeSegmentsByGlobalRecency() {
    try (SegmentedCache<String, String> c = segmented("lru-merge", 100, 4, PolicyType.LRU)) {
      for (int i = 0; i < 12; i++) {
        c.put("k" + i, "v");
      }
      c.get("k3");
      c.get("k7");
      List<String> keys =
          c.policySnapshot(4).entries().stream().map(PolicySnapshot.Entry::key).toList();
      assertThat(keys).containsExactly("k7", "k3", "k11", "k10");
      assertThat(c.entries(2)).extracting(e -> e.key()).containsExactly("k7", "k3");
    }
  }

  @Test
  void lfuSnapshotsMergeSegmentsByFrequency() {
    try (SegmentedCache<String, String> c = segmented("lfu-merge", 100, 4, PolicyType.LFU)) {
      for (int i = 0; i < 8; i++) {
        c.put("k" + i, "v");
      }
      for (int i = 0; i < 3; i++) {
        c.get("k5");
      }
      c.get("k2");
      assertThat(c.policySnapshot(3).entries())
          .containsExactly(
              new PolicySnapshot.Entry<>("k5", 4),
              new PolicySnapshot.Entry<>("k2", 2),
              new PolicySnapshot.Entry<>("k7", 1));
    }
  }

  @Test
  void switchPolicySwitchesEverySegment() {
    try (SegmentedCache<String, String> c = segmented("switch", 64, 4, PolicyType.LRU)) {
      for (int i = 0; i < 40; i++) {
        c.put("k" + i, "v");
      }
      c.switchPolicy(PolicyType.LFU_DECAY);
      assertThat(c.policyType()).isEqualTo(PolicyType.LFU_DECAY);
      assertThat(c.segments())
          .allSatisfy(s -> assertThat(s.policyType()).isEqualTo(PolicyType.LFU_DECAY));
      assertThat(c.size()).isEqualTo(40);
      c.checkInvariants();
    }
  }

  @Test
  void oneSweeperThreadServesAllSegments() {
    try (SegmentedCache<String, String> c = segmented("one-sweeper", 64, 16, PolicyType.LRU)) {
      long sweepers =
          Thread.getAllStackTraces().keySet().stream()
              .filter(t -> t.getName().startsWith("cachelab-sweeper-one-sweeper"))
              .count();
      assertThat(sweepers).isEqualTo(1);
      assertThat(c.name()).isEqualTo("one-sweeper");
    }
  }
}

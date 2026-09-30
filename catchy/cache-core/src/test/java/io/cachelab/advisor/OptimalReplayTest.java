package io.cachelab.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cachelab.PolicyType;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class OptimalReplayTest {

  @Test
  void matchesAHandComputedExample() {
    // capacity 2: a b c a b c a -> MIN keeps the key used soonest
    List<String> trace = List.of("a", "b", "c", "a", "b", "c", "a");
    OptimalReplay.Result r = OptimalReplay.replay(trace, 2);
    // a miss, b miss, c miss (evict b: next b at 4 vs a at 3), a hit, b miss (evict a? next a at 6,
    // c at 5 -> evict a), c hit, a miss
    assertThat(r.hits()).isEqualTo(2);
    assertThat(r.misses()).isEqualTo(5);
    assertThat(r.hitRate()).isEqualTo(2.0 / 7);
  }

  @Test
  void aLoopThatFitsIsAllHitsAfterWarmUp() {
    List<Integer> trace = new ArrayList<>();
    for (int round = 0; round < 10; round++) {
      for (int k = 0; k < 50; k++) {
        trace.add(k);
      }
    }
    OptimalReplay.Result r = OptimalReplay.replay(trace, 50);
    assertThat(r.misses()).isEqualTo(50);
  }

  @Test
  void aLoopLargerThanTheCacheStillHitsOften() {
    // LRU gets 0 % on a cyclic loop of capacity + 1; MIN keeps most of the loop.
    List<Integer> trace = new ArrayList<>();
    for (int round = 0; round < 20; round++) {
      for (int k = 0; k < 110; k++) {
        trace.add(k);
      }
    }
    double optimal = OptimalReplay.hitRate(trace, 100);
    double lru = ShadowCache.replay(trace, 100, PolicyType.LRU).hitRate();
    assertThat(lru).isZero();
    assertThat(optimal).isGreaterThan(0.8);
  }

  @Test
  void optimalIsAnUpperBoundForEveryPolicyOn100RandomTraces() {
    for (int seed = 0; seed < 100; seed++) {
      SplittableRandom rnd = new SplittableRandom(seed);
      int capacity = 5 + rnd.nextInt(60);
      List<Integer> trace = randomTrace(rnd, seed % 3, 3_000);
      double optimal = OptimalReplay.hitRate(trace, capacity);
      for (PolicyType policy : PolicyType.values()) {
        double shadow = ShadowCache.replay(trace, capacity, policy).hitRate();
        assertThat(optimal)
            .as("seed %d (%s, capacity %d): optimal vs %s", seed, kind(seed % 3), capacity, policy)
            .isGreaterThanOrEqualTo(shadow);
      }
    }
  }

  @Test
  void handlesEmptyTracesAndRejectsBadCapacity() {
    assertThat(OptimalReplay.hitRate(List.of(), 10)).isZero();
    assertThatThrownBy(() -> OptimalReplay.hitRate(List.of("a"), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static List<Integer> randomTrace(SplittableRandom rnd, int kind, int length) {
    List<Integer> trace = new ArrayList<>(length);
    int keySpace = 20 + rnd.nextInt(400);
    double[] cdf = zipfCdf(keySpace, 1.0);
    int loop = 10 + rnd.nextInt(120);
    for (int i = 0; i < length; i++) {
      trace.add(
          switch (kind) {
            case 0 -> rnd.nextInt(keySpace);
            case 1 -> sample(cdf, rnd.nextDouble());
            default -> i % loop;
          });
    }
    return trace;
  }

  private static String kind(int kind) {
    return switch (kind) {
      case 0 -> "uniform";
      case 1 -> "zipf";
      default -> "loop";
    };
  }

  private static double[] zipfCdf(int n, double s) {
    double[] cdf = new double[n];
    double sum = 0;
    for (int i = 0; i < n; i++) {
      sum += 1 / Math.pow(i + 1, s);
      cdf[i] = sum;
    }
    for (int i = 0; i < n; i++) {
      cdf[i] /= sum;
    }
    return cdf;
  }

  private static int sample(double[] cdf, double u) {
    int lo = 0;
    int hi = cdf.length - 1;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (cdf[mid] < u) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }
}
